---
type: brief
feature: 023-release-check
created: 2026-11-17
skipped_stages: [research, plan, plan-review, verify]   # small tooling task, built directly; code was reviewed separately
---
# Brief: 023-release-check

## Ask (verbatim, never edit)
Build a pre-release batching check for this Buddy fork: tools/release-check.sh reporting open PRs (CI, mergeable),
unmerged feat/fix branches without a PR, running pipelines and worktrees with unpushed work, and whether official
Buddy is newer, with a one-line RECOMMENDATION and exit 0 ready / 10 wait recommended / 1 error; --json; a test;
AGENTS.md making it mandatory before a version bump or tag; a PreToolUse hook that blocks release tags unless it passes.

## Goal
Every release is a batch: PRs that are ready get merged, official Buddy is brought in, and a running pipeline is
waited for, so Nicholas gets one update on the phone instead of several in a row (each one an install he must tap).

## Non-goals
- No merging, syncing, bumping or tagging by the script: it only reports.
- No change to `upstream-sync.sh`, CI, or the app.
- The hook guards `git tag v...` / `git push origin v...` only, not `gh release create`.

## Done when
- `tools/release-check.sh` prints the sections and RECOMMENDATION; `--json` and `--quiet` work; exit 0/10/1 as above.
- `sh tools/release-check-test.sh` passes (sh, bash and busybox sh).
- AGENTS.md ("Updating Buddy on the phone" and the sync Tag step) makes the check mandatory, with what to do per outcome.
- `.claude/settings.json` + `.claude/hooks/release-gate.sh` block a release tag command unless the check exits 0 or
  `RELEASE_CHECK_OK=1`; other commands pass untouched.
