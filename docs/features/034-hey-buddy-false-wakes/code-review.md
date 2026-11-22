# Code review: 034-hey-buddy-false-wakes
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6.2-astra)

F1 [major] Confirming a wake can send “stop” to chat during barge-in - `app/src/main/java/app/buddy/assistant/WakeLoop.java:124` passes the final hop’s timestamp to `WakeWord.overlapped`. If playback ended at 1000 ms and Medium sees qualifying hops at 1850, 1970 and 2090 ms, the first hop falls inside the existing 1000 ms overlap window. The confirmation falls outside it. With playback now silent, `WakeWord.java:361` treats “hey buddy, stop” as a chat request. -> Save the first qualifying hop’s timestamp when the run starts and pass it to `onWake` when the Nth hop confirms. Cover this boundary with distinct hop timestamps and assert both the callback timing and the overlap result.

F2 [minor] The sensitivity-change test starts with an empty count - `app/src/test/java/app/buddy/assistant/WakeLoopTest.java:310` feeds 0.6 under Low (threshold 0.7), so removing the reset on a rule change still passes. -> Feed two hops at 0.9 under Low, switch to Medium, and assert zero wakes after two new qualifying hops and one after the third.

Both CI jobs passed on the reviewed commit, including the real-model regression and both APK builds. The local JVM failed to start, so the test evidence comes from [CI run 45039935937](https://github.com/nlachica/buddy-android/actions/runs/45039935937).

VERDICT: REVISE
