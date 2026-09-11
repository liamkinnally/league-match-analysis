# Developer guide

This guide covers the current match page, retained analysis APIs, and local development workflows. See the [README](../README.md) for the product overview and packaged application. Commands run from the repository root unless another directory is named.

## Setup and development

Source development requires Java 21, Node.js 24, npm, Docker with Compose, and Git. Versions and dependencies are declared in `backend/pom.xml`, `.node-version`, and `frontend/package.json`.

```bash
./scripts/setup
./scripts/dev app
```

Setup checks the required tools, creates missing ignored environment files without replacing existing values, installs locked frontend dependencies, and installs Playwright Chromium. `dev app` starts PostgreSQL, the ordinary local backend, and Next.js. Open `http://127.0.0.1:3000`; keep ports 3000 and 8080 available. Set `POSTGRES_PORT` in `.env` if 5432 is occupied.

The backend's `local` profile reads the root `.env`; Next.js reads `frontend/.env.local`. Startup applies migrations but does not seed matches. To add the invented sample, run this in a second terminal:

```bash
./scripts/seed-demo
```

The development modes are:

| Command | Runtime |
| --- | --- |
| `./scripts/dev app` | PostgreSQL, ordinary backend, frontend |
| `./scripts/dev fixture` | PostgreSQL, test-classpath browser fixture backend, frontend |
| `./scripts/dev ui` | Frontend-only deterministic UI lab for the retained analysis views |

Use the app or fixture runtime to inspect the current match development page. The UI lab does not exercise persistence or ingestion.

## Match results and current ranks

`/matches/{matchId}/development` reads `/api/v1/matches/{matchId}/development`. The response includes final results, the roster, timeline samples, and recorded events. Final results remain separate from the selected interval. The `focus`, `compare`, `from`, `to`, and `metric` URL parameters restore the view; interval endpoints use exact recorded timestamps. Chart segments connect samples, and missing observations remain gaps. Unavailable final totals display a labeled em dash.

The scoreboard groups occupied regular inventory slots before empty slots and keeps the trinket separate. Stored slot values remain unchanged. Event details retain source records. An unambiguous adjacent control-ward removal and placement by the same player at the same recorded time may share a display row; the heading shows both row and record counts. Other removals keep their recorded meaning. Multikill markers do not add kills to the final result.

Ranks load separately through the frontend `/api/matches/{matchId}/ranks` proxy and backend `/api/v1/matches/{matchId}/ranks`. `CurrentRankService` calls League-V4's by-PUUID endpoint for stored participants on NA1. Queue 420 selects ranked Solo/Duo; queue 440 selects ranked Flex for an already stored match. Public match lookup still requests only queue 420. Credentials and PUUIDs remain on the backend.

Rank states are `loading`, `ranked`, `unranked`, and `unavailable`. Only a successful response without an entry for the selected queue confirms unranked. Missing credentials, unsupported queues, request failures, and malformed responses remain unavailable. Verified cached values survive refresh failures and are marked stale. The cache lasts five minutes and uses bounded concurrency with rate-limit and authentication backoff. It is process-local.

“Current avg. tier” averages verified ranks in the match's queue: Iron IV is step 0, Diamond I is 27, and Master, Grandmaster, and Challenger are 28, 29, and 30. The mean rounds to the nearest step, with halfway values rounded higher. LP, unranked players, and unavailable lookups are excluded. The disclosure shows contribution coverage, fetch/cache state, and the method. This value is neither historical match rank nor MMR.

Item tooltips show catalog total, combine, and sell values for the resolved patch; these are not inferred transaction prices. Spell tooltips show catalog descriptions and base cooldowns. Unknown assets retain text fallbacks and original codes in event details.

## Local Riot match ingestion

The synchronous local ingestion endpoint is available only with the backend's `local` profile, which binds to `127.0.0.1`. The server fixes the NA1 platform, AMERICAS region, and ranked Solo/Duo queue 420. Callers supply a Riot ID and a match limit from 1 through 20.

After setup, put `RIOT_API_KEY` in the ignored root `.env`. Do not put credentials in request JSON or tracked files. The ordinary app runtime above starts the required services. To start only PostgreSQL and the backend:

```bash
docker compose up -d --wait postgres
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Submit a request from another terminal:

```bash
curl --fail-with-body \
  -H 'Content-Type: application/json' \
  -d '{"gameName":"ExamplePlayer","tagLine":"NA1","matchLimit":5}' \
  http://127.0.0.1:8080/api/local/riot/ingestions
