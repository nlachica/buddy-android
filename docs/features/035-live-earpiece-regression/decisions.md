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


## 2026-11-22 - The record is on disk before Android's volume is touched (plan review round 2, F1)
- Options: save after the write (a kill in between loses the original level); save `saved` and `set` before the
  write (a kill around a later key write still leaves a level that matches neither); also save `from`, the level
  before the write, until the write is done.
- Choice: `saved`, `set`, `from`, with `commit()` before `setStreamVolume`; `from` is cleared after it. A restart
  that finds the speaker at `from` or `set` treats it as Buddy's level. The give-back clears the record after its
  write. One line at the top of `mediaStep`; the other rules don't change.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - Media muted or at 0: Buddy leaves it alone (plan review round 2, F2)
- Options: take and restore as for any level (restoring the 0 Android reports for a muted stream erases the level
  behind the mute); leave a muted stream alone; unmute for the call and mute again after.
- Choice: leave it alone, before and during the call; the record stays until Media is unmuted on the speaker. The
  call is silent while muted, as today. Unmute-and-remute needs the mute state saved and restored too: a follow-up
  only if Nicholas asks.
- By: Planner (claude-fable-5-2)

## 2026-11-22 Orchestrator: plan loop ended without APPROVE (exit 3)
- Round 3's only finding (F1: re-check speaker-only route, mute and Media index after a successful commit and right
  before setStreamVolume; skip the write and keep the old record on any failure) is accepted as written; the coder
  applies it and adds the failed-commit / route-change-during-commit tests. No further plan round.
- Diagnosis accepted: the 12:23 call was on the speakers at media volume 6/15 (-29.5 dB), not the earpiece.
- Waiting on Nicholas for "Questions for Nicholas" #1 (a/b/c) before the code loop.
- 2026-11-22 Nicholas answered Q1: (a), the recommended option (volume keys set loudness, Media mirrored during the
  speaker call and restored afterwards). Proceed to the code loop.

## 2026-11-22 - Round 3 F1 and what the tests can reach (Coder)
- Options: unit-test `follow()` with a fake AudioManager and Prefs; keep `follow()` thin, test the pure rules
  (`mediaStep`, `mediaOnSpeaker`, `mediaFor`) and cover the wrapper by review.
- Choice: the second; a fake layer is an abstraction nobody asked for. `follow()` does what round 3 F1 asks: a take or
  a key write commits the record first (a failed commit writes nothing); then asks again for the speaker alone, the
  mute and the same Media index; if any of those fails it puts the old record back and writes nothing. A give-back asks
  again too and, on failure, keeps its record for the next tick. The failed-commit and route-change-during-commit
  cases are therefore not unit tests: CI-only for the build, phone-only for the behaviour.
- Also chosen: `Prefs.liveMedia` is one string "saved,set,from"; `seen` is memory-only (-1 after a load); `follow()`
  catches RuntimeException around the whole body so a refused write can't end the poll (the next tick asks again);
  "volume: media muted, left alone" is logged once per session (reset in `finish()`).
- By: Coder (claude-sonnet-5-5)

## 2026-11-22 - Plan Step 1 not recorded (Coder)
- The orchestrator's Step 1 phone check (keys move Call not Media; Media at the top is loud) has no entry in this file
  and no capture in the folder. The code follows the plan as approved; Step 5's phone checks cover the same ground
  (Risk 1: if the keys already move Media, or the voice stays quiet, back to the Planner).
- By: Coder (claude-sonnet-5-5)

## 2026-11-22 - Code review round 1 (Coder)
- Options: keep `follow()` untested at the write order; extract the order into `AudioRoute.change()` with a three-method `Vol` interface.
- Choice: the second (reviewer F4; the plan requires the failed-commit and route-change tests). Supersedes "Round 3 F1 and what the tests can reach".
- Also: the recovery record lives in its own prefs file `live_media`, not in `buddy`, so Backup neither exports nor overwrites it (F3). A write Android ignored is not accepted: the record stays, the next tick retries (F2). A record change without a write (the user moved Media) is rechecked like a write (F1).
- By: Coder (claude-sonnet-5-5)

## 2026-11-22 - Code review round 2 (Coder)
- Options: a separate "pending" field next to the record; reuse `from`/`set` and keep the committed record in memory after a write not seen to apply.
- Choice: the second (reviewer F1). `change()` leaves `n[FROM]` set after an unconfirmed take write and `follow()` keeps `n`; `mediaStep` resolves it on the next speaker read and resets `seen` if the write never applied.
- F3: a failed final commit sets `unsaved`; `follow()` retries only the disk write. Not unit-tested (static Prefs); phone-only.
- F2 (baseline phone check) stays open for the orchestrator and Nicholas.
- By: Coder (claude-sonnet-5-5)

## 2026-11-22 Orchestrator: code loop ended without APPROVE (exit 3)
- Round 3 F1 (exception on the confirmation read loses the saved level): must fix. One more coder run scoped to F1
  only, then one reviewer run.
- Round 3 F2 (baseline Step 1 on the unmodified build with Nicholas): waived. The driver log already shows the speaker
  path and Media 6/15; the on-phone check moves to verify on the release build (keys change loudness, Media restored).

## 2026-11-22 - Code review round 3 (Coder)
- Options: catch in `follow()` and rebuild the record there; catch in `change()` around the setter and its confirmation read.
- Choice: the second (reviewer F1). The exception becomes an unconfirmed write where `n[FROM]` is already set, so the round 2 keep-and-resolve path handles it and the test reaches it through `change()`.
- F2 waived by the orchestrator.
- By: Coder (claude-sonnet-5-5)
