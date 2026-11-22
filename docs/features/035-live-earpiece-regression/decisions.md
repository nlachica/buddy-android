# Decisions: 035-live-earpiece-regression
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-22 - The call was on the loudspeakers; the fault is loudness, not the route
- Options: treat it as the brief suspects (the speaker is requested, the real output is the earpiece: a Samsung quirk
  or a 033/034 regression) and change routing; read the phone's own records of the 12:23 call first.
- Choice: the records (`evidence-2026-11-22.log`). The driver enabled `voip-comm-dual-speaker` for the whole call and
  never a handset path. The WebView's voice is a media stream (`USAGE_MEDIA`), mixed at media index 6 of 15
  (-29.5 dB) while the call volume sat at 15, and the volume keys move the call volume during a call. So the voice
  was about 30 dB too quiet, from both speakers, one of them at the ear. No routing change. Not a regression: no call
  audio code changed since v2.3.16-fork.1, and the speaker case was never verified on the phone (030, 031).
- By: Planner (claude-fable-5-2)

## 2026-11-22 - In a speaker call the media volume follows the call volume, and is put back after
- Options: (a) media follows the call volume while the speaker is the confirmed call device, restored afterwards;
  (b) media to full for the call; (c) no code, Nicholas raises Media; (d) Buddy plays the voice itself as a call
  stream; (e) make the WebView's stream a call stream.
- Choice: (a). It is the only one where the volume keys work in a call. (d) is feature-sized, lowers voice quality
  (16 kHz tap) and takes the echo canceller's reference away. (e) has no lever: Chromium picks the usage and offers a
  page no output device on a phone. Nicholas may pick (b) or (c) (plan Question 1).
- By: Planner (claude-fable-5-2); Nicholas may overrule

## 2026-11-22 - Volume writes only while media is on the built-in speaker; the saved value survives a crash
- Options: write and restore unconditionally; gate every write and the restore on "media plays on the speaker now"
  and keep the saved index in Prefs until it is given back.
- Choice: gate and persist. `setStreamVolume` writes the index of whatever device media is on at that moment, so an
  ungated restore after a headset connects would put the speaker's value on the headset, and the call's teardown
  passes through the earpiece for an instant. A value left by a crash is given back by the next voice session.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - Speaker only; headsets and the watch keep today's loudness
- Options: follow the call volume on every call device; the speaker only.
- Choice: the speaker only. The brief's goal is the loudspeaker, no headset complaint exists, and Android's public
  API can't write another device's index, so a mid-call device switch would need per-device bookkeeping.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - The diagnosis is confirmed on the phone before code (plan Step 1), not sent back to research
- Options: stop with an Open question and another Researcher run; a one-minute check with a stop rule as Step 1.
- Choice: Step 1. The driver log is direct evidence, and the check (drag Media up during a call) needs Nicholas's
  ears, which a Researcher run doesn't have.
- By: Planner (claude-fable-5-2)
