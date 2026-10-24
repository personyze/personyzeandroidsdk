package com.personyze.android.internal.core

import com.personyze.android.PersonyzeException
import com.personyze.android.PersonyzeException.Transport.Kind
import okhttp3.Credentials

internal sealed class RestResult
{	class Ok(val body: String, val dateMs: Long?) : RestResult()

	/**	[maybeDelivered]: whether any attempt may have reached the server. When false, nothing was
		logged and the commands may be kept for later.
	 **/
	class Failed(val error: PersonyzeException, val maybeDelivered: Boolean) : RestResult()
}

/**	Personyze's `/rest`, with the failure rules every port shares (SPEC sections 1 and 5):

	* one deadline for the whole call, every attempt included;
	* at most one retry, only for a connection failure, a timeout or a 5xx, only while at least a
	  tenth of the budget is left - so a genuine timeout, having used the budget, is not retried;
	* 401 is an auth error and 429 a throttle, neither retried; any other 4xx (a 3xx too, since
	  redirects are not followed) is a bad request carrying the server's plain-text reason.
 **/
internal class RestClient
(	private val transport: Transport,
	private val apiKey: String,
	baseUrl: String,
	private val retries: Int,
	private val monotonicMs: () -> Long,
	userAgent: String
)
{	private val root = "$baseUrl/rest/"
	/**	`NO_WRAP`-style: v1 used `Base64.DEFAULT`, which ends the header value with a newline. **/
	private val headers = mapOf(
		"Authorization" to Credentials.basic("api", apiKey, Charsets.UTF_8),
		"Accept" to "application/json",
		"User-Agent" to userAgent
	)

	/**	A server message that echoes the key must not carry it into a log (SPEC section 2). **/
	private fun redact(text: String): String = if (apiKey.length >= 8) text.replace(apiKey, "***", ignoreCase = true) else text

	suspend fun call(method: String, path: String, body: String?, budgetMs: Long): RestResult
	{	val deadline = monotonicMs() + budgetMs
		var maybeDelivered = false
		var lastError: PersonyzeException? = null
		for (attempt in 0..retries)
		{	val remaining = deadline - monotonicMs()
			if (remaining <= 0 || attempt > 0 && remaining < budgetMs/10)
			{	break
			}
			when (val outcome = transport.execute(HttpRequest(method, root + path, headers, body), remaining))
			{	is HttpOutcome.Answered ->
				{	val status = outcome.status
					val text = if (status == 200) "" else redact(outcome.body.trim())
					when
					{	status == 200 -> return RestResult.Ok(outcome.body, outcome.dateMs)
						status == 401 -> return RestResult.Failed(PersonyzeException.Auth("Personyze rejected the API key (HTTP 401). Check it in Settings > Integrations > Full-featured API."), false)
						status == 429 -> return RestResult.Failed(PersonyzeException.Transport(429, Kind.RATE_LIMITED, "Personyze declined the call (HTTP 429): " + text.take(200)), false)
						status < 500 -> return RestResult.Failed(PersonyzeException.BadRequest(status, text.take(1000)), maybeDelivered)
						else ->
						{	maybeDelivered = true
							lastError = PersonyzeException.Transport(status, Kind.SERVER, "Personyze answered HTTP $status: " + text.take(200))
						}
					}
				}
				is HttpOutcome.Failed ->
				{	if (outcome.sent)
					{	maybeDelivered = true
					}
					lastError = when
					{	outcome.offline -> PersonyzeException.Transport(null, Kind.OFFLINE, "No network")
						outcome.timedOut -> PersonyzeException.Transport(null, Kind.TIMEOUT, "Personyze did not answer within ${budgetMs}ms", outcome.cause)
						!outcome.sent -> PersonyzeException.Transport(null, Kind.CONNECTION, "Could not reach Personyze: " + describe(outcome.cause), outcome.cause)
						else -> PersonyzeException.Transport(null, Kind.SERVER, "The connection to Personyze broke: " + describe(outcome.cause), outcome.cause)
					}
					if (outcome.offline)
					{	break
					}
				}
			}
		}
		return RestResult.Failed(lastError ?: PersonyzeException.Transport(null, Kind.TIMEOUT, "Personyze did not answer within ${budgetMs}ms"), maybeDelivered)
	}

	private fun describe(e: Throwable?): String = redact(e?.let { it.message ?: it.javaClass.simpleName } ?: "unknown error")
}
