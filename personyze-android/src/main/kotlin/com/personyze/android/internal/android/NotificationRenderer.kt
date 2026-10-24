package com.personyze.android.internal.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.personyze.android.PersonyzeNotification
import com.personyze.android.R

/**	Shows a Personyze notification the way v1 did - with its title, which v1 dropped
	(`setContentText(title).setContentText(body)`).
 **/
internal object NotificationRenderer
{	/**	v1's channel id, kept: a user who muted Personyze's channel stays muted. **/
	const val CHANNEL_ID = "Personyze Notifications"
	const val TAG = "personyze"
	/**	v1's id: one Personyze notification at a time, a new one replacing the last. **/
	const val ID = 1839852125

	/**	Allowed by the user: notifications on (on Android 13+ that is the `POST_NOTIFICATIONS`
		grant) and the channel not turned off.
	 **/
	fun canShow(context: Context): Boolean
	{	if (!NotificationManagerCompat.from(context).areNotificationsEnabled())
		{	return false
		}
		if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
		{	return false
		}
		if (Build.VERSION.SDK_INT >= 26)
		{	val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_ID)
			if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE)
			{	return false
			}
		}
		return true
	}

	@SuppressLint("InlinedApi")	// a string constant, compiled in; reading the manifest works on every version
	fun declaresPermission(context: Context): Boolean =
		try
		{	context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
		}
		catch (e: Exception)
		{	true
		}

	private fun ensureChannel(context: Context, name: String)
	{	if (Build.VERSION.SDK_INT >= 26)
		{	val nm = context.getSystemService(NotificationManager::class.java) ?: return
			if (nm.getNotificationChannel(CHANNEL_ID) == null)
			{	nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT))
			}
		}
	}

	/**	Answers whether it was posted. **/
	@SuppressLint("MissingPermission")	// canShow() checks it
	suspend fun show(context: Context, n: PersonyzeNotification, images: ImageLoader, channelName: String, smallIcon: Int): Boolean
	{	if (!canShow(context))
		{	return false
		}
		ensureChannel(context, channelName)
		val wants = listOf(
			n.badgeUrl.takeIf { it.isNotEmpty() }?.let { ImageLoader.Want(it, 96) },
			n.iconUrl.takeIf { it.isNotEmpty() }?.let { ImageLoader.Want(it, 192) },
			n.imageUrl.takeIf { it.isNotEmpty() }?.let { ImageLoader.Want(it, 720) }
		) + n.buttons.map { b -> b.iconUrl.takeIf { it.isNotEmpty() }?.let { ImageLoader.Want(it, 96) } }
		val bitmaps = images.loadAll(wants, 10_000)
		val badge = bitmaps[0]
		val icon = bitmaps[1]
		val picture = bitmaps[2]
		val builder = NotificationCompat.Builder(context, CHANNEL_ID)
			.setContentTitle(n.title)
			.setContentText(n.body)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
			.setAutoCancel(true)
			.setSilent(n.silent)
			.setOnlyAlertOnce(!n.renotify)
		// A badge is drawn as the status-bar icon; the colored icon would be a white blob there.
		if (badge != null)
		{	builder.setSmallIcon(IconCompat.createWithBitmap(badge))
		}
		else
		{	builder.setSmallIcon(if (smallIcon != 0) smallIcon else R.drawable.ic_stat_personyze)
		}
		if (icon != null)
		{	builder.setLargeIcon(icon)
		}
		if (picture != null)
		{	builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(n.body))
		}
		else
		{	builder.setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
		}
		if (n.vibrate.isNotEmpty())
		{	builder.setVibrate(n.vibrate.toLongArray())
		}
		builder.setContentIntent(viewIntent(context, n.url, n.messageId, 0) ?: launchIntent(context, n.messageId))
		n.buttons.forEachIndexed { i, button ->
			val intent = viewIntent(context, button.url, n.messageId, i + 1) ?: return@forEachIndexed
			val buttonIcon = bitmaps.getOrNull(3 + i)
			val action = if (buttonIcon != null)
				NotificationCompat.Action.Builder(IconCompat.createWithBitmap(buttonIcon), button.title, intent).build()
				else NotificationCompat.Action.Builder(0, button.title, intent).build()
			builder.addAction(action)
		}
		return try
		{	NotificationManagerCompat.from(context).notify(TAG, ID, builder.build())
			true
		}
		catch (e: RuntimeException)
		{	Log.w(AndroidPlatform.TAG, "Personyze: could not post the notification: $e")
			false
		}
	}

	/**	A request code per message and slot: v1 used 0 for all, so two buttons opening the same
		address shared one PendingIntent and its ONE_SHOT flag spent the other's.
	 **/
	private fun requestCode(messageId: Long, slot: Int): Int = messageId.hashCode()*4 + slot

	private fun viewIntent(context: Context, url: String, messageId: Long, slot: Int): PendingIntent?
	{	val uri = url.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: return null
		val scheme = uri.scheme?.lowercase() ?: return null
		if (scheme == "file" || scheme == "content" || scheme == "javascript")
		{	return null
		}
		val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		return PendingIntent.getActivity(context, requestCode(messageId, slot), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
	}

	private fun launchIntent(context: Context, messageId: Long): PendingIntent?
	{	val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
		return PendingIntent.getActivity(context, requestCode(messageId, 0), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
	}
}
