package com.personyze.android

/**	Where push stands on this install (SPEC section 11) - what a screen of the app's, or a support case, can show when
	notifications do not arrive at once:

	- no [token]: the app has not passed one on ([PersonyzeApi.setPushToken]) - the notifications come when the app checks;
	- a token, no [registeredVisitorId]: not registered with Personyze yet - that happens after the app's first request
	  (a navigation, events or a refresh), and again after a login or logout; or the notifications are off;
	- registered, not [pushed]: the account has no Firebase service account in the Personyze panel (Integrations > App
	  push) - registered all the same, so a key added later reaches this device;
	- [pushed]: a campaign's notification reaches this device at once.
 **/
public class PersonyzePushStatus internal constructor
(	/**	The token the app passed on, or null. **/
	public val token: String?,
	/**	The visitor Personyze has this device registered for, or null. **/
	public val registeredVisitorId: Int?,
	/**	When it was last registered, ms since the epoch; 0 for never. **/
	public val registeredAtMs: Long,
	/**	Whether Personyze pushes to this device: the account has its key for the platform. **/
	public val pushed: Boolean
)
{	override fun equals(other: Any?): Boolean =
		other is PersonyzePushStatus && token == other.token && registeredVisitorId == other.registeredVisitorId && registeredAtMs == other.registeredAtMs && pushed == other.pushed
	override fun hashCode(): Int = 31*(31*(31*(token?.hashCode() ?: 0) + (registeredVisitorId ?: 0)) + registeredAtMs.hashCode()) + pushed.hashCode()
	/**	The token cut short: enough to tell two devices apart in a log. **/
	override fun toString(): String = "PersonyzePushStatus(token=${token?.let { it.take(10) + "… (${it.length})" }}, registeredVisitorId=$registeredVisitorId, registeredAtMs=$registeredAtMs, pushed=$pushed)"
}
