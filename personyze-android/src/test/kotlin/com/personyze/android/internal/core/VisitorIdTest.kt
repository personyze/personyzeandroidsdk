package com.personyze.android.internal.core

import com.personyze.android.PersonyzeException
import com.personyze.android.PersonyzeResult
import com.personyze.android.PersonyzeException.Delivery.Reason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**	Protocol 2 (SPEC sections 1, 3 and 11): the install's visitor at any width - minted at the width
	the account last answered, sent as `pz_visitor_id` with `pz_visitor_new` until an answer names
	it, replaced by the answer's together with its session - and a login's `user_id` as it was.
 **/
@OptIn(ExperimentalCoroutinesApi::class)
class VisitorIdTest
{	private val nav = { doc: String -> listOf("Navigate", "urn:personyze:doc:$doc") }

	private fun TestScope.trackerHarness(
		store: Store = MemoryStore(),
		random: java.util.Random = ScriptedRandom(),
		first: Step? = null,
		configure: (FakeTracker) -> Unit = {}
	): Pair<Harness, FakeTracker>
	{	val platform = FakePlatform(testScheduler)
		val tracker = FakeTracker { platform.nowMs()/1000 }.also(configure)
		val transport = if (first != null) FakeTransport().tracker(first, tracker.step()) else FakeTransport().tracker(tracker.step())
		return harness(transport = transport, store = store, platform = platform, random = random) to tracker
	}

	private fun fp(): String = Engine.sha256(KEY).take(16)

	private fun state(session: String?, delivered: Map<String, Long> = emptyMap(), token: String? = null): String =
		Wire.json.encodeToString(PersistedState.serializer(), PersistedState(apiKeyFp = fp(), session = session, notifications = PersistedNotifications(delivered = delivered), push = PersistedPush(token = token)))

	private fun Harness.idBits(): Int = Regex("idBits=(\\d+)").find(engine.diagnostics())!!.groupValues[1].toInt()

	/**	The first run has heard no `id_bits`: 32 bits, from the random the engine is given. **/
	@Test fun firstRunMints32Bits() = runTest {
		val store = MemoryStore()
		val (h, _) = trackerHarness(store = store, random = ScriptedRandom(ints = listOf(123)))
		advanceUntilIdle()
		assertEquals("written at once, new", "500000123 new", store.read(Engine.VISITOR))
		h.engine.navigate("Home", null)
		val b = h.transport.bodies().single()
		assertEquals("500000123", b.str("pz_visitor_id"))
		assertTrue(b.bool("pz_visitor_new"))
		assertEquals("named by the answer", "500000123", store.read(Engine.VISITOR))
		assertEquals(32, h.idBits())
	}

	/**	New until an answer names the id - across a failure and a restart; never inferred. **/
	@Test fun newUntilAnAnswerNamesIt() = runTest {
		val store = MemoryStore()
		val first = harness(store = store, transport = FakeTransport().tracker(fail(sent = false)))
		assertTrue(first.engine.navigate("Home", null).failed)
		val minted = first.transport.bodies().map { it.str("pz_visitor_id") }.distinct().single()
		assertEquals(listOf(true, true), first.transport.bodies().map { it.bool("pz_visitor_new") })

		val (h, _) = trackerHarness(store = store)
		h.engine.navigate("Home", null)
		h.engine.navigate("Next", null)
		val bodies = h.transport.bodies()
		assertEquals(listOf(minted, minted), bodies.map { it.str("pz_visitor_id") })
		assertEquals(listOf(true, false), bodies.map { it.containsKey("pz_visitor_new") })
	}

