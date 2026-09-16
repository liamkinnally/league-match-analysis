# Public match lookup

Supported player regions are North America (NA1), Europe West (EUW1), Europe Nordic & East (EUN1), and Korea (KR). Current queue choices are Ranked Solo/Duo (420), Ranked Flex (440), Draft Pick (400), Swiftplay (480), and ARAM (450). Historical Blind Pick (430) and Quickplay (490) records remain readable and retain historical labels; they are not listed as current queue choices. Search starts across all supported queues. A Queue Type dropdown beside the results narrows the history through a queue-specific server lookup. Each row carries its actual queue. Each page requests at most twenty match IDs; unsupported modes in an unfiltered provider page are omitted, so a displayed page may contain fewer than twenty matches while still offering older history. Arena and regions outside these four are not supported by this flow.

The browser submits a Riot game name, tag line, platform and optional queue filter (zero or omitted means all supported queues) to `/api/player-matches`; Next.js forwards to `/api/v1/player-matches`. An omitted platform defaults to NA1 for existing callers. GET `/{runId}` reads a stored page. Poll running pages no more often than once every two seconds. Completed summaries remain usable while other rows load or fail.

## Profiles, regions and suggestions

Profile URLs use `/summoners/{region}/{gameName}-{tagLine}`, for example `/summoners/na/kitinginmylane-000`. The supported route segments are `na`, `euw`, `eune`, and `kr`. Names retain spaces and Unicode through URL encoding; they are not converted into lossy display slugs. Directly opening a profile starts or reuses its region-specific lookup.

| Search region | Platform API host | Account and Match API route |
| --- | --- | --- |
| NA | `na1.api.riotgames.com` | `americas.api.riotgames.com` |
| EUW | `euw1.api.riotgames.com` | `europe.api.riotgames.com` |
| EUNE | `eun1.api.riotgames.com` | `europe.api.riotgames.com` |
| KR | `kr.api.riotgames.com` | `asia.api.riotgames.com` |

The selected platform is explicit; a Riot ID's tag does not determine its region. Lookup reuse, profile caches and current ranks retain platform context. EUW and EUNE share the EUROPE route for account and match requests while their Summoner and League requests use separate platform hosts.

`GET /api/player-suggestions?platform=NA1&q=kit` forwards to `/api/v1/player-suggestions` and returns at most five cached identities. Results contain `gameName`, `tagLine`, `platform`, and nullable `profileIconId` and `summonerLevel`; PUUIDs are omitted. Matching uses a case-insensitive name or full Riot ID prefix within the selected platform. Suggestions never call Riot, and an empty result does not establish that a player does not exist. Cache coverage grows through normal lookups and match imports; it is not a complete directory.

POST `/{runId}/older` requests the next page using the stored queue, offset and original end-time cutoff. A previous-page link supports restoring loaded history. A full provider page offers continuation; a shorter page ends that snapshot. Filtering removed matches can make a visible page shorter than the provider page. Clients deduplicate rows by match ID. Each older page is an explicit action, with no automatic provider backfill.

POST `/{runId}/refresh` starts a new snapshot after the account’s fifteen-minute update cooldown. Searches reuse completed cached history even after that timer expires. Responses expose `lastUpdated`, `nextRefreshAt`, `queueId`, `previousRunId` and `hasMore`; timestamps describe stored history, not a guarantee that Riot has no newer games. Cached reads remain available while live lookup is disabled or the provider is cooling down.

Live lookup is disabled by default. Enable it with `RIOT_PUBLIC_LOOKUP_ENABLED=true` and a backend `RIOT_API_KEY`; see [deployment configuration](deployment.md). The invented sample is independent of both settings. Provider credentials, PUUIDs and captured response bodies stay server-side. Public responses contain summaries and sanitized status messages.

## Summaries and timelines

