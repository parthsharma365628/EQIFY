# Feature Plan: EQ Transition Smoothing (gain ramp on automatic changes)

Status: implemented in source on 2026-10-17; Android compilation and device
listening validation pending. The user's implementation request superseded the
Phase 0 listening gate; do not claim an audible benefit until tested.

This is the "Track-transition EQ crossfade" P2/M candidate in
`EQify_Android_PRD_v2.md` (Section 6.3). The product name
"crossfade" is misleading: nothing about the audio signal is crossfaded. The
feature ramps the **applied EQ gains** from the previous curve to the new curve
over a short time instead of jumping. This document uses "transition smoothing"
or "gain ramp" to avoid implying audio mixing.

Source review: commit `a353457` on 2026-10-17. `EqEngine.kt`,
`EqProcessingService.kt`, `EqState.kt`, `EqProfileManager.kt`, Home bass controls,
Settings, and Gradle test dependencies were read. Device behavior and perceived
benefit remain unverified. Re-check source if HEAD changes before implementation.

## Note for AI assistants reading this file

1. Re-check current source before coding, especially every call to
   `EqEngine.applyGains`, `applyFlat`, and `releaseEqualizer`.
2. Update **Assumptions and open questions** when a decision is made or an
   assumption is disproved.
3. Append a row to the **Correction log** when you correct this plan. Do not
   silently rewrite sections.
4. Phase 0's listening gate may show no benefit. If so, stop and record it.

Confidence tags:
- **[VERIFIED in source]** — observed in the Kotlin/Gradle files at the commit above.
- **[UNVERIFIED on device]** — requires measured or listening validation.
- **[DESIGN CHOICE]** — a decision made in this plan, with reasoning, open to
  challenge.

## Scope

Problem: when the automatic pipeline produces a new composed curve (genre
change, headphone correction arriving or changing), the new gains may be written
to the effect in one step. Every band jumps at once, which can be audible as a
tonal lurch or click. This is most likely at track changes, where the local
genre guess can be published first and then replaced by a network result within
seconds, causing **two** consecutive jumps.

In scope:
1. A pure, JVM-testable gain interpolator.
2. A ramp driver at the hardware-write boundary that applies interpolated gains
   in small steps for automatic-path changes only.
3. Correct behavior when a new target arrives mid-ramp, when the manual path
   writes, when the effect detaches, and when the service stops.
4. A Settings toggle to disable smoothing.

Non-goals:
- No audio-signal crossfade, buffering, or delay. Latency of the audio path is
  unchanged.
- No smoothing of manual slider writes. Add only a ramp-cancel call before the
  existing instant write; never make sliders wait on a ramp.
- No change to gain composition order
  (`tone/genre -> headphone correction -> bass boost`), clamping, or
  `mapToDeviceBands` arithmetic.
- No change to limiter parameters or `OutputProtectionMode` semantics.
- No new network calls, no new persistent data beyond one optional preference.
- No per-sample or sub-10 ms smoothing. No guarantee that `Equalizer` writes
  are artifact-free; that requires device validation.

## Verified current architecture and implications

