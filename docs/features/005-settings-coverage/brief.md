---
type: brief
feature: 005-settings-coverage
created: 2026-11-15
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 005-settings-coverage

## Ask (verbatim, never edit)
Check whether ... all the permissions are listed for Buddy where Buddy can modify its own settings. That was a very, very main, important change I wanted in the fork. [Then chose: Add all permissions + ordinary settings]

## Goal
Extend 004's `buddy_settings` tool so it lists and changes EVERY setting on Buddy's Settings pages (all permissions
and all ordinary settings), not just 004's table. Same gate: one approval card per change showing old -> new, deny
leaves it unchanged, secrets never shown.

Known gaps from 004 (plan.md "Deliberately not covered"): App permissions' per-app button lists (always ask / never
ask), the Smart reviewer choice and the built-in list, a chat's "Allowed without asking", models and default agent,
voice input/output engines, voices, audio device (001), languages, accent/surface, background browser options,
anything else on the Settings pages (walk every page: Agents and models, Bubble and chat, Voice, Safety and approvals,
Background and automation, Tools and connections, Experience, and any other).

## Non-goals
- Secrets: API keys, tokens, passwords, MCP addresses/keys are never shown or changed (as in 004).
- Flows the user must do: sign-ins, pairing (background mode, remote/SSH), backup/restore, downloads/installs of agents.
- Changing the approval gate or letting the agent answer cards.
- New settings; only what the Settings screens already offer.

## Done when
- [ ] A checked inventory (in research.md) of every control on every Settings page, each marked covered or excluded
      with the non-goal that excludes it.
- [ ] `buddy_settings` lists and changes every non-excluded control, with checks matching the screen's own choices.
- [ ] JVM tests for the new families (validation, card text, stale-write refusal), CI green.
- [ ] On the phone: list shows the new entries; one change per new family approved and one denied.

## Clarifications
- 2026-11-15 Nicholas: "the most important thing ... all the permissions are listed for Buddy where Buddy can modify
  its own settings" -> chose "Add all permissions + ordinary settings".
