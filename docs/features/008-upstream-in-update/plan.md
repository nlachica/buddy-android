# Plan: 008-upstream-in-update
<!-- Planner (claude-opus-5-5), 2026-11-16; revised after plan review rounds 1 and 2 -->

## Goal
When Nicholas asks Buddy to update (`update_buddy`, or Settings → Check for updates), Buddy also checks official Buddy
(`ghorbelhamdi/buddy-android`) for a release the fork doesn't have yet. If there is one, it is merged into the fork
(prefer upstream's mechanism, keep fork lines minimal), conflicts are found and resolved (or the run stops with a
clear message and nothing published), CI builds and tests it, and it ships as the next fork release
`<upstream>-fork.1` (VERSION_CODE max+1) that the normal update installs. Done when: (1) asking for an update with a
newer upstream release produces a fork release with upstream's changes plus features 001-007, and `update_buddy`
offers it; (2) conflicts are detected, resolvable ones are resolved and CI is green before publishing, unresolvable
ones stop with a clear message and nothing is published; (3) no upstream change → behaves as today; (4) the first real
run brings 2.3.10 into the fork and it runs on the phone.

Non-goals: changing the approval card or installer route; silent installs; a scheduled/unattended sync (GitHub Action);
merging main into open `feat/*` branches (listed in the report, not touched); editing the pipeline-playbook (its
upstream/main-vs-release mismatch goes to retro). The daily "update available" notification stays fork-only: it fires
only for a fork release and opens the sheet, which does check official Buddy; the ask's trigger is "when updating", and
a background announcement isn't that (a default Nicholas may override).

## Design
Three pieces, split by who can do what (research: the app has no git or token; conflicts need an AI; publishing needs
a confirm):

