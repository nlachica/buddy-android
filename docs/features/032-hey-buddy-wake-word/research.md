# Research: 032-hey-buddy-wake-word
<!-- Researcher, 2026-11-21 (claude-sonnet-5-5). Sources on every claim. -->

## Summary
- **Recommend openWakeWord with a custom-trained "hey buddy" model**, run with LiteRT (TFLite) `com.google.ai.edge.litert:litert`. Free, offline, Apache-2.0 code, no access key, ~0.4 MB model plus 2.4 MB shared mel/embedding models. Porcupine is a poor fit (see Engine table): its AccessKey is validated online, and its free tier reportedly ended 2026-06-30 (unverified).
- **No ready "hey buddy" model exists** (openWakeWord ships alexa, hey_jarvis, hey_mycroft, hey_rhasspy, timer, weather only). It must be trained once (openWakeWord's `automatic_model_training.ipynb`, synthetic TTS data, Colab/GPU) and the `.tflite` committed into the repo. Training in CI is not practical (multi-GB datasets). The official pre-trained models are CC BY-NC-SA 4.0 because of their training data; a model we train ourselves is our own artifact, but training features (ACAV100M) carry license ambiguity: flag for Nicholas, fine for a private fork (unverified).
- **Buddy has no foreground service today.** It stays alive as an AccessibilityService (`BuddyService`). Manifest has no `FOREGROUND_SERVICE*` permission and no `foregroundServiceType` anywhere. The feature needs a new microphone FGS, its permissions, and a notification.
- **Android's mic-FGS start rule is the biggest risk.** `RECORD_AUDIO` is a while-in-use permission: a microphone FGS cannot be started from the background, and **not from a BOOT_COMPLETED receiver** (restriction in place for microphone since Android 14). Starting from a notification action, bubble tap, widget, tile or visible activity is allowed. So "on after reboot" cannot be silent; it needs a user tap (or an on-device test showing the a11y service can start it: unverified).
- **Concurrent capture:** Android 10+ lets only one ordinary app get audio. `VOICE_COMMUNICATION` is privacy-sensitive by default, so a detector using it would silence a voice recorder or call app that starts later. Use a non-private source (`VOICE_RECOGNITION` or `MIC`) while idle, and watch `AudioRecordingCallback` for being silenced. Voice calls always win except for an accessibility service, so Buddy keeps capturing during calls unless it stops itself.
- **Reuse Talk's path:** `Bubble.talk()` listens with `Voice.listen`, sends to `sessions.current` (creating a chat if none) via `hub.send` (`Bubble.java:1636-1665`). Replies are spoken only when `Prefs.speakReplies` is on (`ChatHub.java:1399`), so wake turns need a force-speak path.
- **Battery is the cost.** Only anecdotal figures exist (Home Assistant: ~1% to ~15% battery use with wake word on; one developer: 25%/h unoptimised, 0.02%/min optimised). Must be measured on the S21 FE (SM-G990W, Android 16) and written into docs.
- **Build constraints:** `build.sh` (on-device) compiles with javac + android.jar only (`build.sh:7,35`), no Gradle deps; Gradle has only `play-services-wearable` (github flavor). A LiteRT dependency means an AAR unpack step in `build.sh` or accepting that on-device builds lack the feature. APK is arm64-only, 7.47 MB.

## Repo map

### Voice entry points
| What | Where |
|---|---|
| Assistant gesture activity, exported, only works when Buddy holds ROLE_ASSISTANT; calls `bubble.assist()` | `AssistActivity.java:15-28` |
| `Bubble.assist()`: Live if `assist_mode=live` and Codex signed in, else `talk()` | `Bubble.java:1598-1606` |
| `Bubble.talk()`: `Voice.listen` then `hub.send(sessions.current, text)`, creates chat if none | `Bubble.java:1636-1686` |
| Live voice: `goLive()` (selects chat by `Prefs.liveAgent`/`liveContinue`), `CodexLive` (WebView/WebRTC, "the call has the microphone") | `Bubble.java:1617-1634`, `Voice.java:118` |
| Hold menu (RadialMenu): Stop task, Pause/Resume, Add instruction, Stop voice, Talk, Live/End live, Open chat, Hide. Built in `openMenu()` | `Bubble.java:1511-1535`; `RadialMenu.Action(icon, label, danger, run)` |
| STT: `Voice.listen` (Android `SpeechRecognizer`, or `ClaudeDictation`/`CodexDictation`). `Voice.listening()`, `capturing()` | `Voice.java:87-96,116-200` |
| TTS: `Voice.speak` -> `here` -> `phoneSpeak` / `CodexSpeech` / `WatchVoice`; `isSpeaking()`, `stopSpeaking()` | `Voice.java:332-370,570-585`; TTS uses `USAGE_ASSISTANT` (`Voice.java:378`) |
| Reply read aloud only if `Prefs.speakReplies` (`read_aloud`), not in Live, not for watch chats | `ChatHub.java:1397-1401` |
| Pauses other media while listening/speaking (needs notification access; setting `pause_media_for_listening`) | `SpeechPause.java` |

### Audio routing (features 001/016/030/031)
- `AudioRoute.use(c)` is called before listening and speaking (`Voice.java:121,336`). Uses `setCommunicationDevice` (Android 12+), may set `MODE_IN_COMMUNICATION` itself (`AudioRoute.java:396-399`); `AudioRoute.ownsMode()` tells its own mode (`AutoPause.java:149`).
- Existing raw capture: `CodexLive.java:838-847` uses `AudioRecord(VOICE_COMMUNICATION)` + `AcousticEchoCanceler` (watch/phone live); `MicServer.java:113,243` uses `VOICE_RECOGNITION`. Reuse the AEC pattern from `CodexLive.java:846-848`.
- Dictation (Claude/ChatGPT) and Live capture through WebView getUserMedia (`PRIVACY.md:64`), so the detector must release the mic before any of them starts.

### Call and busy detection already present
- `AutoPause.callNow()` (`AutoPause.java:145-154`): `AudioManager.getMode() > MODE_NORMAL`, minus Buddy's own comm mode, plus `CATEGORY_CALL` notifications from `NotificationWatcher` (needs notification access). No `READ_PHONE_STATE` needed. Reuse; it polls at 700 ms.

### Settings
- Prefs are SharedPreferences getters/setters in `Prefs.java` (e.g. `speakReplies:346`, `assistMode:328`, `voiceCards:355`).
- `buddy_settings` registry: `SettingsTool.scalars()` with `onOff(name, get, set)` helper (`SettingsTool.java:150-152`); voice entries at `:186-198,339-398`. Tests: `SettingsCoverageTest`, `SettingsKeysTest`, `SettingsFamiliesTest` (all in `app/src/test`), which fail when a Prefs key lacks a registry entry (read them before planning; (unverified) exact rule).
- Settings -> Voice screen: `SetupActivity.voiceSection()` (`SetupActivity.java:674`); chat toggle for read-aloud at `ChatActivity.java:149`.

### Manifest and lifecycle
- Permissions: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`AndroidManifest.xml:4-30`). Missing: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`.
- Services declared: `BuddyService` (accessibility), `NotificationWatcher` (listener), `A11yWatch`, boot receiver `Schedules$Boot` (BOOT_COMPLETED, MY_PACKAGE_REPLACED, ...) at `AndroidManifest.xml:~230`, `Schedules.java:250-255`. No TileService.
- `BuddyService.onServiceConnected` creates `Bubble`, McpServer, etc. (`BuddyService.java:84-140`); good place to ensure the detector service. The bubble window is `TYPE_ACCESSIBILITY_OVERLAY` (`Bubble.java:167`); Buddy does not hold `SYSTEM_ALERT_WINDOW`.
- Notification channel for status: `ControlIndicator.CHANNEL="control"` (`ControlIndicator.java:21,91`).
- Build: Gradle `minSdk 30, targetSdk 36` (`app/build.gradle.kts:22-23`); CI runs `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease` (`.github/workflows/build.yml:63`); fdroid APK is compared for reproducibility (`build.yml:124`). Unit tests are plain JVM (`unitTests.isReturnDefaultValues`); 24 test classes in `app/src/test/java/app/buddy/assistant`.
- Docs to update: `docs/GUIDE.md:48` (Voice), `docs/PRIVACY.md:44,63` (says mic only when you tap: becomes false), README voice notes.

## Prior art

### Engine comparison (Android, arm64)
| | openWakeWord | microWakeWord | Porcupine |
|---|---|---|---|
| Code license | Apache-2.0 (GitHub API, README:276) | Apache-2.0 (GitHub API) | Apache-2.0 SDK, but needs AccessKey |
| Custom "hey buddy" | Train with notebook; none shipped | Train; notebook "advanced users... very difficult" (README) | Console trains a `.ppn` in seconds (free plan, per search results) |
| Model size | wake model 0.2-1.7 MB tflite (release v0.5.1 assets); shared `melspectrogram.tflite` 1.09 MB + `embedding_model.tflite` 1.33 MB | tiny, built for MCU (TFLite Micro), ~tens of KB (unverified size) | ~ hundreds of KB `.ppn` (unverified) |
| Runtime on Android | LiteRT/TFLite (arm64 `libtensorflowlite_jni.so` 4.3 MB raw, 1.7 MB zipped, litert 1.4.0 AAR) or ONNX Runtime (AAR 28.5 MB, 1.22.0, all ABIs) | TFLite Micro ops + own audio frontend; no Android port found | Official Android SDK |
| Android ports | `Re-MENTIA/openwakeword-android-kt` (ONNX, Apache-2.0, 21 stars, 2025-07), `msnilsen/openwakeword-android` (ONNX, 2026-03) | none found | official |
| Accuracy claim | target <0.5 false accept/h, <5% false reject (README:123,146); 80 ms frames | not documented in README | vendor benchmark; no phone battery figures (research note 6) |
| Key / network | none | none | AccessKey validated against Picovoice servers (search results; whether periodic is unverified); free-tier keys reported to stop after 2026-06-30 ([HA forum thread title](https://community.home-assistant.io/t/fyi-picovoice-confirmed-free-tier-accesskeys-will-stop-working-after-june-30-2026/1012744)), unverified |
| Last push | 2025-12-30, v0.6.0 | 2026-07-09 | 2026-11-12 |

Pipeline per openWakeWord (README:125,154-156): 16 kHz audio, 80 ms frames -> mel spectrogram model -> speech-embedding model (Google, Apache-2.0) -> small wake classifier; score 0..1; optional Silero VAD and Speex noise suppression. Official pre-trained models are CC BY-NC-SA 4.0 (README:278). Two dependency routes: LiteRT (preferred: small, arm64 only fits the arm64-only APK) vs ONNX (bigger). Porting to Java means re-implementing the streaming buffers (mel frames, 76-frame embedding window, feature ring buffer); the Kotlin ports are a reference. `ncpu` and frame batching are the CPU knobs.

Prior Buddy research: `context-hey-google-research.md` note 6/7: DSP hotword is impossible for a normal app; upstream issue #18 "wakeup / hot word similar to ok google" is CLOSED (declined).

### Battery figures (all weak; none is a controlled phone benchmark)
- Home Assistant companion app: battery use rose from roughly 1% to 15% with wake word on ([Open Home Foundation](https://openhomefoundation.org/blog/a-big-win-for-android-interoperability/), as reported by search; window unstated). HA docs: "uses more battery than Ok Google" ([HA](https://www.home-assistant.io/voice_control/android/)).
- One developer write-up (reposted [feddit.nl](https://old.feddit.nl/post/27849586)): 0.41%/min (~25%/h) unoptimised ONNX, 0.02%/min (~1.2%/h) after native optimisation. Secondhand.
- Picovoice: only CPU figures (0.6% on a Raspberry Pi 5), says to measure on your device ([FAQ](https://picovoice.ai/docs/faq/porcupine/)).
- Drivers: CPU wakeups from continuous 80 ms inference, mic held open (screen-off keeps SoC out of deep idle), the foreground notification, any WakeLock. Mitigations: LiteRT XNNPACK with 1-2 threads, run mel+embedding only (cheap VAD gate first, e.g. energy threshold, then full pipeline), `PARTIAL_WAKE_LOCK` only if the CPU sleeps through the mic stream (it normally does not while recording: unverified).
- Plan for measurement: `adb shell dumpsys batterystats --reset`, 1 h screen-off run, read `dumpsys batterystats` per-UID mAh; compare with feature off. Android Studio Energy Profiler not available on phone.

## Constraints and risks

### Android 14-16 rules (official docs fetched 2026-11-21)
- Microphone FGS needs `FOREGROUND_SERVICE_MICROPHONE` permission, `android:foregroundServiceType="microphone"`, runtime `RECORD_AUDIO`. "You cannot create a microphone foreground service while your app is in the background and you cannot launch a microphone foreground service from a BOOT_COMPLETED receiver, with a few exceptions" ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)).
- Exceptions that keep while-in-use access ([restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), updated 2026-11-16): system component starts it; app widget or **notification** interaction; PendingIntent from a visible app; device-owner DPC; the app provides the `VoiceInteractionService`; `START_ACTIVITIES_FROM_BACKGROUND`. The general (non-while-in-use) list also has bubble interaction, exact alarm, IME, BOOT_COMPLETED/MY_PACKAGE_REPLACED (but mic is carved out), and `SYSTEM_ALERT_WINDOW` with a visible overlay (Android 15+). **An accessibility service is not listed** and Buddy lacks SYSTEM_ALERT_WINDOW; its overlay is `TYPE_ACCESSIBILITY_OVERLAY`. Whether the a11y process counts: unverified, test on device (logcat message "Foreground service started from background can not have location/camera/microphone access").
- Implication: after reboot or app update, listening can start only after a user action (tap a notification action, the bubble, a QS tile, open the app). Plan options: Buddy posts a "Tap to turn on Hey Buddy" notification after boot/update; or the tile/bubble tap starts it. The same applies after the FGS is killed.
- Android 15: `BOOT_COMPLETED` also blocks dataSync/camera/mediaPlayback/phoneCall/mediaProjection FGS types ([behavior changes 15](https://developer.android.com/about/versions/15/behavior-changes-15)); no 6 h timeout for microphone type (only dataSync/mediaProcessing). Android 16/One UI 8: nothing found beyond these (unverified for One UI task killers; Samsung "deep sleeping apps" can kill it; mitigate with `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, already held).
- Foreground service notification must be shown (user can swipe it away on 14+ for most types but not removed from the Task Manager); channel low importance.
- Mic indicator (green dot) stays lit permanently while listening; the Android 12+ mic privacy toggle turns capture to silence.

