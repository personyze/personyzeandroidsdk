package com.personyze.android.internal.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**	The fake answers as `tracker_v1.ts` does where the engine's tests lean on it - a fake that is
	kinder than the server hides what the server would refuse.
 **/
class FakeTrackerTest
{	private val t = FakeTracker { 1_761_220_800L }

	private fun ask(body: String): HttpOutcome.Answered =
		runBlocking { t.step()(HttpRequest("POST", "$BASE/rest/tracker-v1", emptyMap(), body)) as HttpOutcome.Answered }

	/**	No commands, nothing logged: the session as it was sent, or none. **/
	@Test fun noCommandsLogNothing()
	{	val none = ask("""{"protocol":2,"pz_visitor_id":"500000001","session_id":null,"new_session":true,"commands":[]}""")
		assertEquals(200, none.status)
		assertNull(Wire.parseResponse(none.body)!!.sessionId)
		val sid = session(1_761_220_000L, 500_000_001L)
		val kept = ask("""{"protocol":2,"pz_visitor_id":"500000001","session_id":"$sid","new_session":true,"commands":[]}""")
		assertEquals(sid, Wire.parseResponse(kept.body)!!.sessionId)
	}

	/**	"Events not logged: this session has no page view" - a 500. **/
	@Test fun eventsWithoutAPageViewAreRefused()
	{	assertEquals(500, ask("""{"protocol":2,"pz_visitor_id":"500000001","session_id":null,"new_session":true,"commands":[["Product Viewed","A1"]]}""").status)
	}

	/**	Fields 2 to 5 as the server checks them: a visit, a counter and a last start above 0, a digit. **/
	@Test fun aSessionIsValidOnlyAsTheServerWritesIt()
	{	for (sid in listOf("1761220000 42 0 1 1761220000 0 491 0", "1761220000 42 7 0 1761220000 0 491 0", "1761220000 42 7 1 0 0 491 0", "1761220000 42 7 1 1761220000 x 491 0"))
		{	assertEquals(sid, 400, ask("""{"protocol":2,"user_id":42,"session_id":"$sid","new_session":false,"commands":[["Navigate","urn:personyze:doc:Home"]]}""").status)
		}
	}

	/**	`derivedUserId`'s 32-bit replacements: any 32-bit id but 0 and the server's [0, 500M) - negatives too. **/
	@Test fun a32BitReplacementIsAnyInt32OutsideTheReservedRange()
	{	val ids = (0L until 200L).map { FakeTracker.derive(4_611_686_018_427_387_905L + it, 32) }
		assertTrue(ids.any { it < 0 })
		assertTrue(ids.all { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it != 0L && (it < 0 || it >= 500_000_000) })
	}
}
