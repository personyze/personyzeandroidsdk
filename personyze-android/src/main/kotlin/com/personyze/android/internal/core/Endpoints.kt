package com.personyze.android.internal.core

/**	Paths under `<baseUrl>/rest/`, and the two encodings they need. **/
internal object Endpoints
{	const val TRACKER = "tracker-v1"

	/**	The v1 columns plus `placeholder_order` (SPEC section 10), so [com.personyze.android.PersonyzeResult.actionsIn]
		orders as the web tag does. A server older than that column answers 400; then [ACTION_COLUMNS_V1].
	 **/
	const val ACTION_COLUMNS = "id,name,content_type,content_param,content_begin,content_end,libs_app,placeholders,placeholder_order"
	const val ACTION_COLUMNS_V1 = "id,name,content_type,content_param,content_begin,content_end,libs_app,placeholders"
	const val CONDITION_COLUMNS = "id,name"
	const val PLACEHOLDER_COLUMNS = "id,name,html_id,units_count_max"

	fun conditions(ids: Collection<Int>): String = "conditions/columns/$CONDITION_COLUMNS/where/id:" + ids.joinToString(",")

	fun actions(ids: Collection<Int>, withOrder: Boolean): String =
		"actions/columns/" + (if (withOrder) ACTION_COLUMNS else ACTION_COLUMNS_V1) + "/where/id:" + ids.joinToString(",")

	fun placeholders(ids: Collection<Int>): String = "placeholders/columns/$PLACEHOLDER_COLUMNS/where/id:" + ids.joinToString(",")

	fun verdict(conditionId: Int): String = "ab_test/where/condition_id=$conditionId"

	/**	The visitor's queued notification, by the visitor id - the id the server makes of `user_id`,
		which is what field 1 of their session holds.

		v1 sent the session string instead (`session_id=`), encoded with Java's URLEncoder: `+` for a
		space, and a comma encoded twice for a parser that decoded twice. Since the REST parser's Deno
		port (2026-05) that answered `400 Invalid session_id`; the server now reads both. The visitor id
		needs no encoding at all, and is all the server reads from the session here.
	 **/
	fun notification(visitorId: Int, messageId: Long? = null): String
	{	val sb = StringBuilder("current_notification/where/user_id=").append(visitorId)
		if (messageId != null)
		{	sb.append("&message_id=").append(messageId)
		}
		return sb.toString()
	}

	/**	The document of a `Navigate`. The server decodes it (`decodeURIComponent`), so a raw `%`
		in a name threw there; a name without special characters arrives exactly as before.
	 **/
	fun documentUrn(document: String): String = "urn:personyze:doc:" + encodeUriComponent(document)

	/**	JavaScript's `encodeURIComponent`: everything but `A-Z a-z 0-9 - _ . ! ~ * ' ( )` as UTF-8
		percent escapes (a space is `%20`, never `+`).
	 **/
	fun encodeUriComponent(s: String): String
	{	val sb = StringBuilder(s.length + 16)
		for (b in Session.utf8(s))
		{	val c = b.toInt() and 0xFF
			if (c < 0x80 && UNRESERVED[c])
			{	sb.append(c.toChar())
			}
			else
			{	sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
			}
		}
		return sb.toString()
	}

	private const val HEX = "0123456789ABCDEF"
	private val UNRESERVED = BooleanArray(128) { c ->
		c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || "-_.!~*'()".indexOf(c.toChar()) != -1
	}
}
