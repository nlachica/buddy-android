---
type: brief
feature: 007-watch-live-voice
created: 2026-11-15
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 007-watch-live-voice

## Ask (verbatim, never edit)
Go ahead and continue with the call option. Can we extend the voice that buddy uses instead? Or is it going to make it less efficient

## Goal
Phase 2 of 003: (a) the call option (Buddy's live voice conversation, CodexLive on the phone) usable from the Galaxy
Watch: watch mic to the phone's live voice and the voice back to the watch speaker, over the 003 watch link
(`/buddy/live` ChannelClient as 003 planned). (b) Watch replies spoken in the same voice the phone uses (ChatGPT's voice
via CodexSpeech when set) instead of the watch's own TTS, falling back to the watch TTS when that voice or the link
fails. Context: 003 found the watch voice sounds worse than the phone's; Nicholas asks whether reusing Buddy's voice
costs efficiency, so the plan must state the battery/latency cost.

## Non-goals
- Upstream sync automation (separate feature).
- New voice providers; changing the phone's call or voice behaviour.
- Anything Google in the F-Droid build.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] From the watch, start a call to a chat: speak and hear Buddy's live voice from the watch speaker; end it from the watch; phone locked works.
- [ ] With ChatGPT voice on, a typed-path watch reply (003 talk) is spoken on the watch in that voice; with it off or failing, the watch TTS speaks it, once.
- [ ] Battery/latency cost of (b) measured or estimated in verify.md.
- [ ] 003/006 behaviour unchanged; CI green; F-Droid check green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
