# Plan review: 008-upstream-in-update
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Upstream detection does not cover the update entry points promised by the brief - `brief.md:13` includes "update_buddy / Buddy's update check", but `plan.md:67-71` adds the upstream call only to the up-to-date and install=false returns. When an older Buddy calls install=true and a newer fork release exists, `Tools.java:2165-2182` proceeds straight to approval/install without checking upstream; the task then ends on restart. The plan also declares the daily check and update sheet non-goals (`plan.md:14-16`) without a corresponding user clarification, although those are existing update paths (`Updates.java:138-153,199-206`). Thus updating can still omit a newer official release entirely. -> Check upstream before choosing the tool's install branch and define the handoff to the Linux agent when a sync is needed, retaining the existing approval card and installer once the resulting fork APK is ready. Give the sheet/notification an upstream-aware handoff as well, or obtain an explicit brief clarification narrowing those entry points. Cover install=true with an available-but-upstream-behind fork release, not just the pure note formatter; make the handoff state which action proceeds instead of simultaneously asking to sync and install the old fork.

F2 [major] Preservation of all fork features has neither the required merge review nor sufficient verification - `brief.md:26` requires features 001-007 to survive. `plan.md:90-91,120-122` reviews only hand-resolved code conflicts, so the first merge receives no semantic code review despite upstream changes in shared Tools/ChatHub/Guard code (also acknowledged at `plan.md:117-119`). The referenced `/root/.buddy/work/pipeline-playbook/pipeline/upstream-sync.md`, step 3, reviews the whole merge relative to upstream, not just textual conflicts. Ancestry checks at `plan.md:107` prove inclusion of commits, not retention of behavior. The proposed smoke test (`plan.md:110`) omits 001 audio routing and makes the watch check optional; "watch talks to the phone" also does not establish 007 live calls or reply-voice fallback (`docs/features/007-watch-live-voice/brief.md:27-28`). -> Retain the existing whole-merge cross-family review, with attention to automatically merged shared code and the fork's retained behavior. Add a compact feature-to-evidence checklist using existing tests plus targeted phone/watch smoke checks, including audio routing, settings changes/approval, and watch call/reply behavior. Record unavailable hardware checks as incomplete rather than claiming preservation. Since R2 is scheduled after verify (`plan.md:99`), explicitly keep final verification open until its required release/install and behavior evidence is recorded.

F3 [major] The proposed publication gate can publish while CI is red - `plan.md:108` treats tests before the publish step as the release gate, but `.github/workflows/build.yml:15-24` runs helper-tests independently of apk (no dependency), and publication at lines 88-105 precedes the reproducibility check at lines 107-122. The new sync-script tests are assigned to helper-tests (`plan.md:83-84`). A tag run can therefore publish with a failing sync/helper test or before a failing reproducibility check, contrary to `brief.md:27`. This is explicitly left in place at `plan.md:130`; green PR checks do not establish that the subsequently published tag run is green. -> Make publication depend on all required test/build checks for the exact release commit: at minimum gate apk/publication on helper-tests and run reproducibility before publishing, or use a final publish job that depends on the successful check jobs. Add a verification case or inspectable gate proving that a failed helper test and a failed reproducibility check prevent release creation/upload.

F4 [major] Tagging the latest main can release a different commit or an unusable version - `plan.md:94-95` fetches main after merging the PR and tags whatever `origin/main` then points to. The plan acknowledges movement but relies on rerunning tests (`plan.md:126`); tests cannot establish that this is the reviewed merge, that BuildInfo still matches the tag, or that its code exceeds an intervening fork release. For example, another release can consume the script's precomputed max+1 while this sync is under review; the APK can build successfully but fail the installed-version gate at `Updates.java:110-114`. Existing `AGENTS.md` step 3 already says "Tag the commit you mean." -> Record the actual merged PR commit SHA, validate its upstream ancestry and BuildInfo version/code against the intended tag and current fork release, and tag that SHA. If intervening main/release changes invalidate the version or checked tree, refresh the sync/version and rerun the applicable checks before tagging. Exercise this with a main/version advance between preparation and publication.

