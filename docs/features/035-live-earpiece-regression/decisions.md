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

## 2026-11-22 - Buddy only undoes its own volume write (plan review F2)
- Options: restore the saved index whenever possible; track the level Buddy last set and restore only while the
  speaker still has it.
- Choice: track `set` next to `saved`, both in Prefs. A level Nicholas chose later (after a crash, or on the Media
  slider during the call) is never overwritten: outside a speaker call the stale value is dropped, inside one his
  level becomes the value to put back. One pure step function, tested as sequences.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - Following needs the voice on the phone, not just the speaker route (plan review F3)
- Options: follow whenever the speaker is the confirmed call device (also while the watch plays); also require that
  the voice isn't on the watch.
- Choice: require it (`LiveWatch.onWatch()`). Muting the page silences only Buddy; other apps on the phone would
  get the raised media volume while the watch talks.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - Android 13+ and speaker-only routing; no fallback for Android 12 (plan review F4, F5)
- Options: keep the "normal mode and no headset" fallback below API 33; find another speaker check for 31-32; limit
  to API 33+ with the routed devices being exactly the built-in speaker, asked again before each write.
- Choice: API 33+, exactly the speaker. The fallback was false in communication mode, so it could take the volume
  and then never follow, and it proved nothing about where media plays. The phone is Android 16. The remaining
  window between the question and the write is a stated risk: no public API writes a named device's index.
  Supersedes the fallback in "Volume writes only while media is on the built-in speaker" above.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - Earpiece last resort: 033's ruling carried forward (plan review F6)
- Options: build the brief's "earpiece last resort"; keep the code (no safe device ends the call) and 033's ruling.
- Choice: keep. Moot on a phone that always has a speaker, and outside the reported call. Nicholas's literal wording
  stays unresolved and is relayed in the report.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - Step 1 can't leave the sliders changed, and the keys moving Media stops the plan (plan review F1)
- Options: the one-minute listening check as written; record both speaker indices first, read which one a key
  moves, put both back on every exit.
- Choice: the second. Media left at the top equals the call level, so the fixed build's check would pass with Buddy
  doing nothing; and if the keys already move Media, following the call volume has no reason.
- By: Planner (claude-fable-5-2)

