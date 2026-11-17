---
type: brief
feature: 022-watch-chat-view
created: 2026-11-17
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 022-watch-chat-view

## Ask (verbatim, never edit)
Watch: show the chat's messages

## Goal
On the Galaxy Watch (wear/ app), Buddy's Chats list shows chat names, but opening a chat shows only the mic and call buttons, no messages. Nicholas wants to open a chat on the watch and see its conversation text (his messages and Buddy's replies), scroll up and down through it with touch and the rotating bezel, and still have the mic and call buttons there. Reuse the existing phone<->watch channel (007/009/013 features) to fetch the chat's recent messages; newest at the bottom, opened scrolled to the end, updates when a new reply arrives.

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] Opening a chat on the watch shows its recent messages (both sides, readable on a round screen), opened at the newest.
- [ ] Scrolls with touch and the bezel/rotary input; mic and call buttons stay reachable.
- [ ] A new reply in that chat appears while it's open.
- [ ] Long chats stay fast (bounded number of messages / length); works with the phone app from the same release.
- [ ] CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
