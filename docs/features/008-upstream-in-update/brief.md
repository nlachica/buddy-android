---
type: brief
feature: 008-upstream-in-update
created: 2026-11-15
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 008-upstream-in-update

## Ask (verbatim, never edit)
I want it built into the update thing I've set up on this fork, so that when updating, it will also check the official buddy for whether or not there's an update there. If so, it will also pull the latest updates from the official buddy, and include it in my fork update. Naturally it would check for merge conflicts and stuff and resolve accordingly.

## Goal
When Nicholas updates Buddy (update_buddy / Buddy's update check), the fork also checks official Buddy
(ghorbelhamdi/buddy-android) for a newer release. If there is one, its changes are merged into the fork (following
pipeline-playbook/pipeline/upstream-sync.md: prefer upstream's mechanism, keep fork lines minimal), merge conflicts are
found and resolved, the result is built and tested by CI, and published as the next fork release
(`<upstream>-fork.<n>`, VERSION_CODE max+1) that the normal update installs. Today upstream is at 2.3.10 and the fork
at 2.3.9-fork.4, so the first run has real work.

## Non-goals
- Changing the approval card / installer route of update_buddy.
- Silent installs; Nicholas still taps Update.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] Asking for an update when upstream has a newer release produces a fork release containing upstream's changes plus all fork features (001-007), and update_buddy offers it.
- [ ] Conflicts are detected; ones that can be resolved are resolved and the build/tests are green before publishing; ones that can't stop with a clear message to Nicholas, nothing published.
- [ ] No upstream change → behaves as today.
- [ ] First real run brings 2.3.10 into the fork and it runs on the phone.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
