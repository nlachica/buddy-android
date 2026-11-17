#!/bin/sh
# Tests tools/release-check.sh and .claude/hooks/release-gate.sh. Real git on throwaway repos (a fake origin and a
# clone of it), stubbed gh and ps (first in PATH); no network, nothing outside a temp dir is touched.
set -u
R=$(cd "$(dirname "$0")/.." && pwd)
CHECK=$R/tools/release-check.sh
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t \
  GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t
BI=app/src/main/java/app/buddy/assistant/BuildInfo.java
TAB=$(printf '\t')
fails=0
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }
has() { printf '%s\n' "$out" | grep -qF -- "$1"; }
commit() { git add -A && git commit -qm "$1"; }

# stubs: gh prints the files the test wrote (it ignores --jq, so the files hold the already-filtered TSV)
mkdir -p "$T/bin"
cat >"$T/bin/gh" <<'EOF'
#!/bin/sh
[ -z "${GH_FAIL:-}" ] || { echo "gh: boom" >&2; exit 1; }
case "$1 $2" in
  "pr list") case " $* " in *" -R nlachica/buddy-android "*) cat "$STUB/prs" ;; *) echo "gh pr list without -R fork" >&2; exit 1 ;; esac ;;
  "release view") case " $* " in *" -R ghorbelhamdi/buddy-android "*) cat "$STUB/upstream" ;; *) echo "bad release view" >&2; exit 1 ;; esac ;;
  *) echo "unexpected gh $*" >&2; exit 1 ;;
esac
EOF
cat >"$T/bin/ps" <<'EOF'
#!/bin/sh
echo "  PID     ELAPSED COMMAND"
cat "$STUB/ps"
EOF
chmod +x "$T/bin/gh" "$T/bin/ps"
export STUB=$T/stub PATH="$T/bin:$PATH"
mkdir -p "$STUB"

# fork 2.3.12-fork.1 on origin/main; a clone to run in
setup() {
  rm -rf "$T/origin.git" "$T/work" "$T/wt"*; : >"$STUB/prs"; : >"$STUB/ps"; echo v2.3.12 >"$STUB/upstream"; unset GH_FAIL
  git init -qb main "$T/seed" && cd "$T/seed" || exit 2
  mkdir -p "$(dirname "$BI")"
  printf 'class BuildInfo {\n    static final String VERSION = "2.3.12-fork.1";\n    static final int VERSION_CODE = 227;\n}\n' >"$BI"
  commit base
  git clone -q --bare --no-local . "$T/origin.git"; cd /; rm -rf "$T/seed"
  git config --file "$T/origin.git/config" core.createObject rename
  git clone -q --no-local "$T/origin.git" "$T/work" && cd "$T/work" || exit 2
}
# branch <name> [<committer date>]: push a branch with one commit
branch() {
  git checkout -qb "$1" origin/main && echo "$1" >"f-$(echo "$1" | tr / -)" && git add -A &&
    GIT_COMMITTER_DATE="${2:-}" git commit -qm "$1" ${2:+--date "$2"} && git push -q origin "$1" && git checkout -q main
}
run() { out=$(sh "$CHECK" "$@" 2>&1); rc=$?; }

echo "# nothing going on"
setup
run
check "exit 0" 'test $rc = 0'
check "ready: release now" 'has "RECOMMENDATION: ready: release now"'

echo "# two ready PRs, newer upstream"
setup
printf '5\tfeat/a\tMERGEABLE\tfalse\tpass\tnlachica\n7\tfix/b\tMERGEABLE\tfalse\tpass\tnlachica\n' >"$STUB/prs"
echo v2.4.0 >"$STUB/upstream"
run
check "exit 10" 'test $rc = 10'
check "merge both, sync, release once" 'has "RECOMMENDATION: merge PRs #5 #7, bring in upstream v2.4.0, then release once"'
echo v2.3.12 >"$STUB/upstream"
printf '9\tsync/upstream-2.4.0\tMERGEABLE\tfalse\tpass\tnlachica\n' >"$STUB/prs"; echo v2.4.0 >"$STUB/upstream"
run
check "a ready sync PR is not asked for twice" 'has "RECOMMENDATION: merge PR #9, then release once"'

echo "# failing, conflicting and draft PRs are excluded, not blocking"
setup
printf '3\tfeat/x\tMERGEABLE\tfalse\tfail\tnlachica\n4\tfeat/y\tCONFLICTING\tfalse\tpass\tnlachica\n6\tfeat/z\tMERGEABLE\ttrue\tpass\tnlachica\n' >"$STUB/prs"
run
check "exit 0" 'test $rc = 0'
check "listed as excluded" 'has "ready: release now (excluded: #3 failing CI, #4 merge conflicts, #6 draft)"'

