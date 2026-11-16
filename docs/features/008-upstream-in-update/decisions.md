# Decisions: 008-upstream-in-update
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-16 - Where the upstream merge runs
- Options: in the app (update_buddy); a scheduled GitHub Action; the agent in a chat with Linux tools, prompted by update_buddy.
- Choice: update_buddy detects and says so; the agent merges, resolves and publishes from an AGENTS.md section, because the app has no git or token, an Action has no LLM to resolve conflicts and would publish unattended, and the brief's trigger is "when updating".
- By: Planner (claude-opus-5-5)

## 2026-11-16 - What to merge: upstream's latest release tag, not upstream/main
- Options: `upstream/main` (playbook upstream-sync step 1); the tag of upstream's `releases/latest`.
- Choice: the release tag, because the brief says "a newer release" and upstream/main can hold unreleased commits. Today both are f93a593 (verified: `git rev-parse v2.3.10^{commit}`). The playbook mismatch is for the retro, not this feature.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Script for the mechanical half, agent for judgement
- Options: all prose in AGENTS.md; a script for fetch/merge/BuildInfo/conflict list; a script that also does PR/CI/tag.
- Choice: `tools/upstream-sync.sh` does fetch, "already merged" check, worktree, merge, BuildInfo and the conflict list, and never pushes; the agent resolves the rest and publishes with the existing release route. Version arithmetic and tag handling are testable in a script; a publish script would duplicate AGENTS.md's release steps, and CI already gates publishing (PR checks; the tag build runs unit tests before the publish step).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Fork version after a sync
- Options: keep counting `n`; restart `n` at 1 per upstream version.
- Choice: `VERSION=<upstream>-fork.1`, `VERSION_CODE=max(fork, upstream)+1` (written by the script from upstream's BuildInfo), because the version names the upstream base it carries and AGENTS.md step 2 already requires max+1. First run: 2.3.10-fork.1, code 213 (after 2.3.9-fork.5 takes 212).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Detection compares upstream with the fork's latest release
- Options: compare with the installed version; with the fork's latest release.
- Choice: the fork's latest release with `-fork.N` stripped, via `Updates.compare`, because once a synced release exists the installed Buddy only needs the normal update. Only update_buddy shows it; the daily notification and the sheet stay as they are (they can't run a merge). A failed upstream call drops the note, so outputs stay as today.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Confirms and the publish gate
- Options: none (fully automatic); one confirm after the local merge; confirms before push and before publish.
- Choice: one `confirm` card after the merge and resolutions, before any push, covering push + PR + publish-if-green; then PR CI green, merge, tag, tag build (tests run before publish), and the unchanged approval card + installer. Golden rule 4 needs a confirm before pushing; a second one adds a tap but no new information. Nicholas may ask for a second.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Stop rule and review for resolved conflicts
- Options: always Codex-review the merge (playbook step 3); never; only hand-resolved code conflicts.
- Choice: Codex reviews only hand-resolved code conflicts (not BuildInfo, not docs). The agent stops (`git merge --abort`, nothing pushed, tells Nicholas which files and why) when a conflict needs feature-sized work (upstream removed or rewrote what a fork feature needs), or when CI is still red after 2 fix attempts (no tag). Keeps the common case cheap; the risky case gets the playbook's cross-family review.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Sync in a separate worktree
- Options: switch branches in the main checkout (needs a clean tree); `git worktree` at `../buddy-fork-sync`.
- Choice: worktree, because the phone's checkout is often mid-feature (a pipeline may be running in it), and it costs one git command.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Rollout: ship 008 alone before the first real sync
- Options: merge 008 and run the first sync straight away (2.3.10-fork.1 carries 008); release 008 as 2.3.9-fork.5 first.
- Choice: 2.3.9-fork.5 first, so the first real run starts from update_buddy's new note on the phone: a true end-to-end check of done-when 1 and 4. Costs one extra Update tap; Nicholas may skip it (then the live note is first seen at upstream 2.3.11).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Out of scope for the update flow
- Merging main into open `feat/*` branches (playbook step 5): only listed in the report. Updating upstream's release notes into the fork release: generated notes name the sync PR. The script test runs in CI's `helper-tests` job (3 fork-only lines in build.yml).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - install=true checks upstream first; `skip_upstream` is the one way past it
- Options: note only on install=false/up-to-date (round 0); block install=true with no way past; block it with a
  `skip_upstream` flag; append the note after the installer opens (lost on restart).
- Choice: the check runs before the branch; with upstream ahead, no card, recommend bringing it in first;
  `install=true skip_upstream=true` installs the fork release as it is. Because the brief wants upstream in the fork
  update, while a stopped sync must not lock out 004's self-update route. Texts in pure `Updates.report` so every
  branch is unit-tested. (Plan review F1.)
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Daily notification and Settings sheet stay fork-only (default, pending Nicholas)
- Options: add an upstream line to the sheet/notification; leave them fork-only.
- Choice: fork-only unless Nicholas says otherwise (plan Open question Q1), because they can't run a merge, would only
  point to a chat, and need new strings in 22 locales, a hot spot for every sync. (Plan review F1.)
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Codex reviews the whole merge every sync (supersedes the review half of "Stop rule and review")
- Options: only hand-resolved code conflicts; the whole merge (playbook step 3).
- Choice: the whole merge, `git diff <upstream tag> HEAD` plus notes, against the fork features' done-when, with the
  script's list of auto-merged files both sides changed. Clean textual merges in shared Tools/ChatHub/Guard code are
  where fork behaviour can silently break; one Codex run per sync is cheap. (Plan review F2.)
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Publish gate: `apk` needs `helper-tests`; reproducibility stays after publish
- Options: leave CI as is; `needs: helper-tests`; also move the reproducibility step before publish; a final publish
  job.
- Choice: `needs: helper-tests` (one line) so the sync-script and helper tests gate the tag build. The reproducibility
  step stays where upstream put it on purpose; it runs in the PR checks, which must be green before merge and tag.
  Prefer upstream's mechanism and keep fork lines minimal. (Plan review F3.)
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Tag the PR's merge commit, checked by `upstream-sync.sh --release`
- Options: tag `origin/main` after merge (round 0); tag the merge SHA by hand; a script check that prints the tag
  command.
- Choice: script check on the merge SHA (upstream tag inside, VERSION `<ver>-fork.<n>`, code above its first parent's
  and upstream's, tag free); it never pushes. An intervening release conflicts on BuildInfo and is handled by
  starting over. Version and tag handling are where agents slip, so they live in the tested script. (Plan review F4.)
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Recovery: stop path cleans up; exit 5 for an existing sync; exit 4 states facts only
- Options: exit 1 on an existing worktree (round 0); resume/restart flags in the script; state report + AGENTS.md map.
- Choice: the stop path removes the worktree and local branch (nothing pushed). An existing sync gives exit 5 with its
  state and changes nothing; AGENTS.md maps each state to resume or start over. Exit 4 prints the fork tags on origin;
  the release/run state comes from two read-only `gh` calls in AGENTS.md, not from the script (it stays offline and
  testable). (Plan review F5.)
- By: Planner (claude-opus-5-5)
