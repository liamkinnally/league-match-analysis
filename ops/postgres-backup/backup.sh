#!/usr/bin/env bash
set -euo pipefail

umask 077

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

cutoff=$(date -u -d '7 days ago' +%Y%m%dT%H%M%SZ)
keys=$(aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3api list-objects-v2 \
  --bucket "$BACKUP_S3_BUCKET" --prefix "$backup_prefix/" \
  --query 'Contents[].Key' --output text)
for key in $keys; do
  if [[ "$key" =~ ^league-analysis/postgres/backup-([0-9]{8}T[0-9]{6}Z)\.dump$ ]] \
      && [[ "${BASH_REMATCH[1]}" < "$cutoff" ]]; then
    aws --endpoint-url "$BACKUP_S3_ENDPOINT" s3api delete-object \
      --bucket "$BACKUP_S3_BUCKET" --key "$key" >/dev/null
  fi
done

echo "PostgreSQL backup uploaded and verified"
