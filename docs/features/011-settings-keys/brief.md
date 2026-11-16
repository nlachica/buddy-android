---
type: brief
feature: 011-settings-keys
created: 2026-11-16
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 011-settings-keys

## Ask (verbatim, never edit)
The whole point of the buddy fork and update was so that buddy would be able to modify its own settings including setting keys, if I specifically authorized it.

## Goal
Extend 004's buddy_settings so the agent can SET keys after Nicholas approves the specific change on an approval card:
MCP server keys (so a server that needs a Bearer key, e.g. Composio at https://connect.composio.dev/mcp, can be added
in one step with its key) and the API keys on the API keys screen. The key value must not have to be typed by him:
the agent passes it from a source in Buddy's Linux (a file path such as ~/.config/composio/key, or a Bitwarden Secrets
Manager secret it already reads via `bws`). Keys are still never read back out or shown (card shows name, masked).

## Non-goals
- Reading keys out of Buddy (listing stays masked); changing how approval cards work or letting the agent answer them.
- Sign-ins (OAuth flows), GitHub token, passwords; a Bitwarden client inside the app.
- F-Droid flavor changes upstream would reject.

## Done when
- [ ] buddy_settings can add an MCP server whose discovery needs a key, with the key, after one approval card; denying changes nothing.
- [ ] buddy_settings can set/replace/remove an existing MCP server's key and an API-keys-screen key, each after one card.
- [ ] The key can be given by reference to a Linux file (and/or literal), so the agent never echoes it into chat; the card and all tool output show it masked (e.g. last 4 chars at most) and never the full value.
- [ ] Composio added this way lists its tools in Buddy (manual check on the phone after update).
- [ ] Approval cards still can't be answered by the agent; F-Droid build unaffected; fits code style, YAGNI.

## Clarifications
- 2026-11-16 Nicholas: setting keys was part of what he wanted from the fork, \"if I specifically authorized it\" (the approval card is that authorization). He doesn't want to paste keys himself; use Bitwarden/files on the phone.
