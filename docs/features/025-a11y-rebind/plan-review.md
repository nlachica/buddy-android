# Plan review: 025-a11y-rebind
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Minimum latency does not establish the required detection window - `plan.md:42` says `setMinimumLatency(40 s)` with no other constraint runs in 40-60 s, and `plan.md:181` treats unrestricted battery / exempt standby as sufficient evidence that it runs on time. The brief requires notification within approximately one minute (`brief.md:19`). Android documents minimum latency as only the earliest eligibility time; execution afterwards is indeterminate ([JobInfo.Builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setMinimumLatency(long))). The pure decision test cannot validate scheduling, and the plan supplies no measurement supporting its timing claim. -> Keep the one-shot job if it is sufficient, but specify an explicit latest desired execution time (consider `setOverrideDeadline(60_000)` alongside the minimum), retain the documented Doze/system-health caveat, and require a timed device/probe check with Buddy in the background, including a process killed and restarted by the pending job. A deadline is also best-effort; if measured behavior misses the brief's target, revise the scheduling mechanism or obtain an explicit change to the timing requirement rather than assuming exemption proves it.

F2 [major] The reply-history gate silently excludes required outage cases - `plan.md:56` and `plan.md:62` return `NONE` for every unbound service until `Prefs.hadReply` is true. That preference defaults to false (`app/src/main/java/app/buddy/assistant/Prefs.java:393`) and is set only when a running chat finishes with a nonempty reply (`app/src/main/java/app/buddy/assistant/ChatHub.java:909`). A user can already have enabled the service, installed/signed into an agent, and then lose the binding before the first successful reply; this is precisely enabled-but-unbound, yet the planned implementation never alerts them. The same gate excludes a subsequently disabled service. Neither `brief.md:19` nor `brief.md:20` contains this restriction. -> The smallest brief-compliant option is to remove the `hadReply` gate and its third decision input. If suppressing onboarding notices is required, record that scope decision explicitly and use a condition that still detects an enabled-but-unbound service before any reply; add tests for the no-reply outage cases instead of asserting they must return `NONE`.

F3 [major] The details-page constant is unavailable to this app's SDK build - `plan.md:83` specifies `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` as an API 31+ public constant, but AOSP declares it `@hide` and `@SystemApi` ([Settings.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java#439)). Buddy uses the ordinary SDK (`app/build.gradle.kts:18`, compileSdk 36), so that Java reference will not compile; an SDK version guard does not fix symbol resolution. -> Use the literal action string `"android.settings.ACCESSIBILITY_DETAILS_SETTINGS"` in the existing helper, retaining the resolver check, queries entry and general-settings fallback, and describe it as an optional platform action rather than a public API guarantee. The planned flattened string extra is correct: AOSP's [AccessibilityDetailsSettingsFragment](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/master/src/com/android/settings/accessibility/AccessibilityDetailsSettingsFragment.java) reads `Intent.EXTRA_COMPONENT_NAME` with `getStringExtra`. Verify the actual Buddy details destination on Nicholas's phone; falling back to the list remains degraded behavior, not proof of the one-tap done-when item.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - The job now also sets `setOverrideDeadline(60_000)` next to the 40 s minimum, and `build()` sits inside
the same try as `schedule()`. Risks keeps the Doze/OEM caveat and no longer treats the exempt bucket as proof. The Test
plan now requires two timed device checks with the code in the background, timed from `a11y.log` timestamps (trigger
line to `check:` line, at most about 65 s), and records the pending job with `dumpsys jobscheduler`:
(a) before release, a probe app runs `A11yWatch` unchanged. Its process is started, sent to the background and
`am kill`ed while the job is pending. The job must restart it and post the DEAD notice. This also proves the DEAD half
of row 1 without waiting for a recurrence.
(b) after release, Buddy's own OFF run in row 2.
If either misses the window, the Verifier fails the row, and the fix is a different scheduling mechanism or a change
to the timing requirement agreed with Nicholas. The plan does not assume the timing holds.

