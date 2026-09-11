# Deployment and operations

The application is packaged as two portable Linux containers backed by PostgreSQL. Local Compose publishes only the Next.js frontend. The hosted pre-release prototype uses Vercel Pro for Next.js and Railway Hobby for Java and PostgreSQL. Cross-provider backend requests use HTTPS and a server-only service credential; database traffic stays private.

## Container layout

Build both application images from the repository root so the root `.dockerignore` protects the build context:

```bash
docker build -f backend/Dockerfile -t league-analysis-backend:local .
docker build -f frontend/Dockerfile -t league-analysis-frontend:local .
```

The backend image builds with the Maven wrapper and Java 21, then runs as the unprivileged `application` user on port 8080. The frontend uses the locked npm dependency tree and Next.js standalone output on Node 24, then runs as the unprivileged `nextjs` user on port 3000. Vercel builds omit standalone output because Vercel's adapter packages the server; this avoids the Next.js 16.3 standalone-adapter trace-file failure while preserving the Docker build. Credentials are runtime environment values; neither Dockerfile accepts a secret build argument.

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

Set the frontend's server-only `BACKEND_URL` to the backend origin. Across providers use HTTPS and the same `BACKEND_SERVICE_TOKEN` in both runtimes. All backend requests require the credential when auth is enabled, except exact status-only GET/HEAD `/actuator/health`. Do not use a browser-public environment prefix for credentials. Keep `server.forward-headers-strategy=none`; the lookup budget deliberately treats the trusted socket peer as the client. Run one persistent backend instance because ingestion uses an in-process worker and has no distributed scheduler or lease coordination.

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

Leave `RIOT_API_KEY` blank and `RIOT_PUBLIC_LOOKUP_ENABLED=false` for the recruiter sample. That configuration starts normally and keeps the invented demo available without calling Riot. To enable live lookup later, supply an authorized Riot key as a backend runtime secret and set `RIOT_PUBLIC_LOOKUP_ENABLED=true`. The application remains limited to NA1, AMERICAS, ranked Solo/Duo queue 420 and the latest five matches. Review [public-match-lookup.md](public-match-lookup.md) before enabling the switch.

## Release verification

Run the complete code and browser boundary first:

```bash
./scripts/verify full
```

Then run the application-container check:

```bash
./scripts/package-smoke
```

The container check uses the dedicated Compose project `match-development-v1-package-smoke` and frontend port 3416 (override with `PACKAGE_SMOKE_FRONTEND_PORT` when the main app is running). It removes only that project's prior volume, builds both images, starts a clean PostgreSQL database with service authentication enabled, rejects unauthenticated backend access, confirms the sample locator is missing before seed, runs the explicit seed with a blank Riot key, checks the persisted match API, restarts the backend, checks the persisted locator again, and opens home → match summary in Chromium. It also checks frontend health, policy contacts and exact verification text. It tears down that dedicated stack and volume when finished and leaves wide and narrow browser receipts at `frontend/test-results/package-smoke-*.png`. Game asset responses are deterministic in this browser check while application pages, APIs and database state remain the production container path.

Before publication, build and run an isolated snapshot containing only the files staged for the release repository, using its own configuration and database. Reuse still-valid full-suite evidence when source is unchanged; rerun checks affected by corrections. The first hosted release completed these steps; subsequent releases must retain the same boundaries.

## Hosted pre-release prototype

The target is the existing Vercel account for the Next.js frontend and Railway Hobby for the Java backend and PostgreSQL. This is a functional pre-release prototype for testing and Riot review during product registration, not a broad launch or a claim of Riot approval. Development/personal credentials can be used for this phase under the operator's specific guidance from the official Riot third-party developer Discord. Production-key approval is not a prerequisite for hosting this prototype. A development key's expiry remains an operational limit; use a personal key when available to avoid daily renewal.

