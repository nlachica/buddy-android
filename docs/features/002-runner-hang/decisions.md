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

## 2026-11-15 - Implementation notes the plan left open
- Choice: `grok_killed.expected.jsonl` keeps grok's pass-through `system` event (`model`/`subtype` null), because
  `stream_grok` forwards the stand-in's claude-style system line; the other non-Claude backends drop it. The
  busy-case sleep is started in a subshell so the harness prints no "Killed" job line. Steps 4-6 compile with
  steps 1-3 of build.sh by hand into /tmp/buddy-build-002; the string (step 6) was added after the Java that uses
  it, so only the step 5 commit alone doesn't compile.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - The `until_exit` relay is a bash `read` loop, not awk (review round 1 F1)
- Options: (a) keep awk and add a workaround for mawk's input buffering; (b) a `while IFS= read -r` loop that
  stops at the end line and forwards non-empty lines with builtin `printf`.
- Choice: (b). It drops the relay's awk dependency, so busybox and mawk behave the same: a line is passed on as
  soon as it is read, and the loop leaves at the end line. `PIPESTATUS[0]` is still the producer's status.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - Merge notes: upstream 2.3.8 + 2.3.9 (branch sync/upstream-2.3.9)
Upstream 2.3.9 fixed the same hang ("a run always ends"). Rule for this merge: upstream's mechanism wins everywhere,
and a piece of ours stays only if it covers a symptom upstream's doesn't. The result is that almost all of 002's
code is gone, so later upstream merges don't conflict on it.

- **Dropped, replaced by upstream's equivalent**
  - `until_exit` (the read loop and end line) -> upstream's `ended` + `AGENT_END`; all call sites use `ended`.
  - `fallback_error`, `cut_off`, the `fallback` field -> upstream's `quiet_end` / `quiet` field, handled the same
    way in ChatHub (dropped when the run already has an outcome). Wording changed to upstream's ("was killed
    (signal N).", "ended with exit status N and said nothing."). Upstream treats exit 143 as a Stop and says
    nothing; ours reported it as a fallback. Not kept: a TERM that isn't a Stop is rare, and the run still ends.
  - The traps (EXIT done, TERM/INT/HUP) and the `cut-off` marker -> upstream's traps (RUN="" first, no marker).
  - The wrapper's `rs` check on the run section ("cut off" fallback after a `kill -9` of the run section) -> not
    kept: the run still ends with done, just without an explanation line. That is cosmetic, not a hang.
  - `Helper.run(c, Runnable, ...)`, `ChatHub.ask()`, `Sessions.asking`, `fail()` -> upstream's
    `Helper.run(c, IntConsumer gone, ...)`, `Helper.BUSY` (exit 75), `helperGone()` after `HELPER_GONE_MS`, `failRun()`.
    Upstream's check is keyed to the run id and ignores BUSY, which is the case our F4/F5 review rounds worried about.
  - The `ses.outcome == null` condition on the auto-start and the `buddy_stopped` guard: upstream has neither, and
    nothing in upstream's `helperGone` path needs them (the helper is gone, so no late events from it).
  - The 30-minute sub-agent ceiling (`Helpers.MAX_RUN_MS`, `hub_helper_ceiling`, in 17 string files): the parent's
    wait is already bounded by `Helpers.WAIT_MS` (8 minutes) and stops its helpers when the parent ends, so a stuck
    sub-agent can't hold a parent. What is lost: a sub-agent whose helper stays alive but silent keeps its own chat
    "working" until its parent ends or the user taps Stop. If that shows up on the phone, bring the ceiling back as
    its own change.
- **Kept (tests only)**: `linux/test/run.sh` keeps upstream's `run` cases and our harness next to them, with
  expected files rewritten for upstream's events (checked by hand, not only `--update`). Kept because upstream's
  tests only use Claude Code and only look at event types: `<backend>_killed` for all five backends (text + done),
  `claude_error` (an error result, then exit 1: quiet error follows), `claude_agentterm` (143: no extra error),
  `claude_producer` (the agent's parent shell killed while the agent holds stdout), `claude_term` (also fails if the
  agent is still alive after the wrapper's TERM), `claude_runkill` (run section killed: wrapper still ends and says
  done), `claude_busy` (refused with the error, no done, now also exit status 75). Dropped as duplicates of
  upstream's: `claude_orphan`, `claude_exit3` (and their expected files). The curl stand-in is gone: buddy-run's
  `BUDDY_TEST_EVENTS` does the same.
- **HELPER_VERSION=32**. Upstream is 31, but our own 002 build was also 31 with different contents. The app's check
  (`CheckActivity`) compares for equality, so a phone with our old helper would have counted as current. 32 makes it
  offer the update. Upstream's next release will also take 32: expect a one-line conflict there, take the higher.
- **001 / SetupActivity**: Settings is now a menu of pages. The Audio device row sits in `voiceSection()` (the
  "voice" page) after the ChatGPT voice picker, as merged cleanly. The `AudioDeviceCallback` re-render now runs only
  when `page` is "voice", like upstream's listeners do for their pages. VoiceBar and AudioRoute are unchanged.
- Verified: `bash linux/test/run.sh` (15 fixtures, 31 fixtures and runs, 0 failed, twice); compile of FLAVOR=fdroid and
  github (build.sh steps 1-3) into /tmp/buddy-build-sync.
- By: Claude (claude-sonnet-5-5)

## 2026-11-15 - After the upstream 2.3.9 merge: one fork line kept, one gap left to upstream
- Codex merge review F2 (major): with upstream's helper, a kill -9 of the run section ended the chat with `done` only,
  so a sub-agent's partial work looked like a normal report. Fix: buddy-run reports `quiet_end "The run was cut off"`
  when the run section dies from a signal other than TERM (3 lines; test claude_runkill expects it).
- Codex F1 (major): in upstream's ChatHub a late event can restart a run the give-up timer already failed, and that
  run's helper dying is then ignored (the chat can stay "working"). Not patched: it is upstream's design, rare, and a fix
  needs run ownership changes in ChatHub that would make every upstream merge conflict. Tap Stop clears it. Revisit
  (or report upstream) if it is seen on the phone.
- By: Orchestrator (claude-opus-5-5)