	/**	Conformance step 10, offline: a new 32-bit id on an account that mints 64 is answered a
		64-bit one, which the next request sends - beside the session it came with, which goes on.
	 **/
	@Test fun aNewIdIsUpgradedAndAdoptedWithItsSession() = runTest {
		val store = MemoryStore()
		val (h, t) = trackerHarness(store = store, random = ScriptedRandom(ints = listOf(7))) { it.mints64 = true }
		val r1 = h.engine.navigate("Home", null)
		val adopted = FakeTracker.derive(500_000_007L, 64)
		assertEquals(adopted, t.visitors[0].first)
		assertTrue(t.visitors[0].second)
		assertEquals(adopted, Session.visitor(r1.sessionId))
		assertEquals(adopted, h.engine.visitor.value.visitorId)
		assertEquals("the new id, no longer new, saved at once", adopted.toString(), store.read(Engine.VISITOR))
		assertEquals(64, h.idBits())

		val r2 = h.engine.navigate("Next", null)
		val b = h.transport.bodies()[1]
		assertEquals(adopted.toString(), b.str("pz_visitor_id"))
		assertNull(b["pz_visitor_new"])
		assertEquals(r1.sessionId, b.str("session_id"))
		assertFalse(b.bool("new_session"))
		assertEquals(r1.sessionId, r2.sessionId)
		assertEquals(1, Session.counter(r2.sessionId))
		assertEquals(adopted to false, t.visitors[1])
		assertTrue(h.transport.bodies().all { it["protocol"] == JsonPrimitive(2) })

		// And after a restart, the same.
		val (h2, _) = trackerHarness(store = store) { it.mints64 = true }
		advanceUntilIdle()
		assertEquals(adopted, h2.engine.visitor.value.visitorId)
		h2.engine.navigate("Again", null)
		assertEquals(r1.sessionId, h2.transport.bodies().single().str("session_id"))
	}

	/**	A replaced visitor's request without a page logs nothing and opens no session - the id is
		adopted all the same, and the next page sends it, no longer new.
	 **/
	@Test fun aReplacementWithoutAPageIsAdoptedToo() = runTest {
		val (h, t) = trackerHarness(random = ScriptedRandom(ints = listOf(9))) { it.mints64 = true }
		h.engine.refresh()
		assertNull(h.engine.visitor.value.sessionId)
		val adopted = FakeTracker.derive(500_000_009L, 64)
		assertEquals(adopted, h.engine.visitor.value.visitorId)
		h.engine.navigate("Home", null)
		val b = h.transport.bodies()[1]
		assertEquals(adopted.toString(), b.str("pz_visitor_id"))
		assertNull(b["pz_visitor_new"])
		assertEquals(adopted to false, t.visitors[1])
	}

	/**	Events that waited on the device for the old id are the new id's: the same person. **/
	@Test fun queuedEventsFollowTheAdoptedId() = runTest {
		val platform = FakePlatform(testScheduler)
		val tracker = FakeTracker { platform.nowMs()/1000 }.also { it.mints64 = true }
		val slow: Step = { r -> delay(1000); tracker.step()(r) }
		val h = harness(transport = FakeTransport().tracker(slow, tracker.step()), platform = platform)
		launch { h.engine.navigate("Home", null) }
		advanceTimeBy(500)
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		advanceUntilIdle()
		val adopted = h.engine.visitor.value.visitorId!!
		assertTrue("past 32 bits", adopted > Int.MAX_VALUE)
		h.engine.flush()
		advanceUntilIdle()
		val b = h.transport.bodies().last()
		assertEquals(listOf(listOf("Product Viewed", "A1")), b.commands())
		assertEquals(adopted.toString(), b.str("pz_visitor_id"))
		assertFalse(b.bool("new_session"))
		assertTrue("nothing dropped as another visitor's: ${h.errors}", h.errors.isEmpty())
	}

	/**	The last `id_bits` is kept, on disk: a `reset()` mints at 64 bits where the account does, and
		the server keeps the new 64-bit id as it is.
	 **/
	@Test fun idBitsDecideTheNextMint() = runTest {
		val store = MemoryStore()
		val (h, t) = trackerHarness(store = store, random = ScriptedRandom(bytes = listOf("2455641835E6A5AA", "EAE6730296F73CB1"))) { it.mints64 = true }
		h.engine.navigate("Home", null)
		h.engine.reset()
		assertEquals(64, h.idBits())
		assertEquals("-1520401369231049551 new", store.read(Engine.VISITOR))
		val r = h.engine.navigate("Home", null)
		val b = h.transport.bodies()[1]
		assertEquals("-1520401369231049551", b.str("pz_visitor_id"))
		assertTrue(b.bool("pz_visitor_new"))
		assertEquals(-1520401369231049551L to false, t.visitors[1])
		assertEquals(-1520401369231049551L, Session.visitor(r.sessionId))
		advanceUntilIdle()

		val (h2, _) = trackerHarness(store = store, random = ScriptedRandom(bytes = listOf("B87AEFBD7F2339C1"))) { it.mints64 = true }
		h2.engine.reset()
		assertEquals("remembered across a restart", "-5153543226411238975 new", store.read(Engine.VISITOR))
	}

