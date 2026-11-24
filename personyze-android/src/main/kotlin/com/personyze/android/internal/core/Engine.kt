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
import java.security.SecureRandom

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

	**What makes a request** (SPEC sections 1 and 3, protocol 2): the visitor is the app's user id,
	sent as `user_id`, or the install's anonymous id, sent as `pz_visitor_id` - with `pz_visitor_new`
	until an answer names it, and replaced by the answer's when that differs; a stored session of
	another visitor is no session; `new_session` is computed - a forced new session, no session, or
	one older than 90 minutes - never constant.

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
	private val owner: () -> PersonyzeApi?,
	/**	What anonymous visitor ids are minted from; tests pass one that answers what they need. **/
	private val random: java.util.Random = SecureRandom()
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
	/**	The install's anonymous visitor (SPEC section 11), 64 bits: what `pz_visitor_id` sends. **/
	private var installVisitor = 0L
	/**	The SDK minted [installVisitor] and no answer has named it yet: sent with `pz_visitor_new`. **/
	private var installVisitorNew = false
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
				synchronized(lock) {
					if (installVisitor == 0L)
					{	// A visitor for this process at least: a request with none is refused.
						installVisitor = Session.mintVisitorId(32, random)
						installVisitorNew = true
					}
					markLoadedLocked()
				}
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
	{	val visitorText = store.read(VISITOR)
		val stored = readVisitorFile(visitorText)
		val storedVisitor = stored?.first
		val storedNew = stored?.second == true
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
				installId = installId ?: old.userId.takeIf { it != 0 }?.toLong()
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
		st = st ?: PersistedState(apiKeyFp = apiKeyFp)
		if (st.apiKeyFp != null && st.apiKeyFp != apiKeyFp)
		{	// Another account: its session, history, queue and id width mean nothing here.
			st = PersistedState(apiKeyFp = apiKeyFp, userId = st.userId)
			outbox = emptyList()
		}
		st = st.copy(apiKeyFp = apiKeyFp)
		// The `visitor` file lost, state.json kept: the state says whose it was - that visitor, with its
		// session and what waits for it, not a new one minted beside its history (the confirm review,
		// nit 3). Otherwise the first run, which has heard no `id_bits`: 32 bits, which the server
		// upgrades where the account mints 64 (SPEC section 11).
		val restored = if (installId == null) readVisitorFile(st.installVisitor) else null
		val install = installId ?: restored?.first ?: Session.mintVisitorId(st.idBits, random)
		val installNew = if (installId != null) storedNew else restored?.second ?: true
		val visitorFile = visitorFileText(install, installNew)
		if (visitorFile != visitorText)
		{	store.write(VISITOR, visitorFile, sync = true)
		}
		// What waits for the install's visitor is the install's: only its predecessors can be found
		// here - a reset and another key clear the queue and the state. An id adopted after the state
		// was saved (the app died between the two writes: the review, minor 3), or 2.x's `user_id` - a
		// number, the same visitor to the server (SPEC section 3), `pz_visitor_id` now. Left as they
		// were, the events would be dropped as another visitor's and a shown message shown again.
		val installText = install.toString()
		outbox = outbox.map { item ->
			if (item.anonymous && item.user.content != installText || !item.anonymous && !item.user.isString && item.user.content.toLongOrNull() == install) item.copy(user = JsonPrimitive(installText), anonymous = true) else item
		}
		val savedFor = readVisitorFile(st.installVisitor)?.first?.toString()
		val shown = savedFor?.let { st.notifications.delivered[it] }
		if (savedFor != null && savedFor != installText && shown != null)
		{	st = st.copy(notifications = st.notifications.copy(delivered = (st.notifications.delivered - savedFor) + (installText to shown)))
		}
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
			installVisitor = install
			installVisitorNew = installNew
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
		val visitor = currentUserLocked()
		val sessionStart = if (Session.isSessionOf(state.session, visitor.visitorId)) Session.startTime(state.session) else 0
		if (Commands.isActionStatus(cmd) && cmd[2] != "dont-show" && sessionStart == 0L)
		{	// An action is decided in a session of its visitor's. Reported with none of the current
			// visitor's - the decision was the one before a login, a logout or a reset - it belongs to no
			// session of theirs, and held for their first navigation it would be counted there (the
			// confirm review of the port, minor 1; SPEC section 11, Difference 3). Not sent; what it does
			// on the device (a close blocks the action here) it still did - the person did close it.
			report(
				PersonyzeException.Delivery(Reason.OTHER_SESSION, listOf(cmd.map { it.orEmpty() }), "An action report for a decision made before the visitor changed is not sent"),
				PersonyzeErrorContext("track", dropped = 1)
			)
			return null
		}
		val item = PendingItem(
			cmd = cmd,
			user = visitor.user,
			epoch = state.epoch,
			sessionStart = sessionStart,
			queuedAtMs = platform.nowMs(),
			raw = raw,
			navigate = navigate,
			seq = seq++,
			anonymous = visitor.anonymous
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
		var drained = underWire(deadline) { drain(deadline, operation = "navigate", navSeq = navItem.seq) }
		// Another caller's navigation, queued before this one, had not had the wire yet: the queue goes
		// in order, so this one waits for its turn - failing at once ("did not answer"), it logged no
		// page view (the confirm review, nit 2).
		while (drained != null && !drained.navigateDone && drained.navigateError == null && platform.monotonicMs() < deadline &&
			synchronized(lock) { pending.any { it.navigate && it.seq < navItem.seq } })
		{	delay(TURN_WAIT_MS)
			drained = underWire(deadline) { drain(deadline, operation = "navigate", navSeq = navItem.seq) }
		}
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
			val drained = drain(deadline, operation = "refresh", navSeq = null)
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

	/**	Send what is queued, now - what can go: events with no live session of their visitor's wait
		for the next navigation ([takeBatchLocked]).
	 **/
	suspend fun flush(): Unit = withContext(io) {
		loaded.await()
		val deadline = platform.monotonicMs() + config.backgroundTimeoutMs
		underWire(deadline) { drain(deadline, operation = "flush", navSeq = null) }
	}

	/**	[flush] within [budgetMs], from the background. Answers false when events stayed for lack of
		a network.
	 **/
	suspend fun flushLive(budgetMs: Long, operation: String): Boolean = withContext(io) {
		loaded.await()
		val deadline = platform.monotonicMs() + budgetMs
		val drained = underWire(deadline) { drain(deadline, operation = operation, navSeq = null) }
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

	private class Batch(val items: List<PendingItem>, val visitor: Identity, val epoch: Int)
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
	private suspend fun drain(deadline: Long, operation: String, navSeq: Long?): Drained
	{	val drained = Drained()
		while (true)
		{	val batch = synchronized(lock) { takeBatchLocked(operation, navSeq) } ?: break
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
		* events queued before `startNewSession()` whose session is not live: no page of their
		  session will come;
		* anything a day old.

		**Events with no page view to belong to wait.** A request with no navigation goes only into a
		live session of its visitor's (SPEC sections 1 and 11). Sent alone, its events were refused
		("Events not logged: this session has no page view", a 500) - and, for a visitor the server
		replaces (a new install on an account that mints 64-bit ids, a 64-bit visitor on one that no
		longer takes them), not logged, answered 200: lost without a word. Until 3.0 only the
		automatic send held them; `flush()` and `refresh()` sent them alone. They wait in the queue
		for the visitor's next navigation, which takes them along; what can go meanwhile goes past
		them - the navigation itself, when they do not fit in its request.
	 **/
	private fun takeBatchLocked(operation: String, navSeq: Long?): Batch?
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
			val liveOwnSession = isLiveSessionOfLocked(item, serverNow)
			val reason = when
			{	item.identity != current && !liveOwnSession -> Reason.OTHER_VISITOR
				item.epoch < state.epoch && !liveOwnSession -> Reason.OTHER_SESSION
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
		var from = pending.indexOfFirst { !it.inflight }
		while (from != -1)
		{	val first = pending[from]
			if (first.navigate && first.seq != navSeq)
			{	return null	// another caller's navigation, waiting for its turn: it sends from here on
			}
			val items = ArrayList<PendingItem>()
			var size = BODY_OVERHEAD
			var end = from
			for (i in from until pending.size)
			{	val item = pending[i]
				if (item.inflight)
				{	continue
				}
				if (item.navigate && item.seq != navSeq)
				{	break
				}
				if (item.identity != first.identity || item.epoch != first.epoch || item.raw != first.raw || (first.raw && items.isNotEmpty()))
				{	break
				}
				val len = Commands.jsonLength(item.cmd) + 1
				if (items.isNotEmpty() && size + len > Commands.MAX_BODY)
				{	break
				}
				// The events after a navigation go in its request, attributed to the new screen.
				items.add(item)
				size += len
				end = i
			}
			if (items.any { it.navigate } || isLiveSessionOfLocked(first, serverNow))
			{	val ids = items.mapTo(HashSet()) { it.seq }
				for (i in pending.indices)
				{	if (pending[i].seq in ids)
					{	pending[i] = pending[i].copy(inflight = true)
					}
				}
				return Batch(items.map { it.copy(inflight = true) }, first.identity, first.epoch)
			}
			// No page view to belong to: they wait, and what comes after them may go.
			from = (end + 1 until pending.size).firstOrNull { !pending[it].inflight } ?: -1
		}
		return null
	}

	/**	Whether [item] may go into the live session: one of its visitor's, not expired, of its epoch. **/
	private fun isLiveSessionOfLocked(item: PendingItem, serverNow: Long): Boolean =
		state.session != null && Session.isSessionOf(state.session, item.identity.visitorId) &&
			!Session.isExpired(state.session, serverNow) && item.epoch == state.sentEpoch

	private class Sent(val body: String, val session: String?, val newSession: Boolean)

	/**	One tracker request. Answers null on success, else the error (already reported). **/
	private suspend fun sendBatch(batch: Batch, deadline: Long, operation: String): PersonyzeException?
	{	val device = platform.deviceContext()
		val showable = platform.notificationsShowable()
		val sent = synchronized(lock) {
			var session = state.session
			if (!Session.isSessionOf(session, batch.visitor.visitorId))
			{	session = null
			}
			val serverNow = platform.nowMs() + state.clockOffsetMs
			val newSession = batch.epoch > state.sentEpoch || session == null || Session.isExpired(session, serverNow)
			// Newest first, the current session included - as the web tag sends them (stat-track-lib
			// `get_ps`) and as the rule engine counts them (`pastSessionsCount` reads from the front).
			val past = if (newSession) (listOf(serverNow/1000) + state.pastSessions).take(PAST_SESSIONS) else state.pastSessions
			val visitorNew = installVisitorNew && isInstallVisitorLocked(batch.visitor)
			Sent(Wire.requestBody(batch.visitor, visitorNew, session, newSession, past, device, showable, batch.items.map { it.cmd }), session, newSession)
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
	{	// [persistLock] across the state's change and both writes - the adopted visitor first, then the
		// state with the session that came with it (SPEC section 1, "saved with its session"): a persist
		// asked for meanwhile, by a call of the app's on any thread, waits. Without it the background
		// save could write the new session, and the queue tagged for the new id, before the id itself
		// (the independent review of the port, minor 2).
		val adopted = synchronized(persistLock) {
			val visitorFile = synchronized(lock) { applyResponseLocked(batch, sent, response, dateMs) }
			if (visitorFile != null)
			{	// Cut between the two writes, the new visitor finds the old one's state, which load()
				// gives to it (the queue, the shown-message record) or does not send for it (the
				// session): the same visitor, a new session.
				saveVisitorFile(visitorFile)
				persistNow()
			}
			visitorFile != null
		}
		if (!adopted)
		{	persistSoon()
		}
		if (needsMetadata())
		{	scope.launch(io) { fetchMetadata(config.backgroundTimeoutMs) }
		}
		// The visitor may be new to Personyze, or another: the device registered for them.
		if (synchronized(lock) { state.push.token != null })
		{	scope.launch(io) { registerPush() }
		}
	}

	/**	The answer, into the state. Answers the `visitor` file's new text when the install's visitor
		changed - named, or another - else null.
	 **/
	private fun applyResponseLocked(batch: Batch, sent: Sent, response: Wire.TrackerResponse, dateMs: Long?): String?
	{	var st = state
		if (dateMs != null)
		{	val offset = dateMs - platform.nowMs()
			st = st.copy(clockOffsetMs = if (kotlin.math.abs(offset) > CLOCK_SKEW_MS) offset else 0)
		}
		// What an answer to the install's anonymous visitor names it (SPEC section 1): `pz_visitor_id`
		// - or, from a server before protocol 2, which took the request for a new random visitor,
		// field 1 of the session it answered (a safety net only).
		val named = if (isInstallVisitorLocked(batch.visitor)) response.pzVisitorId ?: Session.visitor(response.sessionId) else null
		val replaced = named != null && named != installVisitor
		// Always the newest (SPEC section 3, rule 2) - v1 kept the first of each session. A replaced
		// visitor's is the answer's exactly: the session sent was the other one's, and a request with
		// no page opened none (the review, minor 4: the new id was shown beside the old session).
		val session = if (replaced) response.sessionId else response.sessionId ?: sent.session
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
		if (response.idBits != null)
		{	st = st.copy(idBits = response.idBits)
		}
		if (response.cacheVersion > 0 && response.cacheVersion != st.cacheVersion)
		{	metadata = MetadataFile(apiKeyFp = apiKeyFp, cacheVersion = response.cacheVersion)
			st = st.copy(cacheVersion = response.cacheVersion)
			cacheStore.delete(METADATA)
		}
		val blockedHit = response.actions.filter { it.id in st.blocked }.map { it.id }
		val decided = response.actions.filter { it.id !in st.blocked }
		if (batch.visitor == currentUserLocked())
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
		var adoptedFile: String? = null
		if (named != null)
		{	adoptLocked(st, named)?.let { adopted ->
				st = adopted
				adoptedFile = visitorFileLocked()
			}
		}
		state = st
		val ids = batch.items.mapTo(HashSet()) { it.seq }
		if (replaced && response.sessionId == null && !batch.hasNavigate)
		{	// SPEC section 1, "Events of a replaced visitor are not logged": the server took them for the
			// other visitor's and logged nothing, answering 200 all the same. Not sent, then: kept -
			// tagged for the new id by adoptLocked - for its next page (section 11).
			for (i in pending.indices)
			{	if (pending[i].seq in ids)
				{	pending[i] = pending[i].copy(inflight = false)
				}
			}
		}
		else
		{	pending.removeAll { it.seq in ids }
		}
		for (id in blockedHit)
		{	addLocked(Commands.actionStatus(id, "dont-show"), raw = false)
		}
		if (blockedHit.isNotEmpty())
		{	scheduleAutoFlushLocked()
		}
		emitLocked()
		return adoptedFile
	}

	/**	What an answer to the install's anonymous visitor says of it (SPEC section 1): [named], the
		visitor the request was logged under.

		Named, the id is no longer new. Another id replaces the install's - a new visitor minted at 32
		bits where the account mints 64, or one past 32 bits where the account no longer takes them -
		and what waits on the device for the old one is the new one's: the queued events, the record
		of the notifications shown (it is the same person). The push registration follows by itself:
		it is for the visitor as it stands. Answers [st] with that done, or null when nothing changed.
	 **/
	private fun adoptLocked(st: PersistedState, named: Long): PersistedState?
	{	val old = installVisitor
		if (named == old)
		{	if (!installVisitorNew)
			{	return null
			}
			installVisitorNew = false
			return st
		}
		installVisitor = named
		installVisitorNew = false
		val oldKey = old.toString()
		val newKey = named.toString()
		for (i in pending.indices)
		{	if (pending[i].anonymous && pending[i].user.content == oldKey)
			{	pending[i] = pending[i].copy(user = JsonPrimitive(newKey))
			}
		}
		val delivered = st.notifications.delivered
		val shown = delivered[oldKey] ?: return st
		return st.copy(notifications = st.notifications.copy(delivered = (delivered - oldKey) + (newKey to shown)))
	}

	/**	Whether [visitor] is the install's anonymous visitor as it stands. **/
	private fun isInstallVisitorLocked(visitor: Identity): Boolean = visitor.anonymous && visitor.user.content == installVisitor.toString()

	/**	`visitor`: the install's id, and ` new` after it while no answer has named it - one file, so
		the two are written in one step. Apart from state.json, and written at once: the visitor
		outlives another account's state and a reset's wipe. 2.x wrote the id alone, and reads one
		with ` new` as none - minting another, which only a visitor nobody has answered yet can be.
	 **/
	private fun visitorFileLocked(): String = visitorFileText(installVisitor, installVisitorNew)

	private fun saveVisitorFile(text: String)
	{	try
		{	store.write(VISITOR, text, sync = true)
		}
		catch (e: Exception)
		{	platform.log("Personyze: could not save the visitor: $e")
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
				// At the width the account last answered: the new visitor's id is the account's to keep.
				val idBits = synchronized(lock) { state.idBits }
				val minted = Session.mintVisitorId(idBits, random)
				val visitorFile = synchronized(lock) {
					installVisitor = minted
					installVisitorNew = true
					state = PersistedState(apiKeyFp = apiKeyFp, push = PersistedPush(token = push.token), idBits = idBits)
					pending.clear()
					metadata = MetadataFile(apiKeyFp = apiKeyFp)
					emitLocked()
					visitorFileLocked()
				}
				store.clear()
				cacheStore.clear()
				store.write(VISITOR, visitorFile, sync = true)
				persistNow()
			}
		}
	}

	/**	The visitor a request made now is about: the app's user id, or the install's anonymous one. **/
	private fun currentUserLocked(): Identity = state.userId?.let { Identity(it, anonymous = false) } ?: Identity.anonymous(installVisitor)

	/**	The current visitor's id as the server has it (SPEC section 11) - for the notification check, the
		push registration, [PersonyzeVisitor.visitorId]: the install's own id (the answer's
		`pz_visitor_id` once one named another), or a login's as the server makes it of `user_id` - else,
		when that names nobody, field 1 of the session the server answered. Never recomputed on the
		device for the install's visitor: a 64-bit id is nobody's CRC-32. None for the install's before
		an answer names it: the device's guess, which an account that mints 64-bit ids replaces - an
		app that stored it would keep a visitor that never was (the review, minor 5).
	 **/
	private fun visitorIdLocked(): Long? = if (installVisitorNew && currentUserLocked().anonymous) null else deviceVisitorIdLocked()

	/**	[visitorIdLocked] as the device files its own records (the notifications shown): the install's id
		even before an answer names it - a message pushed then is still shown once. An adopted id takes
		the record over (adoptLocked).
	 **/
	private fun deviceVisitorIdLocked(): Long? = currentUserLocked().visitorId ?: Session.visitor(state.session)

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
			val expected = visitorIdLocked() ?: return@withContext null
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
					// None for an install's visitor not on the server yet: its answer may name another.
					val visitor = visitorIdLocked() ?: return@withLock
					val signature = "$visitor $token"
					if (p.registered == signature && now < p.registeredAtMs + PUSH_REGISTER_AGAIN_MS)
					{	return@withLock
					}
					buildJsonObject {
						// Text, as every visitor id (SPEC section 1): a 64-bit one is past what a double holds.
						put("user_id", visitor.toString())
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
			val visitor = deviceVisitorIdLocked()?.toString() ?: return@synchronized true
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
			val expected = visitorIdLocked() ?: return@withContext
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
		visitorFlow.value = PersonyzeVisitor(
			userId = state.userId?.content,
			visitorId = visitorIdLocked(),
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
				Wire.json.encodeToString(PersistedState.serializer(), state.copy(installVisitor = visitorFileLocked())) to
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
		sb.append("installVisitor=").append(installVisitor).append(if (installVisitorNew) " (new: no answer has named it yet)" else "").append(" idBits=").append(state.idBits).append('\n')
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
		{	sb.append("  ").append(item.cmd).append(if (item.anonymous) " pz_visitor_id=" else " user_id=").append(item.user).append(" epoch=").append(item.epoch)
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

		/**	After the id in the `visitor` file while no answer has named it ([visitorFileLocked]). **/
		const val NEW_MARK = "new"
		private val WHITESPACE = Regex("\\s+")

		fun visitorFileText(visitorId: Long, isNew: Boolean): String = if (isNew) "$visitorId $NEW_MARK" else visitorId.toString()

		/**	[visitorFileText] read back: the id and whether it is new, or null for no id. 64 bits, and as
			leniently as 2.x read the file - which read an Int, and minted a new visitor over a 64-bit one.
		 **/
		fun readVisitorFile(text: String?): Pair<Long, Boolean>?
		{	val fields = text?.trim()?.split(WHITESPACE) ?: return null
			val id = fields.first().toLongOrNull()?.takeIf { it != 0L && it != Long.MIN_VALUE } ?: return null
			return id to (fields.getOrNull(1) == NEW_MARK)
		}

		/**	A navigation behind another caller's looks for its turn this often. **/
		const val TURN_WAIT_MS = 20L
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
