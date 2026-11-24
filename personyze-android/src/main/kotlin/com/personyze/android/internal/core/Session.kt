package com.personyze.android.internal.core

import java.math.BigInteger
import java.util.zip.CRC32

/**	The session string and the visitor id - the two things a port must get right (SPEC section 3).

	The session string is opaque except for two fields: 0, the session's start (a Unix time), and
	1, the visitor it belongs to. Everything else (visit id, counter, hosts) is the server's.

	**A visitor id is a signed 64-bit number** (SPEC section 1, protocol 2; SDK 3.0, 2026-11): held
	as a [Long] or as its decimal text, never as a [Double] - past 2^53 a double is another visitor's
	id. Up to 2.x it was read as ten digits at most, so a session of a 64-bit visitor (an account
	that mints them, since 2026-11) had no visitor, and the install's id was an [Int].
 **/
internal object Session
{	/**	The server's own session timeout. **/
	const val TTL_MS: Long = 90L*60_000
	/**	Renew a session about to expire deliberately, rather than have it rejected in flight. **/
	const val EXPIRY_MARGIN_MS: Long = 5000
	/**	32-bit anonymous ids below this are reserved (SPEC section 10.1): the server's own, and the
		tag's of old.
	 **/
	const val MIN_ANONYMOUS_ID = 500_000_000
	/**	A 64-bit id is minted in [-2^63+1, -2^31) or [2^62, 2^63-1] (SPEC section 3), where nothing
		else mints: below is the server's range and the 32-bit ids of old.
	 **/
	private const val MINTED_HIGH_FROM = 1L shl 62
	private const val MINTED_LOW_BELOW = -(1L shl 31)

	/**	Width-limited: a corrupted `99999999999999999999 …` must read as garbage (expired), not as a
		start far in the future that never expires - that answers `400 Invalid session_id` forever.
	 **/
	private val RE_START = Regex("^\\s*(\\d{1,10})(?:\\s|$)")
	/**	A visitor id as the server writes it (`user_id.ts` `parseUserId`): no `+`, no leading zeros,
		never 0. Within 64 bits is [visitorId]'s to check.
	 **/
	private val RE_VISITOR = Regex("^-?[1-9]\\d{0,18}$")

	/**	Field 0 in seconds, or 0 for anything that is not one. **/
	fun startTime(sessionId: String?): Long =
		sessionId?.let { RE_START.find(it)?.groupValues?.get(1)?.toLong() } ?: 0

	/**	Field 1: the visitor the session belongs to, or null when it holds none - or none the server
		writes (`0`, leading zeros, past 64 bits), which protocol 2 refuses (SPEC section 3).
	 **/
	fun visitor(sessionId: String?): Long? = visitorId(fieldOne(sessionId))

	/**	Field 1 of a session string - one whose field 0, the start, reads as a number (SPEC section 3).
		Null for anything else: it is left to the expiry rule.
	 **/
	private fun fieldOne(sessionId: String?): String?
	{	val fields = sessionId?.split(' ') ?: return null
		return if (fields.size >= 2 && RE_DIGITS.matches(fields[0])) fields[1] else null
	}

	private val RE_DIGITS = Regex("\\d+")

	/**	A visitor id written canonically - field 1 of a session, an answer's `pz_visitor_id` - or null:
		a signed decimal of up to 19 digits within 64 bits, -2^63 excluded (nothing mints it).
	 **/
	fun visitorId(text: String?): Long?
	{	if (text == null || !RE_VISITOR.matches(text))
		{	return null
		}
		return text.toLongOrNull()?.takeIf { it != Long.MIN_VALUE }
	}

	/**	A minted 64-bit id from 8 random bytes, big-endian two's complement - or null where nothing
		mints, and the caller draws again. The server's `userIdFromBytes`, to the vector.
	 **/
	fun visitorIdFromBytes(b: ByteArray): Long?
	{	require(b.size == 8) { "8 bytes expected, not ${b.size}" }
		var v = 0L
		for (x in b)
		{	v = (v shl 8) or (x.toLong() and 0xFF)
		}
		return v.takeIf { it >= MINTED_HIGH_FROM || (it < MINTED_LOW_BELOW && it != Long.MIN_VALUE) }
	}

	/**	A new anonymous visitor at the width the last answer's `id_bits` said (SPEC section 3): 32
		bits a random number in 500,000,000..2^31-1, as the SDK always minted; 64 bits 8 bytes of
		[random] (a `SecureRandom`, but for tests) drawn until [visitorIdFromBytes] takes them.
	 **/
	fun mintVisitorId(bits: Int, random: java.util.Random): Long
	{	if (bits == 64)
		{	val b = ByteArray(8)
			while (true)
			{	random.nextBytes(b)
				visitorIdFromBytes(b)?.let { return it }
			}
		}
		return MIN_ANONYMOUS_ID.toLong() + random.nextInt(Int.MAX_VALUE - MIN_ANONYMOUS_ID + 1)
	}

	/**	Field 3, the session counter: 1 for the visitor's first session. Read only by diagnostics
		and the conformance test (step 7) - the SDK itself treats it as opaque.
	 **/
	fun counter(sessionId: String?): Int? = sessionId?.split(' ')?.getOrNull(3)?.toIntOrNull()

	fun isExpired(sessionId: String?, serverNowMs: Long): Boolean =
		sessionId.isNullOrEmpty() || startTime(sessionId)*1000 + TTL_MS - EXPIRY_MARGIN_MS <= serverNowMs

