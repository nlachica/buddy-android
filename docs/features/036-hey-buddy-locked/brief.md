---
type: brief
feature: 036-hey-buddy-locked
created: 2026-11-22
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 036-hey-buddy-locked

## Ask (verbatim, never edit)
Also, I noticed that Hey Buddy doesn't work when my phone screen is locked. Is there any way around this?

## Goal
<!-- one paragraph, orchestrator's reading of the ask -->

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] 

## Clarifications
<!-- Nicholas's later answers, dated, appended -->

## Context
- 032 decided "answer while locked = yes" (approval cards still need unlock), but on the phone (S21 FE, SM-G990W)
  "hey buddy" doesn't trigger while the screen is locked. Unknown whether the detector stops (mic foreground service
  paused/killed, screen-off/Doze, Samsung battery optimization, a lock check in the code) or it detects but the turn is
  suppressed while locked. Find which with evidence, then fix. Keep 032 battery.md's budget. If the cause is only a
  phone setting, say exactly which and stop there.
- Feature 035 (live speaker loudness) runs in parallel in ../buddy-fork on its own branch; don't touch it.

## Done when
"hey buddy, what time is it" works with the screen locked and off, answered by voice, verified on the phone.
