# Code review: 036-hey-buddy-locked
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6.2-astra)

F1 [major] A pending wake turn can still send after the screen goes dark - `app/src/main/java/app/buddy/assistant/WakeWord.java:217` dispatches `SEND` or `AGAIN` without rechecking screen or power. If the screen turns off on battery just after a service tick and a recognizer callback arrives before the next tick (up to 500 ms later at `WakeService.java:352`), Buddy can send a previously unsent request or start a second listening window. The same ordering occurs when unplugging a dark phone. -> Recheck the current screen and power state before sending or opening another window. End the turn if listening is no longer allowed. Add a regression test that delivers each callback after the state changes and before the next tick.

F2 [minor] Two charging-only cases from T1 lack assertions - `app/src/test/java/app/buddy/assistant/WakeWordTest.java:189` checks an unfed turn with the screen off and power connected. The fed-turn and speech-playback assertions run only with the screen on or with power disconnected. -> With the screen off and power connected, also assert `TURN` for a fed turn and `SPEAKING` during playback.

The targeted Gradle test could not start because the local JVM failed to mark memory executable. CI and the planned phone checks remain unverified in this round.

VERDICT: REVISE

### Coder response (round 1)

F1: fixed in the commit "036: recheck screen and power before a wake send or second window". `WakeWord.act` now reads
`WakeService.stateNow()` (fresh, not the tick's) and passes the step through the pure `WakeWord.gate(step, state)`:
`ASLEEP` turns SEND/AGAIN into END, which ends the turn without sending or reopening. A missing service counts as not
asleep (no service, no turn). Regression test `aSendOrSecondWindowEndsWhenTheStateTurnedAsleep` covers the gate for
every state; the callback-after-state-change ordering itself needs the phone (Android statics), so it stays a
Verifier phone check. Note: this drops a request the recognizer already transcribed when the screen went dark in
that window; the brief's "no capture with the screen off on battery" rules, and the reviewer asked for exactly this.

F2: fixed in the same commit. In `screenOnOrChargingOnly`'s sibling test (`screenOffOnBatteryBeatsATurn...`) with the
screen off and power connected, a fed turn now asserts `TURN` and playback asserts `SPEAKING`.

Tests: not run here, the JVM cannot start in this environment (`Failed to mark memory page as executable`). CI and
the phone checks remain the check.

## Round 2 - Code Reviewer (gpt-6.2-astra)

F1 [blocker] The plugged-in fed-turn test carries over the unfed recognizer's recording - `app/src/test/java/app/buddy/assistant/WakeWordTest.java:192` expects `TURN` after switching to a fed turn, but `foreignRecordings` is still 1 from line 184. A fed turn gets its audio from the detector, so one foreign recording correctly yields `PAUSED` (`WakeWord.java:324`). CI run 45095699462 failed here, with 1 of 384 tests failing. -> Move the existing `i.foreignRecordings = 0` before the fed-turn assertion. Keep the production pause rule and rerun CI.

F2 [major] A call can mask screen-off and let a pending wake callback through - `app/src/main/java/app/buddy/assistant/WakeWord.java:235` ends the step only for `ASLEEP`. If the phone is dark on battery and a call has started when the transcript arrives, `state()` returns `PAUSED` at line 334 before checking screen and power. Before the next tick cancels the turn, `act` can still send the transcript or open another listening window. The test at `WakeWordTest.java:217` explicitly permits both actions in `PAUSED`. -> Allow a pending turn to send or reopen only when its fresh state is `TURN`. Test both callbacks with a dark, unplugged phone and an active call, plus a permitted turn with power connected.

The local JVM failed to start because it could not mark memory executable. The CI result above is for the reviewed HEAD, `5429171`. Phone checks P1–P6 are pending the planned release.

VERDICT: REVISE

### Coder response (round 2)

F1: fixed in the commit "036: a wake send or second window needs state TURN". `i.foreignRecordings = 0` now comes before
the fed-turn assertion in `screenOffOnBatteryBeatsATurnAndSpeakingButNotACause`, as the reviewer said; the production
pause rule is unchanged.

F2: fixed in the same commit. `WakeWord.gate` lets a SEND or AGAIN through only when the fresh state is `TURN`; every
other state (`ASLEEP`, `PAUSED`, `OFF`, no service) ends the turn. Tests: `aSendOrSecondWindowGoesOnlyWhileTheStateIsStillTurn`
(every state, both steps) and `aCallOnADarkPhoneOnBatteryEndsAPendingTurnButPowerKeepsIt` (dark, unplugged, call:
both END; power connected, no call: SEND and AGAIN).

Tests: not run here, the JVM cannot start in this environment (`Failed to mark memory page as executable`); CI is the check.

## Round 3 - Code Reviewer (gpt-6.2-astra)

Both round 2 findings are resolved at `1a9aed0`. The plugged-in fed-turn test clears the unfed recognizer’s recording before asserting `TURN`. The transcript and retry callbacks read the current state before dispatch. A call on a dark, unplugged phone ends either callback, and a plugged-in turn with no pause cause can still send or reopen. The tests cover both callbacks and all eight setting, screen and power combinations.

The GitHub unit tests and both release APK builds passed for this commit. The local JVM could not start because it could not mark memory executable. Phone checks P1–P6, including microphone release, wake-lock release and resume timing, remain for post-install verification. [CI run 45096156373](https://github.com/nlachica/buddy-android/actions/runs/45096156373)

VERDICT: APPROVE
