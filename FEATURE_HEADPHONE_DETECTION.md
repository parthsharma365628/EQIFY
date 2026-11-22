# Feature Plan: Reliable headphone auto-detection (Bluetooth, USB, wired)

Status: **implemented in source (2026-11-22).** Build and 110 unit tests pass (11 new). Device check pending.

Source read: `Bluetoothheadphonedetector.kt`, `EqifyApplication.kt`
(receiver registration), `HeadphoneDataRepository.kt`, `EqProcessingService.kt`
(correction resolution), `EqState.kt` (`HeadphoneCorrectionStatus`),
`screens/HeadphonesScreen.kt` (manual selection), `screens/HomeScreen.kt`
(wired banner), `screens/SettingsScreen.kt`.

## Problems in the current detector (verified in source)

| # | Problem | Effect |
|---|---|---|
| 1 | `ACTION_ACL_DISCONNECTED` only logs | After disconnecting headphones, the **phone speaker gets the headphone correction** |
| 2 | Only `ACTION_HEADSET_PLUG` for wired | **USB-C headphones and dongles are never detected** (most phones have no 3.5 mm jack) |
| 3 | `ACTION_ACL_CONNECTED` fires for any Bluetooth device | Watches, cars and keyboards trigger matching; it also fires when the device isn't the audio output |
| 4 | No memory of manual choices | Renamed devices ("My Buds") and wired headphones need choosing every time |
| 5 | Loose `contains` matching, first hit wins, no minimum length | Can silently pick the wrong model |

## Design **[DESIGN CHOICE]**

### Detection source
Replace the broadcasts with `AudioManager.registerAudioDeviceCallback` (API 23+,
`minSdk` 26):
- It reports **only audio devices**.
- It reports **both add and remove**.
- It gives the device **type** and **product name**.

On each change, the detector recomputes the **current output** from
`getDevices(GET_DEVICES_OUTPUTS)`, highest priority first:

| Output kind | Types | Has usable name |
|---|---|---|
| `BLUETOOTH` | A2DP, BLE headset, BLE speaker, hearing aid | yes |
| `USB` | USB headset, USB device | yes (product name) |
| `WIRED` | wired headphones/headset | no (generic) |
| `SPEAKER` | none of the above | — |

The device identity key is `kind:normalised product name`, or just `wired` for
3.5 mm. The current output is published as a process-wide `StateFlow`.

### On a new output (when auto-detection is on)
1. **Remembered choice** for this device key → apply it silently. That includes
   "not headphones", which means no correction.
2. **Otherwise, a named device** (Bluetooth/USB) goes to `HeadphoneMatcher` (pure):
   - **Confident** → select it, and show the banner *"Using the X correction for Y"* with **Undo**.
   - **Likely** → banner *"Is Y a X?"* with **Use it** / **Choose…** / **Not headphones**.
   - **No match** → banner *"Which headphones are Y?"* with **Choose…** / **Not headphones**.
3. **Wired, not remembered** → today's banner, *"Wired headphones connected. Which model?"*
   with **Choose…**. Wired names are generic, so there's no guessing.
4. The banner appears only when something changed or needs an answer; a
   remembered device that's already selected shows nothing.

### Phone speaker
When the current output is `SPEAKER` (and auto-detection is on):
- The EQ service applies **no headphone correction**, with status *"Phone speaker:
  no headphone correction"*. The genre/tone curve and bass still apply.
- The user's saved selection is **not changed**, so reconnecting restores the
  correction automatically.

### Remembering
Any explicit choice while a non-speaker output is current is stored for that
device key, in a small DataStore map:
- the Headphones screen's Done, a custom-profile tap, or Name a headphone
- the banner's **Use it**
- the banner's **Not headphones**

Undo restores the previous selection and stores nothing.

### Matcher (pure, JVM-tested)
Normalise to lowercase letters and digits. Strip a leading `LE` and owner words
(`parth's`). Then, against every catalog name:

| Kind | Rule | Example |
|---|---|---|
| Exact | normalised equal | `WH-1000XM5` = `wh1000xm5` |
| Brand omitted | catalog ends with the device name, device ≥ 5 chars | `Sony WH-1000XM5` ← `WH-1000XM5` |
| Extra words on device | device contains the catalog name, catalog ≥ 6 chars | `Parth's Galaxy Buds2 Pro` ⊃ `Galaxy Buds2 Pro` |
| Partial | catalog contains the device name, device ≥ 5 chars | likely only |

Exact → confident. Exactly one "brand omitted" or "extra words" hit → confident;
several → likely (best = closest length). Partial → likely. Anything shorter than
the minimum → no match (so "Buds" never matches a model).

## Files
- `HeadphoneMatcher.kt` (new, pure) + `HeadphoneMatcherTest`
- `DeviceHeadphoneMemory.kt` (new, pure codec) + test; `UserPreferencesRepository` stores the map
- `HeadphoneDetector.kt` (new): replaces `Bluetoothheadphonedetector.kt` (deleted)
- `EqifyApplication.kt`: register the device callback; remove the Bluetooth/wired receiver
- `EqProcessingService.kt`: speaker → no correction; `EqState.kt`: new `PhoneSpeaker` status
- `HomeScreen.kt`: detection banner; `HeadphonesScreen.kt`: remember explicit choices, show speaker status
- `SettingsScreen.kt`: updated auto-detect subtitle
- `AI.md`

## Not in scope
Per-app routing, multiple simultaneous outputs, and casting. Bluetooth product
names on Android 12+ may need `BLUETOOTH_CONNECT` (already requested). If a name
is blank, the device is treated like wired (ask, then remember).

## Correction log
| Date | By | Change | Why |
|---|---|---|---|
| 2026-11-22 | Claude | Plan written. | — |
| 2026-11-22 | Claude | **Implemented as planned.**<br>New: `HeadphoneDetector.kt`, `HeadphoneMatcher.kt`, `DeviceHeadphoneMemory.kt` (+ `AudioOutputKind`), plus tests `HeadphoneMatcherTest` (8) and `DeviceHeadphoneMemoryTest` (3). `Bluetoothheadphonedetector.kt` deleted.<br>Changed: `HeadphoneDataRepository.allNames`, `UserPreferencesRepository.deviceChoices`/`setDeviceChoice`, `HeadphoneCorrectionStatus.NotOnHeadphones`, the EQ pipeline input `correctionSuppressed`, the Home `DetectionBanner` (replaces the wired banner), remember-on-choice in `HeadphonesViewModel`, and the Settings subtitle.<br>Details:<br>- The newest connected headphone-like output is current; removing it falls back to the previous one or the speaker.<br>- A confident automatic selection is not remembered, because it re-matches deterministically. Only explicit choices are remembered.<br>- Wired prompts have no "Not headphones" button (3.5 mm is almost always headphones).<br>- Not verified: the Bluetooth `productName` without `BLUETOOTH_CONNECT` on Android 12+ (blank names are treated like wired), and behaviour on each device. | Record the actual source state. |
