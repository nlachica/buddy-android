# Decisions: 002-runner-hang
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-15 - The stream ends when the agent exits: an end-line relay (`until_exit`) at the head of each pipe
- Options: (a) `until_exit run_x | …`: after run_x returns it writes a one-off end line, and awk relays lines up
  to it, then exits; (b) put the agent in its own process group (`setsid`) and kill the group when the agent
  exits; (c) `read -t` plus an agent-pid check in each of the 5 stream loops; (d) check for the end line inside
  each stream_x.
- Choice: (a). It is exact (no timeouts), needs one function and 11 one-word call-site edits, and keeps
  `PIPESTATUS[0]`. (b) fails for daemons that setsid themselves, and busybox `setsid` differs. (c) means 5 loop
  edits and guessed timings. (d) leaves agy's `tee` stage waiting for EOF. Prototyped on the phone: an orphan
  holding stdout plus kill -9 ends in under 1 s with status 137; without the relay it hangs.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - buddy_done comes only from an EXIT trap set before the busy check; TERM/INT/HUP kill the run and report
- Options: (a) EXIT trap right after `event()`, the final `event buddy_done` removed, `trap - EXIT` in the test
  hook; (b) traps only around the run section, so the early exits ("still working", "API key gone") stay
  without done.
- Choice: (a). Every exit sends done exactly once, the early exits included. Under (b), the app's helper-gone
  check would end those runs with a second, misleading error (a stale last-error.log). One extra line keeps
  the stream fixtures unchanged. Subshells don't inherit the EXIT trap (checked). The busy branch ("still
  working") now waits for the old run's pid to go before it exits. Otherwise done (or the helper-gone check)
  would end the new run at once, and the chat would lose its Stop button while the old run, often one the
  give-up timer ended only in the app, still works. That would break today's "Tap Stop first" flow.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - buddy_error for a dead agent: when stderr has text (as today) or the agent was killed by a signal (status > 128, not 143)
- Options: (a) also on a signal death, with the text "it was killed (signal N)"; (b) on any non-zero exit,
  with "exit status N" when stderr is empty; (c) helper-side "no result event" detection (a flag in `post`
  or in 5 stream loops); (d) app-side "done without a result" check.
