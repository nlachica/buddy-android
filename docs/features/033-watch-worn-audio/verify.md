# Verification: 033-watch-worn-audio

Verifier (claude-opus-5-5), 2026-11-21, branch `feat/033-watch-worn-audio` at `6c9d984`.

Evidence sources:
- **CI**: run [44994726364](https://github.com/nlachica/buddy-android/actions/runs/44994726364) on `128a861`, conclusion
  success. The `apk` job ran `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease
  :wear:assembleRelease :wear:testDebugUnitTest`, and the tasks `:app:testGithubDebugUnitTest` and
  `:wear:testDebugUnitTest` actually ran (not from cache). The F-Droid "no Google code" and "reproducible" steps and
  `helper-tests` passed too. `git diff --stat 128a861..HEAD` covers only `code-review.md` and `decisions.md`, so CI
  covers the code at HEAD.
- **Local** (Gradle can't run here: the default JDK 21 won't start, and there's no offline Android plugin). Phone: the
  javac + JUnit harness `/tmp/buddy-build-033/check.sh github` ran 316 tests with 1 failure,
  `BypassTest.everyShortLabelOfATapReachesTheDecision`. That failure is an `UnsatisfiedLinkError` in the stub
  android.jar's `Log.isLoggable`; it is environmental, in files 033 doesn't touch, and green on CI. Every `WornTest`,
  `LiveWatchTest`, `AudioRouteTest` and `ReadQueueTest` test passed. Wear: `wear/.../Worn.java` +
  `WornTest` compiled against android.jar, OK (3 tests).
- **No watch on adb**: the plan's step-0 probe and the sign-off gate couldn't run. Those rows are *pending on-device*
  (after release + adb reinstall of the watch app), not failed.

| # | Done-when (brief) | Evidence | Result |
|---|---|---|---|
| 1 | Watch app reads the off-body sensor and sends worn/not-worn over the Data Layer, on change and at start/reconnect; no sensor = nothing/unknown | Reading accepted by the orchestrator's ruling (a) (decisions.md, "plan review ended without APPROVE"): the phone asks at every Auto placement, and the watch pushes each event while a reading is open. Watch: `wear/.../Worn.java:43` (`start`: first `TYPE_LOW_LATENCY_OFFBODY_DETECT` sensor, `no sensor`/`refused`), `:59` (event -> worn + the event's own timestamp); `ReadAloud.java:56` answers `/buddy/worn {id}` (5 s wait, 8 s wake lock, `{id,worn,ms}` or `{id,why}`; `old sample` at `:79`); `ReadAloud.java:165-167` push while a reading plays, stopped in `down()` `:200`; manifest filter `wear/src/main/AndroidManifest.xml:89`. Phone link: `WatchVoiceLink.java:91` (`askWorn`), `WatchLink.java:100` (reply vs push, no `holdWake` per ruling F3). Tests: wear `WornTest.theSensorValueIsWornFromHalf`, `eachFailureHasItsReason`, `theOffsetIsTheSamplesOwnAgeNotTheCallbacks` (local OK + CI). | PASS (code + unit). The sensor's behaviour on the Galaxy Watch is **pending on-device** (checks A, B) |
| 2 | Phone keeps the latest worn state with a timestamp; unknown or stale = not worn. Unit test | `app/.../Worn.java`: state `node/worn/at` (`at` = upper bound of the sample's age on the phone's clock), `on()` `:62` (true only for a fresh `TRUE`, `FRESH_MS` = 3 s `:15`), `check` `:67` (ask id, 8 s `ASK_MS` timeout, send failure), `reply` `:87` (only the pending node+id), `push` `:54` (age-stamped; a removal settles the pending ask). Tests in `WornTest`: `aWornReplyIsWorn`, `notWornAndCantTellAreNotWorn`, `aDelayedPositiveIsWornAtThreeSecondsAndNotAfter`, `aSlowSensorIsWornWhenItsSampleIsRecent`, `aSampleTimeAfterTheReplyIsClamped`, `everyCheckAsksAgainAndTheNewAnswerWins`, `callersDuringOneAskShareIt`, `noReplyIsNotWornAtEightSeconds`, `aFailedSendIsNotWornAtOnce`, `aLateReplyToAnOlderAskIsDropped`, `anotherNodesReplyIsDropped`, `aRefusedRegistrationSaysSo`, `aRemovalPushVoidsAnEarlierWornAnswerAndAWornPushIsFresh`, `aPositivePushIsAsOldAsItsSampleNotAsItsArrival`, `aRemovalSettlesThePendingAskAndItsOlderPositiveReplyIsDropped`, `aReplyFromThePreviousPhoneProcessCannotSettleTheNewAsk` | PASS |
| 3 | Auto order (unit tests): headset -> headset; no headset, watch reachable + worn -> watch; reachable but not worn/unknown/stale -> phone speaker; earpiece only when no speaker route is possible | `pick()` is unchanged; the worn gate sits in Auto's find, `Worn.find` `:110` / `WatchVoice.find` `:65`. Headset first: existing `AudioRouteTest.autoOrder`, `autoOrderPutsTheHeadsetBeforeTheWatchAndNeverTheEarpiece`, plus new rechecks after the ask (`LiveWatch.java:219`, test `aHeadsetThatCameWhileTheWatchWasFoundKeepsTheVoiceOnThePhone`; `AudioRoute.java:162`). Worn -> watch, not worn/can't tell/no reply -> null: `WornTest.findKeepsAWornNodeAndDropsTheRest`, `findWithNoNodeDoesNotAsk`. Null -> phone speaker: existing `ReadQueueTest.quietNoWatchReadsOnThePhoneWithNoNotice`, `LiveWatchTest.noWatchFoundStaysOnThePhone`, `anAutoCallWithNoWatchUsesTheSpeaker`. Earpiece: by the orchestrator's ruling (b) (Q2, the planner's answer, no code), Auto never picks the earpiece while a speaker exists, and a Live call with *no* speaker still ends (030/031, `AudioRouteTest:356` unchanged). | PASS, with the earpiece clause read as ruled. **Q2 still has to be relayed to Nicholas** |
| 4 | Wherever Auto routes to the watch (read-aloud, Live call), a not-worn watch is skipped; a hand-picked Watch is unchanged; 016/018/030/031 tests still pass | Read-aloud: `WatchVoice.Phone.find` `:114` -> `WatchVoice.find` (`ReadQueue` untouched). Live: `LiveWatch.Phone.find` `:453`. Mid-call: push -> `Call.worn` `:192` -> `leave` `:197` (Auto only; `off` keeps the call on the phone, `want()` `:187`); Watch -> Auto switch mid-call leaves unless fresh worn (`:162`). Hand-picked: `Worn.find` skips the ask when `picked` (read when each answer arrives), and `leave` returns for a picked Watch. Tests: `WornTest.aPickedWatchIsUsedWithoutAsking`, `aWatchPickedWhileAskedIsKeptWhenNotWorn`, `aWatchSwitchedToAutoWhileTheFinderRunsIsAsked`; `LiveWatchTest.takenOffInAPause...`, `takenOffWithAWholeReplyQueued...`, `takenOffMidUtterance...`, `aPickedWatchIgnoresAnOffWristPushAndAutoLeavesWhenItIsSwitchedOffTheWrist`, `aWatchSwitchedToAutoWhileWornStays`, `anotherNodesPushIsIgnored`, `aSwitchToAutoWhileTheWatchIsStillBeingFoundLetsTheFindFinish`, `afterATakeOffTheDrainingReadingsAnswer...`, `afterATakeOffAMissingAnswer...`, `aChangeOfTheSettingAfterATakeOffDecidesAgain`. No existing test edited: the test diff only adds a `worn = true` field and its override to `LiveWatchTest`'s fake. The full suite is green on CI and locally (except the environmental Bypass failure). Dictation input: Auto never routes it to the watch (031 uses the BT headset mic), so nothing to gate. | PASS (unit). Real routing on the phone + watch is **pending on-device** (checks C-E) |
| 5 | The setting's "Auto is using ..." display reflects the skip | `AudioRoute.autoNow` `:162` goes through `WatchVoice.find` (worn-gated) and rechecks the headset. `SetupActivity.autoAsk` `:125-131` keeps one ask in flight (an answer can take up to 11 s, longer than the 5 s tick). `buddy_settings` text `SettingsTool.java:422` ("watch while you wear it"); string `setup_audio_device_info_watch`. No unit test (Context-bound, like the rest of `autoNow`). | PASS (code). Display **pending on-device** (check F) |
| 6 | CI green (both APKs build); diff limited to the brief | CI run above: github, fdroid and wear release builds plus phone and wear unit tests, all green. `git diff main...HEAD --stat`: 17 code/README files, each named in the plan's steps (`AudioRoute`, `LiveWatch`, `SettingsTool`, `SetupActivity`, `WatchVoice`, `Worn` + tests, `strings.xml`, `WatchLink`, `WatchVoiceLink`, wear manifest, `Phone`, `ReadAloud`, wear `Worn` + test, README), plus the feature folder. No scope creep found: no new settings, `pick()`/`ReadQueue`/audio streams untouched, no permission, no new service. | PASS |