F2: accepted - The `hadReply` gate is removed. The decision is now `decide(bound, enabled)` with states
`CLEAR/DEAD/OFF`, and `NONE` is gone. CI now asserts DEAD and OFF with no reply history, because there is no history
input left. A superseding entry is in `decisions.md`. Fresh installs aren't special-cased: on API 33+ nothing is posted
until notifications are allowed, and once they are, an off service really does stop Buddy working (Risks).

F3: accepted, with a different fix - The literal string would compile, but it fails when tapped. AOSP Settings'
`Settings$AccessibilityDetailsSettingsActivity` declares
`android:permission="android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS"` (packages/apps/Settings
`AndroidManifest.xml`), and core declares that permission `signature|installer` ("Not for use by third-party
applications", frameworks/base `core/res/AndroidManifest.xml`). Buddy can't hold it. `resolveActivity` doesn't check
permissions, so the planned resolver check would pick the details page, and the tap would fail with a
SecurityException. The plan drops the details action and its `<queries>` entry. The tap opens public
`Settings.ACTION_ACCESSIBILITY_SETTINGS`, the page upstream's setup opens, with the string extra
`:settings:fragment_args_key` set to Buddy's flattened `BuddyService` component.
AOSP's `SettingsPreferenceFragment` reads that extra from the intent and highlights the matching row. A service row's
key is `componentName.flattenToString()` (`AccessibilityServicePreference`). Other Settings apps ignore the extra.
The tap therefore opens the Accessibility list with Buddy's row highlighted, and one more tap opens Buddy's page.
Because a third-party app can't open the details page at all, done-when 1's "opens Buddy's accessibility settings"
can only be met this way. This is raised as an Open question for Nicholas (it doesn't block coding), and the Verifier
records what Nicholas's phone shows.

## Round 2 - Plan Reviewer (gpt-6-astra)

F1 [major] The replacement tap destination still changes an unamended requirement - `brief.md:13` requires "Settings > Accessibility > Buddy in one tap", and `brief.md:19` requires Buddy's accessibility settings. The revised `plan.md:81-89` deliberately opens the Accessibility list and requires another tap; `plan.md:199` nevertheless uses that destination as proof of done-when 1, while `plan.md:226-230` postpones acceptance until verification. There is no user clarification in `brief.md:25-26`. The Planner's correction to round 1 F3 is sound: [AOSP Settings' manifest](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/AndroidManifest.xml#2443) protects the details activity with `OPEN_ACCESSIBILITY_DETAILS_SETTINGS`; restoring the literal-action proposal is not the fix. However, a platform limitation does not itself authorize changing the goal. -> Have the orchestrator obtain and append Nicholas's explicit acceptance of opening the Accessibility list (including the possibility of no highlight), then align the plan's acceptance row with that clarification. Until then, keep the one-tap item unmet and this finding open rather than treating recording the destination after release as sufficient proof.

F2 [major] The breadcrumb can attribute a renderer's death to Buddy's service process - `plan.md:103-107` takes `getHistoricalProcessExitReasons(pkg, 0, 1)` and states that the returned record is the previous Buddy process's exit. The [ActivityManager API contract](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int)) explicitly includes external-service processes in the package's exit history, and returns records newest first. Buddy uses WebView (`app/src/main/java/app/buddy/assistant/CodexLive.java:670`, `CodexSpeech.java:154`, `CodexDictation.java:167`); [Chromium's renderer documentation](https://chromium.googlesource.com/chromium/src/+/main/android_webview/renderer/) describes its external sandboxed processes running in the embedding app's context. Thus a newer renderer exit can occupy the single returned slot, hiding the main-process death and producing misleading evidence for the very WebView/update/memory incidents this feature investigates. The planned log also omits process identity, so an agent cannot detect that substitution. -> Retrieve the available bounded history (`maxNum = 0`), choose the newest record whose `getProcessName()` matches `Application.getProcessName()`, and log its process name/PID with the existing fields. If no matching record remains, say that the previous main-process exit is unavailable. Add a small selection test with a newer renderer record before an older Buddy record, plus the no-match case; do not infer a Buddy exit from the newest package-wide record.

VERDICT: REVISE
