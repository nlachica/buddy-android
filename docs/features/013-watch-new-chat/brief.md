---
type: brief
feature: 013-watch-new-chat
created: 2026-11-16
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 013-watch-new-chat

## Ask (verbatim, never edit)
I also want the ability to be able to press new chat when I'm selecting a chat to create a new chat and talk.

## Goal
In the watch chat list (009 picker), a "New chat" entry at the top creates a new chat on the phone (Buddy's default agent and settings for new chats) and opens the watch talk screen for it, listening, so the first spoken message starts that chat.

## Non-goals
- Choosing agent/model/Linux tools from the watch; renaming or deleting chats.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] New chat in the watch list → a new chat appears on the phone with the spoken message, reply shown and spoken on the watch.
- [ ] Phone unreachable → the usual watch error, no ghost chat.
- [ ] 003-012 behaviour unchanged; CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
