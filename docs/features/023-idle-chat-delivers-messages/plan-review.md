# Plan review: 023-idle-chat-delivers-messages
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Failed live delivery can lose a message when the old run ends - The plan removes text from notes before the asynchronous `say` finishes, then reuses the existing leftover handler on failure (`plan.md:119-127`, `plan.md:199-204`). That handler clears notes and posts a continuation which silently does nothing if another run has started (`app/src/main/java/app/buddy/assistant/ChatHub.java:885-902`); helper completion runs on a separate thread (`app/src/main/java/app/buddy/assistant/Helper.java:78-103`). Concrete ordering: message A is being sent live; message B becomes a note; `buddy_done` drains B and posts its continuation; A's already-queued failure callback runs before that continuation, sees the session idle and posts A's continuation; B starts a run, then A's continuation skips delivery because `ses.running` is true. A is now in neither notes nor the agent's input. This violates done-when 3 and is not covered by the proposed flag-only Java tests or shell happy-path tests (`plan.md:194-198`, `plan.md:214-217`). -> Make the shared leftover handler retain ownership of text until it is delivered or restored: re-evaluate the current session/run when the posted continuation executes, queue or route the text if another run has started, and preserve Stop/deletion handling. Specify a deterministic test for the ordering above, plus failure callbacks on either side of `buddy_done`, proving each message is delivered once or restored after Stop.

F2 [minor] Flushing every note also sends notification-rule notes live - The plan explicitly excludes notification-rule runs from changed behavior (`plan.md:22`, `decisions.md:54-60`), but the unconditional result-time flush joins and sends all `ses.notes` (`plan.md:125-127`). Today rule sends and user sends reach the same note collection (`app/src/main/java/app/buddy/assistant/ChatHub.java:262-271`, `app/src/main/java/app/buddy/assistant/ChatHub.java:299-301`, `app/src/main/java/app/buddy/assistant/ChatHub.java:942-951`). Keeping the rule branch as a plain note at insertion therefore does not preserve its behavior at the next result. -> Preserve the stated boundary when draining notes, retaining enough source information to leave rule notes on their existing path; include a mixed rule/user-note case. Alternatively, explicitly resolve this scope change in the plan and decisions rather than claiming rule delivery remains unchanged.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted. Text the agent never got now has one owner, `leftover(ses, text)`, which decides when it runs, not
when the text was taken. A static `placeLeftover` makes the route and the state change. Deleted chat
(`sessions.all.contains`): dropped. Running (another run started, or this one still goes): back into `ses.notes`.
Stopped: appended to `ses.unsent`. Otherwise `sendNoteAsMessage`. `buddy_done`'s inline Stop/continuation code
(`ChatHub.java:885-902`) becomes a post of `leftover`. Today's `if (!ses.running)` skip, which dropped the text, is
gone. `sendLive`'s failure (non-zero exit, or `Helper.run` throwing) calls the same `leftover` on the main thread. In
your ordering, whichever of A's failure and B's continuation runs first starts a run, and the other joins that run's
notes. Two small fixes the same pass turned up:
- the failure path passes the raw text, so the "Added while you were working" prefix isn't doubled;
- `sendLive` no longer sets "Thinking…", since `system/init` sets it (`ChatHub.java:749-750`). A failed `say`
  leaves no stale status.

New test `LeftoverTest` (plain JUnit; `ChatHub` statics already load there, see `BypassTest`) covers the routes, both
orderings around `buddy_done` (each of A and B sent exactly once, nothing left), and the Stop variant (nothing sent,
both in `unsent`). Updated: plan.md Approach 4, Step 4, Test plan row 3; decisions.md entry "one owner".

F2: accepted, using your alternative: the scope is resolved, not tagged. A rule run never reaches a running chat.
Triggers starts one only when `runningCount() == 0` (`Triggers.java:366`), moves to a new chat if the rule's chat is
running (`:541`), and offers "Check again" only on an idle chat (`canRecheck`, `:717`). So every note is the user's
own. The plan drops the `prompt == null` carve-out: `send` is unchanged, and its running branch already calls
`addNote`. There is no mixed rule/user case to test. Updated: plan.md Non-goals, Approach 4 ("Rule runs"), Step 4,
Open questions; decisions.md entry "No rule carve-out" (revises "Scope of the live path").

