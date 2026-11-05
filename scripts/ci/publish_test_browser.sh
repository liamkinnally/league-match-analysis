#!/usr/bin/env bash
set -euo pipefail

die() { echo "Chrome update refused: $*" >&2; exit 1; }
: "${GH_TOKEN:?missing GH_TOKEN}" "${GH_REPO:?missing GH_REPO}" "${VERSION:?missing VERSION}" "${GITHUB_SHA:?missing GITHUB_SHA}"
[[ ${BASE_BRANCH:-} == main && ${GITHUB_REF:-} == refs/heads/main ]] || die "run on main"
case ${GITHUB_EVENT_NAME:-} in schedule|workflow_dispatch) ;; *) die "unexpected event";; esac
[[ $VERSION =~ ^[0-9]+(\.[0-9]+){3}$ && $GITHUB_SHA =~ ^[a-f0-9]{40}$ ]] || die "invalid version or commit"
[[ $GH_REPO =~ ^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$ ]] || die "invalid repository"
[[ $(git rev-parse HEAD) == "$GITHUB_SHA" ]] || die "checkout differs from scheduled commit"
manifest=frontend/test-browser-pins.json
branch=automation/chrome-$VERSION
remote="https://github.com/$GH_REPO.git"
api="repos/$GH_REPO/git"
bot='github-actions[bot]'
email='41898282+github-actions[bot]@users.noreply.github.com'
title="Update Chrome for Testing to $VERSION"
ref_head() { git ls-remote "$remote" "refs/heads/$1" | cut -f1; }

read_pr() {
  local prs
  prs=$(gh pr list --repo "$GH_REPO" --state all --head "$branch" --limit 100 \
    --json number,state,url,author,body,headRefName,headRefOid,headRepository,headRepositoryOwner,baseRefName) || return 1
  jq -ce --arg repo "$GH_REPO" --arg branch "$branch" --arg base "$BASE_BRANCH" '
    if length == 0 then {}
    elif length == 1 and (.[0] |
      .baseRefName == $base and .headRefName == $branch and
      ((.headRepositoryOwner.login + "/" + .headRepository.name) | ascii_downcase) == ($repo | ascii_downcase) and
      (.author.login == "app/github-actions" or .author.login == "github-actions[bot]"))
    then .[0] else error("unexpected PR for Chrome branch") end' <<< "$prs"
}

validate_manifest() {
  jq -e --arg version "$VERSION" '
    def hashes($platforms):
      type == "object" and keys == ($platforms | sort) and
      all(.[]; type == "string" and length == 64 and test("^[a-f0-9]{64}$"));
    keys == ["browserVersion","chromeHashes","visualShellHashes"] and .browserVersion == $version and
    (.chromeHashes | hashes(["linux64","linux-arm64","mac-arm64","mac-x64","win32","win64"])) and
    (.visualShellHashes | hashes(["linux64","linux-arm64"]))' > /dev/null
}

pr=$(read_pr)
case $(jq -r '.state // ""' <<< "$pr") in
  CLOSED|MERGED) echo "Respecting existing $(jq -r .state <<< "$pr") PR $(jq -r .url <<< "$pr")"; exit 0 ;;
  OPEN|"") ;;
  *) die "unexpected PR state" ;;
esac
base=$(ref_head "$BASE_BRANCH")
[[ $base == "$GITHUB_SHA" ]] || die "main advanced; rerun from the current revision"
git show "$base:$manifest" | jq -e --arg version "$VERSION" '
  (.browserVersion | split(".") | map(tonumber)) < ($version | split(".") | map(tonumber))' > /dev/null ||
  die "target must be newer than the version on main"

