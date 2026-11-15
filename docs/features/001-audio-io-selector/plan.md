# Plan: 001-audio-io-selector
<!-- Planner (claude-opus-5-5), 2026-11-15; revised for plan review round 1. Paths under app/src/main/java/app/buddy/assistant/ unless shown otherwise. -->

## Goal
Nicholas picks one audio device for Buddy's voice, the way you choose a device during a call: **Auto** (Android
decides, as it does today) or one of the devices Android offers for voice right now (on his S21 FE: Speaker, Phone
(the earpiece), Galaxy Watch4). Done when (`brief.md`):
1. Settings → Voice has an "Audio device" choice, styled like the card's other choices, and picking a device plays
   the sample on it.
2. The voice bar (chat and bubble; dictation and live voice) has a quick switch for the same choice, and it works
   in the middle of a call.
3. With a device chosen, live voice, voice input and read aloud use it. If on-device testing finds a path that
   can't follow it, the Settings note says which one.
4. The choice survives a restart. A missing device means Auto (Settings says so), and the device is used again
   when it reconnects.
5. Auto behaves as before. About 2 s after Buddy's voice stops, the phone is back to normal audio mode. Buddy's own
   voice never auto-pauses an agent as a call.
6. The CI `apk` job is green (`assembleGithubRelease assembleFdroidRelease`, lintVital included).

**Non-goals:** separate mic and speaker pickers; routing other apps' audio or system settings; the microphone of
programs in Buddy's Linux (`MicServer.serve`, the `arecord` stand-in for Claude Code's `/voice` in the Terminal),
which keeps Android's default; Android 11 (API 30), where the choice is hidden; new voice features (refusing live
voice during another call is one); a quick switch while only reading aloud (no voice bar shows then: read aloud
uses the remembered choice); translations (new strings fall back to English).

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

**The helper: `AudioRoute.java`** (new, about 140 lines, final class with static methods like `SpeechPause`; all
its state lives on the main thread):
- `static String[][] choices(Context c)` returns `{key, label}` pairs: `{"", Auto}` first, then each entry of
  `getAvailableCommunicationDevices()`. Below API 31 it returns only Auto. Labels: built-in speaker → "Speaker",
  built-in earpiece → "Phone", wired headset/headphones → "Headset", anything else → `getProductName()` (the
  Bluetooth name, e.g. "Galaxy Watch4 (K1KA)"), or "Headset" if that's empty. Key: `type + ":" + productName`.
  `getId()` changes on reconnect. Bluetooth addresses can be anonymized for apps without `BLUETOOTH_CONNECT`, and
  would change if that permission were ever granted. The name doesn't have those problems, and Settings can show
  it while the device is away.
- **A voice session** is the time Buddy's voice is on, plus a quiet tail. It starts at the first `use()` and ends
  about 1.5 s after the last voice activity. It runs under Auto too, where it only watches and makes no audio
  calls. That way a switch from Auto in the middle of a call finds the session already set up, and the clean-up
  happens in one place.
- `static void use(Context c)` means "Buddy's voice starts now, or the choice changed: apply the choice now". It
  does nothing below API 31. It reads the audio mode at once, on the caller's thread, then posts the rest to the
  main thread. It always uses the application context's `AudioManager`, the same instance the WebView's Chromium
  uses. That makes the two of them one client of Android's audio service, so Buddy's clear also clears what
  Chromium set (E1 checks this). On the main thread:
  1. If no session is on, start one and decide whether **another call** is on: the mode read at the call wasn't
     `MODE_NORMAL`. In that case the whole session leaves routing alone (Auto behavior), so the phone's or another
     app's call keeps its audio. Buddy's own mode can't cause a false "another call", for two reasons. Buddy only
     holds the mode inside a session, and an existing session is never re-judged. And `CodexLive.start` calls
     `use()` before it publishes the call and opens the page (see Hooks), so the mode it reads is from before the
     live call.
  2. Restart the poll's timers (quiet window, "never started").
  3. Forget what was applied, then `reconcile()`.
