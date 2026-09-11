# Private player-data removal

This workflow is for the operator's authenticated shell. It has no HTTP admin endpoint, browser control, or public request form. Implementation/testing does not itself remove any hosted data. Use the reviewed backend containing migration V6 and these commands; do not deploy unrelated working-tree changes.

## What is removed

The command resolves either an exact stored PUUID or an exact NA1 match ID plus participant number. It verifies the identifier against retained account/participant evidence. It does not authenticate a requester or decide whether their request should be honored: the operator must first verify the request and its scope. Riot ID alone, champion name, fuzzy names, unsupported platforms, unknown participants and conflicting evidence are refused.

The dry run lists whole affected match IDs and counts by table. **Every listed match disappears for all participants**, including players who did not request removal. This avoids broken scoreboards, timelines and partial raw records. Unrelated matches remain. A co-player's identity is re-anchored to retained evidence when its latest capture was in a removed match; an identity without any remaining evidence is removed. Conflicting membership or retained records with no safe provenance cause refusal, not wider deletion.

| Storage | Handling |
| --- | --- |
| `riot_identity` | Remove subject identity; repair retained co-player provenance or remove newly orphaned identity |
| `riot_match`, `riot_team`, `riot_participant` | Remove whole affected match, teams and participants |
| `participant_state_observation`, `match_event`, `evidence_coverage` | Remove affected match children, including JSON event/coverage details |
| `source_capture` | Remove all relevant historical captures, not only current match references |
| `source_payload` | Remove affected payload variants and duplicate/shared capture references, including raw-only failed captures and orphan payloads |
| `ingestion_run`, `ingestion_item` | Remove subject lookup records and affected match membership from other players' histories; remove raw match-list captures mentioning affected matches |
| Lookup cache | Same durable run/item records, removed with the above |
| Rank cache, active requests, queued jobs | Backend must stop before execution; normal restart starts with empty process state |

An unresolved new lookup keeps its submitted name only in process memory until Account-V1 verifies an allowed PUUID. A restarted failed lookup without verified identity is displayed as “Player lookup.” Excluded account responses are discarded before capture. Other players' lookups skip known excluded matches and inspect newly returned participant PUUIDs before storing match data. A raw match-list response containing an excluded match is omitted, never silently edited into fabricated source evidence. The remaining supported matches still import in their original order.

Choose the mode explicitly:

- `--mode exclude`: remove current records and prevent future imports for the verified PUUID, including renamed accounts and matches found through another player's lookup. Match hashes accelerate skipping. A reused Riot ID belonging to a different verified PUUID is allowed.
- `--mode erase-only`: remove current records without promising future exclusion. Later lookup can import this player's data again. Keep the ledger so restoring an old backup still requires explicit reconciliation.

The command defaults to a read-only, repeatable-read dry run. It does not start Spring, recover interrupted lookups, seed data, migrate the database, or write a ledger. Explicit execution of `initialize` or `prepare-restore` is the only schema-migration exception; neither removes player records.

## First installation and Railway maintenance

Use the existing private Railway database connection. Do not enable a public PostgreSQL proxy. Secrets remain the backend's server-side `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`; the removal command does not require the Riot key. Never put credentials or a PUUID in a command argument.

1. Build/test the reviewed backend and prepare a clean release checkout using the existing deployment workflow. V6 adds privacy tables and write guards. Keep the previous public application offline while changing versions; do not roll back to code predating the privacy guard after completing a removal.
2. In Railway backend settings, record the normal start/healthcheck settings, temporarily set the custom Start Command to `/bin/sleep infinity`, and temporarily clear the healthcheck path. Deploy the reviewed image in that maintenance configuration. Retain the existing `/data` volume and one replica. This starts an operator-accessible container with **no Java application or HTTP listener**. Pause the backup schedule for the short execution window and wait for any current backup to finish. Other database consoles must disconnect before execution.
3. Enter the container through the authenticated Railway CLI:

   ```sh
   railway ssh --service backend --environment production
   ```

4. Provision `/data/player-removal` once with owner UID/GID `10001:10001` and mode `0700`, then run the operator command as that non-root application user. If the existing root-owned Railway mount prevents creation, use `RAILWAY_RUN_UID=0` **only for the sleep-only maintenance deployment**, run the command below in its private shell, remove that UID override, and redeploy the sleep-only maintenance container before initializing the ledger. Do not run the normal web application as root.

   ```sh
   install -d -m 0700 -o 10001 -g 10001 /data/player-removal
   ```

5. Set the backend runtime variable `PLAYER_REMOVAL_LEDGER_FILE=/data/player-removal/ledger.json`. Railway's application profile uses this path by default. Inside the non-root maintenance shell, initialize only once:

   ```sh
   java -jar /app/application.jar --player-removal initialize --ledger /data/player-removal/ledger.json
   java -jar /app/application.jar --player-removal initialize --ledger /data/player-removal/ledger.json --execute --confirm INITIALIZE-PRIVATE-LEDGER
   java -jar /app/application.jar --player-removal check --ledger /data/player-removal/ledger.json
   ```

