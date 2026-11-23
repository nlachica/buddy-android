# Code review: 036-hey-buddy-locked
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6.2-astra)

F1 [major] A pending wake turn can still send after the screen goes dark - `app/src/main/java/app/buddy/assistant/WakeWord.java:217` dispatches `SEND` or `AGAIN` without rechecking screen or power. If the screen turns off on battery just after a service tick and a recognizer callback arrives before the next tick (up to 500 ms later at `WakeService.java:352`), Buddy can send a previously unsent request or start a second listening window. The same ordering occurs when unplugging a dark phone. -> Recheck the current screen and power state before sending or opening another window. End the turn if listening is no longer allowed. Add a regression test that delivers each callback after the state changes and before the next tick.

F2 [minor] Two charging-only cases from T1 lack assertions - `app/src/test/java/app/buddy/assistant/WakeWordTest.java:189` checks an unfed turn with the screen off and power connected. The fed-turn and speech-playback assertions run only with the screen on or with power disconnected. -> With the screen off and power connected, also assert `TURN` for a fed turn and `SPEAKING` during playback.

The targeted Gradle test could not start because the local JVM failed to mark memory executable. CI and the planned phone checks remain unverified in this round.

VERDICT: REVISE
