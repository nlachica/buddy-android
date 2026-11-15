# Plan: 002-runner-hang
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal
A dead or killed agent, on any backend, must never leave a Buddy chat stuck in "working", and must never leave
a parent stuck waiting on its sub-agents. Done when: (1) the failure is reproduced (agent exits mid-stream,
`kill -9` of the agent, kill of the wrapper); (2) `buddy-run` always sends `buddy_error` + `buddy_done` when the
agent dies, the stream ends, or the wrapper gets TERM/INT/HUP, for every backend; (3) the app ends a run as
failed when its helper process is gone, and a sub-agent ending in any way releases the parent's wait; (4) every
sub-agent run has a hard ceiling that ends it as failed with a clear message; (5) `linux/test/run.sh` passes
with a new regression test for the dead-agent case, and CI is green.

Non-goals: the Codex-under-proot trigger itself; any change to the event protocol (no new event types or
fields); an idle timeout for top-level chats; a ceiling for top-level chats; explaining the "bash in state R"
spin that was never reproduced (research.md, Summary); Java unit-test infrastructure (there is none).

## Design
The research found three holes, and each layer below closes one.

**A. The stream ends when the agent exits, not at pipe EOF** (`linux/buddy-run`). This is the hour-long hang:
an orphan (MCP server, background job) keeps the agent's stdout open, so `while read` in `stream_<x>` never
gets EOF (research, Reproduction 3). Add one function next to `kill_tree`:

```bash
# An agent's run (run_<x>): its output, until it exits. Something it started (an MCP server, a background job)
# can keep that output open after it's gone; read to the end, the stream waited for that, and the chat stayed
# "working" with no buddy_done (feature 002).
until_exit() {
  local end="buddy-run: agent exited $$ $RANDOM"
  { "$@"; s=$?; printf '\n%s\n' "$end"; exit "$s"; } | awk -v end="$end" '$0 == end { exit } $0 != "" { print; fflush() }'
  return "${PIPESTATUS[0]}"
}
```

All 11 call sites change from `run_x | …` to `until_exit run_x | …` (`:924 :928 :937 :943 :948 :957 :961 :970
:974 :980 :984`). `PIPESTATUS[0]` is still `run_x`'s status, so nothing after them changes. The leading `\n`
keeps the end line separate even when the agent was killed mid-line. awk exits at the end line, so whatever is
downstream gets EOF: `stream_x` directly, or agy's `tee` and then `stream_agy`. The orphan is left without a
reader. In the app, proot's `--kill-on-exit` (`Linux.java:77`) kills it once buddy-run exits. I prototyped this
on this phone (bash 5.3, busybox awk). An agent that leaves `sleep 20 &` holding its stdout and then gets
`kill -9`: the stream ends in under 1 s with status 137. After a clean `exit 0` it ends at once with status 0,
and `exit 3` gives status 3. The same agent without the relay hangs until the timeout.

**B. `buddy_done` always, and `buddy_error` when the agent died** (`linux/buddy-run`).
- `trap 'event buddy_done' EXIT` goes right after `event()`/`tag()` (`:266-267`), before the busy check. Every
  exit after that point then sends done exactly once: the normal end, the early error exits ("still working"
  `:271`, "API key no longer in Buddy" `:276`, which send no done today), a `set -u` abort, and a TERM. The
  last line `event buddy_done` (`:998`) goes away. The test hook (`:914`) gets `trap - EXIT` so fixture output
  is unchanged. `( … ) &` and the pipeline subshells don't inherit the EXIT trap, so they never send done
  (checked).
- The busy branch (`:269-272`) waits before it exits:
  `while kill -0 "$(cat "$S/pid" 2>/dev/null)" 2>/dev/null; do sleep 2; done`. This keeps today's flow. The
  give-up timer can end a run in the app while its agent still works in Linux. The next message then gets
  "still working. Tap Stop first", and the chat has to stay "working" so Stop is there. With done always
  sent, or with helper-gone below, it would go idle at once with no Stop. Now Stop kills the old run, the
  wait ends, and done follows.
