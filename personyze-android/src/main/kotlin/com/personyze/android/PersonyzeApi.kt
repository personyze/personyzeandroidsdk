package com.personyze.android

import kotlinx.coroutines.flow.StateFlow

/**	What the visitor did. Each call queues one command; the queue goes out with the next
	[PersonyzeApi.navigate], or by itself a few seconds later, or when the app goes to the
	background. None of these block or touch the network, and all are safe from any thread.

	A blank id, or a promotion without its action id, is not sent: the call is ignored and
	[PersonyzeConfig.Builder.onError] gets a [PersonyzeException.Invalid]. Personyze would refuse it
	(`No Product ID specified`), and with it every other event in the same request.
 **/
public sealed interface PersonyzeEvents
{	/**	Profile data about the visitor - `email`, `first_name`, or any field of your own -
		for targeting, content and email campaigns.
	 **/
	public fun setProfile(field: String, value: String?)

	/**	Several profile fields in one command. **/
	public fun setProfile(fields: Map<String, String?>)

	/**	The visitor viewed a product. [productId] is the "Internal ID" in your products catalog.
		[actionId] attributes it to the action that showed it (recommendation contribution).
	 **/
	public fun productViewed(productId: String, actionId: Int? = null)

	public fun productAddedToCart(productId: String, actionId: Int? = null, quantity: Int? = null)

	/**	Added to a wishlist or favorites. **/
	public fun productLiked(productId: String, actionId: Int? = null)

	/**	A purchase. With [actionId] it counts for the "Products purchased" criterion of the
		action's A/B test (SPEC section 6) - without it a purchase-judged test sees nothing.
	 **/
	public fun productPurchased(productId: String, actionId: Int? = null, quantity: Int? = null)

	/**	Removed from the wishlist. No effect for a product not reported liked. **/
	public fun productUnliked(productId: String, actionId: Int? = null)

	/**	No effect for a product not reported added to cart. **/
	public fun productRemovedFromCart(productId: String, actionId: Int? = null, quantity: Int? = null)

	/**	Everything reported added to cart was purchased. **/
	public fun productsPurchased()

	/**	The wishlist was emptied. **/
	public fun productsUnliked()

	/**	The cart was emptied. **/
	public fun productsRemovedFromCart()

	/**	[articleId] is the "Internal ID" in your articles catalog. **/
	public fun articleViewed(articleId: String, actionId: Int? = null)

	public fun articleLiked(articleId: String, actionId: Int? = null)

	public fun articleCommented(articleId: String, actionId: Int? = null)

	public fun articleUnliked(articleId: String, actionId: Int? = null)

	/**	The visitor reached the article's goal. **/
	public fun articleGoal(articleId: String, actionId: Int? = null)

	public fun articlesUnliked()

	/**	A promotion card of a promotions action came into view. [promotionId] is the card's
		`data-personyze-click-target="promotions <id>"`; [actionId] is required.
	 **/
	public fun promotionViewed(promotionId: String, actionId: Int)

	public fun promotionClicked(promotionId: String, actionId: Int)

	/**	The visitor revealed the promotion's coupon code. **/
	public fun codeRevealed(promotionId: String, actionId: Int)

	/**	A coupon code was redeemed in the app. **/
	public fun couponUsed(code: String)

	/**	Any tracker command, sent as given - for what the typed calls do not cover. Sent in a
		request of its own, so a command Personyze refuses cannot take other events with it. An
		empty command is ignored.
	 **/
	public fun track(command: List<String>)
}

/**	The Personyze client: decisions, events, action reports, the visitor, notifications.
	[Personyze] forwards every member to the client it was initialized with.
 **/
public sealed interface PersonyzeApi : PersonyzeEvents
{	/**	The current decision; updated by every response and restored when the app starts. **/
	public val result: StateFlow<PersonyzeResult>

	/**	The current visitor and session. **/
	public val visitor: StateFlow<PersonyzeVisitor>

	/**	Where push stands on this install: the token passed on, the visitor it is registered for, whether Personyze
		pushes to it ([PersonyzePushStatus] says what each state means).
	 **/
	public val pushStatus: StateFlow<PersonyzePushStatus>

