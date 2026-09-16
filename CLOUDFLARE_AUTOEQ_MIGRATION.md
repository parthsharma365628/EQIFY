# Cloudflare AutoEQ Migration Plan

This document is a future implementation guide for moving EQify's headphone-correction service to Cloudflare while staying within the free tier where practical. It is documentation only: do not create Cloudflare resources, migrate production traffic, or delete the current backend unless the user explicitly requests that work.

Explicit user instructions and the current repository state take priority over this plan. Before acting, inspect the branch, remote, working tree, installed package versions, current Cloudflare documentation, and the target Cloudflare account/environment.

## Handoff snapshot (2026-09-16; re-check before acting)

This plan was drafted before an intermediate GitHub Pages export was built.
The current source repository is
`https://github.com/parthsharma365628/EQIFY` (`main` included converter commit
`fe3310f` at this checkpoint). Its present files relevant to this migration are:

```text
EQIFY/
├─ AI.md                           # Project-wide architecture and file map
├─ GITHUB_PAGES_AUTOEQ.md          # Static export contract and resume checklist
├─ CLOUDFLARE_AUTOEQ_MIGRATION.md  # This deferred D1/Worker plan
├─ app/
│  ├─ build.gradle.kts              # One configurable EQIFY_BASE_URL
│  └─ src/main/java/com/example/eqify/
│     ├─ EqifyApi.kt                # Retrofit paths and JSON models
│     ├─ EqProcessingService.kt     # Fetch/compose/apply headphone correction
│     ├─ HeadphoneEqDiskCache.kt    # Last successful correction on device
│     └─ screens/HeadphonesScreen.kt # Search/favorites/selection UI
└─ eqify-backend/
   ├─ index.js                      # Current Express routes; reads raw files
   ├─ lib/autoeq-converter.js       # Current shared parser and DSP conversion
   ├─ scripts/build-headphone-db.js # Current offline JSON exporter
   ├─ test/{index,autoeq-converter}.test.js
   ├─ autoeq-results/               # Raw local input; ignored, not in Git
   └─ generated/pages-v2/          # Ignored local JSON export, not in Git
```

**Implemented:** The live Node API now calls the shared converter in
`lib/autoeq-converter.js`; the CLI produces a deterministic, sharded static
JSON export for a prospective GitHub Pages host. A local run used
`D:\eqify\eqify-backend\autoeq-results` and converted 6,028 unique profiles
from 8,850 candidate files, removing 2,822 duplicate names. Its canonical
record SHA-256 is
`ed32807426fdeba7866b88b31205c4f1f5961cfb465a9bc1fca3ba30de40b1cc`.
The label `local-autoeq-snapshot` is **not** a known upstream AutoEq revision.
The ignored export may be absent from another clone.

**Not implemented:** No GitHub Pages deployment is confirmed; Android still
calls the Node API. There is no D1 schema/import, Worker code/configuration,
Cloudflare resource, production cutover, or SQL dump. Do not interpret the
later proposed `cloudflare-worker/` tree or SQL example as existing files.
The separate intended static repository is
`https://github.com/parthsharma365628/eqify-data`; inspect its actual state.

**Current next decision:** Complete the GitHub Pages licensing and publishing
checkpoint in `GITHUB_PAGES_AUTOEQ.md` if requested. Begin the Worker + D1
phases only if the user later chooses that migration. No Cloudflare deployment
is needed merely to preserve the current app behavior.

## Decision summary

Recommended initial architecture:

```text
Local AutoEQ results (never committed)
        |
        | offline conversion
        v
Generated eight-band records + validation report
        |
        | controlled import
        v
Cloudflare D1 (queryable headphone records)
        |
        | D1 binding
        v
Cloudflare Worker (existing EQify API contract)
        |
        v
Android app + existing on-device correction cache
```

Use one Worker and D1 first. Do not add R2, KV, Queues, Durable Objects, or a paid custom domain unless a measured requirement justifies them.

