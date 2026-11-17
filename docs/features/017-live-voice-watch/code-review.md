# Code review: 017-live-voice-watch
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

Reviewed `git diff main...HEAD` at `3a36ad8`, the brief, plan, decisions, touched files and connected phone/watch playback code. There were no earlier code-review rounds. The implementation includes the orchestrator's separate 1500 ms failure tail; that accepted plan decision is not reopened here.

F1 [minor] Resumed held speech inherits an obsolete end marker - `app/src/main/java/app/buddy/assistant/LiveWatch.java:289-295` sets `heldEnded` when buffered speech ends, but never clears it when more audible blocks arrive while the previous reading still drains. Concrete sequence: reading A drains slowly; utterance B is held and ends; utterance C starts before A answers. When A answers, `openNext()` at lines 165-170 sends B and the start of C, then immediately closes that reading because B's end marker is still set. The remaining part of C waits for another playback acknowledgement and channel/prebuffer, creating an avoidable break within ongoing speech. A temporary probe against the compiled production `LiveWatch.Call` reproduced both the premature close and the next speech block being held instead of entering the current reading. The existing tests cover a held utterance that ends and one that continues, but not speech resuming after a held end. -> Clear `heldEnded` when appending new kept audio to `held`, so it describes the end of the current buffered audio. Add the A/B/C regression case; no new queue or abstraction is needed.

F2 [blocker] Required CI has not run because repository Actions are disabled - `docs/features/017-live-voice-watch/brief.md:22` requires green CI, and `.github/workflows/build.yml:63` supplies the real Gradle unit-test and GitHub/F-Droid/Wear release builds. The orchestrator reports HTTP 422, `Actions has been disabled for this repository`, when dispatching workflow 418915336. This is an infrastructure blocker, not evidence of a failing source build, but the required gate is still unmet. -> The orchestrator should enable Actions for the repository and rerun the workflow on the reviewed branch head, then record a green result including the existing F-Droid isolation and reproducibility checks. Local stub-based checks cannot replace that run.

Validation: reran the existing `/tmp/buddy-build-017/check.sh` for both flavors: GitHub **171 tests passed**, F-Droid **147 tests passed**, including routing, LiveWatch, ReadQueue, ReadStream and PcmOut tests as applicable. This compiles source copies with javac 17, an API-35 Android jar, Wear stubs and an existing API-36 compatibility substitution; it is not the Gradle/APK build. The temporary held-speech probe also ran successfully and reproduced F1. No additional security/data or YAGNI findings. No application code was modified.

Not checked: real WebView/audio routing, phone/watch playback and plan device checks D1-D8 (including D2b); release APK builds, lint and CI. `verify.md` still has no device evidence. Those remain verification work under the plan's post-install procedure. `git diff --check main...HEAD` reported only trailing whitespace in the existing empty `verify.md` verdict placeholder; omitted as a style-only nit.

VERDICT: REVISE

### Coder response (round 1)

F1: fixed in 78e8f97. `LiveWatch.Call.pcm` clears `heldEnded` when it appends audible blocks to `held`, so the marker always describes the end of the audio currently held. Regression test `speechThatResumesAfterAHeldEndIsNotClosedByTheOldEnd` (A drains, B held and ended, C starts before A answers): it fails without the line and passes with it. No new queue or abstraction.

F2: not a code change. Actions are disabled for the repo (HTTP 422), which is for the orchestrator to enable. Then rerun `build.yml` on the new branch head and record the result, F-Droid isolation and reproducibility steps included. Until that run, the CI-only steps (Gradle build, lint, APKs) stay unverified.

Checks rerun with `/tmp/buddy-build-017/check.sh` (javac/JUnit over the real sources, not Gradle): github 172 tests OK, fdroid 148 OK.

## Round 2 - Code Reviewer (gpt-6-astra)

Reviewed `git diff main...HEAD` at `9b47578`, the brief, plan, decisions, round 1 and its coder response, the touched files and connected routing/playback code. Scope remains one phone and its paired watch. The accepted failure-tail decision is unchanged.

