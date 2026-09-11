# Architecture

[LoL Match Analysis](https://lolmatchanalysis.app) uses a Next.js frontend, a Spring Boot backend, and PostgreSQL. The backend owns provider access, persistence, and analysis. The frontend validates response contracts and renders the selected match view.

## Data flow

A Riot ID lookup goes from the browser to Next.js at `/api/player-matches`, then to Spring at `/api/v1/player-matches`. The backend fixes the region and queue, schedules ingestion, and applies cache and cooldown rules. The Riot gateway retrieves account, match-list, Match-V5 detail, and timeline responses. The decoder and transactional store persist captures and normalized matches, teams, participants, observations, events, and coverage. Public polling exposes match summaries and sanitized status messages; PUUIDs and raw captures remain server-side.

`/api/v1/matches/{matchId}/development?focus=6&compare=1` reads normalized evidence in a consistent database snapshot. It returns final results, the roster, timestamped samples and events, selectable windows, and suggestions. The frontend preserves participant, comparison, interval, and metric selections in the URL. Final match results remain separate from interval calculations.

The explicit `--seed-demo` command sends invented Match-V5 fixtures through the normal decoder and store. `/api/v1/demo` locates the persisted sample and returns 404 before it is seeded. Ordinary startup does not create sample data.

## Runtime and security

Next.js keeps `BACKEND_URL` and the backend service token server-side. In `compose.app.yaml`, only the frontend has a host port; Spring and PostgreSQL communicate over the Compose network. Flyway manages database migrations, and both application images run as unprivileged users. Riot credentials are backend runtime secrets.

The hosted frontend on Vercel calls the Railway backend over HTTPS. The Railway profile requires a valid service token. `ServiceAuthenticationFilter` checks backend requests except GET/HEAD `/actuator/health` with no query string. The frontend transport fixes the destination to the configured backend origin, replaces caller authorization with the service token, and disables automatic redirect following. PostgreSQL has no public ingress.

Public ingestion supports one persistent backend instance. It has one worker, four waiting slots, shared work for identical active requests, and a 15-minute cache for successful or empty lookups. Complete stored matches are reused. Provider cooldowns persist across restarts; interrupted public runs become failed. The Railway volume prevents overlapping deployments of this worker. See [public lookup](public-match-lookup.md) for request limits and [deployment](deployment.md) for runtime configuration.

Current ranks use League-V4 and a separate process-local five-minute cache. A successful lookup without a rank for the match's queue means unranked; failed or unsupported lookups remain unavailable. Rank displays describe current ranks, not ranks at the time of the match.

Optional Data Dragon assets are fetched and cached server-side for the resolved patch. Catalog failures leave readable text and identifier fallbacks.

## Analysis boundaries

At each represented timestamp, the backend reconciles participant samples and calculates focus minus opponent for CS, total gold, and XP. Missing or conflicting evidence remains unavailable. Window summaries use recorded endpoints. Suggested windows pair each gold-bearing sample with its first endpoint 2–3 minutes later, require an absolute change of at least 300 in gold difference, and select up to three nonoverlapping windows ranked by that change. They are displayed chronologically.

Events retain their timestamps and explicit actor and assister roles. Chart segments connect samples for display; they do not establish continuous state. Neither event proximity nor a change in a metric establishes causality or player knowledge.

The retained `/matches/{matchId}?focus=...` route uses `/api/matches/{matchId}/analysis` for Explore, Review, and Investigation. Its evidence model and versioned champion-capability resource remain separate from the development page. The backend also supports death-context analysis without a dedicated public endpoint. See the [developer guide](developer-guide.md) for these contracts and limitations.

## Verification and public source

Tests cover provider decoding, PostgreSQL persistence, response privacy, deterministic calculations, frontend state, browser behavior, and visual regressions. The package smoke test runs production containers against a fresh database, with optional catalog images stubbed for repeatability. Verification commands are documented in the [developer guide](developer-guide.md) and [deployment guide](deployment.md).

The root `.gitignore` denies files by default and explicitly allows public source, fixtures, configuration, and documentation. To add a public file, add its exact exception and any missing parent directories, then run `python3 scripts/tests/export_policy_test.py`. Do not force-add ignored files. The test uses a temporary empty repository to check allowed files, executable wrapper mode, and private/generated-file exclusions. Ignored local material must not be a runtime dependency.
