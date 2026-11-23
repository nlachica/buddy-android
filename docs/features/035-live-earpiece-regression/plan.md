# Plan: 035-live-earpiece-regression

## Goal
Live voice plays on the loudspeaker whenever no Bluetooth headset is connected and the watch isn't worn (order:
Bluetooth > watch if worn > speaker > earpiece last resort), and Nicholas hears it as a speakerphone call. Done when
verified on the phone: in a live call with no headset and the watch off the wrist, the voice comes from the
loudspeaker at call loudness, the volume keys change it, and the phone's logs show the speaker path.

The brief's "earpiece last resort" is not built and not changed here: with no safe device the code ends the call
(`AudioRoute.java:450-453`, pinned by 030/031), and 033's orchestrator ruling stands (no code; moot on a phone that
always has a speaker; `docs/features/033-watch-worn-audio/decisions.md:133-136`). Nicholas's literal wording is still
unresolved and goes into the report, without blocking.

Non-goals:
- Changing which device a call picks (030, 031, 033: `liveDevice`, `live()`, the worn gate). The pick was right.
- An earpiece fallback (above).
- Android 12 and 12L (API 31-32): Buddy leaves the media volume alone there (no public way to know media is on the
  speaker). The phone is Android 16.
- Buddy playing the voice itself, or any change to the WebView's audio.
- Loudness on a headset or the watch.
- Read aloud, dictation, Hey Buddy.

## Design

### What happened (sources: `evidence-2026-11-22.log` in this folder)
The 12:23 call did not play on the earpiece. It played on the loudspeakers, very quietly.

1. Buddy asked for the speaker and Android kept it for the whole call. No `route: slipped` line in `live.log`.
2. The audio driver switched on `voip-comm-dual-speaker` at 12:23:48 and nothing else until the call ended. No
   handset or receiver path was ever enabled. `dual-speaker` is the bottom speaker plus the top one, and the top one
   is the earpiece's own driver.
3. The WebView plays the voice as a media stream (`USAGE_MEDIA`, `AUDIO_STREAM_MUSIC`), not a call stream. Android
   mixed it at the media volume, index 6 of 15, a gain of 0.0335 (-29.5 dB). The call volume was at its maximum,
   15 of 15, and has no effect on a media stream.
4. During a call the volume keys move the call volume (the phone is in communication mode), so pressing volume up
   did nothing to the voice (unverified, from Android's documented behaviour; Step 1 checks it).

So the voice was about 30 dB quieter than a speakerphone call, coming from both speakers, one of which sits at the
ear. That is what "the hold up to the phone ear thing" was. The brief's suspicion (the request is right, the real
output is the earpiece) is not what the driver log shows.

Not a regression from 033 or 034: `git diff v2.3.16-fork.1 HEAD` changes no call audio code (CodexLive gets only the
wake-word microphone release, AudioRoute one settings label). The speaker case was never verified on the phone:
030's `verify.md` is empty, 031's device rows are pending, and every logged call since 030 was on the watch or the
headset.

