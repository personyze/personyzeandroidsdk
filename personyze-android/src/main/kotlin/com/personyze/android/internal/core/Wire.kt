package com.personyze.android.internal.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**	What describes the device on every request. **/
internal class DeviceContext
(	val timeZone: Double,
	val languages: String,
	val screen: String,
	val os: String,
	val deviceType: String
)

/**	The tracker request and response, with the field names the guide documents. A port must not
	rename or reshape them (SPEC section 1).
 **/
internal object Wire
{	val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

	fun requestBody
	(	userId: JsonPrimitive,
		sessionId: String?,
		newSession: Boolean,
		pastSessions: List<Long>,
		device: DeviceContext,
		notiEnabled: Boolean,
		commands: List<Command>
	): String = buildJsonObject {
		put("user_id", userId)
		put("session_id", sessionId?.let { JsonPrimitive(it) } ?: JsonNull)
		put("new_session", newSession)
		put("past_sessions", pastSessions.joinToString(","))
		put("platform", "Android")
		put("time_zone", device.timeZone)
		put("languages", device.languages)
		put("screen", device.screen)
		put("os", device.os)
		put("device_type", device.deviceType)
		put("noti_enabled", notiEnabled)
		put("commands", buildJsonArray {
			for (cmd in commands)
			{	add(buildJsonArray { for (arg in cmd) add(arg?.let { JsonPrimitive(it) } ?: JsonNull) })
			}
		})
	}.toString()

	class Decided(val id: Int, val data: Map<String, String>?)

	class TrackerResponse
	(	val sessionId: String?,
		val cacheVersion: Int,
		val conditions: List<Int>,
		val actions: List<Decided>,
		val dismissConditions: List<Int>,
		val dismissActions: List<Int>,
		val raw: String
	)

	/**	Null when the body is not the object the tracker sends - treated as a failed request. **/
	fun parseResponse(text: String): TrackerResponse?
	{	val obj = try
		{	json.parseToJsonElement(text) as? JsonObject
		}
		catch (e: Exception)
		{	null
		} ?: return null
		if (!obj.containsKey("actions") && !obj.containsKey("session_id"))
		{	return null
		}
		return TrackerResponse(
			sessionId = (obj["session_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() },
			cacheVersion = int(obj["cache_version"]) ?: 0,
			conditions = (obj["conditions"] as? JsonArray).orEmpty().mapNotNull { int((it as? JsonObject)?.get("id")) },
			actions = (obj["actions"] as? JsonArray).orEmpty().mapNotNull { element ->
				val row = element as? JsonObject ?: return@mapNotNull null
				val id = int(row["id"]) ?: return@mapNotNull null
				Decided(id, (row["data"] as? JsonObject)?.mapValues { (_, v) -> text(v) })
			},
			dismissConditions = (obj["dismiss_conditions"] as? JsonArray).orEmpty().mapNotNull { int(it) },
			dismissActions = (obj["dismiss_actions"] as? JsonArray).orEmpty().mapNotNull { int(it) },
			raw = text
		)
	}

	/**	A REST GET answering an array of rows. Null when it is not one. **/
	fun parseRows(text: String): List<JsonObject>? =
		try
		{	(json.parseToJsonElement(text) as? JsonArray)?.filterIsInstance<JsonObject>()
		}
		catch (e: Exception)
		{	null
		}

	/**	An id or count that may come as a number or as numeric text. **/
	fun int(e: JsonElement?): Int?
	{	val p = e as? JsonPrimitive ?: return null
		if (p is JsonNull)
		{	return null
		}
		return p.contentOrNull?.trim()?.toIntOrNull() ?: p.doubleOrNull?.takeIf { it == Math.floor(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
	}

	fun long(e: JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.toLongOrNull()

	fun double(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.toDoubleOrNull()

	fun bool(e: JsonElement?): Boolean
	{	val p = e as? JsonPrimitive ?: return false
		val s = p.contentOrNull ?: return false
		return s == "true" || (s.toDoubleOrNull() ?: 0.0) != 0.0
	}

	/**	A value as text, the way the v1 SDK stringified it: strings as they are, other JSON as JSON. **/
	fun text(e: JsonElement?): String = when (e)
	{	null, is JsonNull -> ""
		is JsonPrimitive -> e.content
		else -> e.toString()
	}

	fun string(e: JsonElement?): String = when (e)
	{	null, is JsonNull -> ""
		is JsonPrimitive -> e.content
		else -> e.toString()
	}

	fun intList(e: JsonElement?): List<Int> = when (e)
	{	is JsonArray -> e.mapNotNull { int(it) }
		is JsonPrimitive -> if (e is JsonNull) emptyList() else e.content.split(',').mapNotNull { it.trim().toIntOrNull() }
		else -> emptyList()
	}

	fun primitiveOf(userId: Any): JsonPrimitive = when (userId)
	{	is Int -> JsonPrimitive(userId)
		is Long -> JsonPrimitive(userId)
		else -> JsonPrimitive(userId.toString())
	}

	fun stringOf(p: JsonPrimitive): String = p.jsonPrimitive.content
}