```

The response contains the durable run ID, terminal status, and requested/complete/partial/failed counts. Check these before using the imported data.

Successful provider responses are hashed from their accepted bytes before parsing. PostgreSQL `jsonb` preserves JSON meaning, not original whitespace, key order, or formatting. Identical response bodies share a content-addressed payload; each retrieval has its own capture. Re-ingestion atomically replaces current normalized match, team, participant, observation, event, and coverage rows while retaining capture history.

Stored evidence includes final facts, timestamped timeline observations, supported event families, and explicit coverage classifications. It does not establish continuous state, health/resources over time, cooldowns, waves, individual jungle-camp state, or player-visible information. Ward state/position and summoner-cast timing are limited or unavailable even when related totals or events exist.

## Backend death-context analysis

Death-context analysis operates on normalized historical evidence without a dedicated public HTTP endpoint. Callers identify a champion-kill event by match, containing-frame timestamp, and frame event index. They supply a non-negative maximum prior-observation age and objective lookback/lookahead windows; there are no hidden tolerance defaults.

Logical event order is represented timestamp, containing-frame timestamp, frame event index, then provider event type. Capture and normalized-row identifiers remain separate provenance. Duplicate reports of one logical death reconcile once while retaining all source references. Conflicting reports or a missing victim produce an explicitly partial or ambiguous result. Results include the match source revision, represented times, source methods, coverage, rule versions, and machine-readable limitations.

The multi-query read uses one read-only PostgreSQL `REPEATABLE_READ` snapshot, preventing concurrent rematerialization from mixing revisions. Coverage records retain signal, source kind, status, represented bounds, normalized row, nullable capture, and method version. Different sources or methods remain distinct; conflicting statuses add a limitation. `UNKNOWN` and `UNAVAILABLE` records may omit a capture when none exists.

Participant state uses each player's latest represented timestamp strictly before the death. Duplicate reports retain all references; semantic disagreements remain ambiguous. Equal-time and future frames are excluded. Maximum age is checked after latest-prior selection, so stale evidence remains explicit. Selected state retains its timestamp, age, raw coordinates, and separate current/total gold values. Missing, stale, invalid, or ambiguous state remains a gap.

On Summoner's Rift, map 11, `summoners-rift-grid-v1` divides coordinates from 0 through 15,000 into a 3-by-3 grid at 5,000 and 10,000 on each axis. Regions do not imply lane, river, jungle, isolation, reachability, vision, or pressure. Unsupported maps and missing or out-of-range coordinates produce `UNKNOWN` with a limitation.

Inventory is labeled `RECONSTRUCTED`. `match-v5-item-reducer-v1` applies ordered purchase, sale, destroy, and supported undo events strictly before the death. Duplicate logical events apply once with all evidence references. Malformed operations, absent-item removals, conflicts, and unattributed actors remain ambiguities. A missing actor differs from an explicit provider value of zero; neither mutates a roster inventory. Only authoritative roster participants receive mutations.

A separate full-match inventory reduction is compared as a multiset with the seven observed final slots. Missing or mismatched final evidence adds a limitation and never rewrites history. The reducer does not infer slot placement, unrecorded transformations, readiness, cooldowns, or combat strength.

Objectives in the requested window are classified as `BEFORE`, `SAME_TIME`, or `AFTER`, with a signed time delta. Rule versions identify latest-prior state selection, objective reconciliation, grid projection, and inventory reconstruction. These results describe evidence and temporal relationships; they do not provide respawn or readiness simulation, causal coaching, counterfactuals, win probability, or replay analysis.

## Retained match analysis views

Open `/matches/{matchId}?focus={participantId}` with a stored match and a participant ID from 1 through 10. This route uses `/api/matches/{matchId}/analysis` and retains Explore, Review, and Investigation.

Explore shows a chronological Match Arc of evidence-backed transitions. Selecting a marker opens its bounded window and before/after receipt. Review orders selected cases while retaining a chronological rail and explicit earlier/later cues. Investigation carries the selected object, question, interval, and evidence revision; return links preserve that context.

Map, Sequence, State, Transfer, Champion Timing, and Receipt lenses depend on available evidence. Unsupported lenses fall back to Receipt. Map shows independent timestamped points; Sequence keeps simultaneous observations in parallel bands. Sampled receipts do not establish continuous state or causality between their timestamps.

Evidence and Ask are contextual panels with close links. Evidence shows time, provenance, methods, revisions, and coverage limits. Ask offers supported structured questions without free-form chat. Observed facts, reconstructed state, maintained expert knowledge, interpretations, and unknowns have distinct assertion modes. The response excludes raw provider payloads and player identifiers.

Champion Timing uses a minimal reviewed Stridebreaker capability resource pinned to a patch/build. It requires a supported ownership breakpoint, coherent item history, and observed following participation. Ownership is reconstructed from item events. Capability knowledge does not demonstrate active use, readiness, or causal impact; missing prerequisites cause abstention.

These views do not provide replay/video, continuous movement, player-visible information, combat/readiness simulation, causal coaching, counterfactuals, win probability, Agency scores, draft/matchup panels, cross-match pattern mining, free-form chat, saved history, or annotations. Missing matches and insufficient evidence have unavailable or empty states.

## UI lab and visual checks

`./scripts/dev ui` prints complete URLs for these retained-analysis scenarios:

| State | Route |
| --- | --- |
| Normal Explore | `/matches/__lab_normal?focus=6` |
| Evidence-rich Investigation | `/matches/__lab_investigation` with the printed selection query |
| Sparse evidence | `/matches/__lab_sparse?focus=6` |
| Unavailable lens with Receipt fallback | `/matches/__lab_unsupported` with the printed selection query |

The server-only scenarios pass through the production response validator. The lab requires explicit enablement in a local or verification runtime and is unavailable in deployed production. It cannot verify backend-to-browser behavior.

For layout changes, inspect the primary state, a narrow viewport, and a related sparse, unavailable, or wrapping state supported by the product. Host screenshots help iteration; committed visual baselines are authoritative only in the pinned Chromium/Linux environment.

## Verification

Docker must be running for backend integration tests, which use disposable PostgreSQL containers. Frontend type checking and production builds do not require a backend. After setup, choose the relevant composition:

| Command | Checks |
| --- | --- |
| `./scripts/verify task backend` | Maven `verify` |
| `./scripts/verify task frontend` | ESLint, TypeScript, Vitest, production build |
| `./scripts/verify task cross-layer` | Compose validation, backend task, frontend task, browser behavior |
| `./scripts/verify ui behavior` | PostgreSQL, test fixture compilation, Playwright behavior tests |
| `./scripts/verify ui visual` | Pinned `linux/amd64` Playwright image, production Next.js build, visual comparisons |
| `./scripts/verify hygiene` | Diff whitespace and tracked private/generated-file checks |
| `./scripts/verify full` | Compose validation, both tasks, browser behavior, visual comparisons, hygiene |

Focused commands pass native selectors through unchanged:

```bash
./scripts/verify focused backend '-Dtest=MatchAnalysisControllerTest#returns_the_frozen_calm_response_shape_without_cache_or_raw_payload' test
./scripts/verify focused frontend src/components/match-analysis/lens-stage.test.tsx -t 'parallel bands'
./scripts/verify focused browser --grep 'development selection restores'
./scripts/verify ui visual --grep 'Investigation'
```

To run the death-context contracts and PostgreSQL integration tests:

```bash
./scripts/verify focused backend \
  -Dtest=TimelineEventKeyTest,CoarseMapProjectorTest,ParticipantStateProjectorTest,InventoryProjectorTest,DeathContextRequestTest,DeathContextServiceTest,JdbcHistoricalDeathQueryIntegrationTest \
  test
