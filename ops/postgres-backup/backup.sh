#!/usr/bin/env bash
set -euo pipefail

umask 077

dry_run=false
if [[ "$#" -eq 1 && "$1" == "--dry-run" ]]; then
  dry_run=true
elif [[ "$#" -ne 0 ]]; then
  echo "Usage: league-analysis-backup [--dry-run]" >&2
  exit 2
fi

: "${PGHOST:?PGHOST is required}"
: "${PGPORT:?PGPORT is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${AWS_ACCESS_KEY_ID:?AWS_ACCESS_KEY_ID is required}"
: "${AWS_SECRET_ACCESS_KEY:?AWS_SECRET_ACCESS_KEY is required}"
: "${AWS_DEFAULT_REGION:?AWS_DEFAULT_REGION is required}"
: "${BACKUP_S3_ENDPOINT:?BACKUP_S3_ENDPOINT is required}"
: "${BACKUP_S3_BUCKET:?BACKUP_S3_BUCKET is required}"

case "$BACKUP_S3_ENDPOINT" in
  https://*) ;;
  *) echo "BACKUP_S3_ENDPOINT must use HTTPS" >&2; exit 2 ;;
esac

readonly backup_prefix="league-analysis/postgres"
if [[ "$dry_run" == true ]]; then
  echo "DRY RUN: checking database read-only and listing backup retention"
  PGOPTIONS="${PGOPTIONS:+$PGOPTIONS }-c default_transaction_read_only=on" \
    psql --no-psqlrc --no-password --set ON_ERROR_STOP=1 --quiet \
      --command 'BEGIN READ ONLY; SELECT 1; ROLLBACK;' >/dev/null
  echo "DRY RUN database connection verified read-only"
else
  timestamp=$(date -u +%Y%m%dT%H%M%SZ)
  object_key="$backup_prefix/backup-$timestamp.dump"
  dump_file=$(mktemp "${TMPDIR:-/tmp}/league-analysis-backup.XXXXXX")
  verified_file=$(mktemp "${TMPDIR:-/tmp}/league-analysis-backup.XXXXXX")
  trap 'rm -f "$dump_file" "$verified_file"' EXIT

  pg_dump --format=custom --file "$dump_file"
  checksum=$(sha256sum "$dump_file")
  checksum=${checksum%% *}

  aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3 cp \
    "$dump_file" "s3://$BACKUP_S3_BUCKET/$object_key" \
    --metadata "sha256=$checksum" --no-progress --only-show-errors

  remote_checksum=$(aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3api head-object \
    --bucket "$BACKUP_S3_BUCKET" --key "$object_key" \
    --query 'Metadata.sha256' --output text)
  if [[ "$remote_checksum" != "$checksum" ]]; then
    echo "Uploaded backup verification failed" >&2
    exit 1
  fi

  aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3 cp \
    "s3://$BACKUP_S3_BUCKET/$object_key" "$verified_file" \
    --no-progress --only-show-errors
  verified_checksum=$(sha256sum "$verified_file")
  verified_checksum=${verified_checksum%% *}
  if [[ "$verified_checksum" != "$checksum" ]]; then
    echo "Downloaded backup verification failed" >&2
    exit 1
  fi
fi

cutoff=$(date -u -d '7 days ago' +%Y%m%dT%H%M%SZ)

list_backup_keys() {
  # Keep AWS CLI automatic pagination enabled. JSON preserves unrelated keys
  # containing whitespace; only exact backup filenames reach the shell loop.
  aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3api list-objects-v2 \
    --bucket "$BACKUP_S3_BUCKET" --prefix "$backup_prefix/" \
    --query 'Contents[].Key' --output json | python3 -c '
import json, re, sys
keys = json.load(sys.stdin)
if keys is None:
    keys = []
if not isinstance(keys, list) or not all(isinstance(key, str) for key in keys):
    sys.exit("Invalid backup object listing")
for key in keys:
    if re.fullmatch(r"league-analysis/postgres/backup-[0-9]{8}T[0-9]{6}Z\.dump", key):
        print(key)
'
}

keys=$(list_backup_keys)
matching=0
expired=0
deleted=0
while IFS= read -r key; do
  [[ -n "$key" ]] || continue
  matching=$((matching + 1))
  backup_timestamp=${key##*/backup-}
  backup_timestamp=${backup_timestamp%.dump}
  if [[ "$backup_timestamp" < "$cutoff" ]]; then
    expired=$((expired + 1))
    if [[ "$dry_run" == true ]]; then
      echo "DRY RUN would-delete: $key"
    else
      aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3api delete-object \
        --bucket "$BACKUP_S3_BUCKET" --key "$key" >/dev/null
      deleted=$((deleted + 1))
    fi
  elif [[ "$dry_run" == true ]]; then
    echo "DRY RUN retain: $key"
  fi
done <<< "$keys"

if [[ "$dry_run" == true ]]; then
  echo "DRY RUN retention: matching=$matching expired=$expired would_delete=$expired"
  echo "DRY RUN complete: no dump, upload, download or deletion performed"
  exit 0
fi

remaining_keys=$(list_backup_keys)
remaining_expired=0
while IFS= read -r key; do
  [[ -n "$key" ]] || continue
  backup_timestamp=${key##*/backup-}
  backup_timestamp=${backup_timestamp%.dump}
  if [[ "$backup_timestamp" < "$cutoff" ]]; then
    remaining_expired=$((remaining_expired + 1))
  fi
done <<< "$remaining_keys"

if [[ "$remaining_expired" -ne 0 ]]; then
  echo "Backup retention verification failed: $remaining_expired expired objects remain" >&2
  exit 1
fi

echo "Retention verified: matching=$matching expired=$expired deleted=$deleted remaining_expired=0"
echo "PostgreSQL backup uploaded and verified"
