#!/usr/bin/env python3
"""Exercise publication rules in an isolated, empty repository; never force-add."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
NEGATIVE = [
    'data/player-removal/ledger.json', 'ops/player-removal/ledger.json',
    'docs/private/removal-receipt.json', '.planning/player-removal/subject.txt',
    '.planning/CURRENT.md', '.planning/reports/review.md',
    '.worktrees/nested/frontend/src/app/page.tsx',
    'backend/src/main/resources/.env.production',
    'frontend/src/.planning/notes.md', 'frontend/src/private/notes.ts',
    'backend/src/main/resources/raw-captures/match.json',
    'backend/src/test/resources/captures/live-response.json',
    'frontend/e2e/agent-reports/review.md', 'frontend/src/research/unrelated.ts',
    'frontend/src/secrets/token.json', 'backend/src/main/resources/credentials.json',
    'frontend/src/config/private.key', 'frontend/src/config/certificate.p12',
    'frontend/src/node_modules/dependency/index.js', 'backend/src/cache/derived.json',
    'backend/target/app.jar', 'frontend/.next/server/app/page.js',
    'frontend/test-results/report.json', 'frontend/src/.worktrees/nested/app.ts',
    'docs/private/handoff.md', 'docs/agent-report.md', 'docs/raw-captures/example.png',
    '.github/workflows/.env', 'unexpected-file.txt', '.env', 'frontend/.env.local',
    'backend/src/main/java/dev/leagueanalysis/.git/config',
    'frontend/src/.git/objects/example', 'frontend/src/.cache/item.ts',
    'backend/src/test/resources/fixtures/riot/secret.json',
    'frontend/e2e-visual/snapshots/private.png', 'docs/unreviewed.md',
    'scripts/update-riot-key', 'scripts/tests/update_riot_key_test.py',
]
# These required boundaries must survive even if a current index omits them.
REQUIRED = [
    '.gitignore', '.gitattributes', '.dockerignore', '.node-version', '.env.example',
    '.github/workflows/ci.yml', 'compose.yaml', 'compose.app.yaml', 'README.md',
    'backend/mvnw', 'backend/mvnw.cmd', 'backend/.mvn/wrapper/maven-wrapper.properties',
    'backend/pom.xml', 'backend/Dockerfile', 'frontend/Dockerfile',
    'frontend/package.json', 'frontend/package-lock.json', 'frontend/.env.example',
    'frontend/src/app/matches/[matchId]/development/page.tsx',
    'frontend/src/app/api/player-matches/[runId]/route.ts',
    'frontend/e2e-visual/snapshots/sample-development-wide.png',
    'docs/deployment.md', 'docs/developer-guide.md', 'docs/architecture.md',
    'docs/public-match-lookup.md', 'docs/images/sample-development.png',
    'scripts/setup', 'scripts/dev', 'scripts/seed-demo', 'scripts/verify',
    'scripts/package-smoke', 'scripts/tests/export_policy_test.py',
    'backend/src/test/java/dev/leagueanalysis/ingestion/riot/adapter/out/persistence/JdbcIngestionLeaseGuardTest.java',
    'scripts/player-removal', 'docs/player-data-removal.md',
    'backend/src/main/resources/db/migration/V6__private_player_removal.sql',
    'backend/src/main/java/dev/leagueanalysis/privacy/PlayerRemovalCommand.java',
    'backend/src/main/java/dev/leagueanalysis/privacy/PrivacyRuntimeGuard.java',
    'backend/src/main/java/dev/leagueanalysis/privacy/PrivacyHash.java',
    'backend/src/main/java/dev/leagueanalysis/privacy/RemovalPlanner.java',
    'backend/src/main/java/dev/leagueanalysis/privacy/PrivacyAvailabilityFilter.java',
    'backend/src/main/java/dev/leagueanalysis/privacy/RemovalLedger.java',
    'backend/src/main/java/dev/leagueanalysis/privacy/RemovalPlan.java',
    'backend/src/test/java/dev/leagueanalysis/privacy/RemovalLedgerTest.java',
    'backend/src/test/java/dev/leagueanalysis/privacy/PlayerRemovalCommandTest.java',
    'backend/src/test/java/dev/leagueanalysis/privacy/RemovalPlannerIntegrationTest.java',
    'backend/src/test/java/dev/leagueanalysis/privacy/PrivacyExclusionIntegrationTest.java',
    'backend/src/test/java/dev/leagueanalysis/ingestion/riot/application/PrivacyAwareIngestionIntegrationTest.java',

]


def git(directory, *args, **kwargs):
    return subprocess.run(['git', '-C', str(directory), *args], check=True,
                          capture_output=True, **kwargs).stdout


class ExportPolicyTest(unittest.TestCase):
    def test_empty_index_stages_only_public_files(self):
        tracked = git(ROOT, 'ls-files', '-z').decode().split('\0')
        public = set(filter(None, tracked)) | set(REQUIRED)
        self.assertFalse(public & set(NEGATIVE))
        for filename in public:
            self.assertTrue((ROOT / filename).is_file(), f'required public file missing: {filename}')
        with tempfile.TemporaryDirectory(prefix='league-export-policy-') as name:
            probe = Path(name)
            git(probe, 'init', '--quiet', '--initial-branch=main')
            shutil.copyfile(ROOT / '.gitignore', probe / '.gitignore')
            for filename in sorted(public | set(NEGATIVE)):
                path = probe / filename
                if filename != '.gitignore':
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('synthetic policy probe\n')
                    original = ROOT / filename
                    if original.is_file() and os.access(original, os.X_OK):
                        path.chmod(0o755)
            git(probe, 'add', '.')
            staged = set(filter(None, git(probe, 'diff', '--cached', '--name-only', '-z').decode().split('\0')))
            self.assertEqual(public, staged,
                             f'missing={sorted(public - staged)}; unexpected={sorted(staged - public)}')
            # check-ignore --no-index tests rules even for source files already tracked above.
            ignored = subprocess.run(
                ['git', '-C', str(probe), 'check-ignore', '--no-index', '--stdin'],
                input='\n'.join(NEGATIVE + ['.git/config']) + '\n',
                text=True, capture_output=True, check=True).stdout.splitlines()
            self.assertEqual(set(NEGATIVE + ['.git/config']), set(ignored))
            modes = git(probe, 'ls-files', '--stage').decode().splitlines()
            self.assertTrue(any(line.startswith('100755 ') and line.endswith('\tbackend/mvnw') for line in modes))
            for filename in public:
                self.assertFalse((ROOT / filename).is_symlink(), filename)
            print(f'empty-index policy: {len(public)} allowed files; {len(NEGATIVE) + 1} denied probes; wrapper executable')


if __name__ == '__main__':
    unittest.main()
