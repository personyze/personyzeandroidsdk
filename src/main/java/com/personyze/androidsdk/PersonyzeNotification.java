package com.personyze.androidsdk;

import java.util.Objects;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.personyze.personyzeandroidsdk.R;

public class PersonyzeNotification
{	public long messageId;
	public @NonNull String title;
	public @NonNull String body;
	public @NonNull String badge;
	public @NonNull String icon;
	public @NonNull String image;
	public @NonNull String tag; // is used for default action
	public @NonNull long[] vibrate;
	public boolean silent;
	public boolean renotify;
	public @NonNull Action[] actions;

	static class Action
	{	public @NonNull String title;
		public @NonNull String icon;
		public @NonNull String action;

		Action(@NonNull String title, @NonNull String icon, @NonNull String action)
		{	this.title = title;
			this.icon = icon;
			this.action = action;
		}
	}

	PersonyzeNotification(String json) throws Exception
	{	JSONObject object = (JSONObject)new JSONTokener(json).nextValue();
		messageId = object.getLong("message_id");
		title = object.getString("title").trim();
		body = object.getString("body").trim();
		badge = object.getString("badge");
		icon = object.getString("icon");
		image = object.getString("image");
		tag = object.getString("tag");
		vibrate = parseVibrate(object.getString("vibrate"));
		silent = object.getBoolean("silent");
		renotify = object.getBoolean("renotify");
		JSONArray rActions = object.getJSONArray("actions");
		actions = new Action[rActions.length()];
		for (int i=0, iEnd=actions.length; i<iEnd; i++)
		{	JSONObject rAction = rActions.getJSONObject(i);
			String actionTitle = rAction.isNull("title") ? "" : rAction.getString("title");
			String actionIcon = rAction.isNull("icon") ? "" : rAction.getString("icon");
			String actionAction = rAction.isNull("action") ? "" : rAction.getString("action");
			actions[i] = new Action(actionTitle, actionIcon, actionAction);
		}
		if (title.length()==0 || body.length()==0)
		{	throw new PersonyzeError("Notification lacks required fields");
		}
	}

	private long[] parseVibrate(String vibrate)
	{	if (vibrate.isEmpty())
		{	return new long[0];
		}
		String[] array = vibrate.split(",");
		long[] result = new long[array.length];
		for (int i=0; i<array.length; i++)
		{	result[i] = Long.parseLong(array[i]);
		}
		return result;
	}

	Task<Notification> toNotification(Context context)
	{	final Task<Bitmap> badgeTask = hrefToBitmapOrNull(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M ? badge : null);
		final Task<Bitmap> iconTask = hrefToBitmapOrNull(icon);
		final Task<Bitmap> imageTask = hrefToBitmapOrNull(image);
		final Task<Bitmap> action0Task = hrefToBitmapOrNull(actions.length>0 && !actions[0].title.isEmpty() && !actions[0].action.isEmpty() ? actions[0].icon : null);
		final Task<Bitmap> action1Task = hrefToBitmapOrNull(actions.length>1 && !actions[1].title.isEmpty() && !actions[1].action.isEmpty() ? actions[1].icon : null);
		final Task<Bitmap> action2Task = hrefToBitmapOrNull(actions.length>2 && !actions[2].title.isEmpty() && !actions[2].action.isEmpty() ? actions[2].icon : null);
		final Task<Bitmap> task = badgeTask.continueWithTask(t0 -> iconTask.continueWithTask(t1 -> imageTask.continueWithTask(t2 -> action0Task.continueWithTask(t3 -> action1Task.continueWithTask(t4 -> action2Task)))));
		return task.continueWith
		(	t5 ->
			{	Bitmap badgeBitmap = badgeTask.getResult();
				Bitmap iconBitmap = iconTask.getResult();
				Bitmap imageBitmap = imageTask.getResult();
				NotificationCompat.Builder builder =
				(	new NotificationCompat.Builder(context, PersonyzeTracker.NOTI_CHANNEL_ID)
					.setPriority(NotificationCompat.PRIORITY_DEFAULT)
					.setAutoCancel(true)
					.setContentText(title)
					.setContentText(body)
					.setSilent(silent)
					.setOnlyAlertOnce(!renotify)
					.setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
				);
				// badgeBitmap
				if (badgeBitmap == null)
				{	badgeBitmap = iconBitmap;
				}
				if (badgeBitmap!=null && Build.VERSION.SDK_INT>=Build.VERSION_CODES.M)
				{	builder.setSmallIcon(IconCompat.createWithBitmap(badgeBitmap));
				}
				else
				{	builder.setSmallIcon(R.drawable.ic_stat_name);
				}
				// iconBitmap
				if (iconBitmap != null)
				{	builder.setLargeIcon(iconBitmap);
				}
				// imageBitmap
				if (imageBitmap != null)
				{	builder.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(imageBitmap));
				}
				// vibrate
				if (vibrate.length != 0)
				{	builder.setVibrate(vibrate);
				}
				// tag (is used for default action)
				if (!tag.isEmpty())
				{	builder.setContentIntent(browserViewIntent(context, tag));
					builder.setAutoCancel(true);
				}
				// actions
				for (int i=0; i<actions.length; i++)
				{	Action action = actions[i];
					if (!action.title.isEmpty() && !action.action.isEmpty())
					{	Bitmap icon = i==0 ? action0Task.getResult() : i==1 ? action1Task.getResult() : i==2 ? action2Task.getResult() : null;
						builder.addAction
						(	icon == null ?
								new NotificationCompat.Action.Builder(R.drawable.ic_stat_name, action.title, browserViewIntent(context, action.action)).build() :
								new NotificationCompat.Action.Builder(IconCompat.createWithBitmap(icon), action.title, browserViewIntent(context, action.action)).build()
						);
					}
				}
				// done
				return builder.build();
			}
		);
	}

	private Task<Bitmap> hrefToBitmapOrNull(String href)
	{	if (href==null || href.isEmpty())
		{	final TaskCompletionSource<Bitmap> asyncResult = new TaskCompletionSource<>();
			asyncResult.setResult(null);
			return asyncResult.getTask();
		}
		return PersonyzeTracker.inst.http.getBitmap(href).continueWith
		(	task ->
			{	if (task.getException() != null)
				{	Log.e("Personyze", Objects.requireNonNull(task.getException().getLocalizedMessage()));
					return null;
				}
				return task.getResult();
			}
		);
	}

	private PendingIntent browserViewIntent(Context context, String href)
	{	Intent viewIntent = new Intent(Intent.ACTION_VIEW);
		viewIntent.setData(Uri.parse(href));
		viewIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		return PendingIntent.getActivity(context, 0, viewIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
	}
}