- `reconcile()` (private) brings Android in line with the choice. It runs from `use()` and on every poll tick while
  Buddy's voice is active:
  1. If the choice is Auto: `drop()` and stop.
  2. If the phone is ringing or in a phone call (mode neither `MODE_NORMAL` nor `MODE_IN_COMMUNICATION`), the
     session counts as another call's from now on, whatever Buddy applied before.
  3. If the session is another call's, or the chosen device isn't connected (Android itself ends the selection
     when it disconnects): `drop()` and stop.
  4. If this choice is already applied, or was refused, stop. On reconnect, step 3 has already reset this, so the
     next tick (within 300 ms) applies the device again, without a tap or a new utterance.
  5. If the mode is `MODE_NORMAL` and `!CodexLive.on()`, set `MODE_IN_COMMUNICATION` and remember that Buddy set
     it (`ownMode`). In live voice the WebView sets and resets the mode itself, and Buddy doesn't fight it.
  6. Call `setCommunicationDevice(d)`. If it returns false, undo the mode and count the choice as refused, so the
     poll doesn't retry it every 300 ms; it tries again when the device reconnects or the choice changes.
     Otherwise mark it applied and note that Buddy set a device in this session (`dirty`).
- `drop()`: if Buddy has a device or the mode applied, call `clearCommunicationDevice()`, and
  `setMode(MODE_NORMAL)` if `ownMode && !CodexLive.on()` (then `ownMode = false`). `dirty` stays set: switching
  to Auto or losing the device mid-call doesn't cancel the session's final clean-up.
- **Poll** (copies `SpeechPause`'s pattern: `POLL_MS` 300, a quiet window of about 1500 ms, a 10 s "never started"
  limit): every 300 ms while the session is on. Whenever `Voice.isSpeaking() || Voice.listening() ||
  CodexLive.on()`, it calls `reconcile()`. Once that has been false for the quiet window (or never became true
  within 10 s), it calls `finish()`:
  - `clearCommunicationDevice()` if `dirty`. This also undoes the speaker that Chromium may select again at
    hang-up, when it restores the state it saved at the start of the call; that happens before the quiet window
    ends.
  - `setMode(MODE_NORMAL)` if `ownMode`.
  - Reset everything and end the session. Exceptions are ignored, as elsewhere.

  Polling for the end of Buddy's voice catches every end path: done, cancelled, error, engine fallback, live
  hang-up, pause. Explicit end hooks would be needed in about 10 places across five classes, and missing one would
  leave the phone stuck on the speaker. The quiet window also bridges the gap between "card read aloud" and
  "listening for its answer" (`Bubble.java:2404-2414`).
- `static boolean ownsMode()` reports whether Buddy itself set communication mode. `AutoPause` reads it.

Cost: under Auto, one `getMode()` per `use()` plus a check of three booleans every 300 ms while Buddy's voice is on.
With a device chosen, add one `getAvailableCommunicationDevices()` per tick.

**Hooks** (one line each):
- `Voice.speak`: `AudioRoute.use(c)` next to `SpeechPause.begin(c)` (`Voice.java:229`). Covers both read-aloud
  engines and the Settings sample.
- `Voice.listen`: `AudioRoute.use(c)` after `stopListening(); stopSpeaking();` (`Voice.java:102-103`), before
  the engine is chosen. Covers all three input engines, chat, bubble, cards and the assistant button.
- `CodexLive.start`: `AudioRoute.use(app)` right after `app = c.getApplicationContext()` (`CodexLive.java:189`).
  That is **before** `ses = s` (`:192`) and `openPage()`, so the mode it reads is the phone's from before the live
  call, and an existing phone or VoIP call is seen. The posted part runs after `start` returns, when
  `CodexLive.on()` is true, so Buddy leaves the mode to the WebView. Both live entry points (`ChatActivity.java:1775`,
  `Bubble.java:712`) go through `start`.
- `CodexLive` JS `connected()` (`CodexLive.java:686-697`): `AudioRoute.use(app)` again. Chromium picks its own
  device when it opens the mic (after `start`), so Buddy applies the choice again once the call is up and before
  any voice plays.
- `AutoPause.callNow` (`AutoPause.java:142-145`): one exemption, for **Buddy's own communication mode**.
  `MODE_IN_COMMUNICATION` doesn't count as a call while Buddy's live call is on or has just ended, or while
  `AudioRoute.ownsMode()`.
  - "Just ended" covers the WebView's teardown: `CodexLive.end` clears `ses` at once, but the page lets go of the
    mode later (it is destroyed 300 ms after `hangUp()`, `CodexLive.java:246-257`). `callNow` runs every 700 ms
    while an agent operates, and each time it sees `CodexLive.on()` it sets `liveUntil = now + 3 s`.
  - Neither key depends on the route choice. Picking Auto or losing the device in the middle of a call leaves live
    voice exempt. `ownMode` is cleared in the same main-thread step that resets the mode, so `AutoPause` (also on
    the main thread) never sees Buddy's mode without its flag.
  - Ringing and phone calls (`MODE_RINGTONE`, `MODE_IN_CALL`) and call notifications still pause. Under Auto,
    live voice is exempt as well, which the brief's done-when 5 asks for.

