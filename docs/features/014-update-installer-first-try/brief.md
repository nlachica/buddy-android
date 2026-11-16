---
type: brief
feature: 014-update-installer-first-try
created: 2026-11-16
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 014-update-installer-first-try

## Ask (verbatim, never edit)
Open item 1: the first update_buddy install=true reported the installer open but nothing appeared; the second worked. Probably Android's background-activity-launch limit.

## Goal
update_buddy install=true reliably shows Android's installer on the first call, also when Buddy isn't in front (seen on 2026-11-15/16 for nearly every release: first call silent, second works). Find the real cause on this phone (Android 14+, Samsung S21 SM-G990W) and fix it the simplest way, e.g. launching the installer from the approval-card tap / a full-screen-intent notification instead of a background start; if the launch can't happen, the tool must say so instead of claiming the installer is open.

## Non-goals
- Silent installs; changing the approval card's wording or the release flow.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] Device: update_buddy install=true while another app is in front opens the installer on the first call (next fork release).
- [ ] If the installer can't be shown, the tool result says so and what the user should tap.
- [ ] CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