## Review findings
- **Plan review**, rounds 1-3: all findings either fixed in the plan or ruled on by the orchestrator in decisions.md
  (R3 F1 fixed as ruled and tested; F2 is risk 5; F3 fixed, `WatchLink.java:100` bypasses `holdWake`; F4 is out of
  scope, 016/031 behaviour).
- **Code review**, rounds 1-2: every finding was fixed with a test, except R1 F6, which is fixed in code, untested
  (Context-bound) and recorded. Round 3: F1 (a positive push delayed more than 3 s in transit) and F2 (a removal within
  about 0.5 s of switching to Auto, then put back on) were never fixed. The orchestrator accepted both as known
  limitations, each with a follow-up if seen (decisions.md, last entry). Both concern only a hand-picked Watch switched
  to Auto mid-call. Nothing is open.

## Gaps (non-blocking)
- The plan's gate asked the orchestrator to append both readings (done-when 1, earpiece/Q2) to the brief's
  Clarifications. The section is still empty; the readings live only in decisions.md's orchestrator ruling. Q1
  (BODY_SENSORS prompt) and Q2 (earpiece as last resort) still have to be relayed to Nicholas.
- Unverified sensor behaviour (risks 1, 2 and decisions round 2 F3): the sensor may need a permission, may withhold
  events from a background app, or may stamp its first event with the last change time ("old sample"). If any of
  these happens, Auto never picks the watch and falls back to the phone speaker. That is the safe direction: no
  regression versus a watch that isn't there, and a hand-picked Watch is unaffected.
