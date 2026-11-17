---
type: brief
feature: {{SLUG}}
created: {{DATE}}
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: {{SLUG}}

## Ask (verbatim, never edit)
{{ASK}}

## Goal
Nicholas heard nothing in live voice calls: on the phone with audio_device=watch (018), the watch got 0 bytes and the page level stayed 0.000 the whole call; from the watch's new-chat call, the watch said 'listening' but nothing happened. diagnosis.md has the root cause (page audio element paused at 3.95 s and never resumed, CodexLive.java:971; tap only sees audio while it plays, CodexSpeech.java:386) and the smallest fixes: resume the audio element whenever it pauses, resume a suspended AudioContext, log both in the 10 s rtp line, log audio_device old->new on change, keep the previous live.log.

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] A live call with Watch picked: voice is heard on the watch; with phone/speaker picked, heard on the phone (level > 0 in rtp lines).
- [ ] A call started from the watch (incl. new chat) is heard both ways.
- [ ] live.log shows audio element/AudioContext state and setting changes; the previous call's log is kept as live.log.1.
- [ ] CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
