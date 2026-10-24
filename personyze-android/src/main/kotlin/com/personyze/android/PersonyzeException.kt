package com.personyze.android

/**	Why a call to Personyze did not do what it was asked.

	The SDK never throws these at the app: a failed decision answers an empty
	[PersonyzeResult] carrying the error, and every failure - decisions, events, metadata,
	notifications - reaches [PersonyzeConfig.Builder.onError]. On a server a bad key answers 500 for
	one page; in an app that throws it, every installed copy would crash until an update ships, so
	the app is told instead (SPEC section 11).

	No message ever contains the API key.
 **/
public sealed class PersonyzeException(message: String, cause: Throwable? = null) : Exception(message, cause)
{	/**	Personyze rejected the API key (HTTP 401). Check it in Settings > Integrations. **/
	public class Auth internal constructor(message: String) : PersonyzeException(message)

	/**	Personyze refused the request (HTTP 400 or another 4xx but 401 and 429). [text] is the
		server's plain-text reason, such as `No Product ID specified`. Retrying cannot help.
	 **/
	public class BadRequest internal constructor(public val status: Int, public val text: String) :
		PersonyzeException(if (text.isBlank()) "Personyze refused the request (HTTP $status)" else "Personyze refused the request (HTTP $status): $text")

	/**	The request did not get an answer to act on: no network, a timeout, a server error, a
		throttled account, or a body that was not the expected JSON. [status] is the HTTP status
		when there was one.
	 **/
	public class Transport internal constructor
	(	public val status: Int?,
		public val kind: Kind,
		message: String,
		cause: Throwable? = null
	) : PersonyzeException(message, cause)
	{	public enum class Kind
		{	/**	The device has no network; the events are kept and sent later. **/
			OFFLINE,
			/**	Name lookup, connect or TLS failed before anything was sent. **/
			CONNECTION,
			/**	The call's time budget ran out. **/
			TIMEOUT,
			/**	HTTP 5xx, or the connection broke after the request went out. **/
			SERVER,
			/**	HTTP 429: the account's tracking is paused (its plan's page views are used up, or it
				awaits an upgrade). The SDK backs off for 10 minutes.
			 **/
			RATE_LIMITED,
			/**	HTTP 200 whose body was not the JSON Personyze sends. **/
			MALFORMED_RESPONSE,
		}
	}

	/**	Events that were dropped rather than sent. [commands] are the wire commands, so the app
		can log what was lost.
	 **/
	public class Delivery internal constructor
	(	public val reason: Reason,
		public val commands: List<List<String>>,
		message: String,
		cause: PersonyzeException? = null
	) : PersonyzeException(message, cause)
	{	public enum class Reason
		{	/**	The request may or may not have reached Personyze (a timeout or error after it was
				sent). Sending it again could count a purchase twice, so it is not resent.
			 **/
			AMBIGUOUS,
			/**	The app was stopped while the request was in flight - the same doubt as [AMBIGUOUS]. **/
			INTERRUPTED,
			/**	The events waited longer than a day for a network. **/
			EXPIRED,
			/**	The events belonged to a visitor who is no longer the current one. **/
			OTHER_VISITOR,
			/**	An action report belonged to a session that has ended. **/
			OTHER_SESSION,
			/**	Too many events were waiting; the oldest were dropped. **/
			OVERFLOW,
			/**	Personyze refused the request ([cause] says why). **/
			REFUSED,
		}
	}

	/**	The SDK refused an input before sending it, such as a blank product id. The call was
		ignored; nothing went over the network.
	 **/
	public class Invalid internal constructor(message: String) : PersonyzeException(message)
}

/**	Where a failure happened, for [PersonyzeErrorListener]. **/
public class PersonyzeErrorContext internal constructor
(	/**	The SDK operation: `navigate`, `refresh`, `flush`, `metadata`, `notifications`, `verdict`, `track`. **/
	public val operation: String,
	/**	How many events were kept to send later. **/
	public val deferred: Int = 0,
	/**	How many events were dropped. **/
	public val dropped: Int = 0
)
{	override fun toString(): String = "PersonyzeErrorContext(operation=$operation, deferred=$deferred, dropped=$dropped)"
}

/**	Receives every failure the SDK absorbs. Called on a background thread. **/
public fun interface PersonyzeErrorListener
{	public fun onError(error: PersonyzeException, context: PersonyzeErrorContext)
}
