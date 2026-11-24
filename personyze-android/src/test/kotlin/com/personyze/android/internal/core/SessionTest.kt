package com.personyze.android.internal.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

class SessionTest
{	@Test fun startTimeIsFieldZero()
	{	assertEquals(1761220800L, Session.startTime("1761220800 42 7 1 1761220800 0 491 0"))
		assertEquals(1761220800L, Session.startTime("  1761220800"))
	}

	/**	Garbage reads as 0, i.e. long expired - so a corrupt session heals instead of answering 400 forever. **/
	@Test fun unparseableStartIsZero()
	{	assertEquals(0L, Session.startTime(null))
		assertEquals(0L, Session.startTime(""))
		assertEquals(0L, Session.startTime("abc 1 2"))
		assertEquals(0L, Session.startTime("99999999999999999999 1 2"))
		assertEquals(0L, Session.startTime("12x 1"))
	}

	@Test fun visitorIsFieldOne()
	{	assertEquals(42L, Session.visitor("1 42 7 1"))
		assertEquals(-963761860L, Session.visitor("1 -963761860 7 1"))
		assertNull(Session.visitor("1"))
		assertNull(Session.visitor("1 abc"))
		assertNull(Session.visitor(null))
	}

	/**	Up to 19 digits and a sign, exactly - 2.x read ten digits at most, and so no visitor in the
		session of a 64-bit one. What the server never writes (`0`, leading zeros, past 64 bits) is no
		visitor id.
	 **/
	@Test fun fieldOneIsReadAtAnyWidth()
	{	assertEquals(9223372036854775807L, Session.visitor("1761220800 9223372036854775807 77 1 1761220800 0 491 0"))
		assertEquals(-9223372036854775807L, Session.visitor("1 -9223372036854775807 77 1"))
		assertEquals(9007199254740993L, Session.visitor("1 9007199254740993 77 1"))
		assertEquals(4611686018427387904L, Session.visitor("1 4611686018427387904 77 1"))
		for (field in listOf("0", "-0", "042", "+42", "9223372036854775808", "-9223372036854775808", "12345678901234567890", ""))
		{	assertNull(field, Session.visitor("1 $field 77 1"))
		}
	}

	@Test fun expiryUsesNinetyMinutesLessFiveSeconds()
	{	val start = 1_761_220_800L
		val s = "$start 42 7 1"
		assertFalse(Session.isExpired(s, start*1000 + Session.TTL_MS - 5001))
		assertTrue(Session.isExpired(s, start*1000 + Session.TTL_MS - 5000))
		assertTrue(Session.isExpired(null, 0))
		assertTrue(Session.isExpired("garbage", start*1000))
	}

	/**	The vectors of the Node port (test/client.test.js), the first confirmed against the live endpoint. **/
	@Test fun serverUserIdMatchesNode()
	{	assertEquals(-963761860, Session.serverUserId("pythonsdk-test-6ada7418@example.com"))
		assertEquals(listOf(123, 123, 42, 26, 1000), listOf<Any>(123, "123", " 42 ", "0x1A", "1e3").map { Session.serverUserId(it) })
		assertEquals(Session.serverUserId(0), Session.serverUserId("0"))
		assertNull(Session.serverUserId(null))
		assertNull(Session.serverUserId(""))
		assertNull(Session.serverUserId("   "))
		// A fraction is the CRC-32 of its text (sdkUserIdOf) - 2.x took it for nobody.
		assertEquals(crc("12.5"), Session.serverUserId("12.5"))
	}

	@Test fun serverUserIdFollowsJsNumber()
	{	assertEquals(5, Session.serverUserId("+5"))
		assertEquals(3, Session.serverUserId("0b11"))
		assertEquals(15, Session.serverUserId("0o17"))
		assertEquals(42, Session.serverUserId("\u00A042\uFEFF"))
		assertEquals(-2147483648, Session.serverUserId("-2147483648"))
		assertEquals(2147483647, Session.serverUserId(2147483647L))
		assertEquals(5, Session.serverUserId("5."))
		// Not numbers to JavaScript, or out of range: the CRC of the text.
		assertEquals(crc("-0x1A"), Session.serverUserId("-0x1A"))
		assertEquals(crc("2147483648"), Session.serverUserId("2147483648"))
		assertEquals(crc("2147483648"), Session.serverUserId(2147483648L))
		assertEquals(crc("Infinity"), Session.serverUserId("Infinity"))
		assertEquals(crc("1_000"), Session.serverUserId("1_000"))
		assertEquals(crc("0x"), Session.serverUserId("0x"))
		assertEquals(crc("-0"), Session.serverUserId("-0"))
		assertEquals(crc(".5"), Session.serverUserId(".5"))
	}

