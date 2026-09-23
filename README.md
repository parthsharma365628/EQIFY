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

See [AI.md](AI.md) for architecture and repository guardrails. Static-data and
Cloudflare documents are future migration plans, not deployed app behavior.

Made by Parth Sharma and Shreyansh Mangal.
