package com.personyze.android.internal.core

import java.math.BigInteger
import java.util.zip.CRC32

/**	The session string and the visitor id - the two things a port must get right (SPEC section 3).

	The session string is opaque except for two fields: 0, the session's start (a Unix time), and
	1, the visitor it belongs to. Everything else (visit id, counter, hosts) is the server's.
 **/
internal object Session
{	/**	The server's own session timeout. **/
	const val TTL_MS: Long = 90L*60_000
	/**	Renew a session about to expire deliberately, rather than have it rejected in flight. **/
	const val EXPIRY_MARGIN_MS: Long = 5000

	/**	Width-limited: a corrupted `99999999999999999999 …` must read as garbage (expired), not as a
		start far in the future that never expires - that answers `400 Invalid session_id` forever.
	 **/
	private val RE_START = Regex("^\\s*(\\d{1,10})(?:\\s|$)")
	private val RE_VISITOR = Regex("^-?\\d{1,10}$")

	/**	Field 0 in seconds, or 0 for anything that is not one. **/
	fun startTime(sessionId: String?): Long =
		sessionId?.let { RE_START.find(it)?.groupValues?.get(1)?.toLong() } ?: 0

	/**	Field 1: the visitor the session belongs to, or null when it holds none. **/
	fun visitor(sessionId: String?): Long?
	{	val field = sessionId?.split(' ')?.getOrNull(1) ?: return null
		return if (RE_VISITOR.matches(field)) field.toLong() else null
	}

	/**	Field 3, the session counter: 1 for the visitor's first session. Read only by diagnostics
		and the conformance test (step 7) - the SDK itself treats it as opaque.
	 **/
	fun counter(sessionId: String?): Int? = sessionId?.split(' ')?.getOrNull(3)?.toIntOrNull()

	fun isExpired(sessionId: String?, serverNowMs: Long): Boolean =
		sessionId.isNullOrEmpty() || startTime(sessionId)*1000 + TTL_MS - EXPIRY_MARGIN_MS <= serverNowMs

	/**	Whether [sessionId] may be sent for [userId]: false only when it surely belongs to another
		visitor. Anything that cannot be told either way is left to the expiry rule.
	 **/
	fun isSessionOf(sessionId: String?, userId: Any?): Boolean
	{	val owner = visitor(sessionId) ?: return true
		val expected = serverUserId(userId) ?: return true
		return owner == expected.toLong()
	}

	/**	The visitor id the server makes of `user_id` (`LogVisitRequest.fromSdkTracker`): what
		JavaScript's `Number()` reads as a non-zero 32-bit integer is that integer; anything else the
		CRC-32 of its text in UTF-8, as a signed 32-bit integer. Null for no id, and for a number with
		a fraction, which the server keeps as it is.
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
		if (n != 0.0 && n >= Int.MIN_VALUE && n <= Int.MAX_VALUE)
		{	return if (n == Math.floor(n)) n.toInt() else null
		}
		val crc = CRC32()
		crc.update(utf8(text))
		return crc.value.toInt()
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