	/**	Another account has said nothing yet: 32 bits again. **/
	@Test fun anotherAccountForgetsTheWidth() = runTest {
		val store = MemoryStore()
		store.write(Engine.STATE, Wire.json.encodeToString(PersistedState.serializer(), PersistedState(apiKeyFp = "another", idBits = 64)))
		val h = harness(store = store, random = ScriptedRandom(ints = listOf(1)))
		advanceUntilIdle()
		assertEquals(32, h.idBits())
		assertEquals("500000001 new", store.read(Engine.VISITOR))
	}

	/**	A 64-bit visitor and its session, read and sent exactly: 19 digits, as text. **/
	@Test fun a64BitVisitorIsKeptExactly() = runTest {
		val store = MemoryStore()
		val sid = session(T0/1000 - 60, Long.MAX_VALUE)
		store.write(Engine.VISITOR, Long.MAX_VALUE.toString())
		store.write(Engine.STATE, state(sid))
		val (h, t) = trackerHarness(store = store) { it.accepts64 = true }
		advanceUntilIdle()
		assertEquals(Long.MAX_VALUE, h.engine.visitor.value.visitorId)
		val r = h.engine.navigate("Home", null)
		val b = h.transport.bodies().single()
		assertEquals("9223372036854775807", b.str("pz_visitor_id"))
		assertNull(b["pz_visitor_new"])
		assertEquals(sid, b.str("session_id"))
		assertFalse(b.bool("new_session"))
		assertEquals(sid, r.sessionId)
		assertEquals(Long.MAX_VALUE to false, t.visitors.single())
		assertEquals("9223372036854775807", store.read(Engine.VISITOR))
	}

	/**	Ownership compares all 64 bits: ids a double cannot tell apart are two visitors. **/
	@Test fun anotherVisitorsSessionIsNotSentAt64Bits() = runTest {
		val store = MemoryStore()
		store.write(Engine.VISITOR, "9007199254740993")
		store.write(Engine.STATE, state(session(T0/1000 - 60, 9007199254740992L)))
		val (h, _) = trackerHarness(store = store) { it.accepts64 = true }
		h.engine.navigate("Home", null)
		val b = h.transport.bodies().single()
		assertEquals("9007199254740993", b.str("pz_visitor_id"))
		assertNull(b.str("session_id"))
		assertTrue(b.bool("new_session"))
	}

	/**	A login is `user_id`, coerced by the server as in version 1, on an account that mints 64-bit
		ids too - never `pz_visitor_id`. The install's visitor, never sent meanwhile, is still new at
		the logout: only an answer makes it otherwise.
	 **/
	@Test fun aLoginIsUserIdAsBefore() = runTest {
		val (h, t) = trackerHarness(random = ScriptedRandom(ints = listOf(5))) { it.mints64 = true }
		h.engine.setUserId("alice@example.com")
		val a = h.engine.navigate("Home", null)
		h.engine.setUserId(9_007_199_254_740_993L)
		h.engine.navigate("Home", null)
		h.engine.setUserId(42L)
		h.engine.navigate("Home", null)
		h.engine.setUserId(null)
		h.engine.navigate("Home", null)
		val bodies = h.transport.bodies()
		assertEquals(listOf(JsonPrimitive("alice@example.com"), JsonPrimitive("9007199254740993"), JsonPrimitive(42)), bodies.take(3).map { it["user_id"] })
		assertTrue(bodies.take(3).none { it.containsKey("pz_visitor_id") || it.containsKey("pz_visitor_new") })
		assertEquals(Session.serverUserId("alice@example.com")!!.toLong(), Session.visitor(a.sessionId))
		assertEquals(listOf(Session.serverUserId("alice@example.com")!!.toLong(), Session.serverUserId("9007199254740993")!!.toLong(), 42L), t.visitors.take(3).map { it.first })
		assertEquals("500000005", bodies[3].str("pz_visitor_id"))
		assertTrue(bodies[3].bool("pz_visitor_new"))
		assertNull(bodies[3]["user_id"])
		assertTrue(bodies.all { it["protocol"] == JsonPrimitive(2) })
	}