echo "# pending CI, UNKNOWN mergeable"
setup
printf '8\tfeat/p\tMERGEABLE\tfalse\tpending\tnlachica\n' >"$STUB/prs"
run
check "wait on pending CI" 'test $rc = 10 && has "wait: CI still pending on #8"'
printf '8\tfeat/p\tUNKNOWN\tfalse\tpass\tnlachica\n' >"$STUB/prs"
run
check "UNKNOWN mergeable is not ready" 'test $rc = 10'

echo "# running pipelines"
setup
cat >"$STUB/ps" <<EOF
 9001     10:00 bash /root/pipeline-playbook/bin/pipe code-loop --ci $T/work 024-new-thing extra words here
 9002     10:00 bash /root/pipeline-playbook/bin/pipe code-loop --ci $T/work 024-new-thing extra words here
 9003     01:00 timeout 3000 claude -p Build it and run pipe code-loop on 025-text-only
 9004     02:00 bash -c cd x && pipe plan-loop $T/work 026-in-a-string
 9005     03:00 /bin/bash /usr/local/bin/pipe code-loop /somewhere/else 027-other-repo
 9006 01-02:00:00 vim pipe
EOF
mkdir -p "$T/other" && git -C "$T/other" init -q
cat >>"$STUB/ps" <<EOF
 9007     03:00 bash /usr/local/bin/pipe code-loop $T/other 028-other-repo
EOF
run
check "exit 10" 'test $rc = 10'
check "one pipeline 024, code-loop, remaining time" 'has "wait: pipeline 024 is in code-loop (~35 min)" && test "$(printf "%s\n" "$out" | grep -c "024-new-thing")" = 1'
check "text mentions of pipe, other repos ignored" '! has 025 && ! has 026 && ! has 028'
check "unresolvable repo path still counts (027)" 'has "pipeline 027"'
printf ' 9010     00:30 bash /x/pipe run coder %s 029-fix\n' "$T/work" >"$STUB/ps"
run
check "pipe run <role> is parsed" 'has "pipeline 029 is in run coder"'
run --json
check "json carries the slug" 'has "\"slug\":\"029-fix\""'

echo "# branches without a PR"
setup
branch feat/fresh
branch fix/old "2020-01-01T00:00:00Z"
branch feat/haspr
printf '12\tfeat/haspr\tMERGEABLE\tfalse\tpass\tnlachica\n' >"$STUB/prs"
git checkout -qb feat/merged origin/main && echo m >m.txt && git add -A && git commit -qm m && git checkout -q main && git merge -q --no-ff feat/merged -m merge && git push -q origin main feat/merged
run
check "fresh branch holds the release" 'has "branch feat/fresh has no PR yet"'
check "old branch listed, not blocking" 'has "fix/old" && ! has "branch fix/old has no PR"'
check "branch with a PR or merged is not listed as no-PR" '! has "feat/merged  last" && ! has "feat/haspr  last"'
check "ready PR still to merge after the wait" 'has "then merge PR #12"'

echo "# HEAD must contain origin/main"
setup
git clone -q --no-local "$T/origin.git" "$T/wt-pusher" && (cd "$T/wt-pusher" && echo n >n && commit n && git push -q origin main)
run
check "behind the fetched origin/main is not ready" 'test $rc = 10 && has "RECOMMENDATION: update this checkout first" && has "git merge --ff-only origin/main"'
git merge -q --ff-only origin/main
run
check "caught up is ready" 'test $rc = 0'

echo "# branch discovery sees origin, not local refs; PR must be from this repo"
setup
branch feat/fresh
git clone -q --no-local --single-branch -b main "$T/origin.git" "$T/wt-single"
check "(the single-branch clone has no origin/feat/fresh)" '! git -C "$T/wt-single" rev-parse -q --verify origin/feat/fresh >/dev/null'
out=$(cd "$T/wt-single" && sh "$CHECK" 2>&1); rc=$?
check "single-branch clone still waits for the fresh branch" 'test $rc = 10 && has "branch feat/fresh has no PR yet"'
printf '13\tfeat/fresh\tMERGEABLE\ttrue\tpass\tsomeone-else\n' >"$STUB/prs"
run
check "another fork's PR with the same branch name does not hide it" 'has "branch feat/fresh has no PR yet"'
printf '13\tfeat/fresh\tMERGEABLE\tfalse\tpass\tnlachica\n' >"$STUB/prs"
run
check "this repo's own PR does" '! has "has no PR yet"'

