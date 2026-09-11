# LoL Match Analysis

A League of Legends match-history and timeline application built with Java, Spring Boot, Next.js and PostgreSQL. Look up a player, open a match, and compare how two champions’ gold, CS and experience changed over time alongside recorded events.

[Open the demo](https://lolmatchanalysis.app) — [Architecture](docs/architecture.md) — [Development guide](docs/developer-guide.md)

The hosted prototype is intended for development, testing, and review—not intended for general public use. No Riot account sign-in is required. The included synthetic match can be explored without an API key.

## Match review

- NA1 ranked Solo/Duo lookup with the player’s five most recent matches.
- Final team scores, gold, objectives and a ten-player scoreboard with identities, builds and current queue-specific ranks when available.
- An area chart for gold, CS or XP differences, with player, opponent and interval selections preserved in the URL.
- Timestamped kills, objectives, item changes and ward events, plus expandable source details and sampled values.

![Synthetic sample match with final results and a gold-difference timeline](docs/images/sample-development.png)

*The screenshots use the built-in synthetic match. Missing source fields are shown as unavailable.*

<details>
<summary>Events and scoreboard</summary>

![Synthetic sample events and ten-player scoreboard](docs/images/sample-scoreboard.png)

</details>

## Architecture

```text
Browser → Next.js server → Spring Boot → PostgreSQL
                                ↓
                           Riot Games API
```

| Layer | Responsibilities |
| --- | --- |
| Next.js 16, React 19, TypeScript | Server-rendered pages, response validation, URL state and interactive match views |
| Recharts, shadcn chart component, Tailwind CSS | Timeline chart and interface styling |
| Java 21, Spring Boot 4, JDBC, Flyway | Riot ingestion, normalization, match calculations, schema migrations and private operator commands |
| PostgreSQL 17 | Player identities, lookup runs, normalized match data and original API captures |
| Vercel and Railway | Frontend hosting; one persistent backend, private PostgreSQL and scheduled backups |

The browser calls Next.js. Server-side requests authenticate to the backend with a separate service token; Riot keys and database credentials stay on the server. Ingestion retains raw responses separately from normalized records so match calculations can be traced to their source. See [architecture](docs/architecture.md) and [lookup behavior](docs/public-match-lookup.md).

## Run locally

The packaged app needs Docker with Compose and Git. From a fresh clone:

```sh
git clone https://github.com/liamkinnally/league-match-analysis.git
cd league-match-analysis
test -e .env || cp .env.example .env
docker compose --project-name league-analysis-app --file compose.app.yaml --env-file .env up --detach --build --wait
docker compose --project-name league-analysis-app --file compose.app.yaml --env-file .env run --rm --no-deps backend --spring.main.web-application-type=none --seed-demo
```

Open [localhost:3416](http://127.0.0.1:3416) and choose **Explore sample match**. Keep `RIOT_API_KEY` blank and `RIOT_PUBLIC_LOOKUP_ENABLED=false` for the sample. Startup applies migrations; the explicit, idempotent seed command adds the synthetic match. PostgreSQL data persists in a named volume.

For source development, install Java 21 JDK and Node.js 24 in addition to Docker, then run:

```sh
./scripts/setup
./scripts/dev app
# In a second terminal, after PostgreSQL is ready:
./scripts/seed-demo
```

Open [localhost:3000](http://127.0.0.1:3000). Setup installs locked frontend dependencies and Playwright Chromium, and creates missing environment files without replacing existing values. See the [development guide](docs/developer-guide.md) for API routes, local ingestion and test fixtures.

To enable Riot lookup, configure `RIOT_API_KEY` and `RIOT_PUBLIC_LOOKUP_ENABLED=true` only in the backend’s environment. Personal API access and production approval are separate; a personal key does not establish Riot approval. [Deployment](docs/deployment.md) covers service authentication, private networking and runtime configuration.

## Tests

```sh
./scripts/verify full
./scripts/package-smoke
python3 scripts/tests/export_policy_test.py
```

The suite covers backend and frontend behavior, disposable PostgreSQL integration, browser interactions, pinned visual snapshots and the packaged application. CI runs lint, type checking, tests and production builds. [View CI](https://github.com/liamkinnally/league-match-analysis/actions/workflows/ci.yml) or use the [focused commands](docs/developer-guide.md#verification) during development.

## Limits and data handling

- Live lookup supports NA1 / AMERICAS, ranked Solo/Duo (queue 420), and five recent matches. Provider errors or rate limits can prevent lookup or current-rank retrieval.
- Current ranks are queue-specific snapshots, not historical ranks or MMR. Unranked and unavailable results are distinct.
- Timeline samples do not describe every instant. Missing data remains a gap; suggested intervals identify recorded changes without claiming their cause.
- Patch-matched game assets fall back to names or IDs when unavailable. The synthetic sample has fewer recorded fields than a real match.
- Match data and raw captures have no automatic expiry. Successful backup jobs remove archives older than seven days. The [private removal workflow](docs/player-data-removal.md) supports dry-run review, whole-match deletion, future exclusion and guarded backup recovery.

[Privacy Policy](https://lolmatchanalysis.app/privacy) — [Terms](https://lolmatchanalysis.app/terms) — [Third-party notices](THIRD_PARTY_NOTICES.md)

## Riot notice

LoL Match Analysis isn't endorsed by Riot Games and doesn't reflect the views or opinions of Riot Games or anyone officially involved in producing or managing Riot Games properties. Riot Games, and all associated properties are trademarks or registered trademarks of Riot Games, Inc.