Initialization refuses an already initialized checkpoint or a ledger containing removals. Never replace a missing ledger with an empty one to make a restored database start. Initialization writes no player deletions, but its explicit execution applies schema migrations. Save a current, access-controlled copy of the ledger outside the database backup system.

Railway's [custom command replaces the Docker entrypoint](https://docs.railway.com/deployments/start-command), [volumes are mounted at runtime with root ownership](https://docs.railway.com/volumes), and [deployments without a configured healthcheck become active when the container starts](https://docs.railway.com/deployments/reference). These provider steps are documented deployment preparation, not a claim that a hosted removal was performed during implementation.

## Dry run, execute, check

The following example identifiers are placeholders; select the match and participant that the operator verified for the request. Do not execute the example against a real database without a corresponding verified request.

```sh
java -jar /app/application.jar --player-removal remove \
  --ledger /data/player-removal/ledger.json \
  --match-id NA1_1234567890 --participant 1 --mode exclude
```

The private JSON report contains `DRY_RUN`, `matchesRemovedForAllParticipants`, `affectedRecords` and a `confirmation` fingerprint. Review every match ID, the match/participant totals, identity re-anchors, captures, payloads and lookup counts. It prints no PUUID or player names. If the affected set is unexpected, stop. A dry run can run while the app is available; execution requires the maintenance configuration above.

Copy the exact confirmation from that dry run, then execute the same request:

```sh
java -jar /app/application.jar --player-removal remove \
  --ledger /data/player-removal/ledger.json \
  --match-id NA1_1234567890 --participant 1 --mode exclude \
  --execute --confirm REPLACE_WITH_THE_DRY_RUN_CONFIRMATION

java -jar /app/application.jar --player-removal check \
  --ledger /data/player-removal/ledger.json
```

Any database data change between planning and execution invalidates the fingerprint. Re-run the dry run and review it again. No execution is accepted merely because `--execute` was present. The command refuses when the backend's lifetime lease or another database client remains active; it never kills sessions for the operator. All deletion, exclusion inserts and completion/checkpoint writes commit together. A post-removal rescan must find zero affected records before commit.

For raw-only cases with no normalized match participant, privately create a `0600` file containing only the verified PUUID. Pass its path, not its contents:

```sh
umask 077
# Populate /data/player-removal/subject.txt through the private operator editor.
java -jar /app/application.jar --player-removal remove \
  --ledger /data/player-removal/ledger.json \
  --puuid-file /data/player-removal/subject.txt --mode exclude
```

Use the same flags plus the reviewed `--execute --confirm ...` for execution, then delete that temporary subject file. For a source checkout, build with `./scripts/verify task backend` and use `./scripts/player-removal` in place of `java -jar /app/application.jar --player-removal`. It uses only explicitly exported server-side datasource variables and does not source `.env`, connect to Railway automatically, or restart services.

After `COMPLETE` and `LEDGER_SYNCHRONIZED`:

1. Save/verify a current private copy of the ledger independently of PostgreSQL dumps, including after every later removal. Keep only that minimal ledger and the database's completion receipt; discard temporary subject files and detailed dry-run output. Do not copy requester messages into the ledger.
2. Restore the normal Java start command by removing the sleep override, restore `/actuator/health`, retain the ledger environment/path and redeploy. The startup guard compares the ledger digest, operation receipts and required exclusions before lookup recovery or serving. A lost lifetime database connection also latches the old process unavailable; restart is required, so recovered connections cannot reuse old caches to bypass maintenance.
3. Resume the backup schedule. Open each affected match URL: it must be unavailable. The former lookup URL must be unavailable, and another participant's history must omit the deleted matches. Verify an unrelated match still works. For `exclude`, search the removed player and a co-player: removed data must not return; a co-player's unrelated matches must remain available. Use a private browser session to avoid viewing previously delivered client memory.