- D1 fits small, searchable records.
- Workers provides the public HTTPS API.
- The Android disk and memory caches already reduce repeat correction requests.
- The raw AutoEQ tree should be converted offline, not scanned or parsed inside a Worker request.
- R2 is optional later for raw archives or versioned exports; it is not required for the first implementation.

## Goals

1. Preserve the Android API contract and existing user-visible behavior.
2. Convert each selected AutoEQ profile into EQify's eight logical band gains before upload.
3. Keep the raw AutoEQ dataset and generated bulk import files out of Git.
4. Make imports reproducible, validated, versioned, and safe to repeat.
5. Stay within Cloudflare's free plan for the expected early workload.
6. Retain a tested rollback path until the Cloudflare service is proven.

## Non-goals for the first phase

- Do not ship the raw AutoEQ dataset inside the Android application.
- Do not bundle the raw dataset into Worker source.
- Do not parse ParametricEQ files at request time.
- Do not change EQ curves, target frequencies, duplicate-selection behavior, or clamping rules during migration.
- Do not redesign the Android headphone UI.
- Do not migrate genre detection in the same first change unless required to test a single production base URL.
- Do not add R2 merely because it is available.

## Current repository findings

### Existing Node backend

The backend lives in `eqify-backend/` and currently uses:

- Node.js and CommonJS;
- Express 4;
- `cors`;
- `dotenv`;
- `node-fetch` 2;
- Node's built-in test runner through `npm test`.

`eqify-backend/index.js` currently owns several unrelated responsibilities:

1. Last.fm and iTunes genre lookup.
2. Keyword-to-genre fallback mapping.
3. A module-level, 24-hour in-memory artist genre cache.
4. Recursive AutoEQ directory scanning.
5. Headphone-name deduplication.
6. ParametricEQ parsing and eight-band conversion, now delegated to
   `lib/autoeq-converter.js`.
7. All Express route handlers and server startup.

The current AutoEQ root is:

```text
eqify-backend/autoeq-results/
```

It is already ignored by Git. The server expects profiles resembling:

```text
autoeq-results/<source>/<type>/<headphone>/<headphone> ParametricEQ.txt
```

The scan only includes a directory when its matching `ParametricEQ.txt` exists. Results are sorted by headphone name, source, and type. Deduplication then keeps the first case-insensitive occurrence of each name. This order is observable behavior and must remain deterministic during migration.

The module-level `headphoneCache` avoids rescanning after the first request, but this design assumes a persistent local filesystem and a long-lived Node process. It is not the desired Worker runtime model.

### Existing routes

The current public contract is:

```text
GET  /api/headphones?search=<text>
GET  /api/eq/:headphoneName
POST /api/v1/genre
GET  /api/cache/stats
```

Current headphone-route behavior:

- Search is trimmed and case-insensitive.
- Search matches text anywhere in the headphone name.
- Results are deduplicated by lowercase name.
- Results are limited to 50.
- Search responses contain `{ name, type }`.
- EQ lookup first attempts exact case-sensitive name matching, then case-insensitive matching.
- Missing headphones return HTTP 404.
- Successful EQ responses contain the headphone name and eight `{ frequencyHz, gainDb }` entries.

Current genre-route priority:

1. In-memory cache.
2. Last.fm when a key is available.
3. iTunes metadata.
4. Keyword matching.
5. `Pop` fallback.

The module-level genre cache cannot be treated as durable or globally consistent in Workers. Handle genre migration separately.

### Android contract and caching

`app/src/main/java/com/example/eqify/EqifyApi.kt` defines the Retrofit models and paths. Do not change their JSON shape during the initial migration:

```kotlin
data class HeadphoneResponse(val name: String, val type: String)
data class EqBandResponse(val frequencyHz: Int, val gainDb: Float)
data class HeadphoneEqResponse(
    val headphone: String,
    val bands: List<EqBandResponse>
)
```

The app searches after a 300 ms debounce and cancels stale requests with `collectLatest`. It combines API results with local favorites and deduplicates names case-insensitively.