**Settings** (`SetupActivity` Voice card, after the Voice output block (`:443-460`) and before Languages; only if
`choices().length > 1`, i.e. API 31+): `fieldLabel("Audio device")` + `segmented(choices, selected, ...)`, the
same control as Voice input/output and the six-option live agent picker.
- `selected` is the saved key if that device is connected, otherwise `""` (Auto is in effect).
- On pick: `Prefs.setAudioDevice`, `render()`, then `Voice.stopSpeaking(); Voice.speak(sample)`, as the ChatGPT
  voice picker does (`:453-457`). That plays the sample through the new device, which is also the quickest check.
- The muted note below says what the choice covers. When the saved device is away it says "Galaxy Watch4 (K1KA)
  isn't connected: Auto until it is." instead (the name is the part of the key after the first `:`).
- While the screen is open (API 31+), an `AudioDeviceCallback` calls `render()` when devices come or go. It is
  registered in `onResume` and removed in `onPause`, like the screen's other listeners (`SetupActivity.java:57-82`).
  A watch that connects or disconnects shows at once. Registering reports the current devices once more, which
  costs one extra render, as the account refreshes on resume already do.

**Quick switch** (`VoiceBar`, used for dictation and live voice by both `ChatActivity` and `Bubble`): on API 31+,
a compact text chip between the status and End/send.
- Built with Ui helpers: `monoText` 12 sp, single line, ellipsized, max about 88 dp, `rounded(CARD2, 10)`, 34 dp
  high like End.
- It shows the device in effect: the saved one if it's connected, otherwise Auto.
- A tap moves to the next entry of `choices()`, saves it, calls `AudioRoute.use(c)` and updates the label. The bar
  only shows while Buddy's voice is on, so the new choice applies at once.
- The label refreshes in `begin()`, which both screens call each time the bar appears (`Bubble.java:688,718`,
  `ChatActivity.java:1718,1752`), on a tap, and about once a second from the bar's existing 70 ms ticker
  (`VoiceBar.java:164-173`). A device lost or reconnected mid-call shows within a second.
- It's hidden when there's only Auto. Content description: "Audio device: <label>".
- Tapping through the choices is used instead of a menu because the bubble is a service window with no Activity
  for `Ui.bottomSheet`, and there are usually 3-4 entries.

**Prefs:** `audioDevice(Context)` / `setAudioDevice(Context, String)`, key `audio_device`, default `""` = Auto
(the same "" default as `liveAgent`).

**Permissions:** none added. `getAvailableCommunicationDevices` and `setCommunicationDevice` don't list a
permission. Android's audio service manages the SCO link itself, and `MODIFY_AUDIO_SETTINGS` (needed for
`setMode`) is already declared (`AndroidManifest.xml:14`). Without `BLUETOOTH_CONNECT`, only a Bluetooth device's
address should be anonymized, and the key doesn't use it. **Not yet confirmed on device, awaiting A3:** the
research only shows that the system sees the watch (`dumpsys`, `research.md:62-67`), not what Buddy can list or
route without the permission. If A3 fails, the permission is added, as the decision says.

**Alternatives considered:**
- `AudioRecord.setPreferredDevice` / `AudioTrack` per stream: rejected. Only `MicServer` and the live fallback own
  their streams; TTS, `SpeechRecognizer` and the WebViews don't.
- `setCommunicationDevice` without the mode: rejected. Read aloud (media) wouldn't follow.
- Switch the phone TTS to `USAGE_VOICE_COMMUNICATION`: rejected as the main path. It doesn't cover ChatGPT's
  voice, so the mode is needed anyway. Kept as the fallback (Risks).
- Legacy `setSpeakerphoneOn` / `startBluetoothSco` for API 30: rejected. These are deprecated, would be a second
  code path, and the only target phone is API 31+.
- Explicit release hooks per engine: rejected (see the poll above).
- A device-change listener in `AudioRoute` (`AudioDeviceCallback`, `OnCommunicationDeviceChangedListener`):
  rejected. The poll already runs whenever Buddy's voice is on, so it reconciles the device as well.
- Comparing `getCommunicationDevice()` with the choice on every tick: rejected for now. A Bluetooth link takes
  about 1 s to come up, and the poll would re-request it in that time. Kept as a fallback (Risks).