Backend writes also take a transaction maintenance lock and check exclusions before payload persistence. The operator takes exclusive locks and table locks. Tests cover an INSERT already waiting during removal, not merely an INSERT started afterward. PostgreSQL [session and transaction lock behavior](https://www.postgresql.org/docs/17/explicit-locking.html) informs this boundary.

## Backup expiry, interrupted removal and restore

Removal is logical deletion from the application database. Previously created backups can still contain the data until the seven-day retention window expires. Do not restore or use those archives for analytics. If the request requires earlier backup disposal, the operator must privately identify and delete the affected pre-removal archives (including any separately retained copies/object versions) and verify their absence. The application command does not claim secure erasure of PostgreSQL free pages, WAL, provider snapshots, browser copies, or records already delivered to another person.

The authoritative ledger is **outside** the PostgreSQL dump on the backend volume. It contains only a version, random ledger/operation IDs, timestamps, mode and SHA-256 matching keys for verified PUUIDs, aliases and affected matches. These hashes are pseudonymous data, not anonymization; alias hashes may be guessable. The file must remain `0600` in a private directory. The database completion receipt keeps only operation ID, time, mode and aggregate counts. Neither is exposed by the application. Do not restore an older ledger together with an older database.

The command atomically writes and fsyncs ledger intent before committing deletion. If it crashes afterward, the ledger and database checkpoint disagree and normal startup refuses. A crash after database commit but before printing success is checked using `check`; do not blindly submit a second removal. If synchronization fails, keep the backend in maintenance and run the explicit reconciliation below.

Restore procedure:

1. Keep the application in sleep-only maintenance. Preserve the **current authoritative ledger** and its independently verified private copy. If the current ledger is unavailable, stop; do not initialize a replacement or bypass the guard.
2. Restore the selected archive into a separate empty PostgreSQL 17 database first, using `pg_restore --no-owner --no-acl --exit-on-error`. Keep its ingress private. Point only the operator process at that restore database using server-side datasource variables. If the archive predates V6, prepare its schema without starting the application or resetting the ledger:

   ```sh
   java -jar /app/application.jar --player-removal prepare-restore --ledger /data/player-removal/ledger.json
   java -jar /app/application.jar --player-removal prepare-restore --ledger /data/player-removal/ledger.json \
     --execute --confirm REPLACE_WITH_THE_SCHEMA_PREPARATION_CONFIRMATION
   ```

   This only applies reviewed migrations. Its result is `SCHEMA_PREPARED_RECONCILIATION_REQUIRED`; continue with the reconciliation below before serving.
3. Run the following against the restore database and the current ledger. Review the entire replay set before explicit execution:

   ```sh
   java -jar /app/application.jar --player-removal reconcile --ledger /data/player-removal/ledger.json
   java -jar /app/application.jar --player-removal reconcile --ledger /data/player-removal/ledger.json \
     --execute --confirm REPLACE_WITH_THE_RECONCILIATION_CONFIRMATION
   java -jar /app/application.jar --player-removal check --ledger /data/player-removal/ledger.json
   ```

4. Reconciliation replays **all** ledger removals against restored normalized and raw records, restores required ongoing exclusions and records the current checkpoint. Even an `erase-only` operation is replayed during restore; the dry run discloses its impact on records present in that restore. With an interrupted intent, replay completes that intended removal. It never silently clears the mismatch.
5. Verify affected/unrelated records, then promote only the reconciled database using the normal private deployment workflow. Restart the current guarded application and check the public URLs and lookup behavior again. Never enable serving while the ledger check is unresolved.

## Retention verification

The existing private backup job is scheduled daily at 07:00 UTC. Read-only inspection on September 11, 2026 found successful jobs reaching the end of the existing retention loop. Its older logs did not report expired-object counts, so they do not establish that any expired object was present or deleted.

For read-only deployment verification, run the updated backup image's entrypoint with its existing private runtime environment:

```sh
/usr/local/bin/league-analysis-backup --dry-run
```

This checks the database connection in a read-only transaction, lists all pages using the same exact backup-name and seven-day retention filters, and labels eligible objects `retain` or `would-delete`. The `DRY RUN retention` summary reports `matching`, `expired` and `would_delete` counts. It performs no dump, upload, download or deletion and does not establish that expired objects were removed. Leave the scheduled job's normal command without arguments to preserve its backup and cleanup behavior; other arguments are refused.

The updated script logs, after verified upload and successful cleanup:

```text
Retention verified: matching=2 expired=1 deleted=1 remaining_expired=0
PostgreSQL backup uploaded and verified
```

It lists all pages, removes only exactly named expired dumps, lists again, and fails if an expired object remains or any listing/deletion fails. A recent-only bucket legitimately reports zero deletions. After separately deploying the updated backup image, inspect the next scheduled run for `remaining_expired=0`; if expired objects existed, require matching nonzero expired/deleted counts. Do not infer a successful cleanup from upload success alone.

Verification commands (disposable fixtures, no production credentials):

```sh
./scripts/verify focused backend -Dtest=RemovalPlannerIntegrationTest,PlayerRemovalCommandTest,RemovalLedgerTest,PrivacyExclusionIntegrationTest,PrivacyAwareIngestionIntegrationTest test
./scripts/verify task backend
./scripts/verify task frontend
python3 ops/postgres-backup/backup.test.py
python3 scripts/tests/export_policy_test.py
```

The backup test suite also runs inside the production-style backup image with network disabled; its real AWS CLI paginator talks only to a synthetic loopback server. The removal suite uses disposable PostgreSQL containers, including a real `pg_dump`/`pg_restore` round trip, shared and unrelated matches, duplicates, raw-only and orphan captures, interrupted requests, stale confirmations, ambiguous provenance, maintenance contention and future reimport attempts. This workflow intentionally scans retained data in memory for a complete dry-run report; reassess that implementation before using it on a much larger database.