Fetched corrections are cached twice:

- in memory inside `EqProcessingService`;
- on disk through `HeadphoneEqDiskCache`.

On a backend failure, EQify keeps working with a flat headphone correction and retries later. Preserve this graceful failure behavior.

The API base URL comes from `BuildConfig.BASE_URL`; the Gradle property is `EQIFY_BASE_URL`. Retrofit requires a trailing `/`.

## Existing conversion algorithm

The current algorithm lives in `eqify-backend/lib/autoeq-converter.js` and is
imported by `index.js` and the offline generator. Reuse it; do not silently
replace it with a different DSP interpretation during infrastructure migration.

The target frequencies are:

```text
60, 170, 310, 600, 1000, 3000, 6000, 12000 Hz
```

### Parsing

For each `ParametricEQ.txt`:

1. Read `Preamp:`; default to `0` if missing or invalid.
2. Find filter lines containing both `Filter` and `Fc`.
3. Read filter type, center frequency (`Fc`), gain, and Q.
4. Default Q to `1.0` if it is absent.
5. The live parser retains the legacy behavior for malformed filters; the
   offline exporter additionally rejects malformed/non-finite data before
   publishing. Re-check both code paths before changing this contract.

Current recognized types:

- peaking: `PK`, `PEAKING`;
- low shelf: `LS`, `LSC`, `LOW_SHELF`;
- high shelf: `HS`, `HSC`, `HIGH_SHELF`.

Unknown filter types currently contribute nothing. The existing generator
counts them in `conversion-report.json` instead of expanding DSP behavior.

### Converting to eight bands

For a peaking filter, the current approximation uses logarithmic frequency distance:

```javascript
sigma = (Math.LOG2E / q) * 0.9
distance = Math.abs(Math.log2(targetHz / filterHz))
contribution = gain * Math.exp(-0.5 * Math.pow(distance / sigma, 2))
```

Low- and high-shelf filters contribute their full gain on the shelf side and use the existing logarithmic exponential decay on the opposite side.

After summing all filters for a target band:

1. Add the file preamp.
2. Clamp to `-12 dB` through `+12 dB`.
3. Round to one decimal place.
4. Require exactly eight finite values.

This is an approximation designed around EQify's eight logical bands, not a general parametric-EQ engine.

## Present and proposed structure

The Node service and `lib/`, `scripts/`, and `test/` files already exist.
Everything under `cloudflare-worker/` below is proposed, not present:

```text
eqify-backend/
|-- index.js
|-- lib/
|   `-- autoeq-converter.js
|-- scripts/
|   `-- build-headphone-db.js
|-- test/
|   |-- index.test.js
|   `-- autoeq-converter.test.js
|-- cloudflare-worker/
|   |-- package.json
|   |-- package-lock.json
|   |-- wrangler.jsonc
|   |-- tsconfig.json
|   |-- worker-configuration.d.ts   # generated by Wrangler
|   |-- migrations/
|   |   `-- 0001_create_headphones.sql
|   |-- src/
|   |   `-- index.ts
|   `-- test/
|       `-- index.test.ts
`-- generated/                       # ignored; local only
    |-- pages-v2/                    # existing static JSON export when local
    `-- headphones.sql               # proposed D1 import artifact, not built
