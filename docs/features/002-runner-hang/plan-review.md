# Plan review: 002-runner-hang
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Known abnormal exits still look successful - `brief.md:43` requires error + done for a dead agent, but `plan.md:62-66` explicitly preserves silence for exit codes 1-128 with empty stderr and for status 143. Research already reproduced exit 3 with empty stderr and no result (`research.md`, Reproduction 1); the plan leaves that case unfixed. A direct TERM of the agent also returns 143 without being a user Stop (confirmed with the proposed relay and a child running `kill -TERM $$`). `ChatHub.java:703-707` treats done without an error as an ordinary completion, so the helper-exit callback cannot repair this after done clears `running`. The duplicate-error concern in decision 3 is explicitly unverified and does not justify dropping the requirement. -> Report a fallback error for abnormal termination when no error was already reported; distinguish an explicit Stop from an independently TERM-killed agent. Define how an incomplete stream without a terminal result is classified while preserving legitimate successful runs. Add empty-stderr exit 3 and agent TERM regressions, alongside the existing kill-9 cases, and check that an already-reported backend error is not duplicated.

F2 [major] The end marker still depends on the producer shell surviving - `plan.md:30` writes the sentinel only after `run_x` returns in that same pipeline process. Using the exact proposed `until_exit`, `run_fake() { sleep 30 & kill -KILL "$BASHPID"; }; until_exit run_fake; echo done` leaves the relay alive without completion while the descendant holds stdout (review experiment: no output/completion after 1 s; isolated process group then cleaned up). A producer-shell kill or fatal shell abort bypasses the marker. This matters to the reported zombie `run_claude` subshell (`brief.md:19-20`), not just an unrelated process failure. The app callback cannot rescue it because the outer helper is still alive; `plan.md:100-103` incorrectly equates helper exit with every silent/no-live-agent case, and leaves the one-shot timer unchanged. -> Observe producer/agent death from a surviving supervisor, or add a bounded liveness fallback that can terminate this stream when its producer is gone. Gate any idle check on liveness so legitimate silent work remains allowed. Add this producer-death/stdout-holder case to the regression harness and require bounded error + done for a top-level run too.

F3 [major] The ceiling misses note-triggered sub-agent runs - The ceiling is installed only in `send` (`plan.md:86-88,149-151`). A note left at completion starts another run through `buddy_done` -> `sendNoteAsMessage` (`app/src/main/java/app/buddy/assistant/ChatHub.java:712-725,801-811`), which does not pass through `send`; neither notes nor this path exclude sessions with a parent (`:766-778`). Its new `runId` also invalidates the earlier run's timer. Thus a live but wedged sub-agent launched this way has no hard ceiling, contrary to `brief.md:45`. -> Arm the ceiling at a shared run-start point, or explicitly at both launch paths, preserving the guard for each run. Add a check where a queued note starts a second sub-agent run and that run exceeds a shortened ceiling.

F4 [major] Late events can undo failure and invalidate the new exit callback - `fail()` clears `running`, but the unchanged `onEvent` automatically calls `startRun` for a system/assistant event whenever the session is not running (`ChatHub.java:559`); `startRun` clears `outcome` and increments `runId` (`:894-900`). Concrete path: the retained give-up timer fails a silent run; a late system event starts it again with a different id; the wrapper is then SIGKILLed. The new callback in `plan.md:78` compares against the original id and does nothing, and no done can arrive. The chat remains running forever. After a ceiling, an event already queued or emitted before the asynchronous Stop likewise restores running, clears the failure, and defeats the proposed `buddy_stopped` guard. -> Make failure terminal for the launched run: prevent its late events from implicitly starting another run, and keep callback ownership tied to that launch until it actually exits. Explicit new sends must still work. Require deterministic checks for give-up -> late system event -> wrapper death and ceiling -> late assistant/stopped/done events; both must remain failed, release the parent, and never resurrect busy state.

F5 [major] The new busy waiter produces an unowned second done - `plan.md:45-56` gives a rejected busy `ask` its own EXIT-generated done after waiting for the old pid. The original wrapper already emits done, removes its pid, and can cause the app to start a queued-note run (`linux/buddy-run:995-998`; `ChatHub.java:703-725`). The waiter can observe the gap with no pid and emit its delayed done after that next run has started. Events carry only the session id (`linux/buddy-run:266`), and `ChatHub.java:704` accepts any done while running, so this second done can incorrectly end the next run and release its parent. The callback's `runId` guard does not guard HTTP events. -> Give completion a single owner in the busy-rejection path, or otherwise ensure its completion cannot be applied to a subsequent run. Add an ordered regression for old run completion -> next run starts -> busy waiter exits, asserting that the next run stays running until its own completion. This can be addressed narrowly without a general protocol redesign.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - every non-zero exit now ends with an error. Classification is by the agent's exit status alone:
0 = finished (even without a result event, so agy's withheld result and an OpenCode end without step_finish stay
successes); non-zero = failed. Stderr text with a status other than 143 is reported as today. Every other non-zero end
(empty stderr: "it exited with status N." or "it was killed (signal N).", plus any 143) goes out as a `buddy_error`
with one new optional field, `"fallback": true`. The wrapper's own "cut off" error does too. The app drops a fallback
error when the run already has an outcome: "failed" (the agent reported its own error, e.g. a Claude error result,
so nothing is doubled) or "stopped" (`stop()` sets it synchronously before it sends "stop"). That one rule tells an
explicit Stop apart from an agent TERMed by anything else. New harness cases: `claude_exit3`, `claude_agentterm`,
and `claude_error` (an error result, then exit 1 with empty stderr: the fallback carries the flag the app drops on).
Decision 3 is superseded.

