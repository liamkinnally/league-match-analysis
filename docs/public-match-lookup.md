# Public match lookup

The public lookup accepts a Riot game name and tag line for NA1 / AMERICAS, ranked Solo/Duo (queue 420), and requests the latest five matches. These values are fixed by the backend.

The browser submits to `/api/player-matches`; the Next.js server forwards to `/api/v1/player-matches`. Poll `/api/player-matches/{runId}` (backend `/api/v1/player-matches/{runId}`) no more often than once every two seconds. Completed match rows remain available while other matches in the run are loading or incomplete.

Live lookup is disabled by default. Enable it with `RIOT_PUBLIC_LOOKUP_ENABLED=true` and a backend `RIOT_API_KEY`; see [deployment configuration](deployment.md). The invented sample is independent of both settings. Provider credentials, PUUIDs, and captured response bodies stay server-side. Public responses contain match summaries and sanitized status messages.

## Work and cache limits

The lookup service supports one backend instance, with one worker and at most four waiting requests. Identical active lookups share work. Successful and empty results are reused for 15 minutes, and complete stored matches do not need to be fetched again. Scheduling and active-request sharing are process-local.

After a restart, unfinished public runs become failed and can be submitted again. Already completed match rows remain available. The synchronous local ingestion endpoint is separate and retains its request and response contracts; see [local ingestion](developer-guide.md#local-riot-match-ingestion).

## Cooldowns and submission budget

A provider 429 stops the current public ingestion run. The full numeric `Retry-After` is persisted as an absolute cooldown. New submissions and queued work check that cooldown before calling the provider. Missing or invalid retry guidance uses a 60-second fallback. Cooldown and application busy/rate-limit responses include a retry time.

Each actual backend socket peer may create six new runs per minute. Active-request sharing and cache hits do not consume that budget. `server.forward-headers-strategy=none` prevents `Forwarded`, `X-Forwarded-For`, or browser-supplied identity headers from creating additional budgets.

Behind Next.js or another proxy, clients share the proxy's ingress budget. The service does not distinguish end users at that boundary. Preserve the socket-peer setting and the intended backend ingress; a trusted per-user limiter would require a separate design.

## Test fixtures

The test-classpath-only `e2e` profile substitutes an invented provider gateway and enables lookup without a Riot key. `Lookup<digits>#NA1`, with 1–16 digits, yields an invented `NA1_<digits>` match. `Unavailable#NA1` returns a sanitized unavailable result. The gateway is absent from the production application classpath.

Integration tests use disposable PostgreSQL databases and injected clocks. Browser tests verify that their generated match is absent before submission, then open its persisted development page. Use `./scripts/verify ui behavior` for this path; the frontend-only UI lab does not exercise ingestion.