	/**	A returning 64-bit visitor on an account taken back to 32 bits is given a 32-bit id: the
		shown-message record and the push registration follow it.
	 **/
	@Test fun aReplacedVisitorKeepsWhatWaitsForIt() = runTest {
		val store = MemoryStore()
		// One whose replacement is negative, as the server's often are: any 32-bit id but [0, 500M).
		val old = (0L until 1000L).map { 4611686018427387905L + it }.first { FakeTracker.derive(it, 32) < 0 }
		store.write(Engine.VISITOR, old.toString())
		store.write(Engine.STATE, state(session(T0/1000 - 60, old), delivered = mapOf(old.toString() to 5L), token = "dev:abc"))
		val (h, t) = trackerHarness(store = store)
		h.platform.app = PushApp("android", "com.shop.app", false)
		h.platform.showable = true
		h.transport.on("/rest/push_token", ok("""{"push":true}"""))
		h.transport.on("/rest/current_notification/", ok("""{"message_id":5,"title":"T","body":"B"}"""))
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		val replacement = FakeTracker.derive(old, 32)
		assertTrue(replacement < 0)
		assertEquals(replacement to true, t.visitors.single())
		assertEquals(replacement, h.engine.visitor.value.visitorId)
		assertNull("shown to this person already", h.engine.pollNotification(force = true))
		assertTrue(h.transport.requests.last().url.endsWith("/rest/current_notification/where/user_id=$replacement"))
		val push = h.transport.requests.filter { it.url.endsWith("/rest/push_token") }.map { Wire.json.parseToJsonElement(it.body!!).jsonObject }
		assertEquals(JsonPrimitive(replacement.toString()), push.last()["user_id"])
		assertEquals(replacement, h.engine.push.value.registeredVisitorId)
	}

	// -------------------------------------------------------------------- the independent review

	/**	Events a new install sends before its first page have no page view to belong to: on an account
		that mints, the server replaces the new visitor and logs nothing, answering 200 (SPEC section
		1, "Events of a replaced visitor are not logged"; section 11). `flush()` and `refresh()` keep
		them for the first navigation, as the automatic send always did.
	 **/
	@Test fun eventsBeforeTheFirstPageWaitForIt() = runTest {
		val (h, _) = trackerHarness { it.mints64 = true }
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		h.engine.flush()
		h.engine.refresh()
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		val sent = h.transport.bodies().map { it.commands() }.filter { it.isNotEmpty() }
		assertEquals(listOf(listOf(listOf("Product Viewed", "A1"), nav("Home"))), sent)
		assertTrue("${h.errors}", h.errors.isEmpty())
	}

	/**	A returning 64-bit visitor on an account that no longer takes them is replaced even beside its
		live session; with no page in the request, its events are not logged. They are kept - for the
		new id, with no session (the old one was the other visitor's, and the answer has none) - and go
		with the next page.
	 **/
	@Test fun aReplacedVisitorsEventsGoWithTheNextPage() = runTest {
		val store = MemoryStore()
		val old = 4611686018427387905L
		store.write(Engine.VISITOR, old.toString())
		store.write(Engine.STATE, state(session(T0/1000 - 60, old)))
		val (h, t) = trackerHarness(store = store)
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		h.engine.flush()
		val replacement = FakeTracker.derive(old, 32)
		assertEquals(replacement to true, t.visitors.single())
		assertEquals(replacement, h.engine.visitor.value.visitorId)
		assertNull("not the old visitor's session beside the new id", h.engine.visitor.value.sessionId)
		assertTrue("not sent: kept", h.engine.hasPending())
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		val b = h.transport.bodies().last()
		assertEquals(replacement.toString(), b.str("pz_visitor_id"))
		assertEquals(listOf(listOf("Product Viewed", "A1"), nav("Home")), b.commands())
		assertNull(b.str("session_id"))
		assertTrue("${h.errors}", h.errors.isEmpty())
	}

