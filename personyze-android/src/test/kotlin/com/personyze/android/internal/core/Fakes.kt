package com.personyze.android.internal.core

import com.personyze.android.PersonyzeErrorContext
import com.personyze.android.PersonyzeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

internal const val KEY = "0123456789ABCDEF0123456789ABCDEF01234567"
internal const val BASE = "https://app.personyze.com"
internal const val ASSETS = "https://counter.personyze.com/"
/**	A fixed moment (2025-10-23 12:00 UTC), in ms. **/
internal const val T0 = 1_761_220_800_000L

/**	A platform on the test scheduler's virtual clock. **/
internal class FakePlatform(private val scheduler: TestCoroutineScheduler) : Platform
{	var wallBase = T0
	var online = true
	var showable = false
	var outboxScheduled = 0
	val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
	var device = DeviceContext(3.0, "en-US,he-IL", "1080x2400", "Android/14", "phone")

	override fun nowMs(): Long = wallBase + scheduler.currentTime
	override fun monotonicMs(): Long = scheduler.currentTime
	override fun deviceContext(): DeviceContext = device
	override fun notificationsShowable(): Boolean = showable
	override fun isOnline(): Boolean = online

	override fun scheduleOutboxFlush()
	{	outboxScheduled++
	}

	override fun log(message: String)
	{	logs.add(message)
	}
}

internal typealias Step = suspend (HttpRequest) -> HttpOutcome

/**	Answers by URL: each route has a queue of steps, the last one repeating. A request no route
	expects fails the test.
 **/
internal class FakeTransport : Transport
{	val requests: MutableList<HttpRequest> = Collections.synchronizedList(ArrayList())
	private val routes = LinkedHashMap<String, ArrayDeque<Step>>()

	fun on(pathContains: String, vararg steps: Step): FakeTransport
	{	routes.getOrPut(pathContains) { ArrayDeque() }.addAll(steps)
		return this
	}

	fun tracker(vararg steps: Step): FakeTransport = on("/rest/tracker-v1", *steps)

	fun trackerRequests(): List<HttpRequest> = requests.filter { it.url.endsWith("/rest/tracker-v1") }

	fun bodies(): List<JsonObject> = trackerRequests().map { Wire.json.parseToJsonElement(it.body!!).jsonObject }

	override suspend fun execute(request: HttpRequest, timeoutMs: Long): HttpOutcome
	{	requests.add(request)
		val queue = routes.entries.firstOrNull { request.url.contains(it.key) }?.value ?: throw AssertionError("unexpected request ${request.method} ${request.url}")
		val step = if (queue.size > 1) queue.removeFirst() else queue.first()
		return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { step(request) } ?: HttpOutcome.Failed(sent = true, timedOut = true, offline = false, cause = null)
	}
}

internal fun ok(body: String, delayMs: Long = 0, dateMs: Long? = null): Step = {
	if (delayMs > 0) delay(delayMs)
	HttpOutcome.Answered(200, body, dateMs)
}

internal fun status(code: Int, body: String = "", delayMs: Long = 0): Step = {
	if (delayMs > 0) delay(delayMs)
	HttpOutcome.Answered(code, body, null)
}

internal fun fail(sent: Boolean, timedOut: Boolean = false, delayMs: Long = 0): Step = {
	if (delayMs > 0) delay(delayMs)
	HttpOutcome.Failed(sent = sent, timedOut = timedOut, offline = false, cause = java.io.IOException(if (sent) "reset" else "refused"))
}

internal fun hang(): Step = {
	delay(Long.MAX_VALUE/2)
	throw IllegalStateException()
}

/**	A session string as the tracker builds it: start, visitor, visit, counter, last session, testing, hosts, social. **/
internal fun session(startSec: Long, visitor: Long, counter: Int = 1): String = "$startSec $visitor 77 $counter $startSec 0 491,492 0"

internal fun tracker(session: String?, cacheVersion: Int = 5, conditions: List<Int> = emptyList(), actions: String = "[]", dismissActions: List<Int> = emptyList(), dismissConditions: List<Int> = emptyList()): String =
	"""{"session_id":${session?.let { "\"$it\"" } ?: "null"},"cache_version":$cacheVersion,"conditions":[${conditions.joinToString(",") { "{\"id\":$it}" }}],"actions":$actions,"dismiss_conditions":$dismissConditions,"dismiss_actions":$dismissActions}"""

internal fun JsonObject.commands(): List<List<String?>> =
	this["commands"]!!.jsonArray.map { cmd -> cmd.jsonArray.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } }

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(name: String): Boolean = this[name]!!.jsonPrimitive.content == "true"

/**	[errors] fill asynchronously: read them after `advanceUntilIdle()`. **/
internal class Harness
(	val engine: Engine,
	val transport: FakeTransport,
	val platform: FakePlatform,
	val store: Store,
	val cache: Store,
	val errors: List<Pair<PersonyzeException, PersonyzeErrorContext>>
)

internal fun TestScope.harness
(	transport: FakeTransport = FakeTransport(),
	store: Store = MemoryStore(),
	cache: Store = MemoryStore(),
	legacy: LegacySource? = null,
	autoFlushMs: Long? = null,
	timeoutMs: Long = 5000,
	apiKey: String = KEY,
	platform: FakePlatform = FakePlatform(testScheduler)
): Harness
{	val errors = Collections.synchronizedList(ArrayList<Pair<PersonyzeException, PersonyzeErrorContext>>())
	val dispatcher = StandardTestDispatcher(testScheduler)
	// A child of backgroundScope, so it is cancelled when the test ends - but without its
	// background marker: advanceUntilIdle() runs only foreground work, and the engine's saves and
	// reports are what the tests wait for.
	val scope = CoroutineScope(SupervisorJob(backgroundScope.coroutineContext[Job]) + dispatcher)
	val engine = Engine(
		EngineConfig(apiKey, BASE, ASSETS, timeoutMs, 10_000, 1, autoFlushMs, "personyze-android/test"),
		platform,
		store,
		cache,
		transport,
		scope,
		dispatcher,
		legacy,
		{ e, c -> errors.add(e to c) },
		{ null }
	)
	return Harness(engine, transport, platform, store, cache, errors)
}

internal fun JsonArray.ints(): List<Int> = map { it.jsonPrimitive.content.toInt() }
