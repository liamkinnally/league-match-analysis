# Developer and legacy analysis guide

See the [README](../README.md) for the current match-history experience, setup and sample. This reference covers retained ingestion/analysis APIs and the development harness. Commands run from the repository root unless stated otherwise.

## Match results and current ranks

The match development page reads final team totals, participant identities, and the
complete captured timeline through `/api/v1/matches/{matchId}/development`. Final
results remain separate from the selected interval. `focus`, `compare`, `from`,
`to`, and `metric` URL parameters restore the selected view; interval endpoints use
exact recorded timestamps. The area chart uses linear segments and keeps missing
observations as gaps. Missing final totals display an em dash labeled Unavailable.

The scoreboard groups occupied regular inventory slots before empty slots and
keeps the trinket separate. This changes display order only. Expanded event details
retain the source records. An unambiguous adjacent control-ward removal and
placement by the same player at the same recorded time may share a display row;
the heading discloses both event-row and record counts. Other removals retain their
recorded meaning, and multikill markers never add kills to the final result.

Current ranks load separately through the frontend's
`/api/matches/{matchId}/ranks` proxy and backend
`/api/v1/matches/{matchId}/ranks`. The backend calls Riot's
[League-v4 by-PUUID endpoint](https://developer.riotgames.com/api-details/league-v4)
for stored participants on the configured NA1 platform. Queue 420 selects ranked
Solo/Duo; queue 440 selects ranked Flex when such a match is already stored.
The existing public match-lookup flow still requests Solo/Duo only.
The key and PUUIDs remain on the backend.

Rank states are `loading`, `ranked`, `unranked`, and `unavailable`. Only a successful
lookup without an entry for the selected queue confirms unranked. Missing keys,
unsupported queues, failed requests, and malformed responses remain unavailable.
Verified cached ranks survive refresh failures and are marked stale in their
details. The process-local cache lasts five minutes, with bounded concurrency and
rate-limit/authentication backoff; it is not a shared multi-instance rate limiter.

“Current avg. tier” averages only verified current ranks in the match's queue.
Iron IV is step 0 through Diamond I at 27; Master, Grandmaster, and Challenger are
steps 28, 29, and 30. The arithmetic mean rounds to the nearest step, with halfway
values rounded higher. LP, unranked players, and unavailable lookups are excluded.
The tooltip and rank disclosure show contribution coverage, cache/fetch state, and
the method. This display is neither historical match rank nor MMR.

Item tooltips show catalog total cost, combine cost, and sell value for the resolved
patch, rather than an inferred historical transaction price. Spell tooltips show
recorded catalog descriptions and base cooldowns. Unknown assets retain readable
fallbacks and original codes in the event record details.

## Local Riot match ingestion

P1 provides a bounded, synchronous ingestion trigger for recent NA ranked solo/duo matches. It is available only when the backend runs with the `local` profile and remains bound to `127.0.0.1`. The server owns the `NA1` platform route, `AMERICAS` regional route, and queue `420`; callers cannot select or override them.

1. Start PostgreSQL from the repository root:

   ```bash
   docker compose up -d postgres
   ```

2. Export `RIOT_API_KEY` from an ignored local environment. Never put a real key in Git, command history, request JSON, or a tracked configuration file. Riot development keys expire; refresh the ignored local value if Riot rejects an expired key.

3. Start the backend from `backend/`:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

4. Submit only the Riot ID and a match limit from 1 through 20:

   ```bash
   curl --fail-with-body \
     -H 'Content-Type: application/json' \
     -d '{"gameName":"ExamplePlayer","tagLine":"NA1","matchLimit":5}' \
     http://127.0.0.1:8080/api/local/riot/ingestions
   ```

The response contains only the durable run ID, terminal run status, and aggregate requested/complete/partial/failed counts. Inspect those values before relying on the imported sample.

Successful provider responses are hashed from their exact accepted bytes before parsing. The parsed document is stored as PostgreSQL `jsonb`, which preserves JSON meaning but not original whitespace, object-key order, or byte formatting. Identical exact response bodies reuse a content-addressed payload while every retrieval retains its own capture record. Re-ingesting a match replaces its current normalized match, team, participant, observation, event, and coverage rows atomically without discarding capture history.

The baseline retains end-of-match facts, coarse timeline observations at their actual timestamps, demonstrated event families, and explicit coverage classifications. Match-V5/timeline does not establish continuous state. In particular, time-varying health/resources, cooldowns, wave and individual jungle-camp state, and player-visible information are unavailable in this baseline. Ward state/position and summoner-cast timing are limited or unavailable even where end totals or event families exist. Replay evidence and analytical conclusions are outside P1.

## Backend death-context analysis

P2 adds backend-only analysis over normalized historical evidence. It can produce a
partial death context for an exact normalized champion-kill event; it does not expose
a public P2 HTTP endpoint or frontend experience.

Callers identify the death by match, containing-frame timestamp, and frame event
index. They must also supply a non-negative maximum prior-observation age and
non-negative objective lookback/lookahead windows. P2 has no hidden default
tolerances.

Death events have a stable logical order: represented timestamp, containing-frame
timestamp, frame event index, then provider event type. Capture and normalized-row
identifiers are retained separately as provenance rather than changing logical event
identity. Identical reports of one logical death are reconciled once with every
source reference. Conflicting reports retain their evidence as an explicit partial,
ambiguous death without selecting or merging convenient fields; a missing victim is
likewise preserved with an explicit limitation. Results also retain the current match
source revision, represented times, source method versions, input coverage, rule
versions, and machine-readable limitations.

Input coverage is retained as deterministic source-specific records, including each
signal, source kind, status, represented bounds, normalized row, nullable capture,
and method version. Multiple methods or sources never overwrite one another;
disagreeing statuses add a conservative conflict limitation. UNKNOWN and
UNAVAILABLE records may truthfully omit a capture rather than inventing provenance.
The complete multi-query analysis read runs in one read-only PostgreSQL
`REPEATABLE_READ` snapshot, so a concurrently committed rematerialization cannot
mix source revisions or normalized evidence in one result.

Participant state reconciles every report at each participant's latest represented
timestamp strictly before the death. Provenance-only duplicates retain every source
reference without becoming a conflict; semantic disagreements become ambiguous.
Equal-time and future frames are not antecedent evidence, and the caller's maximum
age is evaluated only after latest-prior selection so stale evidence remains explicit.
A selected state retains its exact observation timestamp and age, keeps current and
total gold separate, and preserves raw coordinates. Missing, stale, invalid, or
ambiguous state produces an explicit gap instead of a zero-filled snapshot.

For Summoner's Rift map 11, `summoners-rift-grid-v1` projects coordinates from 0
through 15,000 into a 3-by-3 grid split at 5,000 and 10,000 on each axis. These
regions are deliberately non-semantic: they do not claim lane, river, jungle,
isolation, overextension, reachability, vision, or pressure. Missing/out-of-range
coordinates and unsupported maps remain `UNKNOWN` with a limitation.

Inventory is `RECONSTRUCTED`, never labeled observed. The
`match-v5-item-reducer-v1` rule applies ordered purchase, sale, destroy, and supported
undo events strictly before the death boundary. Identical reports of one logical
item event are applied once while retaining every contributing evidence reference.
Malformed operations, absent-item removals, conflicting reports, and unattributed
actors are retained as ambiguities; they are not repaired, discarded, or reassigned.
An unavailable actor remains distinct from an explicitly observed provider value of
zero, though neither can mutate a roster inventory.
Only authoritative roster participants receive inventory mutations. A separate
full-match reduction is compared as a multiset with the seven observed end slots.
Missing or mismatched end evidence adds a limitation and never rewrites the
reconstructed history. The initial rule does not infer slot placement,
transformations not represented by supported events, item readiness, cooldowns, or
combat strength.

Objectives inside the caller's explicit window are reported only as `BEFORE`,
`SAME_TIME`, or `AFTER`, with a signed time delta from the death. This establishes
temporal proximity, not causation, blame, pressure, or decision quality.
Aggregate rule versions explicitly identify both strict latest-prior participant
selection and bounded objective reconciliation, alongside grid and inventory rules.

Expected partial-result limitations include missing or stale participant frames,
incomplete roster or assister evidence, unavailable/ambiguous coverage, unsupported
map position, ambiguous item history, and unavailable or mismatched end inventory.
P2 does not add respawn, summoner-readiness, health/resource, wave, vision,
vulnerability, win-probability, agency, counterfactual, or replay analysis.

## Match analysis product

After ordinary local ingestion, open `/matches/<match-id>?focus=<participant-id>`.
Replace the placeholders with a stored match ID and a participant number from 1
through 10. Normal application startup does not seed matches.

Explore opens on a sparse chronological Match Arc. Each marker is an evidence-backed
transition, not a performance score. Select a marker to inspect its bounded window
and before/after receipt. Review orders a small set of cases for learning while
keeping their chronological rail visible; earlier/later cues make time jumps explicit.
Investigation opens from the selected transition or Review beat, carrying the same
object, question, interval, and evidence revision. Return links preserve that context.

The available Map, Sequence, State, Transfer, Champion Timing, and Receipt lenses
depend on the stored evidence. An unsupported requested lens falls back to Receipt.
Map shows independent timestamped points, never a continuous route. Sequence keeps
simultaneous observations in parallel bands. Receipt values are sampled at the
displayed times; gaps between samples do not establish continuous state or causality.

Evidence and Ask are non-modal contextual panels with exact close links. Evidence
shows represented time, provenance, method/revision information, and coverage limits.
Ask offers only supported structured questions; it has no free-form chat input.
Observed facts, reconstructed state, maintained expert knowledge, interpretations,
and unknowns have distinct assertion modes. Raw provider payloads and player
identifiers are excluded from the analysis response.

Champion Timing currently has a minimal reviewed, patch/build-pinned Stridebreaker
capability slice. It requires a supported ownership breakpoint, coherent item
history, and observed following participation. Ownership is reconstructed from item
events. Maintained capability knowledge does not demonstrate active use, readiness,
or causal impact; missing prerequisites cause abstention.

P3 does not provide replay/video, continuous movement, player-visible information,
cooldown/readiness simulation, health/damage analysis, causal coaching, counterfactuals,
win probability, Agency scores, draft/matchup panels, cross-match pattern mining,
free-form chat, saved history, or annotations. A missing match and insufficient
evidence produce explicit unavailable or empty states.

## Engineering workflow harness

Run repository commands from the repository root. Initial setup checks Java, Node,
npm, Docker, and Git; creates missing ignored environment files without overwriting
them; installs the locked frontend dependencies; and installs Playwright Chromium:

```bash
./scripts/setup
```

Development has three explicit modes:

```bash
./scripts/dev app      # PostgreSQL + ordinary backend + frontend; no seeded match
./scripts/dev fixture  # PostgreSQL + test-only browser fixture backend + frontend
./scripts/dev ui       # frontend-only deterministic UI lab; no backend or ingestion
```

`dev ui` prints the stable URLs below before starting Next.js. Open or screenshot a
URL directly, change only the relevant UI files, and refresh the same URL. Host-native
screenshots make this iteration fast and observable, but they must never replace
committed golden images.

| Named state | Stable path |
| --- | --- |
| Normal Explore | `/matches/__lab_normal?focus=6` |
| Evidence-rich Investigation | `/matches/__lab_investigation?focus=6&object=trn_000000000000000000000002&start=780275&end=900291&question=advantage-conversion&evidence=ev_1111111111111111111111111111111111111111111111111111111111111111&mode=investigate&lens=SEQUENCE` |
| Sparse evidence | `/matches/__lab_sparse?focus=6` |
| Unavailable lens with Receipt fallback | `/matches/__lab_unsupported?focus=6&object=trn_000000000000000000000002&start=780275&end=900291&question=advantage-conversion&evidence=ev_1111111111111111111111111111111111111111111111111111111111111111&lens=CHAMPION_TIMING` |

These sanitized server-only scenarios use the current response contract and pass
through the production runtime validator. They are not backend-to-browser evidence.
The lab fails closed unless explicitly enabled in a local or verification runtime,
and remains unavailable in deployed production even if its flag is set accidentally.

Use the smallest predefined related-state family for a visual change:

| Shared layout being changed | Primary render | Related checks |
| --- | --- | --- |
| Match Arc / Explore | Normal Explore | Sparse evidence; `normal Explore wide` golden |
| Investigation frame or lens stage | Evidence-rich Investigation | `Investigation wide` and `Investigation narrow` goldens |
| Receipt/fallback presentation | Unavailable-lens fallback | Evidence-rich Investigation narrow plus focused Receipt/lens component tests |

When an approved future layout adds materially different density or copy length,
its task must name a truthful scenario family before styling: normally the primary
desktop state, its narrow version, and the nearest partial/sparse or wrapping case
that shares the layout. Keep the set small. This is how long labels, expanded Evidence
content, variable density, borders, and decorative geometry are inspected without
turning every iteration into a full-stack run. Do not fabricate scenarios for
unapproved product capabilities.

Focused verification passes native selectors through unchanged:

```bash
./scripts/verify focused backend '-Dtest=MatchAnalysisControllerTest#returns_the_frozen_calm_response_shape_without_cache_or_raw_payload' test
./scripts/verify focused frontend src/components/match-analysis/lens-stage.test.tsx -t 'parallel bands'
./scripts/verify focused browser --grep 'restores context'
./scripts/verify ui visual --grep 'Investigation'
```

The first two use native Maven and Vitest selection. Focused browser verification
uses the existing PostgreSQL-backed behavior fixture because it proves the real
backend-to-browser path. Focused visual verification needs Docker but no PostgreSQL,
ingestion, or application backend; Playwright arguments such as `--grep` and
`--update-snapshots` remain native and unchanged.

The fixed verification compositions are:

| Command | Exact composition |
| --- | --- |
| `./scripts/verify task backend` | Maven `verify` |
| `./scripts/verify task frontend` | ESLint, TypeScript, Vitest, production build |
| `./scripts/verify task cross-layer` | Compose validation, backend task, frontend task, behavior E2E |
| `./scripts/verify ui behavior` | PostgreSQL, test-only backend fixture compilation, existing behavior E2E |
| `./scripts/verify ui visual` | pinned `linux/amd64` Playwright image, production Next.js build, selected golden comparisons |
| `./scripts/verify hygiene` | diff whitespace and tracked private/generated-artifact checks |
| `./scripts/verify full` | Compose validation, both tasks, behavior E2E, visual E2E, hygiene |

Committed golden comparison and updates are authoritative only in the pinned
Chromium/Linux image used by `./scripts/verify ui visual`. Update a baseline only
after inspecting the rendered state and intentionally approving the change:

```bash
./scripts/verify ui visual --grep 'Investigation' --update-snapshots
./scripts/verify ui visual --grep 'Investigation'
```

Pixel comparison is regression evidence: it proves absence of an unintended change
relative to an approved baseline. It does not prove product or UI quality. The
quality gate is rendered-state inspection and explicit product/visual critique of
hierarchy, content wrapping, overflow, legibility, responsive behavior, and evidence
clarity. The small golden set is intentionally distinct from the PostgreSQL-backed
behavior suite.

## Verification

Docker must be running for backend integration tests. They create an independent disposable PostgreSQL database and verify connectivity, JPA initialization, migrations, repeat application behavior, and HTTP health.

From backend/:

```bash
./mvnw -B -ntp verify
```

To run only the P2 death-context contract and PostgreSQL integration coverage:

```bash
./mvnw -B -ntp \
  -Dtest=TimelineEventKeyTest,CoarseMapProjectorTest,ParticipantStateProjectorTest,InventoryProjectorTest,DeathContextRequestTest,DeathContextServiceTest,JdbcHistoricalDeathQueryIntegrationTest \
  test
```

From frontend/:

```bash
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

Frontend tests cover route restoration, response validation, evidence-aware lenses,
Review order, panels, and healthy/unavailable connections. Type checking and
production builds do not need a running backend.

Run deterministic browser verification from the repository root (Docker required):

```bash
test -e .env || cp .env.example .env
test -e frontend/.env.local || cp frontend/.env.example frontend/.env.local
docker compose up -d --wait postgres
cd backend
./mvnw -B -ntp test-compile
cd ../frontend
npm ci
npx playwright install chromium
npm run test:e2e
cd ..
docker compose down
```

Playwright starts loopback backend/frontend servers. Keep ports 3000 and 8080 free;
outside CI an existing server can be reused only if it was started with the browser
fixture configuration. `npm run test:e2e:headed` runs the same tests visibly.
Use `E2E_FRONTEND_PORT` and `E2E_BACKEND_PORT` to run against dedicated ports when
another checkout is active. Set `CI=1` to require fresh fixture servers rather
than reusing existing ones. For a separate verification database, also set a
dedicated `COMPOSE_PROJECT_NAME`, `POSTGRES_PORT`, and test-only `POSTGRES_*`
credentials; do not reuse the packaged application's database for fixture tests.
Failure screenshots and the HTML report are local ignored outputs. CI retains the
report only on failure.

The browser fixture lives only on the backend test classpath and is enabled only
by the explicit `e2e` profile. It replaces one invented fixture match in the local
database and leaves unrelated matches intact. The serial empty-state case temporarily
moves that fixture's event times beyond its duration, then restores and verifies the
original rows. It does not truncate tables or create a second database.

For manual browser-fixture startup after `test-compile`, run from `backend/`:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local,e2e \
  -Dspring-boot.run.useTestClasspath=true \
  -Dspring-boot.run.additional-classpath-elements=target/test-classes
```

The additional classpath directory is required for the compiled test fixture;
ordinary local and packaged production startup remain seed-free.

GitHub Actions is configured to run backend and frontend checks on pushes and pull requests, followed by browser and container checks. Remote CI has not run yet.

## Stopping local services

Stop the backend and frontend with Ctrl+C in their terminals. From the repository root:

```bash
docker compose down
```

This preserves the PostgreSQL volume and its data.
