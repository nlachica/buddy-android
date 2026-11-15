# Plan: 001-audio-io-selector
<!-- Planner (claude-opus-5-5), 2026-11-15. Paths under app/src/main/java/app/buddy/assistant/ unless shown otherwise. -->

## Goal
Nicholas picks one audio device for Buddy's voice, like choosing a device during a call: **Auto** (Android decides,
the same as today) or one of the devices Android offers for voice right now (on his S21 FE: Speaker, Phone (the
earpiece), Galaxy Watch4). He picks it in Settings → Voice and can switch it from the voice bar (chat screen and
bubble, while dictating or in live voice). It applies to live voice, voice input and read aloud. The choice is
remembered. When that device isn't connected Buddy uses Auto, and goes back to the device when it reconnects.

`brief.md` has no Done-when items yet. **Proposed, for the orchestrator to copy into the brief:**
1. Settings → Voice has an "Audio device" choice: Auto plus the devices Android offers for voice now (on the test
   phone: Speaker, Phone, Galaxy Watch4), styled like the card's other choices. Picking one plays the sample
   sentence on that device.
2. The voice bar (chat and bubble; dictation and live voice) has a switch for the same choice. A tap changes the
   device at once, also in the middle of a live call.
3. With a device chosen, live voice (mic and voice), voice input (phone, Claude and ChatGPT engines) and read
   aloud (phone voice and ChatGPT voice) use it. Speaker = loudspeaker, Phone = earpiece, Watch = the watch's mic
   and speaker. For voice input, Speaker and Phone both use the phone's own mic.
4. The choice survives an app restart. If the chosen device isn't connected, Buddy behaves as Auto and Settings
   says so; when it reconnects it's used again without picking it again.
5. Auto (the default) behaves as before. About 2 s after Buddy's voice stops, the phone is back to normal (no
   communication device from Buddy, audio mode normal). Buddy's own voice never auto-pauses an agent as if it
   were a call.
6. CI `apk` job is green (`assembleGithubRelease assembleFdroidRelease`, including lintVital).

**Non-goals:** separate mic and speaker pickers; routing other apps' audio or system settings; the microphone of
programs in Buddy's Linux (`MicServer.serve`, the `arecord` stand-in for Claude Code's `/voice` in the Terminal),
which keeps Android's default; Android 11 (API 30), where the choice is hidden; new voice features; a quick switch
while only reading aloud (no voice bar shows then: read aloud uses the remembered choice); translations (new
strings fall back to English).

## Design
**One mechanism: while Buddy's voice is on and a device is chosen, Buddy sets that device as Android's
communication device (`AudioManager.setCommunicationDevice`, API 31) and puts the phone in communication mode
(`MODE_IN_COMMUNICATION`). Shortly after Buddy's voice stops, it clears both.** In other words, Buddy's voice
runs as a call for that time. Here is what each path does with this mechanism:

