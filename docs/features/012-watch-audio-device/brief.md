---
type: brief
feature: 012-watch-audio-device
created: 2026-11-16
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 012-watch-audio-device

## Ask (verbatim, never edit)
How do I make the sound for read aloud come out of the watch? Right now, it's like when I click the mic thing, I only see the option for speaker and phone and auto. I'm not seeing an option for the watch. / Yeah build it.

## Goal
Add "Watch" to Buddy's audio-device choice (the list under the mic: Auto / Speaker / Phone, feature 001 audio selector). When chosen, phone read-aloud of replies plays on the Galaxy Watch through the 007 audio stream (`/buddy/say`-style voice to the watch speaker, in the phone's chosen voice), with the phone silent. If the watch isn't reachable, fall back to the phone's normal output and say so once.

## Non-goals
- Routing calls, mic input or other phone sounds to the watch; F-Droid build gets no Watch option.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] The device list shows Watch (github flavor, when a watch is connected or always with a clear unreachable state).
- [ ] Watch chosen + read aloud on: a phone chat reply is heard from the watch, once, phone silent; both voices (phone TTS and ChatGPT voice) work.
- [ ] Watch unreachable: reply read on the phone instead, once.
- [ ] 001/003/007/009 behaviour unchanged; CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
