# Deployment and operations

The application is packaged as two portable Linux containers backed by PostgreSQL. Local Compose publishes only the Next.js frontend. The hosted prototype at [lolmatchanalysis.app](https://lolmatchanalysis.app) uses Vercel for Next.js and Railway for Java and PostgreSQL. Cross-provider backend requests use HTTPS and a server-only service credential; database traffic stays private.

## Container layout

Build both application images from the repository root so the root `.dockerignore` protects the build context:

```bash
docker build -f backend/Dockerfile -t league-analysis-backend:local .
docker build -f frontend/Dockerfile -t league-analysis-frontend:local .
```

The backend image builds with the Maven wrapper and Java 21, then runs as the unprivileged `application` user on port 8080. The frontend uses the locked npm dependency tree and Next.js standalone output on Node 24, then runs as the unprivileged `nextjs` user on port 3000. Vercel builds use Vercel's server packaging; Docker builds use standalone output. Credentials are runtime environment values; neither Dockerfile accepts a secret build argument.

`compose.app.yaml` provides the complete local production shape. It publishes only the frontend on loopback and keeps the backend and PostgreSQL private:

```bash
test -e .env || cp .env.example .env
docker compose --project-name league-analysis-app \
  --file compose.app.yaml \
  --env-file .env \
  up --detach --build --wait
```

Open `http://127.0.0.1:3416`. Change `FRONTEND_PORT` in the ignored `.env` when that port is occupied. `GET /api/health` on the frontend checks the private backend and returns `{"status":"UP"}` when the complete request path is ready. The backend also exposes status-only `GET /actuator/health` on its private port.

## Runtime environment

The Compose stack accepts these runtime values:

| Name | Service | Purpose |
| --- | --- | --- |
| `POSTGRES_DB` | PostgreSQL, backend | Database name. |
| `POSTGRES_USER` | PostgreSQL, backend | Database role. |
| `POSTGRES_PASSWORD` | PostgreSQL, backend | Database password. Use a runtime secret in a real environment. |
| `FRONTEND_PORT` | Local Compose | Loopback port for the frontend; defaults to `3416`. |
| `BACKEND_URL` | Frontend | Server-only backend origin. Compose uses private HTTP; Vercel uses Railway HTTPS. |
| `BACKEND_SERVICE_TOKEN` | Frontend, backend | Separate server-to-server credential, at least 32 printable non-whitespace ASCII characters. Never use the Riot API key here. |
| `BACKEND_AUTH_REQUIRED` | Frontend, backend | Enable the auth boundary. Default false for private local Compose; Railway profile forces it on and Vercel requires a configured token. |
| `RIOT_API_KEY` | Backend | Optional server-only Riot API key; blank is supported. |
| `RIOT_PUBLIC_LOOKUP_ENABLED` | Backend | Live player lookup switch; defaults to `false`. |
| `PROTOTYPE_CONTACT_EMAIL` | Frontend | Public policy contact address. |
| `RIOT_VERIFICATION_TOKEN` | Frontend | Public website-verification string issued by the Riot portal. Not an API credential. |

On a host with a managed PostgreSQL database, configure the backend with the ordinary Spring runtime names below. They replace the Compose-generated internal URL without activating the local profile:

| Name | Example shape |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://private-db-host:5432/database-name` |
| `SPRING_DATASOURCE_USERNAME` | managed database role |
| `SPRING_DATASOURCE_PASSWORD` | managed database secret |
| `SERVER_ADDRESS` | `0.0.0.0` |
| `RIOT_API_KEY` | runtime secret or blank |
| `RIOT_PUBLIC_LOOKUP_ENABLED` | `false` until live access is ready |

Set the frontend's server-only `BACKEND_URL` to the backend origin. Across providers use HTTPS and the same `BACKEND_SERVICE_TOKEN` in both runtimes. All backend requests require the credential when auth is enabled, except exact status-only GET/HEAD `/actuator/health`. Do not use a browser-public environment prefix for credentials. Keep `server.forward-headers-strategy=none`; the lookup budget deliberately treats the trusted socket peer as the client. Run one persistent backend instance because ingestion uses an in-process worker and has no distributed worker scheduler.

## Migrations and explicit sample seed

The backend applies Flyway migrations during ordinary startup and validates the resulting schema through Hibernate. Ordinary startup never inserts the sample match. After the backend is healthy, seed the invented sample explicitly:

```bash
docker compose --project-name league-analysis-app \
  --file compose.app.yaml \
  --env-file .env \
  run --rm --no-deps backend \
  --spring.main.web-application-type=none --seed-demo
```

The command uses the packaged application and current runtime database settings. It is idempotent for the marked fixture and refuses to overwrite unrelated data using the reserved demo match ID. After seeding, the home page shows “Explore sample match,” and the detail route exposes the persisted summary and timeline. Restarting the backend does not remove the sample because PostgreSQL owns the durable data volume.

## Live lookup switch

Leave `RIOT_API_KEY` blank and `RIOT_PUBLIC_LOOKUP_ENABLED=false` to run only the invented sample. That configuration starts normally and keeps the invented demo available without calling Riot. To enable live lookup later, supply an authorized Riot key as a backend runtime secret and set `RIOT_PUBLIC_LOOKUP_ENABLED=true`. The application remains limited to NA1, AMERICAS, ranked Solo/Duo queue 420 and the latest five matches. Review [public-match-lookup.md](public-match-lookup.md) before enabling the switch.

## Release verification

Run the complete code and browser boundary first:

```bash
./scripts/verify full
```

Then run the application-container check:

```bash
./scripts/package-smoke
```

The container check uses the disposable Compose project `match-development-v1-package-smoke` and frontend port 3416 (override with `PACKAGE_SMOKE_FRONTEND_PORT`). It checks authentication, explicit sample seeding, persistence across restart, frontend health, policy contacts and website-verification output, then opens the application in Chromium. It uses deterministic game assets and a blank Riot key. The script removes only its own database volume and leaves browser screenshots in `frontend/test-results/`.

Before publication, verify an isolated snapshot containing only the allowlisted release files, using separate configuration and a disposable database. Do not upload private local configuration, captured data, research or Git history. Follow the [export policy](architecture.md#verification-and-public-source); never force-add ignored files or use `--no-gitignore` for a provider upload.

## Hosted prototype

Use one persistent backend instance, PostgreSQL on a durable volume, and a separate daily backup job. Keep backend-to-database traffic on Railway's private network and disable backend sleeping. Use a dedicated Vercel project for this app. Configure provider usage alerts and review actual resource usage; a [hard spending limit](https://docs.railway.com/pricing/cost-control) can stop workloads.

The site is a prototype for testing and review. API access, including a personal key, does not imply production-key approval or Riot endorsement. Keep the [registration and review details](prototype-registration.md) consistent with the hosted product.

### Service configuration

- Railway backend: configure the service to build from repository root using `backend/Dockerfile`, watch `backend/**`, check `/actuator/health` with a 180-second startup timeout, and restart on failure with at most five retries. Activate only `SPRING_PROFILES_ACTIVE=railway`. This profile listens on Railway's `PORT`, requires service authentication, and uses a five-connection database pool. Inject Spring datasource URL/user/password using private database references; inject `RIOT_API_KEY`, `RIOT_PUBLIC_LOOKUP_ENABLED=true` and the separate service token as runtime secrets.
- Attach a small volume to the backend at `/data` and retain one replica. Railway [prevents overlapping deployments for services with volumes](https://docs.railway.com/volumes/reference). This deliberately accepts brief redeploy downtime and preserves the existing startup recovery of interrupted lookups. The private player-removal ledger uses `/data/player-removal/ledger.json`; provision its directory for UID/GID `10001:10001`, initialize it in maintenance mode, and keep the normal non-root image user. Follow the [operator installation and restore runbook](player-data-removal.md) before first startup with the Railway profile. **Do not remove this volume or enable replicas without replacing the singleton worker/recovery design.** Database persistence uses a separate PostgreSQL volume.
- PostgreSQL: use version 17, a private service hostname and durable volume; avoid public database ingress. Keep backend and database in the same region and frontend functions nearby. Vercel config selects `iad1`. This deployment uses the separate backup job below.
- Vercel: deploy the `frontend` directory as a separate Next.js project; configure `BACKEND_URL` with the Railway HTTPS domain, `BACKEND_SERVICE_TOKEN`, `BACKEND_AUTH_REQUIRED=true`, `PROTOTYPE_CONTACT_EMAIL`, and `LEAGUE_ANALYSIS_RUNTIME=deployed-production`. Do not enable `UI_LAB_ENABLED` or the backend local/e2e profiles. No Riot API credential belongs in Vercel.
- Keep the Prototype notice, noindex metadata/robots and policy links. `noindex` discourages search indexing; it is not access control. The prototype remains reachable for review. `/riot.txt` returns 404 until the portal-issued verification value is configured, and then returns that exact plain-text value with caching disabled.

### Private backup job

Build `ops/postgres-backup/Dockerfile` with `ops/postgres-backup` as its context. Configure a private Railway service to run it daily at 07:00 UTC, with no domain, public port, web healthcheck or long-running process. Use PostgreSQL's private `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` and `PGPASSWORD` references. Keep bucket-scoped `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`, `BACKUP_S3_ENDPOINT` (HTTPS) and `BACKUP_S3_BUCKET` in this service's runtime environment only. Use a dedicated private bucket; no public read policy or browser access is needed.

Each run writes a PostgreSQL 17 custom-format dump, uploads it under `league-analysis/postgres/`, checks its SHA-256 metadata and downloads it to verify the full checksum. Only after verification does it remove matching backups older than seven days. Failed dump/upload/verification steps exit before retention. Local dump files have private permissions and are removed when the process exits. The script does not write to the source database or put passwords in command arguments.

Before relying on the schedule, download a successful backup through authenticated storage access and restore it with `pg_restore --no-owner --no-acl --exit-on-error` into a separate, empty PostgreSQL 17 database. Never use the application database as a restore test target. Before serving any restored database, [reconcile it with the current external removal ledger](player-data-removal.md#backup-expiry-interrupted-removal-and-restore); never restore an old ledger alongside it. Use `league-analysis-backup --dry-run` for read-only connection and retention checks. A completed scheduled run must report `remaining_expired=0`; upload success alone is insufficient. See [retention verification](player-data-removal.md#retention-verification) for the output and limitations.

## Deploying an update

Use a clean allowlisted checkout and the existing linked provider projects. Deploy the frontend with Vercel's project root set to `frontend`, running `vercel deploy --prod` from the repository root. Deploy the backend with `railway up` targeting the existing backend service from that root. Upload only `ops/postgres-backup` as the backup job's source root. Keep source-repository permissions limited to this repository if enabling provider Git integration.

After deployment, verify the active image and command, backend `/actuator/health`, frontend `/api/health`, a stored real match and the labeled sample. Missing or incorrect service credentials must be rejected. Keep credentials and raw captures out of public responses, browser bundles and logs. Record deployment IDs, checks and any outstanding operator actions privately.

When leaving maintenance, restore the normal Java command, healthcheck, non-root user, watch patterns and backup schedule. Do not leave sleep, dry-run or seed commands configured for routine deployments. See [player-data removal](player-data-removal.md) for maintenance, recovery and the authoritative ledger requirements.
