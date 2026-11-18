# Decisions: 023-idle-chat-delivers-messages
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-17 - How a message reaches an agent whose turn is over but whose process lives on
- Options: (a) Claude Code in streaming-input mode on a FIFO that buddy-run holds, plus a `buddy-run say` command;
  (b) a second `claude --resume` process for the new message; (c) stop the idle process and resume; (d) the app writes
  the FIFO itself from Java; (e) buddy-run polls or long-polls the app for notes.
- Choice: (a). Claude Code's own queue then orders user lines and background-task notifications, so nothing collides
  or is lost (done-when 3). Checked on 2.1.326: a line sent while idle with a job pending started a turn at once.
  (b) forks the conversation the live process keeps writing, (c) kills the job and its notification, (d) can't share
  a lock with the shell side (Java FileLock is fcntl, busybox flock is flock(2)), and (e) adds an endpoint and an
  hour of polling.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - "Working" stays tied to the process; a separate flag marks the turn as over
- Options: (a) redefine `ses.running` as "turn in progress"; (b) keep `running` and add a runtime `turnOver` used only
  to route messages.
- Choice: (b). (a) still needs a way into the live process. It also changes Stop (which must still end a background
  job), keep-awake, the background screen, run-end notifications, the watch's run-over check and schedules, none of
  which the brief asks for. `turnOver` is set by `result` and cleared by `system/init`, `assistant`, `startRun` and
  `buddy_done`.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - When buddy-run closes Claude Code's input
- Options: (a) at a `result` when Claude Code's latest `background_tasks_changed` lists no tasks, and at the stream's
  end; (b) guess pending jobs from the process tree; (c) an idle timeout.
- Choice: (a). Claude Code reports its full task list. After EOF it finishes queued work, including a pending
  notification turn, then exits, so runs end as they do today. A version that sends no task events closes at the
  first `result`, which is today's behaviour. (b) and (c) are guesses.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Close/say race: a shared lock and a fallback to today's path
- Options: (a) `say` and the close both take `flock $S/in.lock`; the FIFO's existence means "open for messages"; on
  failure (`say` exits 3) the app falls back to the note or leftover path; (b) the app tracks unacknowledged live
  messages until a turn starts.
- Choice: (a). A line written before the close is read before EOF, and after the close `say` refuses. Every outcome
  ends in delivery: live, at the next tool call, or as the next message at process end. (b) is more state for the
  same result.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Notes left at turn end go live too
- Options: (a) at `result` with the process alive, send untaken notes as a new turn (today's "Added while you were
  working" prefix); (b) leave them for `buddy_done` as today.
- Choice: (a). A note written during the agent's last non-phone steps is the same bug as the brief's evidence: it
  would wait for the background job. For providers without live input, `say` exits 3 and the notes take the
  `buddy_done` path they take today.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Scope of the live path
- Options: everything that becomes a note today, or only the user's own messages; whether attachments go live.
- Choice: only the user's own messages (`addNote`, and `send` with `prompt == null`: bubble, full chat, watch, live
  voice). Notification-rule runs keep notes, because they carry someone else's text and the brief is about "my
  messages". Attachments are still refused while the process is alive, as they are today. Codex, OpenCode,
  Antigravity and Grok get no live input: their process ends with their turn, so the existing path already delivers
  within seconds.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Text the agent never got has one owner until it lands (plan review round 1, F1)
- Options: (a) keep today's posted continuation, which skips the text if another run has started; (b) one
  `leftover(ses, text)` that decides when it runs, through a static `placeLeftover`: deleted chat, dropped; running,
  back into that run's notes; stopped, back to the box; else a new message; (c) track unacknowledged live messages
  by run id until a turn starts.
- Choice: (b). A failed live `say` and the notes `buddy_done` drains can then arrive in either order: the first
  starts a run, and the second joins its notes, so each is delivered once. It also closes today's small hole where a
  run started between `buddy_done` and its post. (a) loses a message in the reviewer's ordering. (c) adds per-run
  state for the same outcome. A failed live send passes its raw text, so the "Added while you were working" prefix
  isn't added twice. `sendLive` sets no status: `system/init` sets "Thinking…", so a failed `say` leaves no stale
  status.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - No rule carve-out on the live path (revises "Scope of the live path"; plan review round 1, F2)
- Options: (a) tag notes by source and keep rule notes off the live path and the turn-end flush; (b) no carve-out:
  every note takes the same path.
- Choice: (b). A rule run never reaches a running chat. Triggers starts one only when no chat is running
  (`Triggers.java:366`), picks a new chat if the rule's chat is running (`:541`), and offers "Check again" only on an
  idle chat (`canRecheck`, `:717`). So every note is the user's own, and `send` needs no `prompt == null` branch. (a)
  adds state for a case that doesn't occur. The rest of the scope entry stands: attachments are still refused, and
  only Claude Code gets live input.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The input closes after a quiet idle spell, not at the zero-task `result` (revises "When buddy-run closes Claude Code's input" and "Close/say race"; plan review round 2, F1)