- Refusing live voice during another call: rejected. That is new behavior the brief doesn't ask for. Buddy only
  leaves that call's routing alone.
- AutoPause keyed on Buddy's route being on: rejected (round 1). Picking Auto mid-call turned Buddy's own call
  into a "new call" and ended it.
- Separate mic/speaker pickers: rejected by Nicholas (Clarifications).
- A menu or sheet for the quick switch: rejected (no Activity in the bubble).

## Steps
1. **Prefs and strings.** `Prefs.java`: `audioDevice` / `setAudioDevice` next to the other voice prefs
   (around `:712-728`). `res/values/strings.xml`, voice block (around `:1279-1297`):
   - `setup_audio_device` "Audio device"
   - `setup_audio_device_info` "Where Buddy listens and speaks: voice input, read aloud and live voice. Auto leaves
     it to Android."
   - `setup_audio_device_missing` "%1$s isn\'t connected: Auto until it is."
   - `audio_auto` "Auto", `audio_speaker` "Speaker", `audio_phone` "Phone", `audio_headset` "Headset"
   - `voice_audio_device` "Audio device: %1$s"
2. **`AudioRoute.java`** (new): `choices`, `use`, `reconcile`, `drop`, the poll and `finish`, and `ownsMode`, as in
   Design. Guard every API 31 call with `Build.VERSION.SDK_INT >= 31` (lintVital `NewApi` fails the CI build
   otherwise). Class doc comment in the repo's style: what it's for, the "runs as a call" idea, voice sessions,
   Android 12+.
3. **Hooks:**
   - `Voice.java`: `speak`, `listen`.
   - `CodexLive.java`: `start`, before `ses = s`; JS `connected()`.
   - `AutoPause.java`: `callNow` gets the exemption and `liveUntil`, with a one-line comment on why Buddy's own
     communication mode isn't a call.
4. **Settings row:** `SetupActivity.java` Voice card, plus the `AudioDeviceCallback` in `onResume`/`onPause`, as
   in Design.
5. **Voice bar switch:** `VoiceBar.java`: the chip, and a private `showDevice()` called from the constructor,
   `begin()`, the tap and about every 14th tick.

Each step builds on its own (step 2 compiles unused until step 3).

## Test plan
There are no unit tests in the app (research). Proof is CI plus an on-device run on Nicholas's S21 FE (SM-G990W)
with the Galaxy Watch4 connected. Build on the phone with `./build.sh` or install the CI APK. `dumpsys` checks run
through the phone shell, as the orchestrator did for research: `dumpsys audio | grep -iE "mode|communication"`.
"No communication device from Buddy" means no route client from Buddy's process and preferred communication
device null.

| # | Check | Proves |
|---|---|---|
| CI | `apk` job green on the branch (`assembleGithubRelease assembleFdroidRelease`, lintVital included) | DW6 |
| A1 | Settings → Voice shows "Audio device" with Auto, Speaker, Phone, Galaxy Watch4 (K1KA), same look as Voice input/output | DW1 |
| A2 | Voice output = Phone: pick Speaker / Phone / Watch, and the sample plays from the loudspeaker / earpiece / watch | DW1, DW3 (phone TTS) |
| A3 | Same with Voice output = ChatGPT. **Permission check (not done yet):** Buddy lists the watch, selects it and plays the sample on it, with no Bluetooth permission prompt or grant (App info → Permissions shows no "Nearby devices") | DW3 (ChatGPT voice), permissions |
| B1 | Watch chosen: dictate with each engine (Phone, Claude, ChatGPT) from the chat mic and the bubble, speaking at the watch with the phone covered or away. The text is right. Speaker/Phone: dictation works as before | DW3 (voice input) |
| C1 | Live voice in a chat, choice Speaker: the voice is on the loudspeaker. Tap the bar's chip → Phone: earpiece; → Watch: the watch's mic and speaker; → Auto: as before. Repeat from the bubble | DW2, DW3 (live) |
| C2 | Voice bar while dictating shows the chip; a tap changes the label and the saved choice (Settings shows it) | DW2 |
| D1 | Watch chosen. (a) Settings left open, watch Bluetooth off: without leaving the screen, Settings highlights Auto with the "isn't connected" note; Bluetooth on: the watch is back and highlighted. (b) Read aloud while the watch is off plays as Auto; watch on: the next sample plays on the watch with no new pick. (c) In one live call: watch off → the voice moves to Auto's device and the chip says Auto; watch on → the voice is back on the watch within about 2 s and the chip says so, with no tap and no new utterance. (d) Live voice started while the watch is off, then the watch turned on: the call moves to the watch. (e) Force-stop and reopen Buddy: the choice is still there | DW4 |
| E1 | About 2 s after each of A-D ends: `dumpsys audio` shows mode NORMAL and no communication device from Buddy. Includes Speaker → live voice → chip to Auto → End, which checks that the WebView's restore at hang-up was cleared. Music in another app plays on its usual device | DW5 (clean-up) |
| E2 | Auto: live voice, dictation and read aloud behave as before, and `dumpsys audio` shows no route from Buddy during them | DW5 (Auto unchanged) |
| E3 | While an agent operates the phone, with "Read replies aloud" and "Answer cards by voice" on, the agent is not paused "for a call" when: an approval card is read aloud and answered by voice with a device chosen; live voice runs under Auto (started before the task, and started during it); live voice with a device chosen, then the chip moved to Auto; live voice while the chosen device is away; live voice ended with End. A real incoming call during the task still pauses it | DW5 (AutoPause) |
| E4 | During a phone (or WhatsApp) call: a reply read aloud doesn't move the call's audio; starting live voice leaves no communication device from Buddy (`dumpsys`); a chip tap in that live call doesn't move the call's audio (it only saves the choice). And a phone call ringing while Buddy reads aloud through the watch: Buddy lets go (`dumpsys`) and the call rings and is answered on its usual device | Design (other calls left alone) |

