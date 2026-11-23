# Research: 035-live-earpiece-regression
<!-- Researcher writes here. Sources on every claim. -->

## 2026-11-22 Researcher (claude-sonnet-5-5)

## Summary
- The 030/031 fix only controls the *communication device request* (`AudioManager.setCommunicationDevice`). The brief's `dumpsys`
  shows that request was made and accepted (speaker), yet audio is on the earpiece. So the fix's premise (request == output)
  fails on this phone; nothing in the code checks the real output. (brief.md Evidence; `AudioRoute.java:399,440`)
- `live()` confirms only via `am.getCommunicationDevice()` (`AudioRoute.java:416-424`), i.e. Android's *requested* device, not the
  actual playback route. It can't see an earpiece output while the request says speaker.
- Live audio plays from a WebView `<audio id=out autoplay>` fed by WebRTC (`CodexLive.java:945`); Buddy can't set its usage or
  sink. The WebView/Chromium owns `MODE_IN_COMMUNICATION` (comment `AudioRoute.java:409-411`); Buddy never sets mode in live.
- Only audio attributes Buddy controls: TTS `USAGE_ASSISTANT` (`Voice.java:572`) and a query of devices for that usage
  (`AudioRoute.java:107`). Neither touches the live WebView stream.
- No live-call code path uses the legacy `setSpeakerphoneOn` (grep: no hit in app/src/main for `setSpeakerphone`).
  That is an untried fallback (unverified whether it helps on this Samsung).
- Verification gap: 030 `verify.md` is an empty template (no on-phone evidence); 031 verified via unit tests built from a headset
  log. Neither shows a real speaker-output check with no headset. (docs/features/030*/verify.md; 031 verify.md:10-13)
- Recent changes since the fix (033 worn-gate, 032/034 Hey Buddy wake: `WakeMic`/`WakeService`) don't touch `AudioRoute`/`CodexLive`
  audio code, but the wake service holds an `AudioRecord` and runs a recording callback (`WakeMic.java:33`, `WakeService.java:117`).
  Whether it interferes with a call's route is unverified. `git log` for AudioRoute: last change 3fd89b1 (033, 2026-11-22 01:58).
- Brief says "yesterday's fix" (033/034), but the earpiece-guard fix is 030 (2026-11-20) + 031; 033 changed only `AudioRoute` in
  `3fd89b1`. Diff that commit's AudioRoute hunk first (not done here).

## Repo map
- `AudioRoute.java`: poll (`:~440-465`) calls `reconcile()` (`:370`); `liveRule(mode, noEarpiece)` (`:363`) sends live calls to
  `live(am)` (`:414`), which picks pick > headset > speaker via `liveAttempt`, applies with `setCommunicationDevice` (`:440`), and
  ends the call with `live_ended_no_speaker` if nothing is safe (`:446-449`). `drop()` = Android's routing (earpiece in call mode).
- `LiveWatch.java:71` `noEarpiece()` = phone live call is on (030 decision: `Call.on`); `LiveWatch.java:148`.
- `CodexLive.java`: WebView page at `:945`; `getUserMedia` mic at `:1006`; fallback own `AudioRecord(VOICE_COMMUNICATION)` `:843`.
- Held/mute lever (030 decision "muted until Android reports the device live() applied"): bridge `toWatch` bit 4 and the page mutes
  `out` while `held`. Check whether it is still wired: grep `held` in `AudioRoute.java`/`CodexLive.java` (not done; the 030 decision says it
  exists, but `live()` above has no `held =` line, so verify it wasn't dropped) (unverified).
- Tests: `app/src/test/java/app/buddy/assistant/AudioRouteTest.java`, `LiveWatchTest.java` (pure logic only).
- Prior features: `docs/features/030-live-no-earpiece/decisions.md`, `031-headset-route/`, `033-watch-worn-audio/`.

## Prior art
- 030 decisions record the same class of bug: 2026-11-19 live.log showed `none found: phone`, no `route:` line, earpiece playback.
  Fixed by always routing via `live()` (decisions.md 030). That log was a *missing request*; 035 is a *request that doesn't take effect*.
- Android docs (training knowledge, not re-fetched): `setCommunicationDevice` selects the device for communication-mode audio;
  `getCommunicationDevice` returns the selected one, and on some OEMs the stream's real route can differ. Samsung quirk is
  a hypothesis (unverified). No upstream issue search was run.

## Constraints and risks
- Can't change the WebView's audio attributes/sink; Chromium sets/restores mode and device itself (`AudioRoute.java:409-411`,
  "Chromium's later earpiece pick", 030 decisions).
- Real output can be read only via `AudioManager.getActiveRecordingConfigurations` (input only) or `AudioPlaybackConfiguration`
  (`AudioManager.getActivePlaybackConfigurations`, API 26+, `getAudioDeviceInfo()` API 28+ hidden/system-limited?) (unverified;
  Planner should check which are public API).
- Evidence in brief was captured with `watch state unknown`: the watch path (worn gate, 033) may be the actual cause if Auto chose a
  non-worn watch result then fell back wrongly. Check `/buddy` live.log for `route:`/`worn` lines (the log wasn't in `runs/`).
- Only on-phone verification is conclusive (done-when says "verified on the phone"); unit tests can't reproduce the Samsung behavior.

## Answers to open questions
None (no Open questions in plan.md).

## Suggested next facts to gather (Planner decides)
1. The live.log from the 12:23 call (route/worn/slipped lines); not in repo.
2. `adb dumpsys audio` playback section during a call for the actual out device of the WebView stream.
3. `git show 3fd89b1 -- app/src/main/java/app/buddy/assistant/AudioRoute.java` to rule 033 in or out.
