package com.personyze.android.internal.core

import com.personyze.android.PersonyzeAction
import com.personyze.android.PersonyzeApi
import com.personyze.android.PersonyzeCondition
import com.personyze.android.PersonyzeErrorContext
import com.personyze.android.PersonyzeException
import com.personyze.android.PersonyzeException.Delivery.Reason
import com.personyze.android.PersonyzeException.Transport.Kind
import com.personyze.android.PersonyzeNotification
import com.personyze.android.PersonyzePlaceholder
import com.personyze.android.PersonyzePushStatus
import com.personyze.android.PersonyzeResult
import com.personyze.android.PersonyzeVerdict
import com.personyze.android.PersonyzeVisitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import kotlin.random.Random

internal class EngineConfig
(	val apiKey: String,
	val baseUrl: String,
	val assetsUrl: String,
	val timeoutMs: Long,
	val backgroundTimeoutMs: Long,
	val retries: Int,
	/**	Null: queued events wait for a navigation, `flush()` or the app leaving the foreground. **/
	val autoFlushDelayMs: Long?,
	val userAgent: String
)

/**	The client's state machine. Pure Kotlin - no Android - so all of it runs in JVM tests.

	**Threads.** Event calls come from any thread, the main one included, and only append to the
	queue under [lock] (short sections, no I/O). One tracker round trip at a time ([wire]): each
	request carries the session the previous response returned, so two in flight would fork it.
	Template fetches ([meta]) run outside [wire]. Disk is touched only from [io].

	**What makes a request** (SPEC section 3): the visitor (`user_id`) is the app's user id or the
	install's anonymous id; a stored session of another visitor is no session; `new_session` is
	computed - a forced new session, no session, or one older than 90 minutes - never constant.

	**What survives** a failure or the app being killed (SPEC section 5 - the endpoint is not
	idempotent, and a purchase counted twice is worse than one missing): queued events are on disk.
	A request that surely never left the device (no network, name lookup, connect, TLS) keeps its
	events for later, except a navigation - a page view sent hours late is a wrong page view. A
	request that may have arrived is not sent again beyond the one retry inside the budget, and the
	failure reaches `onError` with the commands lost.
 **/
