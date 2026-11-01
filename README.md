# Personyze Android SDK 2.0

Personalization and A/B testing for Android apps: tell [Personyze](https://www.personyze.com/) what
the visitor does, get back the campaigns they match and the actions to present, render them, and
receive Personyze notifications.

Kotlin, coroutines, `StateFlow`, Jetpack Compose. Replaces the 1.x Java SDK, which stays on the
[`v1` branch](https://github.com/personyze/personyzeandroidsdk/tree/v1) - every 1.x feature is here, see
[Migrating from 1.x](#migrating-from-1x).

| | |
| --- | --- |
| Artifacts | `com.personyze:personyze-android:2.0.0`, `com.personyze:personyze-android-compose:2.0.0` |
| Android | minSdk 24; the app compiles with compileSdk 35 or later |
| Kotlin | 2.1 or later (built with 2.2) |
| Docs | the [Android SDK guide](https://personyze.com/wiki/mob:android-sdk-guide) and [reference](https://personyze.com/wiki/mob:android-sdk-docs) |
| Protocol | the [tracker endpoint](https://personyze.com/wiki/guide:server-side) the SDK speaks |

## Install

```kotlin
// app/build.gradle.kts
dependencies {
	implementation("com.personyze:personyze-android:2.0.0")
	// only to render actions in Jetpack Compose:
	implementation("com.personyze:personyze-android-compose:2.0.0")
}
```

From Maven Central (published 2026-10-24), which new Android projects already list. An unreleased build of this
repository: `./gradlew publishReleasePublicationToBuildRepository` writes it to `build/repo`, and an app reads it with
`maven(url = "/path/to/personyzeandroidsdk/build/repo")` in its `settings.gradle.kts` repositories.

The SDK declares `INTERNET` and `ACCESS_NETWORK_STATE`. For notifications the app adds
`POST_NOTIFICATIONS` itself ([Notifications](#notifications)).

## Quick start

```kotlin
class App : Application()
{	override fun onCreate()
	{	super.onCreate()
		Personyze.initialize(this, PersonyzeConfig("YOUR_API_KEY"))
	}
}
```

Initialize in `Application.onCreate`, not in an activity: the SDK never stores the key, and its
background work (sending queued events, checking notifications) runs only in a process the app
initialized. The key is in Personyze's Settings > Integrations > Full-featured API.

Then, once each time the visitor opens a screen:

```kotlin
lifecycleScope.launch {
	val result = Personyze.navigate("ProductScreen") {
		productViewed("A1000")	// events of this screen go in the same request
	}
	result.actionsIn("product_banner").firstOrNull()?.let { banner.render(it) }	// a PersonyzeActionView
}
```

`navigate` never throws for a network or server problem: on failure it answers an empty result with
`error` set, and the screen shows its default. One call is one page view - call it on screen entry
(a `ViewModel`'s `init`, `savedInstanceState == null`), not on every recomposition.

## The result

```kotlin
val result: PersonyzeResult = Personyze.result.value	// or collect it
```

| | |
| --- | --- |
| `actions` | what to present, in Personyze's order |
| `conditions` | the campaigns the visitor matches (for logic of your own) |
| `has(id)`, `firstOf(a, b)` | what a server-side A/B test is written against: `if (result.has(88)) variantB() else default()` |
| `actionsIn("name")` | the actions targeted to a placeholder (its name or HTML id in the panel), in `placeholder_order`, at most `units_count_max` |
| `dataFor(id)` | an action's per-visitor values |
| `error`, `failed` | why it is empty, when it is |
| `rawJson` | the response as it came |

`Personyze.result` is a `StateFlow`: every response updates it, and it is restored when the app starts,
so a restarted app shows the last screen's decision without a request. In Compose:
`val result by Personyze.result.collectAsStateWithLifecycle()`.

An action (`PersonyzeAction`) has `id`, `name`, `contentType` (`text/html` for banners and
recommendation widgets, `application/json` for a JSON feed), `content`, `data`, `placeholders`,
`contentJsonArray()` (a JSON feed as rows) and `contentHtmlDocument()` (a page to run the HTML in a
WebView of your own). The decision arrives first and the template a moment later: until then
`isLoaded` is false and `content` null, and the result updates again when it arrives.

## Rendering actions

```kotlin
// Views
val view = PersonyzeActionView(context)
view.render(action)
view.onClick = PersonyzeClickListener { click -> false }	// true = handled by you
// when done
view.destroy()
```

```kotlin
// Compose
PersonyzeActionContent(action, onClick = { click -> false })
```

Both run the action's HTML with Personyze's WebView library, size themselves to the content, and
report for you: `executed` once it has loaded, and each tap - the call to action (`target`), a close
button, a recommended product or article. Then your `onClick`; if it returns false, a link opens in
the browser and a close hides the action. Nothing the content does navigates the WebView away.
A relative link (`href="/"`, written for a web page) has no site behind it in an app: the tap is
reported and reaches `onClick`, but no browser opens.

Rendering it yourself? Report it: `action.reportExecuted()`, `reportEngaged()`, `reportClosed(n)`,
`reportProductClicked(id)`, `reportArticleClicked(id)`, `reportError(message)` - or the same on
`Personyze` with the action id. An action never reported executed looks like it never ran, and its A/B
arm like it had no traffic. `reportEngaged` is the Clicks criterion of an A/B test, and only that; a
purchase is `productPurchased(sku, actionId = id)`.

`reportClosed(id, dontShowForSessions = 3)` keeps the action off the screen for 3 sessions.

## Events

Each queues one command. None blocks; all are safe from any thread. They go out with the next
`navigate`, or by themselves 3 seconds after the last one, or when the app leaves the foreground.

| | |
| --- | --- |
| `setProfile("email", "a@b.c")`, `setProfile(mapOf(...))` | profile data: targeting, content, email campaigns |
| `productViewed(id, actionId?)` | |
| `productAddedToCart(id, actionId?, quantity?)`, `productRemovedFromCart(...)` | |
| `productLiked(id, actionId?)`, `productUnliked(...)` | wishlist |
| `productPurchased(id, actionId?, quantity?)` | with `actionId` it scores the action's A/B test |
| `productsPurchased()`, `productsRemovedFromCart()`, `productsUnliked()` | the whole cart / wishlist |
| `articleViewed/Liked/Commented/Unliked/Goal(id, actionId?)`, `articlesUnliked()` | |
| `promotionViewed/promotionClicked/codeRevealed(promotionId, actionId)`, `couponUsed(code)` | a promotions action drawn by the app |
| `track(listOf("command", ...))` | any tracker command, sent alone |

Ids are the "Internal ID" of your products or articles catalog. A blank id is ignored and reported to
`onError` as `PersonyzeException.Invalid` - Personyze would refuse it, and every other event in the
request with it.

## Visitors and sessions

The SDK gives each install an anonymous visitor. After login, identify the visitor with your own id:

```kotlin
Personyze.setUserId(user.id)	// a String or a Long
Personyze.setUserId(null)		// logout: back to the install's visitor
```

The logged-in visitor gets a session of their own; the anonymous one's is not carried over. A session
lasts 90 minutes from its start; `startNewSession()` starts one now. `Personyze.visitor` is a
`StateFlow` of the visitor and the session. `reset()` makes a new anonymous visitor and deletes
everything stored (a privacy request).

## Notifications

"Web Push Send" actions reach the app as notifications. In the manifest:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
```

ask for it at runtime on Android 13+, and turn them on:

```kotlin
Personyze.initialize(this, PersonyzeConfig("YOUR_API_KEY") { notifications = true })
```

The SDK checks as the app comes to the front (once a minute at most) and every 15 minutes in the
background (WorkManager), and shows what is queued, with its title, text, image, icon and up to three
buttons; a tap opens the message's link. Each message appears once. `checkNotifications()` checks now.
Notifications reach a visitor who matched the campaign's rules once their session has ended -
typically 1.5 hours after they used the app.

Since 2.1 the campaign's statistics count each notification shown and each tap on it or its buttons,
as they count a website's push. A tap passes through a small invisible activity of the SDK's, which
reports it and opens the link; nothing to declare, the SDK's manifest brings it.

### Pushed at once (Firebase)

With the account's Firebase service account uploaded in the Personyze panel (Integrations › App
push), a campaign reaches the phone at once, even with the app closed, instead of at the app's next
check. The app uses Firebase Cloud Messaging itself (the SDK depends on no Firebase library) and
passes two things on - the device's token, and the messages:

```kotlin
class PushService : FirebaseMessagingService()
{	override fun onNewToken(token: String)
	{	Personyze.setPushToken(token)
	}

	override fun onMessageReceived(message: RemoteMessage)
	{	if (Personyze.handlePushMessage(message.data))
		{	return
		}
		// your own messages
	}
}
```

and once at start, after `initialize`:

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener { Personyze.setPushToken(it) }
```

`handlePushMessage` shows the notification as a checked one is shown, reports it shown, and answers
false for a message that is not Personyze's. Firebase Messaging 26 marks `onNewToken` and `getToken()`
deprecated in favour of `register()`, which names the device by its installation id instead; Personyze
sends to the registration token, and those calls still work. The SDK registers the device with Personyze for the
current visitor, again after a login or a logout, and takes it off when the notifications are turned
off or the visitor is reset. Once Personyze pushes to the device, the background check runs every 6
hours instead of 15 minutes; a push that does not go is still shown at the app's next check.

Many Android builds put apps in a battery saver that stops background work; a user may have to allow
it for your app (libraries such as [AutoStarter](https://github.com/judemanutd/AutoStarter) open the
right settings screen).

## When the network fails

* A decision that fails or takes longer than `timeout` (5 s, every attempt included) answers empty,
  with the error; the app renders its default. One retry, only for a connection failure, a timeout or
  a server error, and only inside the budget.
* Events that never left the device (no network) wait on disk and go out when the network is back.
  Events that may have reached Personyze are not sent again - a purchase counted twice is worse than one
  missing - and `onError` gets them.
* An account whose tracking is paused (HTTP 429) is not asked again for 10 minutes.
* Nothing the server answers is thrown. `onError` hears about everything:

```kotlin
PersonyzeConfig("YOUR_API_KEY") {
	onError = PersonyzeErrorListener { error, context -> Log.w("App", "${context.operation}: ${error.message}") }
}
```

## Configuration

| | Default | |
| --- | --- | --- |
| `baseUrl` | `https://app.personyze.com` | https only |
| `assetsUrl` | `https://counter.personyze.com/` | where the WebView library is served |
| `timeout` | 5 s | a decision, every attempt included |
| `backgroundTimeout` | 10 s | queued events, templates, notifications |
| `retries` | 1 | 0 or 1 |
| `notifications` | false | |
| `notificationChannelName` | `Recommendations` | |
| `notificationSmallIcon` | the SDK's bell | used when a message has no badge |
| `autoFlushDelay` | 3 s | null: events go only with a navigation, `flush()` or the app leaving the foreground |
| `storageName` | `default` | a separate visitor and state (a second account) |
| `okHttpClient` | | proxy, certificate pinning; an interceptor that logs headers would log the key |
| `onError` | | |

## Migrating from 1.x

| 1.x | 2.0 |
| --- | --- |
| `PersonyzeTracker.inst.initialize(context, key)` | `Personyze.initialize(this, PersonyzeConfig(key))`, in `Application.onCreate` |
| `initialize(context, key, true)` | `PersonyzeConfig(key) { notifications = true }`, and declare `POST_NOTIFICATIONS` |
| `navigate(doc)` + `getResult(ctx).addOnCompleteListener` | `val result = Personyze.navigate(doc)` |
| `getResult(ctx)` | `Personyze.refresh()`, or `Personyze.result` |
| `done(ctx)` | automatic; `Personyze.flush()` to send now |
| `logUserData(field, value)` | `setProfile(field, value)` |
| `productViewed(id)` ... `articleGoal(id)` | the same names, plus `actionId` / `quantity` |
| `action.renderOnWebView(ctx, webView, callback)` | `PersonyzeActionView.render(action)` or `PersonyzeActionContent(action)` |
| `reportActionClicked(ctx, clicked)` | automatic in the renderer; `report(click)` for your own |
| `action.reportClick(ctx)` | `action.reportEngaged()` |
| `action.reportClose(ctx, n)` | `action.reportClosed(n)` |
| `action.getContentHtmlDoc()`, `getContentJsonArray()` | `contentHtmlDocument()`, `contentJsonArray()` |
| `checkForNotification(ctx)` | `checkNotifications()` |
| `startNewSession(ctx)`, `clearCache(ctx)` | `startNewSession()`, `clearCache()` |
| `PersonyzeAction` passed through an Intent (Serializable) | pass `action.id`, and look it up in `Personyze.result` |
| `uses-library org.apache.http.legacy` | not needed |

The 1.x state on the device is taken over on the first run: the same visitor (and so the same A/B
arms), session, session history and "don't show again" actions.

What 2.0 fixes besides: notifications showed no title and came back every 15 minutes; WebView actions
needing a helper script rendered without it (`actions/webkit/` does not exist); the OS version, the
daylight-saving time zone and the session history order were sent so the server ignored them;
events were lost on any failure; and the notification check worked only while the app was open.

## Develop

```bash
export JAVA_HOME=...                           # JDK 17 or later - Android Studio's own is in its jbr directory
./gradlew test                                 # JVM and Robolectric
PERSONYZE_API_KEY=... ./gradlew :personyze-android:testDebugUnitTest --tests '*ConformanceLiveTest*'   # live; writes to that account - use a test one
./gradlew connectedDebugAndroidTest            # the WebView bridge, on an emulator or a device
./gradlew publishReleasePublicationToBuildRepository   # AAR, sources, javadoc, POM -> build/repo
```

### Releasing to Maven Central

Through the Central Publisher Portal ([central.sonatype.com](https://central.sonatype.com)), where the `com.personyze`
namespace is verified by a DNS TXT record on personyze.com. **A published version can never be removed or changed** -
only a new version fixes it.

1. Raise `personyze` in `gradle/libs.versions.toml` and add the version to `CHANGELOG.md`.
2. Build the bundle, signed with the release key (its primary key signs - Central refuses a signing subkey):

   ```bash
   # ~/.gradle/gradle.properties: signing.gnupg.keyName=<the key's fingerprint>
   ./gradlew clean centralBundle -Ppersonyze.sign   # -> build/central/personyze-android-<version>-bundle.zip
   ```

   It refuses to build unsigned. The plain `publishReleasePublicationToBuildRepository` stays unsigned.
3. Portal > Publish > Publish Component: a deployment name, the zip. Wait for **VALIDATED** (a few minutes); a
   **FAILED** one lists its problems - fix them and upload again. **Drop** discards a deployment.
4. **Publish**. It reaches `https://repo1.maven.org/maven2/com/personyze/` within about half an hour; the search
   pages later.

The engine (`internal/core`) is plain Kotlin and runs on the JVM; Android is in `internal/android`
and the views.

The API key ships inside every APK, as with 1.x: treat it as known to anyone who unpacks the app.
