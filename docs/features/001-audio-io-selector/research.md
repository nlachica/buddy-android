# Research: 001-audio-io-selector
<!-- Researcher, 2026-11-15. Sources on every claim. -->

## Summary
- **No code in Buddy chooses an audio device.** `grep` for `setCommunicationDevice|setSpeakerphoneOn|setPreferredDevice|startBluetoothSco|getDevices|setMode` over `app/src` finds nothing. `AudioManager` is only used to read mode (`AutoPause.java:144`), ringer and music volume (`PhoneTools.java:303-413`). No Bluetooth permission in the manifest (only `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`: `AndroidManifest.xml:13-14`).
- **Four different capture/playback mechanisms**, so one selector means four mechanisms (table below): (1) `AudioRecord` in `MicServer` (Claude/Codex dictation, Linux mic), (2) `AudioRecord` in `CodexLive.startCapture` (fallback only), (3) WebView `getUserMedia` + WebRTC in `CodexLive` (live voice, primary path), (4) `SpeechRecognizer` (phone engine). Output: Android `TextToSpeech` (`Voice.java:245`) and a WebView `<audio>` element (`CodexSpeech`, `CodexLive`).
- **Only (1) and (2) can take `AudioRecord.setPreferredDevice` directly** (we own the AudioRecord). `SpeechRecognizer`, `TextToSpeech` and WebView audio expose no device API (nothing in the code sets one; API surface from Android docs, unverified here). For those the only lever is the system-wide communication device / audio mode (`AudioManager.setCommunicationDevice`), which affects the whole app while set.
- **minSdk 30, targetSdk/compileSdk 36** (`app/build.gradle.kts:18-23`). `getAvailableCommunicationDevices`/`setCommunicationDevice` are API 31+ (Android 12), so API 30 needs the legacy `setSpeakerphoneOn`/`startBluetoothSco` or a guard. `AudioManager.getDevices(GET_DEVICES_INPUTS/OUTPUTS)` and `setPreferredDevice` are API 23, fine.
- **Known "live voice uses the earpiece" issue: not found upstream.** Upstream issues (all 38, `gh issue list -R ghorbelhamdi/buddy-android --state all`) and comments contain no earpiece/speakerphone/Bluetooth report; `gh search issues earpiece|speakerphone` returned nothing. Only user-reported evidence is the brief. Likely cause (unverified): live voice runs a WebRTC call in a WebView; Chromium's WebRTC audio puts the phone in communication mode, where the default route is the earpiece when no headset is connected. Buddy never overrides it. Needs on-device confirmation.
- **Settings UI**: Settings → Voice card in `SetupActivity.java:424-` (built programmatically with `Ui` helpers, no XML layouts). Prefs are `SharedPreferences "buddy"` through static getter/setter pairs in `Prefs.java`. New English strings go in `res/values/strings.xml`; 18 translations exist but untranslated keys fall back to English (`CONTRIBUTING.md` Translations).
- **No unit tests for the Android app** (`app/src/test`, `androidTest` don't exist). CI: `helper-tests` (`bash linux/test/run.sh`, shell helper only) and `apk` (`./gradlew assembleGithubRelease assembleFdroidRelease`) in `.github/workflows/build.yml`. Verification is manual on device.
- **Watch routing is limited**: a watch is a Bluetooth hands-free (SCO/HFP) device; routing generic app audio through it works via communication-device APIs only when Android lists it. Details under Constraints.

## Repo map
Paths under `app/src/main/java/app/buddy/assistant/`.

| Feature | File:line | Capture / playback mechanism | Device choice today |
|---|---|---|---|
| Voice input, phone engine | `Voice.java:110-113` (`SpeechRecognizer.createSpeechRecognizer`) | System recognizer service | System default mic; no control |
| Voice input, Claude engine | `ClaudeDictation.java:56` → `MicServer.startCapture` | `AudioRecord` source `VOICE_RECOGNITION`, 16 kHz mono (`MicServer.java:110-113`) | Default input; no `setPreferredDevice` |
| Voice input, ChatGPT engine | `CodexDictation.java:74` → `MicServer.startCapture`; WebView page only sends it (`CodexDictation.java:186-188`) | same `MicServer` | same |
| Linux mic (`arecord` stand-in for Claude Code /voice) | `MicServer.java:222-246` (`serve`) | second `AudioRecord`, `VOICE_RECOGNITION` | Default input |
| Live voice mic | `CodexLive.java:856` `getUserMedia({audio:{echoCancellation…}})` in a WebView; permission granted at `CodexLive.java:628-630` | WebRTC in WebView | Chromium/system default |
| Live voice mic fallback | `CodexLive.java:751-754` (`ownMic`, `startCapture`) | `AudioRecord` source `VOICE_COMMUNICATION` + AEC (`:757-761`) | Default input |
| Live voice output | `CodexLive.java:818` `<audio id=out autoplay>`; `:866-868` sets `out.srcObject` | WebView `<audio>` | System decides (communication mode) |
| Read aloud, phone | `Voice.java:245-250, 280` `TextToSpeech`, `AudioAttributes USAGE_ASSISTANT / CONTENT_TYPE_SPEECH` | Android TTS engine | Default output for that usage |
| Read aloud, ChatGPT | `CodexSpeech.java:241-249` WebView `<audio autoplay>` + WebRTC receive | WebView | Default media output |
| Auto-pause on call | `AutoPause.java:144` reads `getMode() > MODE_NORMAL` | read-only | n/a |
| Volume tools | `PhoneTools.java:394-413` `STREAM_MUSIC` | n/a | n/a |
| Callers of listen/speak | `Voice.listen`: `Bubble.java:1509,1592,1717,2420`, `ChatActivity.java:1680`; `Voice.speak`: `Bubble.java:2399`, `ChatHub.java:951`, `SetupActivity.java:457` | | |

No wake word (owner declined: upstream issue #18 comment) and no notification sounds, `MediaPlayer`, `SoundPool`, `Ringtone` or `AudioTrack` in the app (grep). Vibration only (`Bubble.java:135`, `BuddyService.java:778`).

Settings and storage:
- Voice card: `SetupActivity.java:424-` (`sectionLabel`, `card()`, `fieldLabel`, `segmented(...)` for "Voice input" `:431-440` and "Voice output" `:445-451`, `text(..., 13, MUTED)` notes). Other toggles use `Ui.switchRow` (`Ui.java:394`, used at `SetupActivity.java:718-745`). `Ui.segmented` at `Ui.java:433/441` (second overload takes a disabled set + callback). Whole screen re-renders with `render()` after a pick.
- Prefs: `Prefs.java:12-13` (`getSharedPreferences("buddy")`); voice keys `voice_engine` (`:633`), `speech_engine` (`:712`), `speech_voice` (`:722`), `voice_langs`/`voice_lang` (`:731-749`), `speak` (`:307`), `speak_pause_media` (`:338`), `voice_cards` (`:316`). Pattern: `static X foo(Context)` + `static void setFoo(Context, X)`.
- Strings: `app/src/main/res/values/strings.xml` (voice block ~`:1279-1297`, live block `:618-636`, errors `:896-911`). Locale dirs: ar de es fr hi in it ja ko nl pl pt ru tr uk vi zh-rCN.
- Flavors `github`/`fdroid` (`app/src/github`, `app/src/fdroid`); F-Droid builds from source, so no non-free deps.
- Precedent for "setting that needs a permission/access": pause-media switch sends the user to `CheckActivity` when notification access is off (`SetupActivity.java:721-726`).
- Process rules: commits/feature docs convention in `AGENTS.md`; contributing says features are built in the project (`CONTRIBUTING.md:3`).

Build/test: `./gradlew assembleGithubRelease assembleFdroidRelease` (`.github/workflows/build.yml`), `./build.sh` on-phone (`CONTRIBUTING.md`), `bash linux/test/run.sh` for the Linux helper only.

## Prior art
- Upstream: no audio-routing issue or PR. Related shipped features: #37 pause media while reading aloud (2.3.6), #27 voice approvals, #18 wake word declined, #2 TTS restart fixed in 2.2.1 (`gh issue view … --comments`). Upstream's `main` is the base; remote `origin` (nlachica fork) has issues disabled.
- Android audio routing (web search, not read in full, treat as secondary): `setCommunicationDevice()`/`clearCommunicationDevice()`/`getCommunicationDevice()` replace `setSpeakerphoneOn`, `startBluetoothSco`, `stopBluetoothSco`, `isBluetoothScoOn`, deprecated in Android 14 ([Audio routing API updates in Android 14](https://developer.android.com/develop/connectivity/telecom/voip-app/api-updates)); `getAvailableCommunicationDevices()` lists SCO, wired, USB and BLE headsets; hearing aids only appear from Android 14 (same page). Android 17 moves SCO management into the audio framework with `setCommunicationDevice()` as the control point ([SCO audio management](https://source.android.com/docs/core/audio/sco-audio-mgmt)).
- Pattern to copy: a segmented/list chooser in the Voice card, a pref string, apply at the start of each recording/playback. For AudioRecord: `setPreferredDevice(AudioDeviceInfo)` after construction (API 23) (unverified in docs here).

## Constraints and risks
- **Bluetooth permission**: connecting to/using a BT device via `setCommunicationDevice`/SCO on API 31+ needs the runtime `BLUETOOTH_CONNECT` permission (unverified here; confirm in docs). Not declared today (`AndroidManifest.xml:13-14`), so a new permission prompt and F-Droid/store disclosure may be needed.
- **Mode coupling**: `AutoPause.java:144` treats `getMode() > MODE_NORMAL` as "a call is on" and pauses Buddy. If the feature sets `MODE_IN_COMMUNICATION` (or WebRTC does during live voice), AutoPause could mistake Buddy's own audio for a call. Needs checking by Planner/Coder (`AutoPause.java:140-144`).
- **Live voice** audio is inside a WebView; Buddy can't set its device. The system communication device is the only handle, and must be cleared (`clearCommunicationDevice`) on call end (`CodexLive` hang-up path, `:835-`, `hangUp()` JS) to avoid leaving the phone routed (e.g. speakerphone stuck on).
- **TTS/SpeechRecognizer**: no per-stream device API; selected output must come from the system route (media route follows connected BT A2DP/LE Audio automatically; speaker vs earpiece for non-call media is not user-selectable via these APIs, since earpiece is only used in communication mode — unverified).
- **Watch (Galaxy Watch / Wear OS)**: acts as a Bluetooth hands-free device; community reports say call audio can land on the wrong device and a watch speaker/mic is used for calls, not generic media; an Android Authority 2022 piece says Wear watches don't support audio clips/voice messages over BT headset ([search results: Samsung community threads](https://us.community.samsung.com/t5/Galaxy-S22/S22-Ultra-Sound-issue-when-connected-to-galaxy-Watch-5-Pro-LTE/m-p/2506978), [Android Authority](https://androidauthority.com/smartwatch-voice-message-3166180)). Anecdotal; no official doc found. Whether the watch shows up in `getAvailableCommunicationDevices()` on Nicholas's phone must be tested on device (`adb shell dumpsys audio` or a quick debug list). Watch routing may not be achievable; the Planner should treat it as "works if Android lists it".
- **Foreground/background**: MicServer only runs while Terminal visible (`MicServer.java` doc); live voice and TTS run from the accessibility service (`BuddyService`) or Activity, so context for `AudioManager` is available either way (`svc.getSystemService`, as in `AutoPause.java:144`).
- **Device list volatility**: devices appear/disappear (watch disconnects); saved pref must fall back to default when the chosen device is absent. Store a stable identifier (type + address/product name; `AudioDeviceInfo.getId()` isn't stable across reconnects, unverified).
- **No automated tests** for audio paths; verification must be on a real phone (emulator has no BT). Existing code tolerates failures silently (try/catch returning false), keep that style.
- AGENT rule: strings go in `values/strings.xml`; skipping translations is allowed (falls back to English).

## Answers to open questions
None yet (Planner has not written `plan.md`).