	/**	Whether [sessionId] may be sent for the visitor the server makes of the request, [expected]
		([Identity.visitorId]): false when it surely belongs to another visitor - or to none the server
		writes, which protocol 2 answers `400 Invalid session_id`. A session with no field 1, or a
		request whose visitor cannot be told, is left to the expiry rule.
	 **/
	fun isSessionOf(sessionId: String?, expected: Long?): Boolean
	{	val field = fieldOne(sessionId) ?: return true
		val owner = visitorId(field) ?: return false
		return expected == null || owner == expected
	}

	/**	The visitor id the server makes of `user_id` (`sdkUserIdOf`, the same in both protocols): what
		JavaScript's `Number()` reads as a non-zero 32-bit integer is that integer; anything else -
		`12.5` too - the CRC-32 of its text in UTF-8, as a signed 32-bit integer. Null for no id, and
		for a CRC-32 of 0, which names nobody (the server mints a visitor of its own). Until SDK 3.0 a
		fraction was null here (the server kept it as it was, and MySQL rounded it); `sdkV1` in the
		server's `user_id_vectors.json` holds it now.
	 **/
	fun serverUserId(userId: Any?): Int?
	{	val text = when (userId)
		{	null -> return null
			is String -> userId
			is Int, is Long -> userId.toString()
			else -> userId.toString()
		}
		if (jsTrim(text).isEmpty())
		{	return null
		}
		val n = when (userId)
		{	is Int -> userId.toDouble()
			is Long -> userId.toDouble()
			else -> jsNumber(text).let { if (it.isNaN()) 0.0 else it }
		}
		if (n != 0.0 && n >= Int.MIN_VALUE && n <= Int.MAX_VALUE && n == Math.floor(n))
		{	return n.toInt()
		}
		val crc = CRC32()
		crc.update(utf8(text))
		return crc.value.toInt().takeIf { it != 0 }
	}

	/**	JavaScript's `Number(string)` (ECMA-262 StringToNumber): trimmed; empty is 0; `0x`/`0o`/`0b`
		integers without a sign; decimal with an optional sign, fraction and exponent; `Infinity`;
		anything else NaN. No numeric separators. Kotlin's `toDouble` accepts more (`1f`, `NaN`,
		hex floats), so the grammar is matched first.
	 **/
	fun jsNumber(text: String): Double
	{	val s = jsTrim(text)
		if (s.isEmpty())
		{	return 0.0
		}
		RE_RADIX.matchEntire(s)?.let { m ->
			return when
			{	m.groups[1] != null -> BigInteger(m.groupValues[1], 16)
				m.groups[2] != null -> BigInteger(m.groupValues[2], 8)
				else -> BigInteger(m.groupValues[3], 2)
			}.toDouble()
		}
		val d = RE_DECIMAL.matchEntire(s) ?: return Double.NaN
		return when (d.groupValues[2])
		{	"Infinity" -> if (d.groupValues[1] == "-") Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
			else -> s.toDouble()
		}
	}

	/**	No sign: `Number("-0x1A")` is NaN. **/
	private val RE_RADIX = Regex("0(?:[xX]([0-9a-fA-F]+)|[oO]([0-7]+)|[bB]([01]+))")
	private val RE_DECIMAL = Regex("([+-]?)(Infinity|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)")

	/**	`String.prototype.trim`: WhiteSpace and LineTerminator, which include U+00A0, U+FEFF and
		every Unicode space separator - wider than Kotlin's `trim()`.
	 **/
	fun jsTrim(s: String): String
	{	var start = 0
		var end = s.length
		while (start < end && isJsSpace(s[start]))
		{	start++
		}
		while (end > start && isJsSpace(s[end - 1]))
		{	end--
		}
		return s.substring(start, end)
	}

	private fun isJsSpace(c: Char): Boolean =
		c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' || c == ' ' || c == '\u00A0' || c == '\uFEFF' ||
		c == '\u2028' || c == '\u2029' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

	/**	UTF-8 as JavaScript's `TextEncoder` writes it: an unpaired surrogate becomes U+FFFD. The JVM's
		own encoder writes `?` there, which would give a different CRC.
	 **/
	fun utf8(s: String): ByteArray
	{	val out = java.io.ByteArrayOutputStream(s.length + 8)
		var i = 0
		while (i < s.length)
		{	var cp = s[i].code
			if (cp in 0xD800..0xDBFF && i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF)
			{	cp = 0x10000 + ((cp - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
				i++
			}
			else if (cp in 0xD800..0xDFFF)
			{	cp = 0xFFFD
			}
			i++
			when
			{	cp < 0x80 -> out.write(cp)
				cp < 0x800 ->
				{	out.write(0xC0 or (cp shr 6))
					out.write(0x80 or (cp and 0x3F))
				}
				cp < 0x10000 ->
				{	out.write(0xE0 or (cp shr 12))
					out.write(0x80 or ((cp shr 6) and 0x3F))
					out.write(0x80 or (cp and 0x3F))
				}
				else ->
				{	out.write(0xF0 or (cp shr 18))
					out.write(0x80 or ((cp shr 12) and 0x3F))
					out.write(0x80 or ((cp shr 6) and 0x3F))
					out.write(0x80 or (cp and 0x3F))
				}
			}
		}
		return out.toByteArray()
	}
}