| Component | Current behavior | Consequence for this feature |
| --- | --- | --- |
| Two paths | `EqProcessingService` has a `collectLatest` automatic pipeline and an immediate manual collector; both call `EqEngine.applyGains`. The auto path checks manual override immediately before publishing. **[VERIFIED in source]** | A delayed ramp job must share cancellation with manual writes; the existing pre-write check alone cannot guard later ramp steps. |
| Engine and sessions | `EqEngine` is a process singleton. Session broadcasts release/recreate effects; attach reapplies `currentGains`. `applyGains` updates `currentGains`, lazily attaches, maps and writes every band. `applyFlat` writes zeros. **[VERIFIED in source]** | Track **final target** separately from interim applied levels. Never let a reattach restore a mid-ramp value. Invalidate ramp on every release/attach, including same-session reattach. |
| Hardware mapping | `mapToDeviceBands` selects the nearest logical center per device band, multiplies dB by 100 using `toInt()` truncation, then clamps to `bandLevelRange`. **[VERIFIED in source]** | Map final, protection-processed logical gains once per target and interpolate the resulting millibel device levels. Do not change mapping arithmetic. |
| Hardware writes | `EqEngine` loops over `Equalizer.setBandLevel(Short, Short)`; no coroutine scope or applied-level snapshot exists. Limiting uses a separate `DynamicsProcessing` effect. **[VERIFIED in source]** | Add a small engine write API and applied-level tracking; service owns the ramp job and its lifetime. Hardware calls remain on the main dispatcher, as the current service path does. |
| Protection | `EqProfileManager.combineGains` clamps to ±12 dB. The service then applies Safe scaling if Safe is chosen or Balanced's limiter is inactive, before calling the engine. **[VERIFIED in source]** | Ramp already-protected targets. Protection mode changes must cancel/snap; otherwise an Off/Balanced starting curve could exceed Safe limits mid-ramp. |
| Shared state | `EqState.activeBandGains` and `currentHeadphoneCorrection` hold computed target/cached correction. Manual writes compose against the latter. **[VERIFIED in source]** | Keep ramp intermediates out of `EqState`; its published gain values remain target values, not instantaneous hardware readback. |
| Events | `NowPlayingState.updateSong` clears the genre and manual override before a local genre result; a network result may later replace it. Headphone selection/data resolution and bass changes also emit into the auto pipeline. **[VERIFIED in source]** | Expect blank → local → remote genre targets and `collectLatest` cancellation. Do not assume a fixed time gap or a distinct correction-arrival flow. |
| User controls | Home bass boost is a row of discrete buttons; `EqScreen` preset selection and slider edits are user-initiated. **[VERIFIED in source]** | Choose selective ramping: genre/correction changes ramp; bass, preset, and other settings changes snap. |
| Test setup | `app/src/test` uses JUnit; `kotlinx-coroutines-test` is not declared in `app/build.gradle.kts`. **[VERIFIED in source]** | Keep the interpolator pure; add a test-only coroutine dispatcher/clock seam and `kotlinx-coroutines-test` for scheduling tests. |
| Device smoothing | The Android effect's own behavior is unknown. **[UNVERIFIED on device]** | Measure/listen before implementation; don't infer a click from the code alone. |

## Design

### Pure interpolator (new file, no Android dependency)

New file `EqGainRamp.kt` in `app/src/main/java/com/example/eqify/`. Keep the
interpolator free of Android types and model levels as `IntArray` millibels;
convert to `Short` only after bounding by the device's level range.

For device band $i$, ramp duration $T$, elapsed time $t$, previous applied level
$g_i^{\text{old}}$ and target level $g_i^{\text{new}}$:

$$g_i(t) = g_i^{\text{old}} + \left(g_i^{\text{new}} - g_i^{\text{old}}\right)\,\frac{\min(t, T)}{T}$$

Interpolate in dB (equivalently millibels). Do not convert to linear amplitude.
**[DESIGN CHOICE]** Linear in dB is simple, monotonic, and adequate for a 250 ms
ramp. An ease-in-out curve is a tuning option, not a v1 requirement.

Sketch of the pure API (illustrative, not literal code to paste):

```text
interpolate(from: IntArray, to: IntArray, elapsedMs: Long, durationMs: Long): IntArray
  - require from.size == to.size
  - durationMs <= 0 or elapsedMs >= durationMs -> return to.copyOf()
- clamp elapsed to [0, duration], then linear per band, rounded to nearest
  millibel; each result remains between its endpoints
```

### Headroom invariant (why Safe and Balanced stay valid mid-ramp)

Each interpolated band level is a convex combination of the old and new level:

$$g_i(t) = (1-\lambda)\,g_i^{\text{old}} + \lambda\,g_i^{\text{new}}, \qquad \lambda = \tfrac{\min(t,T)}{T} \in [0,1]$$

so

$$g_i(t) \le \max\!\left(g_i^{\text{old}},\, g_i^{\text{new}}\right)$$

