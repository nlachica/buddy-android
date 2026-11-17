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
