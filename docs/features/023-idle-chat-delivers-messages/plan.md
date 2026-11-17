# Plan: 023-idle-chat-delivers-messages
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal

When an agent has ended its turn but its process is still alive because background jobs are pending, a message the user
sends (bubble, full chat, or any path that today turns into a held "note") goes to the agent at once as a new user turn.
While a turn is in progress, messages keep today's behaviour: they are appended as `[BUDDY_USER_UPDATE]` to the next
phone tool result. When a background job finishes later, its notification turn and the user's turn are both handled,
one after the other, and neither is lost. This must hold for every provider Buddy runs.

Done when (from the brief):
1. With a background task running and the turn ended, a message sent in the bubble or full chat starts a new turn within seconds.
2. During an in-progress turn, messages still arrive as updates on the next phone tool result (no regression).
3. When the background task later finishes, its notification and the user's turn don't collide or get lost.

Non-goals:
- Changing what "working" means in the UI. The pill, Stop, keep-awake, the background screen, run-end notifications,
  the watch's run-over check and schedules still follow the process (`ses.running`). Stop must still be able to end a
  background job.
- Attachments sent while the process is alive: still refused with today's "still working" message.
- Notification-rule runs. They never reach a running chat, so nothing changes for them (see "Rule runs" in
  Approach 4).
- Live input for Codex, OpenCode, Antigravity and Grok. Their processes end with their turn (see Facts), so they need
  no channel.
- Timers, status text or a new indicator for the "turn over, jobs pending" state.

## Design

### Facts this rests on (research.md is empty: the Planner checked these; sources given)

- **Root cause confirmed.** In chat a35d19e5 (`~/.claude/projects/-root--buddy-work/a35d19e5-…jsonl`):
  - Line 11679, 05:38:55: the agent's final text, after it started a `run_in_background` waiter (line 11671).
  - Lines 11680-11682, 05:50:20: the same Claude Code process takes the `<task-notification>` from its own queue and
    runs a new turn. The process stayed alive between turns, so Buddy kept the chat `running`.
  - Line 11733, 06:11:06: the user's correction ("Wait, wait, I don't want that to be routine allowed…") arrives
    appended to the `update_buddy` tool result as `[BUDDY_USER_UPDATE]`.

  Buddy's side of that:
  - `ChatHub.send` turns any message to a running chat into a note (`ChatHub.java:299-301`).
  - `running` is cleared only on `buddy_done`, which buddy-run sends when the agent process exits
    (`ChatHub.java:876-879`, `buddy-run:313`).
  - Notes reach the agent only in a phone tool's result (`Tools.java:404-410`, `ChatHub.takeNotes` at :1021), or
    as the next message after `buddy_done` (`ChatHub.java:885-904`).
  - Claude Code is started as `claude -p "$MSG"` (`buddy-run:419,437`). Its stdin is `/dev/null`
    (`docs/features/002-runner-hang/plan.md:45`), so nothing can be passed to the live process.
- **Claude Code streaming input**, tested by the Planner on 2026-11-17 with Claude Code 2.1.326 and Haiku: `claude -p
  --input-format stream-json --output-format stream-json --verbose`, stdin a FIFO kept open by the test.
  - The first message `{"type":"user","message":{"role":"user","content":"…"}}` started a turn, which started
    `sleep 40` in the background and ended with a `result`.
  - A second user line written 3 s later, with the job still running, started a new turn at once: reply 2 s
    later, then a second `result`.
  - The stream reports background jobs:
    - `system/background_tasks_changed` carries the full current list (`"tasks":[…]`, `[]` when none);
    - `system/task_started`, `task_updated` and `task_notification` mark the job's steps.
  - Every turn begins with `system/init` and ends with `result`.
  - With stdin closed while the job was still running, Claude Code waited for it, ran the notification turn
    (`system/init` → `assistant` → `result`), then exited 0.
  - buddy-run already uses `claude -p --input-format stream-json` for the model list (`buddy-run:96`).
