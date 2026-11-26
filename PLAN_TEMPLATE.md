# Batch N: plan

<!-- Copy to FEATURES_BATCH<n>_PLAN.md. Keep it short; delete these comments. -->

Chosen YYYY-MM-DD. Target: **0.X.0-beta** (versionCode N). Plan only until built.

| # | Feature | Effort |
|---|---|---|
| A | … | S / M / L |

<!-- Effort: S = days, M = 1–2 weeks, L = 2–4 weeks. Keep a batch to 2–4 features. -->

## Before starting

- The previous batch is device-tested (`STATUS.md`).
- `.\scripts\build.ps1 test` passes on the current tree.
- Anything else to check first: research, an experiment on the phone, a decision for the user.

## Shared paths and how features stay apart

<!-- The most important section. For each place two features (or a new and an old
feature) touch the same code, state who owns it and the rule. Check the existing
rules in CLAUDE.md and AI.md first. -->

1. **<shared thing, e.g. media volume>:** who owns it, and the rule.
2. **Gain chain:** where a new layer goes, and which paths use it.
3. **Backup:** new keys to add to `BackupCodec.SETTINGS`, and what is never backed up.
4. **Outside surfaces:** do the widget, tiles or watch need updating through `ExternalStatus`?

## A. <Feature>

- **What the user sees:** where it lives, and its default (off, or "Same as default").
- **How it works:** pure logic file and functions, and the Android side.
- **Edge cases:** …
- **Privacy:** anything new that leaves the phone, or a new permission. If so, update `PRIVACY.md` in both repos.
- **Tests:** the pure functions to unit-test, and what to check on a phone.

## Order

1. Shared pieces first.
2. Features, smallest first.
3. Tests, `.\scripts\build.ps1 all`, then docs (`STATUS.md`, `AI.md`, release notes).

## Defaults chosen (say if you disagree)

1. …

---

## Correction log

| Date | Who | Change | Why |
|---|---|---|---|
| YYYY-MM-DD | | Built as 0.X.0-beta. N tests pass. Not yet checked on a phone. | — |
