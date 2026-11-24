package com.personyze.android.internal.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**	Answers like `tracker_v1.ts` does as far as sessions and visitors go, in protocol 2
	(`sdk_visitor.ts`, SPEC section 1): the visitor is field 1 of a valid session - not `user_id` or
	`pz_visitor_id` (SPEC section 3) - else `pz_visitor_id`, else the coercion of `user_id`. One the
	account does not take is replaced by one derived from it (the same id, the same replacement), and
	starts afresh. A request with commands logs them: with no session, or with `new_session`, in a new
	one (start = now, counter + 1); events with no page view to belong to are refused, and a replaced
	visitor's are not logged at all. One with no commands logs nothing and answers the session it was
	sent. The answer names the visitor (`pz_visitor_id`, text) and the width to mint at (`id_bits`).
 **/
internal class FakeTracker(private val nowSec: () -> Long)
{	private val counters = HashMap<Long, Int>()
	var cacheVersion = 5
	var conditions: List<Int> = emptyList()
	var actions: (JsonObject) -> String = { "[]" }
	/**	As the real tracker: new actions only for a request with a navigation (SPEC section 4). **/
	var actionsOnlyWithNavigate = true
	var dismissActions: List<Int> = emptyList()
	/**	The account mints 64-bit visitor ids (`id_bits: 64`), and so takes them. **/
	var mints64 = false
	/**	The account takes 64-bit visitor ids, minting 32-bit ones (`st_visitor_id_width` 'verified'). **/
	var accepts64 = false
	val seen = ArrayList<JsonObject>()
	/**	Each request's visitor as decided, and whether it replaced the one the request named. **/
	val visitors = ArrayList<Pair<Long, Boolean>>()
	private var minted = 0L

	fun step(): Step = step@{ request ->
		val body = Wire.json.parseToJsonElement(request.body!!).jsonObject
		seen.add(body)
		if ((body["protocol"] as? JsonPrimitive)?.content != "2")
		{	return@step HttpOutcome.Answered(400, "Not protocol 2", null)
		}
		val sid = body.str("session_id")
		val newSession = body.bool("new_session")
		val user = body["user_id"] as? JsonPrimitive
		val pz = body["pz_visitor_id"] as? JsonPrimitive
		if (user != null && pz != null)
		{	return@step HttpOutcome.Answered(400, "Send user_id (your own id for the visitor) or pz_visitor_id (Personyze's), not both", null)
		}
		// The real server takes a safe JSON number too; this SDK sends text, always.
		val pzId = pz?.takeIf { it.isString }?.let { Session.visitorId(it.content) }
		if (pz != null && pzId == null)
		{	return@step HttpOutcome.Answered(400, "Invalid pz_visitor_id", null)
		}
		val parts = sid?.split(' ')
		val valid = parts != null && isValidSession(parts)
		if (sid != null && !valid && !newSession)
		{	return@step HttpOutcome.Answered(400, "Invalid session_id", null)
		}
		val sessionVisitor = if (valid) Session.visitorId(parts!![1]) else null
		val visitorNew = (body["pz_visitor_new"] as? JsonPrimitive)?.content == "true" && sessionVisitor == null
		val named = sessionVisitor ?: pzId ?: user?.let { Session.serverUserId(if (it.isString) it.content else it.content.toLong())?.toLong() }
		val (visitor, replaced) = decide(named, pzId != null, visitorNew)
		val commands = body.commands()
		val navigated = commands.any { it[0] == "Navigate" }
		val kept = valid && !replaced
		val session = when
		{	// A replaced visitor's request without a page logs nothing (tracker_v1.ts, dropEvents).
			replaced && !navigated -> null
			// Nothing to log: no flush - the session as it was sent, or none.
			commands.isEmpty() -> if (kept) sid else null
			// Events with no visit to go with: "Events not logged: this session has no page view".
			!navigated && !kept -> return@step HttpOutcome.Answered(500, "Events not logged: this session has no page view on record", null)
			kept && !newSession -> sid
			else -> session(nowSec(), visitor, counters.merge(visitor, 1, Int::plus)!!)
		}
		visitors.add(visitor to replaced)
		HttpOutcome.Answered(200, tracker(session, cacheVersion, conditions, if (navigated || !actionsOnlyWithNavigate) actions(body) else "[]", dismissActions, pzVisitorId = visitor.toString(), idBits = if (mints64) 64 else 32), null)
	}

	/**	`sdkVisitorOf`: a 64-bit id is kept where the account takes them - a new one only where it
		mints them; a new 32-bit one is given a 64-bit id where the account mints them.
	 **/
	private fun decide(named: Long?, fromPz: Boolean, visitorNew: Boolean): Pair<Long, Boolean>
	{	if (named == null)
		{	minted++
			return (if (mints64) (1L shl 62) + minted else 600_000_000L + minted) to true
		}
		if (named < Int.MIN_VALUE || named > Int.MAX_VALUE)
		{	return if ((accepts64 || mints64) && (mints64 || !visitorNew)) named to false else derive(named, 32) to true
		}
		if (visitorNew && mints64 && fromPz)
		{	return derive(named, 64) to true
		}
		return named to false
	}

	internal companion object
	{	/**	The shape of `derivedUserId`, not its HMAC: the same id is always replaced by the same one - at
			64 bits in the ranges the tag mints in, at 32 any id but 0 and the server's [0, 500M), negative
			ones too.
		 **/
		fun derive(named: Long, bits: Int): Long
		{	if (bits == 64)
			{	return (1L shl 62) + Math.floorMod(named, 1L shl 61)
			}
			var i = 0L
			while (true)
			{	val n = ((named + i)*-7046029254386353131L ushr 32).toInt()
				if (n != 0 && !(n >= 0 && n < Session.MIN_ANONYMOUS_ID))
				{	return n.toLong()
				}
				i++
			}
		}

		/**	`tracker_v1.ts`: seven fields at least - a start, a visitor id as the server writes it, a
			visit, a counter and a last start above 0, and a digit.
		 **/
		fun isValidSession(parts: List<String>): Boolean =
			parts.size >= 7 && (parts[0].toLongOrNull() ?: 0) > 0 && Session.visitorId(parts[1]) != null &&
				(parts[2].toLongOrNull() ?: 0) > 0 && (parts[3].toLongOrNull() ?: 0) > 0 && (parts[4].toLongOrNull() ?: 0) > 0 && parts[5].matches(Regex("\\d+"))
	}
}
