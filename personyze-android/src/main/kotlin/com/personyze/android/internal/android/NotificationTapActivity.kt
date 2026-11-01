package com.personyze.android.internal.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.personyze.android.Personyze

/**	Where a tap on a Personyze notification goes first when the tap has a report to make (SPEC
	section 11): it hands the report to the client, opens what the tap opens - the address, or the
	app - and closes without being drawn (translucent, no history, not in recents).

	An activity, not a broadcast receiver or a service: since Android 12 neither may start an
	activity from a notification tap (the "trampoline" restriction), and an activity of the app's may.
	Not exported: only the SDK's own PendingIntents reach it. A posted notification holds its name, so
	never rename it.

	Not reported before `Personyze.initialize` (which `Application.onCreate` calls, before this runs):
	until then the app has not said that anything may be sent.
 **/
internal class NotificationTapActivity : Activity()
{	override fun onCreate(savedInstanceState: Bundle?)
	{	super.onCreate(savedInstanceState)
		try
		{	val report = intent.getStringExtra(EXTRA_REPORT).orEmpty()
			if (report.isNotEmpty())
			{	val client = Personyze.clientOrNull()
				if (client == null)
				{	Log.w(AndroidPlatform.TAG, "Personyze: a notification was tapped before Personyze.initialize(); the tap is not reported")
				}
				else
				{	client.reportNotificationTap(report)
				}
			}
			open(intent.getStringExtra(EXTRA_TARGET).orEmpty())
		}
		finally
		{	finish()
		}
	}

	private fun open(target: String)
	{	val next = if (target.isNotEmpty()) Intent(Intent.ACTION_VIEW, Uri.parse(target)) else packageManager.getLaunchIntentForPackage(packageName)
		if (next == null)
		{	return
		}
		try
		{	startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
		}
		catch (e: ActivityNotFoundException)
		{	Log.w(AndroidPlatform.TAG, "Personyze: nothing opens the notification's address: $e")
		}
	}

	companion object
	{	/**	The address to open, or empty for the app. Checked before it was put here. **/
		const val EXTRA_TARGET = "com.personyze.android.notification.target"
		/**	The tap's report address (the server's `click_url`). **/
		const val EXTRA_REPORT = "com.personyze.android.notification.report"
	}
}