| Path | What it is to Android | Follows the choice because |
|---|---|---|
| Live voice mic + voice (`CodexLive`, WebView WebRTC) | Chromium opens the mic as a voice call and sets communication mode itself; output is voice-communication audio | Communication audio goes to the communication device. The fallback `AudioRecord` (`VOICE_COMMUNICATION` source, `CodexLive.java:754`) follows it the same way |
| Voice input: Claude/ChatGPT (`MicServer.startCapture`, `VOICE_RECOGNITION`) and phone engine (`SpeechRecognizer`, recognizer's own process) | Normal recording | With a Bluetooth SCO device as the communication device, Android's input policy records every voice/mic source from the SCO headset's mic. That covers the recognizer service too, which Buddy can't reach any other way. For Speaker/Phone the mic is the phone's mic, as before |
| Read aloud, phone voice (`TextToSpeech`, `USAGE_ASSISTANT`, `Voice.java:245-250`) | Media | In communication mode, Android routes media to the call's device. Without the mode, `setCommunicationDevice` alone would not move it (communication usage only). This is why the mode is set |
| Read aloud, ChatGPT voice (`CodexSpeech`, WebView `<audio>`, receive-only: no `getUserMedia`, `CodexSpeech.java:241-262`) | Media | Same as above. The WebView exposes no device API, so the mode is the only handle |

The input and media-in-call routing in the table comes from AOSP's audio policy. It hasn't been verified on
Samsung, so the on-device checklist tests each row, and Risks gives the fallback for each.

**The helper: `AudioRoute.java`** (new, about 100 lines, final class with static methods like `SpeechPause`):
- `static String[][] choices(Context c)` returns `{key, label}` pairs: `{"", Auto}` first, then each entry of
  `getAvailableCommunicationDevices()`. Below API 31 it returns only Auto. Labels: built-in speaker → "Speaker",
  built-in earpiece → "Phone", wired headset/headphones → "Headset", anything else → `getProductName()` (the
  Bluetooth name, e.g. "Galaxy Watch4 (K1KA)"), or "Headset" if that's empty. Key: `type + ":" + productName`.
  `getId()` changes on reconnect. Bluetooth addresses can be anonymized for apps without `BLUETOOTH_CONNECT`, and
  would change if that permission were ever granted. The name doesn't have those problems, and Settings can show
  it while the device is away.
- `static void use(Context c)` means "Buddy's voice starts now, or the choice changed while it's on". It is
  posted to the main thread and always uses the application context's `AudioManager`. Steps:
  1. Find the chosen key among the available devices. If the choice is Auto, or the device isn't connected,
     call `release()` and stop.
  2. If the phone is in a call that isn't Buddy's (mode not `MODE_NORMAL`, Buddy's route not on, and
     `!CodexLive.on()`), do nothing, so another app's or the phone's call keeps its audio.
  3. If the mode is `MODE_NORMAL` and `!CodexLive.on()`, set `MODE_IN_COMMUNICATION` and remember that Buddy set
     it (`ownMode`). In live voice the WebView sets and resets the mode itself, and Buddy must not fight it.
  4. Call `setCommunicationDevice(d)`. If it returns false, undo the mode and stop (Auto behavior).
  5. Set `on = true` and (re)start the release poll.
- **Release poll** (copies `SpeechPause`'s pattern: `POLL_MS` 300, a quiet window of about 1500 ms, a 10 s
  "never started" limit): it runs every 300 ms while `Voice.isSpeaking() || Voice.listening() || CodexLive.on()`.
  Once that has been false for the quiet window (or never became true within 10 s), it calls `release()`.
  `release()`: `clearCommunicationDevice()`; `setMode(MODE_NORMAL)` only if `ownMode` and `!CodexLive.on()`; then
  `on = ownMode = false`. Exceptions are ignored, as elsewhere. Polling for the end of Buddy's voice catches every
  end path: done, cancelled, error, engine fallback, live hang-up, pause. Explicit end hooks would be needed in
  about 10 places across five classes, and missing one would leave the phone stuck on the speaker. The quiet
  window also bridges the gap between "card read aloud" and "listening for its answer" (`Bubble.java:2404-2414`).
- `static boolean on()` reports whether Buddy's route is on. `AutoPause` reads it.

**Hooks** (one line each):
- `Voice.speak`: `AudioRoute.use(c)` next to `SpeechPause.begin(c)` (`Voice.java:229`). Covers both read-aloud
  engines and the Settings sample.
- `Voice.listen`: `AudioRoute.use(c)` after `stopListening(); stopSpeaking();` (`Voice.java:102-103`), before
  the engine is chosen. Covers all three input engines, chat, bubble, cards and the assistant button.
- `CodexLive.start`: `AudioRoute.use(app)` after `ses = s` (`CodexLive.java:192`), so the poll sees the call.
- `CodexLive` JS `connected()` (`CodexLive.java:686-697`): `AudioRoute.use(app)` again. Chromium picks its own
  device when it opens the mic (after `start`), so Buddy re-applies the choice once the call is up and before
  any voice plays.
- `AutoPause.callNow` (`AutoPause.java:142-145`): treat the mode as a call unless
  `mode == MODE_IN_COMMUNICATION && AudioRoute.on()`. Without this, an agent that is operating the phone would be
  paused "for a call" when a card is read aloud through a chosen device. Ringing and real calls (`MODE_RINGTONE`,
  `MODE_IN_CALL`) and call notifications still pause. With Auto, nothing changes.

**Settings** (`SetupActivity` Voice card, after the Voice output block (`:445-459`) and before Languages; only if
`choices().length > 1`, i.e. API 31+): `fieldLabel("Audio device")` + `segmented(choices, selected, ...)`, the
same control as Voice input/output and the six-option live agent picker. `selected` is the saved key if it's
connected, otherwise `""` (Auto is in effect). On pick: `Prefs.setAudioDevice`, `render()`, then
`Voice.stopSpeaking(); Voice.speak(sample)`, as the ChatGPT voice picker does (`:453-457`). That plays the sample
through the new device, which is also the quickest check. The muted note below says what the choice covers, or
"Galaxy Watch4 (K1KA) isn't connected: Auto until it is." when the saved device is away (its name is the part of
the key after the first `:`). The screen re-renders on resume, so devices connected meanwhile appear.

**Quick switch** (`VoiceBar`, used for dictation and live voice by both `ChatActivity` and `Bubble`): on API 31+,
a compact text chip between the status and End/send. Built with Ui helpers: `monoText` 12 sp, single line,
ellipsized, max about 88 dp, `rounded(CARD2, 10)`, 34 dp high like End. It shows the device in effect (the saved
one if connected, else Auto). A tap moves to the next entry of `choices()`, saves it, calls `AudioRoute.use(c)`
(the bar only shows while Buddy's voice is on, so it applies at once) and updates the label. It's hidden when
there's only Auto. The label refreshes in `begin()`, which both screens call each time the bar appears (`Bubble.java:688,718`,
`ChatActivity.java:1718,1752`). Content description: "Audio device: <label>". Tapping through the choices is used
instead of a menu because the bubble is a service window with no Activity for `Ui.bottomSheet`, and there are
usually 3-4 entries.

**Prefs:** `audioDevice(Context)` / `setAudioDevice(Context, String)`, key `audio_device`, default `""` = Auto
(the same "" default as `liveAgent`).

**Permissions:** none added. `getAvailableCommunicationDevices` and `setCommunicationDevice` don't list a
permission. Android's audio service manages the SCO link itself, and `MODIFY_AUDIO_SETTINGS` (needed for
`setMode`) is already declared (`AndroidManifest.xml:14`). Bluetooth devices are still listed without
`BLUETOOTH_CONNECT`; only their address may be anonymized, and the key doesn't use it. Checked on device (item
A3).

