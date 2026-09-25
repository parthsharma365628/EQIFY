# AutoEQ export and GitHub Pages deployment

The static headphone database is deployed and the Android app now uses it for
headphone search and correction profiles. GitHub Pages cannot serve the separate
genre-detection POST endpoint, so `BuildConfig.BASE_URL` and the Node backend
remain available for that fallback.

## Project map for a docs-only AI

EQify's main repository is
`https://github.com/parthsharma365628/EQIFY` (Kotlin/Jetpack Compose Android
client, `app/`, plus an Express backend, `eqify-backend/`). The separate
`https://github.com/parthsharma365628/eqify-data` repository is intended only
for static converted profile files. AutoEq is the upstream source:
`https://github.com/jaakkopasanen/AutoEq`.

```text
EQIFY/
├─ AI.md                           # Full project architecture and rules
├─ CLOUDFLARE_AUTOEQ_MIGRATION.md  # Deferred Worker + D1 alternative
├─ GITHUB_PAGES_AUTOEQ.md          # This checkpoint and static-data contract
├─ app/src/main/java/com/example/eqify/
│  ├─ EqifyApi.kt                  # Genre and static Pages Retrofit APIs/models
│  ├─ HeadphoneDataRepository.kt   # Cached local search and profile validation
│  ├─ EqProcessingService.kt       # Downloads selected correction, applies EQ
│  ├─ HeadphoneEqDiskCache.kt      # Caches downloaded eight-gain curves
│  └─ screens/HeadphonesScreen.kt  # Debounced search and favorites
└─ eqify-backend/
   ├─ index.js                     # Existing live Node/Express API
   ├─ lib/autoeq-converter.js      # Shared parser and eight-band formula
   ├─ scripts/build-headphone-db.js # Offline, dependency-free Node exporter
   ├─ test/autoeq-converter.test.js
   ├─ autoeq-results/              # Raw source; ignored, not committed
   └─ generated/pages-autoeq-7ae0f56d5307/ # Published build; ignored locally
```

Android uses `BuildConfig.HEADPHONE_DATA_BASE_URL` for the static database and
`BuildConfig.BASE_URL` for `POST /api/v1/genre`. It downloads and caches
`index.json`, searches at most 50 matches locally, then downloads only the
selected profile from its `profilePath`. `HeadphoneEqDiskCache` retains selected
gain arrays for offline reuse. Download success and failure are exposed through
`HeadphoneCorrectionStatus` and shown on the headphone and Home screens.

## Deployment checkpoint (2026-09-25)

