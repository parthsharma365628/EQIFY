# Feature Plan: Crash Reporting (Sentry)

Status: implemented in source on 2026-11-21. See the **Correction log** for the
build and test results. Device check: trigger the debug test crash and confirm
it appears in the Sentry dashboard.

This is the PRD §6.3 "Crash reporting" **P0 (pre-launch)** item. It's needed so
the ">99% crash-free sessions" KPI can be measured at all.

Source read: `EqifyApplication.kt`, `SettingsViewModel.kt`,
`screens/SettingsScreen.kt`, `MainActivity.kt` (onboarding SharedPreferences
pattern), `EqEngine.kt` / `EqProcessingService.kt` / `GenreCoordinator.kt`
(existing `Log.e`/`Log.w` sites), `app/build.gradle.kts`.

## Decisions **[DESIGN CHOICE]**

| Topic | Decision | Why |
|---|---|---|
| Tool | Sentry Android SDK (`io.sentry:sentry-android`, includes NDK) | Free tier is enough for a beta. Captures native crashes (audio effects run below Kotlin) and ANRs. No Firebase project or `google-services.json` needed. |
| DSN | Gradle property `EQIFY_SENTRY_DSN` → `BuildConfig.SENTRY_DSN`, default `""` | Same pattern as `EQIFY_BASE_URL`. Kept in the developer's `~/.gradle/gradle.properties`, never in Git. Blank = reporting fully off. |
| Init | Manual `SentryAndroid.init` as the first thing in `EqifyApplication.onCreate`; manifest `io.sentry.auto-init=false` | Lets the opt-out and the scrubbing run before any event is captured. |
| Opt-out storage | SharedPreferences `eqify_crash_reporting` / `enabled`, default **on** | Must be read synchronously at process start. DataStore is async and would miss early crashes. |
| Toggle effect | Off → `Sentry.close()` immediately. On → init immediately. | No restart required, either way. |
| Privacy | `sendDefaultPii=false`, no user, no screenshots/view hierarchy, no user-interaction breadcrumbs, no performance tracing. `beforeSend`/`beforeBreadcrumb` scrub the current title, artist, selected headphone and Last.fm key, plus URL query strings. | Matches the app's "song data stays local" promise. iTunes URLs contain the title. |
| Handled errors | Report once per process per category: equalizer attach failure, session-0 fallback failure, gain-apply failure, limiter FAILED (not UNSUPPORTED), genre-history write failure, receiver registration failure. | Problems that never crash but break EQ silently. Once per category avoids flooding the dashboard. Network failures are excluded, because offline is normal. |
| Debug tools | Settings items in **debug builds only**: "Send test error" (handled event) and "Crash app (test)". | To verify the setup end to end. |
| Release / env | `release = "com.liteplane.eqify@<versionName>+<versionCode>"`, `environment = BuildConfig.BUILD_TYPE` | Separates debug noise from beta crashes. |

## Not in scope
- Mapping-file upload: R8 is off (`isMinifyEnabled = false`), so stack traces are already readable. Add the Sentry Gradle plugin and `SENTRY_AUTH_TOKEN` when minification is turned on.
- Performance monitoring, session replay, user feedback widget.
- Play Console Data Safety form: the owner declares "Crash logs" and "Diagnostics" before release.

## Files

| File | Change |
|---|---|
| `app/build.gradle.kts` | Sentry dependency; `SENTRY_DSN` BuildConfig field. |
| `AndroidManifest.xml` | `io.sentry.auto-init=false`. |
| `CrashScrubber.kt` (new, pure) | Redacts sensitive strings and URL query strings. JVM-tested. |
| `CrashReporting.kt` (new) | Init/close, opt-out preference, scrubbing hooks, `recordHandled(category, throwable)`, debug test helpers. |
| `EqifyApplication.kt` | Calls `CrashReporting.start(this)` first. |
| `EqEngine.kt`, `GenreCoordinator.kt` | `recordHandled` calls at the listed sites (logging unchanged). |
| `SettingsViewModel.kt`, `screens/SettingsScreen.kt` | Toggle, privacy copy, debug test items. |
| `CrashScrubberTest.kt` (new) | Scrubbing tests. |

## Verification
- **JVM:** the scrubber redacts case-insensitively; it ignores blank or very short secrets (under 3 characters, so "a" doesn't erase text); it redacts URL query strings; its output is unchanged when there is nothing to redact.
- **Device (owner):**
  - Debug build → Settings → "Send test error" → the event appears in Sentry within a minute.
  - "Crash app (test)" → reopen the app → the crash appears.
  - Toggle off → test error → nothing arrives.
  - Check that an event's breadcrumbs and message contain no song title.

## Correction log
| Date | Corrected by | What changed | Why |
|---|---|---|---|
| 2026-11-21 | Claude | Plan written and implemented in the same pass. `assembleDebug` and `testDebugUnitTest` pass (74 tests, 8 new in `CrashScrubberTest`). The DSN reaches `BuildConfig` from the user-level `gradle.properties`. Not verified: events actually arriving in Sentry (needs a device), NDK/ANR capture, and that the toggle stops sending. Sentry SDK pinned at 7.14.0. | Record the actual source state. |
