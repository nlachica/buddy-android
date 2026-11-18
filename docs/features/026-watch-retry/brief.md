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

## Added by Nicholas mid-run (in scope)
When the watch drops mid-call and the setting is Auto, the voice fell back to the earpiece (hold-to-ear speaker). Auto's fallback must prefer the loudspeaker (or a connected headset) over the earpiece. Acceptance: after a watch drop with Auto and no headset, audio plays on the speakerphone, not the earpiece; a test covers it.

## Added mid-run: watch-started call hears nothing (in scope)
See diagnosis-watchcall.md. A call started from the watch connects, but the backend gets silence (mic peak ~0) and the watch shows "Working on it…" forever. In scope: (1) frame/peak counters on watch Call.mic and phone CodexLive.push, logged; (2) find and fix why the watch mic sends silence (half-duplex gate Call.java:246 or VOICE_COMMUNICATION source; prefer a source/gate that works on Galaxy Watch); (3) status shows "Listening…" while the call is idle even if the chat run is busy; (4) keep 5 rotated live logs.

Nicholas: the watch call status should match the phone's call UI (phone says "Listening…" while the chat works); mirror the phone's status logic exactly on the watch.

Nicholas (firm): keep it simple. Reuse the phone's existing call-status code (Listening/Speaking/Thinking) and send that same string to the watch; no new status logic.
