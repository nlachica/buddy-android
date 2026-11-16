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