- **Other providers end with their turn.**
  - `codex exec … </dev/null` (`buddy-run:494-500`), `opencode run`, `agy -p` and `grok -p` are one-shot.
  - Their `result` (synthesized from `turn.completed` etc.) is followed by the process exit and `buddy_done`.
  - The existing "notes the agent never got become the next message" path (`ChatHub.java:885-904`) then delivers
    within seconds.
- Busybox `flock` and `mkfifo` are in Buddy's Alpine (`/usr/bin/flock -> /bin/busybox`). App unit tests are plain
  JUnit with org.json (`app/build.gradle.kts:91-92`), no Robolectric. CI runs `linux/test/run.sh` and
  `testGithubDebugUnitTest` (`.github/workflows/build.yml:20,63`).

### Approach

**1. A live input for Claude Code (buddy-run).** Claude Code runs in streaming-input mode, reading from a FIFO
`$S/in` that buddy-run creates just before the pipeline: `ended run_claude <"$S/in" | stream_claude`, for both the
first try and the "no conversation found" retry. `run_claude` drops the prompt argument and adds
`--input-format stream-json`.

`stream_claude` does the following:
- Opens the FIFO write-only on fd 3. This waits for the agent's read end; SIGPIPE is ignored, so a write to a gone
  agent only fails.
- Writes `$MSG` as the first user line.
- Tracks the latest `background_tasks_changed` task count.
- At each `result` with zero tasks, and at the end of the stream, it closes the input: under `flock "$S/in.lock"`,
  it removes `$S/in` and closes fd 3. Claude Code then finishes anything queued and exits, as it does today.
- While a `result` arrives with tasks listed, the input stays open. It can only stay open for the life of those
  jobs.

Claude Code versions that don't send `background_tasks_changed` count zero tasks, so they close at the first
`result`: today's behaviour.

**2. `buddy-run <token> say <session> <text>`**, a new command next to `stop`:
- Takes the same lock.
- If `$S/in` still exists (it is a FIFO) and `$S/pid` is alive, writes one user line to it, under `timeout 5`, and
  exits 0.
- Otherwise it exits 3 ("no live input").

The lock makes "close" and "say" exclusive:
- A line written before the close is read before EOF, so the agent gets it.
- After the close, `say` refuses, and the app falls back.

`stop` and the start of `ask` remove a stale `$S/in`.