F5 [major] Failed syncs and failed tag builds have no usable recovery path - `plan.md:76` refuses an existing fixed worktree or sync branch, while the failure policy (`plan.md:91-95`) aborts a merge or leaves the PR open and only removes the worktree on success. `git merge --abort` leaves both worktree and branch, so another update attempt exits 1 rather than resuming the stopped sync. Separately, exit 4 treats any matching remote tag as "wait for the tag build" (`plan.md:79-80,124-125`), even if that build has failed and no APK will appear. These are normal outcomes of the specified conflict/CI stop paths. -> Document a bounded resume/retry procedure that identifies the existing sync branch/worktree and PR, preserves uncommitted resolutions, and distinguishes an aborted preparation from an active merge. For an already-merged upstream tag, inspect the matching release/build state and report or recover a failed build instead of waiting indefinitely; tag existence alone is insufficient. Add local tests for abort-then-retry and existing in-progress work, and a release-state check for a tag with a failed build and no APK.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted in part - install=true: the upstream check now runs before the tool picks a branch (Design 1, Steps 1-2).
With upstream ahead and a fork update available, update_buddy never shows the card; it gives one recommendation
(bring X in first, its release contains Y too) and names the single alternative (`install=true skip_upstream=true`,
when the user wants Y now or the sync stopped). That alternative keeps 004's self-update route usable when a sync
can't finish. The handoff ends at `update_buddy install=true`, where the card and installer are unchanged. All branch
texts come from a pure `Updates.report(...)`, so install=true with an available fork release and upstream ahead is
unit-tested (Test plan rows 1 and 3). Sheet/notification: rejected as a code change for now and raised as Open
question Q1 for Nicholas. The ask names "the update thing I've set up on this fork" (004's update_buddy + AGENTS.md
route); the sheet and notification can't run a merge and would only point to a chat, at the cost of new strings in 22
locales (research: strings.xml is a sync hot spot). Default: fork-only. If he wants it, it adds one step.

F2: accepted - Codex now reviews the whole merge every sync (playbook step 3), not just hand-resolved code:
`git diff refs/remotes/upstream/tags/<tag> HEAD` plus the merge notes, checked against each fork feature's done-when.
The script lists the auto-merged files both sides changed so the review looks there first (Steps 3, 5; superseding
decision appended). Added a feature-to-evidence table for R2 covering 001 audio device, 002, 003/006 watch talk, 004,
005 settings approve/deny, and 007 watch call + phone voice. A hardware check that can't be done is recorded as
INCOMPLETE. Verify keeps done-when 1 and 4 PENDING, with the verdict open, until R2's evidence is recorded (Rollout).

F3: accepted in part - `apk` gets `needs: helper-tests`, so a red helper or sync-script test means no APK and no
publish on a tag (Step 4; inspectable gate in Test plan row 2). Moving the reproducibility step before publish is
rejected. Upstream put it after publish on purpose (`build.yml:107-108`), the brief says prefer upstream's mechanism
and keep fork lines minimal, and the step checks the F-Droid APK, which the fork doesn't ship. It is gated another way:
it runs in every PR run, AGENTS.md requires all PR checks green before merge, and F4 tags that merged commit. Residual
case (main moves without a conflict) is in Risks. No deliberate red-release experiment: it would cost a public
release for a property GitHub's `needs` semantics already give.

F4: accepted - tag the PR's merge commit (`gh pr view --json mergeCommit`), not `origin/main`. New
`tools/upstream-sync.sh --release <sha>` refuses unless the upstream tag is an ancestor, VERSION is `<ver>-fork.<n>`,
VERSION_CODE is above both the first parent's (main before the merge, which holds any release made in between) and
upstream's, and the tag is free on origin. It prints the tag command and never pushes. An intervening release also
conflicts on BuildInfo's lines, so GitHub refuses the PR merge. Recovery is a documented start over, which recomputes
max+1 from the new main. Script cases "release" and "main advanced" exercise it (Test plan row 2).

F5: accepted in part - the stop path now also removes the worktree and the local branch (nothing was pushed), so a
retry starts clean (case "abort-then-retry"). An existing worktree/branch now gives exit 5: nothing is changed, and the
script prints whether a merge is in progress, the unmerged files, and whether the branch is on origin. AGENTS.md maps
each state to resume (keep resolving, continue at review, continue at the PR) or start over (case "in-progress":
resolutions and MERGE_HEAD kept). Exit 4 no longer claims "wait for the tag build". It prints the fork tags on origin,
and AGENTS.md's "already merged" step reads the release and run state: APK → install (or fix the Latest flag);
running → watch; failed → show the failed step, rerun once if it's infrastructure, else fix forward as `-fork.2`.
Rejected: a `gh` stub harness to unit-test those two read-only `gh` calls (YAGNI). The Verifier checks the "released"
branch live on `v2.3.9-fork.4` and reads the rest against the brief.

## Round 2 - Plan Reviewer (gpt-6-astra)

F1 [major] The update-entry-point scope remains unresolved - The install=true part of round 1 F1 is addressed, but `plan.md:16-17,201-206` still excludes the daily check and Settings check by default and defers Nicholas's answer until Verify. `brief.md:13` still includes "Buddy's update check", and its Clarifications section is empty (`brief.md:31-32`). Existing `Updates.java:199-219` can therefore still report up to date or offer an older fork without detecting newer upstream changes. The inability of a sheet to perform a merge explains using a chat handoff, not omitting detection; the translation/merge-cost argument does not establish authorization to narrow the brief. -> Have the orchestrator settle Q1 and record Nicholas's scope clarification before treating this plan as approved, or specify the upstream-aware handoff and verification for those entry points. This is the unresolved portion of F1, not a request to put git or conflict resolution in the UI.