ARAM supports Howling Abyss and Butcher’s Bridge records (maps 12 and 14). Its views show tower/inhibitor objectives and omit Summoner’s Rift roles and rank fields. Opponent comparison is explicit; matching role values do not infer an ARAM lane opponent. Summoner’s Rift queues remain restricted to map 11.

History fetches match detail for summary statistics. It reuses stored summaries only when the platform, queue and resolved account membership match. It does not request timelines for every row. A new twenty-match page therefore needs up to twenty-three provider calls before retries: account, Summoner verification on the selected platform, list, and twenty details. The verified Summoner result also supplies the cached profile icon and level; a current-rank fetch adds one League request. Cached summaries reduce that cost.

Opening a match can POST `/api/v1/matches/{matchId}/timeline` through the frontend proxy. GET on the same endpoint reads its state. Concurrent viewers share active work. The job reuses the original stored detail capture and requests only the missing timeline, then upgrades normalized data transactionally. A summary import cannot downgrade a match that already has timeline evidence.

Timeline states distinguish `NOT_REQUESTED`, `RUNNING`, `AVAILABLE`, `UNAVAILABLE` and `FAILED`. A provider not-found response is terminal unavailable; transient failures can be retried. Final statistics remain readable without a timeline. Sample previews use their bundled synthetic timeline without contacting the backend.

## Scheduling and provider limits

One backend instance admits at most five active history/timeline jobs. A single worker processes account resolution, ID listing and one detail at a time, returning unfinished jobs to the queue between turns. This lets admitted accounts interleave rather than one page occupying the worker until completion. Active-request sharing is process-local.

The shared HTTP transport reserves calls atomically by provider host and endpoint, coordinating ingestion and current-rank requests. It starts conservatively at 20 calls per second and 100 per 120 seconds per host, then uses valid provider application/method limit headers. Exhausted budgets return local retry guidance without a network call or sleeping. Provider `Retry-After` blocks the relevant budget scope. Riot documents application and method limits per API key and region; the key does not have one combined worldwide request allowance. See [Riot rate limiting](https://developer.riotgames.com/docs/portal#web-apis_rate-limiting).

Public ingestion also persists a conservative cooldown by regional route so it survives restart. NA, Korea and Europe have separate persisted cooldowns. EUW and EUNE share the Europe cooldown: a platform-specific Summoner throttle may conservatively delay history admission in both even though their platform HTTP budgets are separate. Current rank/profile transport budgets remain scoped to the platform host.

A public 429 keeps the current step retryable and schedules continuation after the cooldown, freeing the worker in the meantime. Jobs have a fifteen-minute active lifetime; interrupted or expired jobs preserve completed rows and require explicit retry. Restart marks unfinished public runs failed. The synchronous local full-ingestion endpoint remains separate.

Each actual backend socket peer may create six new jobs per minute. Cache hits and active sharing do not consume that budget. `server.forward-headers-strategy=none` prevents browser-supplied forwarding or identity headers from creating additional budgets. Behind Next.js, visitors share its ingress budget. These controls support one backend process; multiple instances require coordinated admission and provider budgets.

## Test fixtures

The test-classpath-only `e2e` profile substitutes an invented provider gateway and enables lookup without a Riot key. `Lookup<digits>#NA1`, with 1–16 digits, yields an invented `NA1_<digits>` match. `History<digits>#NA1`, with 1–13 digits, provides forty-three synthetic matches per current queue for pagination checks. Its all-queue stream interleaves those matches with unsupported modes to verify filtering and raw-page continuation. `Unavailable#NA1` returns a sanitized unavailable result. The gateway is absent from the production application classpath.

Integration tests use disposable PostgreSQL databases and injected clocks. They cover pagination beyond twenty games, queue isolation, summary reuse, cooldowns, fair scheduling, timeline upgrades and evidence/privacy boundaries. Browser tests exercise the frontend-to-backend path using synthetic data. Use `./scripts/verify ui behavior`; the frontend-only UI lab does not establish ingestion correctness.