Round 1 F1 is resolved: `app/src/main/java/app/buddy/assistant/LiveWatch.java:291` clears the obsolete held end marker when more audio arrives. `speechThatResumesAfterAHeldEndIsNotClosedByTheOldEnd` covers A draining, B ending while held, and C starting before A answers; it checks that C continues through the open reading. The fix is minimal and the regression passes.

F2 [blocker] Required CI remains blocked by disabled repository Actions - `docs/features/017-live-voice-watch/brief.md:22` requires green CI; `.github/workflows/build.yml:63` runs the real Gradle tests and release builds. The orchestrator again reports HTTP 422, `Actions has been disabled for this repository`, and the coder response confirms there is no successful CI result. This leaves the required build gate unmet; it does not establish a source compilation failure. -> The orchestrator must enable repository Actions, rerun `build.yml` on the reviewed branch head, and record a green run including GitHub/F-Droid/Wear builds, F-Droid Google-code isolation and reproducibility checks. No source change is requested for this infrastructure finding.

Validation: reran `/tmp/buddy-build-017/check.sh` for both flavors: GitHub **172 tests passed**, F-Droid **148 tests passed**. These are javac/JUnit checks over source copies with the existing API-35 Android jar, Wear stubs and API-36 compatibility substitution, not Gradle/APK builds. Extracted the current `CodexLive.PAGE` with `CodexSpeech.TAP_JS`; `node --check` passed. No new correctness, security/data, YAGNI or convention findings. Only this review document was modified in the repository.

Not checked: real WebView/audio behavior, device checks D1-D8 (including D2b), Gradle release builds, lint and CI. `verify.md` still has no device evidence; those checks remain with the Verifier under the plan's post-install procedure. `git diff --check main...HEAD` reports only the existing empty `verify.md` verdict's trailing whitespace, omitted as a style-only nit.

VERDICT: REVISE

### Coder response (round 2)

F2: not a code change, and not mine to close. I don't push (pipeline rule), and `build.yml` only runs on a pushed branch. As of this
run `gh api repos/nlachica/buddy-android/actions/permissions` returns `enabled: true`, so the 422 should be gone. The orchestrator can
push `feat/017-live-voice-watch` (or dispatch `build.yml` on it) and record the result, F-Droid isolation and reproducibility steps
included. Until then the Gradle build, lint, APKs and CI-only steps are unverified. Round 1 F1 was confirmed resolved by the reviewer.

