# Riot registration and review

Reference details and review steps for LoL Match Analysis. The application UI displays “League Match Analysis”. API access, including a personal key, does not imply production-key approval. LoL Match Analysis is not endorsed by Riot Games.

## Product details

| Field | Value |
| --- | --- |
| Product name | LoL Match Analysis |
| Game | League of Legends |
| Stage | Prototype for development, testing and review |
| Contact | lol.match.analysis@gmail.com |
| Website | [lolmatchanalysis.app](https://lolmatchanalysis.app) |
| Privacy Policy | [Privacy Policy](https://lolmatchanalysis.app/privacy) |
| Terms of Service | [Terms of Service](https://lolmatchanalysis.app/terms) |
| Verification | [riot.txt](https://lolmatchanalysis.app/riot.txt), the endpoint for the portal-issued public value |

Product description:

> LoL Match Analysis lets players inspect how a completed League of Legends match developed. A Riot ID search retrieves NA1 Ranked Solo/Duo, Flex, Draft, Swiftplay and ARAM history across supported queues by default, with an optional Queue Type filter and pages of up to twenty matches. Older history is loaded explicitly, with an account refresh cooldown and timelines fetched when a match is opened. The match page shows final team results, a ten-player scoreboard, builds and current queue-specific ranks. Players can compare recorded gold, CS and XP differences over time and inspect timestamped events within a selected interval. Missing data remains explicit, current ranks are not presented as historical ranks or MMR, and the app makes no causal coaching or replay claims. A clearly labeled synthetic sample is also available.

## Data and API use

| Source | Use |
| --- | --- |
| Account-V1 `/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}` | Resolve submitted Riot ID server-side |
| Match-V5 `/lol/match/v5/matches/by-puuid/{puuid}/ids` | Up to twenty raw match IDs per page, optional queue filter, AMERICAS routing |
| Match-V5 `/lol/match/v5/matches/{matchId}` | Final results and participant data |
| Match-V5 `/lol/match/v5/matches/{matchId}/timeline` | Recorded events and sampled timeline values |
| League-V4 `/lol/league/v4/entries/by-puuid/{puuid}` | Current queue-specific NA1 ranks |
| Riot Data Dragon and game-asset CDNs | Champion, item, spell, objective and rank images |

Riot API keys are backend runtime secrets. Public projections omit PUUIDs and raw captures. Matches/timelines persist in PostgreSQL; completed searches are cached until an explicit update, with a 15-minute account update cooldown and current ranks have a 5-minute process cache. Provider cooldown and finite queues constrain ingestion. The application has no Riot account sign-in, user passwords, payments or advertising. The policy pages disclose manual data retention and hosting providers.

[Player-data removal](player-data-removal.md) requires an operator-verified request and removes whole affected matches for all participants. Database backups can retain earlier copies until a successful scheduled run removes archives older than seven days. Restoring them requires the current external removal ledger.

## Review flow

1. Open the HTTPS homepage; confirm the Prototype label and policy links.
2. Search for an NA1 Riot ID. Loading may expose completed matches while remaining matches are retrieved. Open a result.
3. Inspect final team kills/KDA/gold/objectives and all ten participants. Current-rank coverage and unavailable values are disclosed.
4. Change the focal player, opponent, metric and selected interval. Open sampled values and events; inspect item/spell/objective tooltips and record details.
5. Refresh or use browser back; the relevant selection remains in URL state. Check a narrow screen.
6. Use the independently labeled sample link when a deterministic invented example is useful; it does not stand in for live lookup verification.

## Ownership verification

Riot's [verification instructions](https://support-developer.riotgames.com/hc/en-us/articles/22801461443091-Verification-for-Production-Applications) describe a portal-issued text value after application submission. Put that public value in the frontend runtime variable `RIOT_VERIFICATION_TOKEN`, then redeploy. Never use `RIOT_API_KEY` for this field. Check that the final website's `/riot.txt` returns HTTP 200 and exactly the supplied plain-text value without requiring an account. Then complete verification in the portal.

The endpoint returns 404 while unset or invalid. Publish only the portal-issued value.

Riot's [application guidance](https://support-developer.riotgames.com/hc/en-us/articles/22801383038867-Production-Key-Applications) calls for a functioning website with visible user flows and policy pages. Submission, ownership verification and production-key approval are separate steps. Record their status in the private operator record; treat production approval as granted only when Riot confirms it.