- A trap on the same spot:
  `trap '[ -n "${RUN:-}" ] && { kill_tree "$RUN"; rm -f "$S/pid"; }; event buddy_error "The run was cut off: Buddy'\''s helper in Linux was stopped."; exit 1' TERM INT HUP`.
  After `) &` comes `RUN=$!; echo "$RUN" > "$S/pid"; wait "$RUN"`. A killed wrapper stops the agent's tree,
  reports the error, and the EXIT trap then sends done. I prototyped this: TERM during `wait` produced error
  then done, and the agent was gone.
- Error rule (`:989`). Today it needs non-empty `last-error.log`. Add `|| [ "$status" -gt 128 ]` (killed by a
  signal). When stderr is empty, the text is `"$name failed: it was killed (signal $((status - 128)))."`.
  Status 143 is still excluded, because Stop's `kill_tree` TERMs the pipeline before the subshell. Exit codes
  1-128 with empty stderr stay silent, as today (see decisions: Claude Code likely exits 1 after its own error
  result, which would double the error bubble). The run still ends.
- `HELPER_VERSION=31`.

**C. The app ends runs the helper can't end** (`Helper.java`, `ChatHub.java`, `Helpers.java`).
- *Helper gone.* Add `Helper.run(Context, Runnable ended, String... args)`. The existing `run(c, args)` calls it
  with `null`. `ended` runs in a `finally` after `p.waitFor()` (or after a failed start), on the pool thread.
  For `ask`, that Process is the helper for the whole run. With `--kill-on-exit`, proot exits as soon as
  buddy-run's outer shell exits (even on `kill -9`): the read loop sees EOF and `waitFor` returns. There is no
  race with a delivered done. `McpServer.java:177-181` hands the event to `BuddyService.onEvent`, which
  `main.post`s it (`:542`) before the 204 that curl waits for. So done is queued on main before the helper can
  exit, and `ended` posts its check to main after that.
- In `ChatHub.send` (`:295`) and `sendNoteAsMessage` (`:803`), the `ask` call passes
  `() -> main.post(() -> { if (ses.running && ses.runId == run[0]) fail(ses, app.getString(R.string.hub_agent_stopped, ses.brainName())); })`.
  Here `final int[] run = new int[1]` is set to `ses.runId` right after `startRun(ses)`. Both call sites already
  call `startRun` after `Helper.run`, and its throw path must still start no run.
- *One end path.* Extract the give-up body (`:326-334`) into
  `private void fail(Sessions.Session ses, String why)`: `running=false`, `status=""`, an ERROR bubble with
  `why` plus, if there is any, "\n\n<brain>: <lastError>" (as today), `outcome="failed"`, `runEnded`,
  `changed`. It is used by the give-up timer (unchanged behaviour, except that the status is now cleared),
  by helper-gone, and by the ceiling.
- *Ceiling.* `Helpers.MAX_RUN_MS = 30 * 60_000` (package-private, documented next to `WAIT_MS`). In
  `send`, after the give-up timer: if `!ses.parent.isEmpty()`,
  `main.postDelayed(() -> { if (ses.running && id == ses.runId) { fail(ses, getString(R.string.hub_helper_ceiling, MAX_RUN_MS / 60_000)); Helper.run(app, "stop", ses.id) (exceptions ignored); } }, MAX_RUN_MS)`.
  The new string is `hub_helper_ceiling`: "This sub-agent worked for over %1$d minutes, so Buddy ended it."
  The run ends at once instead of waiting for a done that a wedged helper may never send, and "stop" kills
  whatever is left in Linux.
