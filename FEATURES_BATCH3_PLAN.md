# Batch 3: plan

Chosen 2026-11-27. Plan only; nothing is built yet. Target: **0.4.0-beta** (versionCode 5).

| # | Feature | Effort |
|---|---|---|
| A | Volume cap (hard limit) | S |
| B | Loudness compensation (#2), a switch | M |
| C | Sleep timer with fade | S |
| D | Test tones and channel check | S |
| E | Shortcuts and Tasker actions | S |
| F | Onboarding tour (#17) | S |
| G | Home screen widget (#8) | M |
| H | Tablet and landscape layout (#19) | M |

Not in this batch: A/B level-matched comparison (dropped by the user).

## Before starting

The 0.3.0 work is still uncommitted and was merged with Shreyansh's Orbit prototype (`69e7a7d`, `172d3f9`). Its build and tests have **not** been rerun since the merge. Step 0: run `testDebugUnitTest assembleDebug assembleRelease` on the merged tree. Batch 3 starts from a green build.

---

## Shared paths: where features can collide

Four features touch the same few places. These rules keep them apart.

### 1. Media volume (A, B, C, and #10 volume-aware bass)

Today the `VOLUME_CHANGED_ACTION` receiver in `EqProcessingService` is registered **only while volume-aware bass is on**. A and B also need it.

- **Rule:** one `MediaVolumeMonitor` owns the receiver. It registers when **any** of #10, A or B is on, and unregisters when none is. It publishes `EqState.mediaVolumeFraction` (today a private field). No feature registers its own receiver.
- **#10 and B must not fight over bass.** #10 eases bass **above 60%**; B adds bass and treble **below 50%**. Their ranges never overlap, and a unit test pins that.
- **A changes the volume itself** (clamping). That fires the same broadcast, so #10 and B recompute from the capped volume. No loop: A only lowers, and only when above the cap.
- **C during its fade.** C lowers the volume step by step. Without a rule, B would add more bass as the volume falls, which is the opposite of a fade. **Rule:** while the sleep fade runs, B and #10 hold their last values (`EqState.volumeFeaturesFrozen`). They resume when the fade ends and the volume is restored.
- **C and A.** C restores the volume after pausing, but never above the cap.

### 2. The gain chain (B, #10, protection, and Orbit)

Order today: tone → correction → bass (applied, after #10) → protection. B is added as one more layer:

**tone → correction → bass (after #10) → loudness (B) → protection.**

- B goes **before** protection, so Safe mode and the limiter still see the final boost.
- It goes into **both** EQ paths (A: automatic pipeline, B: manual sliders), exactly where `appliedBassBoost` is used now. One helper, `EqProfileManager.combineGains(tone, correction, bass, loudness)`, is used by both so they can't drift.
- B is a new layer in Sound layers ("Loudness"), and `AutoSnapshot.requiresSnap` includes it, so changes snap and don't ramp.
- **Orbit (debug)** writes per-channel *input gain* on the `DynamicsProcessing` effect. None of these features touch input gain or limiter settings, so Orbit is unaffected.

### 3. The limiter (A)

A could be built by lowering the limiter threshold, but the limiter only exists in **Balanced** mode, and Orbit shares that effect. **Decision:** A clamps the **media volume** through `AudioManager` instead. It works in every protection mode and leaves the limiter alone.

### 4. Actions from outside the app (E, G, and the existing tile and notification)

The QS tile, the notification buttons, the widget (G) and Tasker/shortcuts (E) all change the same things.

- **Rule:** every outside action goes through the service's existing actions (`ACTION_SET_ENABLED`, `ACTION_TOGGLE`, `ACTION_CYCLE_BASS`) plus new ones. Bass always goes through `DeviceSoundCoordinator.setBass` (AI.md), so per-device bass keeps working.
- **One state for display.** The tile already reads `processingSnapshot` from DataStore. The widget reads the same snapshot plus bass, and is refreshed from `observeProcessingSnapshot`. No second copy of state.
- **Background start limits.** Android 12+ blocks starting a foreground service from most background broadcasts. Widget taps use `PendingIntent.getForegroundService` (allowed). Tasker broadcasts act **only if the service is running**. Otherwise they save the setting, and it applies when EQify next starts. This is documented for users.

### 5. First run (F and the existing walkthrough)

`MainActivity` already shows `PermissionWalkthrough` on first run (`eqify_onboarding` / `permissions_explained`).

- **Rule:** F **extends** that walkthrough into a short tour. It is not a second overlay. There is one flag, so existing users who already finished the walkthrough never see the tour again.
- It must not swallow a profile-share link: `PendingProfileImport` still runs on a fresh launch, and its sheet shows after the tour closes.

### 6. Rotation (H)

The app is locked to portrait, and no screen uses `rememberSaveable`, so rotation would close open sheets and dialogs. MainActivity also restarts the service and update checks in `onCreate`.

- **Decision:** handle size and orientation changes in the activity (`android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`) instead of recreating it. Compose relayouts; sheets, dialogs, file pickers and the QR scanner keep their state. This is the low-risk option.
- Remove `screenOrientation="portrait"`.

### 7. Backup (#11)

Every new user setting is added to `BackupCodec.SETTINGS`: loudness switch, volume cap on/level, sleep-timer default length. The onboarding flag and widget state are **not** backed up.

---

## A. Volume cap

- **Settings → Sound → Volume limit:** off by default. When on, pick a maximum from 50% to 90% of media volume.
- When media volume goes above the cap, it is set back to the cap. A one-time toast explains why.
- It works on any output. It is optional per device later, but not in this batch.
- **Code:** pure `VolumeCap.clampIndex(volume, max, capFraction)`, plus the shared monitor.
- **Edge cases:**
  - Turning the cap off restores nothing (the volume stays where it is).
  - Bluetooth absolute volume changes are seen as the same stream.
  - Some phones (Samsung's own media limit) may also clamp; ours never raises the volume, so they can't fight.
- **Tests:** clamp rules and rounding at each index.

## B. Loudness compensation

- **Settings → Sound → Loudness compensation:** a switch, off by default.
- Below 50% media volume, it adds a gentle bass shelf and a small treble lift. It is strongest at low volume: up to about +6 dB bass and +3 dB treble near 10%. It is 0 at 50% and above.
- **Code:** pure `LoudnessCompensation.curve(fraction): FloatArray(8)`, rounded to 0.5 dB, plus `EqState.appliedLoudness`.
- It is a layer in both EQ paths, Sound layers, and a Home note ("Loudness compensation +4 dB at this volume").
- **Edge cases:**
  - Paused or bypassed EQ: no effect.
  - Device presets and manual curves: it still applies, since it is about volume, not tone.
  - Frozen during the sleep fade.
- **Tests:**
  - The curve is 0 at and above 50%.
  - It rises as volume falls.
  - It never exceeds the caps.
  - Its range never overlaps #10.

## C. Sleep timer with fade

- **Home → overflow → Sleep timer:** 15, 30, 45 or 60 minutes, or end of track.
  - "End of track" uses the now-playing change from the media listener, so it needs notification access. It is hidden otherwise.
- **Last 30 seconds:** the media volume steps down to 0.
- **Then:** EQify asks for audio focus, which makes music apps pause, and restores the original volume (never above A's cap). EQify itself keeps running.
- An ongoing line in the EQify notification shows "Sleep in 12 min" with a **Cancel** action. Cancelling during the fade restores the volume at once.
- **Code:** `SleepTimer` (timer state, pure schedule logic, unit-tested) runs in the service scope, so it survives the app being closed. It is not persisted across process death or reboot.
- **Edge cases:**
  - The user changes the volume during the fade: the fade cancels and the timer finishes without fading.
  - Headphones disconnect: the timer keeps going.
  - A player ignores audio focus: the volume still ends at 0, then is restored after 5 s with a note in the notification.
- **Tests:** schedule, fade steps, cancel and restore rules.

## D. Test tones and channel check

- This extends the existing `EqTestTonePlayer` (low, mid and high tones on Home). Home → test sound opens a small sheet with:
  - **Left only / Right only**, to check channels and which earbud is which.
  - **Low-to-high sweep** (20 Hz to 20 kHz, 10 s), to hear the EQ and hearing range.
  - **The current low, mid and high tones**, unchanged.
- Stereo `AudioTrack` with a channel mask. Quiet level, as now. One sound at a time (the existing `AtomicBoolean`).
- **Notes shown in the sheet:**
  - When Force mono is on: "Force mono is on, so both sides play together."
  - In debug builds, Orbit is paused while a channel test plays.
- **Tests:** sample generation (pure): channel placement, sweep frequency endpoints, fades with no clicks.

## E. Shortcuts and Tasker actions

- **App shortcuts** (long-press the icon): Pause/Resume EQ, Bass cycle, and Sleep timer 30 min. These are static `shortcuts.xml` entries that open a small, non-UI `ShortcutActivity`. It starts or reaches the service and finishes at once. The app is in the foreground for this, so starting the service is allowed.
- **Tasker / automation:** an exported `AutomationReceiver` with an allowlist of actions:
  - `com.liteplane.eqify.action.SET_EQ` (extra `enabled`)
  - `SET_BASS` (0, 3, 6 or 9)
  - `SET_PRESET` (a preset name; unknown names are ignored)
  - `SET_AUTO_GENRE` (on or off)
  - `SLEEP_TIMER` (minutes, or 0 to cancel)
- Nothing else is accepted. Every extra is validated, and there is no way to read data out.
- **Security:** any app can send these. They only change sound settings, so the risk is low, and they are **off by default** behind **Settings → Automation → Allow other apps to control EQify**.
- **Tests:** the parser and allowlist are pure and unit-tested (bad extras, unknown actions, out-of-range values).

## F. Onboarding tour

- This replaces the content of `PermissionWalkthrough` with 4 short pages, skippable at any point:
  1. **Genre EQ:** "EQify changes your sound to match what's playing." It explains the Auto and manual modes.
  2. **Headphones:** correction, and how it remembers each device.
  3. **Presets and bass**, plus where Sound layers is.
  4. **Permissions:** the existing notification-access and Bluetooth/notification requests, with the existing explanation text (`NotificationAccessInfo`).
- It uses the same flag, so people who finished the old walkthrough skip it. **Settings → About → Show tour again** reopens it.
- It works in landscape (H): pages scroll and buttons stay reachable.

## G. Home screen widget

- **What it shows** (2×1, resizable to 4×1):
  - EQ state (Active, Paused, Bypassed).
  - Current profile.
  - **Pause/Resume** and **Bass** buttons, the same as the notification.
  - A tap elsewhere opens the app.
- RemoteViews `AppWidgetProvider`, with no new library. It follows the app theme's light or dark mode, with a basic style.
- **Data:** `processingSnapshot` plus the effective bass, stored with the snapshot so the widget never reads in-memory state.
  - Updates are pushed from `EqProcessingService.observeProcessingSnapshot` and on bass change.
  - When the service isn't running, the widget shows "Paused" from the snapshot, like the tile.
- **Taps:** `PendingIntent.getForegroundService` for the service actions.
- **Edge cases:**
  - EQify updated or restarted: the widget reloads from DataStore.
  - Several widgets: all update.
  - Removed widget: nothing to clean.
- **Tests:** the pure "what the widget says" mapping, shared with the tile's text where possible.

## H. Tablet and landscape layout

- Allow rotation, with no activity recreation (see §6).
- **Navigation:** a bottom bar on narrow screens and a `NavigationRail` at 600 dp and wider. This is decided by window width (`LocalConfiguration.screenWidthDp`, no new library).
- **Content:**
  - Each tab's content is capped at about 640 dp wide and centred, so it doesn't stretch on tablets.
  - The EQ screen in landscape puts the sliders and the graph side by side when wide enough. Otherwise it scrolls.
- **Sheets and dialogs:** checked in landscape on a phone (short height). Every sheet scrolls; buttons are never off screen.
- **Not included:** two-pane layouts, foldable postures, separate tablet designs.
- **Checks:** each screen in portrait, landscape phone, and 10-inch tablet emulator sizes, in light and dark themes.

---

## Order

1. **Step 0:** verify the merged 0.3.0 build.
2. **Shared volume monitor** (refactor #10 onto it, no behaviour change, tests still pass).
3. **A Volume cap**, then **B Loudness**, on the monitor.
4. **C Sleep timer**: needs the monitor and the freeze rule.
5. **D Test tones**: independent.
6. **E Shortcuts and Tasker**: needs C for the sleep action.
7. **G Widget**: reuses the snapshot and actions.
8. **F Onboarding**, then **H Layout** last. H touches every screen, so it goes after the others' UI is in place, and it checks their sheets in landscape too.

## Checks before release

- Unit tests:
  - `VolumeCap`, `LoudnessCompensation` (including no overlap with #10), `SleepTimer`, the tone generators, the automation parser and the widget text.
  - The backup round trip with the new keys.
- `testDebugUnitTest assembleDebug assembleRelease`.
- **On a phone:**
  - Cap, loudness and volume-aware bass together while pressing volume up and down.
  - A sleep fade with loudness on (no bass rise).
  - A sleep fade with a cap.
  - Widget, tile, notification and shortcut all toggling the same state.
  - Rotating with a sheet open.
  - Orbit still running in debug.
- Version 0.4.0-beta (versionCode 5), release notes, README, PRIVACY (Tasker toggle; no new data leaves the phone), AI.md, and a commit message.

## Defaults I chose (say if you disagree)

1. Volume cap: off, range 50–90%.
2. Loudness compensation: off, active below 50% volume only.
3. Sleep fade: 30 seconds, then pause through audio focus.
4. Tasker control: off by default, allowlisted actions only.
5. Widget: Pause and Bass buttons, matching the notification.
6. Rotation is handled without recreating the activity.

---

## Correction log

| Date | Who | Change | Why |
|---|---|---|---|
| 2026-11-27 | Claude | Built all eight as **0.4.0-beta** (versionCode 5). 203 JVM tests pass (23 new); debug and release builds pass, including release lint. Not yet checked on a phone. | — |
| 2026-11-27 | Claude | §C: changing the volume during the fade **cancels the timer** and keeps the user's volume (the plan said "finishes without fading"). | Touching the volume means they're awake. |
| 2026-11-27 | Claude | §C: if a player ignores the pause request, the volume is **left at 0** with a notification note and a Dismiss action, not restored after 5 s. | Restoring would blast music that is still playing. |
| 2026-11-27 | Claude | §C: the sleep timer opens from a moon icon in Home's top bar. Home has no overflow menu. The notification gets a Cancel sleep action. | — |
| 2026-11-27 | Claude | §D: Home's "Play test sound" button now opens the test-sound sheet. The left/right tests use a 440 Hz tone, 1.5 s each. | — |
| 2026-11-27 | Claude | §E: the automation switch is **not** in backups. | Restoring a file should never silently let other apps control EQify. |
| 2026-11-27 | Claude | §G: the widget uses its own light/dark colours (`values-night`), not the app's accent. Bass taps send the next step (`ACTION_SET_BASS`), not a cycle, so a cold start can't pick the wrong step. | RemoteViews can't follow Compose theming. |
| 2026-11-27 | Claude | §H: content is capped at **840 dp**, not 640. The EQ screen goes side by side whenever the window is landscape and under 600 dp tall. | 640 left phone landscape too narrow. |
