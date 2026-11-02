package com.personyze.android

import android.content.Context
import com.personyze.android.internal.android.AndroidPlatform
import com.personyze.android.internal.android.ImageLoader
import com.personyze.android.internal.android.LegacyMigration
import com.personyze.android.internal.android.NotificationRenderer
import com.personyze.android.internal.android.WorkScheduling
import com.personyze.android.internal.core.Commands
import com.personyze.android.internal.core.Engine
import com.personyze.android.internal.core.EngineConfig
import com.personyze.android.internal.core.FileStore
import com.personyze.android.internal.core.NotificationPoll
import com.personyze.android.internal.core.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**	Marks APIs meant for support and test screens, not for app logic. **/
@RequiresOptIn(level = RequiresOptIn.Level.WARNING, message = "For diagnostics only; the format may change.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class PersonyzeDiagnostics

/**	A Personyze client. Create it with [Personyze.initialize]; one per process and storage name. **/
public class PersonyzeClient internal constructor(context: Context, public val config: PersonyzeConfig) : PersonyzeApi
{	private val appContext: Context = context.applicationContext
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val http: OkHttpClient = config.okHttpClient ?: sharedHttp
	private val images = ImageLoader(http)

	@Volatile internal var notificationsEnabled: Boolean = config.notifications
	/**	The period the background check was last scheduled with, in minutes. **/
	private val scheduledEveryMinutes = AtomicLong(Engine.NOTIFICATION_INTERVAL_MS / 60_000)
	@Volatile internal var errorListener: PersonyzeErrorListener? = config.onError

	internal val engine: Engine

	init
	{	val platform = AndroidPlatform(appContext) { notificationsEnabled }
		engine = Engine(
			config = EngineConfig
			(	apiKey = config.apiKey,
				baseUrl = config.baseUrl,
				assetsUrl = config.assetsUrl,
				timeoutMs = config.timeout.inWholeMilliseconds,
				backgroundTimeoutMs = config.backgroundTimeout.inWholeMilliseconds,
				retries = config.retries,
				autoFlushDelayMs = config.autoFlushDelay?.inWholeMilliseconds,
				userAgent = "personyze-android/${BuildConfig.SDK_VERSION}"
			),
			platform = platform,
			store = FileStore(File(appContext.noBackupFilesDir, "personyze/${config.storageName}")),
			cacheStore = FileStore(File(appContext.cacheDir, "personyze/${config.storageName}")),
			transport = OkHttpTransport(http, platform::isOnline),
			scope = scope,
			io = Dispatchers.IO,
			// v1 kept one state per app; it becomes the default storage's.
			legacy = if (config.storageName == "default") LegacyMigration(appContext) else null,
			onError = { error, context -> errorListener?.onError(error, context) },
			owner = { this }
		)
	}

	override val result: StateFlow<PersonyzeResult>
		get() = engine.result

	override val visitor: StateFlow<PersonyzeVisitor>
		get() = engine.visitor

	override val pushStatus: StateFlow<PersonyzePushStatus>
		get() = engine.push

	override suspend fun navigate(document: String, events: (PersonyzeEvents.() -> Unit)?): PersonyzeResult =
		engine.navigate(document, events?.let { block -> { block(this) } })

	override suspend fun refresh(): PersonyzeResult = engine.refresh()

	override suspend fun flush(): Unit = engine.flush()

	override fun setProfile(field: String, value: String?): Unit = engine.enqueue { Commands.profile(mapOf(field to value)) }

	override fun setProfile(fields: Map<String, String?>): Unit = engine.enqueue { Commands.profile(fields) }

	override fun productViewed(productId: String, actionId: Int?): Unit = engine.enqueue { Commands.product("Product Viewed", productId, actionId, null) }

	override fun productAddedToCart(productId: String, actionId: Int?, quantity: Int?): Unit = engine.enqueue { Commands.product("Product Added to cart", productId, actionId, quantity) }

	override fun productLiked(productId: String, actionId: Int?): Unit = engine.enqueue { Commands.product("Product Liked", productId, actionId, null) }

	override fun productPurchased(productId: String, actionId: Int?, quantity: Int?): Unit = engine.enqueue { Commands.product("Product Purchased", productId, actionId, quantity) }

	override fun productUnliked(productId: String, actionId: Int?): Unit = engine.enqueue { Commands.product("Product Unliked", productId, actionId, null) }

	override fun productRemovedFromCart(productId: String, actionId: Int?, quantity: Int?): Unit = engine.enqueue { Commands.product("Product Removed from cart", productId, actionId, quantity) }

	override fun productsPurchased(): Unit = engine.enqueue { Commands.bare("Products Purchased") }

	override fun productsUnliked(): Unit = engine.enqueue { Commands.bare("Products Unliked") }

	override fun productsRemovedFromCart(): Unit = engine.enqueue { Commands.bare("Products Removed from cart") }

	override fun articleViewed(articleId: String, actionId: Int?): Unit = engine.enqueue { Commands.article("Article Viewed", articleId, actionId) }

	override fun articleLiked(articleId: String, actionId: Int?): Unit = engine.enqueue { Commands.article("Article Liked", articleId, actionId) }

	override fun articleCommented(articleId: String, actionId: Int?): Unit = engine.enqueue { Commands.article("Article Commented", articleId, actionId) }

	override fun articleUnliked(articleId: String, actionId: Int?): Unit = engine.enqueue { Commands.article("Article Unliked", articleId, actionId) }

	override fun articleGoal(articleId: String, actionId: Int?): Unit = engine.enqueue { Commands.article("Article Goal", articleId, actionId) }

	override fun articlesUnliked(): Unit = engine.enqueue { Commands.bare("Articles Unliked") }

	override fun promotionViewed(promotionId: String, actionId: Int): Unit = engine.enqueue { Commands.promotion("promotion viewed", promotionId, actionId) }

	override fun promotionClicked(promotionId: String, actionId: Int): Unit = engine.enqueue { Commands.promotion("promotion clicked", promotionId, actionId) }

	override fun codeRevealed(promotionId: String, actionId: Int): Unit = engine.enqueue { Commands.promotion("code revealed", promotionId, actionId) }

	override fun couponUsed(code: String): Unit = engine.enqueue { Commands.coupon(code) }

	override fun track(command: List<String>)
	{	if (command.isNotEmpty())
		{	engine.enqueue(raw = true) { Commands.raw(command) }
		}
	}

	override fun setUserId(userId: String?)
	{	if (userId != null && userId.isBlank())
		{	engine.reportInvalid("Personyze: setUserId() needs a non-blank id, or null to log out; ignored", "setUserId")
		}
		else
		{	engine.setUserId(userId)
		}
	}

	override fun setUserId(userId: Long): Unit = engine.setUserId(userId)

	override fun startNewSession(): Unit = engine.startNewSession()

	override suspend fun clearCache(): Unit = engine.clearCache()

	override suspend fun reset(): Unit = engine.reset()

	override fun reportExecuted(actionId: Int): Unit = engine.enqueue { Commands.actionStatus(actionId, "executed") }

	override fun reportEngaged(actionId: Int): Unit = engine.enqueue { Commands.actionStatus(actionId, "target") }

	override fun reportClosed(actionId: Int, dontShowForSessions: Int): Unit =
		engine.enqueue { Commands.actionStatus(actionId, "close", dontShowForSessions.coerceAtLeast(0).toString()) }

	override fun reportProductClicked(actionId: Int, productId: String): Unit = engine.enqueue {
		require(productId.isNotBlank()) { "Personyze: reportProductClicked() needs a product id" }
		Commands.actionStatus(actionId, "product", productId)
	}

	override fun reportArticleClicked(actionId: Int, articleId: String): Unit = engine.enqueue {
		require(articleId.isNotBlank()) { "Personyze: reportArticleClicked() needs an article id" }
		Commands.actionStatus(actionId, "article", articleId)
	}

	override fun reportError(actionId: Int, message: String): Unit = engine.enqueue { Commands.actionStatus(actionId, "error", message) }

	override fun report(click: PersonyzeClick): Unit = when (click.kind)
	{	PersonyzeClick.Kind.TARGET -> reportEngaged(click.actionId)
		PersonyzeClick.Kind.CLOSE -> reportClosed(click.actionId, click.dontShowForSessions)
		PersonyzeClick.Kind.PRODUCT -> reportProductClicked(click.actionId, click.arg)
		PersonyzeClick.Kind.ARTICLE -> reportArticleClicked(click.actionId, click.arg)
	}

	override suspend fun checkNotifications(): PersonyzeNotification? = checkNotifications(force = true)

	/**	Reports kept from before go first: a tap made offline, a report the process did not live to
		send; and the device's push registration, when it has something new to say. A notification
		posted is reported shown, and taken off the server's queue. [foreground]: the app has just
		come to the front - checked once a minute at most (SPEC section 11).
	 **/
	internal suspend fun checkNotifications(force: Boolean, foreground: Boolean = false): PersonyzeNotification?
	{	engine.sendNotificationReports()
		engine.registerPush()
		rescheduleIfPushChanged()
		if (!NotificationRenderer.canShow(appContext))
		{	return null
		}
		val n = engine.pollNotification(force, foreground) ?: return null
		show(n, checked = true)
		return n
	}

	/**	Draw it; drawn, report it shown - and a [checked] one is taken off the server's queue. A pushed
		one is off it already: the server takes a message off as it pushes it.
	 **/
	private suspend fun show(n: PersonyzeNotification, checked: Boolean)
	{	if (NotificationRenderer.show(appContext, n, images, config.notificationChannelName, config.notificationSmallIcon))
		{	coroutineScope {
				if (checked)
				{	launch { engine.ackNotification(n.messageId) }
				}
				engine.reportNotification(n.shownUrl)
			}
		}
	}

	override fun setPushToken(token: String)
	{	engine.setPushToken(token.trim().ifEmpty { null })
		scope.launch {
			engine.registerPush()
			rescheduleIfPushChanged()
		}
	}

	override fun handlePushMessage(data: Map<String, String>): Boolean
	{	val json = data[PUSH_DATA_KEY] ?: return false
		val n = NotificationPoll.parse(json) ?: return true
		runBlocking {
			withTimeoutOrNull(15_000) {
				if (notificationsEnabled && NotificationRenderer.canShow(appContext) && engine.acceptPushed(n))
				{	show(n, checked = false)
				}
			}
		}
		return true
	}

	/**	The app came to the front: the notifications are checked - once a minute at most. **/
	internal fun onForeground()
	{	if (notificationsEnabled)
		{	scope.launch { checkNotifications(force = false, foreground = true) }
		}
	}

	/**	The background check's period follows the push: 15 minutes, or 6 hours once Personyze pushes. **/
	private fun rescheduleIfPushChanged()
	{	val minutes = engine.backgroundCheckMinutes()
		if (notificationsEnabled && scheduledEveryMinutes.getAndSet(minutes) != minutes)
		{	WorkScheduling.scheduleNotifications(appContext, true, minutes)
		}
	}

	/**	A tap on a notification, from [com.personyze.android.internal.android.NotificationTapActivity]:
		reported while the app goes on to where the tap leads.
	 **/
	internal fun reportNotificationTap(address: String)
	{	scope.launch { engine.reportNotification(address) }
	}

	override fun setNotificationsEnabled(enabled: Boolean)
	{	notificationsEnabled = enabled
		val minutes = engine.backgroundCheckMinutes()
		scheduledEveryMinutes.set(minutes)
		WorkScheduling.scheduleNotifications(appContext, enabled, minutes)
		// Turned off, the device is taken off - Personyze stops pushing to it; turned on, it is registered again.
		scope.launch { engine.registerPush() }
	}

	override suspend fun verdict(conditionId: Int): Result<PersonyzeVerdict> = engine.verdict(conditionId)

	/**	The stored state, redacted: visitor, session fields, queue, cache. **/
	@PersonyzeDiagnostics
	public fun diagnostics(): String = engine.diagnostics()

	/**	The app left the foreground: what is queued goes to WorkManager, which sends it even if the
		process is frozen or killed now.
	 **/
	internal fun onBackground()
	{	if (engine.hasPending())
		{	WorkScheduling.scheduleOutbox(appContext)
		}
	}

	internal suspend fun flushInBackground(): Boolean = engine.flushLive(config.backgroundTimeout.inWholeMilliseconds, "flush")

	internal fun close()
	{	scope.cancel()
	}

	override fun toString(): String = "PersonyzeClient($config)"

	private companion object
	{	/**	One connection pool for every client in the process. **/
		val sharedHttp: OkHttpClient by lazy { OkHttpClient() }
		/**	The data key a Personyze push carries its notification under (app-service mod_app_push payload.ts). **/
		const val PUSH_DATA_KEY = "personyze"
	}
}
