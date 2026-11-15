
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
   max(fork, upstream) + 1. Android only installs a higher code.
3. Commit, push, then `git tag v<VERSION>` and `git push origin v<VERSION>`. The `v*` tag makes CI build, sign (with the
   fork's key) and publish `buddy.apk` as a GitHub release. Watch it with `gh run watch`. Tag the commit you mean,
   and never mark a release as a prerelease: `releases/latest` skips those.
4. Call `update_buddy install=true`. Buddy shows the user an approval card, downloads the release, and opens Android's
   installer, where the user taps Update. Buddy restarts on the new version; chats and settings stay.

`install_apk` refuses Buddy's own APK on purpose: `update_buddy` is the one route, so it always has the card.
Buddy's settings are read and changed with the `buddy_settings` tool (also one approval card per change).