- Source: [jaakkopasanen/AutoEq](https://github.com/jaakkopasanen/AutoEq),
  commit `7ae0f56d53074872b028649617a22bbb4232feb7`.
- Local reproducible export:
  `eqify-backend/generated/pages-autoeq-7ae0f56d5307/`. Generated output is
  ignored in the EQIFY application repository and may be absent in another clone.
- Verified report: 8,850 files scanned; 6,028 unique profiles converted;
  2,822 duplicates removed; no unknown filter types. Canonical checksum:
  `ed32807426fdeba7866b88b31205c4f1f5961cfb465a9bc1fca3ba30de40b1cc`.
- Published repository:
  [parthsharma365628/eqify-data](https://github.com/parthsharma365628/eqify-data),
  commit `5e84681`. It includes `README.md`, `LICENSE-AUTOEQ`, and
  `THIRD_PARTY_NOTICES.md` with the generated files.
- Live site: <https://parthsharma365628.github.io/eqify-data/>. GitHub Pages is
  configured from `main` and `/(root)`. The landing page, `index.json`, and a
  referenced profile returned HTTP 200 after deployment.
- The published tree contains 6,028 profile files and six top-level files,
  approximately 3.09 MB total. `index.json` is approximately 1.30 MB.
- Android now fetches headphone data from GitHub Pages. The Node backend remains
  necessary only for the final genre-detection fallback.

### Remaining work

1. Continue reviewing source-specific measurement redistribution terms. AutoEq's
   MIT software license does not automatically settle every measurement source's
   terms; retain attribution with every dataset update.
2. Validate first-run download, cached offline search, selected-profile download,
   cached correction reuse, and failure messages on a device.

## Generate locally (PowerShell, from the EQify repository root)

1. Obtain the [upstream AutoEq repository](https://github.com/jaakkopasanen/AutoEq)
   **outside** EQify. Its `results` folder follows
   `source/type/headphone/headphone ParametricEQ.txt`. For example, in a
   separate parent directory run `git clone --depth 1
   https://github.com/jaakkopasanen/AutoEq.git`, then note the revision with
   `git -C <path-to-AutoEq> rev-parse HEAD`. Alternatively use an existing local
   `eqify-backend/autoeq-results` tree. Review measurement-source redistribution
   rights. Do **not** commit the source tree to EQify or the Pages repository.
2. Run this from the EQify root, replacing the input path and version with
   your actual local `results` path and upstream commit:

   ```powershell
   node .\eqify-backend\scripts\build-headphone-db.js --input "C:\path\to\AutoEq\results" --output .\eqify-backend\generated\pages --version YOUR_AUTOEQ_REVISION
   ```

   Node.js is required; `npm install` is not needed for this script.
   The input path must exist. The output directory must be new or empty; the
   script never deletes or overwrites it. For a fresh run, choose a new output
   path (for example `generated/pages-v2`), then review before publishing.
3. Inspect `index.json`, `conversion-report.json`, and a few profile files under
   the chosen `eqify-backend/generated/` output directory. Confirm the profile count, duplicates,
   unknown filter types, and the eight gain values. Run `npm.cmd install`
   followed by `npm.cmd test` from `eqify-backend` if you want the full backend
   regression tests (PowerShell may block the `npm.ps1` shim).

The generator selects the same first case-insensitive headphone-name match as
the Node server after sorting by name, source, type. It fails on malformed
filters, invalid numeric values, empty input, or unsafe output paths. Unknown
filter types are reported and contribute nothing, matching the current server.
The checksum is over ordered canonical records, independent of generation time.

## Static format (schema version 1)

- `index.json`: `schemaVersion`, `datasetVersion`, `frequenciesHz`, `checksum`,
  and `headphones[]` with `name`, `normalizedName`, `source`, `type`,
  `profilePath`.
- `profiles/<first-two-hash-characters>/<sha256-of-lowercase-name>.json`:
  `schemaVersion`,
  `datasetVersion`, `frequenciesHz`, `name`, `normalizedName`, `source`,
  `type`, `gains` (eight numbers, -12 to +12 dB).
- `conversion-report.json`: local QA/provenance; `index.html`: minimal Pages
  landing page.

The names, source/type metadata, and gains make each profile directly
importable into a future D1 table; the index supports on-device search. Do not
guess filenames from display names—use `profilePath` from the index.

Concrete example from the local checkpoint (the first selected model; the
actual `index.json` contains 6,028 `headphones` entries):

```json
{
  "schemaVersion": 1,
  "datasetVersion": "7ae0f56d53074872b028649617a22bbb4232feb7",
  "frequenciesHz": [60, 170, 310, 600, 1000, 3000, 6000, 12000],
  "checksum": "ed32807426fdeba7866b88b31205c4f1f5961cfb465a9bc1fca3ba30de40b1cc",
  "headphones": [{
    "name": "1Custom SA02",
    "normalizedName": "1custom sa02",
    "source": "crinacle",
    "type": "711 in-ear",
    "profilePath": "profiles/35/350a54565bd769c67e4bb83a9b93cefb7142dc30a085caf72e7d05cbba49135c.json"
  }]
}
```

The JSON at that `profilePath` contains the same schema/version/frequencies
and model metadata, plus `"gains": [-3.1, -7.3, -8.9, -7.7, -6.5, -5.8,
-6.2, -4]`. Gain positions correspond exactly to `frequenciesHz`; do not
sort gains independently. The hash is SHA-256 of the lowercase model name;
the first two hex characters select the shard. `profilePath` is relative to
the Pages site root and must be taken from the index, not reconstructed by a
client. The report checksum hashes the ordered canonical records, so the same
source and conversion behavior should reproduce it.

## Publish manually to a separate GitHub Pages repository

1. Create a **public**, dedicated repository on GitHub, e.g. `eqify-data`.
   Do not put the raw AutoEQ tree into it. Check AutoEq and measurement-source
   attribution and redistribution terms before publishing. Include the
   required notices in that repository.
2. Clone that repository into a directory **outside** this EQify repository.
   Copy the **contents** of the selected generated output
   (`pages-autoeq-7ae0f56d5307` for the published checkpoint) into its root:
   `index.html`, `index.json`, `conversion-report.json`, and `profiles/`.
   Review `git status` and the file sizes, then commit and push only the
   generated static files and required notices. Avoid broad `git add` in EQify.
3. On GitHub, open the data repository's **Settings → Pages**. Under **Build
   and deployment**, select **Deploy from a branch**, branch `main`, folder
   `/(root)`, then **Save**. Wait for the Pages workflow to finish.
4. Open `https://YOUR_USERNAME.github.io/eqify-data/index.json` and one
   `profilePath` URL under the same base. Confirm both show JSON and that the
   landing page loads. The final Pages URL is shown in Settings → Pages.

For subsequent updates, regenerate into a new output directory, compare the
report and checksum, then publish the new files to the data repository.
Remove stale profile files there only after reviewing exactly which generated
paths changed. Never push the source AutoEQ tree or secrets.

The Android integration is implemented in `HeadphoneDataRepository.kt`.
See `CLOUDFLARE_AUTOEQ_MIGRATION.md` for the deferred D1 path.