- Choice: (a). Signal deaths (kill -9, OOM, segfault) are the real silent case, and crashes already write to
  stderr. (b) likely adds a second bubble after Claude Code's own error result (rate limits and so on), since
  it exits non-zero with empty stderr (unverified, but the Agent SDK's "process exited with code 1" errors
  suggest it). (c) costs a fork per event in `post` or edits to every stream. (d) adds app state and has false
  positives (agy's withheld result, an OpenCode end without step_finish). Left uncovered: an exit 1-128 with
  empty stderr and no result ends the run without a message, as today. Nicholas may choose (b).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - App "helper gone" = the `ask` Process ending (Helper.run callback); no N-minute idle timer
- Options: (a) a `Runnable ended` overload of Helper.run, run after `waitFor`; ChatHub fails the run if that
  run is still on; (b) re-arm the give-up timer as an idle watchdog gated on reading `$S/pid` plus `/proc`
  from the app; (c) a new `alive <session>` helper command, polled.
- Choice: (a). proot runs with `--kill-on-exit`, so the Process ends exactly when buddy-run's outer shell does
  (kill -9 included). Done is `main.post`ed before curl's 204, so it always comes before the callback. With
  the helper gone, the "no live pid" case is immediate, and an idle limit while the helper is alive would end
  legitimate silent tool calls (10-minute tool timeouts, 15-minute local models). (b) relies on unverified
  pid visibility. (c) adds a command and polling.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Sub-agent ceiling: 30 minutes, in ChatHub.send; it fails the run at once, then sends "stop"
- Options: where: (a) an app timer like the existing give-up timer, or (b) `timeout` in buddy-run (but
  buddy-run can't tell a sub-agent run apart without a protocol change). How: (i) fail now plus "stop", or
  (ii) the existing `stop()`, then wait for done. Value: 30 minutes (vs WAIT_MS 8 minutes per wait call).
- Choice: (a)(i), 30 minutes. A wedged helper may never send done, so the run ends in the app now and "stop"
  cleans up Linux. A one-line `buddy_stopped` guard keeps the run "failed" without a "Stopped." step (no
  existing path reaches that state). 30 minutes is a guess that leaves room for long lookups; Nicholas may
  change the constant.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Strings: reuse `hub_agent_stopped` for helper-gone; one new `hub_helper_ceiling` (+16 locales)
- Options: new strings for both, or reuse where an existing one is clear.
- Choice: reuse "%1$s stopped." (plus the last stderr, like the give-up). The ceiling needs its own clear
  message (brief item 3), and the repo ships every string in all 17 locales.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Regression harness inside run.sh, with a stand-in agent and a stand-in curl
- Options: (a) cases in run.sh: a stand-in agent linked under all five names, plus a curl that saves
  `/event` posts to a file, compared with `fixtures/run/*.expected.jsonl`; (b) a separate script plus a CI
  step; (c) a real HTTP listener.
- Choice: (a). CI already runs run.sh, `--update` and the diff are reused, and buddy-run puts `$HOME/bin`
  first on PATH. (c) would clash with the real app's port 8765 on the phone and needs nc/python.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - `until_exit` runs the agent's function in a shell of its own and only waits for it (supersedes the first entry's relay body; review F2)
- Options: (a) `{ "$@" & wait "$!"; …end line…; }`: a watcher whose `wait` returns however run_x's shell dies;
  (b) a bounded liveness check (an idle timer gated on a pid) that ends the stream when the producer is gone;
  (c) the round-0 relay, where the producer shell itself writes the end line.
- Choice: (a), a change of a few characters. It catches the reviewer's `kill -KILL "$BASHPID"` case in 0.1 s
  (status 137; prototyped on the phone) without timers, and keeps `PIPESTATUS[0]`. `&` changes nothing else: the run
  section is already a background subshell (stdin `/dev/null`, SIGINT ignored). (b) means guessed timings and pid
  files. (c) loses the end line when the producer shell is killed. Left uncovered: a SIGKILL of the watcher itself
  (the ceiling or Stop still end that run).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Traps go after the busy check; a busy-rejected `ask` sends no done and doesn't wait (supersedes the second entry's placement and busy wait; review F5)
- Options: (a) traps after the busy check: the rejected `ask` exits as today, and the app's helper count keeps the
  chat working; (b) round 0: traps before it, and the busy branch waits for the old pid, then sends done.
- Choice: (a). The run in progress is the only owner of the chat's done. (b) sends a second, unowned done that can
  end the next run (F5). The "API key gone" exit is after the traps and now sends done.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Any non-zero exit fails the run; the helper's own guesses are `buddy_error` with `"fallback": true`, which the app drops when the run already has an outcome (supersedes the third entry; review F1)
- Options: (a) a fallback flag, deduplicated in the app by `outcome`; (b) the helper detects "an error was already
  reported" (five streams, or a fork per event); (c) round 0: errors only on signal deaths, except 143.
- Choice: (a). Classification is by exit status: 0 is finished even without a result event; non-zero is failed.
  Stderr text with a status other than 143 is reported as today; every other failure (empty stderr, any 143, the
  wrapper's "cut off") is a fallback. The app already knows whether the agent reported an error ("failed") or the
  user stopped ("stopped", set before "stop" is sent), so one guard handles both the dedupe and Stop vs. another
  TERM. The cost is one optional field, which old apps ignore and which ships with the helper.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The wrapper's TERM trap marks the run `cut-off` before `kill_tree`, and the run section then skips its error rule
- Options: (a) a marker file the run section checks; (b) let both report (the app drops the second fallback, but
  `claude_term` becomes nondeterministic); (c) SIGSTOP the run subshell first (risky under proot's ptrace).
- Choice: (a). The run section reaches its error rule only after `kill_tree` has started, so there is exactly one
  error. Checked on the phone, 5 of 5 runs: "cut off", then done, and the agent gone.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Helper gone = the last live `ask` helper of a working chat exited (a per-chat count, not `runId`) (supersedes the fourth entry's callback guard; reviews F4, F5)
- Options: (a) runtime `Session.asking`, up when `Helper.run` returns and down in the exit callback; fail when it
  reaches 0 while running; (b) round 0: compare the launch's `runId`; (c) a launch token stored per run.
- Choice: (a). Ownership lasts until the process really exits. A run restarted by events (new `runId`) can't detach
  it, and a busy-rejected launch exiting doesn't end the chat while the old helper lives, which keeps "Tap Stop
  first". (b) fails both (F4, F5); (c) needs the same count to handle busy rejection.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Late events never restart a run that ended failed or stopped (review F4)
- Options: (a) the `onEvent` auto-start also requires `ses.outcome == null`; (b) a new "ended by Buddy" flag;
  (c) keep restarts and rely on the helper count alone.
- Choice: (a), one condition and no new state. `startRun` resets the outcome, so explicit sends still work. (c)
  still lets a ceiling'd sub-agent flip back to busy and end "stopped" (F4). Cost: after a give-up, a late-waking
  agent's reply shows, but its chat doesn't go back to "working".
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The sub-agent ceiling is armed in `startRun` (supersedes the fifth entry's "in ChatHub.send"; review F3)
- Options: (a) `startRun`, the one place every run starts; (b) the same timer copied into `send` and
  `sendNoteAsMessage`.
- Choice: (a). A queued note's run gets its ceiling too, and so does any future start path. Still 30 minutes,
  keyed to that run's `runId`, and still fail now, then "stop".
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The wrapper checks how the run section ended: it ends with `exit 0`, and any other wait status sends the cut-off fallback (review round 2 F1)
- Options: (a) an explicit `exit 0` closes the run section; the wrapper keeps `wait "$RUN"`'s status and, if it is
  non-zero, sends `cut_off()` (the TERM trap's fallback, now a function with two uses) before the EXIT trap's done;
  (b) move the error rule into the wrapper, with `status` and `name` passed through files; (c) leave it (the
  round 1 plan, where killing the pid in `$S/pid` ended the run as a success).
- Choice: (a), two lines and no new files. A non-zero status means the section never reached its error rule
  (killed: 137 or 143; `set -u` abort: 1). The existing app-side drop of fallbacks when the run already has an
  outcome covers Stop ("stopped") and an error already reported ("failed"). The outer TERM trap `exit`s before
  the check runs, so "cut off" is still sent once. Prototyped on the phone: SIGKILL of the `$S/pid` process and a
  `set -u` abort each gave system, fallback, done. A failing last command followed by `exit 0` gave no error.
  Proven by the new `claude_runkill` case.
- By: Planner (claude-opus-5-5)
