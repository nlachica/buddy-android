---
type: brief
feature: 019-settings-no-card-routine
created: 2026-11-17
skipped_stages: [research]   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 019-settings-no-card-routine

## Ask (verbatim, never edit)
Yeah (to: a fork feature so changes to Buddy's settings skip the card in chats you trust, still asking for keys and security settings)

## Goal
In a chat whose approvals mode is "Routine allowed" (upstream Session.approvals, settable via 010), buddy_settings
changes go through without an approval card, and each one is noted in the chat the way Routine allowed notes other
steps. Changes that stay sensitive still show the card: keys (011), app permissions/trust levels, safety settings
(reviewer, button rules, approvals modes themselves), Linux tools, MCP servers added/removed, and update installs.
In "Ask" and "Always ask" chats nothing changes. Context: Nicholas found the cards too many while riding (audio_device
to auto needed a card each try).

## Non-goals
- Changing upstream's Routine allowed rules for phone actions; letting the agent answer cards; F-Droid flavor.

## Done when
- [ ] Routine allowed chat: an ordinary setting (e.g. audio_device, read_aloud, voice, appearance) changes with no card and a note in the chat.
- [ ] Routine allowed chat: keys, permissions/trust, safety/approvals, Linux tools, MCP add/remove, update install still card.
- [ ] Ask / Always ask chats: every change still cards (unchanged).
- [ ] Tests for the split; CI green; Codex review approves.

## Clarifications
- 2026-11-17 Nicholas chose this over just setting Routine allowed; he thought an earlier patch already reduced cards.
