---
type: brief
feature: 032-hey-buddy-wake-word
created: 2026-11-21
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 032-hey-buddy-wake-word

## Ask (verbatim, never edit)
I want it to work like hey Google. If I just said hey buddy, then I'd be able to ask a question and it'd go straight to buddy. Like hey buddy what's the weather like. Then it'd say the weather. Then I'd want to be able to continue the convo by saying hey buddy, what about the weather tomorrow? ... when it's talking, I'd want the ability to say hey buddy, ok stop talking to make it stop talking. Basically exactly like how ok google works. | Follow-up: do it always on. But give me a way to toggle it on/off that fits the buddy UI.

## Goal
Saying "hey buddy" (alone, or followed straight away by a request: "hey buddy, what's the weather like") sends the
request to Buddy hands-free, and Buddy answers aloud, like "Ok Google". Saying "hey buddy, <follow-up>" later
continues the same chat. Saying "hey buddy, stop" while Buddy is speaking cuts the speech. It is always on by
default, with an on/off toggle that fits Buddy's UI. Because the phone's DSP hotword is reserved for the default
assistant (see `context-hey-google-research.md`, note 7), this is an on-device software wake word running in a
foreground service.

Orchestrator constraints (from the task):
- Engine is chosen in research: openWakeWord or microWakeWord (free, offline, custom "hey buddy" model) vs
  Porcupine (needs an access key; if chosen, the key goes in via `buddy_settings` and Bitwarden, never in the repo).
- Follow-up turns go to the same chat as Buddy's existing voice/Talk path; reuse the existing speech-to-text, TTS
  and Live voice code where possible rather than adding a second voice stack.
- Barge-in: while TTS plays, keep listening with Android AEC (acoustic echo cancellation) so "hey buddy, stop" cuts
  speech.
- Pause listening during phone calls and while another app holds the mic; resume afterwards.
- Toggle: a Settings → Voice switch, plus a quick toggle (the bubble's hold menu and/or a Quick Settings tile;
  the planner decides).
- Battery impact is written into the docs (user-facing doc + feature folder).

## Non-goals
- Using the DSP / `AlwaysOnHotwordDetector`, becoming the default assistant, root.
- Speaker verification ("only my voice"), multiple wake words, user-trainable wake words in the app.
- Replacing Buddy's existing voice/Live code paths or Google's "Hey Google".
- Wake word on the Wear OS watch.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] A foreground service listens for "hey buddy" on-device (no network audio while idle) with the chosen engine
      and a "hey buddy" model shipped in the APK (or a key read from Buddy's settings if Porcupine), with a
      persistent notification as Android requires.
- [ ] "hey buddy" + request in one breath ("hey buddy what's the weather like") sends the request (without the
      wake phrase) to Buddy and the reply is spoken; "hey buddy" alone starts listening for the request.
- [ ] A later "hey buddy, <follow-up>" goes to the same chat as the previous wake-word turn (same chat as Buddy's
      voice/Talk path), so context carries over.
- [ ] While the reply is being spoken, "hey buddy, stop" (or "hey buddy" + stop/ok stop talking) stops speech
      within ~1 s; the mic uses AEC (VOICE_COMMUNICATION source or AcousticEchoCanceler) during playback.
- [ ] Listening pauses during phone calls and while another app records (e.g. a voice recorder / call app), and
      resumes on its own afterwards; Buddy's own voice paths (Talk, Live, dictation) don't fight the detector for
      the mic.
- [ ] On by default after install/update (with the mic permission granted); a Settings → Voice switch turns it
      off/on, plus a quick toggle (bubble hold menu and/or Quick Settings tile) in Buddy's existing UI style; the
      state survives restart and reboot.
- [ ] `buddy_settings` can read and change the on/off setting (feature 005 settings coverage).
- [ ] Battery impact (measured or sourced estimate, and what drives it) is written into the user docs and the
      feature folder.
- [ ] Unit tests cover the pure logic (wake-phrase stripping, stop-command detection, pause/resume state); CI
      (build.yml) is green on the branch.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->

- 2026-11-22 (Nicholas): Answer while locked = yes. Bypass chats run without approval cards = fine ("approval cards still need unlock"). Q1 and Q4: the planner's recommendations (a).
