
## Feature work

Features are built with a multi-agent pipeline. Each feature has a folder `docs/features/<NNN-slug>/`
(brief, research, plan, reviews, decisions, verification): read it before touching that feature's code.
Decisions that outlive a feature are in `docs/decisions/`. Pipeline rules: the `pipeline-playbook` vault
(`nlachica/pipeline-playbook`, on the phone at `/root/.buddy/work/pipeline-playbook/AGENTS.md`).

## Updating Buddy on the phone

To ship a change to Buddy's own code (github flavor only), from a chat with Linux tools:

1. Change the code in `/root/.buddy/work/buddy-fork` (or a fresh `gh repo clone nlachica/buddy-android`), normally
   through the feature pipeline and a merged PR.
2. In `BuildInfo.java` set `VERSION` to `<upstream>-fork.<n>` (e.g. `2.3.7-fork.2`) and `VERSION_CODE` to
   max(fork, upstream) + 1. Android only installs a higher code. `n` restarts at 1 on a new upstream version (the sync
   script sets it).
3. Commit, push, then `git tag v<VERSION>` and `git push origin v<VERSION>`. The `v*` tag makes CI build, sign (with the
   fork's key) and publish `buddy.apk` as a GitHub release. Watch it with `gh run watch`. Tag the commit you mean,
   and never mark a release as a prerelease: `releases/latest` skips those.
4. Call `update_buddy install=true`. Buddy shows the user an approval card, downloads the release, and opens Android's
   installer, where the user taps Update. Buddy restarts on the new version; chats and settings stay.

`install_apk` refuses Buddy's own APK on purpose: `update_buddy` is the one route, so it always has the card.
Buddy's settings are read and changed with the `buddy_settings` tool (also one approval card per change), including MCP server
and API keys, read from a file in Buddy's Linux and never shown.

## Bringing in official Buddy

When: `update_buddy` says official Buddy (`ghorbelhamdi/buddy-android`) has a release the fork lacks, or the user asks.
Where: a chat with Linux tools (if this chat has none, ask the user to turn them on or open one). Every `gh` call names
`-R nlachica/buddy-android` (with an `upstream` remote, `gh` defaults to the parent repo). Work in
`/root/.buddy/work/buddy-fork`; the sync itself runs in the worktree `../buddy-fork-sync`, so a dirty checkout is fine.

1. **Merge.** `tools/upstream-sync.sh` (latest release; or pass a tag). It fetches only that tag, merges it into branch
   `sync/upstream-<ver>` off `origin/main`, writes BuildInfo (`<ver>-fork.1`, code max+1) and never pushes. Exit 0:
   merged, go to Review. 3: conflicts left, go to Resolve. 4: already merged, see "Already merged". 5: a sync is under
   way, see "Resume". 1: error, tell the user.
2. **Resolve** (in the worktree). Prefer upstream's mechanism where both sides fixed the same thing; keep features
   001-007 and their tests; keep the fork lines (`Updates` REPO/API on nlachica and `UPSTREAM_API`, `build.yml`'s
   `helper-tests` step, `needs` and F-Droid-check lines, the fork notes in README/AGENTS/GUIDE; in docs keep upstream's
   text plus the fork's sentence). Commit with each conflict and its resolution in the message, and a line in an
   affected feature's `decisions.md`.
3. **Review.** Codex reviews the whole merge, `git diff refs/remotes/upstream/tags/<tag> HEAD` (the fork's lines on
   upstream's base), plus the script's both-changed list and the merge message, against each fork feature's done-when
   (`docs/features/00*/brief.md`). Patch only real gaps, minimally, each recorded.
4. **Stop rule.** A conflict needing feature-sized work (upstream removed or rewrote what a fork feature needs), or a
   review gap that is feature-sized: `git merge --abort` if a merge is in progress, `git worktree remove --force <dir>`,
   `git branch -D sync/upstream-<ver>` (nothing was pushed), tell the user which files and why. Nothing is published.
   CI still red after 2 fix attempts: leave the PR open, no tag, tell the user.
5. **Confirm, push, PR.** One `confirm` card: versions, each conflict and its resolution, the review result. It
   authorizes push, PR and publish-if-green. Then push the branch, `gh pr create -R nlachica/buddy-android --base main`,
   `gh pr checks --watch` (every check must pass, the `apk` job's reproducibility step included), `gh pr merge --merge`.
6. **Tag.** `sha=$(gh pr view <n> -R nlachica/buddy-android --json mergeCommit -q .mergeCommit.oid)`, `git fetch origin`,
   `tools/upstream-sync.sh --release "$sha"`, run the tag command it prints, `gh run watch`. Never a prerelease. If
   `--release` refuses, do the way out it prints (already tagged: "Already merged" with that tag; fix forward: a normal
   fork release from current main, checked with `--release` too). Once the sync PR is merged, never redo the upstream
   merge. Only if GitHub refuses the PR merge itself (main got a release first, both sides change BuildInfo): start
   over. With the user's confirm close the PR (`gh pr close -d`), clean up as in the stop rule, rerun the script,
   reapply the resolutions from the old merge message.
7. **Install.** `update_buddy install=true` (the user taps Update). After the restart, smoke check: a chat answers,
   `buddy_settings` reads a setting, Settings → Voice → Audio device plays the sample, and if the watch is at hand a
   watch talk and a watch call. Then clean up: `git worktree remove <dir>`, and once `git merge-base --is-ancestor
   sync/upstream-<ver> origin/main` holds, `git branch -D sync/upstream-<ver>`; list `feat/*` branches that are behind
   main (not touched).

**Already merged** (exit 4). No fork tag for `<ver>`: find the merged sync PR (`gh pr list -R nlachica/buddy-android -H
sync/upstream-<ver> --state merged`) and go to Tag. A tag: `gh release view <tag> -R nlachica/buddy-android --json
assets,isPrerelease`. `buddy.apk` there: `update_buddy` (if its note still shows, the release isn't Latest:
`gh release edit <tag> -R nlachica/buddy-android --prerelease=false --latest`). No APK: `gh run list -R
nlachica/buddy-android --branch <tag> -L 1`; running: `gh run watch`; failed: show the user the failed step (`gh run
view <id> --log-failed | tail -40`), `gh run rerun <id> --failed` once if it is infrastructure, else fix forward (next
`-fork.<n>` via "Updating Buddy on the phone", checked with `--release`). Never move a tag.

**Resume** (exit 5; the script printed the state). origin/main contains the branch: that sync finished, clean up as in
Install, rerun. Merge in progress: keep resolving in that worktree (its resolutions are kept). Committed, not pushed:
continue at Review. On origin: `gh pr list -R nlachica/buddy-android -H <branch> --state all --json number,state`: OPEN,
continue at the PR; MERGED, Tag; CLOSED, start over. For an older upstream version, or if the user wants to start over:
start over as in Tag.
