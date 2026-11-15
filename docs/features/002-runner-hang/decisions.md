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
