package com.personyze.android

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.personyze.android.internal.android.AndroidPlatform
import com.personyze.android.internal.android.AppForeground
import com.personyze.android.internal.android.NotificationRenderer
import com.personyze.android.internal.android.WorkScheduling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**	The SDK's entry point. Initialize once, in `Application.onCreate`:

	```
	class App : Application()
	{	override fun onCreate()
		{	super.onCreate()
			Personyze.initialize(this, PersonyzeConfig("YOUR_API_KEY"))
		}
	}
	```

	then, on each screen:

	```
	val result = Personyze.navigate("ProductScreen") { productViewed("A1000") }
	```

	Every member forwards to [client]. Called before [initialize], the event and report calls are
	ignored (and logged) and the suspend ones answer an empty result; only [client] throws.
 **/
public object Personyze : PersonyzeApi
{	@Volatile private var current: PersonyzeClient? = null

	/**	Create the client, or answer the existing one when [config] is the same. Another config
		(another key, say) replaces it. Never touches the disk on this thread.
	 **/
	public fun initialize(context: Context, config: PersonyzeConfig): PersonyzeClient
	{	synchronized(this) {
			current?.let { existing ->
				if (existing.config.sameAs(config))
				{	existing.errorListener = config.onError
					return existing
				}
				existing.close()
			}
			val client = PersonyzeClient(context, config)
			current = client
			val app = context.applicationContext as? Application
			if (app != null)
			{	AppForeground.install(app, onForeground = { current?.onForeground() }, onBackground = { current?.onBackground() })
			}
			WorkScheduling.cancelLegacy(context)
			WorkScheduling.scheduleNotifications(context, config.notifications, client.engine.backgroundCheckMinutes())
			if (config.notifications && Build.VERSION.SDK_INT >= 33 && !NotificationRenderer.declaresPermission(context))
			{	Log.w(AndroidPlatform.TAG, "Personyze: notifications = true, but the app's manifest does not declare android.permission.POST_NOTIFICATIONS - none can be shown on Android 13+")
			}
			return client
		}
	}

	public val isInitialized: Boolean
		get() = current != null

	/**	The client [initialize] created. **/
	public val client: PersonyzeClient
		get() = current ?: throw IllegalStateException("Personyze: call Personyze.initialize(context, PersonyzeConfig(apiKey)) in Application.onCreate() first")

	internal fun clientOrNull(): PersonyzeClient? = current

	/**	Tests only: forget the client. **/
	internal fun shutdown()
	{	synchronized(this) {
			current?.close()
			current = null
		}
	}

	private inline fun events(call: String, block: (PersonyzeClient) -> Unit)
	{	val c = current
		if (c == null)
		{	Log.e(AndroidPlatform.TAG, "Personyze.$call ignored: Personyze.initialize() has not been called")
		}
		else
		{	block(c)
		}
	}

	private fun notInitialized(): PersonyzeResult =
		PersonyzeResult.failed(PersonyzeException.Invalid("Personyze.initialize() has not been called"))

	private val emptyResult = MutableStateFlow(PersonyzeResult.EMPTY)
	private val emptyVisitor = MutableStateFlow(PersonyzeVisitor(null, null, null))

	override val result: StateFlow<PersonyzeResult>
		get() = current?.result ?: emptyResult

	override val visitor: StateFlow<PersonyzeVisitor>
		get() = current?.visitor ?: emptyVisitor