echo "# pipelines in other clones of Buddy"
setup
git clone -q --no-local "$T/origin.git" "$T/wt-clone2"
mkdir -p "$T/wt-bf-x" "$T/bf-y" "$T/wt-plain" && git -C "$T/wt-plain" init -q
printf ' 9020     00:30 bash /x/pipe code-loop %s 031-clone\n' "$T/wt-clone2" >"$STUB/ps"
run
check "pipeline in a separate clone of the same origin counts" 'test $rc = 10 && has "pipeline 031"'
printf ' 9021     00:30 bash /x/pipe code-loop %s 032-named\n' "$T/bf-y" >"$STUB/ps"
run
check "pipeline in a bf-* folder counts" 'has "pipeline 032"'
printf ' 9022     00:30 bash /x/pipe code-loop %s 033-plain\n' "$T/wt-plain" >"$STUB/ps"
run
check "pipeline in an unrelated repo does not" 'test $rc = 0'

echo "# other worktrees"
setup
git worktree add -q "$T/wt-dirty" -b feat/dirty origin/main && echo x >"$T/wt-dirty/untracked"
git worktree add -q "$T/wt-ahead" -b feat/ahead origin/main && (cd "$T/wt-ahead" && echo y >y && commit y)
git worktree add -q "$T/wt-clean" -b feat/clean origin/main
run
check "uncommitted listed" 'has "worktree wt-dirty (feat/dirty) has uncommitted changes"'
check "unpushed listed" 'has "worktree wt-ahead (feat/ahead) has 1 unpushed commit(s)"'
check "clean worktree not listed" '! has wt-clean'
check "wait" 'test $rc = 10'
(cd "$T/wt-ahead" && git push -q origin feat/ahead)
run
check "pushed branch no longer counts as unpushed" '! has wt-ahead'
(cd "$T/wt-dirty" && sh "$CHECK" --quiet >/dev/null 2>&1; true)
out=$(cd "$T/wt-dirty" && sh "$CHECK" 2>&1)
check "the worktree it runs in is skipped" '! has wt-dirty'

echo "# output modes and errors"
setup
printf '5\tfeat/a\tMERGEABLE\tfalse\tpass\tnlachica\n' >"$STUB/prs"
run --quiet
check "--quiet prints one line" 'test "$(printf "%s\n" "$out" | wc -l | tr -d " ")" = 1'
run --json
check "--json has recommendation and exit" 'has "\"exit\":10" && has "\"recommendation\":\"merge PR #5, then release once\""'
if command -v jq >/dev/null; then check "--json is valid JSON" 'printf "%s" "$out" | jq -e .prs[0].number >/dev/null'; fi
GH_FAIL=1 run
check "gh failure is exit 1, not ready" 'test $rc = 1'
run --bogus
check "unknown argument is exit 1" 'test $rc = 1'

if command -v jq >/dev/null; then
  echo "# CI state expression (jq; gh uses gojq, same syntax)"
  JQ=$(sed -n "/^JQ='/,/@tsv'/p" "$CHECK" | sed "1s/^JQ='//; \$s/'\$//")
  ci() { printf '[{"number":1,"headRefName":"b","mergeable":"MERGEABLE","isDraft":false,"statusCheckRollup":%s}]' "$1" | jq -r "$JQ" | cut -f5; }
  check "all success = pass" '[ "$(ci "[{\"status\":\"COMPLETED\",\"conclusion\":\"SUCCESS\"},{\"status\":\"COMPLETED\",\"conclusion\":\"SKIPPED\"}]")" = pass ]'
  check "one failure = fail" '[ "$(ci "[{\"status\":\"COMPLETED\",\"conclusion\":\"SUCCESS\"},{\"status\":\"COMPLETED\",\"conclusion\":\"FAILURE\"}]")" = fail ]'
  check "one running = pending" '[ "$(ci "[{\"status\":\"COMPLETED\",\"conclusion\":\"SUCCESS\"},{\"status\":\"IN_PROGRESS\",\"conclusion\":\"\"}]")" = pending ]'
  check "no checks yet = pending" '[ "$(ci "[]")" = pending ]'
  check "failure beats running" '[ "$(ci "[{\"status\":\"QUEUED\"},{\"status\":\"COMPLETED\",\"conclusion\":\"TIMED_OUT\"}]")" = fail ]'
  check "status context SUCCESS = pass" '[ "$(ci "[{\"state\":\"SUCCESS\"}]")" = pass ]'
fi

