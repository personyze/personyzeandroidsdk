package com.personyze.android.internal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
		assertNull(Session.serverUserId("12.5"))
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
		assertNull(Session.serverUserId(".5"))
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
	{	assertTrue(Session.isSessionOf("1 42 7 1", 42))
		assertTrue(Session.isSessionOf("1 42 7 1", "42"))
		assertFalse(Session.isSessionOf("1 42 7 1", 43))
		assertTrue(Session.isSessionOf("1 -963761860 7 1", "pythonsdk-test-6ada7418@example.com"))
		// Cannot tell: kept, and left to the expiry rule.
		assertTrue(Session.isSessionOf("garbage", 42))
		assertTrue(Session.isSessionOf("1 42 7 1", "12.5"))
	}

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