	/**	The hold does not hold the page up: a `track()` command, which goes alone, waits behind it. **/
	@Test fun waitingEventsDoNotHoldThePage() = runTest {
		val (h, _) = trackerHarness()
		h.engine.enqueue(raw = true) { Commands.raw(listOf("custom thing", "1")) }
		assertFalse(h.engine.navigate("Home", null).failed)
		h.engine.flush()
		val bodies = h.transport.bodies()
		assertEquals(listOf(listOf(nav("Home")), listOf(listOf("custom thing", "1"))), bodies.map { it.commands() })
		assertFalse(bodies[1].bool("new_session"))
		assertTrue("${h.errors}", h.errors.isEmpty())
	}

	/**	Events queued before `startNewSession()` with no live session of theirs can never go: no page of
		their session will come. Reported, not held - nor sent alone, to be refused.
	 **/
	@Test fun eventsOfASessionThatCannotComeAreReported() = runTest {
		val (h, _) = trackerHarness()
		h.engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
		h.engine.startNewSession()
		assertFalse(h.engine.navigate("Home", null).failed)
		advanceUntilIdle()
		assertEquals(listOf(listOf(nav("Home"))), h.transport.bodies().map { it.commands() })
		assertEquals(Reason.OTHER_SESSION, (h.errors.single().first as PersonyzeException.Delivery).reason)
		assertFalse(h.engine.hasPending())
	}

	/**	Until an answer names it, the install's id is the device's guess - an account that mints 64-bit
		ids replaces it: no visitor id yet, rather than one that is about to die.
	 **/
	@Test fun noVisitorIdUntilAnAnswerNamesIt() = runTest {
		val (h, _) = trackerHarness(random = ScriptedRandom(ints = listOf(3))) { it.mints64 = true }
		advanceUntilIdle()
		assertNull(h.engine.visitor.value.visitorId)
		h.engine.navigate("Home", null)
		assertEquals(FakeTracker.derive(500_000_003L, 64), h.engine.visitor.value.visitorId)
	}

	/**	The app died after the adopted id was written and before the state was: the queue and the
		shown-message record still name the old id. Only a predecessor of the install's visitor can be
		there (a reset and another key clear them), so they are the install's.
	 **/
	@Test fun aDeathBetweenTheTwoWritesLosesNothing() = runTest {
		val store = MemoryStore()
		val a = 500_000_042L
		val b = FakeTracker.derive(a, 64)
		store.write(Engine.VISITOR, b.toString())
		store.write(Engine.STATE, """{"v":1,"apiKeyFp":"${fp()}","installVisitor":"$a","notifications":{"delivered":{"$a":5}}}""")
		store.write(Engine.OUTBOX, """{"v":1,"items":[{"cmd":["Product Viewed","A1"],"user":"$a","epoch":0,"sessionStart":0,"queuedAtMs":$T0,"seq":1,"anonymous":true}]}""")
		val (h, _) = trackerHarness(store = store) { it.mints64 = true }
		h.transport.on("/rest/current_notification/", ok("""{"message_id":5,"title":"T","body":"B"}"""))
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		val body = h.transport.bodies().single()
		assertEquals(b.toString(), body.str("pz_visitor_id"))
		assertEquals(listOf(listOf("Product Viewed", "A1"), nav("Home")), body.commands())
		assertTrue("${h.errors}", h.errors.isEmpty())
		assertNull("shown already, to the same person", h.engine.pollNotification(force = true))
	}

