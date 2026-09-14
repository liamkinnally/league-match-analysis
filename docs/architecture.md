# Architecture

LoL Match Analysis uses a Next.js frontend, a Spring Boot backend and PostgreSQL. The backend owns Riot API access, persistence and match calculations. The frontend validates responses and renders the match-history and timeline views.

## Data flow

A Riot ID lookup follows this path:

```text
Browser -> Next.js API route -> Spring Boot -> Riot Games API
                                  |
                                  v
                              PostgreSQL
```

The backend resolves the Riot account, pages through match IDs across supported Summoner’s Rift and ARAM queues by default, or for a specific Queue Type filter, and imports Match-V5 detail summaries. Timeline evidence is fetched on demand when opening a match. Source captures and normalized rows remain separate. Public polling returns lookup status and match summaries without exposing PUUIDs or raw provider responses.

The match-development route reads normalized data and returns final results, the roster, timestamped samples, recorded events and selectable comparison windows. Participant, opponent, interval, metric, final-state tab and inspected rune participant selections are encoded in the URL so the view is shareable and restorable.

The synthetic demo goes through the same decoder and persistence layer as normal match data. It is added only by the explicit seed command and is never inserted automatically at startup.

## Backend structure

The main backend responsibilities are split into a few areas:

- `ingestion/riot` handles Riot requests, decoding, caching, lookup status and persistence.
- `analysis/match` loads stored match data and calculates the values used by the match pages.
- `analysis/rank` shares cached current rank responses across roster and profile views.
- `analysis/profile` owns current profile snapshots, bounded recent Solo/Duo records and discrete rank observations.
- `privacy` contains the private operator workflow for removing stored player data.
- Flyway migrations in `src/main/resources/db/migration` own the PostgreSQL schema.

Older experimental analysis views remain in the repository, but the recruiter-facing product is centered on the match-history and match-development flow.

## Runtime and security

Next.js keeps `BACKEND_URL` and the backend service token server-side. Riot credentials are available only to the Spring backend. Hosted PostgreSQL has no public application-facing ingress.

The frontend replaces caller authorization with its own backend service credential. The Railway profile requires backend authentication while leaving the health endpoint available for provider checks. Application containers run as unprivileged users.

Live lookup admits five jobs to a single worker and interleaves small work units. Identical active requests share work; completed pages remain cached until an explicit refresh, subject to an account cooldown. A shared transport budgets provider calls, and persisted cooldowns survive backend restart. Cached summaries are reused only when the queue and the PUUID resolved for the current Riot ID match. See [public match lookup](public-match-lookup.md) for pagination and single-instance limits.

## Persistence and evidence boundaries

Provider responses are retained separately from normalized matches, teams, participants, timeline observations and events. Re-importing a match replaces its current normalized representation while keeping source-capture history. A detail-only import cannot overwrite existing timeline evidence. Timeline upgrades reuse original detail provenance in a new ingestion run.

Timeline samples are discrete observations. Connecting them in a chart does not mean the application knows the exact state between samples. Missing or conflicting evidence remains unavailable rather than being filled in. Suggested windows rank recorded changes in gold difference and do not infer cause or player intent.

Current ranks are fetched separately from match ingestion and describe the player's current rank in the selected queue. They are not historical match ranks or MMR.

## Verification

The repository includes unit and integration tests for ingestion, persistence, calculations and privacy behavior; frontend tests for response handling and UI state; Playwright browser tests; visual regression tests; and a packaged-container smoke test against a clean PostgreSQL database.

Run the full development verification with:

```bash
./scripts/verify full
./scripts/package-smoke
```

See the [development guide](developer-guide.md) for focused commands and the [deployment guide](deployment.md) for the hosted layout.


## Rune display metadata

Match-detail rune selections and their nullable `var1`, `var2`, and `var3` counters are retained separately from participant-wide damage, healing and shielding totals. Existing matches are enriched lazily from their current retained detail capture under the privacy and match locks. Enrichment updates participant extension columns without replacing timeline evidence or importing another provider response.

The server-side game asset loader derives the numeric major/minor patch from each match's `gameVersion` and requests that patch's CommunityDragon `perks.json`. It matches rune IDs and accepts a bounded subset of `endOfGameStatDescs`: literal labels with one direct counter placeholder and a supported literal unit. Templates are rendered as text; expressions, reused counters with conflicting meanings and unsupported formats remain unavailable. A recorded zero differs from a missing counter. No rune aggregate is calculated from these display labels.

Catalogs are cached by their exact source URL for 24 hours in a bounded per-process cache; concurrent reads share a request. Failures retry after one minute. A failed refresh can retain the last successful catalog for that same patch with an explicit stale state. A first failure leaves performance labels unavailable without preventing match access. Matching Data Dragon layout and artwork can fail independently. There is no substitution from `latest` or another match patch. Exact source hashes and retrieval times accompany metadata; a refreshed descriptor is parsed again rather than inheriting a previous interpretation. Any future analytical calculation needs its own reviewed semantics and coverage rules.

## Player profile and observations

A cached profile read does not initiate Riot requests. Profile and rank continuation work follows admitted account updates and shares the existing request budget. Profile snapshots and ranks carry separate fetched times, stale/error states and retry limits. A successful rank response without Solo/Duo is unranked; a failed response is unavailable. Current ranked wins and losses are source-reported and do not establish a season boundary.

Recent Solo/Duo collection is an explicit, bounded action. Recorded outcomes are shown with their actual sample size; remake eligibility remains unverified, so these outcomes are not represented as an exact last-20 eligible win rate. Rank history consists of sampled observations from successful refreshes, not reconstructed LP changes per match. Personal snapshots, observations, pending work and source captures remain covered by the player-removal workflow.