Keep one persistent backend instance, PostgreSQL on a durable volume, a separate scheduled backup job, and backend-to-database traffic on Railway's private network. Keep the backend sleeping option disabled. The existing Vercel domain/project used for other work must remain untouched; use a separate project and provider HTTPS subdomain initially.

Budget roughly $10–15/month of Railway usage initially and validate that estimate against actual memory, CPU, storage and traffic. [Railway Hobby](https://docs.railway.com/pricing/plans) has a $5/month minimum including $5 of usage; the full usage bill is not added to that minimum. Use usage alerts; a [hard spending limit](https://docs.railway.com/pricing/cost-control) stops workloads when reached. The frontend uses the operator's existing Vercel Pro account in a separate project.

### Concrete service configuration

- Railway backend: configure the service to build from repository root using `backend/Dockerfile`, watch `backend/**`, check `/actuator/health` with a 180-second startup timeout, and restart on failure with at most five retries. These settings live in Railway's service configuration; the deprecated `railway.json` format is not required. Activate only `SPRING_PROFILES_ACTIVE=railway`. This profile listens on Railway's `PORT`, requires service authentication, and uses a five-connection database pool. Inject Spring datasource URL/user/password using private database references; inject `RIOT_API_KEY`, `RIOT_PUBLIC_LOOKUP_ENABLED=true` and the separate service token as runtime secrets.
- Attach a small volume to the backend at `/data` and retain one replica. Railway [prevents overlapping deployments for services with volumes](https://docs.railway.com/volumes/reference). This deliberately accepts brief redeploy downtime and preserves the existing startup recovery of interrupted lookups. The private player-removal ledger now uses `/data/player-removal/ledger.json`; provision its directory for UID/GID `10001:10001`, initialize it in maintenance mode, and keep the normal non-root image user. Follow the [operator installation and restore runbook](player-data-removal.md) before deploying this version. **Do not remove this volume or enable replicas without replacing the singleton worker/recovery design.** Database persistence uses a separate PostgreSQL volume.
- PostgreSQL: use version 17, a private service hostname and durable volume; avoid public database ingress. Keep backend and database in the same region and frontend functions nearby. Vercel config selects `iad1`. Native Railway backups/PITR require Pro in the dashboard inspected for this release; this Hobby setup uses the separate job below.
- Vercel: deploy the `frontend` directory as a separate Next.js project; configure `BACKEND_URL` with the Railway HTTPS domain, `BACKEND_SERVICE_TOKEN`, `BACKEND_AUTH_REQUIRED=true`, `PROTOTYPE_CONTACT_EMAIL`, and `LEAGUE_ANALYSIS_RUNTIME=deployed-production`. Do not enable `UI_LAB_ENABLED` or the backend local/e2e profiles. No Riot API credential belongs in Vercel.
- Keep the pre-release notice, noindex metadata/robots and policy links. `noindex` discourages search indexing; it is not access control. The prototype remains reachable for review. `/riot.txt` returns 404 until the portal-issued verification value is configured, and then returns that exact plain-text value with caching disabled.

Before upload, build a release snapshot containing only allowlisted source files. The checkout also contains private local configuration, captured data and research; none belongs in a provider source upload. A running local app, a passing container build and a successful public deployment are separate verification steps.

See [prototype registration](prototype-registration.md) for the prepared description, policy/verification requirements and review flow. Actual provider resource IDs, deployed URLs, applied backup settings and hosted smoke results belong in the release record once verified.

### Private backup job on Hobby

Build `ops/postgres-backup/Dockerfile` with `ops/postgres-backup` as its context. Configure a private Railway service to run it once daily, with no domain, public port, web healthcheck or long-running process. Use PostgreSQL's private `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` and `PGPASSWORD` references. Keep bucket-scoped `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`, `BACKUP_S3_ENDPOINT` (HTTPS) and `BACKUP_S3_BUCKET` in this service's runtime environment only. Use a dedicated private bucket; no public read policy or browser access is needed.

Each run writes a PostgreSQL 17 custom-format dump, uploads it under `league-analysis/postgres/`, checks its SHA-256 metadata and downloads it to verify the full checksum. Only after verification does it remove matching backups older than seven days. Failed dump/upload/verification steps exit before retention. Local dump files have private permissions and are removed when the process exits. The script does not write to the source database or put passwords in command arguments.

Before relying on the schedule, download a successful backup through authenticated storage access and restore it with `pg_restore --no-owner --no-acl --exit-on-error` into a separate, empty PostgreSQL 17 database. Never use the application database as a restore test target. Before serving any restored database, [reconcile it with the current external removal ledger](player-data-removal.md#backup-expiry-interrupted-removal-and-restore); never restore an old ledger alongside it. Record the restored schema/data checks and schedule status in the deployment receipt. Container behavior tests run with `python3 ops/postgres-backup/backup.test.py`. The updated retention log must report `remaining_expired=0`; upload success alone is insufficient.

## Verified release status

The pre-release prototype is hosted at **https://match-analysis-v1.vercel.app**. The backend is **https://backend-production-8941a.up.railway.app** and requires the frontend's service credential for application routes. Only status-only health is anonymously accessible. PostgreSQL has no public TCP proxy, service domain or custom domain. Backend and backup connections use Railway's private PostgreSQL hostname in the same US East region. No credentials belong in either URL.

Verification on September 10, 2026 (America/New_York) covered:

- Real `KitingInYourLane#000` lookup returned five matches. The reviewed `NA1_5634751360` and another victory loaded through the deployed frontend and authenticated backend.
- Desktop 1440×1000 and narrow 390×844 pages loaded without page overflow. Metric keyboard controls, comparison changes, refresh/back, result switching, item/spell tooltips and the labeled synthetic sample passed browser checks with no page exceptions. The 24:00–26:00 interval displayed 72 events retaining all 74 underlying records; all 36 samples remained accessible in the centered scrollable table.
- Missing and incorrect service credentials returned 401. Authenticated application access and frontend health returned 200. Public pages, API projections, 13 loaded JavaScript chunks and 387 release files were scanned for the actual Riot, service, database and bucket credentials; none were found. Public projections contained no PUUID fields.
- The private backup service is scheduled daily at **07:00 UTC**, retains seven days of matching backups, and completed two verification runs. The real-data archive restored six matches, 60 participants, 1,660 sampled observations and 4,870 event records into an isolated PostgreSQL 17.11 database. The restored reviewed match reproduced team totals `31/39/39`, `70,021` gold and `39/31/37`, `69,681` gold. Anonymous object retrieval returned 403. The temporary restore database was removed afterward.

The repository is private at **https://github.com/liamkinnally/league-match-analysis**. The first release was uploaded from a fresh allowlisted checkout without the working repository's private captures or history. Deployments currently use authenticated CLI source uploads: the providers' GitHub integrations did not have access to this new private repository, so automatic deploy-on-push is not connected. Do not grant broad repository access as a workaround. Connect this specific repository later if automatic deployments are desired.

For a frontend release, use the linked Vercel project with repository root `frontend` and run `vercel deploy --prod` from the clean repository root. For backend releases, use `railway up` targeting the existing backend service from the clean repository root. The backup job uploads only `ops/postgres-backup` as its source root. Never upload the original working checkout's ignored files or use `--no-gitignore`. Verify health and a real match after each release. Do not leave one-time seeding commands configured for subsequent deploys.

Operational follow-up remains explicit: Railway's dashboard showed trial credits despite the workspace API reporting Hobby, so confirm billing continuity before credits expire. A development Riot key still needs renewal; a personal key can avoid that daily task. The prepared Riot registration has not been submitted, and `/riot.txt` intentionally remains 404 until its portal-issued public verification value is supplied. These are not claims of production-key approval. The broader redesign and deferred statistics remain outside this release.