### Concurrent capture ([sharing audio input](https://developer.android.com/media/platform/sharing-audio-input))
- Priority: privileged (pre-installed, assistant, **accessibility services**) > ordinary; visible UI > background; privacy-sensitive source (`VOICE_COMMUNICATION`, `CAMCORDER`) > non-private; two ordinary apps never both get audio; a background tie goes to the most recent starter.
- `AudioRecord.Builder.setPrivacySensitive(false)` can override the default for `VOICE_COMMUNICATION` (API 30+, same page). Not setting it makes the detector steal mic from other apps: avoid when idle.
- Accessibility service + ordinary app: both get audio only when the service UI is on top, otherwise the two-ordinary-apps rule. A voice call (`MODE_IN_CALL`/`MODE_IN_COMMUNICATION`) always receives audio, and an accessibility service can also capture, so Buddy must stop itself during calls (`AutoPause.callNow`).
- Detect being silenced: `AudioRecord.registerAudioRecordingCallback(executor, AudioManager.AudioRecordingCallback)`; `onRecordingConfigChanged` gives `AudioRecordingConfiguration.isClientSilenced()`. Must be registered before `startRecording()`. Other apps' recordings: `AudioManager.getActiveRecordingConfigurations()` (client uid not exposed to ordinary apps; use `getClientAudioSource`, count > own).
- Buddy's own mic users: `Voice.listen` (SpeechRecognizer service, in Google's process), `ClaudeDictation`/`CodexDictation`/`CodexLive` (WebView), `MicServer` (VOICE_RECOGNITION for the Linux side). Detector must release (stop `AudioRecord`) before any starts and restart after; hook points: `Voice.listen` entry (`Voice.java:116`), `CodexLive.on()`, `Voice.capturing()` (`:92`), `MicServer` start.

