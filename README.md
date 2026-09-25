# EQify

Android equalizer combining automatic genre tone profiles, headphone-specific
AutoEQ correction, bass boost, manual eight-band control, and output protection.
The client uses Kotlin and Jetpack Compose; the companion backend uses
Node.js/Express.

## Development

From the repository root on Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
Set-Location eqify-backend
npm.cmd install
npm.cmd start
```

The default Android backend URL, `http://10.0.2.2:3000/`, is for an emulator.
A physical device needs a reachable server URL configured through the
`EQIFY_BASE_URL` Gradle property. The large AutoEQ source dataset, generated
exports, build output, IDE state, and secrets must remain uncommitted.
The static headphone source defaults to the deployed Pages site and can be
overridden with `EQIFY_HEADPHONE_DATA_BASE_URL`.

See [AI.md](AI.md) for architecture and repository guardrails. The headphone
database is published separately through GitHub Pages. Android caches its index,
searches it locally, and downloads only the selected profile's eight gains.
Cloudflare remains a future migration plan.

Made by Parth Sharma and Shreyansh Mangal.
