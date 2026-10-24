package com.personyze.android.internal.android

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.os.LocaleListCompat
import com.personyze.android.internal.core.DeviceContext
import com.personyze.android.internal.core.Platform
import java.util.TimeZone

internal class AndroidPlatform
(	private val context: Context,
	private val notificationsWanted: () -> Boolean
) : Platform
{	override fun nowMs(): Long = System.currentTimeMillis()

	override fun monotonicMs(): Long = SystemClock.elapsedRealtime()

	override fun deviceContext(): DeviceContext = deviceContext(context)

	override fun notificationsShowable(): Boolean = notificationsWanted() && NotificationRenderer.canShow(context)

	override fun isOnline(): Boolean
	{	return try
		{	val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
			val network = cm.activeNetwork ?: return false
			cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
		}
		catch (e: SecurityException)
		{	true	// no ACCESS_NETWORK_STATE: let the request find out
		}
	}

	override fun scheduleOutboxFlush()
	{	WorkScheduling.scheduleOutbox(context)
	}

	override fun log(message: String)
	{	Log.w(TAG, message)
	}

	companion object
	{	const val TAG = "Personyze"
		private val RE_VERSION = Regex("^[\\d.]+")

		/**	What every request says about the device.

			* `time_zone` - the offset now, daylight saving included (v1 sent the raw offset).
			* `languages` - up to three, as the server reads them (two letters each); language tags,
			  so Hebrew is `he`, not Java's legacy `iw`.
			* `os` - `Android/14`: the server keeps only `Name/[\d.]+` (`log_visit_request.ts`); v1's
			  `Android/14 (REL)` was dropped.
			* `device_type` - v1's rule, a LARGE screen or bigger is a tablet, so segments do not move.
		 **/
		fun deviceContext(context: Context): DeviceContext
		{	val locales = LocaleListCompat.getAdjustedDefault()
			val languages = (0 until minOf(3, locales.size())).mapNotNull { locales[it]?.toLanguageTag() }.joinToString(",")
			val metrics = Resources.getSystem().displayMetrics
			val version = RE_VERSION.find(Build.VERSION.RELEASE ?: "")?.value?.trimEnd('.') ?: "0"
			val large = (context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
			return DeviceContext(
				timeZone = TimeZone.getDefault().getOffset(System.currentTimeMillis())/3_600_000.0,
				languages = languages,
				screen = "${metrics.widthPixels}x${metrics.heightPixels}",
				os = "Android/$version",
				deviceType = if (large) "tablet" else "phone"
			)
		}
	}
}