	override suspend fun navigate(document: String, events: (PersonyzeEvents.() -> Unit)?): PersonyzeResult = current?.navigate(document, events) ?: notInitialized()
	override suspend fun refresh(): PersonyzeResult = current?.refresh() ?: notInitialized()
	override suspend fun flush() { current?.flush() }
	override fun setProfile(field: String, value: String?): Unit = events("setProfile") { it.setProfile(field, value) }
	override fun setProfile(fields: Map<String, String?>): Unit = events("setProfile") { it.setProfile(fields) }
	override fun productViewed(productId: String, actionId: Int?): Unit = events("productViewed") { it.productViewed(productId, actionId) }
	override fun productAddedToCart(productId: String, actionId: Int?, quantity: Int?): Unit = events("productAddedToCart") { it.productAddedToCart(productId, actionId, quantity) }
	override fun productLiked(productId: String, actionId: Int?): Unit = events("productLiked") { it.productLiked(productId, actionId) }
	override fun productPurchased(productId: String, actionId: Int?, quantity: Int?): Unit = events("productPurchased") { it.productPurchased(productId, actionId, quantity) }
	override fun productUnliked(productId: String, actionId: Int?): Unit = events("productUnliked") { it.productUnliked(productId, actionId) }
	override fun productRemovedFromCart(productId: String, actionId: Int?, quantity: Int?): Unit = events("productRemovedFromCart") { it.productRemovedFromCart(productId, actionId, quantity) }
	override fun productsPurchased(): Unit = events("productsPurchased") { it.productsPurchased() }
	override fun productsUnliked(): Unit = events("productsUnliked") { it.productsUnliked() }
	override fun productsRemovedFromCart(): Unit = events("productsRemovedFromCart") { it.productsRemovedFromCart() }
	override fun articleViewed(articleId: String, actionId: Int?): Unit = events("articleViewed") { it.articleViewed(articleId, actionId) }
	override fun articleLiked(articleId: String, actionId: Int?): Unit = events("articleLiked") { it.articleLiked(articleId, actionId) }
	override fun articleCommented(articleId: String, actionId: Int?): Unit = events("articleCommented") { it.articleCommented(articleId, actionId) }
	override fun articleUnliked(articleId: String, actionId: Int?): Unit = events("articleUnliked") { it.articleUnliked(articleId, actionId) }
	override fun articleGoal(articleId: String, actionId: Int?): Unit = events("articleGoal") { it.articleGoal(articleId, actionId) }
	override fun articlesUnliked(): Unit = events("articlesUnliked") { it.articlesUnliked() }
	override fun promotionViewed(promotionId: String, actionId: Int): Unit = events("promotionViewed") { it.promotionViewed(promotionId, actionId) }
	override fun promotionClicked(promotionId: String, actionId: Int): Unit = events("promotionClicked") { it.promotionClicked(promotionId, actionId) }
	override fun codeRevealed(promotionId: String, actionId: Int): Unit = events("codeRevealed") { it.codeRevealed(promotionId, actionId) }
	override fun couponUsed(code: String): Unit = events("couponUsed") { it.couponUsed(code) }
	override fun track(command: List<String>): Unit = events("track") { it.track(command) }
	override fun setUserId(userId: String?): Unit = events("setUserId") { it.setUserId(userId) }
	override fun setUserId(userId: Long): Unit = events("setUserId") { it.setUserId(userId) }
	override fun startNewSession(): Unit = events("startNewSession") { it.startNewSession() }
	override suspend fun clearCache() { current?.clearCache() }
	override suspend fun reset() { current?.reset() }
	override fun reportExecuted(actionId: Int): Unit = events("reportExecuted") { it.reportExecuted(actionId) }
	override fun reportEngaged(actionId: Int): Unit = events("reportEngaged") { it.reportEngaged(actionId) }
	override fun reportClosed(actionId: Int, dontShowForSessions: Int): Unit = events("reportClosed") { it.reportClosed(actionId, dontShowForSessions) }
	override fun reportProductClicked(actionId: Int, productId: String): Unit = events("reportProductClicked") { it.reportProductClicked(actionId, productId) }
	override fun reportArticleClicked(actionId: Int, articleId: String): Unit = events("reportArticleClicked") { it.reportArticleClicked(actionId, articleId) }
	override fun reportError(actionId: Int, message: String): Unit = events("reportError") { it.reportError(actionId, message) }
	override fun report(click: PersonyzeClick): Unit = events("report") { it.report(click) }
	override suspend fun checkNotifications(): PersonyzeNotification? = current?.checkNotifications()
	override fun setNotificationsEnabled(enabled: Boolean): Unit = events("setNotificationsEnabled") { it.setNotificationsEnabled(enabled) }
	override fun setPushToken(token: String): Unit = events("setPushToken") { it.setPushToken(token) }
	/**	False before [initialize] too: a message that arrives then is not shown - `Application.onCreate` initializes first. **/
	override fun handlePushMessage(data: Map<String, String>): Boolean = current?.handlePushMessage(data) ?: false
	override suspend fun verdict(conditionId: Int): Result<PersonyzeVerdict> =
		current?.verdict(conditionId) ?: Result.failure(PersonyzeException.Invalid("Personyze.initialize() has not been called"))
}