```

Visual verification needs Docker but no PostgreSQL or application backend. Inspect the rendered change before updating a baseline, then rerun the comparison:

```bash
./scripts/verify ui visual --grep 'Investigation' --update-snapshots
./scripts/verify ui visual --grep 'Investigation'
```

Visual comparisons detect differences from approved baselines. Inspect hierarchy, wrapping, overflow, legibility, responsive behavior, and evidence clarity separately. See [deployment](deployment.md) for package smoke and release checks; the public-source allowlist check is separate from `verify full`.

### Browser fixture runtime

`./scripts/verify ui behavior` compiles the backend test fixture and starts Playwright's local backend and frontend servers. Use a dedicated verification database: the fixtures write invented data and the empty-state test temporarily moves fixture events before restoring them. Do not point them at the packaged application's database.

Default ports are 3000 and 8080. Set `E2E_FRONTEND_PORT` and `E2E_BACKEND_PORT` when another checkout is active. A separate database also needs a dedicated `COMPOSE_PROJECT_NAME`, `POSTGRES_PORT`, and test-only `POSTGRES_*` credentials. Outside CI, Playwright may reuse existing servers; reuse only servers started with the fixture configuration. Set `CI=1` to require fresh servers. From `frontend/`, `npm run test:e2e:headed` runs the same tests visibly. Reports and failure screenshots are ignored local output.

The fixture exists only on the test classpath and requires the explicit `e2e` profile. Startup replaces the invented analysis fixture and seeds the separate demo sample, leaving unrelated matches intact. For manual startup, use `./scripts/dev fixture`, or run from `backend/`:

```bash
./mvnw -B -ntp test-compile
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local,e2e \
  -Dspring-boot.run.useTestClasspath=true \
  -Dspring-boot.run.additional-classpath-elements=target/test-classes
```

GitHub Actions runs backend and frontend jobs on pushes and pull requests, followed by browser/visual and production-container checks. See `.github/workflows/ci.yml` for the configured checks and report retention.

## Stopping local services

Stop frontend/backend processes with Ctrl+C. Then, from the repository root:

```bash
docker compose down
```

This preserves the PostgreSQL volume and its data.