	@Test fun jsNumberGrammar()
	{	assertEquals(0.0, Session.jsNumber(""), 0.0)
		assertEquals(0.5, Session.jsNumber(".5"), 0.0)
		assertEquals(1e3, Session.jsNumber("1E3"), 0.0)
		assertTrue(Session.jsNumber("1f").isNaN())
		assertTrue(Session.jsNumber("NaN").isNaN())
		assertTrue(Session.jsNumber("0x1p3").isNaN())
		assertTrue(Session.jsNumber("0o9").isNaN())
		assertEquals(Double.NEGATIVE_INFINITY, Session.jsNumber("-Infinity"), 0.0)
	}

	/**	An unpaired surrogate is U+FFFD in UTF-8, as JavaScript's TextEncoder writes it. **/
	@Test fun utf8LikeTextEncoder()
	{	assertEquals(listOf(0xEF, 0xBF, 0xBD), Session.utf8("\uD800").map { it.toInt() and 0xFF })
		assertEquals(listOf(0xF0, 0x9F, 0x98, 0x80), Session.utf8("😀").map { it.toInt() and 0xFF })
	}

	@Test fun sessionOfAnotherVisitorIsNotOurs()
	{	val ofUser = { sessionId: String, userId: Any -> Session.isSessionOf(sessionId, Session.serverUserId(userId)?.toLong()) }
		assertTrue(ofUser("1 42 7 1", 42))
		assertTrue(ofUser("1 42 7 1", "42"))
		assertFalse(ofUser("1 42 7 1", 43))
		assertTrue(ofUser("1 -963761860 7 1", "pythonsdk-test-6ada7418@example.com"))
		assertFalse(ofUser("1 42 7 1", "12.5"))
		// Cannot tell: kept, and left to the expiry rule.
		assertTrue(Session.isSessionOf("garbage", 42L))
		assertTrue(Session.isSessionOf("1 42 7 1", null))
		// A field 1 the server never writes is nobody's: protocol 2 answers it 400 without new_session.
		assertFalse(Session.isSessionOf("1 042 7 1", 42L))
		assertFalse(Session.isSessionOf("1 0 7 1", null))
	}

	/**	A stored value is a session string when its field 0, the start, reads as a number; only then is
		field 1 judged (SPEC section 3). Anything else is left to the expiry rule, which sends it with
		`new_session` - and the server starts afresh.
	 **/
	@Test fun onlyASessionStringIsJudged()
	{	assertTrue(Session.isSessionOf("garbage 0 7 1", 42L))
		assertTrue(Session.isSessionOf("abc 042 7 1", 42L))
		assertNull(Session.visitor("abc 42 7 1"))
		assertFalse(Session.isSessionOf("1761220800 042 7 1", 42L))
		assertTrue(Session.isSessionOf("1761220800 42 7 1", 42L))
	}

	/**	Compared as 64-bit integers: two ids a double cannot tell apart are two visitors. **/
	@Test fun ownershipOfA64BitVisitor()
	{	val s = "1761220800 9007199254740993 77 1 1761220800 0 491 0"
		assertTrue(Session.isSessionOf(s, 9007199254740993L))
		assertFalse(Session.isSessionOf(s, 9007199254740992L))
		assertTrue(Session.isSessionOf("1 -1520401369231049551 77 1", Identity.anonymous(-1520401369231049551L).visitorId))
		// The install's id is itself, never coerced as user_id would be: its CRC-32 is another visitor.
		assertEquals(-1520401369231049551L, Identity.anonymous(-1520401369231049551L).visitorId)
		assertEquals(Session.serverUserId("-1520401369231049551")!!.toLong(), Identity(JsonPrimitive("-1520401369231049551"), false).visitorId)
	}

