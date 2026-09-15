# Developer guide

This guide covers local development, the main application routes and the test commands used by the project. Commands run from the repository root unless noted otherwise.

## Requirements

Source development uses:

- Java 21
- Node.js 24 and npm
- Docker with Compose
- Git

Versions and dependencies are declared in `backend/pom.xml`, `.node-version` and `frontend/package.json`.

## Setup

With the required tools installed and Docker running, run this once:

```bash
./scripts/setup
```

`setup` checks the required tools, creates missing local environment files without overwriting existing values, installs locked frontend dependencies and installs Playwright Chromium. Run it again when dependency installation needs refreshing.

## Daily development

Put your development `RIOT_API_KEY` in the ignored root `.env`, then run:

```bash
./scripts/dev
```

Open [localhost:3000](http://127.0.0.1:3000). This starts PostgreSQL, the Spring backend and Next.js in live mode. The launcher enables public lookup and passes the Riot key only to the backend; keep the key out of `frontend/.env.local`, browser code and tracked files. A production key is not needed for local development.

| Command | Data and lookup behavior | Frontend / backend / PostgreSQL ports |
| --- | --- | --- |
| `./scripts/dev` | Live Riot lookup, persistent local development database | `3000` / `8080` / `5548` |
| `./scripts/dev app` | Real backend and the same development database, Riot key blank and public lookup disabled | `3000` / `8080` / `5548` |
| `./scripts/dev fixture` | Deterministic fixtures in a separate database, Riot key blank and public lookup disabled | `3100` / `8180` / `5549` |

The fixture backend uses an internal E2E switch to exercise the lookup flow with test data. It does not enable real Riot requests. Live and offline app modes share a dedicated persistent PostgreSQL volume; fixture and automated browser runs use test storage separate from that volume.

Frontend edits use Next.js hot reload. Stop and restart the command after backend or environment changes. Ctrl+C stops the frontend and backend processes owned by that run and the PostgreSQL service it started. Database contents remain available for the next run. An already-running PostgreSQL service is left running; unrelated processes are never stopped. Occupied ports fail with a clear error.

To add the synthetic sample to the live/offline development database, run this in a second terminal after PostgreSQL is ready:

```bash
./scripts/seed-demo
```

The seed command uses the same isolated database as `dev` and `dev app`.

### Ports and concurrent browser checks

Override development ports with `DEV_FRONTEND_PORT`, `DEV_BACKEND_PORT` and `DEV_POSTGRES_PORT`:

```bash
DEV_FRONTEND_PORT=3001 DEV_BACKEND_PORT=8081 DEV_POSTGRES_PORT=5550 ./scripts/dev
```

Use the same `DEV_POSTGRES_PORT` when running `seed-demo` against an overridden development database port.

Browser behavior checks use `E2E_FRONTEND_PORT`, `E2E_BACKEND_PORT` and `E2E_POSTGRES_PORT`. Their default ports are `3100`, `8180` and `5549`. Use these overrides to resolve port conflicts. Live and offline app sessions can remain running because their database and Next.js build output are isolated from browser checks. Stop any manual fixture session before starting automated browser checks: both use the same fixture Compose project and Next.js build output, even with different ports.

### Check a real lookup

With a valid development key and `./scripts/dev` running:

1. Search for `Doublelift#NA01` from the home page and wait for the lookup to finish.
2. Check the player profile, current queue-specific rank and recent match history. Confirm that an unranked player is distinct from a rank that is unavailable because of a provider failure.
3. Open a match and inspect its participant runes, including a different participant. Confirm the selected participant and rune details agree.

This manual check exercises current Riot responses. Automated fixtures provide repeatable regression coverage. Respect refresh cooldowns and avoid repeating live requests when existing results already cover the change.

### Troubleshooting

- **Missing or expired key:** live mode needs `RIOT_API_KEY` in the root `.env`. If Riot rejects the key, renew it in the Riot developer portal, update that file and restart `./scripts/dev`. Use `./scripts/dev app` or `./scripts/dev fixture` when a key is unavailable. Do not print the key in logs or paste it into bug reports.
- **Docker or a prerequisite is unavailable:** start Docker and rerun `./scripts/setup`; confirm Java 21 and Node.js 24 are selected in the current shell.
- **A port is occupied:** stop the known development session with Ctrl+C or choose unused ports using the variables above. The launcher will not kill the listener for you.
- **Backend or environment edits have no effect:** restart the development command. Frontend hot reload does not restart Spring or reload backend credentials.

## Main routes

| Route | Purpose |
| --- | --- |
| `/` | Player lookup and sample entry point |
| `/search?run=...` | Polls and displays a live lookup |
| `/matches/{matchId}/development` | Current match-development view |
| `/api/player-matches` | Next.js proxy for starting a lookup |
| `/api/player-matches/{runId}` | Next.js proxy for polling a lookup |
| `/api/health` | Frontend-to-backend health check |

The match-development page preserves `focus`, `compare`, `from`, `to`, `metric`, `finalView`, `runeParticipant` and the originating `historyRunId` selections in the URL. Timeline endpoints are exact recorded sample timestamps.

## Local Riot ingestion

The live development backend also exposes a synchronous ingestion endpoint on loopback only. Start the normal live stack:

```bash
./scripts/dev
```

Then submit a lookup from another terminal. The backend reads its key from the ignored root `.env`; never put credentials in request JSON or tracked files.

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -d '{"gameName":"ExamplePlayer","tagLine":"NA1","matchLimit":5}' \
  http://127.0.0.1:8080/api/local/riot/ingestions
```

Use the configured backend port if you overrode `DEV_BACKEND_PORT`. The local full-ingestion command fixes the platform/region to NA1 / AMERICAS and imports ranked Solo/Duo matches. The separate [public history flow](public-match-lookup.md) supports additional Summoner’s Rift queues and ARAM and paginated summary imports.

## Match data

The ingestion path stores original provider responses separately from normalized records. Normalized data includes final match/team/participant facts, supported timeline observations and recorded event families.

The application deliberately does not pretend that one-minute timeline samples provide continuous state. Health, resources, cooldowns, player-visible information and other unavailable signals remain unavailable.

Current ranks are loaded separately through the rank endpoint. A successful Riot response without the requested queue is treated as unranked; request failures or unsupported states remain unavailable.

## Tests

### During iteration

Start with selectors that cover the changed behavior:

```bash
./scripts/verify focused backend '-Dtest=PublicMatchLookupServiceTest' test
./scripts/verify focused frontend src/components/player-search.test.tsx
./scripts/verify focused browser player-lookup.spec.ts
```

Every `./scripts/verify` command blanks `RIOT_API_KEY` and sets `RIOT_PUBLIC_LOOKUP_ENABLED=false`, even when a real key is configured for development. Browser behavior checks use the fixture backend's internal E2E switch and isolated test database. They do not call Riot.

At a coherent task boundary, run the applicable composition or browser scenario:

```bash
./scripts/verify task backend
./scripts/verify task frontend
./scripts/verify ui behavior
./scripts/verify ui sample
./scripts/verify task cross-layer
```

Backend integration tests use disposable PostgreSQL containers, so Docker must be running. Frontend checks include ESLint, TypeScript, Vitest and the production Next.js build. Browser tests use Playwright.

Reuse passing evidence while the relevant code and environment remain unchanged. Rerun affected checks after a change or failure; do not repeat the full repository, package or visual suites after every edit. Inspect changed UI in the running app, including a narrow viewport and an appropriate sparse or unavailable state.

The sample browser check starts a separate frontend with synthetic preview data and no backend URL or service token. It verifies the sample entry and match controls without starting Spring or PostgreSQL. Run it for changes to sample preview behavior; it is also included in the full and cross-layer checks.

The preview sample is derived from the backend's canonical synthetic seed inputs. After changing those inputs, run `node frontend/scripts/generate-preview-sample.mjs` and rerun the sample tests. This frontend projection is only for synthetic previews; normal match calculations remain in the backend.

### Releases and CI

Keep the [deployment guide](deployment.md) release gates. For a meaningful release, run:

```bash
./scripts/verify full
./scripts/package-smoke
```

The full composition includes backend/frontend tasks, browser behavior, sample and visual checks, and repository hygiene. CI also runs the packaged-container smoke test. For a changed UI release, inspect the rendered result and complete the pinned visual check with `./scripts/verify ui visual`; reuse its result when the same check is already covered by `full` with unchanged inputs.

Visual snapshots are compared in the pinned Playwright environment. Update a snapshot only after inspecting the rendered change, then rerun the comparison.

## Project layout

```text
backend/                 Spring Boot application and tests
frontend/                Next.js application and browser tests
ops/postgres-backup/     Scheduled PostgreSQL backup job
scripts/                 Local setup, development and verification commands
docs/                    Architecture and operational notes
```

The current public product path is the player lookup plus match-development page. The repository also contains older experimental analysis views used during development; they are not required to understand the current demo.

## Privacy workflow

The project includes a private operator command for removing stored player data. It has no public admin endpoint. The command supports a dry run, requires an explicit confirmation before deletion, removes complete affected matches rather than leaving partial match records, and records exclusions when future imports should be blocked.

See [player-data-removal.md](player-data-removal.md) for the public overview of that workflow.
