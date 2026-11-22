---
type: brief
feature: 035-live-earpiece-regression
created: 2026-11-22
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 035-live-earpiece-regression

## Ask (verbatim, never edit)
But I just noticed you're not talking on the speakerphone. You're talking on like the hold up to the phone ear thing. I thought we fixed that. Wasn't that the fix we just added yesterday

## Goal
<!-- one paragraph, orchestrator's reading of the ask -->

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] 

## Clarifications
<!-- Nicholas's later answers, dated, appended -->

## Evidence (2026-11-22 12:23-12:30 MST, live voice call, no Bluetooth headset, watch state unknown)
- Nicholas hears Buddy's live voice from the EARPIECE, not the speaker.
- Android reports otherwise: `dumpsys audio` shows app.buddy.assistant as mode owner MODE_IN_COMMUNICATION, its
  CommunicationRouteClient mDevice=speaker (SM-G990W), "Computed Preferred communication device: speaker",
  setCommunicationDevice(speaker) called repeatedly at 12:23:47-49. So the request is right but the real output is
  earpiece: suspect a Samsung quirk, a playback stream/usage not following the communication device, or a regression
  from 033-watch-worn-audio / 034. See 030-live-no-earpiece and 031-headset-route for the earlier fix.

## Goal
Live voice plays on the loudspeaker whenever no Bluetooth headset is connected and the watch isn't worn
(order: Bluetooth > watch if worn > speaker > earpiece last resort). Done when verified on the phone.
