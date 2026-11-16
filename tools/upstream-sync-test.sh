#!/usr/bin/env bash
# Tests tools/upstream-sync.sh on throwaway git repos (a fake official Buddy and a fork of it); no network, no gh.
set -u
S=$(cd "$(dirname "$0")" && pwd)/upstream-sync.sh
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t \
  GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t UPSTREAM_URL=$T/upstream.git SYNC_DIR=$T/sync
BI=app/src/main/java/app/buddy/assistant/BuildInfo.java
fails=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }
has() { grep -qF -- "$1" <<<"$out"; }
bi() { # bi <version> <code>: write BuildInfo
  mkdir -p "$(dirname "$BI")"; printf 'class BuildInfo {\n    static final String VERSION = "%s";\n    static final int VERSION_CODE = %s;\n}\n' "$1" "$2" >"$BI"
}
# --no-local: this phone's filesystem breaks the hardlinks a local clone makes
clone() { git clone -q --no-local "$@"; }
commit() { git add -A && git commit -qm "$1"; }
refs() { git ls-remote "$T/origin.git"; git ls-remote "$T/upstream.git"; }

# setup <fork code> <upstream code>: upstream 1.0.0 -> fork (1.0.0-fork.1) and upstream 1.0.1 (tagged v1.0.1)
setup() {
  rm -rf "$T"/* "$T"/.[!.]* 2>/dev/null
  git init -qb main "$T/u" && cd "$T/u" || exit 2
  bi 1.0.0 10; seq 1 30 >both.txt; echo a >a.txt; commit base; git tag v1.0.0
  clone --bare . "$T/upstream.git"; clone --bare . "$T/origin.git"
  for r in upstream origin; do git -C "$T/$r.git" config core.createObject rename; done # pushes fail on a hardlink-less fs
  clone "$T/origin.git" "$T/work" && cd "$T/work" || exit 2
  bi 1.0.0-fork.1 "$1"; echo f >fork.txt; sed -i 's/^2$/2 fork/' both.txt; commit "fork 001"
  git push -q origin main; git tag v1.0.0-fork.1; git push -q origin v1.0.0-fork.1
  cd "$T/u" && bi 1.0.1 "$2"; echo u >up.txt; sed -i 's/^29$/29 upstream/' both.txt; commit "upstream 1.0.1"
  git tag v1.0.1; git push -q "$T/upstream.git" main v1.0.1
  cd "$T/work" || exit 2
}
sync() { local before after; before=$(refs); out=$(bash "$S" "$@" 2>&1); rc=$?; after=$(refs); [ "$before" = "$after" ] || { echo "FAIL script changed origin or upstream refs"; fails=$((fails + 1)); }; }
# what GitHub's merge button does with a pushed branch; prints the merge commit
pr_merge() {
  git -C "$T/work" push -q origin "$1"
  rm -rf "$T/pr"; clone "$T/origin.git" "$T/pr"
  git -C "$T/pr" merge -q --no-ff -m "Merge PR $1" "origin/$1" >/dev/null && git -C "$T/pr" push -q origin main && git -C "$T/pr" rev-parse HEAD
}
# a release commit straight on origin main: bump to <version> <code>, tagged when a third argument is given
bump() {
  rm -rf "$T/pr"; clone "$T/origin.git" "$T/pr"; (cd "$T/pr" && bi "$1" "$2" && commit "release $1" && git push -q origin main &&
    { [ -z "${3:-}" ] || { git tag "v$1" && git push -q origin "v$1"; }; } && git rev-parse HEAD)
}
stop() { git -C "$T/sync" merge --abort 2>/dev/null; git worktree remove --force "$T/sync"; git branch -qD sync/upstream-1.0.1; }
at() { git -C "$T/sync" "$@"; }

echo "# clean merge"
setup 11 12
sync v1.0.1
check "exit 0" 'test $rc = 0'
check "HEAD has 2 parents" 'test "$(at rev-list --parents -n1 HEAD | wc -w)" = 3'
check "files from both sides" 'test -f "$T/sync/fork.txt" -a -f "$T/sync/up.txt"'
check "BuildInfo 1.0.1-fork.1, code max+1" 'grep -q "VERSION = \"1.0.1-fork.1\";" "$T/sync/$BI" && grep -q "VERSION_CODE = 13;" "$T/sync/$BI"'
check "both-changed file listed" 'has both.txt'
check "no conflict markers" 'test -z "$(at grep -rl "^<<<<<<<" . || true)"'

echo "# release check on the PR merge commit"
m=$(pr_merge sync/upstream-1.0.1)
tags=$(git ls-remote --tags "$T/origin.git")
sync --release "$m"
check "release: exit 0" 'test $rc = 0'
check "release: prints the tag command" 'has "git tag v1.0.1-fork.1 $m"'
check "release: origin tags unchanged" 'test "$tags" = "$(git ls-remote --tags "$T/origin.git")"'
sync v1.0.1
check "already merged: exit 4, no fork tag" 'test $rc = 4 && has none'
git -C "$T/work" tag v1.0.1-fork.1 "$m" && git -C "$T/work" push -q origin v1.0.1-fork.1
sync v1.0.1
check "already merged: lists the fork tag" 'has v1.0.1-fork.1'
sync --release "$m"
check "already tagged: exit 1" 'test $rc = 1 && has "Already tagged as v1.0.1-fork.1"'
git -C "$T/work" push -q origin :v1.0.1-fork.1

echo "# consecutive syncs (worktree gone, finished branch kept)"
git worktree remove "$T/sync"
cd "$T/u" && bi 1.0.2 14 && commit "upstream 1.0.2" && git tag v1.0.2 && git push -q "$T/upstream.git" main v1.0.2; cd "$T/work" || exit 2
sync v1.0.2
check "second sync: exit 0" 'test $rc = 0'
check "second sync: BuildInfo 1.0.2-fork.1 code 15" 'grep -q "VERSION = \"1.0.2-fork.1\";" "$T/sync/$BI" && grep -q "VERSION_CODE = 15;" "$T/sync/$BI"'

echo "# released meanwhile / main moved past"
setup 11 12; sync v1.0.1; m=$(pr_merge sync/upstream-1.0.1)
b=$(bump 1.0.1-fork.2 14 tag)
sync --release "$m"
check "tagged later: exit 1 naming the newer tag" 'test $rc = 1 && has v1.0.1-fork.2'
setup 11 12; sync v1.0.1; m=$(pr_merge sync/upstream-1.0.1)
b=$(bump 1.0.1-fork.2 14)
sync --release "$m"
check "main moved past: exit 1, fix forward" 'test $rc = 1 && has "Fix forward"'
sync --release "$b"
check "fix-forward commit passes" 'test $rc = 0 && has "git tag v1.0.1-fork.2 $b"'
sync v1.0.1
check "merged sync is never redone: exit 4" 'test $rc = 4'
setup 11 12; sync v1.0.1; m=$(pr_merge sync/upstream-1.0.1)
git -C "$T/work" push -q origin "origin/main~1:refs/tags/v1.0.1-fork.1"
sync --release "$m"
check "tag taken elsewhere: exit 1" 'test $rc = 1 && has taken'

echo "# conflicts"
setup 11 12
(cd "$T/work" && sed -i 's/^29$/29 fork/' both.txt && commit "fork touches the same line" && git push -q origin main)
sync v1.0.1
check "conflict: exit 3" 'test $rc = 3'
check "conflict: lists both.txt, not BuildInfo" 'has "conflicted files: both.txt"'
check "conflict: BuildInfo resolved and staged" 'test "$(at diff --cached --name-only | grep -c BuildInfo)" = 1 -a -z "$(at diff --name-only --diff-filter=U | grep BuildInfo)"'
check "conflict: merge in progress" 'at rev-parse -q --verify MERGE_HEAD >/dev/null'
echo resolved >"$T/sync/both.txt"
sync v1.0.1
check "in progress: exit 5, state printed" 'test $rc = 5 && has "merge in progress: yes" && has "$T/sync"'
check "in progress: resolution and merge kept" 'test "$(cat "$T/sync/both.txt")" = resolved && at rev-parse -q --verify MERGE_HEAD >/dev/null'
stop
sync v1.0.1
check "abort then retry: exit 3 on a fresh worktree" 'test $rc = 3 && grep -q "^<<<<<<<" "$T/sync/both.txt"'
stop

echo "# BuildInfo restructured upstream"
setup 11 12
(cd "$T/u" && sed -i 's/VERSION_CODE = 12;/VERSION_CODE = 10 + 2;/' $BI && commit restructure && git tag -f v1.0.1 && git push -q -f "$T/upstream.git" main v1.0.1)
sync v1.0.1
check "restructured: exit 3, BuildInfo listed" 'test $rc = 3 && has "conflicted files: $BI"'
stop

echo "# main advanced during the sync"
setup 11 12; sync v1.0.1
bump 1.0.0-fork.2 14 >/dev/null
rm -rf "$T/pr2"; clone "$T/origin.git" "$T/pr2"; git -C "$T/work" push -q origin sync/upstream-1.0.1
check "old sync no longer merges into main" 'test -n "$(cd "$T/pr2" && git fetch -q origin && git merge -q --no-ff origin/sync/upstream-1.0.1 >/dev/null 2>&1 || echo conflict)"'
git -C "$T/work" push -q origin :sync/upstream-1.0.1
stop; sync v1.0.1
check "start over: exit 0, code is new max+1" 'test $rc = 0 && grep -q "VERSION_CODE = 15;" "$T/sync/$BI"'
m=$(pr_merge sync/upstream-1.0.1); sync --release "$m"
check "start over: release exit 0" 'test $rc = 0'

echo "# upstream code higher than the fork's"
setup 14 30; sync v1.0.1
check "30 vs 14 gives 31" 'test $rc = 0 && grep -q "VERSION_CODE = 31;" "$T/sync/$BI"'

echo "# nothing to bring in"
setup 11 12
rm -rf "$T/pr"; clone "$T/origin.git" "$T/pr"
(cd "$T/pr" && git fetch -q "$T/upstream.git" refs/tags/v1.0.1 && git merge -q -X ours --no-ff -m m FETCH_HEAD >/dev/null && git push -q origin main)
sync v1.0.1
check "nothing: exit 4, none" 'test $rc = 4 && has none'
check "nothing: no worktree or branch made" 'test ! -e "$T/sync" -a -z "$(git branch --list "sync/*")"'

[ $fails = 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