- Options: (a) close at the zero-task `result` (round 1); (b) close once the agent is idle with no tasks listed:
  at once if the run never listed a task, else after `QUIET=3` s with no line from it; under the lock, a `say` marker
  (`$S/in.said`) since the last try keeps the input open for another wait; (c) count acknowledged lines
  (`--replay-user-messages`) and close only when none is pending; (d) never close, stop the agent when idle.
- Choice: (b). Work that got in before a close (a racing `say`'s line, a finished job's notification) runs after the
  `result` and may start a new job. With (a), that job runs with the input shut, and messages wait for it again.
  Claude Code starts held work as soon as a turn ends, with `system/init` first, so under (b) that work begins with the
  input open, and a job it starts keeps it open. The marker makes a `say` during the wait safe without timing. The
  fast path keeps today's run-end timing for runs that never list a job. (c) is an untested flag and says nothing
  about queued notifications, which still need a wait. (d) has the same timing question and kills instead of
  letting the agent finish. Cost: runs with jobs or live messages end 3-6 s after their last `result`. The
  remaining assumption (held work starts within 3 s) is measured by the Verifier.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The input closes on Claude Code's own word that nothing is pending (revises "The input closes after a quiet idle spell"; plan review round 3, F1)
- Options: (a) round 2's quiet wait with a `say` marker; (b) close when Claude Code's latest
  `session_state_changed` (opt-in, `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1`) is `idle`, with no `task_notification`
  or `background_tasks_changed` since, no job listed, and every line buddy-run wrote (each with a `uuid`, listed in
  `$S/in.sent`) reported `command_lifecycle` `completed`; a gate closes at the first `result` when those events are
  missing; (c) `--replay-user-messages` echoes as the acknowledgement; (d) send the limitation back to the
  orchestrator for a decision.
- Choice: (b). The Planner tested it on 2.1.326 (runs a-i in plan.md Facts):
  - `idle` never shows while work is queued;
  - a job that ends while idle is always followed by its notification turn and a new `idle`;
  - every uuid line gets `completed` before its turn's `idle`, also on `--resume`.

  So both kinds of pending work, a line and a notification, show in the stream, and no timer decides the close. Runs
  also end with the `idle` right after their last `result`, so round 2's 3-6 s delay is gone. (a) closes on
  silence, which isn't evidence. (c) doesn't echo a notification turn that starts while idle, and the echoes would
  reach the app. (d) isn't needed, because the provider gives the condition. What's left is a liveness risk, not
  loss: if a later version broke these signals, the process would sit idle until the next message or Stop.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - No live send after Stop (plan review round 4, F1)
- Options: (a) one static gate `liveInput(ses)` (`running && turnOver && outcome != "stopped"`), used by the
  `addNote` branch and by a static `turnEndNotes(ses)` that takes notes for the turn-end flush only when the gate
  holds; (b) check Stop inside `sendLive` and route the text back through `leftover`; (c) Stop clears `turnOver`, and
  `result` doesn't set it while stopped.
- Choice: (a). After Stop nothing is taken from `notes`, so `buddy_done`'s existing path puts the text back in the
  box with today's "not sent" line, with no new lifecycle state. Stop and events both run on the main thread, so a
  `result` handled after the tap always sees "stopped". The two statics are what make this a plain JUnit case. (b)
  takes the notes and then puts them back. (c) splits the rule over two places, one of them `stop()`, which plain
  JUnit can't reach. A `say` dispatched before the tap is a sent message whose turn the user stopped, as with any
  message sent just before Stop.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Coder: choices the plan left open
- `say` writes with `timeout 5 tee "$S/in"` (opening the FIFO blocks without a reader); `close_input` and `say` take the lock
  with `timeout 5 flock 9` on fd 9, because Buddy's busybox `flock` has no `-w`, and the close runs in `stream_claude`'s
  own shell (not a subshell) so it closes fd 3 for real.
- `stream_claude` gets `fifo_reset` (removes `in`, `in.sent`, `in.done`, `in.lock`) before each pipeline, which is the
  "start of `ask`" cleanup; `stop` removes `in`, `in.sent`, `in.done`.
- `buddy_done` now posts `leftover` for stopped chats too (it was inline); the "not sent" line and `unsent` update happen in
  that post, still before `changed()` returns to the watch link's own post.
- Local checks: Gradle's JVM won't start here, so the app tests ran with a hand-made javac/JUnit script (`/tmp/buddy-build-023/check.sh`,
  copied from feature 017). `BypassTest.everyShortLabelOfATapReachesTheDecision` fails only there (it needs Gradle's mockable android.jar).

## 2026-11-17 - Coder: a late failed live send (code review round 1, F1)
- Options: (a) a new `LEFT_LIVE` route in `placeLeftover`, given the run the send went into; (b) `turnEndNotes`-style
  flush of notes at the callback.
- Choice: (a). The run id is the only extra state (`ses.runId` already exists); same-run failures still go to notes, so a
  refused send isn't retried into the input that refused it, and a retry gets the new run's id, so it can't loop.
- By: Coder (claude-sonnet-5-5)