- `case "buddy_stopped"` (`:686`): `if (!ses.running && "failed".equals(ses.outcome)) break;`. When Buddy has
  already ended a run as failed (the ceiling), the `buddy_stopped` from its own "stop" does not turn it into
  "stopped" or add "Stopped.". No existing path reaches this: `stop()` sets "stopped" first and returns early
  when the chat is not running.
- *Parent release* needs no new code. `Helpers.await` polls `running` every 500 ms and also returns when the
  parent stops running (`Helpers.java:156-165`). Every end path (done, give-up, helper gone, ceiling) clears
  `running` and calls `runEnded` (which closes the sub-agent's screen). The parent's own hang is fixed by A
  and B, and when the parent ends, `await` stops its helpers.
- *No N-minute idle timer.* "Helper process gone" ends the run at once, so the "no event for N minutes and no
  live pid" case is covered with N = 0. An idle limit while the helper is still alive would end legitimate
  silent stretches: 10-minute tool calls and 15-minute local models (research, Constraints). The one-shot
  give-up timer stays as it is.

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
- `buddy_error` on any non-zero exit: doubles the agent's own error-result bubble (decision 3).

## Steps
1. **Regression harness, red first.** `linux/test/run.sh` and `linux/test/fixtures/run/*.expected.jsonl`.
   - Refactor the compare/`--update` body into a function used by both loops.
   - Add stand-ins to `$H/bin`, which buddy-run puts first on PATH. `curl`: when the last argument ends in
     `/event`, it appends stdin and a newline to `$EVENTS`, and otherwise prints nothing. It needs no
     listener, and leaves alone port 8765, which the real app holds on the phone. One agent script, linked as
     `claude codex opencode agy grok`: it exits 0 at once on `inspect` (grok's skill probe), prints
     `{"type":"system","session_id":"abc"}`, then follows `$BUDDY_FAKE`. `orphan`: prints a claude `result`
     line, starts `sleep 60 &`, writes its pid to `$BUDDY_FAKE_DIR`, and exits 0. `killed`: the same
     `sleep 60 &`, then `kill -9 $$`. `hang`: writes its pid, then `exec sleep 60`. Use an absolute dir,
     because agy and grok runs change HOME. Add the tools the run path needs to the symlink list (awk, pgrep,
     wc, tee, ln, basename, readlink, env, timeout, sleep, as needed).
   - Cases: `claude_orphan`; `<backend>_killed` for all five backends; `claude_term` (wrapper in the
     background, wait up to 10 s for the agent pid, `kill -TERM` the wrapper, `wait`). Each runs
     `bash ../buddy-run test ask run <backend> 0 hi` with `HOME=$H` under `timeout 20`. A timeout is a FAIL.
     The events go through `jq -S -c` and are compared with `fixtures/run/<case>.expected.jsonl`.
     `claude_term` also FAILs if the agent pid is still alive. After each case, kill any leftover pids and
     clear `$H/.buddy $H/.claude $H/.gemini $H/.grok $EVENTS`.
   - Write the expected files by hand from the texts in Design. For example, `claude_killed` is the system
     line, then `buddy_error` "Claude Code failed: it was killed (signal 9).", then `buddy_done`. Backends
     whose stream drops the system line just have no event for it. On the current helper the run cases fail
     (timeouts, or a missing `buddy_error`/`buddy_done`). That is the reproduction evidence for done-when 1.
2. **Stream ends when the agent exits.** `linux/buddy-run`: add `until_exit` and wrap the 11 call sites.
3. **Done always, error when killed.** `linux/buddy-run`: the EXIT and TERM/INT/HUP traps after `tag()`,
   the wait in the busy branch, `RUN=$!`, removal of the last `event buddy_done` (the pid file is still
   removed before done goes out, as today, so a note sent right after done isn't refused as "still working"),
   `trap - EXIT` in the test hook, the error rule
   `|| status > 128` with the signal text, and `HELPER_VERSION=31`. After this step, `bash linux/test/run.sh`
   passes, the stream fixtures included.
4. **Helper exit callback.** `Helper.java`: add the `run(Context, Runnable, String...)` overload, with
   `ended` in a `finally`.
5. **App ends runs.** `ChatHub.java`: add `fail()` and make the give-up timer use it; wire the exit callback
   at `:295` and `:803`; add the sub-agent ceiling in `send`; add the `buddy_stopped` guard. `Helpers.java`:
   add `MAX_RUN_MS`.
6. **String.** Add `hub_helper_ceiling` to `values/strings.xml` and to all 16 `values-*/strings.xml`, as the
   release commits do. Reuse `hub_agent_stopped` for helper-gone.

## Test plan
| Done-when | Proof |
|---|---|
| 1 Reproduced | research.md, Reproduction 1-4 (exit 3, kill -9, orphan holding the pipe, TERM to the wrapper). The Verifier also runs the new `run.sh` against main's `buddy-run` (a scratch worktree of `main` with the new `linux/test/` copied in): the run cases time out or miss events. The result goes in verify.md. |
| 2 buddy-run error+done, every backend | `run.sh` cases: `<backend>_killed` for all five (kill -9 with an orphan holding stdout gives error, then done, with no hang), `claude_orphan` (the stream ends after a clean exit and done follows), `claude_term` (wrapper TERM gives error, then done, and the agent is gone). INT and HUP share the TERM trap line (reviewed by reading it; optionally `kill -HUP` by hand). The retry call sites use the same `until_exit` (reviewed by reading them). |
| 3 App ends a run whose helper is gone; sub-agent end releases parent | CI `apk` job compiles. Code-path review: every end path goes through `buddy_done` or `fail()`, both clear `running`, and `Helpers.await` polls it. If the orchestrator OKs installing a build on the phone: `kill -9` a chat's outer `buddy-run` (by pid), and the chat shows "<agent> stopped." within seconds. Do the same to a sub-agent's helper, and the parent's `subagents` wait returns with that sub-agent reported as failed. |
| No regression: "still working" | Code review. Optionally on the phone, with a build installed: a run that the give-up timer ended while its agent still works, then a new message, gives "still working". The chat stays working with Stop, and Stop ends both. |
| 4 Ceiling | Code review of the `send` timer. Optionally, a local build with `MAX_RUN_MS` lowered to 1 minute (not committed): the sub-agent ends with the ceiling message, and `stop` leaves no agent process (`buddy-run procs`). |
| 5 run.sh + CI | `bash linux/test/run.sh` on the phone (Alpine, busybox awk) and in CI `helper-tests` (Ubuntu, mawk). The `apk` job is green. |

## Risks
- awk differs by platform: the prototype used busybox awk, and CI uses mawk (both have `fflush()`). The CI run
  of the new cases proves mawk. If mawk buffers input oddly, `stdbuf`-free workarounds exist, and the harness
  will show it.
- A false "stopped" can happen if buddy-run's done POST timed out (`curl -m 5`) before the app read it: the
  run then ends as failed although it finished. This needs the app's own server to stall for more than 5 s.
- The 30-minute ceiling may cut off a legitimately long sub-agent. The value is one constant (see Open
  questions).
- An agent that exits 1-128 with empty stderr and no result still ends without an explanation (as today);
  only signal deaths gain a message (decision 3).
- Orphans keep running until buddy-run exits. In the app, proot's `--kill-on-exit` then kills them. Outside
  the app (the tests), the harness kills them by pid.
- The "bash in state R" spin was not reproduced. It is covered from outside: sub-agents by the ceiling, a
  killed helper by helper-gone, a top-level chat by Stop (`kill_tree` from the pid file).
- The 16 translations of one string are machine-made by the Coder, as in past releases.

## Open questions
None blocking. Two defaults Nicholas may want to change later (each is one line):
- Sub-agent ceiling: 30 minutes.
- Exit-code-only failures (exit 1-128, empty stderr) show no error, which avoids a second bubble after the
  agent's own error result.
