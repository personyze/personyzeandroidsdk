package com.personyze.android.internal.core

import com.personyze.android.PersonyzeException
import com.personyze.android.PersonyzeException.Transport.Kind
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**	The REST client over the real OkHttp transport and a local server, in real time. **/
class TransportTest
{	private lateinit var server: MockWebServer
	private val clock = { System.nanoTime()/1_000_000 }

	@Before fun start()
	{	server = MockWebServer()
		server.start()
	}

	@After fun stop()
	{	server.close()
	}

	private fun rest(base: String = server.url("/").toString().trimEnd('/'), retries: Int = 1) =
		RestClient(OkHttpTransport(null) { true }, KEY, base, retries, clock, "personyze-android/test")

	@Test fun basicAuthWithoutTrailingNewline() = runBlocking<Unit> {
		server.enqueue(MockResponse.Builder().code(200).body("{}").build())
		rest().call("POST", "tracker-v1", "{\"a\":1}", 2000)
		val request = server.takeRequest()
		assertEquals("POST", request.method)
		assertEquals("/rest/tracker-v1", request.target)
		val auth = request.headers["Authorization"]!!
		assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("api:$KEY".toByteArray()), auth)
		assertFalse(auth.contains("\n"))
		assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
		assertEquals("personyze-android/test", request.headers["User-Agent"])
		assertEquals("{\"a\":1}", request.body!!.utf8())
	}

	/**	v1 (Volley) decoded a body without a charset as ISO-8859-1. **/
	@Test fun bodyWithoutCharsetIsUtf8() = runBlocking<Unit> {
		server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body("{\"t\":\"שלום\"}").build())
		val r = rest().call("GET", "x", null, 2000) as RestResult.Ok
		assertEquals("{\"t\":\"שלום\"}", r.body)
	}

	@Test fun statusMapping() = runBlocking<Unit> {
		server.enqueue(MockResponse.Builder().code(400).body("No Product ID specified").build())
		val bad = rest().call("POST", "tracker-v1", "{}", 2000) as RestResult.Failed
		assertEquals("No Product ID specified", (bad.error as PersonyzeException.BadRequest).text)
		assertEquals(400, (bad.error as PersonyzeException.BadRequest).status)

		server.enqueue(MockResponse.Builder().code(401).body("Invalid API key").build())
		assertTrue((rest().call("POST", "tracker-v1", "{}", 2000) as RestResult.Failed).error is PersonyzeException.Auth)

		server.enqueue(MockResponse.Builder().code(404).body("").build())
		assertEquals(404, ((rest().call("GET", "x", null, 2000) as RestResult.Failed).error as PersonyzeException.BadRequest).status)

		// A redirect is not followed: the base URL is wrong.
		server.enqueue(MockResponse.Builder().code(301).setHeader("Location", "https://elsewhere/").build())
		assertEquals(301, ((rest().call("GET", "x", null, 2000) as RestResult.Failed).error as PersonyzeException.BadRequest).status)
		assertEquals(4, server.requestCount)
	}

	/**	429 is the account's state: not retried, and nothing was logged. **/
	@Test fun throttledIsNotRetried() = runBlocking<Unit> {
		server.enqueue(MockResponse.Builder().code(429).body("Tracking is paused on this account").build())
		val r = rest().call("POST", "tracker-v1", "{}", 2000) as RestResult.Failed
		assertEquals(Kind.RATE_LIMITED, (r.error as PersonyzeException.Transport).kind)
		assertFalse(r.maybeDelivered)
		assertEquals(1, server.requestCount)
	}

	@Test fun fiveHundredIsRetriedOnce() = runBlocking<Unit> {
		server.enqueue(MockResponse.Builder().code(503).build())
		server.enqueue(MockResponse.Builder().code(200).body("ok").build())
		assertEquals("ok", (rest().call("POST", "tracker-v1", "{}", 3000) as RestResult.Ok).body)
		assertEquals(2, server.requestCount)

		server.enqueue(MockResponse.Builder().code(500).build())
		server.enqueue(MockResponse.Builder().code(500).build())
		server.enqueue(MockResponse.Builder().code(200).build())
		val r = rest().call("POST", "tracker-v1", "{}", 3000) as RestResult.Failed
		assertEquals(Kind.SERVER, (r.error as PersonyzeException.Transport).kind)
		assertTrue(r.maybeDelivered)
		assertEquals(4, server.requestCount)
	}

	@Test fun noRetryWhenRetriesIsZero() = runBlocking<Unit> {
		server.enqueue(MockResponse.Builder().code(500).build())
		rest(retries = 0).call("POST", "tracker-v1", "{}", 3000)
		assertEquals(1, server.requestCount)
	}

	/**	The budget covers the whole body: one arriving a byte every 30 ms fails at the deadline, not
		after it (SPEC section 5 - Python's urllib took 3.7 s with timeout=1).
	 **/
	@Test fun trickledBodyFailsAtTheDeadline() = runBlocking<Unit> {
		server.enqueue(MockResponse.Builder().code(200).body("x".repeat(200)).throttleBody(1, 30, TimeUnit.MILLISECONDS).build())
		val started = clock()
		val r = rest().call("POST", "tracker-v1", "{}", 800) as RestResult.Failed
		val took = clock() - started
		assertTrue("took $took ms", took < 1500)
		assertEquals(Kind.TIMEOUT, (r.error as PersonyzeException.Transport).kind)
		assertTrue(r.maybeDelivered)
	}

	/**	Refused before anything was sent: safe to keep the events for later. **/
	@Test fun refusedConnectionWasNotSent() = runBlocking<Unit> {
		val port = ServerSocket(0).use { it.localPort }
		val r = rest(base = "http://127.0.0.1:$port").call("POST", "tracker-v1", "{}", 1500) as RestResult.Failed
		assertEquals(Kind.CONNECTION, (r.error as PersonyzeException.Transport).kind)
		assertFalse(r.maybeDelivered)
	}

	/**	The connection broke after the request went out: maybe delivered, retried at most once. **/
	@Test fun brokenAfterSendingIsMaybeDelivered() = runBlocking<Unit> {
		server.enqueue(MockResponse.Builder().code(200).body("x").onResponseStart(SocketEffect.ShutdownConnection).build())
		server.enqueue(MockResponse.Builder().code(200).body("x").onResponseStart(SocketEffect.ShutdownConnection).build())
		server.enqueue(MockResponse.Builder().code(200).body("never").build())
		val r = rest().call("POST", "tracker-v1", "{}", 3000) as RestResult.Failed
		assertTrue(r.maybeDelivered)
		assertTrue(server.requestCount <= 2)
	}

	@Test fun offlineSendsNothing() = runBlocking<Unit> {
		val r = RestClient(OkHttpTransport(null) { false }, KEY, server.url("/").toString().trimEnd('/'), 1, clock, "t").call("POST", "tracker-v1", "{}", 1000) as RestResult.Failed
		assertEquals(Kind.OFFLINE, (r.error as PersonyzeException.Transport).kind)
		assertFalse(r.maybeDelivered)
		assertEquals(0, server.requestCount)
	}

	/**	A server message that echoes the key never carries it into a log. **/
	@Test fun echoedKeyIsRedacted() = runBlocking<Unit> {
		server.enqueue(MockResponse.Builder().code(400).body("bad key $KEY here").build())
		val r = rest().call("GET", "x", null, 2000) as RestResult.Failed
		assertFalse(r.error.message!!.contains(KEY))
		assertTrue(r.error.message!!.contains("***"))
	}
}
