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

```bash
./scripts/setup
./scripts/dev app
```

`setup` checks the required tools, creates missing local environment files, installs locked frontend dependencies and installs Playwright Chromium. `dev app` starts PostgreSQL, the Spring backend and the Next.js frontend.

Open `http://127.0.0.1:3000`.

To add the synthetic sample in a second terminal:

```bash
./scripts/seed-demo
```

Stop frontend/backend processes with Ctrl+C, then stop Compose services with:

```bash
docker compose down
```

## Main routes

| Route | Purpose |
| --- | --- |
| `/` | Player lookup and sample entry point |
| `/search?run=...` | Polls and displays a live lookup |
| `/matches/{matchId}/development` | Current match-development view |
| `/api/player-matches` | Next.js proxy for starting a lookup |
| `/api/player-matches/{runId}` | Next.js proxy for polling a lookup |
| `/api/health` | Frontend-to-backend health check |

The match-development page preserves `focus`, `compare`, `from`, `to` and `metric` selections in the URL. Timeline endpoints are exact recorded sample timestamps.

## Local Riot ingestion

The local backend profile exposes a synchronous ingestion endpoint on loopback only. Put `RIOT_API_KEY` in the ignored root `.env`; never place credentials in request JSON or tracked files.

Start PostgreSQL and the backend:

```bash
docker compose up -d --wait postgres
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Then submit a lookup from another terminal:

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -d '{"gameName":"ExamplePlayer","tagLine":"NA1","matchLimit":5}' \
  http://127.0.0.1:8080/api/local/riot/ingestions
```

The local full-ingestion command fixes the platform/region to NA1 / AMERICAS and imports ranked Solo/Duo matches. The separate [public history flow](public-match-lookup.md) supports additional Summoner’s Rift queues and ARAM and paginated summary imports.

## Match data

The ingestion path stores original provider responses separately from normalized records. Normalized data includes final match/team/participant facts, supported timeline observations and recorded event families.

The application deliberately does not pretend that one-minute timeline samples provide continuous state. Health, resources, cooldowns, player-visible information and other unavailable signals remain unavailable.

Current ranks are loaded separately through the rank endpoint. A successful Riot response without the requested queue is treated as unranked; request failures or unsupported states remain unavailable.

## Tests

Run everything normally used before a release:

```bash
./scripts/verify full
./scripts/package-smoke
```

Useful focused commands:

```bash
./scripts/verify task backend
./scripts/verify task frontend
./scripts/verify ui behavior
./scripts/verify ui sample
./scripts/verify ui visual
```

Backend integration tests use disposable PostgreSQL containers, so Docker must be running. Frontend checks include ESLint, TypeScript, Vitest and the production Next.js build. Browser tests use Playwright.

The sample browser check starts a separate frontend with synthetic preview data and no backend URL or service token. It verifies the sample entry and match controls without starting Spring or PostgreSQL. Run it for changes to sample preview behavior; it is also included in the full and cross-layer checks.

The preview sample is derived from the backend's canonical synthetic seed inputs. After changing those inputs, run `node frontend/scripts/generate-preview-sample.mjs` and rerun the sample tests. This frontend projection is only for synthetic previews; normal match calculations remain in the backend.

For a specific backend test:

```bash
./scripts/verify focused backend '-Dtest=PublicMatchLookupServiceTest' test
```

For a specific frontend test:

```bash
./scripts/verify focused frontend src/components/player-search.test.tsx
```

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