echo "# hook"
H=$T/hook; mkdir -p "$H/.claude/hooks" "$H/tools"
cp "$R/.claude/hooks/release-gate.sh" "$H/.claude/hooks/"
printf '#!/bin/sh\n[ -e "$PWD/READY" ] && exit 0\necho "RECOMMENDATION: wait: pipeline 024 is in code-loop (~30 min); release after it [in $PWD]"\nexit "${FAKE_RC:-10}"\n' >"$H/tools/release-check.sh"
mkdir -p "$H/ready" "$H/notready"; : >"$H/ready/READY"
hook() { err=$(printf '{"tool_input":{"command":"%s"}}' "$1" | (cd / && sh "$H/.claude/hooks/release-gate.sh") 2>&1 >/dev/null); hrc=$?; }
for c in 'ls -la' 'git status' 'git commit -m release' 'git push origin main' 'git tag -l v*' 'git tag' 'git tag -l v2*' \
  'git tag --list v2*' 'git tag -d v2.1' 'git push --dry-run origin v2.1' 'git push -n origin refs/tags/v2.1' \
  'git tag -n v2.1' 'git commit -m \"git tag v1\"' 'echo git tag v1' 'git tag -m \"release v2 now\" notes' 'git push origin --delete v2.1'; do
  hook "$c"; check "passes: $c" 'test $hrc = 0'
done
for c in 'git tag v2.3.12-fork.2' 'git add -A && git tag v1.0.0 && git push origin v1.0.0' 'git push origin v2.3.12-fork.2' \
  'git -C /x push origin v3.0' 'git tag -a v1.0 -m x' 'git tag \"v2.1\"' "git tag 'v2.1'" 'git push origin refs/tags/v2.1' \
  'git push origin HEAD:refs/tags/v9' 'git push origin +v2.1' 'git push --tags' 'git tag -a -m \"note\" v2.0' 'git tag -f v2.0 abc' \
  'git tag v1 -m RELEASE_CHECK_OK=1' 'git tag -a v1 -m \"RELEASE_CHECK_OK=1\"' 'echo RELEASE_CHECK_OK=1; git tag v1' \
  'git tag v1 # RELEASE_CHECK_OK=1' 'git push origin v1 -o RELEASE_CHECK_OK=1' 'git add -A\ngit tag v1'; do
  hook "$c"; check "blocks: $c" 'test $hrc = 2'
done
hook "cat <<'EOF' >notes\ngit tag v1\ngit push origin refs/tags/x\nEOF\nls"; check "heredoc body is data, not commands" 'test $hrc = 0'
hook "cat <<EOF\nit's\nEOF\ngit tag v1"; check "a tag command after a heredoc is still seen" 'test $hrc = 2'
hook 'git tag v1'
check "recommendation reaches stderr" 'printf "%s" "$err" | grep -qF "wait: pipeline 024 is in code-loop"'
RELEASE_CHECK_OK=1 hook 'git tag v1'; check "RELEASE_CHECK_OK=1 in env lets it through" 'test $hrc = 0'
hook 'RELEASE_CHECK_OK=1 git tag v1'; check "RELEASE_CHECK_OK=1 as a prefix lets it through" 'test $hrc = 0'
hook 'git add -A && RELEASE_CHECK_OK=1 git tag v1'; check "RELEASE_CHECK_OK=1 leading a later command lets it through" 'test $hrc = 0'
hook 'FOO=bar RELEASE_CHECK_OK=1 git push origin v1'; check "RELEASE_CHECK_OK=1 among leading assignments" 'test $hrc = 0'
hook "git -C $H/ready tag v1"; check "git -C: runs the check in the target repo (ready)" 'test $hrc = 0'
hook "git -C $H/notready tag v1"; check "git -C: other repo not ready blocks, and says where" 'test $hrc = 2 && printf "%s" "$err" | grep -qF "in $H/notready"'
hook "cd $H/ready && git tag v1"; check "cd dir && git tag: uses that repo" 'test $hrc = 0'
hook "cd $H && git -C ready tag v1"; check "relative -C after a cd" 'test $hrc = 0'
hook 'git -C /nonexistent-dir tag v1'; check "unreachable -C blocks" 'test $hrc = 2 && printf "%s" "$err" | grep -qF "cannot enter"'
FAKE_RC=0 hook 'git tag v1'; check "check says ready: tag allowed" 'test $hrc = 0'
FAKE_RC=1 hook 'git tag v1'; check "check error blocks too" 'test $hrc = 2'

echo
if [ $fails = 0 ]; then echo "all passed"; else echo "$fails failed"; exit 1; fi
