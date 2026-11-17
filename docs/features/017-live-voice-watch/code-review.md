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
