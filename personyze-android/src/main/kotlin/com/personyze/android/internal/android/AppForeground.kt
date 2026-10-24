package com.personyze.android.internal.android

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**	Tells when the app's last activity stops, so queued events are handed to WorkManager before the
	process can be frozen or killed. A flush started in the process at that moment could be frozen
	mid-request (Android 14's cached-app freezer), turning a clean send into one that may or may not
	have arrived.
 **/
internal object AppForeground
{	@Volatile private var installed = false
	@Volatile private var onBackground: () -> Unit = {}
	private var started = 0

	fun install(app: Application, callback: () -> Unit)
	{	onBackground = callback
		if (installed)
		{	return
		}
		installed = true
		app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks
		{	override fun onActivityStarted(activity: Activity)
			{	started++
			}

			override fun onActivityStopped(activity: Activity)
			{	started = (started - 1).coerceAtLeast(0)
				if (started == 0 && !activity.isChangingConfigurations)
				{	onBackground()
				}
			}

			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
			override fun onActivityResumed(activity: Activity) {}
			override fun onActivityPaused(activity: Activity) {}
			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
			override fun onActivityDestroyed(activity: Activity) {}
		})
	}
}
