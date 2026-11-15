# Plan: 002-runner-hang
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->
<!-- Revised after plan review round 1 (F1-F5) and round 2 (F1), see plan-review.md. -->

## Goal
A dead or killed agent, on any backend, must never leave a Buddy chat stuck in "working", and must never leave
a parent stuck waiting on its sub-agents. Done when: (1) the failure is reproduced (agent exits mid-stream,
`kill -9` of the agent, kill of the wrapper); (2) `buddy-run` always sends `buddy_error` + `buddy_done` when the
agent dies, the stream ends, or the wrapper gets TERM/INT/HUP, for every backend; (3) the app ends a run as
failed when its helper process is gone, and a sub-agent ending in any way releases the parent's wait; (4) every
sub-agent run has a hard ceiling that ends it as failed with a clear message; (5) `linux/test/run.sh` passes
with a new regression test for the dead-agent case, and CI is green.

Non-goals: the Codex-under-proot trigger itself; protocol changes beyond one optional field (`"fallback": true` on
the helper's own end-of-run `buddy_error`, F1); no new event types; an idle timeout for top-level chats; a ceiling
for top-level chats; explaining the "bash in state R" spin that was never reproduced (research.md, Summary); Java
unit-test infrastructure (there is none).

## Design
The research found three holes, and each layer below closes one.

**A. The stream ends when the agent or its shell exits, not at pipe EOF** (`linux/buddy-run`). This is the hour-long hang:
an orphan (MCP server, background job) keeps the agent's stdout open, so `while read` in `stream_<x>` never
gets EOF (research, Reproduction 3). Add one function next to `kill_tree`:

```bash
# An agent's run (run_<x>): its output, until it exits. Something it started (an MCP server, a background job)
# can keep that output open after it's gone; read to the end, the stream waited for that, and the chat stayed
# "working" with no buddy_done (feature 002). run_<x> gets a shell of its own, and this one only waits for it: the
# end line comes however that shell ends, killed included.
until_exit() {
  local end="buddy-run: agent exited $$ $RANDOM"
  { "$@" & wait "$!"; s=$?; printf '\n%s\n' "$end"; exit "$s"; } | awk -v end="$end" '$0 == end { exit } $0 != "" { print; fflush() }'
  return "${PIPESTATUS[0]}"
}
```

All 11 call sites change from `run_x | …` to `until_exit run_x | …` (`:924 :928 :937 :943 :948 :957 :961 :970
:974 :980 :984`). `PIPESTATUS[0]` is still `run_x`'s status, so nothing after them changes. The leading `\n`
keeps the end line separate even when the agent was killed mid-line. awk exits at the end line, so whatever is
downstream gets EOF: `stream_x` directly, or agy's `tee` and then `stream_agy`. The orphan is left without a
reader. In the app, proot's `--kill-on-exit` (`Linux.java:77`) kills it once buddy-run exits.
The brace group (the "watcher") does nothing but `wait`. When `run_x`'s own shell is killed (the reported zombie
`run_claude` subshell, review F2), `wait` returns 137 and the end line still goes out. Running `run_x` with `&`
changes nothing else: the run section is already a background subshell, so stdin is already `/dev/null` and SIGINT
is already ignored. Prototyped on this phone (bash 5.3, busybox awk). The reviewer's
`run_fake() { sleep 30 & kill -KILL "$BASHPID"; }` ends in 0.1 s with status 137. An agent that leaves `sleep 30 &`
holding stdout and then gets `kill -9` ends in 0.08 s with status 137. A clean exit with an orphan gives 0, and
`exit 3` gives 3. The agent's parent is `run_x`'s shell (bash doesn't exec it), so a fake agent's `kill -9 $PPID`
reproduces producer death. Not observed: a SIGKILL aimed at the watcher itself. For a sub-agent the ceiling still
ends that run; for a top-level chat, Stop does (`kill_tree` from the pid file takes the whole tree).

**B. `buddy_done` always, and `buddy_error` whenever the agent failed** (`linux/buddy-run`).
- `fallback_error()` goes next to `event()` (`:266`):
  `jq -cn --arg text "$1" --arg s "$SID" '{type:"buddy_error",text:$text,fallback:true,buddy_session:$s}' | post`.
  It is the helper's own account of how a run ended. The app shows it only if the run reported no error and
  wasn't stopped (C). Next to it:
  `cut_off() { fallback_error "The run was cut off: Buddy's helper in Linux was stopped."; }`. It has two uses: the
  TERM trap, and the wait check below.
- The traps go right **after** the busy check (`:269-272`), before the API-key check:
  `trap 'event buddy_done' EXIT`, and
  `trap '[ -n "${RUN:-}" ] && { touch "$S/cut-off"; kill_tree "$RUN"; rm -f "$S/pid"; }; cut_off; exit 1' TERM INT HUP`,
  then `rm -f "$S/cut-off"`. The run section skips its own error rule when `$S/cut-off` exists. It can reach that
  rule only after `kill_tree` has started, so the trap's error is the only one, and `claude_term` is deterministic.
  Otherwise the run section could also report the TERM it gets, racing the trap.
- *The wrapper checks how the run section ended* (review round 2). The run section gets an explicit `exit 0` as its
  last line, so every normal end gives 0: a finished or failed agent, a "not installed" exit, a last `post` whose
  `curl` failed. After `) &`:
  `RUN=$!; echo "$RUN" > "$S/pid"; wait "$RUN" 2>/dev/null; rs=$?; rm -f "$S/pid"; if [ "$rs" -ne 0 ]; then cut_off; fi`.
  A non-zero `rs` means the run section never reached its end and its error rule never ran: the pid in `$S/pid` was
  killed (137, or 143 on TERM) or the section aborted (`set -u`, 1). Explicit Stop TERMs that pid, and the app drops
  the fallback because the outcome is "stopped". On a wrapper TERM, the trap `exit`s, so this line never runs. If the
  section had already reported an error, the app drops the second error (outcome "failed"). Prototyped on the phone
  with a scratch copy of the structure: SIGKILL of the `$S/pid` process gave system, cut-off fallback, done. A
  `set -u` abort gave the same. A failing last command followed by `exit 0` gave system, then done.
- The last line `event buddy_done` (`:998`) goes away. Every exit after the traps sends done exactly once: the normal
  end, the "API key no longer in Buddy" exit (`:276`, which sends no done today), a `set -u` abort, a kill of the run
  section (cut-off error, then done), and a TERM (the agent's tree is stopped, then error, then done; prototyped).
  The test hook (`:914`) gets `trap - EXIT` so fixture output is unchanged. `( … ) &` and the pipeline subshells
  don't inherit the EXIT trap (checked).
- The busy branch stays as it is: the "still working" error, then `exit 0` with no done and no wait. The run still
  in progress owns the chat's done (review F5). Today's "Tap Stop first" flow is kept by the app's helper count (C).
- Error rule (replaces `:989-993`). Classification is by the agent's exit status only. 0 means finished, even with
  no result event (agy withholds its result, OpenCode can end without step_finish); non-zero means failed:

  ```bash
  [ -e "$S/cut-off" ] && exit 0   # the wrapper's TERM trap reports this run
  if [ "$status" -ne 0 ]; then
    if [ -s "$S/last-error.log" ]; then
      more=""; [ "$(wc -c < "$S/last-error.log")" -gt 4000 ] && more="… "
      why="$more$(tail -c 4000 "$S/last-error.log")"
    elif [ "$status" -gt 128 ]; then why="it was killed (signal $((status - 128)))."
    else why="it exited with status $status."; fi
    if [ -s "$S/last-error.log" ] && [ "$status" -ne 143 ]; then event buddy_error "$name failed: $why"
    else fallback_error "$name failed: $why"; fi
  fi
  exit 0   # the wrapper reads any other status as "this section was cut short"
  ```
  Stderr text with a status other than 143 is reported as today. Every other failure is a fallback: an empty
  stderr (Claude Code may exit 1 after its own error result, which the app then doesn't repeat) and any 143 (Stop
  TERMs the agent, and so does anything else; the app tells them apart). agy and grok still set `status=0` when
  they reported their own error.
- `HELPER_VERSION=31`.

**C. The app ends runs the helper can't end** (`Helper.java`, `ChatHub.java`, `Sessions.java`, `Helpers.java`).
- *Exit callback.* Add `Helper.run(Context, Runnable ended, String... args)`. The existing `run(c, args)` calls it
  with `null`. `ended` runs in a `finally` after `p.waitFor()` (or after a failed start), on the pool thread.
  For `ask`, that Process is the helper for the whole run. With `--kill-on-exit`, proot exits as soon as
  buddy-run's outer shell exits (even on `kill -9`), and `waitFor` returns. A delivered done always comes first:
  `McpServer.java:177-181` hands the event to `BuddyService.onEvent`, which `main.post`s it (`:542`) before the 204
  that curl waits for, and `ended` posts its check to main only after the process exits.
- *Helper gone = no live helper for a working chat.* `Sessions.Session` gets a runtime-only `int asking`: the
  chat's `ask` helpers still alive. A new `private void ask(Sessions.Session ses, String... args)` in ChatHub calls
  `Helper.run(app, () -> main.post(() -> { if (--ses.asking == 0 && ses.running) fail(ses, app.getString(R.string.hub_agent_stopped, ses.brainName())); }), "ask", args…)`,
  then `ses.asking++`. Both on main, so the decrement always follows the increment. `send` (`:295`) and
  `sendNoteAsMessage` (`:803`) use it, and their throw paths still start no run. There is no `runId` in the check, so
  ownership lasts until the process really exits: a run restarted by events, or a busy-rejected launch, can't detach
  it (review F4, F5). Busy rejection: the old helper still counts, so the rejected helper's exit does nothing. The
  chat keeps Stop, and the old run's done ends it, as today.
- *One end path.* Extract the give-up body (`:326-334`) into
  `private void fail(Sessions.Session ses, String why)`: `running=false`, `status=""`, an ERROR bubble with
  `why` plus, if there is any, "\n\n<brain>: <lastError>" (as today), `outcome="failed"`, `runEnded`,
  `changed`. It is used by the give-up timer (unchanged behaviour, except that the status is now cleared),
  by helper-gone, and by the ceiling.
- *Failure is final for that launch* (review F4). The auto-start in `onEvent` (`:559`) also needs
  `ses.outcome == null`. A run that ended failed or stopped (give-up, helper gone, ceiling, Stop) is not started
  again by its late events. Their bubbles still show. An explicit send still starts a run, because `startRun` resets
  the outcome. This is a behavior change: after a give-up, a late-waking agent's reply appears, but the chat doesn't
  go back to "working". A new message then gets "still working. Tap Stop first", and the chat works with Stop until
  that old run ends.
- *Fallback errors.* `case "buddy_error"` (`:679`) starts with
  `if (ev.optBoolean("fallback") && ses.outcome != null) break;`. If the run already has an outcome ("failed" from
  the agent's own error, or from Buddy ending it; "stopped" from `stop()`, which sets it before it sends "stop"), the
  helper's guess at why the run ended is dropped.
- *`buddy_stopped` guard* (`:686`): `if (!ses.running && "failed".equals(ses.outcome)) break;`. When Buddy has
  already ended a run as failed (the ceiling), the `buddy_stopped` from its own "stop" doesn't turn it into
  "stopped" or add "Stopped.". After a user Stop the outcome is "stopped", so that order is unaffected.
- *Ceiling* (review F3). `Helpers.MAX_RUN_MS = 30 * 60_000` (package-private, documented next to `WAIT_MS`). At the
  end of `startRun` (`:894`), the one place every run starts (`send`, a queued note's run, any other), for a chat
  with a parent:
  `final int id = ses.runId; main.postDelayed(() -> { if (ses.running && ses.runId == id) { fail(ses, app.getString(R.string.hub_helper_ceiling, Helpers.MAX_RUN_MS / 60_000)); try { Helper.run(app, "stop", ses.id); } catch (Exception ignored) { } } }, Helpers.MAX_RUN_MS);`.
  The new string is `hub_helper_ceiling`: "This sub-agent worked for over %1$d minutes, so Buddy ended it."
  The run ends at once instead of waiting for a done that a wedged helper may never send, and "stop" kills
  whatever is left in Linux.
- *Parent release* needs no new code. `Helpers.await` polls `running` every 500 ms and also returns when the
  parent stops running (`Helpers.java:156-165`). Every end path (done, give-up, helper gone, ceiling) clears
  `running` and calls `runEnded` (which closes the sub-agent's screen), and nothing turns it back on (F4). The
  parent's own hang is fixed by A and B, and when the parent ends, `await` stops its helpers.
- *No N-minute idle timer.* "No event for N minutes and no live pid" is covered in two places. While the helper is
  alive, A and B catch a dead agent, a killed `run_x` shell or a killed run section, then report error + done. When the helper itself is
  gone, the exit callback ends the run at once (N = 0). An idle limit while the helper is alive would end legitimate
  silent stretches: 10-minute tool calls, and local models that can take 15 minutes (research, Constraints). The
  one-shot give-up timer stays as it is.

Alternatives considered:
- Run the agent in its own process group (`setsid`) and kill the group: daemons can `setsid` out of it, and
  busybox flags differ.
- `read -t` plus an agent-pid check in each `stream_x`: five loops to change, a new pid file, and timing
  guesses.
- Check for the end line inside each `stream_x`: agy's `tee` stage would still wait for EOF.
- Turn the give-up timer into a repeating idle watchdog: it ends long, silent but valid tool calls.
- App reads `$S/pid` and checks `/proc`: whether the app process can see guest pids is unverified, while a
  Process exit is exact.
- Ceiling in buddy-run (`timeout`): buddy-run can't tell a sub-agent run from any other without a protocol
  change.
- Helper-side "an error was already reported" detection, to skip the fallback: five streams to edit, or a fork per
  event. The app already knows it from `outcome`.
- A busy-rejected `ask` that waits for the old run before it sends done (round 0): that is a second, unowned done
  (F5).
- The exit callback keyed to `runId` (round 0): restarts and busy rejections detach it (F4, F5).
- Move the error rule into the outer wrapper so a killed run section can't skip it (round 2): `status` and `name`
  would have to cross the subshell through files. The `exit 0` plus wait status needs two lines.

## Steps
1. **Regression harness, red first.** `linux/test/run.sh` and `linux/test/fixtures/run/*.expected.jsonl`.
   - Refactor the compare/`--update` body into a function used by both loops.
   - Add stand-ins to `$H/bin`, which buddy-run puts first on PATH. `curl`: when the last argument ends in
     `/event`, it appends stdin and a newline to `$EVENTS`, and otherwise prints nothing. It needs no
     listener, and leaves alone port 8765, which the real app holds on the phone. One agent script, linked as
     `claude codex opencode agy grok`: it exits 0 at once on `inspect` (grok's skill probe), prints
     `{"type":"system","session_id":"abc"}`, then follows `$BUDDY_FAKE`. Every pid it starts goes to
     `$BUDDY_FAKE_DIR/pids` (an absolute dir, because agy and grok runs change HOME):
     - `orphan`: prints a claude success `result` line, starts `sleep 60 &`, exits 0;
     - `killed`: `sleep 60 &`, then `kill -9 $$`;
     - `exit3`: exits 3, stderr empty;
     - `error`: prints `{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Rate limited"}`, exits 1, stderr empty;
     - `agentterm`: `kill -TERM $$` (a TERM that isn't a Stop);
     - `producer`: `sleep 60 &`, then `kill -9 $PPID` (`run_claude`'s shell), then `sleep 60` (the agent lives on, holding stdout);
     - `hang`: `exec sleep 60`.
     Add the tools the run path needs to the symlink list (awk, pgrep, wc, tee, ln, basename, readlink, env,
     timeout, sleep, as needed).
   - Cases, each `bash ../buddy-run test ask run <backend> 0 hi` with `HOME=$H` under `timeout 20` (a timeout is a
     FAIL): `claude_orphan`, `<backend>_killed` for all five backends, `claude_exit3`, `claude_error`,
     `claude_agentterm`, `claude_producer`, `claude_term`, `claude_runkill`, `claude_busy`. `claude_term` and
     `claude_runkill` use `hang`: the wrapper runs in the background, and the harness waits up to 10 s for the system
     event in `$EVENTS` and for `$H/.buddy/sessions/run/pid`, so neither case races the first event. `claude_term`
     then sends `kill -TERM` to the wrapper, `wait`s, and also FAILs if the agent pid is still alive.
     `claude_runkill` sends `kill -KILL` to the pid read from that pid file (the run section, review round 2) and
     `wait`s for the wrapper. `claude_busy` writes the pid of a background
     `sleep 60` to `$H/.buddy/sessions/run/pid` first, and FAILs unless the wrapper exits within 5 s.
     The events go through `jq -S -c` and are compared with `fixtures/run/<case>.expected.jsonl`. After each case,
     kill the pids in `$BUDDY_FAKE_DIR/pids` and clear `$H/.buddy $H/.claude $H/.gemini $H/.grok $EVENTS`. Kill
     by pid, never with `pkill -f`: that also matches the live Buddy helpers on the phone (research).
   - Write the expected files by hand from the texts in Design. For example: `claude_killed` is the system line,
     then `{"type":"buddy_error","fallback":true,"text":"Claude Code failed: it was killed (signal 9).",…}`, then
     `buddy_done`. `claude_exit3` ends with "…it exited with status 3." `claude_error` has the result line, then the
     fallback "…it exited with status 1." `claude_agentterm` ends with "(signal 15)". `claude_producer` ends with
     "(signal 9)". `claude_term` and `claude_runkill` have the system line, the "cut off" fallback, then done.
   `claude_busy` is the "still working"
     `buddy_error` alone (no `fallback`, no done). Backends whose stream drops the system line just have no event
     for it. On the current helper the run cases fail (timeouts, or a missing error or done). That is the
     reproduction evidence for done-when 1.
2. **Stream ends when the agent or its shell exits.** `linux/buddy-run`: add `until_exit` and wrap the 11 call sites.
3. **Done always, error whenever the agent failed.** `linux/buddy-run`: `fallback_error()` and `cut_off()`; the EXIT and
   TERM/INT/HUP traps after the busy check (the busy branch itself unchanged), with the `cut-off` marker; `RUN=$!`;
   the `exit 0` that ends the run section and the wait check (`rs`) after it; removal of the last
   `event buddy_done` (the pid file is still removed before done goes out, as today, so a note sent right after
   done isn't refused as "still working"); `trap - EXIT` in the test hook; the new error rule; and
   `HELPER_VERSION=31`. After this step, `bash linux/test/run.sh` passes, the stream fixtures included.
4. **Helper exit callback.** `Helper.java`: add the `run(Context, Runnable, String...)` overload, with
   `ended` in a `finally`.
5. **App ends runs.** `Sessions.java`: `int asking` in the runtime-only block. `ChatHub.java`: `fail()`, used by the
   give-up timer; `ask()`, used at `:295` and `:803`; the `ses.outcome == null` condition on the auto-start; the
   fallback and `buddy_stopped` guards; the ceiling at the end of `startRun`. `Helpers.java`: `MAX_RUN_MS`.
6. **String.** Add `hub_helper_ceiling` to `values/strings.xml` and to all 16 `values-*/strings.xml`, as the
   release commits do. Reuse `hub_agent_stopped` for helper-gone.

## Test plan
| Done-when | Proof |
|---|---|
| 1 Reproduced | research.md, Reproduction 1-4 (exit 3, kill -9, orphan holding the pipe, TERM to the wrapper). The Verifier also runs the new `run.sh` against main's `buddy-run` (a scratch worktree of `main` with the new `linux/test/` copied in): the run cases time out or miss events. The result goes in verify.md. |
| 2 buddy-run error+done, every backend | `run.sh` cases: `<backend>_killed` for all five backends (kill -9 with an orphan holding stdout gives a fallback error, then done, with no hang); `claude_producer` (run_claude's shell is SIGKILLed while the agent holds stdout, and error + done still arrive in bounded time); `claude_exit3` and `claude_agentterm` (a non-zero exit with empty stderr, and a TERM that isn't a Stop, both give an error); `claude_error` (after the agent's own error result, the helper's error carries `fallback`); `claude_orphan` (a clean exit gives done and no error); `claude_term` (wrapper TERM gives error, then done, and the agent is gone); `claude_runkill` (SIGKILL of the pid in `$S/pid`, the run section, gives the cut-off error, then done); `claude_busy` (a rejected `ask` sends no done). INT and HUP share the TERM trap line (reviewed by reading it; optionally `kill -HUP` by hand). The retry call sites use the same `until_exit` (reviewed by reading them). |
| 3 App ends a run whose helper is gone; sub-agent end releases parent | CI `apk` job compiles. The Code Reviewer traces these orders against the code (no Java test setup exists): **(a)** normal end: done, then the callback: `running` is already false, so it does nothing. **(b)** give-up fails the run, then a late `system` event (`outcome` is "failed", so no `startRun`), then the wrapper is SIGKILLed: callback, `asking` = 0, not running, so the run stays failed and the parent's `await` has already returned. **(c)** ceiling: `fail` + "stop", then a late `assistant` event (no restart), then `buddy_stopped` (guard: stays "failed", no "Stopped."), then the 143 fallback error (dropped: outcome set), then done (ignored: not running), then the callback (no-op). **(d)** busy rejection: an old run that the give-up ended is still alive, and the user sends: `startRun` (running) and `asking` = 2. The rejected helper sends the error and exits: `asking` = 1, still running, Stop shown. The old run's done ends it; the old helper exits: `asking` = 0, no-op. **(e)** queued note: done of run 1, then `sendNoteAsMessage` (`asking` + 1, run 2), then run 1's helper exits: run 2 stays running until its own done. **(f)** user Stop: `outcome` "stopped", so the run's 143 fallback is dropped, and so is the wrapper's cut-off fallback when the TERM reaches the run section before its `exit 0`. **(g)** run section killed (`claude_runkill`): the cut-off fallback arrives while `outcome` is null, so it shows and sets "failed". Then done clears `running` and calls `runEnded`, the parent's `await` returns, and `Helpers.lastError` reports that error. If the orchestrator OKs installing a build on the phone: `kill -9` a chat's outer `buddy-run` (by pid), and the chat shows "<agent> stopped." within seconds. Do the same to a sub-agent's helper, and the parent's `subagents` wait returns with that sub-agent reported as failed. |
| No regression: "still working" | Walkthrough (d) above, and `claude_busy`. Optionally on the phone, with a build installed: a run that the give-up timer ended while its agent still works, then a new message, gives "still working". The chat stays working with Stop, and Stop ends both. |
| 4 Ceiling | Code review: `startRun` is the only place a run starts, and both `send` and `sendNoteAsMessage` call it. Each run's timer is keyed to its own `runId`. Optionally, a local build with `MAX_RUN_MS` lowered to 1 minute (not committed): a sub-agent ends with the ceiling message, then again after a note sent to it starts a second run. `stop` leaves no agent process (`buddy-run procs`). |
| 5 run.sh + CI | `bash linux/test/run.sh` on the phone (Alpine, busybox awk) and in CI `helper-tests` (Ubuntu, mawk). The `apk` job is green. |

## Risks
- awk differs by platform: the prototype used busybox awk, and CI uses mawk (both have `fflush()`). The CI run
  of the new cases proves mawk. If mawk buffers input oddly, `stdbuf`-free workarounds exist, and the harness
  will show it.
- `claude_producer` relies on bash not exec'ing the agent from `run_claude`'s shell (true on bash 5.3, the phone).
  If a bash version did, `$PPID` would be the watcher and the case would time out: a visible FAIL, not a silent pass.
- A false "stopped" can happen if buddy-run's done POST timed out (`curl -m 5`) before the app read it: the
  run then ends as failed although it finished. This needs the app's own server to stall for more than 5 s.
- A backend that answers successfully and then exits non-zero with empty stderr would now get a "…exited with
  status N." bubble. No known backend does this (agy and grok already zero their status after their own errors).
  The `fallback` drop covers the ones that report an error first.
- After a give-up, a late-waking agent no longer turns its chat back to "working" (F4). Its reply still shows,
  and the next message brings back Stop through "still working".
- The app half (helper count, guards) is proven by walkthrough, not by tests: the app has no unit-test setup.
- The 30-minute ceiling may cut off a legitimately long sub-agent. The value is one constant (see Open
  questions).
- Orphans keep running until buddy-run exits. In the app, proot's `--kill-on-exit` then kills them. Outside
  the app (the tests), the harness kills them by pid.
- After a kill of the run section, its pipeline (agent, watcher, stream) is orphaned while the wrapper reports and
  exits. In the app, proot's `--kill-on-exit` kills it. In the tests, the agent pids are killed and the rest then
  ends on its own (the watcher's `wait` returns, awk exits, the stream gets EOF).
- The "bash in state R" spin was not reproduced. It is covered from outside: a killed `run_x` shell by the
  watcher, a killed run section by the wrapper's wait check, sub-agents by the ceiling, a killed helper by helper-gone, a top-level chat by Stop (`kill_tree` from
  the pid file).
- The 16 translations of one string are machine-made by the Coder, as in past releases.

## Open questions
None blocking. One default Nicholas may want to change later (one line): the sub-agent ceiling, 30 minutes.
