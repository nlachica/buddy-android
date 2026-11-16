---
type: brief
feature: 010-settings-chat-approvals
created: 2026-11-16
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 010-settings-chat-approvals

## Ask (verbatim, never edit)
Yeah continue, and fix that gap afterwards. (Gap: buddy_settings can't read or change official Buddy's new per-chat approval mode Ask / Routine allowed / Always ask.)

## Goal
Make the fork's `buddy_settings` tool (feature 005) list and change a chat's per-chat approval mode (Ask / Routine allowed / Always ask, from upstream 2.3.10's `Session.approvals`), through the existing approval card, using `ChatHub.setApprovals()` so cached approvals refresh. Codex sync review finding F1 (PR 7).

## Non-goals
- Letting agents change it without the user's approval card; any other settings.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] buddy_settings lists the mode per chat and sets it after an approved card; denied card changes nothing.
- [ ] Unit tests; CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
