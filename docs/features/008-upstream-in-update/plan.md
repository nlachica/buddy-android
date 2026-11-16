# Plan: 008-upstream-in-update
<!-- Planner (claude-opus-5-5), 2026-11-16 -->

## Goal
When Nicholas asks Buddy to update, `update_buddy` also checks official Buddy (`ghorbelhamdi/buddy-android`) for a
release the fork doesn't have yet. If there is one, it is merged into the fork (prefer upstream's mechanism, keep fork
lines minimal), conflicts are found and resolved (or the run stops with a clear message and nothing published), CI
builds and tests it, and it ships as the next fork release `<upstream>-fork.1` (VERSION_CODE max+1) that the normal
update installs. Done when: (1) asking for an update with a newer upstream release produces a fork release with
upstream's changes plus features 001-007, and `update_buddy` offers it; (2) conflicts are detected, resolvable ones are
resolved and CI is green before publishing, unresolvable ones stop with a clear message and nothing is published;
(3) no upstream change → behaves as today; (4) the first real run brings 2.3.10 into the fork and it runs on the phone.

Non-goals: changing the approval card or installer route; silent installs; a scheduled/unattended sync (GitHub Action);
the daily "update available" notification and the Settings update sheet (they can't run a merge and keep showing fork
releases only); merging main into open `feat/*` branches (listed in the report, not touched); editing the
pipeline-playbook (its upstream/main-vs-release mismatch goes to retro).

## Design
Three pieces, split by who can do what (research: the app has no git or token; conflicts need an AI; publishing needs
a confirm):

1. **Detect, in the app.** `update_buddy` (github flavor) also reads upstream's `releases/latest` and compares its
   version with the fork's latest *release* (not the installed one) with `-fork.N` stripped, using the existing
   `Updates.compare`. If upstream is ahead it appends one English note to the tool result: official Buddy X isn't in
   the fork yet; a chat with Linux tools brings it in via "Bringing in official Buddy" in the repo's AGENTS.md; ask the
   user whether to do that first. Not ahead, or the upstream call fails → the note is `""`, so every output is
   byte-for-byte today's (done-when 3). The install=true path, card and installer are unchanged.
2. **Merge, deterministically, in a script.** `tools/upstream-sync.sh [<tag>]` does the mechanical half and never
   pushes: finds upstream's latest release tag (`gh api`, or the argument), fetches only that tag into
   `refs/remotes/upstream/tags/<tag>` (fork and upstream tags share names like `v2.3.9`; a plain `fetch --tags` can
   fail with "would clobber"), exits 4 if `origin/main` already contains it, else makes a worktree
   `../buddy-fork-sync` on branch `sync/upstream-<ver>` from `origin/main`, merges the tag `--no-ff --no-commit`,
   writes BuildInfo from upstream's file with `VERSION="<ver>-fork.1"` and `VERSION_CODE=max(ours,theirs)+1`, then
   commits (exit 0) or lists the remaining conflicted files and leaves the merge in progress (exit 3). The worktree
   keeps a checkout that is mid-feature (like this one) untouched.
3. **Resolve and publish, by the agent, from AGENTS.md.** A new section tells the agent: run the script; resolve
   conflicts per the policy (prefer upstream's mechanism, keep fork features and their tests, keep the named fork
   lines); stop with `git merge --abort` and a clear message if a conflict needs feature-sized work; Codex reviews
   hand-resolved *code* conflicts; one `confirm` card (versions + each conflict and its resolution) authorizes push,
   PR, and publish-if-green; PR CI green → merge → tag `v<ver>-fork.1` → tag build (which runs the unit tests before
   it can publish) → `update_buddy install=true`. Same release route the fork already uses (AGENTS.md steps 3-4).

Alternatives (one line each):
- GitHub Action on a schedule: no LLM secret to resolve conflicts, publishes unattended, and the brief's trigger is
  "when updating".
- Merge inside the app: no git, no token, no AI. Rejected.
- All prose, no script: the version arithmetic, tag fetch and "already merged" check are where agents slip; a script
  makes them testable.
- Script also does PR/CI/tag ("publish"): a second release mechanism beside AGENTS.md's; CI already gates (PR checks,
  and the tag build's `testGithubDebugUnitTest` runs before the publish step). Not worth the code.
- Merge `upstream/main` (playbook step 1): pulls unreleased upstream commits; the brief says "newer release".
- Two confirms (before push, before publish): more taps for no added information; the one card comes after the
  merge, so Nicholas sees the resolutions before anything leaves the phone.

