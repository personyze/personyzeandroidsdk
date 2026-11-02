# Changelog

## 2.1.0 - not released yet

- Notifications are reported: shown when posted, clicked when the notification or one of its buttons
  is tapped - the campaign's statistics count them as they count a website's push. A tap passes
  through a small invisible activity the SDK's manifest declares; a report that cannot go at once
  goes with the next notification check.
- Pushed through Firebase Cloud Messaging, when the account has uploaded its service account:
  `setPushToken` and `handlePushMessage`, called from the app's own `FirebaseMessagingService` (the SDK
  depends on no Firebase library). Once pushed to, the background check runs every 6 hours.
- Notifications are checked as the app comes to the front too, once a minute at most - before, only
  in the background every 15 minutes.
- `PersonyzeApi` gains `setPushToken`, `handlePushMessage` and `pushStatus` (a `StateFlow` of `PersonyzePushStatus`:
  the token, the visitor it is registered for, whether Personyze pushes to it); `diagnostics()` prints it too.

## 2.0.0 - 2026-10-24

A new SDK in Kotlin, replacing the 1.x Java SDK. Every 1.x feature is kept; the API is new - see the
README's "Migrating from 1.x".

- Coroutines and `StateFlow` instead of Play Services `Task`; a Compose composable beside the View.
- Follows the shared SDK contract (`../SPEC.md`): the visitor's session never sent for another
  visitor (`setUserId` for login and logout), a 5-second budget for the whole call, one retry, 429
  fails open and backs off.
- Events wait on disk and survive the app being closed or offline; never resent when they may have
  arrived.
- Typed purchase attribution (`actionId`, `quantity`), promotions, `track()`, `verdict()`.
- Placeholders exposed on each action; `actionsIn(placeholder)`.
- Fixed: the notification title; the same notification every 15 minutes; WebView helper scripts
  (`actions/default/`, not `actions/webkit/`); the OS version, the time zone's daylight saving and the
  order of past sessions as the server reads them; the background notification check.
- The 1.x visitor, session and history on the device are taken over on the first run.
