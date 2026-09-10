#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
repo_dir=$(cd "$script_dir/../.." && pwd -P)
cd "$repo_dir"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local actual=$1
  local expected=$2
  [[ "$actual" == *"$expected"* ]] || fail "expected output to contain: $expected"
}

assert_file_contains() {
  local file=$1
  local expected=$2
  [[ -f "$file" ]] || fail "expected file to exist: $file"
  grep -Fq -- "$expected" "$file" || fail "expected $file to contain: $expected"
}

assert_ordered() {
  local actual=$1
  shift
  local remaining=$actual
  local expected
  for expected in "$@"; do
    [[ "$remaining" == *"$expected"* ]] || fail "missing ordered command: $expected"
    remaining=${remaining#*"$expected"}
  done
}

backend_output=$(VERIFY_DRY_RUN=1 ./scripts/verify focused backend '-Dtest=FooTest#works' test)
assert_contains "$backend_output" "./mvnw -B -ntp -Dtest=FooTest#works test"

frontend_output=$(VERIFY_DRY_RUN=1 ./scripts/verify focused frontend 'src/components/example.test.tsx' '-t' 'renders evidence')
assert_contains "$frontend_output" "npm test -- src/components/example.test.tsx -t renders\\ evidence"

browser_output=$(VERIFY_DRY_RUN=1 ./scripts/verify focused browser '--grep' 'restores context')
assert_contains "$browser_output" "npm run test:e2e -- --grep restores\\ context"

frontend_task_output=$(VERIFY_DRY_RUN=1 ./scripts/verify task frontend)
assert_ordered \
  "$frontend_task_output" \
  "npm run lint" \
  "npm run typecheck" \
  "npm test" \
  "npm run build"

cross_layer_output=$(VERIFY_DRY_RUN=1 ./scripts/verify task cross-layer)
assert_ordered \
  "$cross_layer_output" \
  "docker compose --env-file" \
  "./mvnw -B -ntp verify" \
  "npm run lint" \
  "npm run test:e2e --"

visual_output=$(VERIFY_DRY_RUN=1 ./scripts/verify ui visual '--grep' 'Investigation wide')
assert_contains "$visual_output" "--platform linux/amd64"
assert_contains "$visual_output" "league-analysis-visual:playwright-1.63.0"
assert_contains "$visual_output" "npm run test:visual:raw -- --grep Investigation\\ wide"

if VERIFY_DRY_RUN=1 ./scripts/verify focused frontend >/dev/null 2>&1; then
  fail "focused frontend unexpectedly accepted no native selector"
fi

if VERIFY_DRY_RUN=1 ./scripts/verify focused backend >/dev/null 2>&1; then
  fail "focused backend unexpectedly accepted no native selector"
fi

dev_output=$(DEV_DRY_RUN=1 ./scripts/dev ui)
assert_contains "$dev_output" "UI_LAB_ENABLED=1"
assert_contains "$dev_output" "LEAGUE_ANALYSIS_RUNTIME=local"
assert_contains "$dev_output" "http://127.0.0.1:3000/matches/__lab_normal?focus=6"
assert_contains "$dev_output" "http://127.0.0.1:3000/matches/__lab_investigation?"
assert_contains "$dev_output" "http://127.0.0.1:3000/matches/__lab_sparse?focus=6"
assert_contains "$dev_output" "http://127.0.0.1:3000/matches/__lab_unsupported?"

help_output=$(./scripts/verify --help)
assert_contains "$help_output" "task backend"
assert_contains "$help_output" "task frontend"
assert_contains "$help_output" "task cross-layer"
assert_contains "$help_output" "full"

assert_file_contains backend/Dockerfile "eclipse-temurin:21"
assert_file_contains backend/Dockerfile "USER application"
assert_file_contains frontend/Dockerfile "node:24"
assert_file_contains frontend/Dockerfile "USER nextjs"
assert_file_contains frontend/next.config.ts 'output: "standalone"'
assert_file_contains compose.app.yaml "SPRING_DATASOURCE_URL"
assert_file_contains compose.app.yaml 'RIOT_API_KEY: ${RIOT_API_KEY:-}'
assert_file_contains compose.app.yaml '127.0.0.1:${FRONTEND_PORT:-3416}:3000'
assert_file_contains backend/src/main/resources/application.properties 'league-analysis.riot.api-key=${RIOT_API_KEY:}'
assert_file_contains scripts/package-smoke "match-development-v1-package-smoke"
assert_file_contains scripts/package-smoke "--seed-demo"

echo "command contract tests passed"