- No `docs/decisions/` entry: nothing here outlives the feature. The `/buddy/worn` message table is in plan.md,
  referenced from `WatchLink`'s class comment.

## Pending on-device (after release + `update_buddy`, and the watch app reinstalled over adb)
Put the phone and the watch on the same build; keep the watch screen awake for adb (it drops Wi-Fi when it sleeps).
- **A. Probe (plan step 0):** `adb -s <watch> shell dumpsys sensorservice | grep -i -B1 -A3 offbody`. Record whether
  the sensor exists, its `perm:` field and whether it is wake-up in decisions.md. If a permission is listed, it goes
  back to the Planner with Q1 (grant flow).
- **B. Diagnose:** on the phone, `adb logcat -s Worn` while Settings -> Voice (Auto) is open. Expect `worn` /
  `not worn`. `can't tell: no sensor|refused` -> Q1. `can't tell: no event` -> risk 2's way out (a short foreground
  service). `can't tell: old sample` -> the first event's timestamp risk. `stale (age N ms)` -> risk 6
  (`FRESH_MS`). `no reply` -> the watch app is old or the message filter is missing.
- **C. Gate 1:** watch worn, screen off: the note says "Auto is using Watch now" and a read-aloud plays on the watch.
- **D. Gate 2:** watch on the table for at least 6 s: a read-aloud and a new phone Live call both play on the phone
  speaker, never the earpiece. Pick Watch by hand: both still play on the watch.
- **E. Gate 3:** in an Auto Live call on the wrist, take the watch off while Buddy speaks: the voice moves to the phone
  speaker within about 5 s. Taken off in a pause, then a question 5 s later: the whole reply plays on the phone, and
  it stays there for the rest of the call.
- **F. Note:** Settings -> Voice with Auto, take the watch off: the note changes from Watch to Speaker within about
  15 s, and back once the watch is worn again. With a BT headset connected it shows the headset, worn or not.
- If any of C-F fails: Auto just uses the phone speaker (safe direction). Back to the Planner as the plan's gate says,
  fix forward with the next `-fork.<n>`.

VERDICT: SHIP

Every done-when item passes on code and unit tests, and CI is green on the code at HEAD. The on-device rows (A-F)
are pending: they need the phone app released (see buddy-fork-verify.md step 4/5; in that note's terms this is
PARTIAL). Their failure mode is conservative (Auto falls back to the phone speaker), so they are post-release checks.
Relay Q1 and Q2 to Nicholas with the release.
