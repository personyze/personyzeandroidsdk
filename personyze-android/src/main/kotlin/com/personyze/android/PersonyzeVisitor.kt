package com.personyze.android

/**	Who Personyze is talking about right now. **/
public class PersonyzeVisitor internal constructor
(	/**	The id the app set with [PersonyzeApi.setUserId], or null for the anonymous install visitor. **/
	public val userId: String?,
	/**	The 32-bit visitor id Personyze files this visitor under - your numeric id, or the CRC-32
		of a text one, or the install's own anonymous id.
	 **/
	public val visitorId: Int?,
	/**	The current session string, or null before the first request. **/
	public val sessionId: String?
)
{	override fun equals(other: Any?): Boolean =
		other is PersonyzeVisitor && userId == other.userId && visitorId == other.visitorId && sessionId == other.sessionId
	override fun hashCode(): Int = 31*(31*(userId?.hashCode() ?: 0) + (visitorId ?: 0)) + (sessionId?.hashCode() ?: 0)
	override fun toString(): String = "PersonyzeVisitor(userId=$userId, visitorId=$visitorId, sessionId=$sessionId)"
}
