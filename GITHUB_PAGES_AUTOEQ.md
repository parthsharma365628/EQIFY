# Offline AutoEQ export for GitHub Pages

This is a static-data prototype. It does **not** change the Android app's existing
`/api/headphones` and `/api/eq/:headphoneName` API calls, and GitHub Pages cannot
serve the separate genre-detection POST endpoint. Keep the Node backend running
until a client migration is explicitly implemented.

## Resume here — checkpoint (2026-09-16)

- Source: [jaakkopasanen/AutoEq](https://github.com/jaakkopasanen/AutoEq).
  The local input used was `D:\eqify\eqify-backend\autoeq-results`. Its exact
  upstream revision has **not** been recorded; `local-autoeq-snapshot` is only
  a temporary label, not a commit ID.
- The corrected, sharded export exists at
  `eqify-backend/generated/pages-v2/` in the EQify workspace. **Publish this
  directory's contents**, not the earlier flat `pages/` output. Both the raw
  input and generated output are Git-ignored in the app repository.
- Verified report: 8,850 files scanned; 6,028 unique profiles converted;
  2,822 duplicate names removed; no unknown filter types. There are 6,028
  profile JSON files in 256 hash-prefix subdirectories. Canonical checksum:
  `ed32807426fdeba7866b88b31205c4f1f5961cfb465a9bc1fca3ba30de40b1cc`.
- Target data repository: [parthsharma365628/eqify-data](https://github.com/parthsharma365628/eqify-data).
  Its contents were not verified here. No generated files have been copied,
  committed, pushed, or published by this workflow; check the remote repository
  before assuming it is empty or unpublished.
- The Android app has **not** been changed to fetch from GitHub Pages. The Node
  backend remains necessary, including for genre detection.

### Remaining steps, in order

1. Review AutoEq's license/attribution and the terms of the underlying
   third-party measurement sources before public redistribution. Add required
   notices to `eqify-data`. Do not assume AutoEq's software MIT license settles
   the rights for every measurement source. If possible, identify the exact
   AutoEq source revision; otherwise keep the snapshot label honest.
2. Confirm `pages-v2/conversion-report.json` still has the counts and checksum
   above. Spot-check `index.json` and several `profilePath` targets. Do not
   regenerate unless the input or exporter changes; if regenerating, choose a
   new empty output directory and compare reports before replacing anything.
3. Clone `eqify-data` **outside** the EQify app repository. Inspect its existing
   files and branch. If it is empty, copy only the contents of `pages-v2` to its
   root. If it is not empty, reconcile changes first; do not overwrite blindly.
4. Review `git status` in `eqify-data`. Commit/push only `index.html`,
   `index.json`, `conversion-report.json`, `profiles/`, and required notices.
   Do **not** upload `autoeq-results`, `node_modules`, secrets, or the whole
   EQify repository. GitHub's browser uploader is unsuitable for 6,028 files.
5. In `eqify-data` on GitHub, configure **Settings → Pages → Deploy from a
   branch**, using the pushed branch (usually `main`) and `/(root)`. Wait for
   deployment and check `https://parthsharma365628.github.io/eqify-data/index.json`
   plus at least one profile URL referenced by `profilePath`.
6. Only in a separately requested app change, implement local index caching,
   search, profile download, error/offline behavior, and a retained genre API.

PowerShell commands for step 3-4 **if the new repository is empty**, run one
line at a time from any directory:

```powershell
git clone https://github.com/parthsharma365628/eqify-data.git D:\eqify-data
Copy-Item -Path 'D:\EQIFY GITHUB\EQIFY\eqify-backend\generated\pages-v2\*' -Destination 'D:\eqify-data' -Recurse
git -C D:\eqify-data status --short
git -C D:\eqify-data add -- index.html index.json conversion-report.json profiles
git -C D:\eqify-data commit -m "Publish converted AutoEq headphone profiles"
git -C D:\eqify-data push origin main
```

Before `git add`, include and review any required attribution files, and stage
those explicitly too. If `D:\eqify-data` already exists or the remote branch
is not `main`, adapt the commands after inspecting that repository. Do not
repeat `git clone` into an existing directory.

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

## Publish manually to a separate GitHub Pages repository

1. Create a **public**, dedicated repository on GitHub, e.g. `eqify-data`.
   Do not put the raw AutoEQ tree into it. Check AutoEq and measurement-source
   attribution and redistribution terms before publishing. Include the
   required notices in that repository.
2. Clone that repository into a directory **outside** this EQify repository.
   Copy the **contents** of the selected generated output (`pages-v2` for the
   checkpoint above) into its root:
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

This does **not** yet make the Android app read Pages. A later, separately
requested client change must download/cache `index.json`, search locally, fetch
selected profiles by `profilePath`, retain offline/error behavior, and keep a
working genre API. See `CLOUDFLARE_AUTOEQ_MIGRATION.md` for the deferred D1 path.
