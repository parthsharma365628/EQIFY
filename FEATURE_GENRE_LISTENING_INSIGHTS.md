# Feature Plan: Weekly Genre Listening Insights

This is an implementation plan for a lightweight, on-device "genres you
listened to this week" view in the EQify Android app. It was drafted by an AI
(Claude) from EQify's own project documentation (`AI.md`,
`GITHUB_PAGES_AUTOEQ.md`, `CLOUDFLARE_AUTOEQ_MIGRATION.md`) and a
conversation about the codebase — **not from reading the current source
files directly**. Treat every specific class or field named below as a
hypothesis to verify, not a settled fact. Explicit user instructions and the
actual repository state always take priority over this document.

## Read this first: known gaps in this plan

The AI that picks this up should actively look for and correct mistakes
here, not just execute it. Two are already known:

1. **The genre-state assumption is unverified and may be wrong.** This plan
   was originally motivated by the idea that "`NowPlayingState` is already
   tracking [genre] data." That may not be true. `AI.md` describes
   `MediaListenerService` updating `NowPlayingState` from media notifications
   (track/playback metadata), and separately describes the automatic EQ
   pipeline (`observeAutoEqPipeline()` in `EqProcessingService`) as the thing
   that handles genre. The currently-detected genre for a track most likely
   lives in `EqState` or somewhere inside `EqProcessingService`/
   `HomeViewModel`, not in `NowPlayingState` itself. **Do not write any code
   until you've actually located where "current genre for current track"
   lives today.** See Phase 0.
2. **"Low effort" was probably an oversell.** The feature sounds small
   ("a lightweight view") but actually requires new persistence, a logging
   hook wired into a sensitive part of the pipeline, aggregation logic,
   privacy handling, and a new UI surface. Size the real work honestly once
   Phase 0 is done, and say so if it's bigger than a single small change —
   don't force it to stay "lightweight" if the codebase doesn't support that
   cheaply.

If you find other wrong assumptions below, fix them and note what you
changed and why, the same way `AI.md` asks contributors to keep its own
content honest rather than aspirational.

## What this is

A local, glanceable summary of which genres the user's music fell into over
roughly the trailing 7 days — e.g. "Pop 40%, EDM 25%, Rock 20%, Other 15%" —
surfaced somewhere low-friction, most likely a small card on `HomeScreen`.
Not a full listening-history browser, not synced anywhere, not a new major
screen.

## Goals

- A rolling ~7-day, on-device-only summary of genre distribution.
- Cheap to glance at; not a dense analytics screen.
- No new network calls and no server upload — this is listening-pattern
  data and should stay as private as anything else in `SettingsScreen`'s
  existing privacy section.
- Degrades invisibly (empty state, not an error) when there's no data yet or
  when notification-listener permission isn't granted, consistent with how
  `MediaListenerService` already has to handle that permission being absent.

## Non-goals

- Not a full per-track listening history or scrollable log.
- Not cross-device sync — no backend component, no new `EqifyApi` route.
- Must not change behavior of, or add latency/risk to, the existing
  automatic or manual EQ update paths. Those two paths being independent is
  called out in `AI.md` as load-bearing; this feature must not become a
  reason either path gets touched carelessly.
- Not attempting duration-accurate "minutes listened" in v1 unless Phase 0
  shows that's cheap given data already being tracked. Default to an
  event/session-count approximation, and say so honestly in the UI copy
  (e.g. don't imply precision the data doesn't have).
- Don't restyle or refactor unrelated screens while you're in the
  neighborhood.

## Phase 0 — Investigation (do this before writing any feature code)

1. Find where the "genre currently detected for the currently playing
   track" actually lives right now. Check `EqState.kt`, `NowPlayingState.kt`,
   `HomeViewModel.kt`, and `EqProcessingService.kt`
   (`observeAutoEqPipeline()`). Confirm whether it already changes once per
   genre change, or fires repeatedly (e.g. on every poll/recomposition) —
   this determines whether you need dedup logic before logging anything.
2. Confirm what happens to that state when notification-listener permission
   is absent (per `AI.md`, this must already be handled "gracefully" for
   `MediaListenerService`). Your logging hook should naturally go quiet in
   the same situation rather than needing its own special-case check.
3. Check `app/build.gradle.kts` for existing local-persistence dependencies
   (Room, SQLDelight, or similar) before assuming you need to add one.
4. Read `UserPreferencesRepository.kt` to see the existing DataStore
   conventions (naming, serialization) so any new persistence follows the
   same pattern rather than inventing a second one.
