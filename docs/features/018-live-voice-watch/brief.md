---
type: brief
feature: 018-live-voice-watch
created: 2026-11-17
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 018-live-voice-watch

## Ask (verbatim, never edit)
Both (fix the earpiece bug, and build live calls on the watch). Context: audio_device=watch during a phone live voice call played in the earpiece; Watch only covers read-aloud.

## Goal
Two parts, see diagnosis.md (root cause with file:line). (1) Bug: with audio_device = watch during a phone live voice call (CodexLive, WebView in MODE_IN_COMMUNICATION), AudioRoute.want() maps watch to "" and reconcile() drops the route, so Android uses the earpiece. Fix: while live voice is on and the watch can't take the call audio, Watch must route to the phone loudspeaker (never the earpiece); a connected headset keeps priority per 016. (2) Feature: when audio_device = watch (or Auto with no headset and a reachable watch, per 016 order), a phone live voice call's audio plays on the Galaxy Watch speaker, reusing the 007 audio stream machinery; mic stays where it is today unless research shows the watch mic path from 007 calls fits simply. Falls back to the phone loudspeaker if the watch drops.

## Non-goals
- Changing calls started from the watch (007), voices, or Auto's priority order itself.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] Live voice call on the phone with Watch picked → no earpiece ever; voice heard on the watch; watch unreachable → phone loudspeaker.
- [ ] Picking Watch mid-call doesn't clear the route to earpiece; ending the call restores normal mode.
- [ ] Read-aloud on the watch (012) and 016 Auto unchanged; CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
