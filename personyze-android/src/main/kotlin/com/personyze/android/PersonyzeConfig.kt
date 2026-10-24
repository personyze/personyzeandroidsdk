package com.personyze.android

import androidx.annotation.DrawableRes
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**	How the SDK talks to Personyze. Built with [PersonyzeConfig]:

	```
	Personyze.initialize(this, PersonyzeConfig("YOUR_API_KEY") { notifications = true })
	```

	The API key is a secret with account-wide access. It is never logged and never stored by the
	SDK, which is why [Personyze.initialize] must run in `Application.onCreate`: the background
	workers find the key only in a process the app initialized.
 **/
public class PersonyzeConfig private constructor(builder: Builder)
{	internal val apiKey: String = builder.apiKey.trim()

	/**	Where `/rest` lives. Only https is accepted (the key goes in every request). **/
	public val baseUrl: String = builder.baseUrl.trimEnd('/')

	/**	Where Personyze's WebView library and helper scripts are served, and the origin rendered
		actions run under.
	 **/
	public val assetsUrl: String = builder.assetsUrl.trimEnd('/') + "/"

	/**	The budget for one decision - every attempt, the retry included: name lookup, connect, TLS,
		the whole answer. A slower answer is a failure, and the app renders its default.
	 **/
	public val timeout: Duration = builder.timeout

	/**	The budget for what no screen waits for: queued events, templates, notifications. **/
	public val backgroundTimeout: Duration = builder.backgroundTimeout

	/**	0 or 1. One retry, only for a connection failure, a timeout or a 5xx, and only inside the budget. **/
	public val retries: Int = builder.retries

	public val notifications: Boolean = builder.notifications
	public val notificationChannelName: String = builder.notificationChannelName
	@DrawableRes public val notificationSmallIcon: Int = builder.notificationSmallIcon
	public val autoFlushDelay: Duration? = builder.autoFlushDelay
	public val storageName: String = builder.storageName
	public val okHttpClient: OkHttpClient? = builder.okHttpClient
	public val onError: PersonyzeErrorListener? = builder.onError

	init
	{	require(apiKey.isNotEmpty()) { "Personyze: the API key is empty" }
		require(baseUrl.startsWith("https://")) { "Personyze: baseUrl must be https:// - the API key is sent with every request (got $baseUrl)" }
		require(assetsUrl.startsWith("https://")) { "Personyze: assetsUrl must be https:// (got $assetsUrl)" }
		require(timeout.isPositive() && backgroundTimeout.isPositive()) { "Personyze: timeouts must be positive" }
		require(retries in 0..1) { "Personyze: retries must be 0 or 1" }
		require(STORAGE_NAME.matches(storageName)) { "Personyze: storageName may hold letters, digits, '-' and '_' only" }
	}

	public class Builder internal constructor(internal val apiKey: String)
	{	public var baseUrl: String = DEFAULT_BASE_URL
		public var assetsUrl: String = DEFAULT_ASSETS_URL
		public var timeout: Duration = 5.seconds
		public var backgroundTimeout: Duration = 10.seconds
		public var retries: Int = 1
		/**	Receive notifications from "Web Push Send" actions: a check every 15 minutes, in the
			background too. The app must declare `POST_NOTIFICATIONS` and ask for it on Android 13+.
		 **/
		public var notifications: Boolean = false
		/**	The notification channel's name in the system settings. **/
		public var notificationChannelName: String = "Recommendations"
		/**	The status-bar icon when the message has no badge. 0 = the SDK's own. **/
		@DrawableRes public var notificationSmallIcon: Int = 0
		/**	How long queued events wait for more before going out by themselves; null = only with
			the next navigation, [PersonyzeApi.flush] or the app going to the background.
		 **/
		public var autoFlushDelay: Duration? = 3.seconds
		/**	Keeps a separate visitor and state under this name - a second account, or a test. **/
		public var storageName: String = "default"
		/**	A client of your own (proxy, certificate pinning). The SDK derives its own from it and
			never adds interceptors; one that logs headers would log the API key.
		 **/
		public var okHttpClient: OkHttpClient? = null
		/**	Every failure the SDK absorbs. **/
		public var onError: PersonyzeErrorListener? = null

		public fun build(): PersonyzeConfig = PersonyzeConfig(this)
	}

	/**	Same settings, so initializing again changes nothing. [onError] is not compared. **/
	internal fun sameAs(other: PersonyzeConfig): Boolean =
		apiKey == other.apiKey && baseUrl == other.baseUrl && assetsUrl == other.assetsUrl && timeout == other.timeout &&
		backgroundTimeout == other.backgroundTimeout && retries == other.retries && notifications == other.notifications &&
		notificationChannelName == other.notificationChannelName && notificationSmallIcon == other.notificationSmallIcon &&
		autoFlushDelay == other.autoFlushDelay && storageName == other.storageName && okHttpClient === other.okHttpClient

	override fun toString(): String = "PersonyzeConfig(apiKey=***, baseUrl=$baseUrl, timeout=$timeout, notifications=$notifications, storageName=$storageName)"

	public companion object
	{	public const val DEFAULT_BASE_URL: String = "https://app.personyze.com"
		public const val DEFAULT_ASSETS_URL: String = "https://counter.personyze.com/"
		private val STORAGE_NAME = Regex("^[A-Za-z0-9_-]{1,64}$")
	}
}

/**	Build a [PersonyzeConfig]. **/
public fun PersonyzeConfig(apiKey: String, configure: PersonyzeConfig.Builder.() -> Unit = {}): PersonyzeConfig =
	PersonyzeConfig.Builder(apiKey).apply(configure).build()
