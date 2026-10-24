package com.personyze.android.internal.core

import com.personyze.android.PersonyzeNotification
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**	`GET current_notification` answers `null` or one queued message (app-service
	`mod_rest/handlers/current_notification.ts`), put there by a "Web Push Send" action for a visitor
	whose requests said `noti_enabled`.
 **/
internal object NotificationPoll
{	fun parse(text: String): PersonyzeNotification?
	{	val trimmed = text.trim()
		if (trimmed.isEmpty() || trimmed == "null")
		{	return null
		}
		val obj = try
		{	Wire.json.parseToJsonElement(trimmed) as? JsonObject
		}
		catch (e: Exception)
		{	null
		} ?: return null
		val messageId = Wire.long(obj["message_id"]) ?: return null
		val title = Wire.string(obj["title"]).trim()
		val body = Wire.string(obj["body"]).trim()
		if (messageId <= 0 || title.isEmpty() || body.isEmpty())
		{	return null
		}
		val buttons = (obj["actions"] as? JsonArray).orEmpty().mapNotNull { e ->
			val b = e as? JsonObject ?: return@mapNotNull null
			val buttonTitle = Wire.string(b["title"]).trim()
			val url = Wire.string(b["action"]).trim()
			if (buttonTitle.isEmpty() || url.isEmpty()) null else PersonyzeNotification.Button(buttonTitle, Wire.string(b["icon"]).trim(), url)
		}.take(3)
		return PersonyzeNotification(
			messageId = messageId,
			title = title,
			body = body,
			url = Wire.string(obj["tag"]).trim(),
			imageUrl = Wire.string(obj["image"]).trim(),
			iconUrl = Wire.string(obj["icon"]).trim(),
			badgeUrl = Wire.string(obj["badge"]).trim(),
			buttons = buttons,
			silent = Wire.bool(obj["silent"]),
			renotify = Wire.bool(obj["renotify"]),
			vibrate = Wire.string(obj["vibrate"]).split(',').mapNotNull { it.trim().toLongOrNull()?.takeIf { ms -> ms >= 0 } }
		)
	}
}
