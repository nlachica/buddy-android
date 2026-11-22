# Plan: 032-hey-buddy-wake-word
<!-- Planner, 2026-11-21 (claude-fable-5-2). Round 0. -->

## Goal
Saying "hey buddy" (alone, or with the request in the same breath) sends the request, without the wake phrase, to
Buddy hands-free, and the reply is spoken. A later "hey buddy, <follow-up>" continues the same chat. "hey buddy, stop"
while Buddy speaks cuts the speech within ~1 s. It runs on-device in a microphone foreground service with a persistent
notification, is on by default, pauses for calls, other apps' recordings and Buddy's own voice paths, and can be
switched in Settings → Voice, from a quick toggle and through `buddy_settings`. The setting survives restart and
reboot. Battery impact is written into the user docs and this folder. Pure logic is unit-tested and CI stays green.

Non-goals (brief): DSP hotword, default-assistant role, root; speaker verification, several or user-trained wake
words; replacing the Talk/Live/dictation paths or "Hey Google"; wake word on the watch.
Non-goals (this plan): a Quick Settings tile; a threshold or sensitivity setting; an energy/VAD gate (see Risks R4);
the wake word in the F-Droid flavor and in `build.sh` builds; using a headset's microphone for the wake word;
stopping a running task by voice (only speech is cut).

## Design

### 1. Engine and model (overrules research on two points)
- **Model: no training.** Research said no "hey buddy" model exists. One does: `models/hey-buddy.onnx` in
  `huggingface.co/benjamin-paine/hey-buddy` (code repo `painebenjamin/hey-buddy`, Apache-2.0; its README: "source code
  and pretrained models are released under the Apache License 2.0"; the HF card says CC BY 4.0, so we attribute).
  Checked 2026-11-21 with `gh` and the HF API: `fwartner/home-assistant-wakewords-collection` has no hey_buddy
  (nearest: hey_billy, hey_barabas), and GitHub code search finds no `hey_buddy.tflite`/`.onnx` elsewhere.
- Pinned files (HF revision `68a13d58e216e8e4837559ae665ca3e9769b41c2`):

  | File | Bytes | sha256 |
  |---|---|---|
  | `models/hey-buddy.onnx` | 1186048 | `dfeb1222f8b45da7b151aa66882404632a4d058860828ac29a6756616fbaf397` |
  | `pretrained/mel-spectrogram.onnx` | 1087958 | `ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f` |
  | `pretrained/speech-embedding.onnx` | 1326578 | `70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f` |

  The last two are byte-identical to openWakeWord v0.5.1's `melspectrogram.onnx` and `embedding_model.onnx` (same
  sha256; Google's speech embedding, Apache-2.0). So this is openWakeWord's feature front end with the hey-buddy
  classifier. They are committed as assets (3.6 MB), with their hashes in a `SHA256SUMS` file a test checks.
- **Runtime: ONNX Runtime Android 1.22.0 (MIT), not LiteRT.** The models are ONNX and the classifier was trained on
  features from exactly these two ONNX files. LiteRT would need the classifier converted with onnx2tf, which needs
  TensorFlow: not installable on the phone's Alpine (musl), and a `workflow_dispatch` job only runs once its file is on
  `main`, which the Coder can't push. Cost of ORT: about 6.5 MB more APK (arm64 only; 1.22.0's AAR is 28.5 MB for four
  ABIs). Not the latest 1.32.0: its arm64 library is 36.3 MB raw, 13.7 MB zipped (measured). Expected APK: 7.5 MB →
  about 17 MB; the Coder records the real number.
- Alternatives: LiteRT + converted classifier (smaller by ~4.5 MB, needs a conversion pipeline; revisit only if size
  matters); training with openWakeWord's notebook (needs a GPU and Nicholas; fallback if this model fails the probe,
  R1); microWakeWord (no Android port); Porcupine (key validated online, free tier reported ended).

