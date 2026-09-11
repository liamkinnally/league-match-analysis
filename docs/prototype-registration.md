# Prototype registration and Riot review

This is a prepared registration package for the verified hosted prototype. It is not a submitted application or a claim of Riot approval.

## Product details

| Field | Prepared value |
| --- | --- |
| Product name | match-analysis-v1 |
| Game | League of Legends |
| Stage | Functional pre-release prototype for testing and Riot review |
| Operator | Liam Kinnally |
| Contact | lol.match.analysis@gmail.com |
| Website | https://league-match-analysis.vercel.app |
| Privacy Policy | https://league-match-analysis.vercel.app/privacy |
| Terms of Service | https://league-match-analysis.vercel.app/terms |
| Verification | https://league-match-analysis.vercel.app/riot.txt (404 until the portal value is supplied) |

Suggested product description:

> match-analysis-v1 lets players inspect how a completed League of Legends match developed. A Riot ID search retrieves the latest five NA1 ranked Solo/Duo matches. The match page shows final team results, a ten-player scoreboard, builds and current queue-specific ranks. Players can compare recorded gold, CS and XP differences over time and inspect timestamped events within a selected interval. Missing data remains explicit, current ranks are not presented as historical ranks or MMR, and the app makes no causal coaching or replay claims. A clearly labeled synthetic sample is also available. This hosted version is an unpromoted pre-release prototype for testing and Riot review.

## Data and API use

| Source | Use |
| --- | --- |
| Account-V1 `/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}` | Resolve submitted Riot ID server-side |
| Match-V5 `/lol/match/v5/matches/by-puuid/{puuid}/ids` | Latest five ranked Solo/Duo match IDs, AMERICAS routing |
| Match-V5 `/lol/match/v5/matches/{matchId}` | Final results and participant data |
| Match-V5 `/lol/match/v5/matches/{matchId}/timeline` | Recorded events and sampled timeline values |
| League-V4 `/lol/league/v4/entries/by-puuid/{puuid}` | Current queue-specific NA1 ranks |
| Riot Data Dragon and game-asset CDNs | Champion, item, spell, objective and rank images |

Keys are backend runtime secrets. Public projections omit PUUIDs and raw captures. Matches/timelines persist in PostgreSQL; completed searches are reused for 15 minutes and current ranks have a 5-minute process cache. Provider cooldown and finite queues constrain ingestion. No RSO, passwords, payments or advertising are part of this prototype. The policy pages disclose manual data retention and hosting providers.

## Review flow

1. Open the HTTPS homepage; confirm the pre-release label and policy links.
2. Search for an NA1 Riot ID. Loading may expose completed matches while remaining matches are retrieved. Open a result.
3. Inspect final team kills/KDA/gold/objectives and all ten participants. Current-rank coverage and unavailable values are disclosed.
4. Change the focal player, opponent, metric and selected interval. Open sampled values and events; inspect item/spell/objective tooltips and record details.
5. Refresh or use browser back; the relevant selection remains in URL state. Check a narrow screen.
6. Use the independently labeled sample link when a deterministic invented example is useful; it does not stand in for live lookup verification.

## Ownership verification

Riot's [verification instructions](https://support-developer.riotgames.com/hc/en-us/articles/22801461443091-Verification-for-Production-Applications) describe a portal-issued text value after application submission. Put that public value in the frontend runtime variable `RIOT_VERIFICATION_TOKEN`, then redeploy. Never use `RIOT_API_KEY` for this field. Check that the final website's `/riot.txt` returns HTTP 200 and exactly the supplied plain-text value without requiring an account. Then complete verification in the portal and record the observed status.

The endpoint deliberately returns 404 while unset. That does not prevent hosting the prototype before the portal issues a verification value. No fake value should be published merely to turn the response into 200.

Riot's [application guidance](https://support-developer.riotgames.com/hc/en-us/articles/22801383038867-Production-Key-Applications) calls for a functioning website with visible user flows and policy pages. Prepare the website first; submitting the registration and completing portal verification are explicit separate actions. Production approval remains pending until Riot confirms it.