F2: accepted - `until_exit` now runs `run_x` in a background shell of its own: `{ "$@" & wait "$!"; s=$?; printf …end…; exit "$s"; }`.
The brace group only waits, so `wait` returns however run_x's shell dies (SIGKILL included, status 137) and the end
line always follows. Tried on the phone with your exact `run_fake() { sleep 30 & kill -KILL "$BASHPID"; }`: the stream
ends in 0.1 s with status 137. kill -9 of an agent whose child holds stdout: 0.08 s, 137. The agent's parent is
run_x's shell, so a fake agent's `kill -9 $PPID` reproduces producer death; that is the new `claude_producer` case.
It is a top-level run and must end with error + done within the case timeout. Still not covered: SIGKILL of the
waiting shell itself. Then the ceiling (sub-agents) or Stop (`kill_tree` takes the whole tree) end the run. Design
C's "no idle timer" text is corrected: a dead agent or producer is now caught in the helper while it is alive, and the
app's check covers a dead helper. No idle timer and no change to the one-shot give-up timer: legitimate silent work
stays allowed.

F3: accepted - the ceiling is armed in `startRun`, the shared run-start point, for every run of a chat with a parent:
`send`, a queued note's run (`sendNoteAsMessage`), and any other start. It is keyed to that run's `runId`. Check:
a walkthrough in the Test plan, plus an optional build with a 1-minute ceiling. An automated Java check is rejected:
the app has no unit-test setup, and the brief's done-when 5 asks only for run.sh.

F4: accepted - two changes. (1) Late events never restart a run that ended failed or stopped: the auto-start in
`onEvent` (`:559`) also needs `ses.outcome == null`. An explicit send still works (`startRun` resets the outcome),
and late events still add their bubbles. (2) The helper-gone check no longer compares `runId`. Each chat counts its
live `ask` helpers (`Session.asking`, runtime only): it goes up when `Helper.run` returns and down in that launch's
exit callback. The check is "the last helper exited and the chat still works -> fail". Ownership lasts until the
process really exits, and a restarted run or a busy-rejected launch can't detach it. Both of your paths are traced
step by step in the Test plan (give-up -> late system event -> wrapper SIGKILL; ceiling -> late assistant /
`buddy_stopped` / fallback error / done). Both stay failed, release the parent, and never turn busy again.
Deterministic Java tests: rejected for the reason given under F3.

F5: accepted - the busy waiter is gone. The traps are set after the busy check, so a rejected `ask` exits as today:
the "still working" error and no done. The run in progress is the only owner of the chat's done. The app's exit
callback for the rejected launch does nothing, because the old launch's helper still counts (F4 (2)). The chat keeps
its Stop button, and the old run's done ends it. No second done exists. New harness case `claude_busy`: a live pid in
`$S/pid` gives exactly the "still working" error, no `buddy_done`, and an immediate exit. The app half is traced in
the Test plan.

Also changed, found while answering F1: since 143 is now reported, the run subshell could race the wrapper's TERM
trap and post a second fallback. The trap now writes `$S/cut-off` before `kill_tree`, and the run section skips its
error rule when that file exists. On the phone, 5 of 5 runs gave exactly "cut off", then done, with the agent gone.

## Round 2 - Plan Reviewer (gpt-6-astra)

F1 [major] Killing the recorded run wrapper still reports successful completion - The revised supervisor catches death of `run_x`, but the enclosing run subshell remains another failure boundary. `plan.md:61` retains `wait "$RUN"` without checking its status, and `plan.md:59` makes the outer EXIT trap send only `buddy_done`. The process recorded in `$S/pid` is that enclosing subshell (`linux/buddy-run:921,994-998`), which also owns the error-classification code (`:989-993`). Killing it bypasses that code; its failed wait is then discarded by the outer wrapper. Reproduced in an isolated scratch run using the current script with the planned relay, EXIT trap and `RUN`/wait changes, a fake Claude that emits a system event then sleeps, and local event capture: SIGKILL to the pid from the session pid file made the outer wrapper exit 0 with exactly `system`, `buddy_done`, and no `buddy_error`. No real chat or agent was touched; the isolated process group was cleaned up. The proposed app callback cannot repair this: `buddy_done` has already cleared `running` (`ChatHub.java:703-707`; `plan.md:95-100`), so the interrupted run is reported as successful and its parent receives no failure. This is a separate wrapper-death path from round 1 F2's producer-shell death, and violates the failed-run/error requirement in `brief.md:28-30,43-44`. -> Capture and classify the enclosing run subshell's wait status before emitting done, reporting a fallback error for unexpected termination. Preserve the existing fallback suppression for explicit Stop and avoid duplicating the outer TERM trap's error. Add a regression that kills the pid actually stored in `$S/pid`, requiring bounded error + done and a failed app outcome; keep the existing producer-shell and outer-wrapper cases too.

VERDICT: REVISE