	// ------------------------------------------------------- the server's vectors (user_id_vectors.json)

	/**	A copy of app-service's `mod_user_id/private/user_id_vectors.json`, so the SDK's own repository
		runs it too. In the monorepo the copy must match the original.
	 **/
	private val vectors: JsonObject by lazy {
		Wire.json.parseToJsonElement(javaClass.getResource("/user_id_vectors.json")!!.readText()) as JsonObject
	}

	private fun pairs(name: String): List<Pair<JsonPrimitive, String?>> =
		vectors[name]!!.jsonArray.map { (it as JsonArray).let { p -> p[0].jsonPrimitive to (p[1] as? JsonPrimitive)?.takeIf { v -> v !is JsonNull }?.content } }

	@Test fun vectorsAreTheServersOwn()
	{	val original = File("../../../app-service/private/mod_user_id/private/user_id_vectors.json")
		if (original.isFile)
		{	assertEquals("src/test/resources/user_id_vectors.json differs from app-service's: copy it again", original.readText(), javaClass.getResource("/user_id_vectors.json")!!.readText())
		}
	}

	/**	The 64-bit mint: 8 bytes, big-endian, kept only in [-2^63+1, -2^31) and [2^62, 2^63-1]. **/
	@Test fun fromBytesVectors()
	{	val cases = pairs("fromBytes")
		assertTrue(cases.size > 40)
		for ((hex, expected) in cases)
		{	assertEquals(hex.content, expected, Session.visitorIdFromBytes(bytes(hex.content))?.toString())
		}
	}

	/**	`user_id`'s coercion, unchanged in protocol 2: a number in 32 bits as it is, else the CRC-32 of
		its text - a fraction too; a CRC-32 of 0 names nobody.
	 **/
	@Test fun sdkV1Vectors()
	{	for ((input, expected) in pairs("sdkV1"))
		{	val user: Any = if (input.isString) input.content else input.content.toLong()
			assertEquals("$input", expected, Session.serverUserId(user)?.toString())
		}
	}

	/**	What is read as written - field 1, `pz_visitor_id` - is the server's canonical form. **/
	@Test fun parseVectors()
	{	for ((input, expected) in pairs("parse"))
		{	assertEquals("\"${input.content}\"", expected, Session.visitorId(input.content)?.toString())
		}
	}

	@Test fun mintsAtTheWidthAsked()
	{	// 64: the first draw the server's rule takes - here after two it does not.
		assertEquals(-1520401369231049551L, Session.mintVisitorId(64, ScriptedRandom(bytes = listOf("2455641835E6A5AA", "FFFFFFFF80000000", "EAE6730296F73CB1"))))
		// 32: 500,000,000 to 2^31-1, both ends included.
		assertEquals(500_000_000L, Session.mintVisitorId(32, ScriptedRandom(ints = listOf(0))))
		assertEquals(Int.MAX_VALUE.toLong(), Session.mintVisitorId(32, ScriptedRandom(ints = listOf(Int.MAX_VALUE - 500_000_000))))
		val random = SecureRandom()
		repeat(2000)
		{	val id = Session.mintVisitorId(64, random)
			assertTrue("$id", id >= 1L shl 62 || (id < -(1L shl 31) && id != Long.MIN_VALUE))
			val small = Session.mintVisitorId(32, random)
			assertTrue("$small", small in 500_000_000L..Int.MAX_VALUE.toLong())
		}
	}

	private fun bytes(hex: String): ByteArray = ByteArray(hex.length/2) { hex.substring(it*2, it*2 + 2).toInt(16).toByte() }

	@Test fun counterIsFieldThree()
	{	assertEquals(3, Session.counter("1 42 7 3 1 0 491 0"))
		assertNull(Session.counter("1 42"))
	}

	private fun crc(s: String): Int
	{	val c = java.util.zip.CRC32()
		c.update(s.toByteArray(Charsets.UTF_8))
		return c.value.toInt()
	}
}
