import json
import os
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import shutil
import subprocess
import tempfile
import textwrap
import threading
import unittest
from urllib.parse import parse_qs, urlparse
from xml.sax.saxutils import escape


SCRIPT = Path(__file__).with_name("backup.sh")
OLD_KEY = "league-analysis/postgres/backup-20260901T010203Z.dump"
RECENT_KEY = "league-analysis/postgres/backup-20260909T010203Z.dump"


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "commands.log"
        self._executable("psql", r'''#!/bin/sh
printf 'psql %s\n' "$*" >> "$TEST_COMMAND_LOG"
printf 'PGOPTIONS %s\n' "$PGOPTIONS" >> "$TEST_COMMAND_LOG"
[ "${TEST_DATABASE_FAIL:-0}" = 1 ] && exit 25
printf '1\n'
''')
        self._executable("pg_dump", r'''#!/bin/sh
printf 'pg_dump %s\n' "$*" >> "$TEST_COMMAND_LOG"
printf 'umask %s\n' "$(umask)" >> "$TEST_COMMAND_LOG"
[ "${TEST_DUMP_FAIL:-0}" = 1 ] && exit 23
while [ "$#" -gt 0 ]; do
  if [ "$1" = "--file" ]; then shift; printf 'private custom dump bytes' > "$1"; exit 0; fi
  shift
done
exit 24
''')
        self._executable("aws", r'''#!/bin/sh
printf 'aws %s\n' "$*" >> "$TEST_COMMAND_LOG"
export TEST_AWS_ARGS="$*"
case " $* " in
  *" s3 cp "*)
    case "$5" in
      s3://*) printf '%s' "${TEST_DOWNLOAD_CONTENT:-private custom dump bytes}" > "$6"; exit 0 ;;
      *) [ "${TEST_UPLOAD_FAIL:-0}" = 1 ] && exit 31; exit 0 ;;
    esac ;;
  *" s3api head-object "*) printf '%s\n' "${TEST_REMOTE_SHA:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"; exit 0 ;;
  *" s3api list-objects-v2 "*)
    [ "${TEST_LIST_FAIL:-0}" = 1 ] && exit 33
    python3 -c 'import json,os,pathlib; keys=json.loads(os.environ.get("TEST_LIST_KEYS", "[]")); deleted=pathlib.Path(os.environ["TEST_COMMAND_LOG"]+".deleted"); removed=deleted.read_text().splitlines() if deleted.exists() and os.environ.get("TEST_DELETE_STILL_LISTED") != "1" else []; remaining=[key for key in keys if key not in removed]; print(json.dumps(remaining) if "--output json" in os.environ["TEST_AWS_ARGS"] else "\t".join(remaining))'
    exit $? ;;
  *" s3api delete-object "*)
    [ "${TEST_DELETE_FAIL:-0}" = 1 ] && exit 34
    printf '%s\n' "$8" >> "$TEST_COMMAND_LOG.deleted"
    exit 0 ;;
esac
exit 32
''')
        self._executable("sha256sum", r'''#!/bin/sh
if [ "$(sed -n '1p' "$1")" = "private custom dump bytes" ]; then
  printf 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  %s\n' "$1"
else
  printf 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  %s\n' "$1"
fi
''')
        self._executable("date", r'''#!/bin/sh
case "$*" in
  *"7 days ago"*) printf '20260903T120000Z\n' ;;
  *) printf '20260910T120000Z\n' ;;
esac
''')

    def tearDown(self):
        self.temporary.cleanup()

    def _executable(self, name, body):
        path = self.bin / name
        path.write_text(textwrap.dedent(body))
        path.chmod(0o755)

    def run_backup(self, *arguments, **overrides):
        environment = {
            "PATH": f"{self.bin}:/usr/bin:/bin",
            "TMPDIR": str(self.root),
            "TEST_COMMAND_LOG": str(self.log),
            "PGHOST": "private-postgres.internal",
            "PGPORT": "5432",
            "PGDATABASE": "league",
            "PGUSER": "backup-user",
            "PGPASSWORD": "do-not-log-db-secret",
            "AWS_ACCESS_KEY_ID": "do-not-log-access-key",
            "AWS_SECRET_ACCESS_KEY": "do-not-log-aws-secret",
            "AWS_DEFAULT_REGION": "auto",
            "BACKUP_S3_ENDPOINT": "https://storage.example.test",
            "BACKUP_S3_BUCKET": "private-backups",
        }
        environment.update(overrides)
        if "TEST_LIST_KEYS" in environment:
            environment["TEST_LIST_KEYS"] = json.dumps(environment["TEST_LIST_KEYS"])
        return subprocess.run(
            ["bash", str(SCRIPT), *arguments], env=environment, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )

    def commands(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def test_dry_run_reads_database_and_reports_retention_without_mutations(self):
        cutoff_key = "league-analysis/postgres/backup-20260903T120000Z.dump"
        result = self.run_backup(
            "--dry-run", TEST_DUMP_FAIL="1", TEST_UPLOAD_FAIL="1", TEST_DELETE_FAIL="1",
            TEST_LIST_KEYS=[OLD_KEY, RECENT_KEY, cutoff_key,
                            "notes " + OLD_KEY, OLD_KEY + "\nextra",
                            "league-analysis/postgres/readme.txt", "other/" + OLD_KEY],
        )
        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        self.assertEqual(3, len(commands), commands)
        self.assertTrue(commands[0].startswith("psql "))
        self.assertIn("--no-psqlrc", commands[0])
        self.assertIn("--no-password", commands[0])
        self.assertIn("ON_ERROR_STOP=1", commands[0])
        self.assertIn("BEGIN READ ONLY; SELECT 1; ROLLBACK;", commands[0])
        self.assertIn("default_transaction_read_only=on", commands[1])
        self.assertIn(" s3api list-objects-v2 ", commands[2])
        self.assertIn("--output json", commands[2])
        self.assertNotIn("--no-paginate", commands[2])
        self.assertNotIn("--max-items", commands[2])
        self.assertIn(f"DRY RUN would-delete: {OLD_KEY}", result.stdout)
        self.assertIn(f"DRY RUN retain: {RECENT_KEY}", result.stdout)
        self.assertIn(f"DRY RUN retain: {cutoff_key}", result.stdout)
        self.assertIn("DRY RUN retention: matching=3 expired=1 would_delete=1", result.stdout)
        self.assertNotIn("notes", result.stdout)
        self.assertNotIn("extra", result.stdout)
        self.assertNotIn("other/", result.stdout)
        self.assertNotIn("PostgreSQL backup uploaded and verified", result.stdout)
        self.assertNotIn("do-not-log", result.stdout + result.stderr + "\n".join(commands))
        self.assertEqual([], list(self.root.glob("league-analysis-backup.*")))
        self.assertFalse(Path(str(self.log) + ".deleted").exists())

    def test_dry_run_empty_bucket_reports_zero(self):
        result = self.run_backup("--dry-run", TEST_LIST_KEYS=[])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("DRY RUN retention: matching=0 expired=0 would_delete=0", result.stdout)
        self.assertEqual(1, sum(" list-objects-v2 " in line for line in self.commands()))
        self.assertFalse(any("pg_dump " in line or " s3 cp " in line or " delete-object " in line
                             for line in self.commands()))

    def test_dry_run_database_failure_never_contacts_storage(self):
        result = self.run_backup("--dry-run", TEST_DATABASE_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(line.startswith("aws ") or line.startswith("pg_dump ")
                             for line in self.commands()))
        self.assertNotIn("DRY RUN retention:", result.stdout)

    def test_dry_run_listing_failure_never_claims_success_or_mutates(self):
        result = self.run_backup("--dry-run", TEST_LIST_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any("pg_dump " in line or " s3 cp " in line or " delete-object " in line
                             for line in self.commands()))
        self.assertNotIn("DRY RUN retention:", result.stdout)

    def test_rejects_invalid_arguments_before_connecting(self):
        for arguments in [("--execute",), ("--dry-run", "--execute"),
                          ("--dry-run", "--dry-run"), ("",)]:
            with self.subTest(arguments=arguments):
                result = self.run_backup(*arguments)
                self.assertEqual(2, result.returncode)
                self.assertIn("Usage:", result.stderr)
                self.assertEqual([], self.commands())

    def test_uploads_verified_custom_dump_then_prunes_only_old_matching_keys(self):
        result = self.run_backup(TEST_LIST_KEYS=[OLD_KEY, RECENT_KEY, "league-analysis/postgres/readme.txt", "other/backup-20200101T000000Z.dump"])
        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        self.assertIn("--format=custom", commands[0])
        self.assertIn("umask 0077", commands)
        upload = next(line for line in commands if " s3 cp " in line)
        self.assertIn("s3://private-backups/league-analysis/postgres/backup-20260910T120000Z.dump", upload)
        head_index = next(index for index, line in enumerate(commands) if " head-object " in line)
        delete_lines = [line for line in commands if " delete-object " in line]
        self.assertEqual(1, len(delete_lines))
        self.assertIn(OLD_KEY, delete_lines[0])
        self.assertGreater(commands.index(delete_lines[0]), head_index)
        self.assertNotIn(RECENT_KEY, "\n".join(delete_lines))
        self.assertNotIn("other/", "\n".join(delete_lines))
        self.assertNotIn("do-not-log", result.stdout + result.stderr)
        self.assertEqual([], list(self.root.glob("league-analysis-backup.*")))

    def test_reports_retention_counts_and_rechecks_after_deletion(self):
        result = self.run_backup(TEST_LIST_KEYS=[OLD_KEY, RECENT_KEY, "league-analysis/postgres/readme.txt"])
        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        listing = [i for i, command in enumerate(commands) if " list-objects-v2 " in command]
        deletion = next(i for i, command in enumerate(commands) if " delete-object " in command)
        download = max(i for i, command in enumerate(commands) if " s3 cp " in command)
        self.assertEqual(2, len(listing))
        self.assertLess(download, listing[0])
        self.assertLess(listing[0], deletion)
        self.assertLess(deletion, listing[1])
        self.assertIn("Retention verified: matching=2 expired=1 deleted=1 remaining_expired=0", result.stdout)
        for i in listing:
            self.assertIn("--output json", commands[i])
            self.assertNotIn("--no-paginate", commands[i])
            self.assertNotIn("--max-items", commands[i])

    def test_deletion_error_fails_without_claiming_retention_success(self):
        result = self.run_backup(TEST_LIST_KEYS=[OLD_KEY], TEST_DELETE_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Retention verified", result.stdout)
        self.assertNotIn("PostgreSQL backup uploaded and verified", result.stdout)
        self.assertEqual([], list(self.root.glob("league-analysis-backup.*")))

    def test_successful_delete_that_leaves_expired_object_fails_verification(self):
        result = self.run_backup(TEST_LIST_KEYS=[OLD_KEY], TEST_DELETE_STILL_LISTED="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Backup retention verification failed", result.stderr)
        self.assertNotIn("Retention verified", result.stdout)

    def test_retention_list_failure_fails_without_deleting(self):
        result = self.run_backup(TEST_LIST_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(" delete-object " in command for command in self.commands()))
        self.assertNotIn("Retention verified", result.stdout)

    def test_whitespace_keys_are_never_mistaken_for_backup_objects(self):
        result = self.run_backup(TEST_LIST_KEYS=["notes " + OLD_KEY, OLD_KEY + "\nextra", OLD_KEY + "\tother", RECENT_KEY])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(any(" delete-object " in command for command in self.commands()))
        self.assertIn("Retention verified: matching=1 expired=0 deleted=0 remaining_expired=0", result.stdout)

    def test_empty_bucket_still_verifies_retention(self):
        result = self.run_backup(TEST_LIST_KEYS=[])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Retention verified: matching=0 expired=0 deleted=0 remaining_expired=0", result.stdout)

    def test_dump_failure_never_contacts_storage_or_prunes(self):
        result = self.run_backup(TEST_DUMP_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(line.startswith("aws ") for line in self.commands()))
        self.assertEqual([], list(self.root.glob("league-analysis-backup.*")))

    def test_upload_failure_never_verifies_or_prunes(self):
        result = self.run_backup(TEST_UPLOAD_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        commands = self.commands()
        self.assertTrue(any(" s3 cp " in line for line in commands))
        self.assertFalse(any(" head-object " in line or "delete-object " in line for line in commands))
        self.assertEqual([], list(self.root.glob("league-analysis-backup.*")))

    def test_checksum_mismatch_never_lists_or_prunes(self):
        result = self.run_backup(TEST_REMOTE_SHA="wrong")
        self.assertNotEqual(0, result.returncode)
        commands = self.commands()
        self.assertTrue(any(" head-object " in line for line in commands))
        self.assertFalse(any(" list-objects-v2 " in line or " delete-object " in line for line in commands))
        self.assertEqual([], list(self.root.glob("league-analysis-backup.*")))

    def test_downloaded_object_mismatch_never_lists_or_prunes(self):
        result = self.run_backup(TEST_DOWNLOAD_CONTENT="corrupted")
        self.assertNotEqual(0, result.returncode)
        commands = self.commands()
        self.assertEqual(2, sum(" s3 cp " in line for line in commands))
        self.assertFalse(any(" list-objects-v2 " in line or " delete-object " in line for line in commands))
        self.assertEqual([], list(self.root.glob("league-analysis-backup.*")))

    def test_rejects_non_https_endpoint_before_dumping(self):
        result = self.run_backup(BACKUP_S3_ENDPOINT="http://storage.example.test")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.commands())

    def test_requires_credentials_without_printing_their_values(self):
        result = self.run_backup(PGPASSWORD="")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.commands())
        self.assertNotIn("do-not-log", result.stdout + result.stderr)


class AwsPaginationTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("aws"), "Run in the backup image to verify the installed AWS CLI paginator")
    def test_real_cli_reads_all_pages_without_splitting_whitespace_keys(self):
        requests = []
        first_key = "league-analysis/postgres/read me\tkeep\nthis.txt"

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                query = parse_qs(urlparse(self.path).query)
                token = query.get("continuation-token", [None])[0]
                requests.append(token)
                key = first_key if token is None else OLD_KEY
                continuation = "<NextContinuationToken>second-page</NextContinuationToken>" if token is None else ""
                response = (f'<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
                            f'<Name>private-backups</Name><IsTruncated>{str(token is None).lower()}</IsTruncated>'
                            f'{continuation}<Contents><Key>{escape(key)}</Key><Size>1</Size>'
                            '<LastModified>2026-09-01T01:02:03.000Z</LastModified></Contents></ListBucketResult>').encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/xml")
                self.send_header("Content-Length", str(len(response)))
                self.end_headers()
                self.wfile.write(response)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = subprocess.run([
                shutil.which("aws"), "--endpoint-url", f"http://127.0.0.1:{server.server_port}",
                "s3api", "list-objects-v2", "--bucket", "private-backups",
                "--prefix", "league-analysis/postgres/", "--query", "Contents[].Key", "--output", "json",
            ], env={
                "PATH": os.environ["PATH"], "AWS_ACCESS_KEY_ID": "local-test-only",
                "AWS_SECRET_ACCESS_KEY": "local-test-only", "AWS_DEFAULT_REGION": "us-east-1",
                "AWS_EC2_METADATA_DISABLED": "true", "AWS_CONFIG_FILE": os.devnull,
                "AWS_SHARED_CREDENTIALS_FILE": os.devnull, "AWS_PAGER": "",
            }, text=True, capture_output=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([None, "second-page"], requests)
            self.assertEqual([first_key, OLD_KEY], json.loads(result.stdout))
        finally:
            server.shutdown()
            thread.join(timeout=5)
            server.server_close()


if __name__ == "__main__":
    unittest.main()