```

Commit source code, migration files, lockfiles, small test fixtures, and documentation. Do not commit:

- `eqify-backend/autoeq-results/`;
- `eqify-backend/generated/` bulk output;
- Worker local state such as `.wrangler/`;
- `.dev.vars`, `.env`, credentials, tokens, or account IDs that should remain private;
- `node_modules/`.

`.gitignore` already excludes `eqify-backend/autoeq-results/` and
`eqify-backend/generated/`. A future Worker project must also exclude its
local state and secrets.

## Proposed D1 schema

Use ordinary numeric columns to make validation and response construction simple:

```sql
CREATE TABLE headphones (
    name TEXT PRIMARY KEY COLLATE NOCASE,
    normalized_name TEXT NOT NULL,
    type TEXT NOT NULL,
    source TEXT NOT NULL,
    gain_60 REAL NOT NULL,
    gain_170 REAL NOT NULL,
    gain_310 REAL NOT NULL,
    gain_600 REAL NOT NULL,
    gain_1000 REAL NOT NULL,
    gain_3000 REAL NOT NULL,
    gain_6000 REAL NOT NULL,
    gain_12000 REAL NOT NULL,
    dataset_version TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_headphones_normalized_name
ON headphones(normalized_name);

CREATE TABLE dataset_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
```

Suggested metadata:

- AutoEQ source commit or release identifier;
- generation timestamp;
- converter version;
- target frequency list;
- converted count;
- duplicate count;
- skipped/failed count;
- checksum of the generated canonical records.

Do not hand-edit production records. Regenerate them from a documented source revision.

## Phase 0: preflight

Before editing:

1. Read `AI.md`, this document, and the current code.
2. Inspect Git branch, remote, status, and diffs.
3. Confirm the AutoEQ directory exists locally without adding it to Git.
4. Record the AutoEQ source revision or dataset release.
5. Confirm Node and npm versions.
6. Confirm current Cloudflare login with the project-local Wrangler version once installed.
7. Re-check current Workers and D1 free-tier limits and import restrictions.
8. Decide the exact staging and production resource names before creating them.
9. Confirm redistribution and attribution requirements for the selected AutoEQ sources.

Do not assume a successful Cloudflare account login means the intended account or environment is selected.

## Phase 1: shared conversion logic (implemented; audit before D1 work)

`eqify-backend/lib/autoeq-converter.js` already contains reusable logic:

- target frequencies;
- ParametricEQ text parsing;
- eight-band conversion;
- the CLI performs export validation and uses lowercase names for stable
  deduplication and filenames. A future D1 importer still needs explicit
  record validation and normalized-name handling.

The shared parser accepts text; filesystem reading remains in callers. This
allows small committed fixtures and deterministic unit tests.

`index.js` retains compatibility exports for existing tests. The Node service
must continue to return the same gains; compare representative API outputs
before any further parser changes.

The committed tests cover basic conversion, deterministic selection, output
shape, and refusal to overwrite an existing output. Before D1 import, extend
tests for:

- preamp parsing;
- PK, low-shelf, and high-shelf filters;
- absent Q default;
- malformed filters;
- unknown filter reporting;
- exactly eight outputs;
- one-decimal rounding;
- `-12`/`+12` clamping;
- deterministic results for a committed synthetic fixture.

Stop if the refactor changes existing outputs. Infrastructure migration must not hide a DSP change.

## Phase 2: offline JSON generator (implemented); future D1 importer

`eqify-backend/scripts/build-headphone-db.js` already accepts `--input`,
`--output`, and `--version` and writes `index.json`, a per-model JSON file
under `profiles/<hash-prefix>/`, a report, and `index.html`. See
`GITHUB_PAGES_AUTOEQ.md` for the exact schema and local checkpoint. It does
**not** generate SQL, `generatedAt`, or an import-ready D1 dump.

Existing JSON workflow:

1. Accept the AutoEQ root as an explicit CLI argument or documented environment variable.
2. Refuse broad or missing filesystem paths.
3. Recursively discover only matching `ParametricEQ.txt` files.
4. Sort candidates using the current `name`, `source`, `type` ordering.
5. Deduplicate case-insensitively, preserving the current first-result rule.
6. Parse and convert each selected record.
7. Validate every output.
8. Generate deterministic JSON and a machine-readable report. A future D1
   phase must convert these validated records into bounded SQL batches or
   another explicit importer; do not assume SQL exists now.
9. Exit nonzero on malformed filters, invalid gains, missing input, unsafe
   paths, or incomplete metadata. Add collision detection appropriate to the
   chosen D1 key before import.
10. Print a concise summary without dumping the dataset into logs.

Each generated record must have:

- non-empty display name;
- stable normalized name;
- type and source;
- eight finite gains in the correct order;
- every gain within `[-12, 12]`;
- dataset version; generation timestamps are **not** in schema version 1, so
  the future importer must record an import timestamp separately if needed.

Future D1 importer: use one prepared/import-safe insert per row or reasonably
sized batches. Do not generate one unbounded SQL statement. Escape data
correctly and check then-current D1 import limits.

Current JSON report shape (the local checkpoint has concrete counts in
`GITHUB_PAGES_AUTOEQ.md`):

```json
{
  "datasetVersion": "autoeq-commit-or-release",
  "schemaVersion": 1,
  "filesScanned": 0,
  "profilesSelected": 0,
  "profilesConverted": 0,
  "duplicatesRemoved": 0,
  "unknownFilterTypes": {},
  "checksum": "sha256"
}
```

The report is ignored with the generated output in the app repository. A
future SQL dump must also remain ignored. The report's checksum covers
ordered canonical records, not a publish timestamp.

## Phase 3: local acceptance gate

Do not create D1 until the local export is measured.

Required checks:

1. Existing backend tests still pass.
2. New converter/generator tests pass.
3. Generation completes without unexpected failures.
4. Every row has exactly eight valid gains.
5. Case-insensitive names are unique.
6. Counts reconcile: selected = converted + failed.
7. Before D1 work, generate and measure SQL/import batches against the
   then-current D1 free-plan and import limits; the current exporter has no SQL.
8. Re-running against unchanged inputs produces the same canonical record checksum.
9. Spot-check known headphones, including Sony WH-1000XM5.
10. Compare generated output with the current `/api/eq/:headphoneName` result for representative PK/shelf/preamp profiles.

Record the actual counts and size in the implementation handoff. Do not estimate them when the dataset is available locally.

## Phase 4: create the Worker project

Create a separate Worker project under `eqify-backend/cloudflare-worker/` so the current Node service remains available for parity tests and rollback.

Guidelines:

- Install and pin Wrangler locally as a development dependency; use the lockfile.
- Prefer TypeScript with a module `fetch` handler.
- Prefer `wrangler.jsonc` for new configuration.
- Set `compatibility_date` to the implementation date, then review current compatibility changes.
- Use `nodejs_compat` if the chosen dependencies need Node APIs.
- Generate binding/runtime types with `wrangler types`; do not hand-write a duplicate `Env` interface.
- Enable Workers Logs and Traces with appropriate sampling.
- Use structured JSON logs and do not log entire records, secrets, or user-provided music metadata.
- Use the D1 binding from `env`, never the Cloudflare REST API from inside the Worker.
- Bind all user input through D1 prepared statements; never interpolate it into SQL.

Illustrative configuration shape only; verify it against the installed Wrangler schema before committing:

```jsonc
{
  "$schema": "node_modules/wrangler/config-schema.json",
  "name": "eqify-api",
  "main": "src/index.ts",
  "compatibility_date": "SET_TO_IMPLEMENTATION_DATE",
  "compatibility_flags": ["nodejs_compat"],
  "d1_databases": [
    {
      "binding": "DB",
      "database_name": "eqify-headphones-staging",
      "database_id": "SET_FROM_WRANGLER_OUTPUT"
    }
  ],
  "observability": {
    "enabled": true,
    "logs": { "enabled": true, "head_sampling_rate": 1 },
    "traces": { "enabled": true, "head_sampling_rate": 0.01 }
  }
}
```

Never guess or copy a database ID from another environment.

## Phase 5: create staging D1 and apply schema

Use separate staging and production databases if the current free plan permits it. Re-check command syntax with the pinned Wrangler version:

```powershell
npx wrangler --version
npx wrangler whoami
npx wrangler d1 --help
npx wrangler d1 create eqify-headphones-staging
npx wrangler d1 migrations apply eqify-headphones-staging --remote
npx wrangler types
```

Important distinctions:

- Local D1 data does not automatically update remote D1.
- `--remote` changes a Cloudflare resource; confirm the exact database first.
- Staging bindings do not automatically establish production bindings.
- Dashboard edits may be overwritten by Wrangler configuration.

After schema creation, query table metadata before importing any records.

## Phase 6: import the generated dataset into staging

Re-check the current D1 import/export documentation and pinned CLI help before running the import. A typical command is:

```powershell
npx wrangler d1 execute eqify-headphones-staging --remote --file=..\generated\headphones.sql
```

Before running it:

1. Verify the generated file's resolved path is inside the intended project output directory.
2. Verify its checksum matches the report.
3. Verify the target says `staging`.
4. Export or preserve any existing staging data if it matters.
5. Do not place secrets in command arguments or logs.

After import, verify:

- total row count;
- distinct normalized-name count;
- metadata values;
- min/max gain for every band;
- no null required fields;
- known headphone records;
- query plans for exact lookup and search.

## Phase 7: implement Worker routes

Implement the headphone routes first.

### `GET /api/headphones?search=<text>`

Preserve:

- trimmed, case-insensitive matching;
- substring semantics unless an Android/API change is explicitly approved;
- alphabetical deterministic output;
- maximum 50 results;
- JSON objects containing only `name` and `type`.

Leading-wildcard substring SQL can scan many rows and consume D1 row-read allowance. Start by preserving behavior, measure it, cache repeated queries, and inspect query plans. Do not quietly change substring matching to prefix-only matching. A later optimization could serve a versioned static catalog for on-device search, but that is a separate Android/API change.

Reject unreasonable query length and escape SQL wildcard characters intentionally. Bind the final search value as a prepared-statement parameter.

### `GET /api/eq/:headphoneName`

Preserve:

- URL-decoded name lookup;
- case-insensitive exact matching;
- HTTP 404 for missing profiles;
- the existing Retrofit response shape;
- the exact eight target frequencies and order.

Construct the response explicitly from the eight numeric columns. Do not return internal fields such as normalized name, source path, or database IDs unless the API is deliberately versioned.

### HTTP behavior

- Return `application/json`.
- Keep public read-only routes unauthenticated unless abuse creates a measured need.
- Preserve CORS compatibility for existing tools; Android itself does not enforce browser CORS.
- Use explicit 400, 404, and 500 JSON errors.
- Do not expose exception messages, SQL text, bindings, or account identifiers to clients.
- Use structured error logs with a request path and safe error category.
- Await all required promises; use `ctx.waitUntil()` only for optional post-response work such as cache writes.

## Phase 8: cache without breaking free-tier assumptions

Use Workers Cache for HTTP response caching before adding KV.

Suggested policy:

- Cache exact EQ responses for a long period because a dataset version is immutable.
- Cache repeated search queries for a shorter period.
- Include the dataset version in the internal cache key or purge/version caches when importing new data.
- Continue relying on Android's disk cache for already-selected corrections.

Cloudflare still counts Worker invocations even when Worker Cache serves the inner response, so caching primarily reduces D1 reads and CPU work rather than the Worker request count. Verify current pricing before making cost guarantees.

Do not add KV solely as a second cache until measurements show Workers Cache is insufficient.

## Phase 9: Worker validation

Use runtime tests appropriate for Workers and D1, not only ordinary Node tests.

Minimum coverage:

- blank search;
- case-insensitive and substring search;
- wildcard characters in user input;
- 50-result limit;
- exact and differently-cased headphone lookup;
- encoded spaces and punctuation in route names;
- known eight-band response;
- missing headphone 404;
- database error 500 without internal leakage;
- CORS and content-type headers;
- staging D1 binding availability.

Validation sequence:

1. Run converter and Node tests.
2. Run Worker type checking.
3. Regenerate Wrangler binding types after config changes.
4. Run Worker runtime tests with local D1 migrations.
5. Run `wrangler deploy --dry-run` if supported by the pinned version.
6. Run the Worker locally against local data.
7. Deploy only to staging.
8. Exercise staging against remote staging D1.
9. Compare representative staging responses byte-for-byte or semantically with the Node backend.
10. Test the Android debug build using the staging URL.

A successful build or dry run does not prove that remote bindings or imported data are correct.

## Phase 10: handle the single Android base URL

The Android client currently uses one EQify API base URL for both headphone and genre routes. Before pointing production at the Worker, choose one explicit approach:

### Preferred eventual free architecture: port genre route to the Worker

- Replace `node-fetch` with Worker-native `fetch`.
- Store `LASTFM_API_KEY` as a Wrangler secret, never a `vars` value or committed file.
- Replace the module-level genre `Map` with Workers Cache or another justified persistent cache.
- Preserve Last.fm -> iTunes -> keyword -> Pop priority and response shape.
- Decide what `/api/cache/stats` should mean after the cache changes.

### Temporary transition: proxy genre requests to the existing Node origin

This preserves behavior but still requires the Node service to be hosted somewhere. Store the origin URL as non-secret configuration and avoid recursive routing. This is not the desired final free architecture if the old origin costs money.

Do not change the Android production URL until all routes used by that URL are operational.

## Phase 11: production preparation

1. Create/confirm `eqify-headphones-production`.
2. Apply migrations to production explicitly.
3. Import the exact validated dataset version used in staging.
4. Verify counts, checksum, metadata, ranges, and known records again.
5. Confirm Worker production binding points to production, not staging.
6. Configure secrets interactively with the documented Wrangler secret workflow.
7. Enable logs and traces with a sustainable sample rate.
8. Deploy the production Worker to its free `workers.dev` address first.
9. Smoke-test every route from outside local development.
10. Update only a debug Android build initially.
11. Perform device tests for search, selection, correction download, offline cached reuse, and backend failure fallback.
12. Change the production Android `EQIFY_BASE_URL` only after approval.

Keep the trailing slash in the Retrofit base URL.

## Dataset update workflow

For each future AutoEQ update:

1. Update the local ignored dataset from the approved source.
2. Record the source revision and required attribution.
3. Run converter tests.
4. Generate a new SQL file and report.
5. Review count deltas, duplicates, unknown filters, failures, size, and checksum.
6. Compare a stable set of golden headphone profiles with the previous version.
7. Import into staging.
8. Run staging API and Android checks.
9. Export or otherwise preserve the current production state.
10. Import/switch production using a documented, recoverable procedure.
11. Verify production metadata and representative responses.
12. Invalidate/version caches.
13. Retain provenance and a rollback artifact without committing the raw dataset.

Never update production directly from an unreviewed local directory.

## Rollback and recovery

Worker code rollback does not roll back D1 data. Treat these as separate systems.

Before a production dataset update:

- record the current Worker version;
- export or preserve the previous D1 dataset;
- record its source version and checksum;
- confirm current D1 Time Travel/restore behavior and retention;
- retain the prior API base URL until the new service is stable.

If production verification fails:

1. Stop further imports.
2. Restore or switch back to the previous validated dataset.
3. Roll back Worker code if the handler changed.
4. Point Android development builds back to the Node backend if necessary.
5. Confirm cached corrections continue working.
6. Document the failure before retrying.

Never assume a Worker deployment rollback restores connected resource data.

## Free-tier safeguards

Cloudflare limits and pricing change. Future agents must verify current official documentation before provisioning or promising zero cost.

At the time this plan was written, the relevant free-plan figures included:

- Workers: 100,000 requests per day and a 10 ms CPU allowance per invocation.
- D1: 500 MB maximum per Free database, 5 GB total account storage, 5 million rows read per day, and 100,000 rows written per day.
- The free account supported enough databases to keep separate staging and production databases.

Current sources to re-check:

- <https://developers.cloudflare.com/workers/platform/limits/>
- <https://developers.cloudflare.com/workers/platform/pricing/>
- <https://developers.cloudflare.com/d1/platform/limits/>
- <https://developers.cloudflare.com/d1/platform/pricing/>
- <https://developers.cloudflare.com/d1/best-practices/import-export-data/>

Operational safeguards:

- Keep the account on Workers Free unless the user explicitly approves a paid plan.
- Do not attach a paid custom domain requirement; use `workers.dev` initially.
- Monitor Worker requests, CPU, D1 rows read/written, errors, and latency.
- Avoid per-request full-table work where possible.
- Keep search debounced in Android.
- Cache exact EQ responses and repeated searches.
- Put an explicit limit on search results and input length.
- Do not add paid services automatically when a free limit is reached; surface the limit and request direction.

## Security and privacy

- Store Last.fm and Cloudflare credentials only through supported secret mechanisms.
- Never commit `.dev.vars`, `.env`, API tokens, account tokens, or local Cloudflare state.
- The public headphone routes are read-only and should not accept arbitrary SQL or object keys.
- Use prepared statements for every user-supplied value.
- Return generic server errors and log safe structured details internally.
- Do not log song titles, artists, headphone searches, IP addresses, or identifiers unless there is a defined privacy need and retention policy.
- Keep dependencies minimal and pinned.
- Review CORS intentionally; do not copy permissive middleware without understanding which clients require it.

## Licensing and attribution

AutoEq's repository includes an MIT license, but its measurement collection references several third-party measurement sources. Before publicly redistributing raw or derived data:

1. Preserve the required AutoEq copyright and license notice.
2. Record the AutoEq source revision.
3. Review source-specific attribution or redistribution terms.
4. Include appropriate attribution in the backend/project documentation and, if necessary, the app.
5. Do not assume that software licensing automatically settles every third-party measurement-data right.

References:

- <https://github.com/jaakkopasanen/AutoEq/blob/master/LICENSE>
- <https://github.com/jaakkopasanen/AutoEq/blob/master/README.md>

This is a project-planning caution, not legal advice.

## Final acceptance checklist

The migration is complete only when all applicable items are true:

- [ ] Current Node backend behavior was documented before modification.
- [ ] Conversion code is shared or parity-tested, not duplicated inconsistently.
- [ ] Raw AutoEQ data and generated SQL are ignored by Git.
- [ ] Generator output is deterministic and fully validated.
- [ ] Actual record count and database size are documented.
- [ ] D1 schema and indexes are migration-controlled.
- [ ] Staging and production resources are unambiguously separated.
- [ ] Wrangler is pinned locally and binding types are generated.
- [ ] Worker queries use prepared statements.
- [ ] Existing headphone API paths and JSON shapes are preserved.
- [ ] Search behavior, including substring matching and the 50-result cap, is preserved or explicitly versioned.
- [ ] Known headphone gains match the Node backend.
- [ ] Android debug testing succeeds against staging.
- [ ] Genre routing works before changing the single production base URL.
- [ ] Logs and traces are enabled without leaking sensitive data.
- [ ] Free-tier usage and current limits were verified.
- [ ] Licensing and attribution were reviewed.
- [ ] Production data was backed up and rollback was exercised or documented.
- [ ] `AI.md` and this plan reflect the final implemented architecture.

## Instructions for the future implementing AI

When implementation is requested:

1. Do not execute the entire plan blindly.
2. Inspect current code, Git state, installed tool versions, and Cloudflare resources first.
3. Re-read current Cloudflare Workers, D1, Wrangler, and pricing documentation.
4. Audit the completed Phase 1/2 implementation, then start at the first
   outstanding acceptance gate; do not recreate the converter or assume the
   proposed D1 artifacts already exist.
5. Preserve the Node backend until staging and Android parity are demonstrated.
6. Never upload, deploy, migrate, delete, or switch production without confirming the exact target and that the user requested that action.
7. Report actual measurements and validation results, not estimates.