## Round 2 - Plan Reviewer (gpt-6-astra)

Round 1 F1 is addressed by deciding ownership when the continuation executes and testing both callback orderings. Round 1 F2 is resolved by the trigger guards: `Triggers.java:366`, `:541` and `:717-718` support the Planner's explanation.

F1 [major] Closing input can recreate the idle-message delay on the next background turn - The acknowledged close/say race is not limited to a millisecond delivery delay (`plan.md:268-270`). Concrete ordering: `say` writes user message A before the close acquires its lock; the old turn's zero-task `result` removes the FIFO and closes stdin (`plan.md:81-82`, `:95-97`); Claude processes queued A, starts another long background job, and ends that turn. By the plan's own tested EOF behavior (`plan.md:57-58`), the process can remain alive waiting for that job. Message B, sent now while the turn is over, gets "no live input"; `sendLive` clears `turnOver`, and `leftover` puts B into notes because the process is still running (`plan.md:125-140`). With no phone tool call, B waits for the background notification or process exit again. The FIFO is only created before launching a process (`plan.md:184-185`), so the new turn's task events cannot restore live delivery. This violates done-when 1 even though A and B are eventually retained. -> Revise the input-close lifecycle so work accepted before closure cannot leave a live process waiting on new background jobs without a usable message channel. Add a deterministic shell case that accepts A before the zero-task close, has A start another background job and end its turn, then sends B and proves it is received before that job completes. Include the equivalent case if a queued task-notification turn starts further background work; do not treat delivery at eventual process exit as satisfying the seconds requirement.

Review was against the plan and repository code; no implementation or device tests were run.

VERDICT: REVISE

### Planner response (round 2)

F1: accepted - the input no longer closes at the zero-task `result`. `stream_claude` tracks three things: whether a
turn is in progress (`system/init` starts one, `result` ends it), the task count, and whether the run has ever listed
a task. It closes the input once the agent is idle with no tasks listed:
- at once, if the run never listed a task (today's run-end timing);
- otherwise after `QUIET=3` s with no line from the agent. Any line restarts the wait, and a `system/init` cancels
  it.

`say` now also touches `$S/in.said` under the lock. A close attempt that finds the marker removes it and keeps the
input open for another wait. In your ordering, A's `say` leaves the marker, so the close doesn't happen. Claude
starts A at once with `system/init`, A's turn lists its job, and the input stays open, so B's `say` succeeds.

A finished job's notification queued at a zero-task `result` works the same way. The run has listed a task, so the
close waits, and the notification turn starts inside the wait. If it starts a job, its `result` lists it.

The one assumption left is that Claude Code starts work it already holds within 3 s of a `result`. A `say` during
the wait doesn't depend on it, because of the marker. The Verifier measures the gap (new real-agent step a2), and
Risks says what happens if it ever fails: today's delay, nothing lost.

Cost: runs with jobs or live messages end 3-6 s after their last `result`.

Tests: the `live` stand-in mode is replaced by two deterministic run.sh cases, which emit their lines with no delay
and fail every time against round 1's close:
- `claude_queued`: A is accepted before a zero-task `result`, A's turn lists a job, and B's `say` exits 0 and is
  echoed. The stand-in's job ends only after it reads B.
- `claude_notify`: a job ends mid-turn, the queued notification turn starts a new job, and a `say` then exits 0 and
  is echoed.

Both end about two `QUIET`s after their last `result`, which proves the wait gives way.

Rejected alternative: counting acknowledged lines with `--replay-user-messages`. It is untested and doesn't cover
notifications.

Updated: plan.md Approach 1 and 2, "Why this meets the brief", Alternatives, Steps 1-2, Test plan rows 1 and 3, the
real-agent check (a2, b), and Risks ("Queued work that starts late" replaces the close/say window; new "Runs with jobs
or live messages end a little later"). decisions.md: new entry "The input closes after a quiet idle spell".
