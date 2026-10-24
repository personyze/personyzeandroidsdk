package com.personyze.android.internal.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class HttpRequest
(	val method: String,
	val url: String,
	val headers: Map<String, String>,
	val body: String?
)

internal sealed class HttpOutcome
{	/**	Any HTTP status. [dateMs] is the server's `Date` header. **/
	class Answered(val status: Int, val body: String, val dateMs: Long?) : HttpOutcome()

	/**	No response. [sent]: whether the request may have reached the server - false for a failed
		name lookup, connect or TLS handshake, and for a device with no network.
	 **/
	class Failed(val sent: Boolean, val timedOut: Boolean, val offline: Boolean, val cause: Throwable?) : HttpOutcome()
}

/**	One HTTP exchange within [timeoutMs], covering everything it waits for. Must return any status
	and fail only when there was no response at all.
 **/
internal interface Transport
{	suspend fun execute(request: HttpRequest, timeoutMs: Long): HttpOutcome
}

/**	OkHttp's call timeout spans name lookup, connect, TLS, writing, the headers and the whole body
	(SPEC section 5: a socket timeout bounds each read, and a body trickling in a byte at a time never
	trips it). Name lookup itself cannot be interrupted, so the coroutine also gives up at the
	deadline and cancels the call.

	Request bodies are one-shot: OkHttp then retries silently only a connection that sent nothing,
	never a request that may have arrived - the tracker is not idempotent.
 **/
internal class OkHttpTransport(base: OkHttpClient?, private val isOnline: () -> Boolean) : Transport
{	private val client: OkHttpClient = (base?.newBuilder() ?: OkHttpClient.Builder())
		.followRedirects(false)
		.followSslRedirects(false)
		.eventListenerFactory { call -> SentFlagListener(call.request().tag(AtomicBoolean::class.java)) }
		.build()

	override suspend fun execute(request: HttpRequest, timeoutMs: Long): HttpOutcome
	{	if (!isOnline())
		{	return HttpOutcome.Failed(sent = false, timedOut = false, offline = true, cause = null)
		}
		val sent = AtomicBoolean(false)
		val builder = Request.Builder().url(request.url).tag(AtomicBoolean::class.java, sent)
		for ((k, v) in request.headers)
		{	builder.header(k, v)
		}
		builder.method(request.method, request.body?.let { OneShotBody(it) })
		val call = client.newCall(builder.build())
		call.timeout().timeout(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
		val outcome = withTimeoutOrNull(timeoutMs.coerceAtLeast(1))
		{	suspendCancellableCoroutine { cont ->
				cont.invokeOnCancellation { call.cancel() }
				call.enqueue(object : Callback
				{	override fun onResponse(call: Call, response: Response)
					{	val result = try
						{	response.use { r ->
								HttpOutcome.Answered(r.code, r.body.string(), r.headers.getDate("Date")?.time)
							}
						}
						catch (e: IOException)
						{	failed(sent.get(), e)
						}
						cont.resume(result)
					}

					override fun onFailure(call: Call, e: IOException)
					{	cont.resume(failed(sent.get(), e))
					}
				})
			}
		}
		if (outcome == null)
		{	call.cancel()
			return HttpOutcome.Failed(sent = sent.get(), timedOut = true, offline = false, cause = null)
		}
		return outcome
	}

	private fun failed(sent: Boolean, e: IOException): HttpOutcome.Failed =
		HttpOutcome.Failed(sent = sent, timedOut = e is InterruptedIOException, offline = false, cause = e)

	private class SentFlagListener(private val flag: AtomicBoolean?) : EventListener()
	{	override fun requestHeadersStart(call: Call)
		{	flag?.set(true)
		}
	}

	private class OneShotBody(text: String) : RequestBody()
	{	private val bytes = text.toByteArray(Charsets.UTF_8)
		override fun contentType() = JSON
		override fun contentLength() = bytes.size.toLong()
		override fun isOneShot() = true
		override fun writeTo(sink: BufferedSink)
		{	sink.write(bytes)
		}
	}

	private companion object
	{	val JSON = "application/json; charset=utf-8".toMediaType()
	}
}
