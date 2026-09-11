import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


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
case " $* " in
  *" s3 cp "*)
    case "$5" in
      s3://*) printf '%s' "${TEST_DOWNLOAD_CONTENT:-private custom dump bytes}" > "$6"; exit 0 ;;
      *) [ "${TEST_UPLOAD_FAIL:-0}" = 1 ] && exit 31; exit 0 ;;
    esac ;;
  *" s3api head-object "*) printf '%s\n' "${TEST_REMOTE_SHA:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"; exit 0 ;;
  *" s3api list-objects-v2 "*) printf '%s\n' "${TEST_LIST_KEYS:-None}"; exit 0 ;;
  *" s3api delete-object "*) exit 0 ;;
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

    def run_backup(self, **overrides):
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
        return subprocess.run(
            ["bash", str(SCRIPT)], env=environment, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )

    def commands(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def test_uploads_verified_custom_dump_then_prunes_only_old_matching_keys(self):
        result = self.run_backup(TEST_LIST_KEYS=f"{OLD_KEY}\t{RECENT_KEY}\tleague-analysis/postgres/readme.txt\tother/backup-20200101T000000Z.dump")
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


if __name__ == "__main__":
    unittest.main()
