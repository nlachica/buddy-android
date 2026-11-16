#!/usr/bin/env bash
# The mechanical half of bringing official Buddy's latest release into this fork (the rest is AGENTS.md, "Bringing in
# official Buddy"). Never pushes or tags.
#   tools/upstream-sync.sh [<tag>]        merge upstream's release tag (default: its latest release) into a worktree
#   tools/upstream-sync.sh --release <sha>  check that merge commit <sha> may be tagged as the fork release
# Exit: 0 merged and committed (or --release: tag it), 1 error/refused, 3 conflicts left (merge in progress),
#       4 origin/main already has the tag, 5 a sync is already under way (nothing changed).
# Env: SYNC_DIR (worktree, default ../buddy-fork-sync), UPSTREAM_URL.
set -eu
BI=app/src/main/java/app/buddy/assistant/BuildInfo.java
cd "$(git rev-parse --show-toplevel)"
root=$PWD
dir=${SYNC_DIR:-$root/../buddy-fork-sync}
git remote get-url upstream >/dev/null 2>&1 ||
  git remote add upstream "${UPSTREAM_URL:-https://github.com/ghorbelhamdi/buddy-android.git}"

die() { echo "$*" >&2; exit 1; }
version() { git show "$1:$BI" | sed -n 's/.*VERSION = "\(.*\)";.*/\1/p'; }
vcode() { git show "$1:$BI" | sed -n 's/.*VERSION_CODE = \([0-9][0-9]*\);.*/\1/p'; }
fetch_tag() { git fetch -q --no-tags upstream "+refs/tags/$1:refs/remotes/upstream/tags/$1"; }
# "<commit> <refs/tags/name>" for every v* tag on origin (annotated ones peeled)
origin_tags() { git ls-remote --tags origin 'v*' | awk '{n=$2; sub(/\^\{\}$/,"",n); c[n]=$1} END{for(n in c) print c[n], n}'; }

git fetch -q origin main

if [ "${1:-}" = --release ]; then
  sha=$(git rev-parse -q --verify "${2:?usage: --release <sha>}^{commit}") || die "no such commit: $2"
  tags=$(origin_tags)
  while read -r c n; do
    [ -n "$c" ] && git cat-file -e "$c^{commit}" 2>/dev/null && git merge-base --is-ancestor "$sha" "$c" &&
      die "Already tagged as ${n#refs/tags/}, which contains it: AGENTS.md 'already merged' with ${n#refs/tags/}."
  done <<<"$tags"
  fix="Fix forward: release current main as the next -fork.<n> per AGENTS.md 'Updating Buddy on the phone' steps 2-3, checking that commit with --release first."
  ver=$(version "$sha"); code=$(vcode "$sha"); parent=$(git rev-parse "$sha^1") || die "$sha has no parent. $fix"
  up=${ver%-fork.*}
  [ "$up" != "$ver" ] || die "VERSION $ver isn't <upstream>-fork.<n>. $fix"
  git rev-parse -q --verify "refs/remotes/upstream/tags/v$up" >/dev/null || fetch_tag "v$up"
  ut=refs/remotes/upstream/tags/v$up
  git merge-base --is-ancestor "$ut" "$sha" || die "Upstream v$up isn't inside $sha. $fix"
  [ "$ver" != "$(version "$parent")" ] || die "VERSION $ver is the same as before the merge. $fix"
  [ "$code" -gt "$(vcode "$parent")" ] && [ "$code" -gt "$(vcode "$ut")" ] || die "VERSION_CODE $code isn't above the parent's and upstream's. $fix"
  [ "$code" -ge "$(vcode origin/main)" ] || die "origin/main has a higher VERSION_CODE than $sha. $fix"
  ! grep -q " refs/tags/v$ver\$" <<<"$tags" || die "Tag v$ver is taken on origin: AGENTS.md 'already merged' with v$ver."
  echo "git tag v$ver $sha && git push origin v$ver"
  exit 0
fi

