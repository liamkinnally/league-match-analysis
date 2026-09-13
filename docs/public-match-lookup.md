# Public match lookup

Current queue choices for NA1 / AMERICAS are Ranked Solo/Duo (420), Ranked Flex (440), Draft Pick (400), Swiftplay (480), and ARAM (450). Historical Blind Pick (430) and Quickplay (490) records remain readable and retain historical labels; they are not listed as current queue choices. Search starts across all supported queues. A Queue Type dropdown beside the results narrows the history through a queue-specific server lookup. Each row carries its actual queue. Each page requests at most twenty match IDs; unsupported modes in an unfiltered provider page are omitted, so a displayed page may contain fewer than twenty matches while still offering older history. Arena and other regions are not supported by this flow.

The browser submits a Riot game name, tag line and optional queue filter (zero or omitted means all supported queues) to `/api/player-matches`; Next.js forwards to `/api/v1/player-matches`. GET `/{runId}` reads a stored page. Poll running pages no more often than once every two seconds. Completed summaries remain usable while other rows load or fail.

POST `/{runId}/older` requests the next page using the stored queue, offset and original end-time cutoff. A previous-page link supports restoring loaded history. A full provider page offers continuation; a shorter page ends that snapshot. Filtering removed matches can make a visible page shorter than the provider page. Clients deduplicate rows by match ID. Each older page is an explicit action, with no automatic provider backfill.

POST `/{runId}/refresh` starts a new snapshot after the account’s fifteen-minute update cooldown. Searches reuse completed cached history even after that timer expires. Responses expose `lastUpdated`, `nextRefreshAt`, `queueId`, `previousRunId` and `hasMore`; timestamps describe stored history, not a guarantee that Riot has no newer games. Cached reads remain available while live lookup is disabled or the provider is cooling down.

Live lookup is disabled by default. Enable it with `RIOT_PUBLIC_LOOKUP_ENABLED=true` and a backend `RIOT_API_KEY`; see [deployment configuration](deployment.md). The invented sample is independent of both settings. Provider credentials, PUUIDs and captured response bodies stay server-side. Public responses contain summaries and sanitized status messages.

## Summaries and timelines

ARAM supports Howling Abyss and Butcher’s Bridge records (maps 12 and 14). Its views show tower/inhibitor objectives and omit Summoner’s Rift roles and rank fields. Opponent comparison is explicit; matching role values do not infer an ARAM lane opponent. Summoner’s Rift queues remain restricted to map 11.

History fetches match detail for summary statistics. It reuses stored summaries only when the queue and resolved account membership match. It does not request timelines for every row. A new twenty-match page therefore needs up to twenty-two provider calls before retries: account, list, and twenty details. Cached summaries reduce that cost.

Opening a match can POST `/api/v1/matches/{matchId}/timeline` through the frontend proxy. GET on the same endpoint reads its state. Concurrent viewers share active work. The job reuses the original stored detail capture and requests only the missing timeline, then upgrades normalized data transactionally. A summary import cannot downgrade a match that already has timeline evidence.

Timeline states distinguish `NOT_REQUESTED`, `RUNNING`, `AVAILABLE`, `UNAVAILABLE` and `FAILED`. A provider not-found response is terminal unavailable; transient failures can be retried. Final statistics remain readable without a timeline. Sample previews use their bundled synthetic timeline without contacting the backend.

## Scheduling and provider limits

One backend instance admits at most five active history/timeline jobs. A single worker processes account resolution, ID listing and one detail at a time, returning unfinished jobs to the queue between turns. This lets admitted accounts interleave rather than one page occupying the worker until completion. Active-request sharing is process-local.

The shared HTTP transport reserves calls atomically by provider host and endpoint, coordinating ingestion and current-rank requests. It starts conservatively at 20 calls per second and 100 per 120 seconds per host, then uses valid provider application/method limit headers. Exhausted budgets return local retry guidance without a network call or sleeping. Provider `Retry-After` blocks the relevant budget scope. Public ingestion also persists a conservative shared cooldown so it survives restart.

A public 429 keeps the current step retryable and schedules continuation after the cooldown, freeing the worker in the meantime. Jobs have a fifteen-minute active lifetime; interrupted or expired jobs preserve completed rows and require explicit retry. Restart marks unfinished public runs failed. The synchronous local full-ingestion endpoint remains separate.

Each actual backend socket peer may create six new jobs per minute. Cache hits and active sharing do not consume that budget. `server.forward-headers-strategy=none` prevents browser-supplied forwarding or identity headers from creating additional budgets. Behind Next.js, visitors share its ingress budget. These controls support one backend process; multiple instances require coordinated admission and provider budgets.

## Test fixtures

The test-classpath-only `e2e` profile substitutes an invented provider gateway and enables lookup without a Riot key. `Lookup<digits>#NA1`, with 1–16 digits, yields an invented `NA1_<digits>` match. `History<digits>#NA1`, with 1–13 digits, provides forty-three synthetic matches per current queue for pagination checks. Its all-queue stream interleaves those matches with unsupported modes to verify filtering and raw-page continuation. `Unavailable#NA1` returns a sanitized unavailable result. The gateway is absent from the production application classpath.

Integration tests use disposable PostgreSQL databases and injected clocks. They cover pagination beyond twenty games, queue isolation, summary reuse, cooldowns, fair scheduling, timeline upgrades and evidence/privacy boundaries. Browser tests exercise the frontend-to-backend path using synthetic data. Use `./scripts/verify ui behavior`; the frontend-only UI lab does not establish ingestion correctness.
