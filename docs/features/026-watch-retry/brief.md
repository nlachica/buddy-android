---
type: brief
feature: 026-watch-retry
created: 2026-11-18
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 026-watch-retry

## Ask (verbatim, never edit)
x

## Goal
018 follow-up: when a live call's voice falls back from the watch to the phone (watch app restarted, link drop), it never returns to the watch for the rest of the call unless the setting changes, so with Auto it stays on the phone. Nicholas wants it to go back to the watch. diagnosis.md has the cause and the smallest fix: retry the watch up to 3 times per call, at least 5 s after a drop and only during a 1 s quiet gap (LiveWatch.fallback/poll, CodexLive.voiceQuiet). Overrules 018's recorded no-retry default.

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] After a fallback, the call returns to the watch within ~10 s once the watch is reachable, at most 3 tries per call, never mid-utterance.
- [ ] An unreachable watch never silences the phone voice.
- [ ] Tests cover retry timing, the cap and quiet-gap gating; 018 decisions updated.
- [ ] CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
