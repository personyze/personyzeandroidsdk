package com.personyze.android.internal.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**	Answers like `tracker_v1.ts` does as far as sessions go: a request with no session, or with
	`new_session`, opens one (start = now, counter + 1); a valid session is kept, and its field 1 -
	not `user_id` - is the visitor (SPEC section 3).
 **/
internal class FakeTracker(private val nowSec: () -> Long)
{	private val counters = HashMap<Long, Int>()
	var cacheVersion = 5
	var conditions: List<Int> = emptyList()
	var actions: (JsonObject) -> String = { "[]" }
	/**	As the real tracker: new actions only for a request with a navigation (SPEC section 4). **/
	var actionsOnlyWithNavigate = true
	var dismissActions: List<Int> = emptyList()
	val seen = ArrayList<JsonObject>()

	fun step(): Step = { request ->
		val body = Wire.json.parseToJsonElement(request.body!!).jsonObject
		seen.add(body)
		val sid = body.str("session_id")
		val newSession = body.bool("new_session")
		val user = body["user_id"] as JsonPrimitive
		val visitor = Session.visitor(sid) ?: Session.serverUserId(if (user.isString) user.content else user.content.toLong())!!.toLong()
		val session = if (sid == null || newSession)
		{	val counter = counters.merge(visitor, 1, Int::plus)!!
			session(nowSec(), visitor, counter)
		}
		else
		{	sid
		}
		val navigated = body.commands().any { it[0] == "Navigate" }
		HttpOutcome.Answered(200, tracker(session, cacheVersion, conditions, if (navigated || !actionsOnlyWithNavigate) actions(body) else "[]", dismissActions), null)
	}
}