## Risks
- **Media following the call device (read aloud) is AOSP behavior, unverified on Samsung.** If A2 fails, phone
  TTS uses `USAGE_VOICE_COMMUNICATION` while Buddy's route is applied (set the attributes in `Voice.say` before
  each `speak`); communication audio is certain to follow the device. If A3 fails as well, ChatGPT's voice can't
  be routed (its WebView has no device API), and the Settings note says read aloud with ChatGPT's voice stays on
  Android's media output.
- **Volume:** in communication mode the volume keys change call volume, while read aloud plays at the media
  volume for that device. It may be quiet on the earpiece or watch. Check in A2; the same fallback (call usage)
  makes the keys work.
- **Watch mic for the phone recognizer** depends on Android recording `VOICE_RECOGNITION` from the SCO headset
  while it's the communication device. If B1 fails for Claude/ChatGPT (Buddy's own `AudioRecord`), add
  `setPreferredDevice` with the matching SCO input in `MicServer.startCapture`. If it fails for the phone engine,
  that engine can't be routed, and Settings says so.
- **Bluetooth SCO takes about 0.5-1 s to connect**, so the first syllable of a read-aloud or the first word of
  dictation on the watch can be lost. Accepted (live voice takes longer than that to connect). If it's bad in
  A2/B1, a follow-up can wait for `OnCommunicationDeviceChangedListener` before speaking.
- **The watch may refuse a non-call SCO link** even though it's listed (Wear OS behavior, anecdotal per research).
  That shows in A2/B1/C1. Buddy doesn't re-request it on every tick (it only tries again on reconnect or a new
  choice). There is no in-app fix; report it and keep the other devices.
- **Chromium in the WebView:**
  - It picks its own device when the mic opens. The `connected()` re-apply covers this.
  - It may select the speaker again at hang-up. `finish()` clears that, but only if Chromium uses the same
    application-context `AudioManager` (one audio-service client). If E1 shows a request left from Buddy's
    process, apply live voice's device only from `connected()`, after Chromium has saved its state, so it has
    nothing to restore.
  - It may also move the call when devices change mid-call (for example, Speaker chosen and the watch connects),
    and the poll only re-applies when the chosen device comes or goes. If C1/D1 show that, the poll also compares
    `getCommunicationDevice()` with the choice, with about 2 s of grace for a Bluetooth link to come up.
- **While Buddy's voice holds communication mode, other apps' media also goes to the chosen device** (for
  example, music on the earpiece while dictating). The hold lasts only about 1.5 s past Buddy's voice, and "Pause
  media while reading aloud" covers read aloud. Accepted.
- **AutoPause edge:** a VoIP call that another app starts while Buddy holds communication mode (its own mode, a live
  call, or the 3 s after one) is only seen through its call notification until then. Accepted.
- **"Another call" is judged once per voice session.** A call that ends during a Buddy voice session leaves that
  session on Auto until it ends (about 1.5 s after Buddy's voice stops). This errs on the safe side. Accepted.
- **A renamed Bluetooth device** no longer matches its key, so Buddy uses Auto until it's picked again.

## Open questions
None blocking.
