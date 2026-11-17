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

## Round 3 - Plan Reviewer (gpt-6-astra)

Round 1's ownership and rule-routing findings remain resolved. Round 2 F1 is improved for promptly started queued turns, but remains open at the input-close boundary.

F1 [major] The quiet timer still closes input with accepted work pending - `close_input` deletes `in.said` on its first attempt without observing that Claude consumed or started the message, then closes on the next quiet attempt (`plan.md:82-89`, `:213-216`). The marker therefore postpones closure once; it does not make the interval between an accepted line and `system/init` safe independently of timing as claimed at `plan.md:97-98` and `:315-316`. Concrete ordering: `say A` succeeds before a zero-task result; the first close attempt consumes A's marker; Claude emits no further line for longer than the remaining quiet waits; the next attempt removes the FIFO and closes fd 3; Claude then processes A, starts a long background job and ends its turn. By the EOF behavior recorded at `plan.md:57-58`, the process can still wait for that job. B sent after that turn now gets exit 3 and is returned to notes (`plan.md:142-157`), so B again waits for a phone tool or job completion. A queued notification has the same problem, without even a send marker. The plan expressly admits this outcome (`plan.md:314-318`), which still violates done-when 1; increasing the constant or measuring one fast transition does not establish that closure is safe. Both new stand-ins deliberately start the queued work immediately (`plan.md:227-235`, `:257`), so they cannot catch it. -> Keep evidence of accepted pending input until the stream confirms its processing, and establish the equivalent closure condition for queued task notifications; a quiet timer may delay cleanup but must not be the evidence that pending work is absent. Verify the provider events needed for that condition before coding. Extend the deterministic queued and notification cases with a gap exceeding the close grace period before the next `system/init`, then have that turn start another job and require B to arrive before the job completes. If the provider exposes no usable completion condition, return that specific lifecycle limitation to the orchestrator for a design decision rather than treating the original delay as an accepted implementation risk.

Review covered the plan, prior responses and relevant runner/app code. No implementation or device tests were run; the delayed-start sequence above is a trace of the proposed state machine.

VERDICT: REVISE

### Planner response (round 3)

F1: accepted. No timer decides the close any more: Claude Code's own events do. I checked the events first, with
Claude Code 2.1.326 and Haiku in Buddy's Linux, a FIFO harness and nine timestamped runs (plan.md Facts):
- With `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1`, Claude Code sends `session_state_changed` `running`/`idle`. `idle`
  never shows while work is queued:
  - lines queued at a `result` ran next, `result` → `system/init`, with no `idle` between (runs d, e);
  - a job that ended mid-turn was folded into that turn (c).
- A job that ends while idle gives `task_notification` and `background_tasks_changed []`, then `running` →
  notification turn → `idle` (b, f, h).
- A line that carries a `uuid` gets `command_lifecycle` `queued`/`started`/`completed` for it, before its turn's
  `idle`, also on `--resume`. Notification turns get none (g, h, i).

New close rule in `stream_claude`:
- Every line buddy-run writes carries a uuid, listed in `$S/in.sent`. Uuids reported `completed` go to `$S/in.done`.
- Under the lock, the input closes only when all of these hold:
  - the latest state is `idle`, with no `task_notification` or `background_tasks_changed` since;
  - no job is listed;
  - every sent uuid is done.
- The `QUIET` wait and the `in.said` marker are gone. So is round 2's 3-6 s later end: runs close at the `idle`
  right after their last `result`.

Your trace:
- A's uuid is in `in.sent` and not `completed`, so no close happens, however long A waits. A's turn lists its job, and
  B's `say` succeeds.
- Queued notification: the job's end clears `idle`. The next `idle` comes after the notification turn, which lists any
  job it starts.

A Claude Code without these events closes at its first `result` (a gate): today's behaviour, with no live input. What
is left is a liveness risk, not loss. If a later version broke these signals, the process would sit idle until the
next message or Stop (Risks).

`--replay-user-messages` was the other candidate. It doesn't echo a notification turn that starts while idle (b, f).

Tests:
- `claude_queued` and `claude_notify` now hold the next `system/init` back for 7 s. That is longer than round 2's
  whole close, so round 2 fails both.
- In `queued`, A sits unread in the FIFO after `idle` has been sent, which is the hardest case.
- In both, the late turn then starts a job, and B or ping must get through before that job ends.
- New `legacy` case for the gate.
- Real-agent check: (a2) checks that every uuid in `in.sent` is in `in.done` and the input is still open; (b) expects
  `buddy_done` within 3 s of the last `result`.

Nothing goes back to the orchestrator: the provider gives the condition.

Updated: plan.md Facts, Approach 1 and 2, "Why this meets the brief", Alternatives, Steps 1-2, Test plan rows 1 and 3,
the real-agent check, Risks and Open questions. decisions.md: new entry "The input closes on Claude Code's own word
that nothing is pending".