## Steps
1. **Upstream note (Java).** `app/src/main/java/app/buddy/assistant/Updates.java`: add
   `UPSTREAM_API = "https://api.github.com/repos/ghorbelhamdi/buddy-android/releases/latest"`; pure
   `static String upstreamNote(String upstream, String forkLatest)` → `""` if `upstream` is null/empty or
   `compare(upstream, forkLatest.replaceFirst("-fork\\.\\d+$", "")) <= 0`, else the note text (mentions the upstream
   version, "Bringing in official Buddy" in the buddy-fork repo's AGENTS.md at `/root/.buddy/work/buddy-fork` or
   `gh repo clone nlachica/buddy-android`, needs Linux tools, the user confirms, CI publishes a new fork release, call
   update_buddy again; tell the user and ask whether to do that first); network wrapper
   `static String upstreamNote(String forkLatest)` that reads `tag_name` via `get(UPSTREAM_API)`, strips `v`, and
   returns `""` on any exception. Update the class Javadoc (installs still only from the fork; upstream is only
   read for its version). New test `app/src/test/java/app/buddy/assistant/UpstreamNoteTest.java` (cases in Test plan).
2. **Wire it into update_buddy.** `app/src/main/java/app/buddy/assistant/Tools.java` `updateBuddy`: append
   `Updates.upstreamNote(r.version)` to the "up to date" return, and in the install=false "update available" return
   put it after the notes and before "Ask the user…". Nothing else in the method changes. Add one sentence to the
   github-flavor `update_buddy` description (`Tools.java:305-310`): it also says when official Buddy has a release the fork
   lacks; see "Bringing in official Buddy" in the same AGENTS.md. F-Droid branch untouched; no `strings.xml` change.
3. **Sync script + its test.** New `tools/upstream-sync.sh` (bash, `set -eu`) per Design 2: upstream remote added if
   missing; `gh api repos/ghorbelhamdi/buddy-android/releases/latest --jq .tag_name` only when no tag argument;
   `git fetch origin main` and `git fetch --no-tags upstream "+refs/tags/$tag:refs/remotes/upstream/tags/$tag"`;
   worktree dir overridable with `SYNC_DIR`; the caller's checkout may be dirty or on any branch (the worktree is
   separate), but exit 1 with nothing changed if the worktree dir or the branch already exists; after setting BuildInfo, `grep`
   both lines and treat BuildInfo as an unresolved conflict if either is missing (upstream restructured it). Always
   print: upstream version, new fork version/code, worktree, branch, conflicted files, and the next AGENTS.md step.
   Exit codes: 0 merged and committed, 3 conflicts left, 4 nothing to do (say whether a `v<ver>-fork.*` tag exists on
   origin, i.e. "release it" vs "wait for the tag build"), 1 error. New `tools/upstream-sync-test.sh`: builds bare
   `origin`/`upstream` repos in a temp dir and runs the cases in Test plan; no network, no `gh` (tag passed in), git
   identity set via env.
4. **CI.** `.github/workflows/build.yml`, `helper-tests` job: one step `bash tools/upstream-sync-test.sh` (a 3-line,
   fork-only hunk; the job already has git).
5. **AGENTS.md.** New section "Bringing in official Buddy" after "Updating Buddy on the phone": when (update_buddy's
   note), where (chat with Linux tools), then the steps: run the script and read its exit code; resolve (policy: prefer
   upstream's mechanism where both fixed the same thing; keep features 001-007 and their tests; keep the fork lines:
   `Updates` REPO/API on nlachica and `UPSTREAM_API`, `build.yml`'s test/watch/F-Droid-check lines, fork notes in
   README/AGENTS/GUIDE; docs keep upstream's text plus the fork's sentence); commit with each conflict and its
   resolution in the message, plus a line in an affected feature's `decisions.md`; Codex reviews hand-resolved code
   (not docs, not BuildInfo) with `git diff refs/remotes/upstream/tags/<tag> HEAD`; stop rule (feature-sized conflict,
   or CI still red after 2 fix attempts → `git merge --abort` / leave the PR open, tell the user which files and why,
   no tag); one `confirm` card; push, `gh pr create -R nlachica/buddy-android --base main`, `gh pr checks --watch`;
   `gh pr merge --merge`; `git fetch origin main && git tag v<ver>-fork.1 origin/main && git push origin
   v<ver>-fork.1`; `gh run watch`; never a prerelease; `update_buddy install=true`; `git worktree remove`; report
   `feat/*` branches behind main. In "Updating Buddy on the phone" step 2 add: `n` restarts at 1 on a new upstream
   version (the sync script sets it).

