package com.personyze.androidsdk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;

import java.util.Objects;

public class PersonyzeNotificationWorker extends Worker
{	public PersonyzeNotificationWorker(@NonNull Context context, @NonNull WorkerParameters workerParams)
	{	super(context, workerParams);
	}

	@Override @NonNull public Result doWork()
	{	Handler handler = new Handler(Looper.getMainLooper());
		handler.postDelayed
		(	() ->
			{	try
				{	Tasks.await(PersonyzeTracker.inst.checkForNotification(getApplicationContext(), false));
				}
				catch (Exception error)
				{	Log.e("Personyze", Objects.requireNonNull(error.getLocalizedMessage()));
				}
			},
			1000
		);
		return Result.success();
	}
}
