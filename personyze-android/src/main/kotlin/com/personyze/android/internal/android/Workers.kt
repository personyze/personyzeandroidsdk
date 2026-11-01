package com.personyze.android.internal.android

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personyze.android.Personyze

/**	Every 15 minutes: show the visitor's queued notification, if any.

	The API key is never stored, so this works only in a process where the app called
	`Personyze.initialize` - which `Application.onCreate` does before WorkManager runs anything. Without
	it, it does nothing (v1 polled from the main thread and silently did nothing then too).
 **/
internal class PersonyzeNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params)
{	override suspend fun doWork(): Result
	{	val client = Personyze.clientOrNull()
		if (client == null)
		{	Log.w(AndroidPlatform.TAG, "Personyze: the notification check ran before Personyze.initialize(); call it in Application.onCreate()")
			return Result.success()
		}
		if (!client.notificationsEnabled)
		{	WorkScheduling.scheduleNotifications(applicationContext, false, 0)
			return Result.success()
		}
		client.checkNotifications(force = false)
		return Result.success()
	}
}

/**	Sends events queued when the app left the foreground or the network was gone. **/
internal class PersonyzeOutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params)
{	override suspend fun doWork(): Result
	{	val client = Personyze.clientOrNull() ?: return Result.success()
		return if (client.flushInBackground()) Result.success() else Result.retry()
	}
}
