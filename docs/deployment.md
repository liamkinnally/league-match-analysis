# Deployment

The hosted prototype uses Vercel for the Next.js frontend and Railway for the Spring Boot backend, PostgreSQL and the scheduled backup job.

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

The frontend can be deployed through the existing Vercel project. The backend can be uploaded from the repository root with the Railway CLI, targeting the existing production service.

After a deployment, verify:

- Railway reports a successful backend deployment.
- `GET /actuator/health` succeeds on the backend.
- `GET /api/health` succeeds through the frontend.
- The synthetic sample opens.
- A live lookup can move from `RUNNING` to a terminal status when Riot access is enabled.

Provider deployment status alone is not enough; the application health endpoints confirm that the request path is actually serving.

## Live lookup

Leave `RIOT_API_KEY` blank and `RIOT_PUBLIC_LOOKUP_ENABLED=false` when only the synthetic sample is needed. Live lookup currently supports NA1 / AMERICAS, ranked Solo/Duo queue 420 and the latest five matches.

Riot API access does not imply Riot endorsement or production-key approval. The prototype keeps its Riot notice, privacy policy and terms visible in the hosted application.
