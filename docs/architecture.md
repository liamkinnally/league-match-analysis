# Architecture

## Data flow

The browser submits a Riot ID to the Next.js `/api/player-matches` proxy. Spring’s `/api/v1/player-matches` facade owns the fixed region/queue scope, bounded work, cache and provider cooldown. The existing Riot gateway retrieves Match-V5 details/timeline; a decoder and transactional store write captures plus normalized matches, teams, participants, observations, events and coverage into PostgreSQL. Public polling returns safe history projections, not raw captures or PUUIDs.

`/api/v1/matches/{matchId}/development?focus=6&compare=1` reads normalized data and returns the result summary, roster, timestamped differences, recorded events, selectable windows and suggestions. Java computes both signed values and factual summary prose. The frontend validates the response, renders React components, and preserves focus, comparison and interval in the URL. Missing/ambiguous evidence stays unavailable. Chart lines do not establish continuous state between observations.

The explicit `--seed-demo` command feeds invented Match-V5 fixtures through the normal decoder and store. A marked locator at `/api/v1/demo` identifies the persisted sample; it returns 404 before seed. Startup never silently creates demo data.

## Runtime boundaries

Next.js keeps `BACKEND_URL` server-side. Only the frontend is published by the production Compose configuration; Spring and PostgreSQL use private networking. The database owns durable state, Flyway applies migrations, and the application images run as unprivileged users. Riot keys are runtime backend secrets. The optional game-asset route resolves a matching Data Dragon patch with cached server fetches; catalog failures preserve readable text/ID fallbacks.

In the hosted prototype, Vercel calls Railway over HTTPS with a separate server-only service token. The Railway profile refuses startup without a valid token. A filter protects every backend route except exact status-only GET/HEAD `/actuator/health`; frontend transport pins the backend origin, strips caller authorization and refuses redirects. PostgreSQL remains private. A backend volume enforces Railway's non-overlapping deployments for the singleton worker, accepting brief redeploy downtime.

Current ranks use Riot League-V4 for the match's queue and are cached for five minutes. The UI distinguishes confirmed unranked participants from unavailable or failed lookups, labels ranks as current and reports verified coverage in the average-tier disclosure. Stored game identities are kept in their original language; regular inventory slots are compacted only in presentation.

One persistent backend instance runs public ingestion with one worker and four waiting slots. Identical active lookups share work; successful/empty lookups cache for 15 minutes. Complete stored matches are reused. Provider cooldown survives restart, while interrupted work becomes failed. The request budget uses the backend socket peer, so users behind Next share its ingress budget. See [lookup limits](public-match-lookup.md) and [deployment](deployment.md).

## Deterministic calculations

For each represented timestamp, the backend reconciles focus/opponent samples and computes focus minus opponent for CS, total gold and XP, retaining levels and exact timestamps. It summarizes actual endpoint values and selects a small set of bounded gold-change windows using the heuristic documented in the [README](../README.md). Purchase/kill/objective context retains source timestamps and explicit actor/assistant roles. Temporal proximity is not causality, and generic participant membership is not interpreted as an assistant role.

## Retained analysis and engineering evidence

The older `/matches/{matchId}?focus=...` experience and `/api/matches/{matchId}/analysis` contract remain available. Their evidence-aware Explore/Review/Investigation views and required versioned champion-capability resource are retained; they are separate from the simpler development-window experience. [Developer guide](developer-guide.md) records those APIs and limitations.

Tests exercise provider decoding, normalized PostgreSQL persistence, privacy/response contracts, deterministic windows, React selection, real browser navigation and pinned visual regressions. The package smoke uses actual production containers and a fresh database; only optional catalog images are stubbed for repeatability. The public screenshot instead uses the normal catalog with the invented persisted sample. CI configuration is included; local checks and configured remote jobs are distinct evidence.

## Public source boundary

The root `.gitignore` denies files by default and lists each public source, fixture, configuration and documentation file explicitly, along with its parent directories. When adding a public file, add its exact exception and any missing parents, then run `python3 scripts/tests/export_policy_test.py` (Python 3 required). Do not force-add files. The test stages synthetic probes in a temporary empty repository and checks required files, executable wrapper mode and nested private/output exclusions. Ignored local material is never a runtime dependency.