**Alternatives considered:**
- `AudioRecord.setPreferredDevice` / `AudioTrack` per stream: rejected. Only `MicServer` and the live fallback own
  their streams; TTS, `SpeechRecognizer` and the WebViews don't.
- `setCommunicationDevice` without the mode: rejected. Read aloud (media) wouldn't follow.
- Switch the phone TTS to `USAGE_VOICE_COMMUNICATION`: rejected as the main path. It doesn't cover ChatGPT's
  voice, so the mode is needed anyway. Kept as the fallback (Risks).
- Legacy `setSpeakerphoneOn` / `startBluetoothSco` for API 30: rejected. These are deprecated, would be a second
  code path, and the only target phone is API 31+.
- Explicit release hooks per engine: rejected (see the release poll above).
- Separate mic/speaker pickers: rejected by Nicholas (Clarifications).
- A menu or sheet for the quick switch: rejected (no Activity in the bubble).

## Steps
1. **Prefs and strings.** `Prefs.java`: `audioDevice` / `setAudioDevice` next to the other voice prefs
   (around `:712-728`). `res/values/strings.xml`, voice block (around `:1279-1297`): `setup_audio_device`
   "Audio device", `setup_audio_device_info` ("Where Buddy listens and speaks: voice input, read aloud and live
   voice. Auto leaves it to Android."), `setup_audio_device_missing` ("%1$s isn\'t connected: Auto until it
   is."), `audio_auto` "Auto", `audio_speaker` "Speaker", `audio_phone` "Phone", `audio_headset` "Headset",
   `voice_audio_device` "Audio device: %1$s".
2. **`AudioRoute.java`** (new): `choices`, `use`, `on`, the poll and `release`, as in Design. Guard every API 31
   call with `Build.VERSION.SDK_INT >= 31` (lintVital `NewApi` fails the CI build otherwise). Class doc comment in
   the repo's style (what it's for, the "runs as a call" idea, Android 12+).
3. **Hooks:** `Voice.java` (`speak`, `listen`), `CodexLive.java` (`start`, JS `connected()`), `AutoPause.java`
   (`callNow`, with a one-line comment on why Buddy's own communication mode isn't a call).
4. **Settings row:** `SetupActivity.java` Voice card, as in Design.
5. **Voice bar switch:** `VoiceBar.java`: the chip, a private `showDevice()` called from the constructor,
   `begin()` and the tap.

Each step builds on its own (step 2 compiles unused until step 3).

## Test plan
There are no unit tests in the app (research). Proof is CI plus an on-device run on Nicholas's S21 FE
(SM-G990W) with the Galaxy Watch4 connected. Build on the phone with `./build.sh` or install the CI APK.
`dumpsys` checks run via the phone shell, as the orchestrator did for research:
`dumpsys audio | grep -iE "mode|communication"`.