5. Skim `app/src/test/` for existing test conventions to match.
6. Re-read the "Where to make a change" table near the end of `AI.md`. If
   this feature's natural home doesn't fit an existing row, add a new row
   once you're done, per that file's own instruction to keep it current.

## Proposed approach (a starting hypothesis — adjust after Phase 0)

### Data model

An append-only local event log: `(timestampEpochMillis, genre, source)`,
written once per **genre change** for the current track — not once per
detection call or poll, to avoid a single long listen flooding the log with
duplicate rows.

- **Storage**: use Room if it's already a dependency, or if adding it is
  clearly reasonable after Phase 0. Otherwise, extend the existing DataStore
  boundary with a small, size-capped list rather than introducing a second
  persistence mechanism. Prefer whichever needs fewer new moving parts, and
  record the decision (and why) in a short comment or a doc update.
- **Retention**: keep only what's needed for the display window plus a
  small buffer (e.g. 8–9 days), and prune older rows opportunistically (app
  start, or lazily on write). Do not let this grow unbounded — this app
  already has patterns for capped/bounded local caches
  (`HeadphoneEqDiskCache`); follow a similar spirit.

### Logging hook

Hook into wherever Phase 0 finds the genre actually changes in the
automatic pipeline, firing only on an actual value change. The write must be
fire-and-forget and must never be able to block, throw into, or delay the
manual or automatic EQ paths — a missed log entry is an acceptable failure
mode; a stalled slider or delayed correction is not.

### Aggregation

A pure, easily-unit-testable function: `(events, now) -> per-genre counts or
percentages within the trailing 7 days`. Cover in tests:

- Empty history.
- A single genre only.
- Events exactly at the 7-day boundary (in and out).
- A genre that's no longer in the app's current genre list (data from a
  since-removed/renamed genre shouldn't crash the aggregation).

### UI

- Prefer a small card on `HomeScreen` over a new screen, unless Phase 0/a
  quick look shows `HomeScreen` is already crowded. Use existing `ui/theme`
  tokens and match the current card-based dark interface — don't introduce
  new colors or styles for this.
- Keep the presentation simple: top 3–4 genres by share as short labeled
  bars or percentages. Don't pull in a charting library for this — Compose
  can draw a handful of simple bars natively.
- A clear, friendly empty state for "no data yet" and for "notification
  access not granted" — this should read as normal, not broken.
- A way to clear this data, ideally colocated with `SettingsScreen`'s
  existing cache/privacy section, matching that section's existing pattern.

## Privacy

- Local-only, on-device, no new network calls, no path through `EqifyApi`.
- Mention it explicitly in `SettingsScreen`'s existing privacy-information
  copy.
- Give the user a clear/reset action for this specific data, separate from
  (or alongside) any existing cache-clearing option.

## Testing

- Unit tests for the aggregation function (JVM, `app/src/test/`), covering
  the boundary cases listed above.
- If new persistence is added, a minimal test of the write/prune logic.
- Manual check: confirm no change to EQ/audio behavior with this feature
  deliberately broken or erroring — it must fail silently, not visibly.

## Acceptance checklist

- [ ] Phase 0 investigation actually completed and its findings noted
      (what genre state looks like today — confirmed or corrected from this
      doc's assumption)
- [ ] No new dependency added without first checking what already exists
- [ ] No regression to the two-path EQ update separation, foreground
      service lifetime, or manual-slider responsiveness
- [ ] Feature is inert/harmless when notification-listener permission is
      absent
- [ ] Data is local-only, size/time-capped, and user-clearable
- [ ] Aggregation logic is unit-tested per the cases above
- [ ] `AI.md`'s "Where to make a change" table updated if this introduces a
      new source-of-truth location
- [ ] UI matches existing card-based dark theme; no unrelated screens
      touched

## Open questions left for the implementing AI (not guessed here)

- The real current shape of genre state (Phase 0).
- Event/session-count vs. duration-based "listened to" — default to
  event-count for v1 unless duration tracking turns out to already be cheap.
- Exact placement on `HomeScreen` without crowding it.
- Whether Room (or an equivalent) is already a dependency.

## Note on how this plan was produced

This was written without direct access to the live repository in this
session — no working GitHub connector was available, and a Firecrawl-based
search returned nothing for this small, unstarred repo. Everything above
comes from the project's own documentation and general Android/Compose
conventions, not from reading the current source. Treat this whole document
as a proposal to validate against the real code, not a spec to execute
blindly — which is the same standard `AI.md` already asks of anyone working
in this repository.
