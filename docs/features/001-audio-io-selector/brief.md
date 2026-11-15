---
type: brief
feature: 001-audio-io-selector
created: 2026-11-15
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 001-audio-io-selector

## Ask (verbatim, never edit)
Ok first buddy fork feature, in Buddy I want to have a selector for audio/mic input. So I can choose speakerphone or my watch etc... For stuff. Make sure everything is integrated and fits into how buddy looks, and is set up. Follow yagni principal, be clean and adhere to the current code schema. Feel free to grill me before starting

## Goal
Let Nicholas choose which audio device Buddy uses for voice: the mic it listens on and the output it speaks
through (e.g. phone speaker / speakerphone, earpiece, a Bluetooth watch or headset), from Buddy's own UI,
styled like the rest of Buddy, applied everywhere Buddy uses voice. Draft: refined after grilling.

## Non-goals
- Audio routing for other apps or system-wide settings.
- New voice features beyond choosing the device.

## Done when
1. Settings → Voice has an "Audio device" choice: Auto plus the devices Android offers for voice now (test phone:
   Speaker, Phone, Galaxy Watch4), styled like the card's other choices; picking one plays the sample on it.
2. The voice bar (chat and bubble; dictation and live voice) has a quick switch for the same choice, working
   mid-call.
3. With a device chosen, live voice, voice input and read aloud use it (or the Settings note says honestly which
   path can't follow it, after on-device testing).
4. The choice survives a restart; a missing device means Auto (Settings says so), and it's used again on reconnect.
5. Auto behaves as before; ~2 s after Buddy's voice stops the phone is back to normal audio mode; Buddy's own
   voice never auto-pauses an agent as a call.
6. CI apk job green.

## Clarifications
- 2026-11-15: Pick the device in Settings AND via a quick switch in the voice/bubble UI.
- 2026-11-15: One combined choice for mic + speaker together (like a call: Auto / Speaker / Phone / Watch...),
  not separate pickers.
- 2026-11-15: Remember the choice; when that device isn't connected, fall back to Auto.
