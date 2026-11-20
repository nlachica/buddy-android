# Verification: 031-headset-route

Build: the JVM does not start on the phone (PaX), so `./gradlew` and `build.sh` cannot run here. CI is the build of
record: run 44815214396 (commit 0ca3099, `helper-tests` and `apk` jobs green, incl. `testGithubDebugUnitTest` and the
F-Droid reproducibility step). 32bea31 (HEAD before this file) only adds review notes; the PR's own CI run covers the
final commit. No build break to fix.

| Done-when item | Evidence | Result |
|---|---|---|
| Live: headset gets its setup time while Android reports the earpiece; test reproduces the log and fails on main | `AudioRouteTest.theLs2ConnectingWhileTheEarpieceIsReportedIsNotRefused` (log 0.26 -> 2.99, 7.59 -> 8.44); `grace()` now `SETUP_MS`; CI 44815214396; code review round 3 APPROVE | pass (red-on-main not re-run locally) |
| A headset that really fails still falls to the speaker, never the earpiece; 030 tests pass | `aLateHeadsetKeepsTheSpeakerEligibleForTheNextFallback`, `aRefusedHeadsetThatConnectsLateIsTriedAgainNotTheSpeaker`; 030 tests unchanged, in CI run | pass |
| Dictation with the headset (Auto, saved): input from the headset; root cause fixed with a test | `autoWhileDictatingUsesTheHeadsetsCallLink`; `Voice.capturing()` follows `ClaudeDictation/CodexDictation.taking()` and the phone recognizer's `inputDone` (round 2); engine transitions checked by inspection only | pass (JVM tests); device check pending |
| Auto order: headset > watch > speaker, never earpiece | `autoOrderPutsTheHeadsetBeforeTheWatchAndNeverTheEarpiece` | pass |
| CI green; diff limited to routing fix, tests, docs | CI 44815214396 green; diff: `AudioRoute`, `ClaudeDictation`, `CodexDictation`, `Voice`, `SettingsTool` (text), `AudioRouteTest`, README, feature folder | pass |

Open review findings: plan review and code review rounds 1-2 findings fixed (see `code-review.md` coder responses);
round 3 has none open.

Pending (needs the fork on the phone, see `learnings/solutions/android/buddy-fork-verify.md` item 4): two-way Live
audio through LS2 Freecom, dictation through its mic, read-aloud routing. Verify after merge, tag and `update_buddy`;
fix forward if it fails.

VERDICT: PARTIAL (all CI/unit-test rows pass; device rows pending after release)
