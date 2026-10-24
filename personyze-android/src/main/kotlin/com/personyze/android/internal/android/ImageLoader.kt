package com.personyze.android.internal.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**	Notification images: a badge, an icon, a big picture, button icons. Fetched without the API key
	(they are the account's public assets), each within its own timeout, at most 3 MB, and scaled
	down - a notification carries its bitmaps through a Binder call of about 1 MB.
 **/
internal class ImageLoader(base: OkHttpClient)
{	private val client = base.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()

	class Want(val url: String, val maxPx: Int)

	/**	All of [wants] in parallel, within [totalMs]; a failed one is null. **/
	suspend fun loadAll(wants: List<Want?>, totalMs: Long): List<Bitmap?> = coroutineScope {
		val jobs = wants.map { want -> async { want?.let { withTimeoutOrNull(totalMs) { load(it.url, it.maxPx) } } } }
		jobs.awaitAll()
	}

	suspend fun load(url: String, maxPx: Int): Bitmap?
	{	if (!url.startsWith("https://") && !url.startsWith("http://"))
		{	return null
		}
		val (bytes, type) = fetch(url) ?: return null
		return withContext(Dispatchers.Default) {
			try
			{	if (type.startsWith("image/svg+xml") || url.substringBefore('?').endsWith(".svg", ignoreCase = true))
				{	svg(bytes, maxPx)
				}
				else
				{	bitmap(bytes, maxPx)
				}
			}
			catch (e: Exception)
			{	null
			}
			catch (e: OutOfMemoryError)
			{	null
			}
		}
	}

	private suspend fun fetch(url: String): Pair<ByteArray, String>? = suspendCancellableCoroutine { cont ->
		val call = client.newCall(Request.Builder().url(url).build())
		cont.invokeOnCancellation { call.cancel() }
		call.enqueue(object : Callback
		{	override fun onFailure(call: Call, e: IOException)
			{	cont.resume(null)
			}

			override fun onResponse(call: Call, response: Response)
			{	val result = try
				{	response.use { r ->
						if (!r.isSuccessful || r.body.contentLength() > MAX_BYTES)
						{	null
						}
						else
						{	val source = r.body.source()
							source.request(MAX_BYTES + 1)
							if (source.buffer.size > MAX_BYTES) null else source.readByteArray() to (r.header("Content-Type") ?: "").lowercase()
						}
					}
				}
				catch (e: IOException)
				{	null
				}
				cont.resume(result)
			}
		})
	}

	private fun bitmap(bytes: ByteArray, maxPx: Int): Bitmap?
	{	val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
		{	return null
		}
		var sample = 1
		while (maxOf(bounds.outWidth, bounds.outHeight)/(sample*2) >= maxPx)
		{	sample *= 2
		}
		val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
		return fit(decoded, maxPx)
	}

	/**	AndroidSVG, as v1. A document without a size takes its viewBox, else 96 px. **/
	private fun svg(bytes: ByteArray, maxPx: Int): Bitmap?
	{	val svg = SVG.getFromString(String(bytes, Charsets.UTF_8))
		var w = svg.documentWidth
		var h = svg.documentHeight
		if (w <= 0 || h <= 0)
		{	val box = svg.documentViewBox
			w = box?.width()?.takeIf { it > 0 } ?: 96f
			h = box?.height()?.takeIf { it > 0 } ?: 96f
		}
		val scale = minOf(1f, maxPx/maxOf(w, h))
		val bw = (w*scale).toInt().coerceAtLeast(1)
		val bh = (h*scale).toInt().coerceAtLeast(1)
		svg.setDocumentWidth(bw.toFloat())
		svg.setDocumentHeight(bh.toFloat())
		val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
		svg.renderToCanvas(Canvas(bitmap))
		return bitmap
	}

	private fun fit(bitmap: Bitmap, maxPx: Int): Bitmap
	{	val longest = maxOf(bitmap.width, bitmap.height)
		if (longest <= maxPx)
		{	return bitmap
		}
		val scale = maxPx.toFloat()/longest
		return Bitmap.createScaledBitmap(bitmap, (bitmap.width*scale).toInt().coerceAtLeast(1), (bitmap.height*scale).toInt().coerceAtLeast(1), true)
	}

	private companion object
	{	const val MAX_BYTES = 3L*1024*1024
	}
}
