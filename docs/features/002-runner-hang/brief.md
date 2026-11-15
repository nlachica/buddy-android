---
type: brief
feature: 002-runner-hang
created: 2026-11-15
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 002-runner-hang

## Ask (verbatim, never edit)
Next task, after what you're doing now: fix the bug in /root/.buddy/work/buddy-fork-tasks/runner-hang.md in the fork, using the pipeline.

## Bug report (verbatim from buddy-fork-tasks/runner-hang.md)

### Bug: a dead agent leaves its Buddy chat "working" forever

Seen 2026-11-14. A sub-agent's Claude Code process died (Codex sandbox broke under proot; that trigger is
fixed separately, see /root/.buddy/work/AGENTS.md). Afterwards:

- `linux/buddy-run` (installed as /root/bin/buddy-run) stayed alive about an hour: two bash processes in
  state R (busy) with a zombie child (`run_claude` subshell). It never reached `event buddy_done`.
- The app showed the sub-agent "Running", and the parent chat sat in "Waiting for the sub-agents" with its own
  claude process gone too.
- Killing the wrappers by hand did not help: the app still showed both chats as working until a
  `buddy_done` event was POSTed by hand for each session.

Fix it at the root in the fork (helper script and/or app), for every backend, not just Claude:
1. The runner must notice when the agent process has exited (or the stream is idle with no live agent) and
   always send buddy_error and buddy_done. Use a trap on EXIT/TERM too, so a kill still reports done.
2. App side: a run whose helper process is gone (or no event for N minutes with no live pid) should end as
   failed, and a sub-agent ending must release the parent's wait.
3. A hard ceiling per sub-agent run, ending it as failed with a clear message.
Reproduce first if you can (e.g. an agent that exits mid-stream, kill -9 of the agent).

## Goal
A dead or killed agent (any backend) can never leave a Buddy chat, or its parent waiting on sub-agents, stuck in "working": the helper always reports error+done, the app ends runs whose helper is gone or silent with no live process, sub-agent end releases the parent, and each sub-agent run has a hard ceiling.

## Non-goals
- The Codex sandbox trigger itself (fixed separately on the phone).
- Redesigning the run/event protocol beyond what the fix needs.

## Done when
1. Reproduced first (agent exits mid-stream; kill -9 of the agent; kill of the wrapper), documented in research or verify.
2. buddy-run always emits buddy_error + buddy_done when the agent process exits, the stream ends, or the wrapper gets TERM/INT/HUP (trap), for every backend.
3. App: a run whose helper process is gone, or with no event for N minutes and no live pid, ends as failed; a sub-agent ending (any way) releases the parent's wait.
4. A hard ceiling per sub-agent run ends it as failed with a clear message.
5. linux/test/run.sh passes, with a new recorded-output/regression test for the dead-agent case; CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