tag=${1:-$(gh api repos/ghorbelhamdi/buddy-android/releases/latest --jq .tag_name)}
ver=${tag#v}; br=sync/upstream-$ver; ut=refs/remotes/upstream/tags/$tag
fetch_tag "$tag"

if git merge-base --is-ancestor "$ut^{commit}" origin/main; then
  echo "origin/main already contains upstream $tag. Fork tags on origin:"
  git ls-remote --tags origin "v$ver-fork.*" | grep -v '\^{}$' | sed 's|.*refs/tags/||' | grep . || echo none
  echo "Next: AGENTS.md 'already merged' step."
  exit 4
fi

git worktree prune
open=
for b in $(git for-each-ref --format='%(refname:short)' 'refs/heads/sync/upstream-*'); do
  git merge-base --is-ancestor "$b" origin/main || open="$open $b"
done
if [ -e "$dir" ] || [ -n "$open" ]; then
  echo "A sync is already under way. Nothing was changed."
  echo "worktree: $dir ($([ -e "$dir" ] && echo exists || echo missing))"
  for b in ${open:-$(git -C "$dir" rev-parse --abbrev-ref HEAD 2>/dev/null || true)}; do
    echo "branch $b: on origin: $(git ls-remote --exit-code --heads origin "$b" >/dev/null 2>&1 && echo yes || echo no); in origin/main: $(git merge-base --is-ancestor "$b" origin/main && echo yes || echo no)"
  done
  [ -e "$dir" ] && echo "merge in progress: $(git -C "$dir" rev-parse -q --verify MERGE_HEAD >/dev/null && echo yes || echo no); unmerged files: $(git -C "$dir" diff --name-only --diff-filter=U | tr '\n' ' ')"
  echo "Next: AGENTS.md 'resume' step."
  exit 5
fi

# a leftover branch here is an ancestor of origin/main (checked above), so nothing is lost
git rev-parse -q --verify "refs/heads/$br" >/dev/null && git branch -qD "$br"
git worktree add -q -b "$br" "$dir" origin/main
cd "$dir"
git merge -q --no-ff --no-commit -m "Merge official Buddy $ver ($tag) into the fork" "$ut^{commit}" >/dev/null ||
  git rev-parse -q --verify MERGE_HEAD >/dev/null || die "git merge failed in $dir (not a conflict)"

ours=$(vcode origin/main); theirs=$(vcode "$ut")
conflicts=$(git diff --name-only --diff-filter=U)
ok=
if [ -n "$ours" ] && [ -n "$theirs" ]; then
  code=$(( ours > theirs ? ours : theirs ))
  code=$((code + 1))
  git show "$ut:$BI" | sed "s/VERSION = \"[^\"]*\"/VERSION = \"$ver-fork.1\"/; s/VERSION_CODE = [0-9]*;/VERSION_CODE = $code;/" >"$BI.new"
  if grep -q "VERSION = \"$ver-fork.1\";" "$BI.new" && grep -q "VERSION_CODE = $code;" "$BI.new"; then
    mv "$BI.new" "$BI"; git add "$BI"; ok=1
    conflicts=$(grep -vxF "$BI" <<<"$conflicts" || true)
  else
    rm "$BI.new"
  fi
fi
# upstream restructured BuildInfo: the person resolving does it by hand
[ -n "$ok" ] || conflicts=$(printf '%s\n%s\n' "$conflicts" "$BI" | grep . | sort -u)

base=$(git merge-base origin/main "$ut^{commit}")
skip=$(mktemp)
printf '%s\n%s\n' "$BI" "$conflicts" | grep . >"$skip" || true
both=$(comm -12 <(git diff --name-only "$base" origin/main | sort) <(git diff --name-only "$base" "$ut^{commit}" | sort) |
  grep -vxF -f "$skip" || true)
rm "$skip"

echo "upstream: $tag; fork version: $([ -n "$ok" ] && echo "$ver-fork.1, code $code" || echo "BuildInfo needs a hand edit")"
echo "worktree: $dir; branch: $br"
echo "conflicted files: ${conflicts:-none}"
echo "auto-merged files both sides changed (review these): ${both:-none}"
if [ -n "$conflicts" ]; then
  echo "Merge left in progress. Next: AGENTS.md 'Resolve' step."
  exit 3
fi
git commit -q -m "Merge official Buddy $ver ($tag) into the fork" -m "Fork version $ver-fork.1, code $code."
echo "Committed. Next: AGENTS.md 'Review' step."
