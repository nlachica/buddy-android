# Code review: 023-idle-chat-delivers-messages
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

F1 [major] A delayed failed-send callback can strand text in an already-idle replacement run - `app/src/main/java/app/buddy/assistant/ChatHub.java:1028` queues every leftover into `notes` whenever the process is running, even when `turnOver` is true and live input is available. Concrete ordering: A's asynchronous live send fails; the old run emits `buddy_done`; its leftover B starts a replacement run; B finishes its turn with a long background job pending; only then A's failure callback reaches `leftover` (`:1011`). A is put into notes after the replacement run's result-time flush, so with no phone tool call it waits for the background job again. The callback runs through a separate helper thread and a main-thread post, with no ordering guarantee relative to the new run's events. A temporary probe against the compiled production statics confirmed `liveInput == true` followed by `placeLeftover == LEFT_NOTES`; `leftover` performs no dispatch for that route. `LeftoverTest.eachIsSentOnceWhicheverArrivesFirst` tests the second callback before the replacement run ends its turn, missing this case. -> Re-evaluate turn state when routing a failed send into a newer run and deliver it live if that run is already idle. Retain enough originating-run information (the existing `runId` can distinguish replacement runs) to avoid endlessly retrying a refused send against the same closed input. Add the callback-after-replacement-result ordering to the routing tests, including Stop, and require delivery before the new background job ends.

F2 [minor] The delayed-notification test inherits already-released gates - `linux/test/run.sh:214` starts `notify` after `queued` created `$H/go` and `$H/go2` (`:206`, `:208`), but neither `finish` (`:128`) nor `live_case` (`:192`) removes those files. Both `waitf` calls in the notification stand-in therefore return immediately. The harness's seven-second sleep at `:216` delays `say ping`, not the notification turn, so the planned delayed-notification regression is not exercised. Running the unmodified stand-in with these leftover markers produced both results in 0.24 seconds without releasing a fresh `go2`. -> Remove the gate files before each live case (or give each case its own directory), and assert that the notification's second result has not appeared before releasing `go2` after the delay.

Review covered `git diff main...HEAD`, touched files, connected delivery/helper callers, the brief, plan, decisions and prior plan reviews. No other security or YAGNI findings.

Validation:
- `bash linux/test/run.sh`: 35 fixtures and runs passed; F2 limits the notification-delay coverage.
- The Coder's temporary javac/JUnit harness compiled the app and ran `TurnTrackTest` and `LeftoverTest`: 8 tests passed. This harness uses Android shims and is not equivalent to Gradle/CI.
- `./gradlew testGithubDebugUnitTest --offline --no-daemon`: could not initialize the JVM (`Failed to mark memory page as executable`).
- Temporary production-static routing and notification-stand-in probes reproduced F1's route and F2's skipped wait. No production or test code was modified.
- Real Claude, bubble/full-chat/watch interactions, and CI were not run; the plan's Verifier checks remain outstanding.

VERDICT: REVISE

### Coder response (round 1)
F1: fixed in b54d814. `sendLive` remembers the run it sent into; the failure callback passes that run id to `placeLeftover`, which
returns a new route `LEFT_LIVE` when a different run is going and `liveInput` holds (turn over, not stopped): `leftover` then
calls `sendLive` again (as a note, already in the chat), into the new run. The same run, a replacement still mid-turn, and
any run after Stop go to notes as before (Stop: `buddy_done` sends them back to the box). `buddy_done`'s own call passes -1.
`LeftoverTest.aLateFailedSendGoesLiveToAnIdleReplacementRun` covers the callback-after-replacement-result order, the
same-run and busy cases, and Stop. Not verified: the real helper/main-thread race; only the routing decision is tested.
F2: fixed in b54d814. `live_case` removes `go`/`go2` before starting; the notify case asserts only one `result` exists after the
7 s wait, before `go2` is released.
