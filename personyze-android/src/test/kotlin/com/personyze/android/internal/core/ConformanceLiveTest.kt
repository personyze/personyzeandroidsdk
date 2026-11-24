package com.personyze.android.internal.core

import com.personyze.android.PersonyzeErrorContext
import com.personyze.android.PersonyzeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections
import kotlin.random.Random

/**	SPEC section 9 against the real endpoint - every port's golden path. Skipped without
	`PERSONYZE_API_KEY` (account 1, the test account). Writes to that account, like the other ports'.
	Steps 9 and 10 need a server that speaks protocol 2 (app-service since P7, 2026-11) and an account
	that mints 64-bit visitor ids - account 1 does since 2026-11-17. Step 11 is for ports that hand the
	visit to the tag (section 10.1); an app has no tag.

	```
	PERSONYZE_API_KEY=$(cat key-file) ./gradlew :personyze-android:testDebugUnitTest --tests '*ConformanceLiveTest*'
	```
 **/
class ConformanceLiveTest
{	private val key: String? = System.getenv("PERSONYZE_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
	private val baseUrl: String = System.getenv("PERSONYZE_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() } ?: "https://app.personyze.com"
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val errors: MutableList<Pair<PersonyzeException, PersonyzeErrorContext>> = Collections.synchronizedList(ArrayList())
	/**	Ids of its own per run, below the anonymous range, as an app's own user ids would be. **/
	private val userBase = 777_100_000 + Random.nextInt(0, 9_000_000)

	private class RealPlatform : Platform
	{	override fun nowMs(): Long = System.currentTimeMillis()
		override fun monotonicMs(): Long = System.nanoTime()/1_000_000
		override fun deviceContext(): DeviceContext = DeviceContext(3.0, "en-US", "1080x2400", "Android/14", "phone")
		override fun notificationsShowable(): Boolean = false
		override fun isOnline(): Boolean = true
		override fun scheduleOutboxFlush() {}
		override fun pushApp(): PushApp? = null
		override fun log(message: String) = println(message)
	}

	/**	The real transport, keeping each tracker request's body: what the SDK sent, beside what came back. **/
	private class Recorder(private val inner: Transport = OkHttpTransport(null) { true }) : Transport
	{	val bodies: MutableList<JsonObject> = Collections.synchronizedList(ArrayList())

		override suspend fun execute(request: HttpRequest, timeoutMs: Long): HttpOutcome
		{	if (request.url.endsWith("/rest/tracker-v1") && request.body != null)
			{	bodies.add(Wire.json.parseToJsonElement(request.body).jsonObject)
			}
			return inner.execute(request, timeoutMs)
		}
	}

	private fun engine(apiKey: String = key!!, base: String = baseUrl, store: Store = MemoryStore(), timeoutMs: Long = 10_000, transport: Transport = OkHttpTransport(null) { true }): Engine =
		Engine(
			EngineConfig(apiKey, base, "https://counter.personyze.com/", timeoutMs, 15_000, 1, null, "personyze-android/conformance"),
			RealPlatform(),
			store,
			MemoryStore(),
			transport,
			scope,
			Dispatchers.IO,
			null,
			{ e, c -> errors.add(e to c) },
			{ null }
		)

	@After fun stop()
	{	scope.cancel()
	}

	/**	Steps 1-3: a session comes back; the visitor persists in it; an executed report succeeds. **/
	@Test fun step1to3_decideKeepVisitorReport() = runBlocking<Unit> {
		assumeTrue("PERSONYZE_API_KEY not set", key != null)
		val e = engine()
		val user = userBase + 1L
		e.setUserId(user)
		val first = e.navigate("androidsdk-conformance/home", null)
		assertNull(first.error)
		assertNotNull("step 1: a session id comes back", first.sessionId)
		assertEquals(user, Session.visitor(first.sessionId))
		val second = e.navigate("androidsdk-conformance/product", null)
		assertEquals("step 2: the same visitor persists", user, Session.visitor(second.sessionId))
		val actionId = second.actions.firstOrNull()?.id
		if (actionId != null)
		{	e.enqueue { Commands.actionStatus(actionId, "executed") }
		}
		else
		{	e.enqueue { Commands.profile(mapOf("conformance" to "android")) }
		}
		e.flush()
		delay(200)
		assertEquals("step 3: no error", emptyList<Any>(), errors.map { it.first.message })
	}

	/**	Step 4: a corrupt stored session with new_session recovers instead of raising. **/
	@Test fun step4_corruptSessionRecovers() = runBlocking<Unit> {
		assumeTrue("PERSONYZE_API_KEY not set", key != null)
		val store = MemoryStore()
		store.write(Engine.STATE, """{"v":1,"apiKeyFp":"${Engine.sha256(key!!).take(16)}","userId":${userBase + 4},"session":"garbage 1 2"}""")
		val r = engine(store = store).navigate("androidsdk-conformance/home", null)
		assertNull(r.error)
		assertEquals((userBase + 4).toLong(), Session.visitor(r.sessionId))
	}

	/**	Step 5: a bad key is the auth error type. **/
	@Test fun step5_badKeyIsAuth() = runBlocking<Unit> {
		assumeTrue("PERSONYZE_API_KEY not set", key != null)
		val r = engine(apiKey = "0000000000000000000000000000000000000000").navigate("androidsdk-conformance/home", null)
		assertTrue("got ${r.error}", r.error is PersonyzeException.Auth)
	}

	/**	Step 6: an unreachable host answers empty decisions and fires the callback - no raise. **/
	@Test fun step6_unreachableFailsOpen() = runBlocking<Unit> {
		assumeTrue("PERSONYZE_API_KEY not set", key != null)
		val port = java.net.ServerSocket(0).use { it.localPort }
		val r = engine(base = "https://127.0.0.1:$port", timeoutMs = 2000).navigate("androidsdk-conformance/home", null)
		assertTrue(r.isEmpty)
		assertTrue(r.error is PersonyzeException.Transport)
		delay(200)
		assertFalse(errors.isEmpty())
	}

	/**	Step 7: three decisions in a row keep one session - the counter (field 3) does not climb. **/
	@Test fun step7_threeDecisionsOneSession() = runBlocking<Unit> {
		assumeTrue("PERSONYZE_API_KEY not set", key != null)
		val e = engine()
		e.setUserId(userBase + 7L)
		val counters = (1..3).map { Session.counter(e.navigate("androidsdk-conformance/page$it", null).sessionId) }
		assertNotNull(counters[0])
		assertEquals("session counters $counters", 1, counters.toSet().size)
	}

	/**	Step 8: one device store, visitor A then visitor B - field 1 of B's session is B. **/
	@Test fun step8_visitorSwitch() = runBlocking<Unit> {
		assumeTrue("PERSONYZE_API_KEY not set", key != null)
		val e = engine()
		val a = userBase + 81L
		val b = "androidsdk-conformance-${userBase}@example.com"
		e.setUserId(a)
		assertEquals(a, Session.visitor(e.navigate("androidsdk-conformance/home", null).sessionId))
		e.setUserId(b)
		val rb = e.navigate("androidsdk-conformance/home", null)
		assertEquals(Session.serverUserId(b)!!.toLong(), Session.visitor(rb.sessionId))
	}

	private fun answer(r: com.personyze.android.PersonyzeResult): JsonObject = Wire.json.parseToJsonElement(r.rawJson!!).jsonObject

	private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

	private fun fits32(id: Long): Boolean = id >= Int.MIN_VALUE && id <= Int.MAX_VALUE

	/**	Step 9: a 64-bit visitor is kept, exactly. The account answers first (`id_bits: 64`), so the
		visitor a reset mints next is minted at 64 bits; the answer keeps it as it was sent, field 1 of
		the session is that id to the digit, and a second decision keeps it in one session.
	 **/
	@Test fun step9_a64BitVisitorIsKeptExactly() = runBlocking<Unit> {
		assumeTrue("PERSONYZE_API_KEY not set", key != null)
		val recorder = Recorder()
		val e = engine(transport = recorder)
		val heard = e.navigate("androidsdk-conformance/home", null)
		assertNull(heard.error)
		assertEquals("the account mints 64-bit ids", "64", answer(heard).text("id_bits"))
		e.reset()
		assertNull("no visitor id until an answer names it", e.visitor.value.visitorId)
		val first = e.navigate("androidsdk-conformance/home", null)
		assertNull(first.error)
		val sent = recorder.bodies.last()
		val minted = sent.text("pz_visitor_id")!!.toLong()
		assertFalse("minted at 64 bits once the account said so: $minted", fits32(minted))
		assertEquals(JsonPrimitive(true), sent["pz_visitor_new"])
		assertEquals("64", answer(first).text("id_bits"))
		assertEquals("kept as it was sent", minted.toString(), answer(first).text("pz_visitor_id"))
		assertEquals(minted, Session.visitor(first.sessionId))
		val second = e.navigate("androidsdk-conformance/product", null)
		assertNull(second.error)
		assertEquals(minted, Session.visitor(second.sessionId))
		assertEquals(Session.startTime(first.sessionId), Session.startTime(second.sessionId))
		assertEquals(1, Session.counter(second.sessionId))
		assertNull("named: no longer new", recorder.bodies.last()["pz_visitor_new"])
		assertEquals(minted, e.visitor.value.visitorId)
	}

	/**	Step 10: a new 32-bit id is upgraded and adopted. A store that has heard nothing mints 32 bits,
		new; the account answers a 64-bit `pz_visitor_id`, which the next request sends beside the
		session that came with it - and the session goes on.
	 **/
	@Test fun step10_aNew32BitIdIsUpgradedAndAdopted() = runBlocking<Unit> {
		assumeTrue("PERSONYZE_API_KEY not set", key != null)
		val recorder = Recorder()
		val e = engine(transport = recorder)
		val first = e.navigate("androidsdk-conformance/home", null)
		assertNull(first.error)
		val sent = recorder.bodies.single()
		val minted = sent.text("pz_visitor_id")!!.toLong()
		assertTrue("the first run mints 32 bits: $minted", minted in 500_000_000L..Int.MAX_VALUE.toLong())
		assertEquals(JsonPrimitive(true), sent["pz_visitor_new"])
		val adopted = answer(first).text("pz_visitor_id")!!
		assertNotEquals(minted.toString(), adopted)
		assertFalse("upgraded to 64 bits: $adopted", fits32(adopted.toLong()))
		assertEquals(adopted.toLong(), Session.visitor(first.sessionId))
		assertEquals(adopted.toLong(), e.visitor.value.visitorId)
		val second = e.navigate("androidsdk-conformance/product", null)
		assertNull(second.error)
		val next = recorder.bodies[1]
		assertEquals(JsonPrimitive(adopted), next["pz_visitor_id"])
		assertNull(next["pz_visitor_new"])
		assertEquals("beside the session it came with", first.sessionId, next.text("session_id"))
		assertEquals(JsonPrimitive(false), next["new_session"])
		assertEquals(adopted, answer(second).text("pz_visitor_id"))
		assertEquals(adopted.toLong(), Session.visitor(second.sessionId))
		assertEquals(Session.startTime(first.sessionId), Session.startTime(second.sessionId))
		assertEquals(1, Session.counter(second.sessionId))
	}

	/**	Beyond the steps: the templates of what was decided, and the notification endpoint, answer
		without error.
	 **/
	@Test fun extras_templatesAndNotifications() = runBlocking<Unit> {
		assumeTrue("PERSONYZE_API_KEY not set", key != null)
		val e = engine()
		e.setUserId(userBase + 9L)
		val r = e.navigate("androidsdk-conformance/home", null)
		assertNull(r.error)
		e.clearCache()
		assertNull(e.pollNotification(force = true))
		delay(200)
		assertEquals(emptyList<Any>(), errors.map { it.first.message })
		System.getenv("PERSONYZE_AB_CONDITION_ID")?.toIntOrNull()?.let { id ->
			val v = e.verdict(id)
			assertTrue("verdict: ${v.exceptionOrNull()}", v.isSuccess)
			assertEquals(id, v.getOrThrow().conditionId)
		}
	}
}
