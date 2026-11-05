#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export GIT_CONFIG_GLOBAL="$tmp/gitconfig" GH_TOKEN=test GH_REPO=owner/repo BASE_BRANCH=main VERSION=2.1.0.0
export GITHUB_REF=refs/heads/main GITHUB_EVENT_NAME=schedule
export GIT_CONFIG_NOSYSTEM=1
mkdir -p "$tmp/bin"
export TEST_STATE="$tmp/state" TEST_REMOTE="$tmp/remote.git"
mkdir "$TEST_STATE"
export PATH="$tmp/bin:$PATH"

git init -q --bare "$tmp/remote.git"
git config --global url."file://$tmp/remote.git".insteadOf "https://github.com/$GH_REPO.git"
git config --global user.name "Test User"
git config --global user.email test@example.invalid
git init -q -b main "$tmp/work"
cd "$tmp/work"
mkdir frontend
manifest=frontend/test-browser-pins.json
sha=$(printf '%064d' 1)
jq -n --arg h "$sha" '{browserVersion:"1.0.0.0",chromeHashes:{linux64:$h,"linux-arm64":$h,"mac-arm64":$h,"mac-x64":$h,win32:$h,win64:$h},visualShellHashes:{linux64:$h,"linux-arm64":$h}}' > "$manifest"
git add "$manifest"
git commit -qm base
export GITHUB_SHA=$(git rev-parse HEAD)
git push -q "https://github.com/$GH_REPO.git" main
branch="automation/chrome-${VERSION}"
new_manifest() { jq --arg v "$VERSION" '.browserVersion=$v' "$manifest" > "$tmp/desired"; cp "$tmp/desired" "$manifest"; }
cat > "$tmp/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$1 $2" in
  "api graphql")
    jq -r '.[0] | if .lastEditedAt == null then .headRefOid else "" end' "$TEST_STATE/pr" ;;
  "api -X")
    [[ $3 == POST ]]
    data=$(cat)
    bare=(git --git-dir="$TEST_REMOTE")
    case "$4" in
      "repos/$GH_REPO/git/trees")
        [[ $(jq -r '.tree | length' <<< "$data") -eq 1 ]]
        path=$(jq -r '.tree[0].path' <<< "$data")
        [[ $path == frontend/test-browser-pins.json ]]
        export GIT_INDEX_FILE="$TEST_STATE/index"
        "${bare[@]}" read-tree "$(jq -r .base_tree <<< "$data")"
        blob=$(jq -j '.tree[0].content' <<< "$data" | "${bare[@]}" hash-object -w --stdin)
        "${bare[@]}" update-index --cacheinfo "100644,$blob,$path"
        "${bare[@]}" write-tree ;;
      "repos/$GH_REPO/git/commits")
        export GIT_AUTHOR_NAME=$(jq -r .author.name <<< "$data") GIT_AUTHOR_EMAIL=$(jq -r .author.email <<< "$data")
        export GIT_COMMITTER_NAME=$(jq -r .committer.name <<< "$data") GIT_COMMITTER_EMAIL=$(jq -r .committer.email <<< "$data")
        "${bare[@]}" commit-tree "$(jq -r .tree <<< "$data")" -p "$(jq -r '.parents[0]' <<< "$data")" -m "$(jq -r .message <<< "$data")" ;;
      "repos/$GH_REPO/git/refs")
        "${bare[@]}" update-ref "$(jq -r .ref <<< "$data")" "$(jq -r .sha <<< "$data")" 0000000000000000000000000000000000000000 ;;
      *) exit 1;;
    esac ;;
  "pr list") if [[ -f $TEST_STATE/pr ]]; then cat "$TEST_STATE/pr"; else echo '[]'; fi ;;
  "pr create")
    if [[ -f $TEST_STATE/fail-create ]]; then exit 1; fi
    sha=$(git ls-remote "https://github.com/$GH_REPO.git" "refs/heads/automation/chrome-$VERSION" | cut -f1)
    body=''
    while (($#)); do if [[ $1 == --body ]]; then body=$2; break; fi; shift; done
    jq -n --arg body "$body" --arg sha "$sha" --arg branch "automation/chrome-$VERSION" '[{number:9,state:"OPEN",body:$body,url:"https://github.com/owner/repo/pull/9",baseRefName:"main",headRefName:$branch,headRefOid:$sha,headRepositoryOwner:{login:"owner"},headRepository:{name:"repo"},author:{login:"app/github-actions"}}]' > "$TEST_STATE/pr"
    echo "https://github.com/owner/repo/pull/9" ;;
  "run list")
    if [[ -e $TEST_STATE/run ]]; then cat "$TEST_STATE/run"; else echo '[]'; fi ;;
  "workflow run")
    if [[ -f $TEST_STATE/fail-dispatch ]]; then exit 1; fi
    sha=$(git ls-remote "https://github.com/$GH_REPO.git" "refs/heads/automation/chrome-$VERSION" | cut -f1)
    echo "$sha" >> "$TEST_STATE/dispatches"
    jq -n --arg sha "$sha" '[{headSha:$sha,databaseId:123}]' > "$TEST_STATE/run" ;;
  *) echo "unexpected gh command: $1 $2" >&2; exit 1 ;;
