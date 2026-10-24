package com.personyze.android.internal.core

/**	What the engine needs from the device, so the engine itself runs on a plain JVM in tests. **/
internal interface Platform
{	/**	Wall clock, ms since the epoch. **/
	fun nowMs(): Long

	/**	A clock that never jumps, for deadlines. **/
	fun monotonicMs(): Long

	fun deviceContext(): DeviceContext

	/**	Whether a notification could be shown now: enabled in the config, allowed by the user,
		channel not off. Sent as `noti_enabled`: true makes push campaigns queue messages for the
		visitor, which is pointless where they cannot appear.
	 **/
	fun notificationsShowable(): Boolean

	fun isOnline(): Boolean

	/**	Have queued events sent later, when the network is back - WorkManager on Android. **/
	fun scheduleOutboxFlush()

	fun log(message: String)
}

/**	A v1 install's state, read once from its SharedPreferences. **/
internal class LegacyState
(	val userId: Int,
	val session: String?,
	/**	As v1 stored them: oldest first. **/
	val pastSessionsOldestFirst: List<Long>,
	val blocked: Map<Int, Int>,
	val newSession: Boolean,
	val notiLastCheckMs: Long,
	/**	`apiKey.hashCode()` of the key it last ran with. **/
	val apiKeyHash: Int
)

internal interface LegacySource
{	fun read(): LegacyState?
	/**	Delete the v1 files once the new state is written. **/
	fun delete()
}
