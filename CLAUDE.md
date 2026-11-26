# CLAUDE.md

Short rules for AI assistants. The full architecture is in [AI.md](AI.md): read
the sections you need before changing code. What's built and what's validated is
in [STATUS.md](STATUS.md). Explicit user instructions always win.

## Building and testing

- Use `scripts/build.ps1`. It sets `JAVA_HOME` and the temp-path workaround this
  machine needs. Run it **outside the sandbox**: Gradle needs a loopback socket.
  - `.\scripts\build.ps1 test`: JVM unit tests, with a pass/fail count.
  - `.\scripts\build.ps1 debug`: debug APKs (phone and watch).
  - `.\scripts\build.ps1 release`: tests, then signed release APKs for both modules.
  - `.\scripts\build.ps1 verify`: checks both release APKs have the same app ID,
    version and signing key, and copies them to `EQify-<version>.apk` and
    `EQify-Wear-<version>.apk`.
- After a code change, run at least `test`. Before a release, run `release`, then `verify`.

## Rules that keep features from breaking each other

1. **Two EQ paths stay independent.** The automatic pipeline and the manual slider
   path in `EqProcessingService` both use the same gain chain:
   tone → correction → bass → loudness → level match → protection.
   The manual path never waits on the network.
2. **One media-volume receiver**, in `EqProcessingService`. Never register another;
   add your feature to the monitor's "needed" condition.
3. **Bass goes through `DeviceSoundCoordinator.setBass`**, never `EqState.setBassBoost`.
4. **Per-channel gain has one writer** (`EqEngine`: balance + Orbit).
5. **Outside surfaces update from `ExternalStatus.refresh()`** (widget, tiles, watch).
6. **Every new user setting** gets a key in `UserPreferencesRepository` and, unless it
   is device-specific or security-sensitive, an entry in `BackupCodec.SETTINGS`.
7. **Never rename a shipped manifest component.** `com.example.eqify.MediaListenerService`
   and `com.example.eqify.EqQuickSettingsTileService` keep their old names on purpose.
   The app ID `com.liteplane.eqify` is permanent.
8. **Keep pure logic pure and unit-tested**, and keep Android code thin around it.
9. Preference flows use `pref { }`. `combine` takes at most 5 flows: nest them.
10. Old libraries: OkHttp 3 (`response.code()`, `response.body()`) and Gson 2.8
    (`JsonParser().parse()`).

## Product rules

- **Automatic first, don't overwhelm.** New options start off or "Same as default".
- **Local first:** no account, no analytics. Anything that leaves the phone goes in
  `PRIVACY.md` (keep the copy in `eqify-releases` identical).
- **Honest copy.** Estimates say "estimated"; stats count detected songs, not time.
- Plain-English UI and docs: short sentences, no jargon.

## Workflow

- **Plans:** new features get a plan from [PLAN_TEMPLATE.md](PLAN_TEMPLATE.md), named
  `FEATURES_BATCH<n>_PLAN.md`. List shared paths and how features avoid each other.
  Record changes from the plan in its correction log.
- **Batches:** keep them to 2–4 features, and device-test before starting the next.
- **After each implementation:**
  - Update `STATUS.md`, plus `AI.md` for new rules.
  - Give a commit title and description.
- **Commits:** end every commit message with the attribution line from the session.
  Commit or push only when asked.
- **Never commit** keystores, `local.properties`, `.idea`, build output, the AutoEQ
  dataset, `.env` or the Sentry DSN.
  - Signing passwords live only in `%USERPROFILE%\.gradle\gradle.properties`.
- **Don't modify `EQify_PRD.docx`.**
- **Releases:** follow `RELEASING.md`. Release notes go in `eqify-releases/release-notes/`.
- **Parallel work:** when two features touch different files, they can be built in
  separate git worktrees or sessions. Never let two sessions edit the same file.