### The fix: in a speaker call, the media volume follows the call volume
While a phone live call plays its voice on the phone, the speaker is the confirmed communication device, and Android
says media plays on the speaker alone, Buddy sets the media volume to the call volume's level, follows the call
volume when it changes, and puts the media volume back when any of that stops being true or the call ends. The voice
is then as loud as the call volume says, and the volume keys work, as in any call. Android 13+ only. All in
`AudioRoute` (it already owns the call's poll and the speaker confirmation), plus one accessor in `LiveWatch`:

- `mediaFor(call, callMax, mediaMax)`: pure. `max(1, ceil(call * mediaMax / callMax))`. On this phone both scales
  are 15, so the media index equals the call index.
- `mediaOnSpeaker(routed)`: pure. True only when Android's routed devices for `USAGE_MEDIA`
  (`getAudioDevicesForAttributes`, API 33+) are exactly one device and it is the built-in speaker. Empty, mixed,
  several, or no answer (below 33): false. `setStreamVolume` writes the index of the device media is on at that
  moment, so every read, write and restore is gated on this.
- `mediaStep(speakerCall, now, call, want, state)`: pure, the whole rule, so it is unit-tested as sequences.
  `state` = `saved` (Nicholas's level, to put back), `set` (the level Buddy last set), `from` (the level before a
  write that may not have reached Android; only ever set in a state loaded after a crash), `callSeen` (the call
  index last mirrored); -1 = none. `now` = the speaker's media index, 0 when Media is muted; `want` =
  `mediaFor(call)`. Returns the index to write, or -1.
  - `now == 0` (Media muted or at zero): nothing is written and the state is untouched. Android reads a muted
    stream as 0, so Buddy can't know the level behind the mute, and a write would unmute it. Buddy never saves 0
    and never writes 0.
  - `from` set (once, on the first step after a load): `now == from` means the interrupted write never happened, so
    `set = from`. Either way `from = -1`. The rules below then apply unchanged.
  - Speaker call, nothing saved (take): `saved = now`, write `want`.
  - Speaker call, `now != set`: Nicholas moved Media himself. `saved = set = now`: his level stays, and it is what he
    gets back. This rule writes nothing itself.
  - Speaker call, `call != callSeen` (a volume key): write `want`.
  - Every write: `set = want`, `callSeen = call`.
  - Not a speaker call, something saved (give back): `now == set` writes `saved`; otherwise the level is no longer
    Buddy's (he changed it since) and nothing is written. Both clear the state.
- `follow(am, speakerCall)`: the thin wrapper, on the main thread like the rest of `AudioRoute`.
  - `speakerCall` = `live()` applied the speaker, Android reports it (`applied.equals(curKey)`, speaker key), and the
    voice is not on the watch (`!LiveWatch.onWatch()`, new: the `watch` flag `LiveWatch.Call.move()` sets,
    `LiveWatch.java:254-258`). When the watch takes the voice the next tick (300 ms) gives the level back; when the
    watch drops or comes off the wrist, the next tick takes it again.
  - `mediaOnSpeaker` false: the tick does nothing and the state stays (a headset came; the next tick, `finish()` or
    the next voice session's first tick tries again).
  - Otherwise read `now` (`isStreamMute(STREAM_MUSIC)` ? 0 : `getStreamVolume`), run `mediaStep` on a copy of the
    state. Before a write the routed query is asked again; if it no longer says speaker alone, the tick changes
    nothing.
  - A take or a key write, in this order: `Prefs` gets `saved`, `set` = the new level and `from` = `now`, with
    `commit()` (on disk before Android is touched; `apply()` can be lost with the process); `setStreamVolume`; the
    copy becomes the state; `Prefs` gets `from = -1`. Killed anywhere in between, the next session finds the speaker
    at `from` or at `set` and both count as Buddy's level. A `setStreamVolume` that throws leaves the memory state
    as it was, so the next tick tries again.
  - A give-back: `setStreamVolume(saved)`, then clear memory and `Prefs`. Killed in between, the next session finds
    the speaker at neither level and drops the record. A level he changed (in a call or since): `Prefs` updated or
    cleared, no write.
  - Log `volume: media 6 -> 15 (call 15/15)`, `volume: media back to 6`, `volume: media left at 3 (changed since)`,
    or once per call `volume: media muted, left alone`.
  - `use()` loads `saved`, `set` and `from` left in `Prefs` by a crash, so the next session's first tick gives the
    level back or discards it by the same rule (or, in a speaker call, carries on from it).

Why this and not something else:
- It needs no cooperation from the WebView, and it stays correct if a later WebView plays the voice as a call stream
  (Chromium's source says it should in communication mode): media then simply equals the call volume for the call.
- The route logic and its tests are untouched.
- Buddy only ever undoes its own write: a level Nicholas set later is never overwritten.

Alternatives considered:
- Make the WebView's stream a call stream: no lever. Chromium picks the usage itself, and a page can't choose an
  output device on a phone (evidence, Chromium section).
- Buddy plays the voice itself through a call-usage `AudioTrack` (the page's 16 kHz tap exists for the watch):
  feature-sized, lower voice quality, and the page's echo canceller loses its reference.
- Raise the media volume once at call start, no following: the volume keys would still do nothing.
- Volume keys handled by Buddy's window: only works while Buddy is in front, and doesn't fix the starting level.
- Gain in the page: the voice is already near full scale; the cut is applied after the page.
- No code, tell Nicholas to raise Media: fails the goal on the next call (Question 1, option c).

## Steps
1. **Confirm on the phone, before any code** (orchestrator with Nicholas, no build, 2 minutes). Audio device =
   Auto, headset disconnected, watch off the wrist.
   1. Before the call, record the speaker's two indices from `dumpsys audio`: STREAM_MUSIC `2 (speaker)` (M0) and
      STREAM_VOICE_CALL `2 (speaker)` (C0).
   2. Start a live call. `live.log` must show `route: 2:SM-G990W applied` and no `watch: playing there`.
   3. While Buddy talks, press volume down once and read both indices again. Expected: Call moved (C0 - 1), Media
      still M0, the voice no quieter. **If Media moved instead: stop, back to the Planner** (the keys already set
      the voice's loudness, so following the call volume has no reason; only the starting level is left).
   4. Drag **Media** to the top. Expected: the voice becomes loud from the bottom speaker. Capture
      `logcat -d | grep -E "enable_snd_device|output [0-9]+, stream 3,"` for the call. **If the voice stays quiet or
      at the ear: stop, save the capture in this folder, back to the Planner.**
   5. On every exit, pass or stop, before hanging up: put Media back to M0 and Call back to C0, and confirm both
      with `dumpsys audio`. (Left at the top, Media would equal Call and the fixed build's check would pass without
      Buddy doing anything.)
   Record the indices and the result in `decisions.md`.
2. **Pure rules and tests.** `AudioRoute.java`: `mediaFor`, `mediaOnSpeaker`, `mediaStep`. `AudioRouteTest.java`: the
   cases in the Test plan, written first.
3. **State and wiring.** `AudioRoute.java`: `follow()` holding the state; called from `live()` with the speaker-call
   flag, and with `false` from `reconcile()` when the live rule doesn't hold, from the poll's idle branch and from
   `finish()`; `use()` loads the saved pair. `LiveWatch.java`: `static boolean onWatch()` (the call's `watch` flag).
   `Prefs.java`: `liveMedia(c)` get/set for `saved`, `set` and `from` (-1 = none), written with `commit()`. Class
   comment: one sentence on 035.
4. **Docs.** `README.md`: one sentence in the Audio device bullet (on Android 13+, a live call on the phone speaker
   follows the call volume; Buddy moves the media volume for the call and puts it back, unless you changed it
   meanwhile). `decisions.md`: anything the Coder chose.
5. **Release and verify on the phone** (orchestrator: release check, bump, tag, `update_buddy`), then the manual
   checks in the Test plan. `verify.md` gets the evidence.

## Test plan
- Unit (CI `testGithubDebugUnitTest`; the JVM doesn't start on the phone):
  - `mediaFor`: 15/15 on a 15 scale gives 15; 8 gives 8; 1 gives 1; a 7-step call scale: 7 gives 15, 4 gives 9,
    1 gives 3; never 0.
  - `mediaOnSpeaker`: [speaker] true; [earpiece] false; [A2DP] false; [speaker, A2DP] false; empty false; null (below
    API 33: Media is never touched) false.
  - `mediaStep`, as sequences from media 6, call 15:
    - Normal call: take writes 15 (`saved` 6); steady ticks write nothing; call 13 writes 13; hang-up with `now` 13
      writes 6 and clears.
    - Changed by hand in the call: `now` 10 writes nothing, `saved` 10; a later key (call 14) writes 14; hang-up
      writes 10.
    - Restart after a crash (state loaded as `saved` 6, `set` 15, `callSeen` none): not a speaker call, `now` 15
      writes 6; `now` 3 writes nothing and clears (his newer level stays); a speaker call with `now` 3 takes from 3
      and gives back 3.
    - Restart with a headset connected: the wrapper's gate, covered by the `mediaOnSpeaker` rows (no step, the pair
      stays) and then the restart rows above once media is on the speaker.
    - Killed around the take (loaded `saved` 6, `set` 15, `from` 6), not a speaker call: `now` 6 (Android never got
      the write) writes 6, no change, and clears; `now` 15 (it did) writes 6. In a speaker call with `now` 6: writes
      15, `saved` still 6.
    - Killed around a key write 15 to 13 (loaded `saved` 6, `set` 13, `from` 15), not a speaker call: `now` 15 writes
      6; `now` 13 writes 6; `now` 3 writes nothing and clears.
    - Muted before the call (`now` 0): speaker-call ticks write nothing and save nothing; unmuted at 6 mid-call:
      take, writes 15, `saved` 6.
    - Muted mid-call (after the take, `now` 0): ticks, a key (call 13) and the hang-up write nothing and the state
      stays; a later session with `now` 15 writes 6, with `now` 4 writes nothing and clears.
    - Watch: speaker call true -> false (the watch took the voice) writes 6; false -> true takes again.
    - Nothing saved, not a speaker call: nothing written.
  - 030/031/033 tests unchanged and green (the route logic isn't touched).
- Done-when "plays on the loudspeaker, verified on the phone", after install, no headset, watch off the wrist. Start
  with different levels on the speaker (as recorded in Step 1: Media 6, Call 15; set them so if not). The speaker's
  Media index below is STREAM_MUSIC `2 (speaker)` in `dumpsys audio`, read by the orchestrator each time:
  1. Before the call: Media index N, Call index C, N != C.
  2. Start a live call, let Buddy talk. Nicholas: loud from the speaker, like a speakerphone call. Media index = C.
  3. `live.log`: `route: 2:SM-G990W applied`, then `volume: media N -> C (call C/15)`.
  4. `logcat`: `enable_snd_device: snd_device(93: voip-comm-dual-speaker)` and
     `checkAndSetVolume: output …, stream 3, index C, devices 0x2`.
  5. Volume down, read the index (C - 1); volume down again, read it (C - 2). The voice gets quieter each time.
     `live.log` has a `volume:` line ending at C - 2 (one or two lines: two fast presses can land in one 300 ms
     tick).
  6. End the call. `live.log`: `volume: media back to N`. Media index = N.
- Watch (Auto, watch worn, no headset): from `watch: playing there` on, and for as long as it plays, the speaker's
  Media index is N (a `volume:` take and give-back pair in the first second is fine). Take the watch off mid-call:
  the voice moves to the phone, `volume: media N -> C`, Media index = C. Hang up: N.
- Headset during a speaker call (LS2, one call). Before: note the speaker's Media index N and LS2's `bt_sco` and
  `bt_a2dp` Media indices. Start on the speaker (index C). Switch LS2 on: `route: 7:LS2 Freecom applied`. Switch
  LS2 off: the speaker is applied again, Media index = the call index. Hang up: `volume: media back to N`.
  Pass: LS2's two indices are the same at every read (after it connects, after it leaves, after hang-up) and the
  speaker ends at N. While LS2 has the call the speaker's index may read C or N (Risk 3).
- Hang-up while the headset has the call (LS2, the deferred give-back). Start on the speaker (index C), switch LS2
  on, hang up with LS2 still connected: no `volume: media back` line, LS2's two indices unchanged. Switch LS2 off:
  the speaker's Media index may still read C. Start a live call on the speaker and end it: `volume: media back to
  N`, Media index = N.
- Media muted (volume panel, speaker at N). Muted before the call: the call has `volume: media muted, left alone`
  and no other `volume:` line, the voice is silent; hang up, unmute: Media index = N. Muted during a speaker call
  (after `volume: media N -> C`): no further `volume:` line, also not on a volume key; unmute in the call, hang up:
  `volume: media back to N`, or `media left at …` if unmuting gave a level other than C (then that level stays).
- Order still holds (regression, by `live.log`): a call started with LS2 on (`route: 7:LS2 Freecom applied`, no
  `volume:` line).
- Crash path, by review: `Prefs` is committed before each `setStreamVolume` that keeps a record and cleared after
  the give-back; `use()` loads the three values. What a restart can find is covered by the unit sequences above.

## Risks
1. The diagnosis is wrong and the sound really is at the ear, or the keys already move Media. Step 1 catches both
   before code is written.
2. Buddy changes a system volume. If Buddy is killed mid-call the media volume stays at the call level until the
   next voice session gives it back (only if it is still the level Buddy set); music started on the speaker in
   between is louder than Nicholas left it. One case overwrites his choice: Buddy is killed in the instant between
   a key write and the `from` clear, and he then sets Media to exactly the level before that write.
3. A headset that connects during a speaker call and stays past the session, or Media muted at hang-up: the
   speaker's media volume can stay at the call level (behind the mute) until a later voice session sees media on
   the speaker, unmuted, again.
4. The routed-devices answer is a prediction and routing can change between the question and the write. Buddy asks
   again right before each write, which leaves a window of a few milliseconds: a headset that takes media exactly
   then gets one write on its own index (the call level, or the saved level). Android has no public call that names
   the device, so this can't be closed, only made rare.
5. If this phone's routed answer during a speaker call isn't the speaker alone, Buddy writes nothing: the installed
   check shows no `volume:` line and the feature comes back to the Planner.
6. The first fixed call is about 30 dB louder than today's (call volume is at 15). That is what 15 means; volume
   down works.
7. Other media playing on the phone during a speaker call gets the call's level too. Not while the voice is on the
   watch: the level is given back for that time.
8. The media curve is steeper than a call's: a few steps down are quieter than the same steps in a phone call. Tune
   `mediaFor` after the phone check if it feels wrong.
9. When the watch drops mid-call the voice is at the old media level for up to one tick (300 ms).
10. With Media muted or at 0 a speaker call is silent, as today: Buddy doesn't unmute, and the volume keys move
    the call volume. Unmuting in the call starts the following. Unmuting for the call and muting again after is a
    follow-up if Nicholas wants it (the mute state would have to be saved and put back too).
11. A headset call has the same media-volume behaviour (LS2: media index 7 on its call link). Not reported, not in
    the brief; a follow-up if Nicholas finds headset calls quiet.

## Open questions
None. Step 1 is a check with a stop rule, not a blocker for review.

## Questions for Nicholas
1. In a live call on the phone speaker, how should loudness work?
   - **a (recommended):** the volume keys and the call volume set how loud Buddy is, as in any call. Buddy moves
     your media volume to match during the call and puts it back afterwards. Why: it is the only option where the
     volume keys work during a call. If you move Media yourself meanwhile, that is what you keep.
   - b: Buddy turns the media volume to full for the call and puts it back afterwards. Simpler, but the keys still
     do nothing and it is always at full.
   - c: no change in Buddy. The voice follows your media volume; you raise Media in the volume panel yourself.