	/**	The visitor opened a screen ("document"): log it and get what to present there, in one
		round trip. [events] run first and go in the same request, attributed to this screen:

		```
		val result = Personyze.navigate("Product") { productViewed("A1000") }
		```

		Never throws for a network or server problem: the answer is then empty with
		[PersonyzeResult.error] set. Call it once per screen entry, not on every recomposition or
		configuration change - each call is a page view.
	 **/
	public suspend fun navigate(document: String, events: (PersonyzeEvents.() -> Unit)? = null): PersonyzeResult

	/**	Send what is queued and answer the current decision, merged with what the response
		changed. With nothing queued it answers the current decision without a request.
	 **/
	public suspend fun refresh(): PersonyzeResult

	/**	Send what is queued now instead of waiting for the automatic send. **/
	public suspend fun flush()

	/**	Identify the visitor with your own user id (after login). Personyze files them under it from
		the next request on; the anonymous visitor's session is not carried over (SPEC section 3).
		Null logs out, back to this install's anonymous visitor.
	 **/
	public fun setUserId(userId: String?)

	public fun setUserId(userId: Long)

	/**	Start a new Personyze session on the next request. A session otherwise ends 90 minutes
		after it started.
	 **/
	public fun startNewSession()

	/**	Forget the cached action and campaign templates and load them again. Normally never needed. **/
	public suspend fun clearCache()

	/**	A new anonymous visitor, and everything stored for the old one deleted: session, queued
		events, decision, history. For a privacy request or a test.
	 **/
	public suspend fun reset()

	/**	The action was shown (`executed`). An action never reported executed looks like it never
		ran, and its A/B arm like it had no traffic.
	 **/
	public fun reportExecuted(actionId: Int)

	/**	The visitor clicked the action's call to action (`target`) - the Clicks criterion of an A/B
		test, and only that. A purchase is [productPurchased] with the action id.
	 **/
	public fun reportEngaged(actionId: Int)

	/**	The visitor closed the action; with [dontShowForSessions] > 0 it is not presented again for
		that many sessions.
	 **/
	public fun reportClosed(actionId: Int, dontShowForSessions: Int = 0)

	public fun reportProductClicked(actionId: Int, productId: String)

	public fun reportArticleClicked(actionId: Int, articleId: String)

	/**	The action could not be presented. The message shows in Live visits. **/
	public fun reportError(actionId: Int, message: String)

	/**	Report a click from a renderer of your own, as [PersonyzeActionView] does. **/
	public fun report(click: PersonyzeClick)

	/**	Check for a notification now and show it, ignoring the 15-minute interval. Answers what was
		shown, or null.
	 **/
	public suspend fun checkNotifications(): PersonyzeNotification?

	/**	Turn the notifications on or off (also [PersonyzeConfig.Builder.notifications]): the periodic
		check, and the device's push registration - turned off, Personyze stops pushing to it.
	 **/
	public fun setNotificationsEnabled(enabled: Boolean)

	/**	The token Firebase Cloud Messaging gave the app: pass it on from your
		`FirebaseMessagingService.onNewToken`, and once at start (`FirebaseMessaging.getInstance().token`).
		Personyze then pushes this visitor's notifications to the device at once - when the account
		has its Firebase service account (Integrations › App push in the Personyze panel) - instead of
		waiting for the app's check. The SDK depends on no Firebase library: the app has its own.
	 **/
	public fun setPushToken(token: String)

	/**	A push message, as your `FirebaseMessagingService.onMessageReceived` gets it
		(`remoteMessage.data`): if it is Personyze's, the SDK shows it - as it shows a checked one, with
		its picture and buttons - reports it shown, and answers true; anything else answers false and
		is yours. It waits for the pictures, 15 seconds at most, as `onMessageReceived` may.
	 **/
	public fun handlePushMessage(data: Map<String, String>): Boolean

	/**	Who is winning the A/B test of campaign [conditionId]. **/
	public suspend fun verdict(conditionId: Int): Result<PersonyzeVerdict>
}
