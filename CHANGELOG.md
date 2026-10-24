# Changelog

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
