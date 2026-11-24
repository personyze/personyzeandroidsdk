# Changelog

## 3.0.0 - 2026-11-23

Personyze's visitor ids are 64-bit numbers on an account that mints them. This version speaks
protocol 2 of the tracker endpoint (`../SPEC.md`, section 1), which carries them exactly.

- **Breaking:** `PersonyzeVisitor.visitorId` and `PersonyzePushStatus.registeredVisitorId` are `Long?`
  (were `Int?`). Compare an id as a number or as its decimal text, never through a `Double`.
- The install's anonymous visitor is sent as `pz_visitor_id`, decimal text, and minted at the width the
  account last answered (`id_bits`): 32 bits on a first run, 64 bits after a `reset()` on an account
  that mints them. Where the account gives the install another id - a new 32-bit one upgraded to 64
  bits, or a 64-bit one on an account taken back to 32 - the SDK keeps it, together with the session
  it came with, and the queued events, the shown-notification record and the push registration follow
  it. A login (`setUserId`) is sent as `user_id` exactly as before.
- A session of a 64-bit visitor is read whole: 2.x read ten digits at most, found no visitor in it,
  and minted a new visitor over the one on the device.
- `PUT push_token` and the notification check name the visitor as its decimal text; a 64-bit
  visitor's id is never replaced by a CRC-32 of it.
- A `user_id` with a fraction (`"12.5"`) is matched to its session as the server keys it (the CRC-32 of
  its text); 2.x could not tell it.
- `visitorId` is null for a new install, and after `reset()`, until Personyze's first answer names its
  visitor (the id the device made up is replaced where the account mints 64-bit ids), and changes once
  more only if Personyze gives the install another id.
- Events with no live session - before the first `navigate`, or after a session ended - wait for the
  next `navigate` in `flush()` and `refresh()` too, as the automatic send always made them: sent alone,
  Personyze has no page view to log them with. Events queued before `startNewSession()` whose session
  is over are reported (`OTHER_SESSION`) instead of being sent alone and refused, and so is an action
  report for a decision made before a login, a logout or a reset - it belonged to no session of the
  new visitor's (a close still keeps the action off the screen).
- Two navigations at once go in the order they were made; the later one no longer fails at once when
  it reaches the network first.
- Needs a server that speaks protocol 2 (Personyze since 2026-11). **Going back to 2.x splits every
  64-bit visitor on the device** - 2.x cannot read the id, and mints a new visitor in its place.

## 2.1.0 - 2026-11-02

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
