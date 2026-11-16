---
type: brief
feature: 012-chat-scroll
created: 2026-11-16
skipped_stages: [research, plan, plan-review]   # two-file bug fix
---
# Brief: 012-chat-scroll

## Ask (verbatim, never edit)
Fix the chat jumping while I scroll.

## Goal
New agent output (messages, step lines) only scrolls the chat (bubble panel and chat screen) to the end when the
user is already at or near the end. The user's own messages, opening the chat, and the keyboard still go to the end.

## Done when
- [ ] Bubble and ChatActivity follow agent output only when within 48dp of the end.
- [ ] User messages and opening a chat still jump to the end.
- [ ] CI green; reviewer approves.