If both endpoints came from Safe-processed targets on the **same attachment**,
each intermediate mapped band stays at most +6 dB (or the lower hardware max).
No per-step protection recomputation is needed. This does **not** justify a
ramp across protection-mode changes: Off/Balanced may start above +6 dB, so
those changes snap. Balanced's limiter remains enabled independently.

### Ramp driver

**[DESIGN CHOICE]** The service owns one cancellable ramp job in `serviceScope`
(already `Dispatchers.Main`); the engine owns the attachment identity, final
logical target, mapped device target, and last successfully written device
levels. This avoids a process-wide coroutine job surviving service teardown.
Keep all engine mutation on the main thread; the application-registered audio
session receiver also runs there with its current registration.

Proposed engine boundary (names illustrative):

```text
prepareTarget(finalLogicalGains) -> { attachmentEpoch, fromLevels?, toLevels }
writeRampStep(attachmentEpoch, levels) -> success/failure
applyGains(finalLogicalGains) -> existing instant path; service cancels ramp first
applyFlat() -> existing instant bypass/disable path; service cancels ramp first
```

`prepareTarget` copies **final** logical gains to `currentGains`, lazily attaches
if needed, maps to device levels once, and captures the last successful levels.
If there is no trustworthy `fromLevels` (first apply, reattach, band-layout
change, or prior write failure), write the target immediately. Intermediate
`writeRampStep` calls must not overwrite `currentGains`. The existing attach
path continues to apply `currentGains` directly, so a new session snaps to the
latest final target. Track an `attachmentEpoch` incremented on every release
and successful attach; a boolean attached flow alone could miss a rapid
detach/reattach. Invalidate `fromLevels` on release and on any partial write
failure. Never assume all bands were written if an exception interrupted a loop.

Behavior:
1. Use one service-owned `Job` plus a monotonically increasing request token.
   Every new automatic target, manual write, snap event, and service teardown
   invalidates the token and cancels the old job **before** writing anything.
2. For an eligible automatic target, capture `fromLevels` from the engine's
   last successful step, plus the new mapped target and attachment epoch.
   Never begin from the previous *target*. If equal, skip scheduling.
3. Use an injected monotonic clock backed by `SystemClock.uptimeMillis()` in
   production, not a count of completed delays. Aim for 20 ms intervals and a
   250 ms total; delayed frames skip ahead, never queue catch-up writes. Do
   not busy-wait.
4. Before every step, verify request token, service active/enabled/not bypassed,
   manual-override state, and attachment epoch. Then write only changed band
   levels. After the final write, verify exact equality to the target.
5. On a per-band write failure, abort the ramp and use the engine's existing
   release/fallback behavior; the next attachment snaps to `currentGains`.
   Never continue writing through a stale `Equalizer` reference.

The sequence above is a contract, not an invitation to add a second audio
effect or make network calls in the ramp job. The existing auto pipeline still
computes and publishes the final curve once; only the hardware application is
deferred. `EqState` and notification reflect the target immediately.

### What snaps instead of ramping

**[DESIGN CHOICE]** Apply instantly, with no ramp, in these cases:
- Any manual-path write. It invalidates the service's request token and
  cancels its running job before writing, so manual input wins.
- First apply after the service starts or the effect (re)attaches, and any
  session change. There is nothing to ramp from.
- Master enable/disable and the comparison bypass (A/B). A comparison must be
  immediate to be fair to the listener.
- Bass/preset/mono/auto-genre controls, output-protection mode changes, and
  any emission mixing one of these with a genre/correction change.
- Effect detach/session change or engine release: invalidate the attachment
  epoch; the service job exits on its next check. Service stop cancels the job
  but **does not release the application-owned engine solely for smoothing**.
- Smoothing disabled in Settings.

### Which automatic changes ramp