| # | Check | Proves |
|---|---|---|
| CI | `apk` job green on the branch (`assembleGithubRelease assembleFdroidRelease`, lintVital included) | DW6 |
| A1 | Settings → Voice shows "Audio device" with Auto, Speaker, Phone, Galaxy Watch4 (K1KA), same look as Voice input/output | DW1 |
| A2 | Voice output = Phone: pick Speaker / Phone / Watch, and the sample plays from the loudspeaker / earpiece / watch | DW1, DW3 (phone TTS) |
| A3 | Same with Voice output = ChatGPT. The watch is listed and selectable without any Bluetooth permission prompt | DW3 (ChatGPT voice), permissions |
| B1 | Watch chosen: dictate with each engine (Phone, Claude, ChatGPT) from the chat mic and the bubble, speaking at the watch with the phone covered or away. The text is right. Speaker/Phone: dictation works as before | DW3 (voice input) |
| C1 | Live voice in a chat, choice Speaker: the voice is on the loudspeaker. Tap the bar's chip → Phone: earpiece; → Watch: the watch's mic and speaker; → Auto: as before. Repeat from the bubble | DW2, DW3 (live) |
| C2 | Voice bar while dictating shows the chip; a tap changes the label and the saved choice (Settings shows it) | DW2 |
| D1 | Watch chosen, then the watch's Bluetooth turned off: Settings highlights Auto with the "isn't connected" note; read aloud plays as Auto. Bluetooth on again: the sample plays on the watch with no new pick. Force-stop and reopen Buddy: the choice is still there | DW4 |
| E1 | About 2 s after each of A-C ends: `dumpsys audio` shows mode NORMAL and no communication route from Buddy (preferred communication device null). Music in another app plays on its usual device | DW5 (clean-up) |
| E2 | Auto: live voice, dictation and read aloud behave as before, and `dumpsys audio` shows no route from Buddy during them | DW5 (Auto unchanged) |
| E3 | Device chosen, "Read replies aloud" and "Answer cards by voice" on: during a task where the agent operates the phone, an approval card is read aloud and answered by voice, and the agent is not paused "for a call". A real incoming call during a task still pauses it | DW5 (AutoPause) |
| E4 | During a phone (or WhatsApp) call, a reply read aloud doesn't move the call's audio | Design step 2 (other calls left alone) |

## Risks
- **Media following the call device (read aloud) is AOSP behavior, unverified on Samsung.** If A2 fails, phone
  TTS uses `USAGE_VOICE_COMMUNICATION` while `AudioRoute.on()` (set the attributes in `Voice.say` before each
  `speak`); communication audio follows the device for certain. If A3 fails as well, ChatGPT's voice can't be
  routed (its WebView has no device API) and the Settings note says read aloud with ChatGPT's voice stays on
  Android's media output.
- **Volume:** in communication mode the volume keys change call volume, while read aloud plays at the media
  volume for that device. It may be quiet on the earpiece or watch. Check in A2; the same fallback (call usage)
  makes the keys work.
- **Watch mic for the phone recognizer** depends on Android recording `VOICE_RECOGNITION` from the SCO headset
  while it's the communication device. If B1 fails for Claude/ChatGPT (Buddy's own `AudioRecord`), add
  `setPreferredDevice` with the matching SCO input in `MicServer.startCapture`. If it fails for the phone engine,
  that engine can't be routed, and Settings says so.
- **Bluetooth SCO takes about 0.5-1 s to connect**, so the first syllable of a read-aloud or the first word of
  dictation on the watch can be lost. Accepted (live voice connects for longer than that). If it's bad in A2/B1,
  a follow-up can wait for `OnCommunicationDeviceChangedListener` before speaking.
- **The watch may refuse a non-call SCO link** even though it's listed (Wear OS behavior, anecdotal per
  research). That shows in A2/B1/C1. There is no in-app fix; report it and keep the other devices.
- **Chromium in the WebView** picks its own device when the mic opens, and may restore its saved speakerphone
  state at hang-up. The `connected()` re-apply and the release about 1.5 s after hang-up (which comes after
  Chromium's teardown) cover both. E1 checks the clean-up.
- **While Buddy's voice holds communication mode, other apps' media also goes to the chosen device** (for
  example, music on the earpiece while dictating). The hold lasts only about 1.5 s past Buddy's voice, and
  "Pause media while reading aloud" covers read aloud. Accepted.
- **AutoPause edge:** a VoIP call that another app starts while Buddy's route is on is only seen through its
  call notification until Buddy releases (then the mode check sees it again). Accepted.
- **A renamed Bluetooth device** no longer matches its key, so Buddy uses Auto until it's picked again.

## Open questions
None blocking. The orchestrator should copy the proposed Done-when items above into `brief.md`.
