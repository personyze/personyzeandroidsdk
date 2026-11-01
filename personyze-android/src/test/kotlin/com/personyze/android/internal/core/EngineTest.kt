package com.personyze.android.internal.core

import com.personyze.android.PersonyzeException
import com.personyze.android.PersonyzeException.Delivery.Reason
import com.personyze.android.PersonyzeException.Transport.Kind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineTest
{	private val nav = { doc: String -> listOf("Navigate", "urn:personyze:doc:$doc") }

	private fun TestScope.trackerHarness(
		store: Store = MemoryStore(),
		autoFlushMs: Long? = null,
		legacy: LegacySource? = null,
		configure: (FakeTracker) -> Unit = {}
	): Pair<Harness, FakeTracker>
	{	val platform = FakePlatform(testScheduler)
		val tracker = FakeTracker { platform.nowMs()/1000 }.also(configure)
		val h = harness(transport = FakeTransport().tracker(tracker.step()), store = store, autoFlushMs = autoFlushMs, legacy = legacy, platform = platform)
		return h to tracker
	}

	// ------------------------------------------------------------------------- SPEC section 3

	@Test fun firstNavigateOpensTheSession() = runTest {
		val (h, _) = trackerHarness()
		val r = h.engine.navigate("Home", null)
		val b = h.transport.bodies().single()
		assertNull(b.str("session_id"))
		assertTrue(b.bool("new_session"))
		val userId = b["user_id"]!!.jsonPrimitive
		assertFalse(userId.isString)
		assertTrue("anonymous ids are >= 500,000,000", userId.content.toLong() >= 500_000_000)
		assertEquals(listOf(nav("Home")), b.commands())
		assertEquals("Android", b.str("platform"))
		assertEquals("Android/14", b.str("os"))
		assertEquals("phone", b.str("device_type"))
		assertEquals("en-US,he-IL", b.str("languages"))
		assertEquals("1080x2400", b.str("screen"))
		assertEquals("3.0", b["time_zone"]!!.jsonPrimitive.content)
		assertFalse(b.bool("noti_enabled"))
		// The session being opened, as the web tag counts it (newest first, current included).
		assertEquals("${T0/1000}", b.str("past_sessions"))
		assertEquals(userId.content.toLong(), Session.visitor(r.sessionId))
		assertEquals(r.sessionId, h.engine.visitor.value.sessionId)
	}

	/**	Conformance step 7: three decisions in a row keep one session (counter stays 1). **/
	@Test fun threeNavigatesKeepOneSession() = runTest {
		val (h, _) = trackerHarness()
		val counters = (1..3).map { Session.counter(h.engine.navigate("Page$it", null).sessionId) }
		assertEquals(listOf(1, 1, 1), counters)
		val bodies = h.transport.bodies()
		assertEquals(listOf(true, false, false), bodies.map { it.bool("new_session") })
		assertEquals(bodies[1].str("session_id"), bodies[2].str("session_id"))
		assertEquals("${T0/1000}", bodies[2].str("past_sessions"))
	}

	@Test fun sessionExpiresAfterNinetyMinutes() = runTest {
		val (h, _) = trackerHarness()
		h.engine.navigate("A", null)
		h.platform.wallBase += Session.TTL_MS - Session.EXPIRY_MARGIN_MS
		val r = h.engine.navigate("B", null)
		val b = h.transport.bodies()[1]
		assertTrue(b.bool("new_session"))
		assertEquals(2, Session.counter(r.sessionId))
		// The new session's start first, then the previous one.
		val newStart = Session.startTime(r.sessionId)
		h.engine.navigate("C", null)
		assertEquals("$newStart,${T0/1000}", h.transport.bodies()[2].str("past_sessions"))
	}

	/**	Conformance step 4: a corrupt stored session with new_session recovers instead of failing. **/
	@Test fun corruptStoredSessionStartsOver() = runTest {
		val store = MemoryStore()
		store.write(Engine.STATE, """{"v":1,"apiKeyFp":"${Engine.sha256(KEY).take(16)}","session":"garbage"}""")
		val (h, _) = trackerHarness(store = store)
		val r = h.engine.navigate("Home", null)
		val b = h.transport.bodies().single()
		assertEquals("garbage", b.str("session_id"))
		assertTrue(b.bool("new_session"))
		assertNotNull(r.sessionId)
		assertFalse(r.failed)
	}

	@Test fun corruptStateFileIsQuarantined() = runTest {
		val store = MemoryStore()
		store.write(Engine.STATE, "{not json")
		val (h, _) = trackerHarness(store = store)
		h.engine.navigate("Home", null)
		assertEquals("{not json", (store as MemoryStore).files["state.json.corrupt"])
	}

	/**	Conformance step 8: one device, visitor A then visitor B - B is not answered as A. **/
	@Test fun visitorSwitchDropsTheOtherVisitorsSession() = runTest {
		val (h, _) = trackerHarness()
		h.engine.setUserId("alice@example.com")
		val a = h.engine.navigate("Home", null)
		assertEquals(Session.serverUserId("alice@example.com")!!.toLong(), Session.visitor(a.sessionId))
		h.engine.setUserId(777)
		val b = h.engine.navigate("Home", null)
		val body = h.transport.bodies()[1]
		assertNull("A's session is not sent for B", body.str("session_id"))
		assertTrue(body.bool("new_session"))
		assertEquals("777", body["user_id"]!!.jsonPrimitive.content)
		assertEquals(777L, Session.visitor(b.sessionId))
		assertEquals(777, h.engine.visitor.value.visitorId)
	}

	@Test fun logoutReturnsToTheInstallVisitor() = runTest {
		val (h, _) = trackerHarness()
		h.engine.navigate("Home", null)
		val install = h.transport.bodies()[0]["user_id"]!!.jsonPrimitive.content
		h.engine.setUserId("bob")
		h.engine.navigate("Home", null)
		h.engine.setUserId(null)
		h.engine.navigate("Home", null)
		assertEquals(listOf(install, "bob", install), h.transport.bodies().map { it["user_id"]!!.jsonPrimitive.content })
	}

	@Test fun bigLongUserIdGoesAsText() = runTest {
		val (h, _) = trackerHarness()
		h.engine.setUserId(9_007_199_254_740_993L)
		h.engine.navigate("Home", null)
		val id = h.transport.bodies()[0]["user_id"]!!.jsonPrimitive
		assertTrue(id.isString)
		assertEquals("9007199254740993", id.content)
	}

	@Test fun startNewSessionForcesOneAfterTheQueuedEvents() = runTest {
		val (h, _) = trackerHarness()
		h.engine.navigate("Home", null)
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		h.engine.startNewSession()
		assertTrue(h.engine.result.value.isEmpty)
		h.engine.navigate("Home", null)
		val bodies = h.transport.bodies()
		assertEquals(3, bodies.size)
		// The event first, in the old session; then the navigation, opening a new one.
		assertEquals(listOf(listOf("Product Viewed", "A1")), bodies[1].commands())
		assertFalse(bodies[1].bool("new_session"))
		assertTrue(bodies[2].bool("new_session"))
		assertEquals(bodies[1].str("session_id"), bodies[2].str("session_id"))
	}

	// ----------------------------------------------------------------------------- requests

	@Test fun eventsGoInTheNavigationsRequest() = runTest {
		val (h, _) = trackerHarness()
		h.engine.enqueue { Commands.profile(mapOf("email" to "a@b.c")) }
		h.engine.navigate("Product") { h.engine.enqueue { Commands.product("Product Viewed", "A1000", null, null) } }
		assertEquals(listOf(listOf("User profile", "email", "a@b.c"), nav("Product"), listOf("Product Viewed", "A1000")), h.transport.bodies().single().commands())
		assertFalse(h.engine.hasPending())
	}

	@Test fun rawTrackGoesAlone() = runTest {
		val (h, _) = trackerHarness()
		h.engine.navigate("Home", null)
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		h.engine.enqueue(raw = true) { Commands.raw(listOf("custom thing", "1")) }
		h.engine.enqueue { Commands.product("Product Viewed", "A2", null, null) }
		h.engine.flush()
		assertEquals(listOf(listOf(listOf("Product Viewed", "A1")), listOf(listOf("custom thing", "1")), listOf(listOf("Product Viewed", "A2"))), h.transport.bodies().drop(1).map { it.commands() })
	}

	@Test fun bigQueueIsSplitInOrder() = runTest {
		val (h, _) = trackerHarness()
		h.engine.navigate("Home", null)
		for (i in 1..30)
		{	h.engine.enqueue { Commands.profile(mapOf("f$i" to "x".repeat(4000))) }
		}
		h.engine.flush()
		val sent = h.transport.bodies().drop(1)
		assertTrue(sent.size >= 3)
		assertTrue(sent.all { Wire.requestBody(JsonPrimitive(1), null, false, emptyList(), h.platform.device, false, it.commands()).length <= Commands.MAX_BODY })
		assertEquals((1..30).map { "f$it" }, sent.flatMap { it.commands() }.map { it[1] })
	}

	@Test fun invalidInputIsReportedNotSent() = runTest {
		val (h, _) = trackerHarness()
		h.engine.enqueue { Commands.product("Product Viewed", " ", null, null) }
		h.engine.enqueue { Commands.promotion("promotion viewed", "P", 0) }
		advanceUntilIdle()
		assertFalse(h.engine.hasPending())
		assertEquals(2, h.errors.count { it.first is PersonyzeException.Invalid })
		val r = h.engine.navigate("", null)
		assertTrue(r.error is PersonyzeException.Invalid)
		assertTrue(h.transport.requests.isEmpty())
	}

	// ------------------------------------------------------------------- failing (SPEC section 5)

	@Test fun serverErrorFailsOpenAfterOneRetry() = runTest {
		val h = harness(transport = FakeTransport().tracker(status(500), status(503)))
		val r = h.engine.navigate("Home", null)
		assertTrue(r.failed)
		assertTrue(r.isEmpty)
		assertEquals(Kind.SERVER, (r.error as PersonyzeException.Transport).kind)
		assertEquals(2, h.transport.requests.size)
		advanceUntilIdle()
		assertEquals(1, h.errors.size)
	}

	@Test fun authErrorIsNotRetriedAndNotThrown() = runTest {
		val h = harness(transport = FakeTransport().tracker(status(401, "Invalid API key")))
		val r = h.engine.navigate("Home", null)
		assertTrue(r.error is PersonyzeException.Auth)
		assertEquals(1, h.transport.requests.size)
	}

	@Test fun badRequestDropsTheBatch() = runTest {
		val h = harness(transport = FakeTransport().tracker(status(400, "No Product ID specified")))
		h.engine.enqueue { Commands.raw(listOf("Product Viewed", "")) }
		h.engine.flush()
		advanceUntilIdle()
		assertFalse(h.engine.hasPending())
		val (e, c) = h.errors.single()
		assertEquals(Reason.REFUSED, (e as PersonyzeException.Delivery).reason)
		assertEquals(1, c.dropped)
		assertEquals("No Product ID specified", (e.cause as PersonyzeException.BadRequest).text)
	}

	/**	429: fail open, no retry, and no request at all for the next 10 minutes. **/
	@Test fun throttledAccountBacksOff() = runTest {
		val throttled = harness(transport = FakeTransport().tracker(status(429, "Tracking is paused on this account")))
		val r1 = throttled.engine.navigate("Home", null)
		assertEquals(Kind.RATE_LIMITED, (r1.error as PersonyzeException.Transport).kind)
		val r2 = throttled.engine.navigate("Home", null)
		assertEquals(Kind.RATE_LIMITED, (r2.error as PersonyzeException.Transport).kind)
		assertEquals(1, throttled.transport.requests.size)
		throttled.platform.wallBase += Engine.THROTTLE_MS
		throttled.engine.navigate("Home", null)
		assertEquals(2, throttled.transport.requests.size)
	}

	/**	Never sent (no network, refused): the events wait for later, the navigation does not. **/
	@Test fun notSentKeepsTheEventsButNotTheNavigation() = runTest {
		val platform = FakePlatform(testScheduler)
		val tracker = FakeTracker { platform.nowMs()/1000 }
		val h = harness(transport = FakeTransport().tracker(fail(sent = false), fail(sent = false), tracker.step()), platform = platform)
		val r = h.engine.navigate("Home") { h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) } }
		assertEquals(Kind.CONNECTION, (r.error as PersonyzeException.Transport).kind)
		assertTrue(h.engine.hasPending())
		assertEquals(1, h.platform.outboxScheduled)
		advanceUntilIdle()
		assertEquals(1, h.errors.single().second.deferred)
		h.engine.navigate("Next", null)
		assertEquals(listOf(listOf("Product Viewed", "A1"), nav("Next")), h.transport.bodies().last().commands())
	}

	@Test fun offlineKeepsTheEvents() = runTest {
		val h = harness(transport = FakeTransport().tracker({ HttpOutcome.Failed(sent = false, timedOut = false, offline = true, cause = null) }))
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		h.engine.flush()
		assertTrue(h.engine.hasPending())
		assertEquals(1, h.transport.requests.size)
	}

	/**	Maybe delivered: not sent again (a purchase counted twice is worse than one missing). **/
	@Test fun ambiguousFailureDropsAndReportsTheEvents() = runTest {
		val h = harness(transport = FakeTransport().tracker(fail(sent = true), fail(sent = true)))
		h.engine.enqueue { Commands.product("Product Purchased", "A1", 88, 1) }
		h.engine.flush()
		advanceUntilIdle()
		assertFalse(h.engine.hasPending())
		assertEquals(2, h.transport.requests.size)
		val e = h.errors.single().first as PersonyzeException.Delivery
		assertEquals(Reason.AMBIGUOUS, e.reason)
		assertEquals(listOf(listOf("Product Purchased", "A1", "action_id", "88", "quantity", "1")), e.commands)
	}

	@Test fun slowAnswerFailsAtTheBudget() = runTest {
		val h = harness(transport = FakeTransport().tracker(ok(tracker(session(1, 1)), delayMs = 60_000)), timeoutMs = 5000)
		val started = testScheduler.currentTime
		val r = h.engine.navigate("Home", null)
		assertEquals(Kind.TIMEOUT, (r.error as PersonyzeException.Transport).kind)
		assertTrue(testScheduler.currentTime - started <= 5000)
	}

	@Test fun malformedAnswerFailsOpen() = runTest {
		val h = harness(transport = FakeTransport().tracker(ok("<html>oops</html>")))
		val r = h.engine.navigate("Home", null)
		assertEquals(Kind.MALFORMED_RESPONSE, (r.error as PersonyzeException.Transport).kind)
	}

	/**	A request in flight when the app died may have arrived: dropped at the next start, reported. **/
	@Test fun inflightAtStartupIsDropped() = runTest {
		val store = MemoryStore()
		val item = PendingItem(listOf("Product Purchased", "A1"), JsonPrimitive(500000001), 0, 0, T0, inflight = true, seq = 1)
		val kept = PendingItem(listOf("Product Viewed", "A2"), JsonPrimitive(500000001), 0, 0, T0, seq = 2)
		store.write(Engine.OUTBOX, Wire.json.encodeToString(OutboxFile.serializer(), OutboxFile(items = listOf(item, kept))))
		val h = harness(store = store)
		advanceUntilIdle()
		assertEquals(Reason.INTERRUPTED, (h.errors.single().first as PersonyzeException.Delivery).reason)
		assertTrue(h.engine.diagnostics().contains("Product Viewed"))
		assertFalse(h.engine.diagnostics().contains("Product Purchased"))
	}

	/**	A screen closed mid-request: the request still completes and its events are not stranded. **/
	@Test fun cancelledCallerDoesNotStrandEvents() = runTest {
		val platform = FakePlatform(testScheduler)
		val tracker = FakeTracker { platform.nowMs()/1000 }
		val slow: Step = { r -> kotlinx.coroutines.delay(1000); tracker.step()(r) }
		val h = harness(transport = FakeTransport().tracker(slow), platform = platform)
		val job = launch { h.engine.navigate("Home") { h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) } } }
		advanceTimeBy(500)
		job.cancelAndJoin()
		advanceUntilIdle()
		assertFalse(h.engine.hasPending())
		assertNotNull(h.engine.visitor.value.sessionId)
	}

	@Test fun eventsOfAVisitorWhoLeftAreDroppedWithoutALiveSession() = runTest {
		val (h, _) = trackerHarness()
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }	// anonymous, no session yet
		h.engine.setUserId("carol")
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		assertEquals(listOf(nav("Home")), h.transport.bodies().single().commands())
		assertEquals(Reason.OTHER_VISITOR, (h.errors.single().first as PersonyzeException.Delivery).reason)
	}

	@Test fun previousVisitorsEventsGoUnderTheirOwnLiveSession() = runTest {
		val (h, _) = trackerHarness()
		h.engine.navigate("Home", null)
		val anonymous = h.transport.bodies()[0]["user_id"]!!.jsonPrimitive.content
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		h.engine.setUserId("dave")
		h.engine.navigate("Account", null)
		val bodies = h.transport.bodies()
		assertEquals(anonymous, bodies[1]["user_id"]!!.jsonPrimitive.content)
		assertEquals(listOf(listOf("Product Viewed", "A1")), bodies[1].commands())
		assertFalse(bodies[1].bool("new_session"))
		assertEquals("dave", bodies[2].str("user_id"))
		assertTrue(bodies[2].bool("new_session"))
	}

	@Test fun actionReportOfAnEndedSessionIsDropped() = runTest {
		val (h, _) = trackerHarness()
		h.engine.navigate("Home", null)
		h.engine.enqueue { Commands.actionStatus(88, "executed") }
		h.platform.wallBase += Session.TTL_MS
		h.engine.navigate("Later", null)
		advanceUntilIdle()
		assertEquals(listOf(nav("Later")), h.transport.bodies().last().commands())
		assertEquals(Reason.OTHER_SESSION, (h.errors.single().first as PersonyzeException.Delivery).reason)
	}

	@Test fun queueIsCapped() = runTest {
		val h = harness()
		repeat(Engine.MAX_PENDING + 5) { h.engine.enqueue { Commands.product("Product Viewed", "A$it", null, null) } }
		advanceUntilIdle()
		assertTrue(h.errors.any { (it.first as? PersonyzeException.Delivery)?.reason == Reason.OVERFLOW })
	}

	// ------------------------------------------------------------------------- auto flush

	@Test fun autoFlushSendsIntoTheLiveSession() = runTest {
		val (h, _) = trackerHarness(autoFlushMs = 3000)
		h.engine.navigate("Home", null)
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		advanceTimeBy(2900)
		runCurrent()
		assertEquals(1, h.transport.requests.size)
		advanceTimeBy(200)
		runCurrent()
		assertEquals(2, h.transport.requests.size)
		assertFalse(h.transport.bodies()[1].bool("new_session"))
	}

	/**	Events alone would open a session with a visit of no page: they wait for the navigation. **/
	@Test fun autoFlushWaitsWithoutALiveSession() = runTest {
		val (h, _) = trackerHarness(autoFlushMs = 3000)
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		advanceTimeBy(10_000)
		runCurrent()
		assertTrue(h.transport.requests.isEmpty())
		assertTrue(h.engine.flushLive(10_000, "flush"))
		assertTrue(h.transport.requests.isEmpty())
	}

	// --------------------------------------------------------------------- decisions & reports

	@Test fun navigationReplacesOtherRequestsMerge() = runTest {
		val (h, t) = trackerHarness { it.conditions = listOf(1); it.actionsOnlyWithNavigate = false }
		h.transport.on("/rest/conditions/", ok("[{\"id\":1,\"name\":\"C1\"},{\"id\":2,\"name\":\"C2\"}]"))
		h.transport.on("/rest/actions/", ok("[]"))
		t.actions = { """[{"id":88,"data":{"data":"x"}},{"id":89}]""" }
		val first = h.engine.navigate("Home", null)
		assertEquals(listOf(88, 89), first.actions.map { it.id })
		t.conditions = listOf(2)
		t.actions = { """[{"id":90}]""" }
		t.dismissActions = listOf(89)
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		val merged = h.engine.refresh()
		assertEquals(listOf(88, 90), merged.actions.map { it.id })
		assertEquals(listOf(1, 2), merged.conditions.map { it.id })
		assertEquals(listOf(89), merged.dismissedActions)
		t.actions = { "[]" }
		assertTrue(h.engine.navigate("Other", null).actions.isEmpty())
	}

	@Test fun refreshWithNothingQueuedAnswersTheCurrentDecision() = runTest {
		val (h, _) = trackerHarness()
		h.engine.navigate("Home", null)
		h.engine.refresh()
		assertEquals(1, h.transport.requests.size)
	}

	@Test fun executedOncePerPage() = runTest {
		val (h, _) = trackerHarness()
		h.engine.navigate("Home", null)
		h.engine.enqueue { Commands.actionStatus(88, "executed") }
		h.engine.enqueue { Commands.actionStatus(88, "executed") }
		h.engine.enqueue { Commands.actionStatus(88, "target") }
		h.engine.enqueue { Commands.actionStatus(88, "target") }
		h.engine.flush()
		assertEquals(listOf(listOf("Action Status", "88", "executed", ""), listOf("Action Status", "88", "target", "")), h.transport.bodies()[1].commands())
		h.engine.navigate("Again", null)
		h.engine.enqueue { Commands.actionStatus(88, "executed") }
		h.engine.flush()
		assertEquals(listOf(listOf("Action Status", "88", "executed", "")), h.transport.bodies()[3].commands())
	}

	/**	"Don't show for N sessions" is the SDK's: the action is dropped from answers, reported
		`dont-show`, until N new sessions have started.
	 **/
	@Test fun closedActionIsBlockedForSessions() = runTest {
		val (h, t) = trackerHarness { it.actions = { """[{"id":88}]""" } }
		h.transport.on("/rest/actions/", ok("[]"))
		assertTrue(h.engine.navigate("Home", null).has(88))
		h.engine.enqueue { Commands.actionStatus(88, "close", "1") }
		assertFalse("a close takes it off the screen at once", h.engine.result.value.has(88))
		assertFalse(h.engine.navigate("Home", null).has(88))
		h.engine.flush()
		assertEquals(listOf("Action Status", "88", "dont-show", ""), h.transport.bodies().last().commands().last())
		h.platform.wallBase += Session.TTL_MS
		assertTrue("one new session later it is back", h.engine.navigate("Home", null).has(88))
		assertEquals(3, t.seen.count { it.commands().any { c -> c[0] == "Navigate" } })
	}

	/**	A server that answered the blocked action to every request got a `dont-show` for each, and
		answered that with the action again - an endless loop (found by this test, as an out of memory).
	 **/
	@Test fun blockedActionIsReportedOncePerPage() = runTest {
		val (h, _) = trackerHarness { it.actions = { """[{"id":88}]""" }; it.actionsOnlyWithNavigate = false }
		h.transport.on("/rest/actions/", ok("[]"))
		h.engine.navigate("Home", null)
		h.engine.enqueue { Commands.actionStatus(88, "close", "3") }
		h.engine.navigate("Home", null)
		h.engine.flush()
		h.engine.flush()
		val dontShow = h.transport.bodies().flatMap { it.commands() }.count { it.getOrNull(2) == "dont-show" }
		assertEquals(1, dontShow)
	}

	@Test fun blockedActionsSurviveARestart() = runTest {
		val store = MemoryStore()
		val first = harness(store = store, transport = FakeTransport())
		first.engine.enqueue { Commands.actionStatus(88, "close", "5") }
		advanceUntilIdle()
		val (h, _) = trackerHarness(store = store)
		advanceUntilIdle()
		assertTrue(h.engine.diagnostics().contains("blocked={88=5}"))
	}

	// ----------------------------------------------------------------------------- metadata

	@Test fun templatesLoadAfterTheDecision() = runTest {
		val (h, t) = trackerHarness { it.conditions = listOf(7) }
		t.actions = { """[{"id":88,"data":{"data":"<b>Hi</b>"}}]""" }
		h.transport.on("/rest/conditions/columns/id,name/where/id:7", ok("""[{"id":7,"name":"Shoppers"}]"""))
		h.transport.on("/rest/actions/columns/", ok("""[{"id":88,"name":"Banner","content_type":"text/html","content_param":"data","content_begin":"<div>","content_end":"</div>","libs_app":"timer","placeholders":[635],"placeholder_order":2}]"""))
		h.transport.on("/rest/placeholders/columns/id,name,html_id,units_count_max/where/id:635", ok("""[{"id":635,"name":"Home banner","html_id":".app-home","units_count_max":1}]"""))
		val r = h.engine.navigate("Home", null)
		val a = r.action(88)!!
		assertTrue(a.isLoaded)
		assertEquals("<div><b>Hi</b></div>", a.content)
		assertEquals(listOf("timer"), a.libs)
		assertEquals(2, a.placeholderOrder)
		assertEquals("Shoppers", r.conditions.single().name)
		assertEquals(listOf(88), r.actionsIn("Home banner").map { it.id })
		assertEquals(listOf(88), r.actionsIn(".app-home").map { it.id })
		// Cached: the next decision asks for nothing more.
		val before = h.transport.requests.size
		h.engine.navigate("Again", null)
		assertEquals(before + 1, h.transport.requests.size)
	}

	@Test fun oldServerWithoutPlaceholderOrder() = runTest {
		val (h, t) = trackerHarness()
		t.actions = { """[{"id":88}]""" }
		h.transport.on("placeholder_order", status(400, "Unknown column placeholder_order"))
		h.transport.on("/rest/actions/columns/", ok("""[{"id":88,"name":"B","content_type":"text/html","content_param":"","content_begin":"x","content_end":"","libs_app":null,"placeholders":[]}]"""))
		val r = h.engine.navigate("Home", null)
		assertTrue(r.action(88)!!.isLoaded)
		assertNull(r.action(88)!!.placeholderOrder)
	}

	@Test fun newCacheVersionReloadsTemplates() = runTest {
		val (h, t) = trackerHarness()
		t.actions = { """[{"id":88}]""" }
		h.transport.on("/rest/actions/", ok("""[{"id":88,"name":"v5","content_type":"text/plain","placeholders":[]}]"""), ok("""[{"id":88,"name":"v6","content_type":"text/plain","placeholders":[]}]"""))
		assertEquals("v5", h.engine.navigate("Home", null).action(88)!!.name)
		t.cacheVersion = 6
		assertEquals("v6", h.engine.navigate("Home", null).action(88)!!.name)
	}

	/**	v1 never fetched the placeholders right after a cache clear. **/
	@Test fun clearCacheReloadsPlaceholdersToo() = runTest {
		val (h, t) = trackerHarness()
		t.actions = { """[{"id":88}]""" }
		h.transport.on("/rest/actions/", ok("""[{"id":88,"name":"B","content_type":"text/html","placeholders":[635]}]"""))
		h.transport.on("/rest/placeholders/", ok("""[{"id":635,"name":"P","html_id":"p","units_count_max":0}]"""))
		h.engine.navigate("Home", null)
		h.engine.clearCache()
		assertEquals(2, h.transport.requests.count { it.url.contains("/rest/placeholders/") })
		assertEquals("P", h.engine.result.value.action(88)!!.placeholders.single().name)
	}

	@Test fun idsTheServerDoesNotKnowAreNotAskedAgain() = runTest {
		val (h, t) = trackerHarness()
		t.actions = { """[{"id":88}]""" }
		h.transport.on("/rest/actions/", ok("[]"))
		h.engine.navigate("Home", null)
		h.engine.navigate("Home", null)
		assertEquals(1, h.transport.requests.count { it.url.contains("/rest/actions/") })
		assertFalse(h.engine.result.value.action(88)!!.isLoaded)
	}

	// ------------------------------------------------------------------------- persistence

	@Test fun stateSurvivesARestart() = runTest {
		val store = MemoryStore()
		val (h1, t) = trackerHarness(store = store) { it.actions = { """[{"id":88,"data":{"k":"v"}}]""" } }
		h1.transport.on("/rest/actions/", ok("[]"))
		val r1 = h1.engine.navigate("Home", null)
		advanceUntilIdle()
		val (h2, _) = trackerHarness(store = store)
		// A new cache directory: the templates are fetched again as it loads.
		h2.transport.on("/rest/actions/", ok("[]"))
		advanceUntilIdle()
		assertEquals(r1.sessionId, h2.engine.visitor.value.sessionId)
		assertEquals(mapOf("k" to "v"), h2.engine.result.value.dataFor(88))
		h2.engine.navigate("Next", null)
		assertEquals(r1.sessionId, h2.transport.bodies().single().str("session_id"))
		assertEquals(h1.transport.bodies()[0]["user_id"], h2.transport.bodies()[0]["user_id"])
		assertTrue(t.seen.isNotEmpty())
	}

	@Test fun anotherKeyIsAnotherAccount() = runTest {
		val store = MemoryStore()
		val (h1, _) = trackerHarness(store = store)
		h1.engine.navigate("Home", null)
		advanceUntilIdle()
		val platform = FakePlatform(testScheduler)
		val tracker = FakeTracker { platform.nowMs()/1000 }
		val h2 = harness(store = store, transport = FakeTransport().tracker(tracker.step()), platform = platform, apiKey = "FFFF" + KEY.drop(4))
		h2.engine.navigate("Home", null)
		val b = h2.transport.bodies().single()
		assertNull(b.str("session_id"))
		assertEquals(h1.transport.bodies()[0]["user_id"], b["user_id"])
	}

	@Test fun v1StateIsMigrated() = runTest {
		var deleted = false
		val legacy = object : LegacySource
		{	override fun read() = LegacyState(-123456, session(T0/1000 - 60, -123456, 4), listOf(100L, 200L, 300L), mapOf(88 to 2), false, T0 - 1000, KEY.hashCode())
			override fun delete()
			{	deleted = true
			}
		}
		val (h, _) = trackerHarness(legacy = legacy)
		h.engine.navigate("Home", null)
		val b = h.transport.bodies().single()
		assertEquals("-123456", b["user_id"]!!.jsonPrimitive.content)
		assertEquals(session(T0/1000 - 60, -123456, 4), b.str("session_id"))
		assertFalse(b.bool("new_session"))
		assertEquals("300,200,100", b.str("past_sessions"))
		assertTrue(deleted)
		assertTrue(h.engine.diagnostics().contains("blocked={88=2}"))
	}

	@Test fun v1StateOfAnotherKeyKeepsOnlyTheVisitor() = runTest {
		val legacy = object : LegacySource
		{	override fun read() = LegacyState(4242, "1 4242 7 1", listOf(100L), mapOf(88 to 2), true, 0, "other".hashCode())
			override fun delete() {}
		}
		val (h, _) = trackerHarness(legacy = legacy)
		h.engine.navigate("Home", null)
		val b = h.transport.bodies().single()
		assertEquals("4242", b["user_id"]!!.jsonPrimitive.content)
		assertNull(b.str("session_id"))
	}

	// ------------------------------------------------------------------------- clock

	/**	A device clock 3 hours fast would find every session expired and open a new one on every
		request; the Date header fixes it.
	 **/
	@Test fun deviceClockSkewIsCorrected() = runTest {
		val platform = FakePlatform(testScheduler)
		val server = { platform.nowMs() - 3*3600_000L }
		val tracker = FakeTracker { server()/1000 }
		val step: Step = { r -> (tracker.step()(r) as HttpOutcome.Answered).let { HttpOutcome.Answered(it.status, it.body, server()) } }
		val h = harness(transport = FakeTransport().tracker(step), platform = platform)
		h.engine.navigate("A", null)
		h.engine.navigate("B", null)
		assertFalse(h.transport.bodies()[1].bool("new_session"))
	}

	// ------------------------------------------------------------------------- notifications

	@Test fun notificationIsShownOncePerMessage() = runTest {
		val (h, _) = trackerHarness()
		h.transport.on("/rest/current_notification/", ok("""{"message_id":5,"title":"T","body":"B","actions":[]}"""))
		assertNull("no session yet", h.engine.pollNotification(force = true))
		h.engine.navigate("Home", null)
		val visitor = h.engine.visitor.value.visitorId
		val n = h.engine.pollNotification(force = true)
		assertEquals(5L, n?.messageId)
		val url = h.transport.requests.last().url
		assertTrue(url, url.endsWith("/rest/current_notification/where/user_id=$visitor"))
		assertNull("the queue still holds it; not shown twice", h.engine.pollNotification(force = true))
		assertNull("throttled to 15 minutes", h.engine.pollNotification(force = false))
	}

	@Test fun firstNotificationAfterTheV1UpgradeIsTakenAsShown() = runTest {
		val legacy = object : LegacySource
		{	override fun read() = LegacyState(4242, session(T0/1000 - 60, 4242), emptyList(), emptyMap(), false, T0 - 1000, KEY.hashCode())
			override fun delete() {}
		}
		val (h, _) = trackerHarness(legacy = legacy)
		h.transport.on("/rest/current_notification/", ok("""{"message_id":5,"title":"T","body":"B"}"""), ok("""{"message_id":6,"title":"T","body":"B"}"""))
		assertNull(h.engine.pollNotification(force = true))
		assertEquals(6L, h.engine.pollNotification(force = true)?.messageId)
	}

	/**	A report goes to the address the server gave, as it is, and without the API key: the key goes
		to `/rest` only.
	 **/
	@Test fun notificationReportGoesWithoutTheKey() = runTest {
		val h = harness()
		h.transport.on("/white/", ok(""))
		h.engine.reportNotification("https://pic.test/white/k=d57hr0hy9dp2/u=7/extra=1?p=p%2F1")
		val request = h.transport.requests.last()
		assertEquals("GET", request.method)
		assertEquals("https://pic.test/white/k=d57hr0hy9dp2/u=7/extra=1?p=p%2F1", request.url)
		assertNull(request.headers["Authorization"])
		assertEquals("personyze-android/test", request.headers["User-Agent"])
		h.engine.sendNotificationReports()
		assertEquals("sent, so not kept", 1, h.transport.requests.size)
	}

	@Test fun nothingToReportWithIsNotSent() = runTest {
		val h = harness()
		h.engine.reportNotification("")
		h.engine.reportNotification("http://pic.test/white/k=d57hr0hy9dp2/u=7")
		h.engine.reportNotification("javascript:alert(1)")
		assertTrue(h.transport.requests.isEmpty())
	}

	/**	A report the server did not get is kept - on disk, so a new process sends it too - and goes
		with the next check. Any answer below 500 is the server's: a 4xx is not sent again.
	 **/
	@Test fun aReportThatCannotGoNowGoesWithTheNextCheck() = runTest {
		val store = MemoryStore()
		val first = harness(store = store)
		first.transport.on("/white/", fail(sent = false))
		first.engine.reportNotification("https://pic.test/white/k=d57hr0hy9dp2/u=7")
		assertEquals("tried, and retried once", 2, first.transport.requests.size)
		advanceUntilIdle()
		assertTrue("a report is not an error the app hears of", first.errors.isEmpty())

		val second = harness(store = store)
		second.transport.on("/white/", status(404))
		second.engine.sendNotificationReports()
		assertEquals(listOf("https://pic.test/white/k=d57hr0hy9dp2/u=7"), second.transport.requests.map { it.url })
		second.engine.sendNotificationReports()
		assertEquals(1, second.transport.requests.size)
	}

	// ------------------------------------------------------------------------- push (SPEC section 11)

	private fun TestScope.pushHarness(answer: String = """{"push":true}"""): Harness
	{	val (h, _) = trackerHarness()
		h.platform.app = PushApp("android", "com.shop.app", false)
		h.platform.showable = true
		h.transport.on("/rest/push_token", ok(answer))
		return h
	}

	private fun pushBodies(h: Harness): List<JsonObject> =
		h.transport.requests.filter { it.url.endsWith("/rest/push_token") }.map { Wire.json.parseToJsonElement(it.body!!).jsonObject }

	/**	Registered once there is a visitor on the server, with the app - and again only when there is
		something new to say, or a week on.
	 **/
	@Test fun pushTokenIsRegisteredForTheVisitor() = runTest {
		val h = pushHarness()
		h.engine.setPushToken("dev:abc")
		advanceUntilIdle()
		assertTrue("no visitor yet", pushBodies(h).isEmpty())
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		val visitor = h.engine.visitor.value.visitorId!!
		assertEquals(listOf(buildJsonObject { put("user_id", visitor); put("token", "dev:abc"); put("platform", "android"); put("app_id", "com.shop.app"); put("sandbox", false); put("enabled", true) }), pushBodies(h))
		assertEquals("PUT", h.transport.requests.last().method)
		assertEquals("pushed: the background checks are few", 360L, h.engine.backgroundCheckMinutes())
		h.engine.registerPush()
		assertEquals("nothing new to say", 1, pushBodies(h).size)
		h.platform.wallBase += 8L*24*3600_000
		h.engine.registerPush()
		assertEquals("a week on", 2, pushBodies(h).size)
	}

	/**	Without a key for the platform the device is still registered - a key added later finds it -
		and the app goes on checking every 15 minutes.
	 **/
	@Test fun withoutAKeyTheChecksStayAsTheyWere() = runTest {
		val h = pushHarness("""{"push":false}""")
		h.engine.setPushToken("dev:abc")
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		assertEquals(1, pushBodies(h).size)
		assertEquals(15L, h.engine.backgroundCheckMinutes())
	}

	@Test fun notificationsTurnedOffTakeTheDeviceOff() = runTest {
		val h = pushHarness()
		h.engine.setPushToken("dev:abc")
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		h.platform.showable = false
		h.engine.registerPush()
		assertEquals(buildJsonObject { put("token", "dev:abc"); put("enabled", false) }, pushBodies(h).last())
		assertEquals(15L, h.engine.backgroundCheckMinutes())
		h.engine.registerPush()
		assertEquals("off once", 2, pushBodies(h).size)
	}

	/**	The forgotten visitor's notifications reach the device no more; the new visitor registers it. **/
	@Test fun resetTakesTheOldVisitorOffAndKeepsTheToken() = runTest {
		val h = pushHarness()
		h.engine.setPushToken("dev:abc")
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		val before = h.engine.visitor.value.visitorId
		h.engine.reset()
		assertEquals(buildJsonObject { put("token", "dev:abc"); put("enabled", false) }, pushBodies(h).last())
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		val after = h.engine.visitor.value.visitorId!!
		assertTrue(before != after)
		assertEquals(JsonPrimitive(after), pushBodies(h).last()["user_id"])
		assertEquals(3, pushBodies(h).size)
	}

	/**	A pushed notification is shown once; the app's own check does not show it again. **/
	@Test fun aPushedNotificationIsShownOnce() = runTest {
		val h = pushHarness()
		h.transport.on("/rest/current_notification/", ok("""{"message_id":5,"title":"T","body":"B"}"""))
		h.engine.navigate("Home", null)
		val pushed = NotificationPoll.parse("""{"message_id":5,"title":"T","body":"B"}""")!!
		assertTrue(h.engine.acceptPushed(pushed))
		assertFalse("pushed twice", h.engine.acceptPushed(pushed))
		assertNull("the check finds the same", h.engine.pollNotification(force = true))
	}

	/**	As the app comes to the front the visitor is there to see one: checked, but once a minute at most. **/
	@Test fun foregroundChecksAreOnceAMinute() = runTest {
		val (h, _) = trackerHarness()
		h.transport.on("/rest/current_notification/", ok("null"))
		h.engine.navigate("Home", null)
		val checks = { h.transport.requests.count { it.url.contains("/current_notification/") } }
		h.engine.pollNotification(force = false, foreground = true)
		h.engine.pollNotification(force = false, foreground = true)
		assertEquals(1, checks())
		h.platform.wallBase += 61_000
		h.engine.pollNotification(force = false, foreground = true)
		assertEquals(2, checks())
		h.engine.pollNotification(force = false)
		assertEquals("the background's 15 minutes", 2, checks())
	}

	@Test fun resetIsANewVisitor() = runTest {
		val (h, _) = trackerHarness()
		h.engine.navigate("Home", null)
		val before = h.transport.bodies()[0]["user_id"]
		h.engine.reset()
		h.engine.navigate("Home", null)
		val after = h.transport.bodies()[1]
		assertTrue(before != after["user_id"])
		assertNull(after.str("session_id"))
	}
}
