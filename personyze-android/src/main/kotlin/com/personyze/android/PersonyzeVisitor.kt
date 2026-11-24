package com.personyze.android

/**	Who Personyze is talking about right now. **/
public class PersonyzeVisitor internal constructor
(	/**	The id the app set with [PersonyzeApi.setUserId], or null for the anonymous install visitor. **/
	public val userId: String?,
	/**	The visitor id Personyze files this visitor under, a signed 64-bit number (a [Long] since 3.0,
		an `Int` before): the install's own anonymous id - past 32 bits on an account that mints 64-bit
		ids, as Personyze answered it - or, after [PersonyzeApi.setUserId], your numeric id or the
		CRC-32 of a text one (both 32-bit). Compare it as a number or as its decimal text, never
		through a `Double`: past 2^53 that is another visitor's id.

		Null for a new install's visitor, and after [PersonyzeApi.reset], until Personyze's first
		answer names it: the id the device made up is replaced on an account that mints 64-bit ids. It changes once more only if Personyze
		gives the install another id - a 32-bit one for a 64-bit visitor, on an account taken back to
		32-bit ids. Store it once it is set, and again when it changes.
	 **/
	public val visitorId: Long?,
	/**	The current session string, or null before the first request. **/
	public val sessionId: String?
)
{	override fun equals(other: Any?): Boolean =
		other is PersonyzeVisitor && userId == other.userId && visitorId == other.visitorId && sessionId == other.sessionId
	override fun hashCode(): Int = 31*(31*(userId?.hashCode() ?: 0) + (visitorId?.hashCode() ?: 0)) + (sessionId?.hashCode() ?: 0)
	override fun toString(): String = "PersonyzeVisitor(userId=$userId, visitorId=$visitorId, sessionId=$sessionId)"
}