**Decision: selective ramping.** Compare the latest auto pipeline inputs to
the previous accepted inputs. Ramp only when genre changes (including its
interim blank state) or when the selected headphone correction identity/**gain
content** changes. A remote genre upgrade is another normal retarget. Snap
when bass boost, preset/base tone, auto-genre setting, master enable/bypass,
mono, protection mode, or smoothing preference changes; these are controls or
safety transitions. If several inputs change in one emission, **snap wins**.
Compare gain arrays by content, not reference. Track the previous accepted
target only after the existing manual-override stale check. Do not classify a
repeated emission as a fresh transition, and do not let an older `collectLatest`
run overwrite a newer manual write. A manual write cancels the job; suppress
further automatic ramp steps while manual override is active. A subsequent
explicit preset pick snaps. A track change that clears override can ramp again.

### Settings

**[DESIGN CHOICE]** One boolean preference, default on **after device gate
passes**: "Smooth EQ transitions". Store it in `UserPreferencesRepository`,
expose it through `SettingsViewModel`, and include it in the service pipeline.
The engine does not read a separate process-local flag. Place it near processing
settings in `SettingsScreen`, with one line of explanatory copy: "Gradually
shifts the EQ when the genre or headphone profile changes instead of switching
instantly." Ramp duration stays a code constant (250 ms) in v1, not a user
setting. Turning smoothing off during a ramp cancels and snaps to the current
final target; turning it on does not ramp until the next eligible change.

## Files expected to change

Kotlin paths below are relative to `app/src/main/java/com/example/eqify/`;
test, Gradle, and documentation paths are repository-relative.

| File | Change |
| --- | --- |
| `EqGainRamp.kt` (new) | Pure interpolator and constants. |
| `EqEngine.kt` | Final-target vs applied-level tracking, mapped-target preparation, attachment epoch, guarded/changed-band step writes; retain instant `applyGains`/`applyFlat` semantics. No engine-owned coroutine. |
| `EqProcessingService.kt` | Service-owned ramp job and token; classify auto emissions; manual collector cancels ramp before its existing instant write; snap on controls and stop. Preserve `collectLatest` stale check. |
| `UserPreferencesRepository.kt` | New boolean preference with default `true`. |
| `SettingsViewModel.kt`, `screens/SettingsScreen.kt` | Toggle and copy. |
| `app/src/test/java/com/example/eqify/EqGainRampTest.kt` (new) | Pure interpolation/convexity/boundary tests. Add a separately testable coordinator with injected engine/clock if scheduler tests are included. |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | Add a test-only `kotlinx-coroutines-test` dependency aligned with the resolved coroutine runtime so the coordinator can be tested with virtual time; JUnit is already present. |
| `AI.md` | Add a row to "Where to make a change" and `EqGainRamp.kt` to the layout and file reference. |
| `EQify_Android_PRD_v2.md` | Mark the feature status after implementation. |

## Phase 0: go/no-go before writing code

1. **User/device check remains.** Compare immediate genre changes with music
   or a steady test tone at a safe volume on a real device; note hardware band
   count and whether the change is an objectionable lurch/click. If no useful
   improvement seems possible because the effect already smooths, stop and log
   the observation. Do not claim the platform is smoothing merely because no
   step was heard in one test.
2. Source investigation completed at `a353457`: engine uses `setBandLevel` on
   the main-service path, protection is applied before `applyGains`, bass is
   discrete, engine has no coroutine scope, and JUnit exists without
   `kotlinx-coroutines-test`. Re-check if code changes before implementation.
3. Before coding, confirm that every caller of engine gain/release methods is
   accounted for. If a new background-thread caller appears, serialize engine
   state/writes on the main dispatcher or revise this design.
4. Record the decision to proceed, chosen starting duration, and tested
   device(s) in the Correction log. A no-go requires no source changes.

## Implementation sequence after go decision

1. Add pure `EqGainRamp` interpolation with explicit clamping/rounding rules
   and boundary tests; keep `EqProfileManager.mapToDeviceBands` unchanged.
2. Refactor `EqEngine` only at its write boundary: track attachment epoch and
   last successful device levels, separate restorable final target from ramp
   steps, and preserve instant callers and release/fallback behavior. Verify
   attach during preparation snaps rather than starting a ramp from stale data.
3. Add a service-owned coordinator with injected monotonic clock and engine
   adapter. Centralize `cancelAndSnap` and request-token checks so the auto,
   manual, bypass, toggle, mode, detach, and `onDestroy` paths cannot diverge.
4. Classify accepted auto emissions from a copied prior input/target snapshot.
   Run the existing manual-override stale check **before** preparing/publishing
   a new target. Map/prepare once; use `collectLatest` for resolution, then
   hand the final target to the coordinator without blocking that collector.
5. Add the DataStore toggle and Settings copy. Switching it off calls the
   coordinator's snap path, not just a flag change.
6. Add pure and virtual-time coordinator tests, then perform the device/manual
   checks below. Only then update `AI.md` and the PRD status as implemented.

## Required flows

### 1. Track change with a two-stage genre result

1. `NowPlayingState.updateSong` clears manual override and emits a blank genre;
   the pipeline may target Flat first. This is an eligible genre change.
2. Local genre produces T1. The service cancels the previous ramp and starts
   from the engine's **last successfully applied** levels.
3. If a remote result produces T2, cancel and retarget again. No assumption
   about a fixed 1–3 s delay is needed; the final hardware curve is T2.

### 2. User drags a manual slider during a ramp

1. Manual collector invalidates/cancels the service ramp job **before** its
   existing `EqEngine.applyGains` call, then writes immediately.
2. Token and manual-override checks prevent later ramp steps from writing.
3. The manual value stays. The automatic pipeline's stale-emission guard still
   prevents it from overwriting the override.

### 3. Effect detaches or session changes mid-ramp

1. Engine increments attachment epoch and invalidates the applied-level
   snapshot on release; a job with the old epoch cannot write again.
2. Reattach applies `currentGains` (the final target), not the last ramp step.

### 4. Bypass or master toggle during a ramp

1. Cancel the ramp, apply flat instantly. On resume, snap to the current
   protected target before any later eligible transition.

### 5. Protection or smoothing setting changes mid-ramp

Cancel the ramp immediately. Recompute the target under the new mode and snap;
do not blend an Off/Balanced curve into Safe. Turning smoothing off snaps to
the last final target. Preserve the existing Balanced → Safe fallback when the
limiter fails.

## Verification plan

Unit tests (JVM, `app/src/test/`):
- `interpolate`: $t=0$ returns `from`; $t \ge T$ returns `to`; midpoint is the
  rounded average; negative and positive gains; mismatched array sizes throw;
  `durationMs <= 0` returns `to`.
- Convexity: for random inputs, every interpolated band stays within
  $[\min(\text{from}_i,\text{to}_i),\ \max(\text{from}_i,\text{to}_i)]$.
- Scheduler with a test dispatcher and fake clock: ramp reaches the target in
  about the configured duration; delayed dispatch skips missed steps rather
  than queuing them; retarget mid-ramp starts from the **applied**
  levels, not the old target; a manual write cancels the job and no stale write
  occurs afterward; cancel on detach leaves no writes; unchanged bands are not
  rewritten. Use an injected engine/clock seam and `kotlinx-coroutines-test` if
  a virtual-time test is chosen; the current build lacks that dependency.
- Session epoch/target: a mid-ramp reattach snaps to the final target; a
  partial band-write exception invalidates applied-level state; stale jobs
  cannot write into a new attachment.
- Event classification: genre blank/local/remote and correction-content changes
  ramp; bass, preset, mono, protection, enable/bypass, and preference toggles
  snap; mixed emissions choose snap; a duplicate emission does not restart.

Manual checks (device, with Logcat for write timing):
- Bass-heavy to flat genre switch: no audible step; compare against smoothing
  off in Settings.
- Rapid track skipping: no accumulating lag, no audible glitch, final curve
  equals the last target.
- Headphone selection or downloaded/custom correction change retargets without
  a jump; selecting a preset and clicking a bass button remain immediate.
- Drag a slider while a ramp runs: slider response is immediate and the value
  sticks.
- A/B bypass toggle is instant.
- Safe mode with a boosted headphone correction: no band exceeds the +6 dB rule
  at any step. Switching Off/Balanced → Safe mid-ramp snaps to a Safe target.
  Balanced mode: limiter behavior unchanged.
- Device with 5 hardware bands and API 26 or 27: works, no excess IPC.
- Service killed and restarted mid-ramp: next attach applies the target
  directly.
- Verify CPU/Binder/write cadence in Logcat on a low-end device; do not log
  every normal ramp step in production.
- No regression: Quick Settings tile, notification, Home status unchanged.

## Acceptance checklist

- [ ] Phase 0 records a device-observed problem/benefit or an explicit decision
      to proceed despite inconclusive listening results.
- [ ] Ramp applies to the automatic path only; the manual path is unchanged and
      always cancels a running ramp.
- [ ] No intermediate ramp values are written to `EqState`.
- [ ] Retargeting mid-ramp starts from currently applied levels.
- [ ] Every delayed write is guarded by request token, manual state, and
      attachment epoch; partial write failure invalidates applied-level state.
- [ ] Safe-mode scaling remains before engine target preparation; no per-step
      recompute is needed for same-mode Safe endpoints.
- [ ] Master toggle, bypass, protection-mode change, first apply, and
      reattach snap instantly.
- [ ] Ramp job is cancelled on detach, service stop, and engine release.
- [ ] Reattach applies the final logical target; no interim step becomes the
      engine's restorable `currentGains`.
- [ ] Settings toggle persists, defaults on, and disabling it restores
      instant behavior.
- [ ] Unit tests for the interpolator and scheduler pass in
      `.\gradlew.bat test`.
- [ ] `AI.md` file reference and "Where to make a change" table updated.

## Assumptions and open questions

| # | Item | Status |
| --- | --- | --- |
| 1 | Is an abrupt change audible and does ramping improve it on target devices? | **[UNVERIFIED on device]** Go/no-go listening gate. Do not infer a universal result from one handset. |
| 2 | Which auto emissions ramp? | **[DESIGN CHOICE resolved]** Genre or correction identity/content only; all control/safety changes snap. Bass is discrete in current Home UI. |
| 3 | Settings toggle/default | **[DESIGN CHOICE resolved]** Toggle included; default on only after go/no-go gate, with immediate snap when disabled. |
| 4 | Duration/cadence | **[DESIGN CHOICE]** 250 ms, target 20 ms spacing, monotonic elapsed time; tune from device results without adding a user-facing duration setting. |
| 5 | Driver lifetime | **[DESIGN CHOICE resolved]** Job in `EqProcessingService`; engine owns target/applied levels and attachment epoch. No engine-owned coroutine scope. |
| 6 | Band writes and scaling order | **[VERIFIED in source]** `Equalizer.setBandLevel` in millibels; Safe scaling precedes `EqEngine.applyGains`. |
| 7 | Thread serialization | **[VERIFIED for current callers]** Service collectors and application receiver use main-thread paths; recheck call sites if source moves. |
| 8 | Curve shape | **[DESIGN CHOICE]** Linear in dB/millibels. Try another easing only if listening shows a problem. |

## Correction log

Another AI model, a human reviewer, or a later pass should append here rather
than silently rewriting sections above.

| Date | Corrected by | What changed | Why |
| --- | --- | --- | --- |
| 2026-10-17 | Claude | Initial draft from `AI.md` and plan documents; engine and service source not read. Two corrections to an earlier chat explanation baked in: per-band EQ goes through `Equalizer`, not `DynamicsProcessing` (which is used for the limiter), and headroom does not need per-step recomputation because a convex blend of two protected curves is itself protected. | Keep the plan aligned with the documented architecture and avoid implying unverified internals. |
| 2026-10-17 | Codex | Read current Kotlin/Gradle source; corrected PRD effort and source claims, chose selective ramping and service-owned job, specified final-target/applied-level separation, epoch/token cancellation, Safe-mode snap, tests, and device go/no-go. | Original driver could restore a mid-ramp value on reattach or let delayed writes override manual input. |
| 2026-10-17 | Codex | Implemented the gain ramp, engine attachment guards, service classification/cancellation, DataStore toggle, Settings UI, and focused JVM tests. Device gate was deferred at the user's request; local Gradle execution could not start because Java/JAVA_HOME is unavailable in this shell. | Record actual source status and outstanding validation without claiming listening results. |