**3. The app knows when the turn is over (ChatHub).**
- A runtime flag `Sessions.Session.turnOver`.
- A small static `ChatHub.trackTurn(ses, type, subtype)` keeps it, called at the top of `onEvent`:
  - `result` sets it while `running`;
  - `system/init` and `assistant` clear it (a new turn started, e.g. a background job's notification);
  - every other event leaves it as it is (the `task_*` and `background_tasks_changed` events, `rate_limit_event`,
    and `system/model` and `buddy_context` from Codex);
  - `startRun` and `buddy_done` also clear it.

**4. Live delivery (ChatHub).**
- **The branch.** In `addNote`, when `ses.running && ses.turnOver`, the message takes a new private
  `sendLive(ses, text, false)` instead of becoming a note.
  - Every message to a running chat reaches `addNote`: bubble dictation directly, and the rest through `send`'s
    running branch (`ChatHub.java:299-301`), which refuses attachments first. That covers the full chat, the watch
    (`WatchLink.deliver` → `send`) and live voice (`CodexLive` → `send`).
  - `send` itself is unchanged.
- **Rule runs** never reach that branch:
  - Triggers starts a rule run only when no chat is running (`Triggers.java:366`);
  - it picks a new chat if the rule's chat is running (`:541`);
  - "Check again" shows only on an idle chat (`canRecheck`, `:717`).

  So notes have one source, the user, and there is no rule case to keep apart.
- **`sendLive(ses, text, note)`**:
  - shows the USER bubble, unless `note` (a note is already shown);
  - clears `turnOver` (a turn is starting: later messages are updates to it, as in done-when 2);
  - runs `Helper.run(app, gone, "say", ses.id, forAgent)`. `forAgent` is `text`; for a note it gets today's prefix,
    "(Added while you were working; if it asks something, answer it:) ";
  - on an exit status other than 0, or when `Helper.run` throws, it calls `leftover(ses, text)` on the main thread,
    with the raw text, so a later `sendNoteAsMessage` doesn't add the prefix twice;
  - sets no status: the new turn's `system/init` sets "Thinking…" (`ChatHub.java:749-750`), so a failed `say`
    leaves no stale status.
- **`leftover(ses, text)`, one owner for text the agent never got** (main thread).
  - Used by `sendLive`'s failure and by `buddy_done`. In `buddy_done` it replaces the inline Stop/continuation code
    (`ChatHub.java:885-902`): the notes are drained as today, and the post (still before `changed()`, for WatchLink)
    calls `leftover`.
  - It decides when it runs, not when the text was taken, through a static `placeLeftover(ses, text, present)`
    that returns a route and makes the state change:
    - chat deleted (`present` is `sessions.all.contains(ses)`): dropped, as a deleted chat's notes are today;
    - `ses.running` (another run started meanwhile, or this one still goes): back into `ses.notes`. The next phone
      tool result, the next turn-end flush or that run's `buddy_done` takes it;
    - stopped (`"stopped".equals(ses.outcome)`): appended to `ses.unsent`, and `leftover` adds today's "not sent"
      line;
    - otherwise: `leftover` calls `sendNoteAsMessage`, which starts the run at once.
  - Today's `if (!ses.running)` skip is gone. It dropped the text when another run had started first; that text now
    joins the run's notes.
  - The reviewer's ordering (round 1 F1):
    - A is sent live; B becomes a note; `buddy_done` drains B and posts its `leftover`; A's failure arrives on
      either side of it.
    - Whichever runs first finds the chat idle and starts a run with its text. The other finds it running and goes
      into that run's notes.
    - Each message reaches the agent once. After a Stop, both go back to the box.
- **Notes left at turn end.** In the `result` case, when `running`, notes the agent never took are joined, cleared
  and passed to `sendLive(ses, joined, true)`.

  This fixes the case where the user wrote during the agent's last non-phone steps and then waited an hour. For the
  other providers `say` exits 3. `leftover` then puts the notes back (the process still runs) and `buddy_done` sends
  them, as today.

Why this meets the brief:
- Done-when 1: the agent gets the message within a proot exec, about a second.
- Done-when 2: nothing changes while `turnOver` is false.
- Done-when 3: Buddy never starts a second process for the chat. Claude Code's own queue orders user lines and task
  notifications (`queue-operation` enqueue/dequeue in the transcript), and the input stays open until a turn ends
  with no jobs pending. A live send that fails stays with `leftover` until it is delivered or back in the box.
- Every provider: the app logic has no backend check. Claude Code gets the live path. The others fall back to the
  existing path, which already delivers right after their turn, because their process ends with it.

Alternatives considered:
- A second `claude --resume` process for the new message: it forks the conversation the live process keeps
  appending to, and the live process's notification turn runs on stale context (a collision).
- Stop the idle process and resume: kills the background job and loses its notification.
- Redefine `running` as "turn in progress": still needs a way into the live process, and it changes Stop, keep-awake,
  the background screen, run-end notifications, the watch and schedules.
- The app writes the FIFO from Java: Java's `FileLock` (fcntl) doesn't exclude busybox `flock`, so it can't close the
  close/say race.
- buddy-run polls or long-polls the app for notes while idle: a new endpoint plus an hour of polling, for no gain over
  a push.
- Process-tree or idle-timeout guesses for "jobs pending": fragile. Claude Code reports its task list.

## Steps

1. **buddy-run: Claude Code reads its messages from a FIFO** (`linux/buddy-run`).
   - In `run_claude`: `-p --input-format stream-json` with no prompt argument.
   - In the run section, before both Claude pipelines: `rm -f "$S/in"; mkfifo "$S/in"`, then
     `ended run_claude <"$S/in" | stream_claude`.
   - In `stream_claude`:
     - `trap '' PIPE`;
     - if `[ -p "$S/in" ]`: `exec 3>"$S/in"` and write `$MSG` as the first user line (jq);
     - track `bg` from `background_tasks_changed`;
     - at `result` with `bg=0`, and after the loop, close the input under `flock "$S/in.lock"` (rm the FIFO, close
       fd 3).
   - With no FIFO (the `BUDDY_TEST_STREAM` fixture mode), the input plumbing is skipped, so the recorded fixtures
     run unchanged.
   - In `stop`, also `rm -f "$S/in"`.
   - `HELPER_VERSION=33`, as feature 002 bumped it.

   Tests (`linux/test/run.sh`): link `flock` and `mkfifo` into `$H/bin`. Add a stand-in mode `live` to the `agent`
   script:
   - read the first stdin line and echo its content as an assistant text;
   - emit `background_tasks_changed` with one task, then `result`;
   - block on the next stdin line, echo it, then emit `background_tasks_changed` `[]` and `result`;
   - `cat >/dev/null` until EOF, then exit 0.

   Add a mode `noinput`: `result` with no tasks, then `cat >/dev/null`, exit 0. It must end within 10 s, which proves
   the input is closed at a no-jobs `result`. Existing cases must pass unchanged.
2. **buddy-run `say`** (`linux/buddy-run`, usage line and header comment):
   - `say)`: `valid_sid`, lock with `flock -w 5`;
   - `[ -p "$S/in" ]` and the pid alive, else exit 3;
   - `jq -cn --arg t "$TEXT" '{type:"user",message:{role:"user",content:$t}}'` written via `timeout 5`, exit 0.

   Tests, using the `live` stand-in, with the wrapper in the background:
   - wait for the first `result` in the events;
   - `buddy-run test say run ping` must exit 0;
   - the wrapper ends within 20 s;
   - a second `say` exits 3;
   - the events match `fixtures/run/claude_live.expected.jsonl`: "hi" and "ping" texts, two results, `buddy_done`.
3. **App: turn tracking.** Add `boolean turnOver` to `Sessions.Session` (runtime, comment). In `ChatHub`, add
   `static void trackTurn(Sessions.Session, String type, String subtype)`, called at the top of `onEvent`, and clear
   the flag in `startRun` and `buddy_done`.

   Test: `app/src/test/java/app/buddy/assistant/TurnTrackTest.java`, in the HelpersBoxTest style:
   - `result` sets it only while running;
   - `system/init` and `assistant` clear it;
   - `system/background_tasks_changed`, `system/task_notification`, `system/model`, `rate_limit_event` and
     `buddy_context` leave it.
4. **App: live delivery** (`ChatHub.java`):
   - `static int placeLeftover(Sessions.Session, String, boolean present)` and `leftover`, replacing the inline
     handling in `buddy_done`;
   - `sendLive`, falling back through `leftover`;
   - the `addNote` branch (`send` unchanged);
   - the notes flush in the `result` case.

   No string changes needed. The bubble's "Got it: X will take it into account" toast fits both paths.

   Test: `app/src/test/java/app/buddy/assistant/LeftoverTest.java`, plain JUnit (`ChatHub` statics already load
   there: `BypassTest` calls `ChatHub.clip`):
   - routes: deleted, nothing changes; running, text in `notes`; idle and stopped, text appended to `unsent`
     (`"\n"`-joined after an earlier one); idle, send.
   - the F1 ordering, both ways. Start from a session just after `buddy_done`: idle, B drained, A's live send failed.
     Call `placeLeftover` for A then B, and again for B then A. The test plays the hub's part: on "send" it records
     the text and sets `running`; at a run's end it clears `running` and drains `notes` through `placeLeftover`.
     Each of A and B is sent exactly once, and nothing is left.
   - the same with `outcome` "stopped": nothing is sent, and `unsent` holds both.
5. **Docs** (`linux/GUIDE.md:51`, fork sentence): "When the agent has answered but still waits on a background job
   (Linux tools), what you send goes to it right away as a new message."

## Test plan

| Done-when | Proof |
|---|---|
| 1. New turn within seconds | Two run.sh cases. `claude_live`: `say` while a task is listed reaches the agent, and the stand-in echoes "ping". `noinput`: a no-jobs `result` closes the input and the run ends. Then the real-agent check below (step a), and on the phone in a Claude chat with Linux tools: ask it to start `sleep 300; echo done` with `run_in_background` and end its turn. Send "what's 2+2?" from the bubble, then from the full chat. Each gets an answer within ~5 s while the chat still shows working. |
| 2. Updates mid-turn unchanged | `TurnTrackTest`: `turnOver` is false from `system/init` to `result`. Existing run.sh cases and unit tests pass. On the phone: during a turn that keeps using phone tools (e.g. "read the screen 4 times, 10 s apart"), send a note. The transcript JSONL shows it in a tool result after `[BUDDY_USER_UPDATE]`, and there is no new user turn. |
| 3. No collision or loss | `claude_live`: the input stays open while a task is listed and closes only after the `result` with `[]`. `LeftoverTest`: a failed live send on either side of `buddy_done` is sent once, or back in the box after Stop. Real-agent check, step b. On the phone: with `sleep 60` in the background, send a message at about 55 s and another while the notification turn runs. The transcript shows each user line and the `<task-notification>` enqueued and dequeued once, each answered in its own turn. The process then ends (`buddy_done`, chat idle). |
| Every provider | One Codex chat on the phone: a message sent right after its reply starts the next run at once (today's path, unchanged). The fallback is covered by `say` exiting 3 for a session with no FIFO (a run.sh assertion in step 2). |

Real-agent check (Verifier, in Buddy's Linux, Haiku, events to a file):
- Run the branch's helper, not the installed `/root/bin` copy: `BUDDY_TEST_EVENTS=/tmp/ev bash linux/buddy-run test
  ask t023 claude 1 "<start sleep 40 with run_in_background, reply STARTED>" haiku` in the background. Wait for the
  first `result` in `/tmp/ev`.
- (a) `bash linux/buddy-run test say t023 "Reply PONG"` exits 0, and a PONG `assistant` event follows within 10 s.
- (b) After about 40 s, the notification turn's events follow, then `buddy_done`. The run ends without further input.
- Then `rm -rf ~/.buddy/sessions/t023`. The phone MCP refuses the `test` token, and that is fine for this check.

CI: `bash linux/test/run.sh` and `./gradlew testGithubDebugUnitTest` (both in `build.yml`) must be green.

## Risks

- **Close/say window after a racing `say`.** If a `say` wins the lock just before a no-jobs `result` closes the
  input, the agent runs that message with stdin already at EOF. Messages during that new turn then fall back to today's
  path (delivered at process end). The window is milliseconds, and nothing is lost.
- **Older Claude Code.** If a version exits at stdin EOF without waiting for its background jobs, closing at the
  first `result` (no `background_tasks_changed` seen) would end jobs that today run to completion. Checked on 2.1.326
  only; Buddy installs the current Claude Code. The Verifier records the version.
- **fd 3 inherited by long-lived children of `stream_claude`** would hold the writer and stop EOF. It only starts
  short jq/curl processes, and the coder must keep it that way: open fd 3 only in `stream_claude`, never in the
  run section or `run_claude`.
- **A crash between the agent's death and `AGENT_END`.** A `say` then blocks on a FIFO with no reader. `timeout 5`
  ends it with exit 3, and the app falls back.
- **Sub-agent (helper) chats** use the same Claude plumbing. Their behaviour is unchanged unless the user writes to
  one while it waits on a job, and then it goes live too. That is harmless.

## Open questions

None blocking. The design choices that could have gone to Nicholas have recommended answers recorded in
`decisions.md`: the "working" UI stays tied to the process, and attachments are still refused. Rule runs never reach
a running chat, so they need no choice.
