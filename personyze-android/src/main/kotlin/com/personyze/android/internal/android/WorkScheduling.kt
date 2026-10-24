package com.personyze.android.internal.android

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**	The SDK's WorkManager jobs. WorkManager stores the worker class names: never rename
	[PersonyzeNotificationWorker] or [PersonyzeOutboxWorker].
 **/
internal object WorkScheduling
{	const val NOTIFICATIONS = "personyze-notifications-v2"
	const val OUTBOX = "personyze-outbox"
	/**	v1's job. Its stored class no longer exists, and under KEEP it would hold the slot forever. **/
	const val LEGACY = "Personyze Notification Worker"

	private val CONNECTED = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

	/**	Null when the app removed WorkManager's initializer and provides no configuration. **/
	private fun workManager(context: Context): WorkManager? =
		try
		{	WorkManager.getInstance(context.applicationContext)
		}
		catch (e: IllegalStateException)
		{	Log.w(AndroidPlatform.TAG, "Personyze: WorkManager is not initialized; background sending and notifications are off ($e)")
			null
		}

	fun scheduleNotifications(context: Context, enabled: Boolean)
	{	val wm = workManager(context) ?: return
		if (enabled)
		{	val request = PeriodicWorkRequestBuilder<PersonyzeNotificationWorker>(15, TimeUnit.MINUTES).setConstraints(CONNECTED).build()
			wm.enqueueUniquePeriodicWork(NOTIFICATIONS, ExistingPeriodicWorkPolicy.UPDATE, request)
		}
		else
		{	wm.cancelUniqueWork(NOTIFICATIONS)
		}
	}

	fun scheduleOutbox(context: Context)
	{	val wm = workManager(context) ?: return
		val request = OneTimeWorkRequestBuilder<PersonyzeOutboxWorker>()
			.setConstraints(CONNECTED)
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
			.build()
		wm.enqueueUniqueWork(OUTBOX, ExistingWorkPolicy.KEEP, request)
	}

	fun cancelLegacy(context: Context)
	{	workManager(context)?.cancelUniqueWork(LEGACY)
	}
}
