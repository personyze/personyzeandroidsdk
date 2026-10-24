package com.personyze.android.internal.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**	`state.json`. Unknown keys are ignored and missing ones take their defaults, so a newer SDK
	reads an older file; [v] changes only for an incompatible layout.
 **/
@Serializable
internal data class PersistedState
(	val v: Int = 1,
	/**	A SHA-256 prefix of the API key - never the key. Another key means another account. **/
	val apiKeyFp: String? = null,
	/**	The app's user id (text or number), or null for the install's anonymous visitor. **/
	val userId: JsonPrimitive? = null,
	/**	The newest session string, for the visitor in field 1. **/
	val session: String? = null,
	/**	Bumped by `startNewSession()`; a batch of a later epoch than [sentEpoch] opens a new session. **/
	val epoch: Int = 0,
	val sentEpoch: Int = 0,
	/**	Server time minus device time, from the `Date` header: a device clock off by 90 minutes
		would otherwise open a new session on every request.
	 **/
	val clockOffsetMs: Long = 0,
	/**	Session start times, newest first, the current one included - as the web tag keeps them. **/
	val pastSessions: List<Long> = emptyList(),
	/**	Action id -> sessions left not to show it ("don't show again"). **/
	val blocked: Map<Int, Int> = emptyMap(),
	val cacheVersion: Int = 0,
	val result: PersistedResult? = null,
	/**	Action statuses reported since the last navigation: an action is reported executed once per page. **/
	val pageReports: Map<Int, List<String>> = emptyMap(),
	val notifications: PersistedNotifications = PersistedNotifications(),
	/**	Device time until which the account is treated as paused (HTTP 429). **/
	val throttledUntilMs: Long = 0
)

@Serializable
internal data class PersistedResult
(	val conditions: List<Int> = emptyList(),
	val actions: List<PersistedAction> = emptyList(),
	val dismissedConditions: List<Int> = emptyList(),
	val dismissedActions: List<Int> = emptyList(),
	val sessionId: String? = null,
	val cacheVersion: Int = 0,
	val raw: String? = null
)

@Serializable
internal data class PersistedAction(val id: Int, val data: Map<String, String>? = null)

@Serializable
internal data class PersistedNotifications
(	val lastCheckMs: Long = 0,
	/**	Visitor id -> the last message shown. The server keeps one queued message per visitor and
		replaces it on each send, so a different id is always a new send.
	 **/
	val delivered: Map<String, Long> = emptyMap(),
	/**	After a v1 upgrade the queued message was probably shown already (v1's delete never
		deleted): take the first one seen as delivered, silently.
	 **/
	val primeOnNextPoll: Boolean = false
)

/**	A queued command and what it was queued under. **/
@Serializable
internal data class PendingItem
(	val cmd: List<String?>,
	/**	The `user_id` it is about. **/
	val user: JsonPrimitive,
	val epoch: Int,
	/**	Start of the session it was queued in (0 for none): an action report goes only into that session. **/
	val sessionStart: Long,
	val queuedAtMs: Long,
	/**	From `track()`: sent in a request of its own. **/
	val raw: Boolean = false,
	/**	Written to disk before the request goes out; found set at startup, it may have been sent. **/
	val inflight: Boolean = false,
	/**	A navigation is never kept for later - a page view sent hours late is a wrong page view. **/
	val navigate: Boolean = false,
	val seq: Long = 0
)

@Serializable
internal data class OutboxFile(val v: Int = 1, val items: List<PendingItem> = emptyList())

/**	`metadata.json`: the per-account templates, cached by `cache_version`. Rows are kept raw so a
	column this version does not read survives.
 **/
@Serializable
internal data class MetadataFile
(	val v: Int = 1,
	val apiKeyFp: String? = null,
	val cacheVersion: Int = 0,
	val conditions: Map<Int, JsonObject> = emptyMap(),
	val actions: Map<Int, JsonObject> = emptyMap(),
	val placeholders: Map<Int, JsonObject> = emptyMap(),
	/**	Ids asked for and not returned, for this cache version: not asked again. **/
	val missing: List<String> = emptyList(),
	/**	False once the server refused `placeholder_order`. **/
	val placeholderOrder: Boolean = true
)