esac
MOCK
chmod +x "$tmp/bin/gh"
publish() { bash "$script_dir/publish_test_browser.sh"; }
ref() { git ls-remote "https://github.com/$GH_REPO.git" "refs/heads/$branch" | cut -f1; }
must_fail() { if publish > "$tmp/stdout" 2>&1; then echo "Expected refusal: $1" >&2; exit 1; fi; }

new_manifest
touch "$TEST_STATE/fail-create"
must_fail "simulated failure after push"
head=$(ref); [[ -n $head ]]
rm "$TEST_STATE/fail-create"
git checkout -q "$GITHUB_SHA" -- "$manifest"
must_fail "orphaned branch requires trusted generated manifest"
[[ ! -e $TEST_STATE/pr && ! -e $TEST_STATE/dispatches ]]
new_manifest
jq '.chromeHashes.linux64=("a"*64)' "$tmp/desired" > "$manifest"
must_fail "orphaned branch differs from regenerated hashes"
[[ ! -e $TEST_STATE/pr && ! -e $TEST_STATE/dispatches ]]
jq -S '.' "$tmp/desired" > "$manifest"
touch "$TEST_STATE/fail-dispatch"
must_fail "simulated failure after PR creation"
[[ $(ref) == "$head" && -s "$TEST_STATE/pr" ]]
rm "$TEST_STATE/fail-dispatch"
git checkout -q "$GITHUB_SHA" -- "$manifest"
cp "$TEST_STATE/pr" "$tmp/trusted-pr"
jq '.[0].author.login = "other"' "$tmp/trusted-pr" > "$TEST_STATE/pr"
must_fail "foreign PR actor"
jq '.[0].body = "another head"' "$tmp/trusted-pr" > "$TEST_STATE/pr"
must_fail "PR head differs from the recorded bot commit"
jq '.[0].lastEditedAt = "2026-11-05T00:00:00Z"' "$tmp/trusted-pr" > "$TEST_STATE/pr"
must_fail "a rewritten bot PR cannot authenticate its original commit"
cp "$tmp/trusted-pr" "$TEST_STATE/pr"
publish
publish
[[ $(ref) == "$head" && $(wc -l < "$TEST_STATE/dispatches") -eq 1 ]]
jq '.[0].state="CLOSED" | .[0].lastEditedAt="2026-11-05T00:00:00Z"' "$TEST_STATE/pr" > "$tmp/pr"; mv "$tmp/pr" "$TEST_STATE/pr"
git push -q "https://github.com/$GH_REPO.git" ":refs/heads/$branch"
publish
[[ -z $(ref) && $(wc -l < "$TEST_STATE/dispatches") -eq 1 ]]
rm "$TEST_STATE/pr"
new_manifest
jq '.visualShellHashes.linux64="not-a-hash"' "$tmp/desired" > "$manifest"
must_fail "malformed generated digest"
[[ -z $(ref) ]]
git checkout -q "$GITHUB_SHA" -- "$manifest"
echo 'unsafe' > unrelated
git add unrelated; git commit -qm unrelated
git push -q "https://github.com/$GH_REPO.git" "HEAD:refs/heads/$branch"
bad=$(ref); git checkout -q --detach "$GITHUB_SHA"
must_fail "existing version branch has unrelated changes"
[[ $(ref) == "$bad" ]]
echo "Browser publisher tests passed"