F2 [major] The release guard checks historical parents, not the release it must supersede - `plan.md:105-109` checks the candidate against its first parent and upstream, plus availability of its exact tag. These checks still pass if sync merge M has code 213, work pauses before tagging M, and main then publishes `2.3.10-fork.2` with code 214: M's parent still has code 212 and `v2.3.10-fork.1` is still free. The protection described at `plan.md:134-136,193-194` only catches a release before the PR merge; there is no BuildInfo merge conflict after M is already merged. This leaves round 1 F4's requested current-release validation incomplete, and code 213 cannot update a phone on 214 (`app/src/main/java/app/buddy/assistant/Updates.java:110-114`). The generic "start over" recovery is also unsuitable after a merged candidate is rejected: rerunning the script sees upstream already in origin/main and exits 4 (`plan.md:95-96`), whose no-tag path sends the agent back to the same merge SHA (`plan.md:140-141`). -> Immediately before tagging, refresh and compare with current fork release/version state as well as the candidate's parents. If a newer published release already contains this upstream tag, use it; if a merged candidate needs a version correction, use a tested fix-forward release from current main rather than retrying the upstream merge. Add the post-merge/pre-tag intervening-release case and the merged-but-rejected candidate case to the existing local release tests.

F3 [minor] Successful cleanup leaves a branch that blocks the next upstream version - `plan.md:97` returns exit 5 for any local `sync/upstream-*` branch, but the success path at `plan.md:139` removes only the worktree. Git retains its branch when removing a worktree ([git-worktree documentation](https://git-scm.com/docs/git-worktree)). Thus the next upstream version enters recovery for the previous successful sync; the on-origin resume command at `plan.md:146-148` uses `gh pr list` without a state filter, which only lists open PRs and will omit that merged PR ([GitHub CLI documentation](https://cli.github.com/manual/gh_pr_list)). -> After success, remove the completed local sync branch once its inclusion in origin/main is verified, and distinguish merged/closed PRs when inspecting leftovers. Add a consecutive-successful-syncs case proving the second version can start without a restart confirmation for the completed first version.

VERDICT: REVISE

### Planner response (round 2)

F1: accepted - Settings → Check for updates (the brief's "Buddy's update check", and also the sheet the daily
notification opens) now checks official Buddy too, so Q1 is gone (Goal, Design 1, Step 2). After its fork check,
`showSheet` calls `Updates.upstreamVersion()`. When official Buddy is ahead of the fork's latest release: if the fork
is up to date, a toast "Official Buddy X isn't in your fork yet: ask Buddy in a chat to update" replaces "up to date";
if a fork update is available, the same line goes under the sheet's subtitle and Update is unchanged (the user's own
tap, like `skip_upstream`). If the upstream call fails or official Buddy isn't ahead, today's toast and sheet show.
One English string. My round-1 locale cost was wrong: 001/003/005 added English-only strings and CI stays green. The
daily notification itself stays fork-only. It fires only for a fork release and opens that sheet. An upstream-only
notification would be a background announcement, and the verbatim ask's trigger is "when updating". This is listed
as a default Nicholas may override, not a blocker. Proof: `upstreamAhead` unit cases; R2 checks the toast on fork.5
before the sync and "up to date" after (Test plan rows 1 and 3).

F2: accepted - `--release <sha>` now fetches origin/main and checks the current state as well as the parents (Step 3).
(1) If a `v*` tag on origin points at `<sha>` or a commit containing it, it refuses with "already tagged as vX:
AGENTS.md 'already merged' with vX" (use that release, or recover its build). (2) If origin/main's VERSION_CODE is
above the candidate's, or the candidate's version isn't new against its parent, it refuses with "fix forward":
release current main as the next `-fork.<n>` through "Updating Buddy on the phone", and check that commit with
`--release` first. The fix-forward commit passes the same checks. AGENTS.md: once the sync PR is merged, the upstream
merge is never redone. "Start over" applies only when GitHub refuses the unmerged PR. Exit 4's no-tag path goes to
Tag, whose refusal names one of these ways out, so it can't loop back to the same SHA. New script cases: "released
meanwhile" (tagged bump after the merge → refuses, naming the tag) and "main moved past" (untagged bump → refuses with
fix forward; `--release` on the bump passes; rerunning the script gives exit 4, not a second merge). The old "main
advanced" case now ends in start over before any merge (Test plan rows 1 and 2).

F3: accepted - exit 5 now ignores `sync/upstream-*` branches that origin/main already contains, and prints whether
origin/main contains a leftover worktree's branch. The success step removes the worktree, then runs `git branch -D`
once `git merge-base --is-ancestor` confirms the branch is in origin/main. Exit-5 resume reads the PR with
`gh pr list … --state all --json number,state`: OPEN → the PR step, MERGED → Tag, CLOSED → start over. A leftover
worktree whose branch is in origin/main means that sync finished: clean up and rerun. New script case "consecutive
syncs": 1.0.1 merged, worktree removed, branch kept → 1.0.2 exits 0 on `sync/upstream-1.0.2` (Test plan row 1).