### 2. Feature pipeline (a Java port of `src/python/heybuddy/embeddings.py`, the path the model was trained on)
16 kHz mono 16-bit. Every 1920 samples (120 ms):
1. the last 17280 samples (1.08 s), as float32 in int16 range → mel model (`input` [1,17280] → `output`
   [1,1,105,32]), then `x/10 + 2`;
2. 4 windows of 76 frames at frame offsets 0, 8, 16, 24 → embedding model (`input_1` [4,76,32,1] → `conv2d_19`
   [4,96]);
3. the last 4 such groups (16×96, oldest first) → classifier (`input` [1,16,96] → `output`, one float 0..1);
4. detected when the score ≥ `THRESHOLD` (0.5, the library's default; a constant, tuned from the probe).

This is not openWakeWord's 80 ms streaming scheme, so its Kotlin ports are not a reference. The upstream JS feeds
floats in -1..1; the Python (training) feeds int16 range. The CI test on real clips (Test plan T1) is the arbiter; the
Coder records which scale passes.

Split so the framing is testable without a phone:
- `WakeFeatures` (`src/main`, pure Java): the ring buffer, the hop counter, the windowing and the group history,
  around `interface Engine { float[] mel(float[] audio); float[] embed(float[] windows); float score(float[] emb); }`.
  `float feed(short[] pcm, int n)` returns the newest score or -1 when no hop completed. It also hands out the last
  1.5 s as the pre-roll.
- `OnnxWakeEngine` (`src/wakeword/java`, github flavor only): the three `OrtSession`s (one intra-op thread each), no
  `android.*` import, models passed in as `byte[]`.

### 3. Flavors and builds
Github flavor only, by the watch link's pattern: `sourceSets["github"]` gets `java.srcDir("src/wakeword/java")` and
`assets.srcDir("src/wakeword/assets")`, the dependency is `"githubImplementation"`, and `src/main` loads the engine by
name (`WatchVoice.load`'s reflection). `WakeWord.available()` is false in the F-Droid flavor and in `build.sh` builds:
no switch, no menu item, `hey_buddy` refuses "on". Why: the F-Droid APK then carries neither the native library nor
the models, so `build.yml`'s reproducibility and no-Google-code steps see an APK that differs only by plain Java, and
`build.sh` (javac, no Maven) keeps compiling. `defaultConfig.ndk.abiFilters += "arm64-v8a"` keeps ORT's three other
ABIs out (Buddy's own native libraries are arm64 only already). The service and its two permissions are declared in
the main manifest (a source set takes one manifest file and github's is the watch link's); in the F-Droid flavor the
service is never started.

### 4. Service, start rules, reboot
- `WakeService` (new, not exported, `foregroundServiceType="microphone"`) owns the `AudioRecord`, the capture thread
  and the notification. New permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`.
- `WakeWord.ensure(ctx)` (main thread) is the one entry: it starts the service when `available()`, the setting is on,
  the microphone is granted and `BuddyService` is connected; otherwise it stops it. Called from
  `BuddyService.onServiceConnected` (boot after first unlock, app update, a11y rebind), `onUnbind`/`onDestroy` (stop),
  the Settings switch, `buddy_settings`, the hold-menu toggle, the notification actions and `MainActivity.onResume`.
- **Reboot and update are settled at run time, not assumed.** Android refuses a microphone foreground service from
  the background. Whether Buddy's accessibility-service process counts as background is undocumented (research). The
  system binds accessibility services with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`, which is one of the
  while-in-use exemptions in AOSP's `ActiveServices` (planner's reading of the source, unverified on One UI 8). So the
  code tries and handles the refusal: `startForegroundService` and `startForeground(id, n,
  FOREGROUND_SERVICE_TYPE_MICROPHONE)` are wrapped; on `ForegroundServiceStartNotAllowedException` or
  `SecurityException` (what targetSdk 34+ throws when while-in-use access is not allowed) the service stops itself and
  posts one quiet notification, **"Tap to turn on Hey Buddy"**, whose content intent is
  `PendingIntent.getForegroundService(WakeService)` (a notification tap is a documented exemption). Opening Buddy
  (`MainActivity.onResume`) starts it too. The refusal is logged (`Log.i("BuddyWake", …)`), and the after-switch
  reboot check (T6) records which path this phone takes.
- `START_STICKY`; a restart by the system goes through the same try/catch.
- Notification (channel `wake`, `IMPORTANCE_LOW`, ongoing): "Hey Buddy is listening" or "Hey Buddy is paused: <call |
  the microphone is in use>", action **Turn off** (sets the setting off), tap opens Settings → Voice.
- Shortcut, marked in code: on Android 11-13 a background start gives silence instead of an exception; not detected
  (the one phone is on Android 16).

### 5. States: one pure function decides
`WakeWord.state(Inputs)` → `OFF | PAUSED | IDLE | SPEAKING | TURN`, evaluated on the main thread every 500 ms, on
every `AudioManager.AudioRecordingCallback` event and at the hooks below. The service applies the result.

| State | When | Microphone |
|---|---|---|
| OFF | setting off, no mic permission, no engine | closed, service stopped |
| TURN | a wake turn is feeding the STT (§6) | stays open, detection off |
| PAUSED | a call (`AutoPause`'s rule: audio mode not normal and not Buddy's own, or a call notification); or Buddy's own voice path (`Voice.listening()`, `CodexLive.on()`); or **any recording on the phone that isn't the detector's own** (`getActiveRecordingConfigurations()`, minus our session id and `HOTWORD`-source clients) | closed |
| SPEAKING | `Voice.phoneSpeaking()` | `VOICE_COMMUNICATION` + `AcousticEchoCanceler` (CodexLive's pattern, `CodexLive.java:838-848`), `setPrivacySensitive(false)` |
| IDLE | otherwise | `VOICE_RECOGNITION` (not privacy-sensitive, as `MicServer`) |

- Leaving PAUSED waits until the cause has been gone for 1 s, so a recorder that restarts doesn't make it flap.
- The "any other recording" rule covers a voice recorder, a call app, the Terminal's microphone, Talk with any engine,
  dictation and Live with one check, and the detector never needs to notice being silenced: it has already let go.
- `AutoPause.callNow()` becomes package-visible (`inCall()`), unchanged otherwise.
- One synchronous hook: `Voice.listen` calls `WakeWord.release()` first (closes the microphone at once unless a wake
  turn is handing over), because Android's recognizer runs in another app and must not start against an open mic.
- IDLE ↔ SPEAKING reopens the `AudioRecord` (~0.1-0.2 s deaf at the start and end of speech).
- The capture thread reads 40 ms blocks and runs the pipeline inline every third block. No wake lock of our own:
  Android holds one for an active recording (checked in the probe with the screen off).

### 6. A wake turn, and the one-breath request
On a detection (capture thread):
1. If the chosen voice input records through `MicServer` (`voice_input` = claude with a supported language, or
   chatgpt; new helper `Voice.usesMicServer(c)` mirroring `Voice.listen`'s choice): `MicServer.adopt(preroll)`, at
   once, on the capture thread. `MicServer` gets an external-source mode: the pre-roll (the last 1.5 s, wake phrase
   included) becomes its first chunk, the capture thread keeps appending through `MicServer.feed(chunk)` (which also
   calls `levels`), and `startCapture()` keeps what is there instead of opening a second `AudioRecord`. So the same
   recorder carries on without a gap, and Claude's and ChatGPT's dictation get the audio from before the detection,
   exactly as they already get "everything from the tap". `stopCapture()`/`endCapture()` end the feed; the thread sees
   `!MicServer.recording()` and returns to detecting. A turn is cut at 2 minutes (`Voice.stopListening()` +
   `endCapture()`).
2. Then on the main thread, `WakeWord.onWake()`: `boolean wasSpeaking = Voice.isSpeaking(); Voice.stopSpeaking();`
   (the barge-in: speech stops on the wake word itself, well inside 1 s), a short vibration, then
   `bubble.talk(true)`. If Buddy is already listening or a Live call is on, the detection is dropped and the adopted
   capture ended.
3. `Bubble.talk()` gains a `wake` flag and is otherwise reused (waves, the "Listening…" pill, `Voice.listen`,
   end-of-speech; UI calls are skipped when the bubble isn't showing). In `onFinal` for a wake turn:
   `req = WakeWord.strip(text)`; empty → nothing; `WakeWord.isStop(req)` → nothing (speech is already cut); otherwise
   the request goes to the wake chat (§7). A send error is toasted and spoken.

Pure functions in `WakeWord`, unit-tested:
- `strip(text)`: removes everything up to and including the first wake phrase when it starts within the first 8
  words (hey/hay/hi/a/eh/okay + buddy/buddie/body, any punctuation between and after), or a bare leading "buddy";
  no match → the text unchanged (the detector fired, the recognizer dropped the phrase). Cutting "up to" also drops
  words of Buddy's own speech that leaked into the pre-roll.
- `isStop(req)`: the whole request is a stop command: stop, ok stop, stop talking, ok stop talking, be quiet, quiet,
  shut up, that's enough, never mind, cancel (case, punctuation and a leading ok/okay/please ignored).

**Fallback.** With `voice_input` = phone (Android's recognizer cannot take our audio before API 33, and
`EXTRA_AUDIO_SOURCE` is unproven on Samsung), and whenever `adopt` isn't possible, step 1 is skipped: `Voice.listen`
closes the detector's microphone and the recognizer opens its own. "hey buddy" + a short pause + the request works;
words said in the same breath can be lost. The guide says so. Nicholas's setting is `voice_input = claude`, so his
path is the gapless one (Questions, Q4).

### 7. Which chat, and speaking the reply
- **Chat:** one ongoing chat, remembered in `Prefs.wakeChat` (its id). A turn uses it while it exists and is not
  archived, incognito, a watch chat or a helper's; otherwise it creates one with the default agent, titled
  "Hey Buddy", and remembers it. It is **not** made the current chat: Talk sends to whichever chat is open, which for
  a word spoken across the room could be a chat with Bypass approvals or one in the middle of a task (`hub.send` to a
  running chat adds an instruction to that task). A dedicated chat keeps "the follow-up goes to the same chat as the
  previous wake turn" true whatever is open (Q1).
- **Speaking:** `read_aloud` is off for Nicholas, so a wake turn sets a transient `Session.spokenRun`; `ChatHub.add`
  speaks when `Prefs.speakReplies(app) || ses.spokenRun` (the other conditions unchanged) and the flag is cleared
  where `scheduledRun`/`quietRun` are, at the run's end. Speech goes through `Voice.speak` as today, so it follows
  Voice output and Audio device (Q3).

### 8. Settings and the quick toggle
- `Prefs.wakeWord` (key `wake_word`, default **true**), `Prefs.wakeChat`.
- Settings → Voice: a `switchRow` "Hey Buddy" with a two-line note (always listening on this phone, nothing leaves it
  before the wake word, uses more battery, paused in calls and while another app records). Turning it on without the
  microphone permission asks for it (`Voice.askMic`).
- `buddy_settings`: `hey_buddy` (on, off) in `SettingsTool.scalars()`; its setter calls `WakeWord.ensure`; "on" is
  refused in a build without the engine.
- **Quick toggle: the bubble's hold menu**, "Hey Buddy off" / "Hey Buddy on" (new `ms_mic_off` icon, Material
  Symbols), placed before Open chat, plus **Turn off** on the listening notification, which is there anyway. No Quick
  Settings tile: it is a new exported service that the user must add to the shade by hand, for a switch the hold menu
  (Buddy's own quick-action surface) and the notification already give.

Alternatives considered, one line each:
- Capture straight from the accessibility service without a foreground service (Talk does it today): the brief asks
  for a foreground service, and only that keeps the microphone with the screen off.
- `startForeground` on `BuddyService` itself: mixes foreground state into the accessibility service; a second service
  is the standard shape.
- Release the microphone on every wake and call `Voice.listen` (research option b): loses a one-breath request.
- A second, always-on streaming STT for stop words during speech: a second voice stack; cutting on the wake word meets
  "within ~1 s".
- Detector on `VOICE_COMMUNICATION` all the time: privacy-sensitive by default and processed for calls; kept to the
  seconds Buddy speaks.

## Steps
Each is one commit. Paths are under `app/src/` unless they start elsewhere.

1. **Engine, models, CI proof.** `app/build.gradle.kts` (github source set dirs, `githubImplementation
   com.microsoft.onnxruntime:onnxruntime-android:1.22.0`, `testGithubImplementation
   com.microsoft.onnxruntime:onnxruntime:1.22.0`, `abiFilters`); `wakeword/assets/wakeword/{hey-buddy,mel-spectrogram,
   speech-embedding}.onnx` + `SHA256SUMS`; `tools/wakeword-models.sh` (downloads the pinned revision and checks the
   hashes: the reproducible source); `main/java/.../WakeFeatures.java`; `wakeword/java/.../OnnxWakeEngine.java`;
   tests `test/.../WakeFeaturesTest.java` (fake engine) and `testGithub/.../OnnxWakeEngineTest.java` with
   `testGithub/resources/wakeword/*.wav` (16 kHz mono, from the HF dataset's `examples/hey_buddy.mp3`,
   `hey_buddy_augmented.mp3`, `hey_buddy_adv.mp3`, padded with silence; CC BY 4.0); `third_party/README.md` and the
   README licence line (ONNX Runtime MIT; hey-buddy model; Google speech embedding via openWakeWord, Apache-2.0).
2. **Probe on the phone** (playbook rule: device checks before the phone runs the fork). A throwaway app
   `app.buddy.wakeprobe`, outside `app/`, with `WakeFeatures`, `OnnxWakeEngine` and the capture loop copied unchanged:
   a microphone foreground service, a screen with the live score and a detection count, a switch between the IDLE and
   SPEAKING microphone setups while it plays a spoken paragraph, the list of active recording configurations, and the
   time per hop. Results go to `docs/features/032-hey-buddy-wake-word/probe.md`. Gates in Test plan (P1-P5). If P1
   fails, stop and tell Nicholas (R1). If the probe can't be built, say so in `decisions.md`; its checks then run
   after the switch and are fixed forward.
3. **Pure logic.** `main/java/.../WakeWord.java` (`strip`, `isStop`, `state`, `available`); `test/.../WakeWordTest.java`.
4. **Setting.** `Prefs.java`, `SettingsTool.java`, `SetupActivity.java` (`voiceSection`), `res/values/strings.xml`
   (English only, as CONTRIBUTING says), a case in `SettingsCoverageTest.java`.
5. **Service.** `main/AndroidManifest.xml`; `main/java/.../WakeService.java`; `WakeWord.ensure/release`;
   `BuddyService.java` (connect, unbind/destroy); `MainActivity.java` (`onResume`); `HiddenNotice.java` (Turn off);
   `AutoPause.java` (`inCall()` visible). After this step Buddy detects and logs, and pauses and resumes; nothing is
   sent yet.
6. **Wake turn.** `MicServer.java` (`adopt`, `feed`, `startCapture` keeps an adopted capture); `Voice.java`
   (`usesMicServer`, the `release()` hook); `Bubble.java` (`talk(boolean wake)`, wake chat); `Sessions.java`
   (`spokenRun`); `ChatHub.java` (speak condition, reset); `WakeWord.onWake`.
7. **Quick toggle.** `Bubble.java` (`openMenu`), `res/drawable/ms_mic_off.xml`, strings.
8. **Docs.** `docs/GUIDE.md` (Voice: a "Hey Buddy" entry with the battery cost and the limits; the "there's no wake
   word" sentence), `docs/PRIVACY.md` (Voice and the Microphone permission line; the two new permissions), `README.md`,
   `AssistActivity.java`'s comment, `docs/features/032-hey-buddy-wake-word/battery.md` (figure, method, what drives it).

## Test plan
Where each check runs: **CI** (`build.yml` on the branch), **probe** (step 2, before release), **after-switch** (the
released fork on the phone; rows that need it stay `pending` in `verify.md` until then).

| # | Done-when | Proof |
|---|---|---|
| T1 | On-device engine, model in the APK, foreground service + notification | CI: `OnnxWakeEngineTest` runs the real models over the clips through `WakeFeatures`: both positive clips peak ≥ 0.5; the sound-alike clip, silence and white noise stay < 0.5; `SHA256SUMS` matches the assets. `unzip -l` of the github APK lists the three models and `lib/arm64-v8a/libonnxruntime.so` only for arm64. Probe P1: 10 "hey buddy" at arm's length in a quiet room, ≥ 8 detected; 30 min of TV or podcast speech, ≤ 2 false detections. After-switch: the notification shows; airplane mode on, detection still works. |
| T2 | One breath sends the request without the phrase; alone starts listening | CI: `WakeWordTest` strip table ("Hey buddy, what's the weather like" → "what's the weather like"; "hey body what…", "…anyway hey buddy what…", "buddy, what…", "hey buddy" → "", no phrase → unchanged). CI: a `MicServer` test: after `adopt(p)` + `feed(a)` + `startCapture`, `pull` returns p then a, and `stopCapture` ends the feed. After-switch: "hey buddy what's the weather like" in one breath, 5 times: the chat shows the request without the phrase and the reply is spoken; "hey buddy", pause, request: same. |
| T3 | Follow-up goes to the same chat | After-switch: "hey buddy, what about tomorrow" lands in the "Hey Buddy" chat with another chat open; the reply shows it used the context. CI: none (two lines of lookup). |
| T4 | "hey buddy, stop" cuts speech ≤ ~1 s; AEC during playback | CI: `isStop` table (stop, ok stop talking, be quiet → true; "stop the timer", "what's the weather" → false). Probe P2: while the phone speaks a paragraph at media volume 60%, 10 "hey buddy": detections per microphone setup, and Buddy's own speech must not trigger it; the SPEAKING setup must reach ≥ 7/10, else the setup is revisited before step 5. After-switch: stopwatch from the end of "buddy" to silence, 5 tries, each ≤ 1 s; "stop" is not sent to the chat. |
| T5 | Pauses for calls and other recorders, resumes; Buddy's own paths don't fight | CI: `WakeWordTest` state table (every input combination that matters, and the 1 s resume delay). After-switch, with `logcat -s BuddyWake`: a phone call → PAUSED, back to IDLE after it; Samsung Voice Recorder records a clean 20 s memo while the notification says paused, then IDLE; tap Talk, chat dictation, a Live call: each works as before and the detector returns. Probe P3: the recording configurations at idle show no always-on recorder that would pause it for good. |
| T6 | On by default; Settings switch; quick toggle; survives restart and reboot | CI: default true (test). After-switch: fresh update → listening without touching settings (or the tap notification); the switch and the hold-menu item stop and start it and stay in step; kill Buddy → back; reboot → `BuddyWake` log says whether the accessibility start was allowed; if not, one tap on the notification turns it on. The outcome goes into `verify.md` and the guide. |
| T7 | `buddy_settings` reads and changes it | CI: `SettingsCoverageTest` case (`hey_buddy` found; accepts on/off; refuses others). After-switch: `buddy_settings hey_buddy` reads, and changes with a card. |
| T8 | Battery in the docs and the folder | Probe P4: `dumpsys batterystats --reset`, 1 h unplugged with the screen off and the probe listening, then its mAh; the same hour without. Probe P5: ms per hop. Both, the method and the drivers (CPU kept awake by the open microphone; inference 8 times a second) go into `battery.md` and the guide. If the hour can't be had before release, the guide carries the sourced range from research, marked as an estimate, and the measured figure replaces it after the switch. |
| T9 | Unit tests and green CI | CI: `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease` green, including "Check the F-Droid APK has no Google code" and "Check the F-Droid APK is reproducible". `assembleFdroidRelease` has no ONNX Runtime on its classpath, so it also proves `src/main` compiles without it, which is what `build.sh` needs. `unzip -l` of the F-Droid APK shows no `libonnxruntime.so` and no `assets/wakeword/`. |

## Risks
- **R1. The pretrained model may not suit Nicholas's voice or rooms.** It is trained on synthetic voices; nobody has
  measured it here. The probe (P1) decides before anything ships. If it fails: tune `THRESHOLD`; else stop and ask
  Nicholas for a trained model (openWakeWord notebook on Colab, or `heybuddy train` on a GPU machine: 25-72 GB of
  data, not doable on the phone or in a free CI run).
- **R2. Echo cancelling on this phone.** `VOICE_COMMUNICATION` + `AcousticEchoCanceler` outside a call may get no
  reference signal for TTS or ChatGPT's voice (a WebView). P2 measures it. With Audio device on Auto, Nicholas's
  replies often play on the watch, where no echo reaches the phone loudly anyway.
- **R3. ChatGPT's voice might put the phone in communication mode**, which `inCall()` would read as a call and pause
  the detector during speech. Checked after the switch (T4); the fix would be to count `CodexSpeech.active()` as
  Buddy's own mode, as `CodexLive.on()` is.
- **R4. Battery.** The microphone keeps the CPU awake all day; figures in research range from about 1%/h to far
  worse. If P5 shows more than 10 ms per hop, add an energy gate (skip inference while the room is quiet) as a
  follow-up; it is left out now because nothing measured says it is needed.
- **R5. Start after reboot may need a tap** (§4). Handled, not prevented.
- **R6. An always-on recorder in another app** (a software hotword, a call recorder) would keep the detector paused.
  `HOTWORD` clients are ignored; P3 looks for others; the notification names the reason.
- **R7. The two ONNX Runtime artifacts on the unit-test classpath** (Android AAR classes and the desktop jar, same
  version) could clash. If they do, keep `WakeFeaturesTest`, move the real-model check to the probe, and record it.
- **R8. No speaker check** (a non-goal): anyone in earshot, or a video saying "hey buddy", can start a turn. The wake
  chat has the default approvals, so risky actions still show a card.
- **R9. Size and memory.** APK about +10 MB; ONNX Runtime and three sessions stay loaded in Buddy's process.
- **R10. Android 11-13**: untested; a background start there gives silence (marked shortcut).

## Open questions
None that block. What is still unknown is on this phone and is settled by the probe (P1-P5) or by the run-time
fallbacks: detection quality, echo cancelling, whether the accessibility service may start the microphone service
after a reboot, and the battery figure.

## Questions for Nicholas
1. **Which chat should "hey buddy" talk to?** (a) One ongoing "Hey Buddy" chat, whatever chat is open (recommended: a
   request can never land in a chat that is mid-task or set to Bypass; it matches your "Continue the last chat" choice
   for Live voice). (b) Whichever chat is open, exactly like Talk. (c) The "Hey Buddy" chat, but a fresh one after 30
   minutes of quiet.
2. **Should it answer while the phone is locked?** (a) Yes (recommended: that is "always on, like Ok Google"; anyone
   in earshot can ask, and approval cards still need your unlock). (b) Only while unlocked.
3. **Where is the answer spoken?** (a) Wherever your Audio device setting sends read-aloud today: headset, else
   watch, else phone speaker (recommended: no new rule). (b) Always the phone's speaker for "hey buddy" answers.
4. **One-breath requests with Voice input set to Phone** (you use Claude, where it works). (a) Leave it: with Phone,
   pause after "hey buddy" (recommended: the Android 13+ audio hand-off is unproven on Samsung). (b) Build and probe
   that hand-off now too.
