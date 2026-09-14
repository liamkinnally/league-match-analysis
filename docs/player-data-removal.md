# Player-data removal

LoL Match Analysis includes a private operator workflow for removing stored player data. It is intentionally not exposed as a public HTTP admin endpoint.

## What the workflow does

A removal starts from a verified stored participant identity. Before any write, the command produces a dry-run report showing the matches and record counts that would be affected.

When executed, the workflow removes complete affected matches rather than deleting one participant from an otherwise intact match. This keeps scoreboards, timelines, source captures and normalized records internally consistent.

Two modes are supported:

- `exclude` removes the current data and records the verified player so future imports are skipped.
- `erase-only` removes the current data without blocking a later import.

The workflow also handles related lookup records and source captures. An external ledger stores the minimum information needed to preserve removal decisions across database restores.

## Safety checks

The operator command is designed around a few rules:

- dry run first;
- explicit confirmation before execution;
- refuse ambiguous or conflicting identity evidence;
- remove all affected records in one transaction;
- verify that affected records are gone before committing;
- keep the removal ledger outside PostgreSQL backups;
- prevent normal application startup when the database and ledger disagree.

The command never needs a Riot API key to perform a removal.

## Operator flow

The usual workflow is:

1. Verify the request and the stored participant identity privately.
2. Stop normal backend writes for the execution window.
3. Run the removal command without `--execute` and review the dry-run result.
4. Run the same request with `--execute` and the confirmation value from the dry run.
5. Run the ledger/database consistency check.
6. Restart the normal backend and verify that removed matches are unavailable while unrelated matches still work.

A source checkout exposes the command through:

```bash
./scripts/player-removal
```

The exact production maintenance steps are intentionally kept out of this public repository documentation because they depend on the active Railway configuration and private credentials.

## Backups and restores

Deleting application rows does not retroactively erase older backup files. The scheduled backup job keeps a short retention window and deletes expired archives after a successful new backup.

Any database restored from a backup must be reconciled against the current external removal ledger before the application serves traffic. This prevents a restore from silently reintroducing data that had already been removed.

The removal ledger is private operational data. It is not exposed through the application or included in the normal repository.

Current profile data includes minimized profile icon and level observations, current ranked records, and discrete rank/LP observations collected during requested lookups. These follow the same manual retention and removal policy as stored matches. Removing a stored identity also removes its profile cache and rank observations; backup restoration must reconcile these records against the external removal ledger before serving traffic. No separate raw Summoner or League response archive is retained for this feature.
