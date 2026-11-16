---
type: brief
feature: 015-subagents-box-close
created: 2026-11-16
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 015-subagents-box-close

## Ask (verbatim, never edit)
Idea: let me collapse/close a chat's Sub-agents box once all its sub-agents are done (it stays until the next round today).

## Goal
In a chat, the Sub-agents box (delegate/subagents reports) gets a close (or collapse) control once every sub-agent in it is done; closing hides it until the next delegate round, which shows a fresh box as today. Match Buddy's look.

## Non-goals
- Changing sub-agent behaviour, reports or the bubble panel beyond the box.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] All sub-agents done → close control visible; tap hides the box; reports still reachable where they are today.
- [ ] While any sub-agent runs, no close (or collapse only).
- [ ] Next delegate round shows the box again. CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
