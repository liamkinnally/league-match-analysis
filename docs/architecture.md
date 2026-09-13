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

The match-development route reads normalized data and returns final results, the roster, timestamped samples, recorded events and selectable comparison windows. Participant, opponent, interval and metric selections are encoded in the URL so the view is shareable and restorable.

The synthetic demo goes through the same decoder and persistence layer as normal match data. It is added only by the explicit seed command and is never inserted automatically at startup.

## Backend structure

The main backend responsibilities are split into a few areas:

- `ingestion/riot` handles Riot requests, decoding, caching, lookup status and persistence.
- `analysis/match` loads stored match data and calculates the values used by the match pages.
- `analysis/rank` retrieves current queue-specific ranks for stored participants.
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
