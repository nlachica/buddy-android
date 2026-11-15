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