### Call state without READ_PHONE_STATE
- `AudioManager.getMode()` polling (as `AutoPause`), `AudioManager.addOnModeChangedListener` (API 31, but minSdk is 30: guard), `CATEGORY_CALL` notifications via `NotificationWatcher`. `TelephonyCallback`/`PhoneStateListener` need `READ_PHONE_STATE`: avoid (new runtime permission).
- Buddy's own mode (`AudioRoute.ownsMode()`, `CodexLive.on()`) must be excluded exactly as `AutoPause.callNow` does, or Buddy's own speech would look like a call.

### AEC / barge-in
- While TTS plays the mic must cancel Buddy's own speech. `AcousticEchoCanceler.create(audioSessionId)` works on the `AudioRecord` session (`CodexLive.java:846-848` pattern; `isAvailable()` first). `VOICE_COMMUNICATION` source normally applies AEC/NS/AGC itself; but it is privacy-sensitive (see above), acceptable only while Buddy is itself speaking (short). Option: keep one `AudioRecord` on `VOICE_RECOGNITION` + attached AEC always (effect availability per device: unverified on SM-G990W; check `AcousticEchoCanceler.isAvailable()`), else switch source to `VOICE_COMMUNICATION` during TTS. TTS here is on USAGE_ASSISTANT; for AEC to have a reference signal the playback should go through the same audio session routing (platform AEC uses the device's playback reference; Samsung's implementation: unverified, test).
- Stop phrase: detection of "hey buddy" while TTS plays only gets the wake word; the following words ("stop") need STT. Cheapest: on wake during TTS, immediately `Voice.stopSpeaking()` (duck first), then listen for the command; the brief says "within ~1 s", which this meets. No streaming STT keeps running during TTS; ask the Planner to decide.
- `SpeechRecognizer` is Google's service and uses the shared mic; the engine selection (`voice_input` phone/claude/chatgpt) decides which STT transcribes the wake turn: `Voice.listen` already follows it. "Hey buddy what's the weather" in one breath: the wake detector fires after "hey buddy" and the rest of the sentence is in the audio stream: either (a) keep a ~1-2 s pre-roll / continue the same capture into a recogniser (needs PCM to STT: only `ClaudeDictation`/`CodexDictation`/`MicServer`-style paths take raw PCM, `SpeechRecognizer` cannot take a stream before API 33 `EXTRA_AUDIO_SOURCE`), or (b) release the mic and start `Voice.listen`: the first words after the wake are lost. Android 13+ `RecognizerIntent.EXTRA_AUDIO_SOURCE` takes a ParcelFileDescriptor (documented API 33; unverified here); minSdk is 30. Planner must choose; (b) loses "what's the weather" if said without pause. This is the main functional design risk.

