# Code review: 004-self-modify
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

F1 [major] Capture the caller's chat before dispatching the listing to the main thread - `app/src/main/java/app/buddy/assistant/SettingsTool.java:741`: `call()` posts `listing()` to the main thread at line 679, but `listing()` reads `McpServer.CALLER_CHAT` there. This is a plain `ThreadLocal` (`McpServer.java:232`), set on the request thread (`McpServer.java:129`); `BuddyService.runOnMain()` only posts the runnable (`BuddyService.java:420`). Consequently `here` is null and no entry is ever marked `(this chat)`. The agent cannot reliably choose the current chat's ID to change its Linux tools, background mode or schedule, especially with duplicate/default chat titles. This breaks the plan's explicit caller marker and the intended "this chat's Linux tools" flow. -> Read the caller ID in `call()` on the request thread and pass it into `listing(here)`. Add a regression check that uses a distinct executor thread and verifies the caller is identified, rather than testing only with `Runnable::run`.

F2 [minor] Clean up the staged update when installer launch fails - `app/src/main/java/app/buddy/assistant/Updates.java:122`: after a successful download/package check, an exception from `startActivity` leaves this attempt's APK in `cache/apk/`; only the download's IOException and explicit refusal branches delete it. Unlike `Tools.installApk`, this route has no enclosing `finally` for an unsuccessful handoff. A disabled/unavailable installer or a launch-time permission failure therefore retains a full APK until a later staging operation happens after the age cutoff. This misses plan B.4's cleanup on every path that does not open the installer. -> Use the same `opened` flag and `finally` cleanup as `installApk`, setting it immediately after the installer launch succeeds; retain the staged file after a successful handoff.

Validation: reviewed `git diff main...HEAD`, the touched source/tests, the feature brief/plan/decisions and the relevant existing callers/stores. Re-ran `/tmp/buddy-build-004/check.sh github`: resources and Java compiled, all 18 JUnit tests passed. Re-ran the same script for `fdroid`: resources and Java compiled. These are the local aapt2/javac checks documented by the Coder, not Gradle/CI, full APK packaging or device tests. The tests cover approval rejection, stale writes, rule validation, skill confinement/import locking, MCP address masking and per-attempt APK staging; they do not exercise the real cross-thread listing. The documented CI signing/release/reproducibility checks and device tests (including actual card-tap blocking and update/restart with data intact) remain for verification after the phone switch. No additional blocker/major security or scope finding identified.

VERDICT: REVISE

### Coder response (round 1)

F1: fixed in the round-1 fix commit. `call()` now reads `McpServer.CALLER_CHAT` on the request thread and passes it to `listing(here)`. The chats block is a static `chatLines` so a JVM test can check it: `listingMarksTheCallersChatAcrossThreads` captures the caller on one thread, runs the lines on another executor thread (where the ThreadLocal is null) and checks only the caller's chat is marked, with two same-titled chats. The test covers the capture-then-pass pattern and `chatLines`; `call()` itself needs a `BuddyService`, so the one-line capture in it is only compile-checked.
F2: fixed in the round-1 fix commit. `Updates.install` wraps the installer launch in the same `opened` flag and `finally` delete as `Tools.installApk`; a launched file is kept. Not unit-tested (needs `startActivity`).

Tests: `/tmp/buddy-build-004/check.sh github` compiled and 19 JUnit tests passed; `fdroid` compiled.

## Round 2 - Code Reviewer (gpt-6-astra)

No new findings. Round 1 F1 and F2 are closed:

- F1: `SettingsTool.call()` captures `McpServer.CALLER_CHAT` on the request thread and passes it through `listing(here)` to `chatLines`. The new regression test uses a separate executor thread and duplicate chat titles, and verifies that only the caller's ID receives the marker. As the Coder notes, the test exercises the capture/pass pattern and formatter rather than the Android-dependent `call()`; inspection confirms the production wiring.
- F2: `Updates.install()` now deletes its staged APK in `finally` if launching the installer fails, and retains it after a successful launch. This closes the reported launch-failure leak without changing another attempt's file. The Android launch path still needs device verification.

Validation: read the brief, plan, decisions and previous review/response; reviewed `git diff main...HEAD`, the touched files and relevant stores, approval and UI guard code, with particular attention to commit `0774760`. Re-ran `/tmp/buddy-build-004/check.sh github`: resources and Java compiled, all 19 JUnit tests passed. Re-ran `/tmp/buddy-build-004/check.sh fdroid`: resources and Java compiled. No additional correctness, security, scope or convention issue requiring revision identified.

Coverage limits remain as recorded in round 1: these local checks use aapt2/javac/JUnitCore, not Gradle or full APK packaging. CI unit/build/signing/release/reproducibility checks and the planned device tests remain for the verifier, including actual card-tap blocking, approved changes across settings families, install-side-door checks, and update/restart with chats and settings intact. This code-review approval does not claim those done-when proofs are complete.

VERDICT: APPROVE