Rollout (orchestrator, after verify and the 008 PR merge; not Coder steps):
- R1. Release 008 alone as `2.3.9-fork.5` (code 212) per AGENTS.md and install it, so the phone has the new check.
- R2. First real run, on the phone in a chat with Linux tools: "update Buddy" → note about 2.3.10 → follow the new
  section → `v2.3.10-fork.1` (code 213) → `update_buddy install=true` → Nicholas taps Update.

## Test plan
| Done-when | Proof |
|---|---|
| 1. fork release with upstream + 001-007, offered | `UpstreamNoteTest`: `("2.3.10","2.3.9-fork.4")` → note naming 2.3.10; `("2.4.0","2.3.10-fork.2")` → note. Script test "clean": exit 0, HEAD has 2 parents, files from both sides present, BuildInfo `1.0.1-fork.1` / max+1, no conflict markers. R2 evidence: `git merge-base --is-ancestor v2.3.10 v2.3.10-fork.1` and `… v2.3.9-fork.5 v2.3.10-fork.1` both true; PR and tag CI runs green (unit tests of 004/005/007 included); `gh release view v2.3.10-fork.1 -R nlachica/buddy-android` has `buddy.apk`, not prerelease, Latest; the phone's update_buddy (fork.5) output shows the 2.3.10 note before the sync and "Latest release: 2.3.10-fork.1 … available" after. |
| 2. conflicts detected/resolved/green; unresolvable → stop, nothing published | Script test "conflict": exit 3, output lists the conflicting file and not BuildInfo, BuildInfo staged and resolved, merge in progress. Script test "BuildInfo restructured": exit 3 listing BuildInfo. All cases: `origin` refs and tags unchanged (script never pushes). Publishing gate: PR checks green before merge (R2 PR link) and `build.yml` runs `testGithubDebugUnitTest` in the step before "Publish GitHub release" (red → no release). Stop path: AGENTS.md section has the abort rule; Verifier reads it against the brief (an AI judgement can't be unit-tested). |
| 3. no upstream change → as today | `UpstreamNoteTest`: `("2.3.10","2.3.10-fork.1")`, `("2.3.10","2.3.10-fork.3")`, `("2.3.9","2.3.9")`, `(null, …)`, `("", …)` → `""`, so `updateBuddy`'s strings equal today's. Script test "nothing": exit 4, no worktree or branch made. On the phone after R2: update_buddy shows no upstream note. |
| 4. 2.3.10 in the fork, runs on the phone | R2: `update_buddy` after install reports Installed 2.3.10-fork.1; smoke check on the phone: a chat works, the new per-chat Approvals line (upstream 2.3.10) shows, `buddy_settings` reads a setting (004/005), and the watch talks to the phone if it is at hand (007; watch APK from the same release via adb). |

Other cases in `upstream-sync-test.sh`: upstream code higher than the fork's (30 vs 14 → 31); existing worktree or
branch → exit 1 and nothing changed. Local checks: `bash tools/upstream-sync-test.sh`, `bash linux/test/run.sh`;
Gradle/JUnit run in CI (`pipe code-loop --ci`), as the phone has no Android SDK.

## Risks
- **Clean textual merges can still break behavior** (2.3.10 touches `Tools`, `ChatHub`, `Guard`, `Sessions` beside
  features 004/007). CI compiles and runs the unit tests; features without tests (001 audio, 007 watch audio) are only
  covered by the R2 smoke check, then fixed forward.
- **An AI resolution that compiles but is wrong.** Mitigated by the confirm card listing each resolution, Codex review
  of hand-resolved code, and the merge commit message recording them. First run: only BuildInfo (script) and
  `docs/GUIDE.md` (text: keep upstream's new lines and the fork's sentence) conflict.
- **Upstream restructures BuildInfo** → the script's line check reports BuildInfo as a conflict instead of guessing.
- **The note keeps showing while the release build runs** (fork's latest release is still old) → the script exits 4
  and says "merged, wait for the tag build" or "merged but not released: tag it".
- **`main` moves between PR CI and tagging** (unlikely, one user) → the tag build runs the tests again before publishing.
- **The release is public once tagged** (public repo) → covered by the confirm card before any push.
- **Unauthenticated GitHub API (60/h)**: one extra call per update_buddy; a failure only drops the note.
- **gh defaults to the parent repo** with an `upstream` remote (005 learning) → every `gh` call names `-R`.
- The F-Droid reproducibility step runs after publish and can go red without holding the release back (existing).

## Open questions
None blocking. Defaults Nicholas may override (recorded in decisions.md): R1 ships 008 alone first (one extra
Update tap) so the first real run is a true end-to-end test of the trigger; one confirm card per sync covers push,
PR and publish-if-green.