1. **Detect, in the app, before any install.** `update_buddy` (github flavor) also reads upstream's `releases/latest`
   and compares its version with the fork's latest *release* (not the installed one) with `-fork.N` stripped, using
   the existing `Updates.compare`. The check runs before the tool picks a branch, so every call sees it:
   - fork up to date, upstream ahead → "up to date" plus the note: official X isn't in the fork; bring it in (a chat
     with Linux tools, "Bringing in official Buddy" in the repo's AGENTS.md), then `update_buddy install=true`.
   - fork release Y available, upstream ahead, install=false or true → no card; the note says the sync's release will
     contain Y too, so bring X in first; if the user wants Y now anyway (or the sync stopped), call
     `update_buddy install=true skip_upstream=true`. One action is recommended, never both at once.
   - upstream not ahead, upstream call failed, or `skip_upstream=true` → today's outputs and today's card + installer,
     byte-for-byte (done-when 3).
   The texts come from one pure `Updates.report(...)`, so every branch, install=true included, is unit-tested. The
   handoff ends where 004's route starts: the agent that brought X in calls `update_buddy install=true`, which shows
   the unchanged card and installer.
   **Settings → Check for updates** (also the sheet the daily notification opens) makes the same comparison after its
   fork check and hands off to a chat: up to date → a toast "Official Buddy X isn't in your fork yet: ask Buddy in a
   chat to update" instead of "up to date"; fork update available → the same line under the sheet's subtitle, Update
   unchanged (the user's own tap, like `skip_upstream`). Upstream call failed or not ahead → today's toast and sheet.
2. **Merge, deterministically, in a script.** `tools/upstream-sync.sh [<tag>]` does the mechanical half and never
   pushes: finds upstream's latest release tag (`gh api`, or the argument), fetches only that tag into
   `refs/remotes/upstream/tags/<tag>` (fork and upstream tags share names like `v2.3.9`; a plain `fetch --tags` can
   fail with "would clobber"), exits 4 if `origin/main` already contains it, exits 5 if the sync worktree or an
   unfinished sync branch (one origin/main doesn't contain) exists, else makes a worktree `../buddy-fork-sync` on
   branch `sync/upstream-<ver>` from `origin/main`, merges the tag `--no-ff --no-commit`, writes BuildInfo from
   upstream's file with `VERSION="<ver>-fork.1"` and `VERSION_CODE=max(ours,theirs)+1`, then commits (exit 0) or lists
   the remaining conflicted files and leaves the merge in progress (exit 3). It also lists files both sides changed
   that git auto-merged (review focus). `tools/upstream-sync.sh --release <merge-sha>` checks the merged PR commit
   before tagging (not already inside a tagged release, upstream tag inside, BuildInfo version/code new against its
   first parent and upstream and not below origin/main's, tag free on origin) and prints the tag command, or why not
   and the way out.
3. **Resolve and publish, by the agent, from AGENTS.md.** A new section tells the agent: run the script; resolve
   conflicts per the policy; Codex reviews the whole merge (playbook step 3), with the both-changed files called out;
   one `confirm` card (versions, each conflict and its resolution, review result) authorizes push, PR, and
   publish-if-green; all PR checks green → merge → `--release` on the PR's merge commit → tag that SHA → tag build
   (`apk` now needs `helper-tests`, and runs the unit tests before it can publish) → `update_buddy install=true` →
   smoke check → remove the worktree and the finished branch. Stop and resume paths are spelled out (Step 5); once
   the sync PR is merged, the upstream merge is never redone: a stale candidate is fixed forward from current main.

Alternatives (one line each):
- GitHub Action on a schedule: no LLM secret to resolve conflicts, publishes unattended, and the brief's trigger is
  "when updating".
- Merge inside the app: no git, no token, no AI. Rejected.
- All prose, no script: the version arithmetic, tag fetch, "already merged" and tag checks are where agents slip; a
  script makes them testable.
- Script also pushes, opens the PR, tags: a second release mechanism beside AGENTS.md's. Not worth the code.
- Merge `upstream/main` (playbook step 1): pulls unreleased upstream commits; the brief says "newer release".
- Move the F-Droid reproducibility step before publish: changes upstream's deliberate order (`build.yml:107-108`);
  the PR checks already run it on the same tree before anything is tagged.
- A `gh` stub harness to test release-state reads: two read-only `gh` calls in prose are cheaper than a test rig.

## Steps
1. **Upstream note and report (Java).** `app/src/main/java/app/buddy/assistant/Updates.java`: add `UPSTREAM_API =
   "https://api.github.com/repos/ghorbelhamdi/buddy-android/releases/latest"`; pure `static boolean
   upstreamAhead(String upstream, String forkLatest)` → false if `upstream` is null/empty, else `compare(upstream,
   forkLatest.replaceFirst("-fork\\.\\d+$", "")) > 0`; pure `static String upstreamNote(String upstream, String
   forkLatest)` → `""` unless `upstreamAhead`, else one sentence: official Buddy X is out and isn't in this fork yet;
   a chat with Linux tools brings it in by following "Bringing in official Buddy" in the buddy-fork repo's AGENTS.md
   (`/root/.buddy/work/buddy-fork`, or `gh repo clone nlachica/buddy-android`); the user confirms, and the fork's CI
   publishes a new fork release. Network `static String upstreamVersion()` reads `tag_name` via `get(UPSTREAM_API)`,
   strips `v`, returns `null` on any exception. Pure `static String report(String installed, Release r, String note,
   boolean install)`: returns the tool text, or `null` when the install should go ahead (card + installer). With
   `note` empty it returns today's two strings verbatim (moved from `Tools.updateBuddy`) and `null` for install=true
   with an update. With a note: up to date → today's text + note + "ask whether to bring it in now; afterwards call
   update_buddy with install=true"; update Y available (either install value) → head (+ " Not installed yet." when
   install=true) + "An update is available. What's new" + notes + note + "that release will contain Y too, so
   recommend bringing X in first; if the user wants Y now anyway, or bringing X in stopped, call update_buddy with
   install=true and skip_upstream=true". Class Javadoc: installs still only from the fork; upstream is only read for
   its version. New test `app/src/test/java/app/buddy/assistant/UpstreamNoteTest.java` (cases in Test plan).
2. **Wire it into update_buddy and the sheet.** `app/src/main/java/app/buddy/assistant/Tools.java`: `installs` passes
   `a.optBoolean("skip_upstream", false)`; `updateBuddy(install, skipUpstream)`: after `latest()`, `note =
   skipUpstream ? "" : Updates.upstreamNote(Updates.upstreamVersion(), r.version)`, `text =
   Updates.report(BuildInfo.VERSION, r, note, install)`, `if (text != null) return text;` then the card and installer
   code exactly as today. github-flavor schema (`Tools.java:305-310`): add `skip_upstream` (boolean: "true: install
   this fork release without first bringing in a newer official Buddy (the user chose that, or bringing it in
   stopped)") and one description sentence: it also says when official Buddy has a release the fork lacks; see
   "Bringing in official Buddy" in the same AGENTS.md. F-Droid branch untouched. Sheet, `Updates.java` `showSheet`: in
   its thread after `latest()`, `String up = upstreamVersion(); boolean ahead = upstreamAhead(up, r.version);`. Not
   newer: `ahead` → toast `update_upstream_ahead` (LENGTH_LONG) instead of `update_up_to_date`, else as today. Newer:
   `sheet(a, r, ahead ? up : null)` appends "\n" + that line to the subtitle; the Update button is unchanged.
   `app/src/main/res/values/strings.xml` only: `update_upstream_ahead` = "Official Buddy %1$s isn't in your fork yet:
   ask Buddy in a chat to update." with a translator comment; English only, like 001/003/005's strings (other locales
   fall back to English).
3. **Sync script + its test.** New `tools/upstream-sync.sh` (bash, `set -eu`) per Design 2: upstream remote added if
   missing; `gh api repos/ghorbelhamdi/buddy-android/releases/latest --jq .tag_name` only when no tag argument;
   `git fetch origin main` and `git fetch --no-tags upstream "+refs/tags/$tag:refs/remotes/upstream/tags/$tag"`;
   worktree dir overridable with `SYNC_DIR`; the caller's checkout may be dirty or on any branch.
   - Exit 4 (origin/main contains the tag): print the `v<ver>-fork.*` tags on origin (`git ls-remote --tags`), or
     "none", and point to AGENTS.md's "already merged" step. No claim about the tag build.
   - Exit 5 (after `git worktree prune`, the worktree dir exists, or a `sync/upstream-*` branch that isn't an
     ancestor of origin/main; a finished sync's branch is ignored): change nothing; print the dir, branch, whether a
     merge is in progress (`MERGE_HEAD`), unmerged files, whether the branch is on origin, and whether origin/main
     contains it; point to AGENTS.md's resume step.
   - Otherwise merge as in Design 2. After setting BuildInfo, `grep` both lines and treat BuildInfo as an unresolved
     conflict if either is missing (upstream restructured it). Print: upstream version, new fork version/code,
     worktree, branch, conflicted files, auto-merged files both sides changed (`comm -12` of `git diff --name-only`
     from the merge-base to each side, minus conflicts), and the next AGENTS.md step. Exit 0 committed, 3 conflicts
     left, 1 error.
   - `--release <sha>`: `git fetch origin main`, read VERSION/VERSION_CODE at `<sha>`, then in order (exit 1 with the
     reason and the way out on the first that fails):
     1. no `v*` tag on origin (`git ls-remote --tags origin`, peeled; commits not present locally skipped) points at
        `<sha>` or a commit containing it; else "already tagged as vX, which contains it: AGENTS.md 'already merged'
        with vX";
     2. VERSION is `<ver>-fork.<n>`, `refs/remotes/upstream/tags/v<ver>` (fetched if missing) is an ancestor of
        `<sha>`, VERSION_CODE is above both `<sha>^1`'s and the upstream tag's, VERSION differs from `<sha>^1`'s, and
        VERSION_CODE is not below origin/main's; else "fix forward: release current main as the next `-fork.<n>` per
        AGENTS.md 'Updating Buddy on the phone' steps 2-3, checking that commit with --release first";
     3. `v<VERSION>` isn't on origin; else "tag taken: AGENTS.md 'already merged' with v<VERSION>".
     All pass → print `git tag v<VERSION> <sha> && git push origin v<VERSION>` and exit 0. Never tags or pushes itself.
     A fix-forward commit passes the same checks (upstream tag inside, code new against its parent and main).
   New `tools/upstream-sync-test.sh`: builds bare `origin`/`upstream` repos in a temp dir and runs the cases in Test
   plan; no network, no `gh` (tag passed in), git identity set via env; it plays GitHub's PR merge in a temp clone.
4. **CI.** `.github/workflows/build.yml`: `helper-tests` gets a step `bash tools/upstream-sync-test.sh`; `apk` gets
   `needs: helper-tests`, so a red helper or sync-script test means no APK and no release on a tag. Two small
   fork-only hunks; the reproducibility step stays where upstream put it.
5. **AGENTS.md.** New section "Bringing in official Buddy" after "Updating Buddy on the phone": when (update_buddy's
   note), where (a chat with Linux tools; if this chat has none, the user turns them on or opens one), then:
   - Run the script; act on its exit code (0/3 continue, 4 already merged, 5 resume, 1 error → tell the user).
   - Resolve: prefer upstream's mechanism where both fixed the same thing; keep features 001-007 and their tests;
     keep the fork lines (`Updates` REPO/API on nlachica and `UPSTREAM_API`, `build.yml`'s test/watch/F-Droid-check
     and `needs` lines, fork notes in README/AGENTS/GUIDE; docs keep upstream's text plus the fork's sentence); commit
     with each conflict and its resolution in the message, plus a line in an affected feature's `decisions.md`.
   - Review: Codex reviews the whole merge, `git diff refs/remotes/upstream/tags/<tag> HEAD` (the fork's lines on
     upstream's base) plus the script's both-changed list and the merge message, against each fork feature's
     done-when (`docs/features/00*/brief.md`); patch only real gaps, minimally, each recorded.
   - Stop rule: a conflict needing feature-sized work (upstream removed or rewrote what a fork feature needs), or a
     review gap that is feature-sized → `git merge --abort` if in progress, `git worktree remove --force <dir>`,
     `git branch -D sync/upstream-<ver>` (nothing was pushed), tell the user which files and why; nothing published.
     CI still red after 2 fix attempts → leave the PR open, no tag, tell the user.
   - One `confirm` card: versions, conflicts and resolutions, review result. Then push,
     `gh pr create -R nlachica/buddy-android --base main`, `gh pr checks --watch` (all checks must pass, the `apk`
     job's reproducibility step included), `gh pr merge --merge`.
   - Tag: `sha=$(gh pr view <n> -R nlachica/buddy-android --json mergeCommit -q .mergeCommit.oid)`, `git fetch
     origin`, `tools/upstream-sync.sh --release "$sha"`, run the printed tag command, `gh run watch`; never a
     prerelease. If `--release` refuses, do the way out it prints (already tagged → 'already merged' with that tag;
     fix forward → a normal fork release from current main). Once the sync PR is merged, never redo the upstream
     merge. Only if GitHub refuses the PR merge itself (main got a release first: both sides change BuildInfo's lines)
     → start over: with the user's confirm close the PR (`gh pr close -d`), clean up as in the stop rule, rerun the
     script, reapply the resolutions from the old merge message.
   - Install: `update_buddy install=true`; after the restart, smoke check: a chat answers, `buddy_settings` reads a
     setting, Settings → Voice → Audio device plays the sample, and if the watch is at hand a watch talk and a watch
     call. Then clean up: `git worktree remove <dir>`, and once `git merge-base --is-ancestor sync/upstream-<ver>
     origin/main` holds, `git branch -D sync/upstream-<ver>`; report `feat/*` branches behind main.
   - Exit 4, already merged: no fork tag → find the merged sync PR (`gh pr list -R … -H sync/upstream-<ver> --state
     merged`), go to Tag. A tag → `gh release view <tag> -R … --json assets,isPrerelease`: `buddy.apk` there →
     `update_buddy` (if its note still shows, the release isn't Latest: `gh release edit <tag> -R … --prerelease=false
     --latest`); no APK → `gh run list -R … --branch <tag> -L 1`: running → `gh run watch`; failed → show the user the
     failed step (`gh run view <id> --log-failed | tail -40`), `gh run rerun <id> --failed` once if it is
     infrastructure, else fix forward (next `-fork.<n>` via "Updating Buddy on the phone", checked with `--release`).
     Never move a tag.
   - Exit 5, resume: origin/main contains the branch → that sync finished: clean up as in Install, rerun; merge in
     progress → keep resolving in that worktree (its resolutions are kept); committed but not pushed → continue at
     Review; on origin → `gh pr list -R … -H <branch> --state all --json number,state`: OPEN → continue at the PR,
     MERGED → Tag, CLOSED → start over; for an older upstream version, or the user wants to start over → start over
     as above.
   In "Updating Buddy on the phone" step 2 add: `n` restarts at 1 on a new upstream version (the sync script sets it).

Rollout (orchestrator, after the 008 PR merge; not Coder steps):
- R1. Release 008 alone as `2.3.9-fork.5` (code 212) per AGENTS.md and install it, so the phone has the new check.
- R2. First real run, on the phone in a chat with Linux tools: "update Buddy" → note about 2.3.10 → follow the new
  section → `v2.3.10-fork.1` (code 213) → `update_buddy install=true` → Nicholas taps Update → evidence table below.
- Verify: done-when 2 and 3 are verified before the PR merge; rows 1 and 4 stay PENDING in `verify.md`, and the
  verdict stays open, until R2's evidence is recorded.

## Test plan
| Done-when | Proof |
|---|---|
| 1. fork release with upstream + 001-007, offered | `UpstreamNoteTest`: `upstreamNote("2.3.10","2.3.9-fork.4")` → note naming 2.3.10; `("2.4.0","2.3.10-fork.2")` → note. `report` with a note: update available + install=true → not `null` (no card), says "Not installed yet", has the note and `skip_upstream=true`; install=false → same advice, not today's "Ask the user if they want it now"; up to date → today's text + note + `install=true`. Script "clean": exit 0, HEAD has 2 parents, files from both sides present, BuildInfo `1.0.1-fork.1` / max+1, both-changed list printed, no conflict markers. Script "release": test merges the sync branch into origin main → `--release <sha>` exit 0, prints `git tag v1.0.1-fork.1 <sha>`, origin tags unchanged. "released meanwhile": after "release", origin main gets a commit bumping to `1.0.1-fork.2` (code+1) tagged `v1.0.1-fork.2` → `--release <merge sha>` exit 1 naming `v1.0.1-fork.2`. "main moved past": the same bump commit B, untagged → `--release <merge sha>` exit 1 saying "fix forward"; `--release B` exit 0 printing `git tag v1.0.1-fork.2 B`; the script with `v1.0.1` → exit 4 (no second merge). "consecutive syncs": after "release", only `git worktree remove` (branch kept) → upstream tags `v1.0.2` → the script exits 0 on `sync/upstream-1.0.2`, BuildInfo `1.0.2-fork.1`. R2: Codex whole-merge review in the PR or merge notes; `git merge-base --is-ancestor v2.3.10 v2.3.10-fork.1` and `… v2.3.9-fork.5 v2.3.10-fork.1`; tag = the PR's merge commit; PR and tag runs green; `gh release view v2.3.10-fork.1 -R nlachica/buddy-android` has `buddy.apk`, not prerelease, Latest; fork.5's update_buddy shows the 2.3.10 note before and "Latest release: 2.3.10-fork.1 … available" after; on fork.5 before the sync, Settings → Check for updates shows the toast naming official Buddy 2.3.10; the feature table below. |
| 2. conflicts detected/resolved/green; unresolvable → stop, nothing published | Script "conflict": exit 3, lists the conflicting file and not BuildInfo, BuildInfo staged and resolved, merge in progress. "BuildInfo restructured": exit 3 listing BuildInfo. "abort-then-retry": exit 3 → the AGENTS.md stop commands → rerun → exit 3 with a fresh worktree. "in-progress": exit 3 → write a resolution → rerun → exit 5, that file and `MERGE_HEAD` unchanged, state printed. "main advanced": after exit 0, origin main gets a release commit (code bumped) → merging the sync branch into it conflicts on BuildInfo (GitHub would refuse the PR merge) → start over (stop commands, rerun) → code = new max+1; merged into origin main, `--release` exit 0. `--release` with the tag already on origin, pointing elsewhere → exit 1 "tag taken". All cases: origin refs and tags unchanged by the script. Gate (inspect): `build.yml` `apk` has `needs: helper-tests` (a failed needed job skips `apk`, so no publish step runs) and runs `testGithubDebugUnitTest` before "Publish GitHub release"; AGENTS.md requires all PR checks green, which include the reproducibility step, before merge and tag. Stop/resume prose: the Verifier reads AGENTS.md against the brief; exit-4 reads checked live against `v2.3.9-fork.4` (has `buddy.apk` → "released" branch). |
| 3. no upstream change → as today | `UpstreamNoteTest`: `upstreamNote` → `""` for `("2.3.10","2.3.10-fork.1")`, `("2.3.10","2.3.10-fork.3")`, `("2.3.9","2.3.9")`, `(null, …)`, `("", …)`, and `upstreamAhead` false for the same pairs (true for row 1's); `report` with `""`: up to date and update available (install=false) equal today's strings copied literally from `Tools.updateBuddy`; update available + install=true → `null` (today's card path). Script "nothing": exit 4, prints "none", no worktree or branch made; after a `v1.0.1-fork.1` tag in origin, prints it. Sheet: `ahead` false (not ahead, or upstream call failed) runs today's toast/sheet code unchanged (Verifier reads the diff). Phone after R2: update_buddy shows no upstream note; Settings → Check for updates says up to date. |
| 4. 2.3.10 in the fork, runs on the phone | R2: `update_buddy` after install reports Installed 2.3.10-fork.1, the per-chat Approvals line (upstream 2.3.10) shows, and the table below. |

Feature evidence for R2 (a hardware check that can't be done is recorded as INCOMPLETE in `verify.md`, not passed):

| Feature | Evidence |
|---|---|
| 001 audio device | Settings → Voice → Audio device: pick Speaker → the sample plays there; the voice bar's quick switch is present |
| 002 runner hang | `linux/test/run.sh` green in the tag run's `helper-tests` (dead-agent regression test) |
| 003/006 watch talk | watch APK from the same release via adb; pick a chat, speak → message in the phone chat; reply shown and spoken on the watch |
| 004 self-update | the R2 install itself (card → installer → new version, chats kept); `ApprovalGateTest`, `ApkStagingTest` green |
| 005 settings | `SettingsCoverageTest`, `SettingsFamiliesTest` green; one `buddy_settings` change approved on the card, one denied |
| 007 watch call/voice | from the watch, start a call, speak, hear Buddy, end it; with ChatGPT voice on, a watch reply in that voice |

Other script cases: upstream code higher than the fork's (30 vs 14 → 31). Local checks: `bash tools/upstream-sync-test.sh`,
`bash linux/test/run.sh`; Gradle/JUnit run in CI (`pipe code-loop --ci`), as the phone has no Android SDK.

## Risks
- **Clean textual merges can still break behavior** (2.3.10 touches `Tools`, `ChatHub`, `Guard`, `Sessions` beside
  features 004/007). CI compiles and runs the unit tests, Codex reviews the whole merge with the both-changed files
  called out, and features without tests (001 audio, 003/007 watch) get the R2 smoke checks.
- **An AI resolution that compiles but is wrong.** Mitigated by the whole-merge review, the confirm card listing each
  resolution, and the merge commit message recording them. First run: only BuildInfo (script) and `docs/GUIDE.md`
  (text: keep upstream's new lines and the fork's sentence) conflict.
- **Upstream restructures BuildInfo** → the script's line check reports BuildInfo as a conflict instead of guessing.
- **The note keeps showing while the release build runs** (fork's latest release is still old) → exit 4 and
  AGENTS.md's release-state reads say which: tag it, watch the build, fix a failed build, or fix the Latest flag.
- **`main` moves between PR checks and merge without a release** (no BuildInfo conflict) → the merged tree differs
  from the checked one; the tag run reruns helper and unit tests before publishing, the reproducibility step only
  after (upstream's order; it concerns the F-Droid APK, which the fork doesn't ship). One user: unlikely.
- **A release lands on main during a sync** → before the PR merge: BuildInfo conflict blocks the PR; start over
  costs redoing the resolutions (recorded in the old merge message). After it: `--release` sees the newer tag or
  main's higher code and says "already tagged" or "fix forward"; the upstream merge is never redone.
- **Settings → Check for updates waits for a second GitHub call** (same host and timeouts as the fork check); a
  failure only drops the line.
- **The release is public once tagged** (public repo) → covered by the confirm card before any push.
- **Unauthenticated GitHub API (60/h)**: one extra call per update_buddy; a failure only drops the note.
- **gh defaults to the parent repo** with an `upstream` remote (005 learning) → every `gh` call names `-R`.
- **`skip_upstream` could be used to dodge the sync** → only after the user chose it; the card still names the version.

## Open questions
None blocking. Defaults Nicholas may override (decisions.md): the daily notification stays fork-only (the sheet it
opens carries the official-Buddy line); R1 ships 008 alone first (one extra Update tap); one confirm card per sync
covers push, PR and publish-if-green.