No source change this round. Checks rerun with `/tmp/buddy-build-017/check.sh` (javac/JUnit over the real sources, not Gradle):
172 tests OK (github flavor; the fdroid pass was not rerun, no source changed since round 1's 148 OK).

## Round 3 - Code Reviewer (gpt-6-astra)

Reviewed `git diff main...HEAD` at `4f5d93c`, the brief, plan, decisions, earlier review rounds and coder responses, touched source/tests, and connected phone/watch playback code. Scope remains one phone and its paired watch. Since round 2's reviewed head (`9b47578`), only this review document changed; there are no further source changes. Round 1 F1 remains resolved by the held-speech end-marker fix and its regression test. The accepted failure-tail decision is not reopened. No new correctness, security/data, YAGNI or convention findings.

F2 [blocker] Required branch CI has still not run; Actions is now enabled - `docs/features/017-live-voice-watch/brief.md:22` requires green CI, and `.github/workflows/build.yml:63` supplies the real Gradle unit tests and GitHub/F-Droid/Wear release builds. Read-only queries in this round confirm `gh api repos/nlachica/buddy-android/actions/permissions` returns `enabled: true` and `allowed_actions: all`, but `gh run list -R nlachica/buddy-android --branch feat/017-live-voice-watch -L 6 --json databaseId,headSha,status,conclusion,url,workflowName,createdAt` returns `[]`. The earlier disabled-Actions error is historical; the remaining blocker is missing CI evidence, not a demonstrated source build failure. -> The orchestrator should push the reviewed head if needed, then dispatch `build.yml` on this branch (a feature-branch push alone does not trigger this workflow), and record a green run at that head including the release builds, F-Droid Google-code isolation and reproducibility checks. No source change or another coder round is needed for this finding.

Validation: reran `/tmp/buddy-build-017/check.sh` for both flavors: GitHub **172 tests passed**, F-Droid **148 tests passed**. The existing harness uses javac/JUnit over source copies, an API-35 Android jar, Wear stubs and an API-36 compatibility substitution; it does not establish a Gradle/APK build. Extracted the current `CodexLive.PAGE` with `CodexSpeech.TAP_JS`; `node --check` passed. `git diff --check main...HEAD` reports only trailing whitespace in the existing empty `verify.md` verdict placeholder, omitted as a style-only nit. Only this review document was modified in the repository.

Not checked: real WebView/audio behavior, device checks D1-D8 (including D2b), Gradle release builds, lint and CI. `verify.md` still has no device evidence; those checks remain with the Verifier under the plan's post-install procedure. This is the third review round; the remaining infrastructure gate belongs with the orchestrator under the playbook's bounded-review rule.

VERDICT: REVISE

### Coder response (round 3)

F2: not a code change, for the orchestrator. I don't push, and `build.yml` runs only when dispatched on a pushed branch. `gh run list` for
`feat/017-live-voice-watch` still returns `[]`. Push the head, dispatch `build.yml` on it and record the result (release builds, F-Droid
isolation, reproducibility). Until then the Gradle build, lint and APKs are CI-only and unverified. Round 1 F1 stays fixed in 78e8f97
(`heldEnded` cleared at `LiveWatch.java:291`, with its regression test). No source change; no checks rerun.

## Round 4 - Code Reviewer (gpt-6-astra)

Reviewed `git diff main...HEAD` at `80d042b`, the brief, plan, decisions, all earlier review rounds and coder responses, touched source/tests and connected routing/playback code. Since round 3's reviewed head (`4f5d93c`), only this review document changed. Round 1 F1 remains resolved by the held-audio end-marker fix and its regression test. The accepted failure-tail decision is unchanged. No new correctness, security/data, YAGNI or convention findings for the intended one-phone/paired-watch use.

F2 [blocker] Required branch CI still has no run - `docs/features/017-live-voice-watch/brief.md:22` requires green CI; `.github/workflows/build.yml:63` runs the Gradle unit tests and GitHub/F-Droid/Wear release builds. Read-only queries in this round again show repository Actions enabled (`enabled: true`, `allowed_actions: all`), while `gh run list -R nlachica/buddy-android --branch feat/017-live-voice-watch -L 6 --json databaseId,headSha,status,conclusion,url,workflowName,createdAt` returns `[]`. The supplied HTTP 422 is historical; the current blocker is missing CI evidence, not a demonstrated source build failure. -> The orchestrator should push the reviewed head if needed, dispatch `build.yml` on this branch, and record a green run including the release builds, F-Droid Google-code isolation and reproducibility checks. A feature-branch push alone does not trigger this workflow. No source fix or further coder round is needed for F2.

Validation: reran `/tmp/buddy-build-017/check.sh` for both flavors: GitHub **172 tests passed**, F-Droid **148 tests passed**, including the held-speech regression and the routing, playback acknowledgement and failure-tail cases. This harness compiles source copies with javac/JUnit, an API-35 Android jar, Wear stubs and an existing API-36 compatibility substitution; it does not establish a Gradle/APK build. The source and page JS are unchanged since round 3's syntax check, so that check was not repeated. `git diff --check main...HEAD` reports only the existing empty `verify.md` verdict's trailing whitespace, omitted as a style-only nit. Only this review document was modified in the repository.

Not checked: real WebView/audio behavior, device checks D1-D8 (including D2b), Gradle release builds, lint and CI. `verify.md` still contains no device evidence; those checks remain with the Verifier under the plan's post-install procedure. The normal three-round review limit has already been exceeded; the remaining CI gate needs orchestrator action rather than another unchanged code-review loop.

VERDICT: REVISE
