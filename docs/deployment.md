# Deployment

The hosted application uses Vercel for the Next.js frontend and Railway for the Spring Boot backend, PostgreSQL and the scheduled backup job.

```text
Browser -> Vercel / Next.js -> Railway / Spring Boot -> PostgreSQL
                                     |
                                     v
                                Riot Games API
```

## Containers

Both application images are built from the repository root:

```bash
docker build -f backend/Dockerfile -t league-analysis-backend:local .
docker build -f frontend/Dockerfile -t league-analysis-frontend:local .
```

The backend runs on Java 21 as an unprivileged user. The frontend uses Node 24 and the locked npm dependency tree. Credentials are runtime environment values rather than Docker build arguments.

For a local production-shaped stack:

```bash
test -e .env || cp .env.example .env
docker compose --project-name league-analysis-app \
  --file compose.app.yaml \
  --env-file .env \
  up --detach --build --wait
```

Open `http://127.0.0.1:3416`.

## Runtime configuration

Important runtime values include:

| Name | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` / `USERNAME` / `PASSWORD` | Backend PostgreSQL connection |
| `BACKEND_URL` | Server-only backend origin used by Next.js |
| `BACKEND_SERVICE_TOKEN` | Separate server-to-server credential |
| `BACKEND_AUTH_REQUIRED` | Enables the backend authentication boundary |
| `PREVIEW_DATA_SOURCE` | Selects `backend` or `sample` data for preview deployments |
| `RIOT_API_KEY` | Server-only Riot API key |
| `RIOT_PUBLIC_LOOKUP_ENABLED` | Enables or disables live player lookup |
| `PROTOTYPE_CONTACT_EMAIL` | Contact shown on policy pages |
| `RIOT_VERIFICATION_TOKEN` | Public website-verification value, not an API key |

Do not expose the Riot key, database credentials or backend service token through browser-public environment variables.

## Release checks

Before deploying a change:

```bash
./scripts/verify full
./scripts/package-smoke
```

The first command covers backend/frontend tests plus browser and visual checks. The package smoke test builds the production containers against a clean database, seeds the synthetic sample and verifies the application in Chromium.

## Hosted services

### Backend

The Railway backend builds from `backend/Dockerfile`, exposes `/actuator/health`, runs one persistent application instance and uses the `railway` Spring profile. The backend talks to PostgreSQL over Railway's private network.

A small `/data` volume stores the private player-removal ledger used by the privacy workflow. The current ingestion worker is process-local, so the deployment intentionally uses one backend replica.

### Frontend

Vercel builds the `frontend` directory as a Next.js application. `BACKEND_URL`, `BACKEND_SERVICE_TOKEN` and policy/verification values are server-side configuration. The Riot API key never belongs in Vercel.

### PostgreSQL and backups

PostgreSQL 17 uses durable private storage. A separate Railway job creates daily custom-format backups in private object storage, verifies uploaded content and removes matching backups older than seven days after a successful new backup.

Restore tests should target a separate empty PostgreSQL database. A restored database must be reconciled with the current player-removal ledger before it is used by the application.

## Deploying an update

GitHub `main` is the release source. Changes reach it through a pull request after the `backend`, `frontend`, `end-to-end` and `application-containers` checks pass. Branch protection also requires the branch to be current with `main`.

The existing Vercel project builds `frontend` from `main`. Its required GitHub checks gate assignment to the production domain; a frontend build can start while those checks run. The existing Railway backend tracks `main` with **Wait for CI** enabled. It watches `backend/**` and `.dockerignore`, since both can affect its image.

The Railway CLI and Vercel CLI remain manual recovery options. Explicitly verify the project, service, environment and source revision before using them. A successful CLI upload is not evidence that the same revision passed CI.

After a deployment, verify:

- Railway reports a successful backend deployment.
- `GET /actuator/health` succeeds on the backend.
- `GET /api/health` succeeds through the frontend.
- The synthetic sample opens.
- A live lookup can move from `RUNNING` to a terminal status when Riot access is enabled.

Provider deployment status alone is not enough; the application health endpoints confirm that the request path is actually serving.

## Preview modes

Preview deployments use the existing Vercel project. Choose the data source through the preview-scoped `PREVIEW_DATA_SOURCE` environment variable:

| Value | Use | Data and dependencies |
| --- | --- | --- |
| `backend` | Check frontend behavior against a real backend | Shared staging backend and staging PostgreSQL; dedicated staging service token |
| `sample` | Work on layout and supported match interactions | Synthetic sample data in the frontend; no backend requests or credential required |

`backend` is the default preview configuration. To use `sample` for a particular branch, add a branch-specific Preview override for `PREVIEW_DATA_SOURCE` in Vercel's environment-variable settings and create a new preview deployment. Environment-variable changes do not alter an existing deployment. Remove the override and redeploy when that branch needs the staging backend again.

Sample mode never uses the staging token. To also omit the credential from that deployment's environment, add an empty branch-specific Preview override for `BACKEND_SERVICE_TOKEN`. Remove both overrides when returning to backend mode; the inherited staging token will then apply again.

Sample mode is a data-source choice, not a test of backend correctness. Live player lookup is unavailable in that mode. Production always uses the backend regardless of a preview-mode setting.

Keep `BACKEND_URL` and `BACKEND_SERVICE_TOKEN` scoped to their matching environments: Production uses the production backend and token; Preview uses staging. A preview must never inherit the production service token. Preserve Vercel's viewer authentication and fork protection.

### Shared staging backend

The Railway `staging` environment has a separate backend instance, PostgreSQL database and persistent storage. It uses its own service credential and begins with only the synthetic seed match. Its Riot key is unset and live lookup is disabled, so backend previews can exercise stored-match behavior without using production's Riot quota. This does not verify live ingestion.

Staging serves one backend revision at a time. A frontend preview does not automatically deploy the backend changes on its branch. For a backend change, first complete the applicable checks, then deliberately deploy the intended revision to the **staging** backend and verify its health, synthetic sample and authenticated request path. Record that backend revision alongside the frontend revision when reporting results. Coordinate incompatible backend changes before replacing the shared staging deployment.

Use the documented seed command against staging only when sample data is needed. Do not copy the production database, credentials, removal ledger or backup job into staging. Production's independent backup schedule remains separate from preview and application deployments.

## Live lookup

Leave `RIOT_API_KEY` blank and `RIOT_PUBLIC_LOOKUP_ENABLED=false` when only the synthetic sample is needed. Live lookup supports NA1 / AMERICAS Summoner’s Rift and ARAM queues in pages of up to twenty matches. See [public match lookup](public-match-lookup.md) for queue IDs, refresh cooldowns, deferred timelines and the single-backend scheduling requirement.

Riot API access does not imply Riot endorsement or production-key approval. The application keeps its Riot notice, privacy policy and terms visible in the hosted application.
