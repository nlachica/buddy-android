---
type: brief
feature: {{SLUG}}
created: {{DATE}}
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: {{SLUG}}

## Ask (verbatim, never edit)
{{ASK}}

## Goal
Buddy scheduled messages are dropped (or delayed hours) when another chat is busy for over an hour: on 2026-11-17 the 07:30 brief went out 10:31 and the curator's 08:00 run never happened. See audit-excerpt.md. Fix simply: a due scheduled message must run on time in its own chat regardless of other chats being busy, or at least run as soon as possible and never be dropped. Smallest native fix.

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] A scheduled message due while another chat is busy still runs (on time if its chat is idle), never dropped.
- [ ] Test covers it; CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
