# Plan: 035-live-earpiece-regression

## Goal
Live voice plays on the loudspeaker whenever no Bluetooth headset is connected and the watch isn't worn (order:
Bluetooth > watch if worn > speaker > earpiece last resort), and Nicholas hears it as a speakerphone call. Done when
verified on the phone: in a live call with no headset and the watch off the wrist, the voice comes from the
loudspeaker at call loudness, the volume keys change it, and the phone's logs show the speaker path.

Non-goals:
- Changing which device a call picks (030, 031, 033: `liveDevice`, `live()`, the worn gate). The pick was right.
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
While a phone live call is on and the speaker is the confirmed communication device, Buddy sets the media volume to
the call volume's level, follows the call volume when it changes, and puts the media volume back when the call
leaves the speaker or ends. The voice is then as loud as the call volume says, and the volume keys work, as in any
call. Three small parts, all in `AudioRoute` (it already owns the call's poll and the speaker confirmation):

- `mediaFor(call, callMax, mediaMax)`: pure. `max(1, ceil(call * mediaMax / callMax))`. On this phone both scales
  are 15, so the media index equals the call index.
- `mediaOnSpeaker(routed, connected, mode)`: pure. True when media plays on the built-in speaker now: API 33+,
  Android's routed devices for `USAGE_MEDIA` contain the speaker; below 33, normal mode and no headset connected.
  `setStreamVolume` writes the index of the device media is on at that moment, so every write and the restore are
  gated on this. It keeps Buddy from writing a speaker value onto a headset or the earpiece.
- `follow(am, speakerCall)`: the state, on the main thread like the rest of `AudioRoute`.
  - `speakerCall` = `live()` applied the speaker and Android reports it (`applied.equals(curKey)`, speaker key).
  - Take: first tick with `speakerCall && mediaOnSpeaker` and nothing saved: save the media index (memory and
    `Prefs`), set media to `mediaFor(call)`, log `volume: media 6 -> 15 (call 15/15)`.
  - Follow: the call index differs from the last one mirrored: set media again, log it. A change Nicholas makes to
    the Media slider itself is left alone.
  - Give back: any tick that isn't a speaker call, with a value saved and `mediaOnSpeaker`: set media to the saved
    index, clear it (memory and `Prefs`), log `volume: media back to 6`. If media isn't on the speaker (a headset
    came), the value stays saved and the next tick, `finish()`, or the next voice session's first tick tries again.
  - `use()` loads a saved value left in `Prefs` by a crash, so the next session gives it back (or, if that session is
    a speaker call, keeps it as the value to restore).

Why this and not something else:
- It needs no cooperation from the WebView, and it stays correct if a later WebView plays the voice as a call stream
  (Chromium's source says it should in communication mode): media then simply equals the call volume for the call.
- The route logic and its tests are untouched.

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
1. **Confirm on the phone, before any code** (orchestrator with Nicholas, no build, 1 minute). Live call, no headset,
   watch off the wrist or Audio device = Speaker. While Buddy talks: press volume up once and note which slider
   shows (expected: Call). Open the volume panel and drag **Media** to the top. Expected: the voice becomes loud from
   the bottom speaker. Capture `logcat -d | grep -E "enable_snd_device|output [0-9]+, stream 3,"` for the call.
   If the voice stays quiet or at the ear with Media at the top: stop, save the capture in this folder, back to the
   Planner. Record the result in `decisions.md`.
2. **Pure rules and tests.** `AudioRoute.java`: `mediaFor`, `mediaOnSpeaker`. `AudioRouteTest.java`: the cases in the
   Test plan, written first.
3. **State and wiring.** `AudioRoute.java`: `follow()` with `mediaSaved` / `callSeen`; called from `live()` with the
   confirmed-speaker flag, and with `false` from `reconcile()` when the live rule doesn't hold, from the poll's idle
   branch and from `finish()`; `use()` loads the saved value. `Prefs.java`: `liveMedia(c)` get/set (int, -1 = none).
   Class comment: one sentence on 035.
4. **Docs.** `README.md`: one sentence in the Audio device bullet (a phone-speaker live call follows the call volume;
   Buddy moves the media volume for the call and puts it back). `decisions.md`: anything the Coder chose.
5. **Release and verify on the phone** (orchestrator: release check, bump, tag, `update_buddy`), then the manual
   checks in the Test plan. `verify.md` gets the evidence.

## Test plan
- Unit (CI `testGithubDebugUnitTest`; the JVM doesn't start on the phone):
  - `mediaFor`: 15/15 on a 15 scale gives 15; 8 gives 8; 1 gives 1; a 7-step call scale: 7 gives 15, 4 gives 9,
    1 gives 3; never 0.
  - `mediaOnSpeaker`: routed [speaker] true; routed [earpiece] false; routed [A2DP] false; no routed answer with a
    headset connected false; no routed answer, no headset, normal mode true; same in communication mode false.
  - 030/031/033 tests unchanged and green (the route logic isn't touched).
- Done-when "plays on the loudspeaker, verified on the phone", after install, no headset, watch off the wrist:
  1. Start a live call, let Buddy talk. Nicholas: loud from the speaker, like a speakerphone call.
  2. `live.log`: `route: 2:SM-G990W applied`, then `volume: media N -> M (call C/15)`.
  3. `logcat`: `enable_snd_device: snd_device(93: voip-comm-dual-speaker)` and
     `checkAndSetVolume: output …, stream 3, index M, devices 0x2`, with M = the call index.
  4. Volume down twice during the call: the voice gets quieter, `live.log` shows two more `volume:` lines.
  5. End the call. `live.log`: `volume: media back to N`. `dumpsys audio`: STREAM_MUSIC `2 (speaker): N`.
- Order still holds (regression, by `live.log`): one call with LS2 on (`route: 7:LS2 Freecom applied`, no `volume:`
  line), one with the watch worn (`watch: playing there`).
- Crash path, by review: `Prefs` holds the saved index from take to give back; `use()` loads it.

## Risks
1. The diagnosis is wrong and the sound really is at the ear. Step 1 catches it before code is written.
2. Buddy changes a system volume. If Buddy is killed mid-call the media volume stays at the call level until the
   next voice session gives it back; music started on the speaker in between is louder than Nicholas left it.
3. A headset that connects during a speaker call and stays past the session: the speaker's media volume stays at the
   call level until a later session sees media on the speaker again.
4. The first fixed call is about 30 dB louder than today's (call volume is at 15). That is what 15 means; volume
   down works.
5. Other media playing on the phone during a speaker call gets the call's level too.
6. The media curve is steeper than a call's: a few steps down are quieter than the same steps in a phone call. Tune
   `mediaFor` after the phone check if it feels wrong.
7. With the voice on the watch the speaker is still the confirmed device, so the media volume follows the call volume
   there too. Harmless (the page is muted), and it is right the moment the voice comes back to the phone.
8. A headset call has the same media-volume behaviour (LS2: media index 7 on its call link). Not reported, not in
   the brief; a follow-up if Nicholas finds headset calls quiet.

## Open questions
None. Step 1 is a check with a stop rule, not a blocker for review.

## Questions for Nicholas
1. In a live call on the phone speaker, how should loudness work?
   - **a (recommended):** the volume keys and the call volume set how loud Buddy is, as in any call. Buddy moves
     your media volume to match during the call and puts it back afterwards. Why: it is the only option where the
     volume keys work during a call.
   - b: Buddy turns the media volume to full for the call and puts it back afterwards. Simpler, but the keys still
     do nothing and it is always at full.
   - c: no change in Buddy. The voice follows your media volume; you raise Media in the volume panel yourself.
