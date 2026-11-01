package com.personyze.android

/**	A notification a "Web Push Send" action queued for this visitor. The SDK shows it itself; this
	is what [PersonyzeApi.checkNotifications] answers, for logging or a UI of your own.
 **/
public class PersonyzeNotification internal constructor
(	public val messageId: Long,
	public val title: String,
	public val body: String,
	/**	Where a tap on the notification goes (the message's `tag`), or empty. **/
	public val url: String,
	public val imageUrl: String,
	public val iconUrl: String,
	public val badgeUrl: String,
	public val buttons: List<Button>,
	public val silent: Boolean,
	public val renotify: Boolean,
	/**	Vibration pattern in milliseconds. **/
	public val vibrate: List<Long>,
	/**	Where the SDK reports the notification shown (the server's `shown_url`), or empty: nothing to
		report with.
	 **/
	internal val shownUrl: String = "",
	/**	Where the SDK reports a tap on it or one of its buttons (`click_url`), or empty. **/
	internal val clickUrl: String = ""
)
{	public class Button internal constructor
	(	public val title: String,
		public val iconUrl: String,
		/**	The address the button opens. **/
		public val url: String
	)
	{	override fun toString(): String = "Button(title=$title, url=$url)"
	}

	override fun toString(): String = "PersonyzeNotification(messageId=$messageId, title=$title)"
}