	/**	The adopted id is on disk before the session that came with it - whatever else the app does
		meanwhile on another thread: a persist it asks for waits. Real threads here; the test
		dispatcher runs one thing at a time and could not show it.
	 **/
	@Test fun theVisitorFileIsWrittenBeforeTheSessionThatCameWithIt() = runBlocking<Unit> {
		val adopted = FakeTracker.derive(500_000_011L, 64).toString()
		val order = Collections.synchronizedList(ArrayList<String>())
		val inner = MemoryStore()
		lateinit var engine: Engine
		val store = object : Store by inner
		{	override fun write(name: String, text: String, sync: Boolean)
			{	if (name == Engine.VISITOR && text == adopted)
				{	// The app, meanwhile: an event, and with it a persist asked for.
					engine.enqueue { Commands.product("Product Viewed", "A1", null, null) }
					Thread.sleep(300)
				}
				inner.write(name, text, sync)
				order.add(if (name == Engine.VISITOR) "visitor $text" else if (name == Engine.STATE && text.contains(adopted)) "state, adopted" else name)
			}
		}
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		try
		{	val platform = FakePlatform(TestCoroutineScheduler())
			val tracker = FakeTracker { platform.nowMs()/1000 }.also { it.mints64 = true }
			engine = Engine(
				EngineConfig(KEY, BASE, ASSETS, 5000, 10_000, 1, null, "personyze-android/test"),
				platform, store, MemoryStore(), FakeTransport().tracker(tracker.step()), scope, Dispatchers.IO, null,
				{ _, _ -> }, { null }, ScriptedRandom(ints = listOf(11))
			)
			engine.navigate("Home", null)
			withTimeout(5000)
			{	while (!order.contains("state, adopted"))
				{	delay(20)
				}
			}
			val visitorAt = order.indexOf("visitor $adopted")
			assertTrue("$order", visitorAt != -1 && visitorAt < order.indexOf("state, adopted"))
		}
		finally
		{	scope.cancel()
		}
	}

	// ------------------------------------------------------------------ the confirm review

	/**	An action decided for the visitor before a login is reported after it: it belongs to no session
		of the new visitor's, and held for their first page it would be counted there - in a session
		that never showed it (SPEC section 11, Difference 3). Not sent, and reported; a close still
		blocks the action on the device.
	 **/
	@Test fun aReportOfThePreviousVisitorsActionIsDroppedAtLogin() = runTest {
		val (h, _) = trackerHarness { it.actions = { """[{"id":88}]""" } }
		h.transport.on("/rest/actions/", ok("[]"))
		h.engine.navigate("Home", null)
		h.engine.setUserId("alice@example.com")
		h.engine.enqueue { Commands.actionStatus(88, "executed") }
		h.engine.enqueue { Commands.actionStatus(88, "close", "3") }
		assertTrue("a close still blocks it", h.engine.diagnostics().contains("blocked={88=3}"))
		h.engine.flush()
		h.engine.navigate("Account", null)
		advanceUntilIdle()
		assertEquals(listOf(nav("Account")), h.transport.bodies().last().commands())
		assertEquals(listOf(Reason.OTHER_SESSION, Reason.OTHER_SESSION), h.errors.map { (it.first as PersonyzeException.Delivery).reason })
		assertEquals(listOf(1, 1), h.errors.map { it.second.dropped })
	}

	/**	Two screens' navigations at once, the later one first at the wire: it waits for the earlier
		one to go, rather than failing with "did not answer" and logging no page view. Real threads.
	 **/
	@Test fun aNavigationWaitsForAnEarlierOneToGoFirst() = runBlocking<Unit> {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		try
		{	val platform = FakePlatform(TestCoroutineScheduler())
			val tracker = FakeTracker { platform.nowMs()/1000 }
			lateinit var engine: Engine
			engine = Engine(
				EngineConfig(KEY, BASE, ASSETS, 5000, 10_000, 1, null, "personyze-android/test"),
				platform, MemoryStore(), MemoryStore(), FakeTransport().tracker(tracker.step()), scope, Dispatchers.IO, null,
				{ _, _ -> }, { null }, ScriptedRandom()
			)
			var second: Deferred<PersonyzeResult>? = null
			val first = engine.navigate("First") {
				// Another screen's, on another thread, reaches the wire before this one does.
				second = scope.async { engine.navigate("Second", null) }
				Thread.sleep(300)
			}
			val secondResult = withTimeout(10_000) { second!!.await() }
			assertFalse("${first.error}", first.failed)
			assertFalse("${secondResult.error}", secondResult.failed)
			assertEquals(listOf("urn:personyze:doc:First", "urn:personyze:doc:Second"), tracker.seen.map { it.commands().single()[1] })
		}
		finally
		{	scope.cancel()
		}
	}

