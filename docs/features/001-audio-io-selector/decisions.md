# Decisions: 001-audio-io-selector
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-15 - Routing mechanism: communication device + communication mode while Buddy's voice is on
- Options: per-stream `setPreferredDevice` (only Buddy's own `AudioRecord`s can take it); `setCommunicationDevice`
  alone (moves only communication audio, so read aloud wouldn't follow); switch the phone TTS to call usage (doesn't
  cover ChatGPT's voice in a WebView); communication device + `MODE_IN_COMMUNICATION` for as long as Buddy's voice is on.
- Choice: the last one, because it's one mechanism that reaches every path. Live voice is already a call. In
  communication mode, media (both read-aloud engines) follows the call device. A Bluetooth SCO communication device
  also carries the recording sources (`SpeechRecognizer` too). Live voice leaves the mode to the WebView, which sets
  it itself. The TTS call-usage switch stays as the fallback if on-device checks show read aloud doesn't follow.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Release by polling Buddy's voice activity, not per-engine end hooks
- Options: explicit clear in every end path (TTS done/stopped, three input engines' finish/cancel/error, live end,
  fallbacks: about 10 places); a 300 ms poll on `Voice.isSpeaking() || Voice.listening() || CodexLive.on()` with a
  quiet window, as `SpeechPause` already does.
- Choice: poll, because it can't miss an end path (a missed one leaves the phone stuck on the speaker or in
  communication mode), it bridges the read-card → listen-for-answer gap, and it copies an existing pattern.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Android 12+ only; hidden on Android 11
- Options: legacy `setSpeakerphoneOn`/`startBluetoothSco` path for API 30; hide the choice below API 31.
- Choice: hide it, because the legacy calls are deprecated and would be a second code path, and the only target
  phone (S21 FE) is API 31+ (research). On API 30, Buddy behaves as today.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - No BLUETOOTH_CONNECT permission
- Options: request `BLUETOOTH_CONNECT` at runtime; none.
- Choice: none, because the AudioManager communication-device APIs don't list a permission, the audio service
  manages SCO itself, `MODIFY_AUDIO_SETTINGS` is already declared, and Bluetooth devices are still listed (only the
  address may be anonymized, and the key doesn't use it). Checked on device (test A3); added only if the watch
  can't be listed or selected without it.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Saved key is device type + product name
- Options: `AudioDeviceInfo.getId()` (changes on reconnect); type + address (Bluetooth address can be anonymized
  and would change if the permission is ever granted); type + product name.
- Choice: type + product name, because it stays the same across reconnects and permission changes, and it gives
  Settings the name to show while the device is away. Cost: a renamed device must be picked again.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Quick switch: a tap-to-cycle chip in VoiceBar
- Options: a menu or bottom sheet (`Ui.bottomSheet` needs an Activity; the bubble is a service window); a chip in
  the shared `VoiceBar` that moves to the next device on each tap.
- Choice: the chip, because `VoiceBar` is the voice UI of both the chat screen and the bubble, for dictation and
  live voice, so one change covers every place, and there are usually 3-4 devices. No switch while only reading
  aloud (no voice bar then): read aloud uses the remembered choice.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - AutoPause ignores Buddy's own communication mode
- Options: leave `AutoPause.callNow` as is (a chosen device would pause an operating agent when a card is read
  aloud); ignore all modes while Buddy's route is on; ignore only `MODE_IN_COMMUNICATION` while Buddy's route is on.
- Choice: the last one, because ringing and phone calls (`MODE_RINGTONE`, `MODE_IN_CALL`) and call notifications
  still pause, and with Auto nothing changes.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Out of scope: the Linux programs' microphone
- Options: route `MicServer.serve` (the `arecord` stand-in for programs in Buddy's Linux) too; leave it.
- Choice: leave it on Android's default, because it's a program's microphone in the Terminal, not one of Buddy's
  voice features (YAGNI). Buddy's own dictation through `MicServer.startCapture` is covered.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The release poll also reconciles the chosen device (plan review round 1, F1)
- Options: a device-change listener in `AudioRoute` (`AudioDeviceCallback` / `OnCommunicationDeviceChangedListener`);
  checking only when `use()` is called (misses a device that comes or goes during a call); reconciling on every
  poll tick while Buddy's voice is active, with a voice session that also runs under Auto.
- Choice: the poll, because it already runs whenever Buddy's voice is on, so there's no listener lifecycle to add.
  A session under Auto makes no audio calls, but it lets a switch to a device mid-call find the session already set
  up, with its "another call" verdict and its clean-up. "Already applied" is Buddy's own record, not
  `getCommunicationDevice()`, because a Bluetooth link takes about 1 s to come up and the poll would re-request it
  during that time (kept as the fallback if Chromium moves the call itself).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Visible device state: chip from VoiceBar's ticker, Settings from an AudioDeviceCallback (round 1, F1)
- Options: refresh only on `begin()`, a tap and `onResume`; a device callback in each view; the bar's existing
  ticker for the chip plus a callback for Settings.
- Choice: the last one. The bar already ticks every 70 ms, so a once-a-second label check costs one binder call.
  Settings has no ticker, but it already re-renders from listeners registered in `onResume`/`onPause`.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - "Another call" is decided once per voice session, before live voice publishes its call (round 1, F3)
- Options: refuse live voice during another call; check the mode at every `use()` (can't tell Buddy's or
  Chromium's communication mode from another app's); decide when the session starts, from the mode read before
  Buddy's voice starts, with `CodexLive.start` calling `use()` before `ses = s`.
- Choice: decide at session start and keep the verdict, because it's one read that no Buddy mode can fool.
  Refusing live voice would be new behavior beyond the brief. Ringing or a phone call mid-session also stops
  routing, whatever Buddy applied.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Switching to Auto or losing the device mid-call keeps the session's clean-up (round 1, F4)
- Options: release and forget at once; clear at once but keep a `dirty` mark, so the session's end clears again.
- Choice: keep the mark. Chromium can select the speaker again at hang-up (restoring what it saved at the call's
  start), and only a clear after the WebView's teardown undoes that.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - AutoPause exempts Buddy's own communication mode, not Buddy's route (round 1, F2; replaces the earlier AutoPause choice)
- Options: exempt while Buddy's route is on (the earlier choice: picking Auto mid-call turned Buddy's own live call
  into a "new call" and ended it); exempt only while `CodexLive.on()` (a card read aloud through a chosen device
  would still pause the agent); exempt `MODE_IN_COMMUNICATION` while Buddy's live call is on or ended under 3 s ago,
  or while `AudioRoute.ownsMode()`.
- Choice: the last one, because those are the mode's two owners on Buddy's side (the WebView in live voice,
  `AudioRoute` otherwise), and neither depends on the route choice. The 3 s tail covers the page's teardown after
  End. Ringing, phone calls and call notifications still pause.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Correction: "No BLUETOOTH_CONNECT" hasn't been checked on device yet (round 1, F5)
- Options: n/a (correction to the earlier entry, which said "Checked on device (test A3)").
- Choice: the no-permission approach stands but is waiting on A3. The research only shows the system sees the
  watch (`dumpsys`), not that Buddy can list or route it without the permission. Add the permission only if A3
  fails.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Re-apply once after a live call's teardown, keyed on the mode (plan review round 2, F1)
- Options: compare `getCommunicationDevice()` with the choice every tick (it doesn't report a Bluetooth SCO device
  until the link is up, so a watch link still connecting or refused would be re-requested every 300 ms); re-apply
  whenever the mode reads NORMAL (would fight Android if it takes the mode back); once per live call, forget what was
  applied on the first tick after End that reads `MODE_NORMAL`.
- Choice: the last one. Chromium's teardown clears the communication device and restores speakerphone first, then
  calls `setMode(MODE_NORMAL)` (always NORMAL; `AudioManagerAndroid`, `CommunicationDeviceSelectorPostS`). So NORMAL
  means the teardown has nothing left to undo, and the re-apply sticks for the dictation or read aloud that started
  during it.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Phone voice reacquires the route at the TTS handoff (plan review round 2, F2)
- Options: also hook the ChatGPT fallback's start; track pending speech until playback; call `AudioRoute.use` in
  `Voice.say` right before `tts.speak`.
- Choice: the `Voice.say` call, because it's the one place every phone-voice playback passes through. It covers the
  late fallback, a cold engine and slow language detection with one line, and the existing poll still does the
  clean-up.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Settings highlights the saved choice, not the effective one (plan review round 2, F3)
- Options: change `Ui.segmented` so a selected option can be tapped again; add the away device as an extra segment;
  pass the saved key as `selected`, so nothing is highlighted while that device is away.
- Choice: the saved key, because it needs no code beyond the value passed in. Auto can be tapped to clear the
  choice, and the note already says that Auto is in effect.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Coder: small choices the plan left open
- API 31 guards: `use()` and `choices()` check `SDK_INT >= 31`; the private helpers that call API 31 methods
  (`reconcile`, `drop`, `finish`) carry `@TargetApi(31)`, as they are only reachable through `use()`. Lint isn't
  run here; the CI `lintVital` is the check.
- `AudioRoute.indexOf(choices, key)` (not in the plan) is shared by the Settings row and the voice bar, to avoid two
  copies of the same loop.
- `dropMode` only sets `MODE_NORMAL` while the mode still reads `MODE_IN_COMMUNICATION`, so a call that started
  ringing meanwhile isn't overwritten. Exceptions from the audio calls in `use()` and the poll are ignored, as in
  `SpeechPause`.
- The Settings `AudioDeviceCallback` is registered on every API level (it exists since API 23); the row itself
  only shows on 31+.
- The voice bar's chip uses `TEXT` colour on `CARD2`, to read as a tappable choice next to the muted status.

## 2026-11-17 - Upstream 2.3.12 sync: AudioRoute and SpeechPause both kept
- Conflict in `Voice.listen` and `CodexLive.start`: the fork calls `AudioRoute.use`, upstream (#56) calls
  `SpeechPause.listen` at the same place. Kept both, AudioRoute first (it reads the phone's mode before anything pauses
  media), then SpeechPause. By: Orchestrator (claude-sonnet-5-5)