head=$(ref_head "$branch")
if [[ -n $head ]]; then
  # Read the existing tree without ever checking out or executing branch files.
  git fetch --quiet --no-tags "$remote" "refs/heads/$branch"
  [[ $(git rev-parse FETCH_HEAD) == "$head" ]] || die "branch changed during fetch"
  parents=$(git show -s --format=%P "$head")
  [[ $parents =~ ^[a-f0-9]{40}$ ]] && git merge-base --is-ancestor "$parents" "$base" ||
    die "branch must be one commit based on main"
  [[ $(git show -s --format='%an|%ae|%cn|%ce' "$head") == "$bot|$email|$bot|$email" ]] ||
    die "unexpected commit identity"
  [[ $(git diff-tree --no-commit-id --name-status -r "$head") == $'M\t'"$manifest" ]] ||
    die "unexpected paths on version branch"
  [[ $(git ls-tree "$head" -- "$manifest") == '100644 blob '* ]] || die "invalid manifest file mode"
  git show "$head:$manifest" | validate_manifest || die "invalid manifest on existing branch"
  if [[ $pr == '{}' ]]; then
    # No PR binds this commit to our publisher. Authenticate the pins using
    # the manifest independently regenerated from Google's archives this run.
    [[ -f $manifest && ! -L $manifest ]] && validate_manifest < "$manifest" || die "trusted regenerated manifest required for orphan branch"
    git show "$head:$manifest" | jq -e --slurpfile trusted "$manifest" '. == $trusted[0]' >/dev/null ||
      die "orphan branch disagrees with freshly generated pins"
  fi
else
  [[ $pr == '{}' ]] || die "open PR lost its branch"
  [[ -f $manifest && ! -L $manifest ]] && validate_manifest < "$manifest" || die "invalid generated manifest"
  tree=$(jq -n --arg base "$(git rev-parse "$base^{tree}")" --arg path "$manifest" --rawfile content "$manifest" \
    '{base_tree:$base,tree:[{path:$path,mode:"100644",type:"blob",content:$content}]}' |
    gh api -X POST "$api/trees" --input - --jq .sha)
  head=$(jq -n --arg message "$title" --arg tree "$tree" --arg base "$base" --arg bot "$bot" --arg email "$email" \
    '{message:$message,tree:$tree,parents:[$base],author:{name:$bot,email:$email},committer:{name:$bot,email:$email}}' |
    gh api -X POST "$api/commits" --input - --jq .sha)
  [[ $head =~ ^[a-f0-9]{40}$ ]] || die "invalid commit response"
  # POST creates a ref only when absent; a concurrent publisher cannot be overwritten.
  jq -n --arg ref "refs/heads/$branch" --arg sha "$head" '{ref:$ref,sha:$sha}' |
    gh api -X POST "$api/refs" --input - --silent
fi

[[ $(ref_head "$branch") == "$head" ]] || die "version branch moved"
if [[ $pr == '{}' ]]; then
  body=$(printf 'Update the test browser manifest to Chrome for Testing %s and pin the SHA-256 digests of the official archives.\n\nPin commit: %s' "$VERSION" "$head")
  gh pr create --repo "$GH_REPO" --base "$BASE_BRANCH" --head "$branch" --title "$title" \
    --body "$body"
  pr=$(read_pr)
fi
[[ $(jq -r .state <<< "$pr") == OPEN && $(jq -r .headRefOid <<< "$pr") == "$head" ]] || die "PR head changed"
jq -e --arg marker "Pin commit: $head" '(.body | split("\n") | index($marker)) != null' <<< "$pr" >/dev/null ||
  die "PR does not identify the original pin commit"
echo "Chrome update $branch at $head: $(jq -r .url <<< "$pr")"
runs=$(gh run list --repo "$GH_REPO" --workflow ci.yml --event workflow_dispatch --branch "$branch" --commit "$head" --limit 1 --json databaseId)
if jq -e 'length > 0' <<< "$runs" >/dev/null; then
  echo "CI already dispatched for $head."
else
  # Repository writers can edit a bot-authored PR description. Only the
  # original description can attest which commit this publisher put up for CI.
  original_head=$(gh api graphql -f query='query($owner:String!,$repo:String!,$number:Int!){
    repository(owner:$owner,name:$repo){pullRequest(number:$number){lastEditedAt headRefOid}}}' \
    -f owner="${GH_REPO%/*}" -f repo="${GH_REPO#*/}" -F number="$(jq -r .number <<< "$pr")" \
    --jq '.data.repository.pullRequest | select(.lastEditedAt == null) | .headRefOid')
  [[ $original_head == "$head" ]] || die "original PR was edited or its head changed"
  [[ $(ref_head "$branch") == "$head" ]] || die "branch moved before CI dispatch"
  gh workflow run ci.yml --repo "$GH_REPO" --ref "$branch"
  echo "Dispatched CI for $head."
fi
