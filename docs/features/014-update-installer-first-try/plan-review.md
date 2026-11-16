# Plan review: 014-update-installer-first-try
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] The success predicate can reproduce the false-success bug - `plan.md:31-35` returns `OPENED` for the existing `installerOnScreen` predicate and `plan.md:139-141` explicitly accepts its false positives. In `app/src/main/java/app/buddy/assistant/Tools.java:2134`, any Play Store window qualifies, and at `:2137` any active window owned by `android` qualifies. If a launch is silently dropped while the Play Store or an unrelated system dialog is visible, the tool immediately claims the installer is open and skips both retry and notification. The existing use in `waitForInstall` merely extends a timeout; actual success there requires the installed version to change (`:2149-2154`), so reusing that heuristic as proof of a successful launch changes its meaning. Even a genuine chooser or Play Protect screen does not yet offer the Update button named by the unchanged success text. -> Use a conservative check for the actual installer confirmation; distinguish intermediate install-related UI with truthful instructions if it must count as progress. Keep unrelated Play Store/system windows from producing success, and add negative checks for those cases alongside a real installer case. Leave `wait_for_install` behavior alone unless sharing the stricter check is necessary.

F2 [major] A thrown launch bypasses the actionable failure result - `plan.md:69-73` defines only normal-return outcomes, and `:84-86` retains delete-on-throw around the first start without specifying recovery. Today `Updates.install` has a `finally` but no catch around `startActivity` (`app/src/main/java/app/buddy/assistant/Updates.java:167-177`); `Tools.updateBuddy` also does not catch it (`Tools.java:2239`). The MCP boundary reduces such exceptions to `Error: <message>` (`app/src/main/java/app/buddy/assistant/McpServer.java:282-286`). An `ActivityNotFoundException` or `SecurityException` on either attempt therefore bypasses `afterInstall`, leaving no instruction about what to tap, contrary to DW2. -> Define handling for launch exceptions and return an explicit failure with a usable manual/settings route. Use the notification fallback only when it can still work; a missing installer handler is not fixed by wrapping the same intent in a PendingIntent. Test first-attempt and retry exceptions, and preserve the staged file after a successful first dispatch even if the retry throws.

F3 [major] Finding the device cause is optional rather than part of completion - `brief.md:13` explicitly asks to find the real cause on this phone. `plan.md:117-124` treats the attempt number as cause evidence, while diagnostics are conditional/optional (`:123-133`) and failure of both attempts is deferred to a follow-up (`:136-138`). `OPENED_RETRY` establishes only that a retry helped; it cannot distinguish a blocked launch, a slow first launch, task state, or package verification. A passing run alone also cannot establish why earlier launches failed. -> Make capturing launch/task/installer evidence around a reproduction a required verification step, using available phone logcat/dumpsys and screen observations. Record which hypothesis the evidence supports; if diagnostics are unavailable or the failure cannot be reproduced, explicitly leave the cause unresolved and verification partial. Keep DW1 open until the fixed build actually opens the installer on the first tool call with another app in front; notification-only recovery must not close it. This can remain a bounded retry experiment while evidence is gathered, without adding another installer architecture. Android documents silent BAL rejection and Logcat as the diagnostic source: [Activity security](https://developer.android.com/guide/components/activities/secure-bal).

F4 [minor] The named CI unit-test task is incorrect - `plan.md:103` names CI `testGithubReleaseUnitTest`, but `.github/workflows/build.yml:63` runs `testGithubDebugUnitTest`. Tests in the common test source directory should still run, so this does not require changing the workflow. -> Name the actual CI task and record its result in verification.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - The check now counts only Android's installer: a window whose package contains `packageinstaller`
(static `Updates.installerPkg`, same rule as `BuddyService.installerInFront`), read through a new
`Tools.installerShown()` over `svc.getWindows()`. Play Store, Play Protect, the chooser and other system windows no
longer count. `installerOnScreen()`/`wait_for_install` stay untouched. Unit tests add true cases (Google and Samsung
installer) and false ones (`com.android.vending`, `android`, `com.android.chrome`, null). No separate "intermediate"
outcome (YAGNI: no such screen was seen on this phone). Instead, the NOTIFIED/NOT_SHOWN texts say "couldn't see the
installer" and add one clause: finish a Play Protect / "Open with" screen first. Old Risk "False on screen" replaced.

F2: accepted - A throw on the first start leaves `openChecked`. `install` deletes the file as today, and
`Tools.updateBuddy` catches `RuntimeException` and returns "Couldn't open Android's installer (...), so it isn't on
screen" plus buddy.apk / Update and `MANUAL`, through `afterInstall`'s error path. No notification, since a missing
handler isn't fixed by a PendingIntent. A throw on the retry only is caught inside `openChecked` and handled as not
shown (wait, then the notification). The file stays, because the delete-on-throw only wraps the call and the retry's
exception never leaves it. Tests: first-launch throw propagates (1 launch, no fallback); retry throw → `NOTIFIED`
(2 launches, fallback once); afterInstall on an error string → string + `MANUAL`.

F3: accepted - The Test plan has a required "Cause" step. Updating to the fix release still runs the old code, so that
update is the reproduction: dumpsys before, read_screen + logcat + dumpsys right after. Raw lines and the hypothesis
they support (BAL / cold-late installer / leftover task / Play Protect) go in `verify.md`. With no reproduction or no
Phone commands, it says "cause unresolved". `OPENED_RETRY` alone never counts as the cause. DW1 passes only on
`OPENED`/`OPENED_RETRY` with the installer on screen after the first call. `NOTIFIED`/`NOT_SHOWN` fail DW1. No other
installer architecture added.

F4: accepted - Test plan names `testGithubDebugUnitTest` (`build.yml:63`). DW3 records its result in `verify.md`.

## Round 2 - Plan Reviewer (gpt-6-astra)

F1 [minor] Capture an effectively final staged file in the new lambdas - `plan.md:101` passes `out` into both the launch and notification lambdas, but the existing method initializes `out` to null and assigns it again during download (`app/src/main/java/app/buddy/assistant/Updates.java:139-141`). That variable is not effectively final, so copying the planned expression literally will not compile. -> After download and validation, bind `final File apk = out` and capture `apk` in both lambdas. This is a local implementation correction, not a design change.

Round 1 F1-F4 are addressed by the revised plan: the narrower installer predicate and its negative tests, explicit first/retry exception handling, required cause capture with unresolved results recorded honestly, and the correct CI task. No blocker or major finding remains open. The default-display observation and direct notification activity handoff are consistent with the Android references for [AccessibilityService.getWindows](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows()) and [background activity launch exceptions](https://developer.android.com/guide/components/activities/secure-bal).

Review scope: inspected the plan, prior decisions/review, affected code and CI configuration; no implementation or device tests were run. Approval is for implementation. The cause remains unconfirmed, and DW1 remains open until the fixed build passes the planned device check; notification recovery alone does not satisfy it.

VERDICT: APPROVE