internal class Engine
(	private val config: EngineConfig,
	private val platform: Platform,
	private val store: Store,
	private val cacheStore: Store,
	transport: Transport,
	private val scope: CoroutineScope,
	private val io: CoroutineDispatcher,
	private val legacy: LegacySource?,
	private val onError: (PersonyzeException, PersonyzeErrorContext) -> Unit,
	private val owner: () -> PersonyzeApi?
)
{	private val rest = RestClient(transport, config.apiKey, config.baseUrl, config.retries, platform::monotonicMs, config.userAgent)
	private val apiKeyFp = sha256(config.apiKey).take(16)

	private val lock = Any()
	private val persistLock = Any()
	private val metaPersistLock = Any()
	private val wire = Mutex()
	private val meta = Mutex()
	/**	One pass over the notification reports at a time, so none goes twice. **/
	private val reportWire = Mutex()
	/**	One push registration at a time. **/
	private val pushWire = Mutex()
	private val loaded = CompletableDeferred<Unit>()

	// Guarded by `lock`.
	private var isLoaded = false
	private val deferredOps = ArrayList<() -> Unit>()
	private var state = PersistedState()
	private val pending = ArrayList<PendingItem>()
	private var seq = 1L
	private var installVisitor = 0
	private var metadata = MetadataFile()
	private var autoFlushJob: Job? = null

	private val resultFlow = MutableStateFlow(PersonyzeResult.EMPTY)
	private val visitorFlow = MutableStateFlow(PersonyzeVisitor(null, null, null))
	private val persistSignal = Channel<Unit>(Channel.CONFLATED)

	val result: StateFlow<PersonyzeResult> = resultFlow
	val visitor: StateFlow<PersonyzeVisitor> = visitorFlow
	private val pushFlow = MutableStateFlow(PersonyzePushStatus(null, null, 0, false))
	val push: StateFlow<PersonyzePushStatus> = pushFlow

	init
	{	scope.launch(io) {
			try
			{	load()
			}
			catch (e: Exception)
			{	platform.log("Personyze: could not load the stored state: $e")
				synchronized(lock) { markLoadedLocked() }
			}
		}
		scope.launch(io) {
			for (signal in persistSignal)
			{	persistNow()
			}
		}
	}

	// ---------------------------------------------------------------------------------------- load

	private fun load()
	{	val storedVisitor = store.read(VISITOR)?.trim()?.toIntOrNull()?.takeIf { it != 0 }
		var st = store.read(STATE)?.let { text ->
			try
			{	Wire.json.decodeFromString(PersistedState.serializer(), text)
			}
			catch (e: Exception)
			{	platform.log("Personyze: state.json is corrupt, starting over (kept as state.json.corrupt)")
				store.quarantine(STATE)
				null
			}
		}
		var outbox = store.read(OUTBOX)?.let { text ->
			try
			{	Wire.json.decodeFromString(OutboxFile.serializer(), text).items
			}
			catch (e: Exception)
			{	null
			}
		}.orEmpty()
		var installId = storedVisitor
		var migrated = false
		if (st == null && legacy != null)
		{	val old = try
			{	legacy.read()
			}
			catch (e: Exception)
			{	null
			}
			if (old != null)
			{	// The v1 visitor id is kept even below 500,000,000: it is the visitor's history and A/B arms.
				installId = installId ?: old.userId.takeIf { it != 0 }
				val sameKey = old.apiKeyHash == config.apiKey.hashCode()
				st = PersistedState(
					apiKeyFp = apiKeyFp,
					session = if (sameKey) old.session else null,
					pastSessions = if (sameKey) old.pastSessionsOldestFirst.reversed().take(PAST_SESSIONS) else emptyList(),
					blocked = if (sameKey) old.blocked else emptyMap(),
					epoch = if (sameKey && old.newSession) 1 else 0,
					notifications = PersistedNotifications(lastCheckMs = old.notiLastCheckMs, primeOnNextPoll = old.notiLastCheckMs > 0)
				)
				migrated = true
			}
		}
		if (installId == null)
		{	installId = Random.nextInt(MIN_ANONYMOUS_ID, Int.MAX_VALUE)
		}
		if (installId != storedVisitor)
		{	store.write(VISITOR, installId.toString(), sync = true)
		}
		st = st ?: PersistedState(apiKeyFp = apiKeyFp)
		if (st.apiKeyFp != null && st.apiKeyFp != apiKeyFp)
		{	// Another account: its session, history and queue mean nothing here.
			st = PersistedState(apiKeyFp = apiKeyFp, userId = st.userId)
			outbox = emptyList()
		}
		st = st.copy(apiKeyFp = apiKeyFp)
		val cached = cacheStore.read(METADATA)?.let { text ->
			try
			{	Wire.json.decodeFromString(MetadataFile.serializer(), text)
			}
			catch (e: Exception)
			{	null
			}
		}
		val md = cached?.takeIf { it.apiKeyFp == apiKeyFp && it.cacheVersion == st.cacheVersion } ?: MetadataFile(apiKeyFp = apiKeyFp, cacheVersion = st.cacheVersion)
		val interrupted = outbox.filter { it.inflight }
		val kept = outbox.filter { !it.inflight && !it.navigate }
		synchronized(lock) {
			state = st
			metadata = md
			installVisitor = installId
			pending.clear()
			pending.addAll(kept)
			seq = (kept.maxOfOrNull { it.seq } ?: 0) + 1
			markLoadedLocked()
		}
		if (migrated || interrupted.isNotEmpty())
		{	persistNow()
		}
		if (migrated)
		{	try
			{	legacy?.delete()
			}
			catch (e: Exception)
			{	platform.log("Personyze: could not delete the v1 state: $e")
			}
		}
		if (interrupted.isNotEmpty())
		{	report(
				PersonyzeException.Delivery(Reason.INTERRUPTED, interrupted.map { it.cmd.map { a -> a.orEmpty() } }, "The app stopped while ${interrupted.size} events were being sent; they may or may not have arrived, and are not sent again"),
				PersonyzeErrorContext("flush", dropped = interrupted.size)
			)
		}
		if (needsMetadata())
		{	scope.launch(io) { fetchMetadata(config.backgroundTimeoutMs) }
		}
		synchronized(lock) {
			if (pending.isNotEmpty())
			{	scheduleAutoFlushLocked()
			}
		}
	}

	private fun markLoadedLocked()
	{	isLoaded = true
		val ops = ArrayList(deferredOps)
		deferredOps.clear()
		for (op in ops)
		{	op()
		}
		emitLocked()
		loaded.complete(Unit)
	}

	/**	Run [op] under the lock now, or - before the stored state is loaded - right after loading,
		in call order. Events queued during startup are tagged with the visitor the app set, not the
		one on disk before it.
	 **/
	private fun mutate(op: () -> Unit)
	{	synchronized(lock) {
			if (!isLoaded)
			{	deferredOps.add(op)
			}
			else
			{	op()
			}
		}
	}

	// -------------------------------------------------------------------------------------- events

	fun enqueue(raw: Boolean = false, build: () -> Command)
	{	val cmd = try
		{	build()
		}
		catch (e: IllegalArgumentException)
		{	report(PersonyzeException.Invalid(e.message ?: "invalid input"), PersonyzeErrorContext("track", dropped = 1))
			return
		}
		mutate {
			addLocked(cmd, raw)
			persistSoon()
			scheduleAutoFlushLocked()
		}
	}

	private fun addLocked(cmd: Command, raw: Boolean, navigate: Boolean = false): PendingItem?
	{	if (Commands.isActionStatus(cmd) && !acceptStatusLocked(cmd))
		{	return null
		}
		val user = currentUserLocked()
		val item = PendingItem(
			cmd = cmd,
			user = user,
			epoch = state.epoch,
			sessionStart = if (Session.isSessionOf(state.session, userValue(user))) Session.startTime(state.session) else 0,
			queuedAtMs = platform.nowMs(),
			raw = raw,
			navigate = navigate,
			seq = seq++
		)
		pending.add(item)
		enforceCapLocked()
		return item
	}

	/**	An action is reported once per page for each status, and `executed` not after any other
		(the v1 rule). Remembered on disk, so the screen a restarted app restores is not counted
		again. A close also takes the action out of the result, and with N > 0 blocks it for N
		sessions - Personyze keeps no such state for an app; the SDK does.
	 **/
	private fun acceptStatusLocked(cmd: Command): Boolean
	{	val actionId = cmd[1]?.toIntOrNull() ?: return true
		val status = cmd[2] ?: return true
		val reported = state.pageReports[actionId].orEmpty()
		// `dont-show` once per page too: a server answering the blocked action again would otherwise
		// be answered with another report, and that with the action again, without end.
		if (status == "executed" && reported.any { it != "dont-show" } || status in reported)
		{	return false
		}
		state = state.copy(pageReports = state.pageReports + (actionId to reported + status))
		if (status == "close")
		{	val sessions = cmd.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
			if (sessions > 0)
			{	state = state.copy(blocked = state.blocked + (actionId to sessions))
			}
			state.result?.let { r ->
				if (r.actions.any { it.id == actionId })
				{	state = state.copy(result = r.copy(actions = r.actions.filter { it.id != actionId }))
					emitLocked()
				}
			}
		}
		return true
	}

	private fun enforceCapLocked()
	{	var total = pending.sumOf { Commands.jsonLength(it.cmd) }
		val dropped = ArrayList<PendingItem>()
		while (pending.size > MAX_PENDING || total > MAX_PENDING_CHARS)
		{	val i = pending.indexOfFirst { !it.navigate && !it.inflight }
			if (i == -1)
			{	break
			}
			val item = pending.removeAt(i)
			total -= Commands.jsonLength(item.cmd)
			dropped.add(item)
		}
		if (dropped.isNotEmpty())
		{	report(
				PersonyzeException.Delivery(Reason.OVERFLOW, dropped.map { it.cmd.map { a -> a.orEmpty() } }, "Too many events waiting to be sent; the oldest ${dropped.size} were dropped"),
				PersonyzeErrorContext("track", dropped = dropped.size)
			)
		}
	}

	private fun scheduleAutoFlushLocked()
	{	val delayMs = config.autoFlushDelayMs ?: return
		autoFlushJob?.cancel()
		autoFlushJob = scope.launch(io) {
			delay(delayMs)
			flushLive(config.backgroundTimeoutMs, "flush")
		}
	}

	// ------------------------------------------------------------------------------------ decisions

	suspend fun navigate(document: String, events: (() -> Unit)?): PersonyzeResult = withContext(io) {
		val navCmd = try
		{	Commands.navigate(document)
		}
		catch (e: IllegalArgumentException)
		{	val error = PersonyzeException.Invalid(e.message ?: "invalid document")
			report(error, PersonyzeErrorContext("navigate"))
			return@withContext PersonyzeResult.failed(error)
		}
		loaded.await()
		val deadline = platform.monotonicMs() + config.timeoutMs
		val (navItem, throttled) = synchronized(lock) {
			if (platform.nowMs() < state.throttledUntilMs)
			{	null to true
			}
			else
			{	state = state.copy(pageReports = emptyMap())
				addLocked(navCmd, raw = false, navigate = true) to false
			}
		}
		events?.invoke()
		persistSoon()
		if (throttled || navItem == null)
		{	val error = PersonyzeException.Transport(429, Kind.RATE_LIMITED, "Personyze paused this account's tracking (HTTP 429); not asking again for a few minutes")
			return@withContext failNavigation(error, null)
		}
		val drained = underWire(deadline) { drain(deadline, live = false, operation = "navigate", navSeq = navItem.seq) }
		if (drained == null)
		{	val error = PersonyzeException.Transport(null, Kind.TIMEOUT, "Personyze did not answer within ${config.timeoutMs}ms (an earlier request was still in flight)")
			report(error, PersonyzeErrorContext("navigate"))
			return@withContext failNavigation(error, navItem.seq)
		}
		if (!drained.navigateDone)
		{	return@withContext failNavigation(drained.navigateError ?: PersonyzeException.Transport(null, Kind.TIMEOUT, "Personyze did not answer within ${config.timeoutMs}ms"), navItem.seq)
		}
		val fetch = scope.launch(io) { fetchMetadata(config.backgroundTimeoutMs) }
		withTimeoutOrNull((deadline - platform.monotonicMs()).coerceAtLeast(1)) { fetch.join() }
		resultFlow.value
	}

	/**	No decision for this screen: an empty one (SPEC section 8 - the last screen's actions are not
		this one's), and the navigation is not kept for later.
	 **/
	private fun failNavigation(error: PersonyzeException, navSeq: Long?): PersonyzeResult
	{	synchronized(lock) {
			if (navSeq != null)
			{	pending.removeAll { it.seq == navSeq }
			}
			state = state.copy(result = null)
			resultFlow.value = PersonyzeResult.failed(error)
		}
		persistSoon()
		return PersonyzeResult.failed(error)
	}

	suspend fun refresh(): PersonyzeResult = withContext(io) {
		loaded.await()
		val needed = synchronized(lock) { pending.any { !it.navigate } || state.result == null }
		if (!needed)
		{	return@withContext resultFlow.value
		}
		val deadline = platform.monotonicMs() + config.timeoutMs
		val outcome = underWire(deadline) {
			val drained = drain(deadline, live = false, operation = "refresh", navSeq = null)
			val (empty, user, epoch) = synchronized(lock) { Triple(state.result == null && !throttledLocked(), currentUserLocked(), state.epoch) }
			if (drained.error == null && !drained.sentAny && empty)
			{	// Nothing queued and no decision yet: ask for the visitor's current state (v1 did the same).
				Holder(sendBatch(Batch(emptyList(), user, epoch), deadline, "refresh"))
			}
			else
			{	Holder(drained.error)
			}
		}
		val error = if (outcome == null) PersonyzeException.Transport(null, Kind.TIMEOUT, "Personyze did not answer within ${config.timeoutMs}ms (an earlier request was still in flight)") else outcome.value
		val fetch = scope.launch(io) { fetchMetadata(config.backgroundTimeoutMs) }
		withTimeoutOrNull((deadline - platform.monotonicMs()).coerceAtLeast(1)) { fetch.join() }
		val current = resultFlow.value
		if (error == null) current else PersonyzeResult(current.actions, current.conditions, current.dismissedActions, current.dismissedConditions, current.sessionId, current.cacheVersion, current.rawJson, error)
	}

	/**	Send what is queued, now. **/
	suspend fun flush(): Unit = withContext(io) {
		loaded.await()
		val deadline = platform.monotonicMs() + config.backgroundTimeoutMs
		underWire(deadline) { drain(deadline, live = false, operation = "flush", navSeq = null) }
	}

	/**	Send what is queued if it can go into the live session - otherwise it waits for the next
		navigation, which opens the new session with a page. Events sent alone into a new session
		would log a visit with no page. Answers false when events stayed for lack of a network.
	 **/
	suspend fun flushLive(budgetMs: Long, operation: String): Boolean = withContext(io) {
		loaded.await()
		val deadline = platform.monotonicMs() + budgetMs
		val drained = underWire(deadline) { drain(deadline, live = true, operation = operation, navSeq = null) }
		drained != null && !drained.keptForLater
	}

	private class Holder<T>(val value: T)

	/**	Run [block] holding [wire], in the engine's own scope: a caller cancelled mid-request (its
		screen closed) must not abandon a request half done - its events would stay marked in flight.
		Null when [wire] was not free before [deadline].
	 **/
	private suspend fun <T : Any> underWire(deadline: Long, block: suspend () -> T): T? =
		scope.async(io) {
			val locked = withTimeoutOrNull((deadline - platform.monotonicMs()).coerceAtLeast(1)) { wire.lock() }
			if (locked == null)
			{	null
			}
			else
			{	try
				{	block()
				}
				finally
				{	wire.unlock()
				}
			}
		}.await()

	fun hasPending(): Boolean = synchronized(lock) { pending.any { !it.navigate } }

	private class Batch(val items: List<PendingItem>, val user: JsonPrimitive, val epoch: Int)
	{	val hasNavigate: Boolean = items.any { it.navigate }
	}

	private class Drained
	{	var navigateDone = false
		var navigateError: PersonyzeException? = null
		var error: PersonyzeException? = null
		var sentAny = false
		var keptForLater = false
	}

	/**	Send the queue in order, a batch per request, until it is empty or a request fails. Called
		holding [wire].
	 **/
	private suspend fun drain(deadline: Long, live: Boolean, operation: String, navSeq: Long?): Drained
	{	val drained = Drained()
		while (true)
		{	val batch = synchronized(lock) { takeBatchLocked(live, operation, navSeq) } ?: break
			persistNow()
			val error = sendBatch(batch, deadline, operation)
			drained.sentAny = true
			val hadNavigation = navSeq != null && batch.items.any { it.seq == navSeq }
			if (hadNavigation)
			{	drained.navigateDone = error == null
				drained.navigateError = error
			}
			if (error != null)
			{	drained.error = error
				if (navSeq != null && drained.navigateError == null)
				{	drained.navigateError = error
				}
				drained.keptForLater = synchronized(lock) { pending.any { !it.navigate && !it.inflight } }
				break
			}
			if (hadNavigation)
			{	// The screen waits for this answer, not for the rest of the queue.
				synchronized(lock) {
					if (pending.any { !it.navigate })
					{	scheduleAutoFlushLocked()
					}
				}
				break
			}
		}
		return drained
	}

	/**	The next request's commands: from the front of the queue, all for one visitor and epoch,
		at most [Commands.MAX_BODY] characters; a `track()` command alone. Marked in flight. Drops
		first what may no longer be sent (SPEC section 5's replay rules, applied to a queue that can
		outlive the app):

		* events of a visitor who is no longer current, unless that visitor's session is still the
		  live one - sending them would open a session for a visitor who left;
		* action reports outside the session they were made in: "executed" counted in a session that
		  never showed the action;
		* anything a day old.
	 **/
	private fun takeBatchLocked(live: Boolean, operation: String, navSeq: Long?): Batch?
	{	if (throttledLocked())
		{	return null
		}
		val now = platform.nowMs()
		val serverNow = now + state.clockOffsetMs
		val current = currentUserLocked()
		val drops = HashMap<Reason, MutableList<PendingItem>>()
		val iter = pending.iterator()
		while (iter.hasNext())
		{	val item = iter.next()
			if (item.inflight || item.navigate)
			{	continue
			}
			val ownSession = Session.isSessionOf(state.session, userValue(item.user)) && state.session != null
			val liveOwnSession = ownSession && !Session.isExpired(state.session, serverNow) && item.epoch == state.sentEpoch
			val reason = when
			{	item.user != current && !liveOwnSession -> Reason.OTHER_VISITOR
				Commands.isActionStatus(item.cmd) && item.cmd[2] != "dont-show" && item.sessionStart != 0L && (!liveOwnSession || Session.startTime(state.session) != item.sessionStart) -> Reason.OTHER_SESSION
				now - item.queuedAtMs > MAX_AGE_MS -> Reason.EXPIRED
				else -> null
			}
			if (reason != null)
			{	iter.remove()
				drops.getOrPut(reason) { ArrayList() }.add(item)
			}
		}
		for ((reason, items) in drops)
		{	report(
				PersonyzeException.Delivery(reason, items.map { it.cmd.map { a -> a.orEmpty() } }, "${items.size} queued events can no longer be sent (${reason.name.lowercase().replace('_', ' ')})"),
				PersonyzeErrorContext(operation, dropped = items.size)
			)
		}
		val first = pending.firstOrNull { !it.inflight } ?: return null
		if (first.navigate && first.seq != navSeq)
		{	return null	// another caller's navigation, waiting for its turn: it sends from here on
		}
		if (live)
		{	val liveSession = state.session != null && Session.isSessionOf(state.session, userValue(first.user)) &&
				!Session.isExpired(state.session, serverNow) && first.epoch == state.sentEpoch
			if (!liveSession)
			{	return null
			}
		}
		val items = ArrayList<PendingItem>()
		var size = BODY_OVERHEAD
		for (item in pending)
		{	if (item.inflight)
			{	continue
			}
			if (item.navigate && item.seq != navSeq)
			{	break
			}
			if (item.user != first.user || item.epoch != first.epoch || item.raw != first.raw || (first.raw && items.isNotEmpty()))
			{	break
			}
			val len = Commands.jsonLength(item.cmd) + 1
			if (items.isNotEmpty() && size + len > Commands.MAX_BODY)
			{	break
			}
			// The events after a navigation go in its request, attributed to the new screen.
			items.add(item)
			size += len
		}
		val ids = items.mapTo(HashSet()) { it.seq }
		for (i in pending.indices)
		{	if (pending[i].seq in ids)
			{	pending[i] = pending[i].copy(inflight = true)
			}
		}
		return Batch(items.map { it.copy(inflight = true) }, first.user, first.epoch)
	}

	private class Sent(val body: String, val session: String?, val newSession: Boolean)

	/**	One tracker request. Answers null on success, else the error (already reported). **/
	private suspend fun sendBatch(batch: Batch, deadline: Long, operation: String): PersonyzeException?
	{	val device = platform.deviceContext()
		val showable = platform.notificationsShowable()
		val sent = synchronized(lock) {
			var session = state.session
			if (!Session.isSessionOf(session, userValue(batch.user)))
			{	session = null
			}
			val serverNow = platform.nowMs() + state.clockOffsetMs
			val newSession = batch.epoch > state.sentEpoch || session == null || Session.isExpired(session, serverNow)
			// Newest first, the current session included - as the web tag sends them (stat-track-lib
			// `get_ps`) and as the rule engine counts them (`pastSessionsCount` reads from the front).
			val past = if (newSession) (listOf(serverNow/1000) + state.pastSessions).take(PAST_SESSIONS) else state.pastSessions
			Sent(Wire.requestBody(batch.user, session, newSession, past, device, showable, batch.items.map { it.cmd }), session, newSession)
		}
		val remaining = deadline - platform.monotonicMs()
		val outcome = if (remaining <= 0)
		{	RestResult.Failed(PersonyzeException.Transport(null, Kind.TIMEOUT, "Personyze did not answer within the time budget"), false)
		}
		else
		{	rest.call("POST", Endpoints.TRACKER, sent.body, remaining)
		}
		when (outcome)
		{	is RestResult.Ok ->
			{	val response = Wire.parseResponse(outcome.body)
				if (response == null)
				{	val error = PersonyzeException.Transport(200, Kind.MALFORMED_RESPONSE, "Personyze answered something that is not the tracker's JSON")
					dropBatch(batch, error, Reason.AMBIGUOUS, operation)
					return error
				}
				applyResponse(batch, sent, response, outcome.dateMs)
				return null
			}
			is RestResult.Failed ->
			{	val error = outcome.error
				when
				{	error is PersonyzeException.Transport && error.kind == Kind.RATE_LIMITED ->
					{	synchronized(lock) { state = state.copy(throttledUntilMs = platform.nowMs() + THROTTLE_MS) }
						dropBatch(batch, error, Reason.REFUSED, operation)
					}
					error is PersonyzeException.Auth || error is PersonyzeException.BadRequest -> dropBatch(batch, error, Reason.REFUSED, operation)
					!outcome.maybeDelivered -> keepBatch(batch, error, operation)
					else -> dropBatch(batch, error, Reason.AMBIGUOUS, operation)
				}
				return error
			}
		}
	}

	private fun applyResponse(batch: Batch, sent: Sent, response: Wire.TrackerResponse, dateMs: Long?)
	{	synchronized(lock) {
			var st = state
			if (dateMs != null)
			{	val offset = dateMs - platform.nowMs()
				st = st.copy(clockOffsetMs = if (kotlin.math.abs(offset) > CLOCK_SKEW_MS) offset else 0)
			}
			// Always the newest (SPEC section 3, rule 2) - v1 kept the first of each session.
			val session = response.sessionId ?: sent.session
			val started = session != null && Session.startTime(session) != Session.startTime(sent.session)
			if (started)
			{	val start = Session.startTime(session)
				st = st.copy(
					blocked = st.blocked.mapValues { it.value - 1 }.filterValues { it > 0 },
					pastSessions = (listOf(start) + st.pastSessions.filter { it != start }).take(PAST_SESSIONS),
					pageReports = if (batch.hasNavigate) st.pageReports else emptyMap()
				)
			}
			st = st.copy(session = session, sentEpoch = maxOf(st.sentEpoch, batch.epoch))
			if (response.cacheVersion > 0 && response.cacheVersion != st.cacheVersion)
			{	metadata = MetadataFile(apiKeyFp = apiKeyFp, cacheVersion = response.cacheVersion)
				st = st.copy(cacheVersion = response.cacheVersion)
				cacheStore.delete(METADATA)
			}
			val blockedHit = response.actions.filter { it.id in st.blocked }.map { it.id }
			val decided = response.actions.filter { it.id !in st.blocked }
			if (batch.user == currentUserLocked())
			{	val prev = st.result
				st = st.copy(
					result = if (batch.hasNavigate || prev == null)
					{	PersistedResult(
							conditions = response.conditions.distinct(),
							actions = decided.distinctBy { it.id }.map { PersistedAction(it.id, it.data) },
							sessionId = session,
							cacheVersion = st.cacheVersion,
							raw = response.raw
						)
					}
					else
					{	// Merge, as v1: what is new is added, what Personyze dismissed is removed.
						val conditions = (prev.conditions + response.conditions.filter { it !in prev.conditions }).filter { it !in response.dismissConditions }
						val known = prev.actions.mapTo(HashSet()) { it.id }
						val actions = (prev.actions + decided.filter { it.id !in known }.map { PersistedAction(it.id, it.data) }).filter { it.id !in response.dismissActions }
						PersistedResult(conditions.distinct(), actions.distinctBy { it.id }, response.dismissConditions, response.dismissActions, session, st.cacheVersion, response.raw)
					}
				)
			}
			state = st
			val ids = batch.items.mapTo(HashSet()) { it.seq }
			pending.removeAll { it.seq in ids }
			for (id in blockedHit)
			{	addLocked(Commands.actionStatus(id, "dont-show"), raw = false)
			}
			if (blockedHit.isNotEmpty())
			{	scheduleAutoFlushLocked()
			}
			emitLocked()
		}
		persistSoon()
		if (needsMetadata())
		{	scope.launch(io) { fetchMetadata(config.backgroundTimeoutMs) }
		}
		// The visitor may be new to Personyze, or another: the device registered for them.
		if (synchronized(lock) { state.push.token != null })
		{	scope.launch(io) { registerPush() }
		}
	}

	/**	Nothing reached Personyze: the events stay queued for when it can, a navigation does not. **/
	private fun keepBatch(batch: Batch, error: PersonyzeException, operation: String)
	{	val kept = synchronized(lock) {
			val ids = batch.items.mapTo(HashSet()) { it.seq }
			pending.removeAll { it.seq in ids && it.navigate }
			for (i in pending.indices)
			{	if (pending[i].seq in ids)
				{	pending[i] = pending[i].copy(inflight = false)
				}
			}
			pending.count { it.seq in ids }
		}
		persistSoon()
		if (kept > 0)
		{	platform.scheduleOutboxFlush()
		}
		report(error, PersonyzeErrorContext(operation, deferred = kept))
	}

	private fun dropBatch(batch: Batch, error: PersonyzeException, reason: Reason, operation: String)
	{	synchronized(lock) {
			val ids = batch.items.mapTo(HashSet()) { it.seq }
			pending.removeAll { it.seq in ids }
		}
		persistSoon()
		val commands = batch.items.filter { !it.navigate }.map { it.cmd.map { a -> a.orEmpty() } }
		if (commands.isEmpty())
		{	report(error, PersonyzeErrorContext(operation))
		}
		else
		{	val message = if (reason == Reason.AMBIGUOUS)
				"${commands.size} events may or may not have reached Personyze, and are not sent again: ${error.message}"
				else "${commands.size} events were refused: ${error.message}"
			report(PersonyzeException.Delivery(reason, commands, message, error), PersonyzeErrorContext(operation, dropped = commands.size))
		}
	}

	private fun throttledLocked(): Boolean = platform.nowMs() < state.throttledUntilMs

	// ------------------------------------------------------------------------------------- visitor

	fun setUserId(userId: Any?)
	{	val value = when (userId)
		{	null -> null
			is Int -> JsonPrimitive(userId)
			// Past 2^53 a JSON number reaches the server's JavaScript rounded; as text it is hashed exactly.
			is Long -> if (userId > MAX_SAFE_INTEGER || userId < -MAX_SAFE_INTEGER) JsonPrimitive(userId.toString()) else JsonPrimitive(userId)
			else -> JsonPrimitive(userId.toString())
		}
		mutate {
			if (value != state.userId)
			{	state = state.copy(userId = value)
				emitLocked()
				persistSoon()
				// The previous visitor's queued events go out under their own session first.
				scheduleAutoFlushLocked()
			}
		}
	}

	fun startNewSession()
	{	mutate {
			state = state.copy(epoch = state.epoch + 1, result = null, pageReports = emptyMap())
			emitLocked()
			persistSoon()
			scheduleAutoFlushLocked()
		}
	}

	suspend fun clearCache(): Unit = withContext(io) {
		loaded.await()
		synchronized(lock) {
			metadata = MetadataFile(apiKeyFp = apiKeyFp, cacheVersion = state.cacheVersion)
			emitLocked()
		}
		cacheStore.delete(METADATA)
		fetchMetadata(config.backgroundTimeoutMs)
	}

	suspend fun reset(): Unit = withContext(io) {
		loaded.await()
		// The forgotten visitor's notifications reach this device no more; the new visitor registers it again.
		val push = synchronized(lock) { state.push }
		if (push.token != null && push.registered != null)
		{	rest.call("PUT", Endpoints.PUSH_TOKEN, buildJsonObject { put("token", push.token); put("enabled", false) }.toString(), config.backgroundTimeoutMs)
		}
		wire.withLock {
			meta.withLock {
				synchronized(lock) {
					installVisitor = Random.nextInt(MIN_ANONYMOUS_ID, Int.MAX_VALUE)
					state = PersistedState(apiKeyFp = apiKeyFp, push = PersistedPush(token = push.token))
					pending.clear()
					metadata = MetadataFile(apiKeyFp = apiKeyFp)
					emitLocked()
				}
				store.clear()
				cacheStore.clear()
				store.write(VISITOR, synchronized(lock) { installVisitor }.toString(), sync = true)
				persistNow()
			}
		}
	}

	private fun currentUserLocked(): JsonPrimitive = state.userId ?: JsonPrimitive(installVisitor)

	/**	The user id as the server reads it: text stays text, a number is a number. **/
	private fun userValue(user: JsonPrimitive): Any = if (user.isString) user.content else user.content.toLongOrNull() ?: user.content

	// ------------------------------------------------------------------------------------ metadata

	private fun needsMetadata(): Boolean = synchronized(lock) { missingLocked().let { it.first.isNotEmpty() || it.second.isNotEmpty() || it.third.isNotEmpty() } }

	private fun missingLocked(): Triple<List<Int>, List<Int>, List<Int>>
	{	val r = state.result ?: return Triple(emptyList(), emptyList(), emptyList())
		val skip = metadata.missing.toHashSet()
		val conditions = r.conditions.filter { it !in metadata.conditions && "c$it" !in skip }
		val actions = r.actions.map { it.id }.filter { it !in metadata.actions && "a$it" !in skip }
		val placeholders = r.actions.mapNotNull { metadata.actions[it.id] }.flatMap { Wire.intList(it["placeholders"]) }.distinct()
			.filter { it !in metadata.placeholders && "p$it" !in skip }
		return Triple(conditions, actions, placeholders)
	}

	/**	The campaign names, action templates and placeholders the current result needs and the cache
		lacks - after a cache clear too (v1 skipped the placeholders then). Per account version, the
		same for every visitor (SPEC section 10), so cached; ids the server does not return are not
		asked for again until the version changes.
	 **/
	private suspend fun fetchMetadata(budgetMs: Long)
	{	meta.withLock {
			val deadline = platform.monotonicMs() + budgetMs
			val (conditions, actions) = synchronized(lock) { missingLocked() }.let { it.first to it.second }
			val version = synchronized(lock) { metadata.cacheVersion }
			var ok = fetchRows("c", conditions, deadline, version) { Endpoints.conditions(it) }
			if (ok && actions.isNotEmpty())
			{	val withOrder = synchronized(lock) { metadata.placeholderOrder }
				ok = fetchRows("a", actions, deadline, version, refusedWithOrder = withOrder) { Endpoints.actions(it, withOrder) }
				if (!ok && withOrder && synchronized(lock) { !metadata.placeholderOrder })
				{	ok = fetchRows("a", actions, deadline, version) { Endpoints.actions(it, false) }
				}
			}
			if (ok)
			{	val placeholders = synchronized(lock) { missingLocked().third }
				fetchRows("p", placeholders, deadline, version) { Endpoints.placeholders(it) }
			}
			saveMetadata()
			synchronized(lock) { emitLocked() }
		}
	}

	private suspend fun fetchRows(kind: String, ids: List<Int>, deadline: Long, version: Int, refusedWithOrder: Boolean = false, path: (List<Int>) -> String): Boolean
	{	for (chunk in ids.chunked(METADATA_CHUNK))
		{	val remaining = deadline - platform.monotonicMs()
			if (remaining <= 0)
			{	return false
			}
			when (val outcome = rest.call("GET", path(chunk), null, remaining))
			{	is RestResult.Ok ->
				{	val rows = Wire.parseRows(outcome.body)
					if (rows == null)
					{	report(PersonyzeException.Transport(200, Kind.MALFORMED_RESPONSE, "Personyze answered the $kind templates with something that is not a list"), PersonyzeErrorContext("metadata"))
						return false
					}
					synchronized(lock) {
						if (metadata.cacheVersion != version)
						{	return false	// the account changed version meanwhile; these rows are stale
						}
						val got = HashMap<Int, JsonObject>()
						for (row in rows)
						{	Wire.int(row["id"])?.let { got[it] = row }
						}
						metadata = when (kind)
						{	"c" -> metadata.copy(conditions = metadata.conditions + got)
							"a" -> metadata.copy(actions = metadata.actions + got)
							else -> metadata.copy(placeholders = metadata.placeholders + got)
						}
						val absent = chunk.filter { it !in got }.map { "$kind$it" }
						if (absent.isNotEmpty())
						{	metadata = metadata.copy(missing = (metadata.missing + absent).distinct())
						}
					}
				}
				is RestResult.Failed ->
				{	if (refusedWithOrder && outcome.error is PersonyzeException.BadRequest && (outcome.error as PersonyzeException.BadRequest).status == 400)
					{	// A server older than `placeholder_order` (SPEC section 10).
						synchronized(lock) { metadata = metadata.copy(placeholderOrder = false) }
						return false
					}
					report(outcome.error, PersonyzeErrorContext("metadata"))
					return false
				}
			}
		}
		return true
	}

	// ------------------------------------------------------------------------------- notifications

	/**	Fetch the visitor's queued notification. Answers it when it is to be shown - not shown
		before to this visitor - and marks it shown first, so a crash while posting cannot repeat it.

		How often (SPEC section 11): [force] at once; as the app comes to the front ([foreground])
		once a minute at most - the visitor is there to see it; otherwise, in the background, every
		15 minutes, or every 6 hours when Personyze pushes to the device - the check is then only the
		fallback for a push that did not go.
	 **/
	suspend fun pollNotification(force: Boolean, foreground: Boolean = false): PersonyzeNotification? = withContext(io) {
		loaded.await()
		val now = platform.nowMs()
		val request = synchronized(lock) {
			val n = state.notifications
			val interval = when
			{	force -> 0L
				foreground -> FOREGROUND_CHECK_MS
				state.push.active -> PUSHED_CHECK_MS
				else -> NOTIFICATION_INTERVAL_MS - 60_000
			}
			if (now < n.lastCheckMs + interval)
			{	return@withContext null
			}
			// No request yet, no visitor on the server, nothing queued for one.
			state.session ?: return@withContext null
			val expected = Session.serverUserId(userValue(currentUserLocked())) ?: return@withContext null
			state = state.copy(notifications = n.copy(lastCheckMs = now))
			Endpoints.notification(expected) to expected.toString()
		}
		persistSoon()
		val outcome = rest.call("GET", request.first, null, config.backgroundTimeoutMs)
		if (outcome is RestResult.Failed)
		{	report(outcome.error, PersonyzeErrorContext("notifications"))
			return@withContext null
		}
		val notification = NotificationPoll.parse((outcome as RestResult.Ok).body) ?: return@withContext null
		val show = synchronized(lock) {
			val n = state.notifications
			if (n.delivered[request.second] == notification.messageId)
			{	false
			}
			else
			{	val delivered = (n.delivered - request.second) + (request.second to notification.messageId)
				state = state.copy(notifications = n.copy(delivered = delivered.entries.toList().takeLast(DELIVERED_KEPT).associate { it.key to it.value }, primeOnNextPoll = false))
				!n.primeOnNextPoll
			}
		}
		persistNow()
		if (show) notification else null
	}

	/**	How long the background may wait before it checks again, in minutes: longer when Personyze
		pushes to the device.
	 **/
	fun backgroundCheckMinutes(): Long = synchronized(lock) { if (state.push.active) PUSHED_CHECK_MS else NOTIFICATION_INTERVAL_MS } / 60_000

	/**	The token Firebase gave the install, as the app passes it on (null: none any more).
		Registered as soon as there is a visitor to register it for.
	 **/
	fun setPushToken(token: String?)
	{	mutate {
			if (token != state.push.token)
			{	state = state.copy(push = state.push.copy(token = token))
				emitPushLocked()
				persistSoon()
			}
		}
		scope.launch(io) { registerPush() }
	}

	/**	Tell Personyze which device this visitor's notifications can be pushed to (SPEC section 11,
		`PUT push_token`) - when there is something new to tell: a token, a visitor (a login, a
		logout, a reset), or a week since the last time. Where the notifications cannot be shown
		any more (turned off in the app or by the user), the device is taken off instead. Best
		effort: what fails is tried again at the next chance - a response, a check, the app's
		return to the front.
	 **/
	suspend fun registerPush(): Unit = withContext(io) {
		loaded.await()
		val app = platform.pushApp() ?: return@withContext
		pushWire.withLock {
			val now = platform.nowMs()
			val showable = platform.notificationsShowable()
			val job: Pair<JsonObject, String?> = synchronized(lock) {
				val p = state.push
				val token = p.token ?: return@withLock
				if (!showable)
				{	if (p.registered == null)
					{	return@withLock
					}
					buildJsonObject { put("token", token); put("enabled", false) } to null
				}
				else
				{	state.session ?: return@withLock
					val visitor = Session.serverUserId(userValue(currentUserLocked())) ?: return@withLock
					val signature = "$visitor $token"
					if (p.registered == signature && now < p.registeredAtMs + PUSH_REGISTER_AGAIN_MS)
					{	return@withLock
					}
					buildJsonObject {
						put("user_id", visitor)
						put("token", token)
						put("platform", app.platform)
						put("app_id", app.appId)
						put("sandbox", app.sandbox)
						put("enabled", true)
					} to signature
				}
			}
			when (val outcome = rest.call("PUT", Endpoints.PUSH_TOKEN, job.first.toString(), config.backgroundTimeoutMs))
			{	is RestResult.Ok ->
				{	val active = job.second != null && Wire.bool((Wire.json.parseToJsonElement(outcome.body.ifBlank { "{}" }) as? JsonObject)?.get("push"))
					synchronized(lock) {
						state = state.copy(push = state.push.copy(registered = job.second, registeredAtMs = now, active = active))
						emitPushLocked()
					}
					persistSoon()
				}
				is RestResult.Failed -> platform.log("Personyze: the device could not be registered for push now: ${outcome.error.message}")
			}
		}
	}

	/**	A notification Personyze pushed, as the app's Firebase service passed it on: whether to show
		it - not shown before to this visitor, by a check or an earlier push - marked shown first, as
		[pollNotification] marks a checked one.
	 **/
	suspend fun acceptPushed(notification: PersonyzeNotification): Boolean = withContext(io) {
		loaded.await()
		val show = synchronized(lock) {
			val visitor = Session.serverUserId(userValue(currentUserLocked()))?.toString() ?: return@synchronized true
			val n = state.notifications
			if (n.delivered[visitor] == notification.messageId)
			{	false
			}
			else
			{	val delivered = (n.delivered - visitor) + (visitor to notification.messageId)
				state = state.copy(notifications = n.copy(delivered = delivered.entries.toList().takeLast(DELIVERED_KEPT).associate { it.key to it.value }))
				true
			}
		}
		persistNow()
		show
	}

	/**	Report a notification shown, or tapped (SPEC section 11): fetch the address `GET
		current_notification` answered with it. Written down first and taken off once the server has
		had it, so a report the network or the process lost goes with the next notification check
		([sendNotificationReports]). An empty address, or one that is not https, is nothing to report.
	 **/
	suspend fun reportNotification(address: String): Unit = withContext(io) {
		if (!isReportUrl(address))
		{	return@withContext
		}
		loaded.await()
		synchronized(lock) {
			val n = state.notifications
			state = state.copy(notifications = n.copy(reports = (n.reports.filter { it != address } + address).takeLast(REPORTS_KEPT)))
		}
		persistNow()
		sendNotificationReports()
	}

	/**	Send the notification reports not sent yet, oldest first, within the background budget.
		Stops at the first that does not reach the server; the rest wait for the next check.
	 **/
	suspend fun sendNotificationReports(): Unit = withContext(io) {
		loaded.await()
		reportWire.withLock {
			val addresses = synchronized(lock) { state.notifications.reports }
			if (addresses.isEmpty())
			{	return@withLock
			}
			val deadline = platform.monotonicMs() + config.backgroundTimeoutMs
			val sent = HashSet<String>()
			for (address in addresses)
			{	val remaining = deadline - platform.monotonicMs()
				if (remaining <= 0)
				{	break
				}
				if (isReportUrl(address) && !rest.ping(address, remaining))
				{	platform.log("Personyze: could not report the notification; trying again at the next check")
					break
				}
				sent.add(address)
			}
			if (sent.isNotEmpty())
			{	synchronized(lock) {
					val n = state.notifications
					state = state.copy(notifications = n.copy(reports = n.reports.filter { it !in sent }))
				}
				persistNow()
			}
		}
	}

	/**	Tell the server the message was delivered, so it leaves the queue. Best effort. **/
	suspend fun ackNotification(messageId: Long): Unit = withContext(io) {
		val path = synchronized(lock) {
			val expected = Session.serverUserId(userValue(currentUserLocked())) ?: return@withContext
			Endpoints.notification(expected, messageId)
		}
		val outcome = rest.call("DELETE", path, null, config.backgroundTimeoutMs)
		if (outcome is RestResult.Failed)
		{	platform.log("Personyze: could not remove the delivered notification from the queue: ${outcome.error.message}")
		}
	}

	suspend fun verdict(conditionId: Int): Result<PersonyzeVerdict> = withContext(io) {
		when (val outcome = rest.call("GET", Endpoints.verdict(conditionId), null, config.backgroundTimeoutMs))
		{	is RestResult.Ok -> VerdictParser.parse(outcome.body)?.let { Result.success(it) }
				?: Result.failure(PersonyzeException.Transport(200, Kind.MALFORMED_RESPONSE, "Personyze answered the verdict with something unexpected"))
			is RestResult.Failed ->
			{	report(outcome.error, PersonyzeErrorContext("verdict"))
				Result.failure(outcome.error)
			}
		}
	}

	// ------------------------------------------------------------------------------- state & output

	private fun emitPushLocked()
	{	val p = state.push
		pushFlow.value = PersonyzePushStatus(p.token, p.registeredVisitorId, p.registeredAtMs, p.active)
	}

	private fun emitLocked()
	{	emitPushLocked()
		resultFlow.value = buildResultLocked()
		val user = currentUserLocked()
		visitorFlow.value = PersonyzeVisitor(
			userId = state.userId?.content,
			visitorId = Session.serverUserId(userValue(user)),
			sessionId = state.session
		)
	}

	private fun buildResultLocked(): PersonyzeResult
	{	val r = state.result ?: return PersonyzeResult.EMPTY
		val api = owner()
		return PersonyzeResult(
			actions = r.actions.map { action(it).also { a -> a.owner = api; a.assetsUrl = config.assetsUrl } },
			conditions = r.conditions.map { PersonyzeCondition(it, metadata.conditions[it]?.let { row -> Wire.string(row["name"]) }) },
			dismissedActions = r.dismissedActions,
			dismissedConditions = r.dismissedConditions,
			sessionId = r.sessionId,
			cacheVersion = r.cacheVersion,
			rawJson = r.raw,
			error = null
		)
	}

	private fun action(a: PersistedAction): PersonyzeAction
	{	val row = metadata.actions[a.id]
			?: return PersonyzeAction(a.id, a.data.orEmpty(), null, null, emptyList(), null, emptyList(), "", "", "", metadata.cacheVersion, isLoaded = false)
		val placeholders = Wire.intList(row["placeholders"]).mapNotNull { id ->
			metadata.placeholders[id]?.let { p -> PersonyzePlaceholder(id, Wire.string(p["name"]), Wire.string(p["html_id"]), Wire.int(p["units_count_max"]) ?: 0) }
		}
		return PersonyzeAction(
			id = a.id,
			data = a.data.orEmpty(),
			name = Wire.string(row["name"]),
			contentType = Wire.string(row["content_type"]).ifEmpty { null },
			placeholders = placeholders,
			placeholderOrder = Wire.int(row["placeholder_order"]),
			libs = HtmlDocument.libsOf(Wire.string(row["libs_app"])),
			contentParam = Wire.string(row["content_param"]),
			contentBegin = Wire.string(row["content_begin"]),
			contentEnd = Wire.string(row["content_end"]),
			cacheVersion = metadata.cacheVersion,
			isLoaded = true
		)
	}

	private fun persistSoon()
	{	persistSignal.trySend(Unit)
	}

	/**	Snapshots are taken and written in the same order, so an older one never overwrites a newer. **/
	private fun persistNow()
	{	synchronized(persistLock) {
			val (st, ob) = synchronized(lock) {
				Wire.json.encodeToString(PersistedState.serializer(), state) to
					Wire.json.encodeToString(OutboxFile.serializer(), OutboxFile(items = pending.filter { !it.navigate }))
			}
			try
			{	store.write(STATE, st)
				store.write(OUTBOX, ob)
			}
			catch (e: Exception)
			{	platform.log("Personyze: could not save the state: $e")
			}
		}
	}

	private fun saveMetadata()
	{	synchronized(metaPersistLock) {
			val text = synchronized(lock) { Wire.json.encodeToString(MetadataFile.serializer(), metadata) }
			try
			{	cacheStore.write(METADATA, text)
			}
			catch (e: Exception)
			{	platform.log("Personyze: could not save the templates: $e")
			}
		}
	}

	fun reportInvalid(message: String, operation: String)
	{	report(PersonyzeException.Invalid(message), PersonyzeErrorContext(operation))
	}

	private fun report(error: PersonyzeException, context: PersonyzeErrorContext)
	{	platform.log("Personyze: ${context.operation}: ${error.message}")
		scope.launch(io) {
			try
			{	onError(error, context)
			}
			catch (e: Exception)
			{	platform.log("Personyze: the onError listener threw: $e")
			}
		}
	}

	/**	A redacted dump of the state, for a support case or a test screen. **/
	fun diagnostics(): String = synchronized(lock) {
		val sb = StringBuilder()
		sb.append("installVisitor=").append(installVisitor).append('\n')
		sb.append("userId=").append(state.userId?.content).append('\n')
		sb.append("session=").append(state.session).append('\n')
		sb.append("  start=").append(Session.startTime(state.session)).append(" visitor=").append(Session.visitor(state.session)).append(" counter=").append(Session.counter(state.session)).append('\n')
		sb.append("epoch=").append(state.epoch).append(" sentEpoch=").append(state.sentEpoch).append(" clockOffsetMs=").append(state.clockOffsetMs).append('\n')
		sb.append("pastSessions=").append(state.pastSessions).append('\n')
		sb.append("blocked=").append(state.blocked).append('\n')
		sb.append("cacheVersion=").append(state.cacheVersion).append(" templates: ").append(metadata.conditions.size).append(" campaigns, ")
			.append(metadata.actions.size).append(" actions, ").append(metadata.placeholders.size).append(" placeholders, ").append(metadata.missing.size).append(" missing\n")
		sb.append("pageReports=").append(state.pageReports).append('\n')
		sb.append("notifications=").append(state.notifications).append('\n')
		sb.append("push=").append(pushFlow.value).append(" (registered ").append(state.push.registered?.let { "for visitor " + state.push.registeredVisitorId } ?: "nowhere").append(")\n")
		sb.append("throttledUntilMs=").append(state.throttledUntilMs).append('\n')
		sb.append("queue (").append(pending.size).append("):\n")
		for (item in pending)
		{	sb.append("  ").append(item.cmd).append(" user=").append(item.user).append(" epoch=").append(item.epoch)
			if (item.inflight) sb.append(" inflight")
			sb.append('\n')
		}
		sb.toString()
	}

	internal companion object
	{	const val STATE = "state.json"
		const val OUTBOX = "outbox.json"
		const val VISITOR = "visitor"
		const val METADATA = "metadata.json"

		/**	Anonymous ids below this are reserved (SPEC section 10.1). **/
		const val MIN_ANONYMOUS_ID = 500_000_000
		/**	The web tag keeps 12 (`WConf::$n_remember_past_sessions_time`); so did v1. **/
		const val PAST_SESSIONS = 12
		const val THROTTLE_MS = 10L*60_000
		const val MAX_AGE_MS = 24L*3600_000
		const val MAX_PENDING = 1000
		const val MAX_PENDING_CHARS = 200_000
		const val BODY_OVERHEAD = 1000
		const val METADATA_CHUNK = 200
		const val NOTIFICATION_INTERVAL_MS = 15L*60_000
		const val DELIVERED_KEPT = 20
		/**	Notification reports not sent yet, kept until they are; the oldest go first past this. **/
		const val REPORTS_KEPT = 10
		/**	A check as the app comes to the front, at most this often. **/
		const val FOREGROUND_CHECK_MS = 60_000L
		/**	A background check where Personyze pushes to the device. **/
		const val PUSHED_CHECK_MS = 6L*3600_000
		/**	A device registered for push is registered again after this, so Personyze keeps it. **/
		const val PUSH_REGISTER_AGAIN_MS = 7L*24*3600_000
		const val CLOCK_SKEW_MS = 60_000L
		const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

		/**	A report's address as the server gave it, when it is one to fetch: https only. **/
		fun isReportUrl(address: String): Boolean = address.toHttpUrlOrNull()?.isHttps == true

		fun sha256(s: String): String =
			MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
	}
}