### Quick Settings tile
- `TileService` (API 24+) fits minSdk 30; needs `<service android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">` with `android.service.quicksettings.action.QS_TILE`; the tile click is a user action and may start the mic FGS. Buddy has none yet. Users must add the tile manually (Android 13 `StatusBarManager.requestAddTileService` can prompt). The bubble hold menu is the cheaper toggle: add an action in `openMenu()` (`Bubble.java:1511-1535`).

### Other risks
- A11y service is the process host: if the user disables it Buddy has no bubble; the FGS would still run (separate service, same process).
- `PRIVACY.md` currently promises the mic is used "only when you tap"; must change. Play-store-style disclosure not needed (GitHub/F-Droid).
- F-Droid flavor: LiteRT from Google Maven (binary AAR) may not satisfy F-Droid source-only policy (unverified); consider github flavor only or a source build of the native lib.
- `build.sh` on-device build has no dependency resolution (`build.sh:35`); needs the AAR classes + `.so` unpacked into `jniLibs/arm64-v8a` and classpath, or the feature excluded there.
- Voice pause settings (`voice_pause`, `voice_max`) and `EndOfSpeech` (`Voice.java:35-55`) exist for end-of-speech detection of raw PCM engines: reusable for the "hey buddy" alone -> listen path.
- Samsung's battery management can kill the FGS silently; no restart from BOOT is allowed for mic (above).

## Answers to open questions
(none yet: no `## Open questions` in plan.md)
