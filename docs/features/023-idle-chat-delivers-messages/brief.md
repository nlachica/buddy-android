---
type: brief
feature: 023-idle-chat-delivers-messages
created: 2026-11-17
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 023-idle-chat-delivers-messages

## Ask (verbatim, never edit)
When a chat has finished its reply but still has background jobs running, Buddy holds my new messages as 'added while you were working' updates, delivered only on the next phone tool call, so the chat ignores me for up to an hour. When the agent is idle, my message should go straight to it as a new message.

## Goal
Evidence (2026-11-17, orchestrator chat a35d19e5): at 05:38:55 the agent ended its reply after starting a
run_in_background waiter for a pipe job. Nicholas's correction, sent right after, was not delivered as a new turn; it
was held as a [BUDDY_USER_UPDATE] and only reached the agent at 06:11 on an update_buddy tool result, after the
feature was already built and released. Root cause to confirm: Buddy treats the chat as busy while the Claude Code /
Codex process is alive (background tasks pending) rather than while a turn is actually in progress, and only
injects queued text into phone tool results. Fix: when the agent's current turn has ended (result/idle event in the
stream) a new user message is delivered immediately as a normal user turn, even if background tasks are still
running; when a turn is in progress, keep today's update behaviour. Should work for every provider Buddy runs.

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] With a background task running and the turn ended, a message sent in the bubble or full chat starts a new turn within seconds.
- [ ] During an in-progress turn, messages still arrive as updates on the next phone tool result (no regression).
- [ ] When the background task later finishes, its notification and the user's turn don't collide or get lost.
- [ ] 

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