	/**	The `visitor` file lost, state.json kept: the state says whose it was - that visitor goes on,
		with its session and what waits for it, not a new one minted beside its history.
	 **/
	@Test fun aLostVisitorFileIsRestoredFromTheState() = runTest {
		val store = MemoryStore()
		val old = 4611686018427387905L
		val sid = session(T0/1000 - 60, old)
		store.write(Engine.STATE, """{"v":1,"apiKeyFp":"${fp()}","installVisitor":"$old","session":"$sid"}""")
		val (h, _) = trackerHarness(store = store) { it.accepts64 = true }
		advanceUntilIdle()
		assertEquals(old, h.engine.visitor.value.visitorId)
		assertEquals(old.toString(), store.read(Engine.VISITOR))
		h.engine.navigate("Home", null)
		val b = h.transport.bodies().single()
		assertEquals(old.toString(), b.str("pz_visitor_id"))
		assertNull(b["pz_visitor_new"])
		assertEquals(sid, b.str("session_id"))
	}

	/**	... and new, when no answer had named it yet. **/
	@Test fun aLostVisitorFileKeepsItsNewMark() = runTest {
		val store = MemoryStore()
		store.write(Engine.STATE, """{"v":1,"apiKeyFp":"${fp()}","installVisitor":"500000123 new"}""")
		val (h, _) = trackerHarness(store = store)
		h.engine.navigate("Home", null)
		val b = h.transport.bodies().single()
		assertEquals("500000123", b.str("pz_visitor_id"))
		assertTrue(b.bool("pz_visitor_new"))
	}

	/**	A server before protocol 2 answers no `pz_visitor_id`, and takes the request for a new random
		visitor: field 1 of its session is adopted, so the next request continues that visitor.
	 **/
	@Test fun anOldServersVisitorIsAdoptedFromItsSession() = runTest {
		val old: Step = { r ->
			val b = Wire.json.parseToJsonElement(r.body!!).jsonObject
			HttpOutcome.Answered(200, tracker(b.str("session_id") ?: session(T0/1000, 777_000_001L)), null)
		}
		val h = harness(transport = FakeTransport().tracker(old))
		h.engine.navigate("Home", null)
		assertEquals(777_000_001L, h.engine.visitor.value.visitorId)
		h.engine.navigate("Next", null)
		val b = h.transport.bodies()[1]
		assertEquals("777000001", b.str("pz_visitor_id"))
		assertNull(b["pz_visitor_new"])
		assertEquals(session(T0/1000, 777_000_001L), b.str("session_id"))
	}

	/**	An install upgraded from 2.x keeps its visitor: the id as `pz_visitor_id` now, a returning one
		(not new), its session and the events 2.x queued as `user_id`.
	 **/
	@Test fun anInstallOf2xKeepsItsVisitor() = runTest {
		val store = MemoryStore()
		val sid = session(T0/1000 - 60, 500_123_456L)
		store.write(Engine.VISITOR, "500123456")
		store.write(Engine.STATE, state(sid))
		val queued = """{"v":1,"items":[{"cmd":["Product Viewed","A1"],"user":500123456,"epoch":0,"sessionStart":${T0/1000 - 60},"queuedAtMs":$T0,"seq":1}]}"""
		store.write(Engine.OUTBOX, queued)
		val (h, _) = trackerHarness(store = store)
		h.engine.navigate("Home", null)
		advanceUntilIdle()
		val b = h.transport.bodies().single()
		assertEquals("500123456", b.str("pz_visitor_id"))
		assertNull(b["pz_visitor_new"])
		assertNull(b["user_id"])
		assertEquals(sid, b.str("session_id"))
		assertEquals(listOf(listOf("Product Viewed", "A1"), nav("Home")), b.commands())
		assertEquals("500123456", store.read(Engine.VISITOR))
		assertTrue(h.errors.isEmpty())
	}

	/**	The `visitor` file of a 64-bit visitor - which 2.x read as no visitor - is read whole. **/
	@Test fun theVisitorFileIsReadAt64Bits() = runTest {
		val store = MemoryStore()
		store.write(Engine.VISITOR, " -1520401369231049551\n")
		val h = harness(store = store)
		advanceUntilIdle()
		assertEquals(-1520401369231049551L, h.engine.visitor.value.visitorId)
		assertEquals("-1520401369231049551", store.read(Engine.VISITOR))
	}
}
