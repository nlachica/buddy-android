# Plan: 032-hey-buddy-wake-word
<!-- Planner, 2026-11-21 (claude-fable-5-2). Round 1: answers plan-review.md round 1 (F1-F10). -->

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
the wake word in the F-Droid flavor, in `build.sh` builds and on Android 13 or older; using a headset's microphone
for the wake word; stopping a running task by voice (only speech is cut); a chat of its own for wake turns.

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
  1.5 s as the pre-roll, and `reset()` empties it.
- `OnnxWakeEngine` (`src/wakeword/java`, github flavor only): the three `OrtSession`s (one intra-op thread each), no
  `android.*` import, models passed in as `byte[]`.

### 3. Flavors, versions and builds
Github flavor only, by the watch link's pattern: `sourceSets["github"]` gets `java.srcDir("src/wakeword/java")` and
`assets.srcDir("src/wakeword/assets")`, the dependency is `"githubImplementation"`, and `src/main` loads the engine by
name (`WatchVoice.load`'s reflection). `WakeWord.available()` is true only when the engine loads **and the phone runs
Android 14 or newer** (API 34). Otherwise (F-Droid flavor, `build.sh` builds, Android 11-13): no switch, no menu item,
`hey_buddy` refuses "on" with the reason.
- Why github only: the F-Droid APK then carries neither the native library nor the models, so `build.yml`'s
  reproducibility and no-Google-code steps see an APK that differs only by plain Java, and `build.sh` (javac, no
  Maven) keeps compiling. `defaultConfig.ndk.abiFilters += "arm64-v8a"` keeps ORT's three other ABIs out.
- Why Android 14+: below it, a microphone service started from the background gets silence instead of an exception,
  so Buddy could show "listening" while hearing nothing, and there is no device to verify a workaround on (Nicholas's
  phone is Android 16). From 14 the refusal is an exception Buddy can handle (§4). It also means
  `RecognizerIntent.EXTRA_AUDIO_SOURCE` (API 33) is always there (§6).
- The service and its two permissions (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`) are declared in the
  github-only manifest, `app/src/watchlink/AndroidManifest.xml` (its comment is widened: "what only the github build
  has"). The F-Droid and `build.sh` manifests gain nothing. `WakeService.java` itself is in `src/main` (plain
  Android, no dependency), never started where it is not declared.

### 4. Service, start rules, reboot
- `WakeService` (not exported, `foregroundServiceType="microphone"`) owns the `AudioRecord`, the capture thread and
  the notification.
- `WakeWord.ensure(ctx)` (main thread, idempotent, cheap) is the one entry: it starts the service when `available()`,
  the setting is on, the microphone is granted and `BuddyService` is connected; otherwise it stops it. Called from
  `BuddyService.onServiceConnected` (boot after first unlock, app update, a11y rebind), `onUnbind`/`onDestroy` (stop),
  the Settings switch, `buddy_settings`, the hold-menu toggle, the notification actions, and **every Buddy activity's
  `onResume`**: `BuddyApp.onCreate` registers one `ActivityLifecycleCallbacks`. A permission dialog closing resumes
  the activity that asked, so a microphone grant given from Settings, a chat or the Terminal starts the service
  without a visit to MainActivity. `ensure` only starts the service; the state function (§5) keeps the microphone
  closed while Talk, dictation or Live has it.
- **Reboot and update are settled at run time, not assumed.** Android refuses a microphone foreground service from
  the background. Whether Buddy's accessibility-service process counts as background is undocumented (research). The
  system binds accessibility services with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`, one of the while-in-use
  exemptions in AOSP's `ActiveServices` (planner's reading of the source, unverified on One UI 8). So the code tries
  and handles the refusal.
- **One recovery for every way the microphone can fail**: `startForegroundService` or `startForeground(id, n,
  FOREGROUND_SERVICE_TYPE_MICROPHONE)` throwing `ForegroundServiceStartNotAllowedException` or `SecurityException`;
  an `AudioRecord` that is not `STATE_INITIALIZED`; `startRecording()` throwing or not reaching `RECORDSTATE_RECORDING`;
  `read()` returning an error. Each stops the recorder and the service, logs the cause (`Log.i("BuddyWake", …)`) and
  posts one quiet notification, **"Tap to turn on Hey Buddy"**, whose content intent is
  `PendingIntent.getForegroundService(WakeService)` (a notification tap is a documented exemption). Opening any Buddy
  screen retries too (`ensure`). No retry loop of its own.
- `START_STICKY`; a restart by the system goes through the same handling.
- Notification (channel `wake`, `IMPORTANCE_LOW`, ongoing): "Hey Buddy is listening" or "Hey Buddy is paused: <call |
  the microphone is in use>", action **Turn off** (sets the setting off), tap opens Settings → Voice.
- Setting off: no service, no notification, no tap prompt, also after a reboot.

### 5. States: one pure function decides
`WakeWord.state(Inputs)` → `OFF | PAUSED | TURN | SPEAKING | IDLE`, evaluated on the main thread every 500 ms, on
every `AudioManager.AudioRecordingCallback` event and at the hooks below. The service applies the result. **The first
row that matches wins**:

| # | State | When | Microphone |
|---|---|---|---|
| 1 | OFF | setting off, no mic permission, not `available()` | closed, service stopped |
| 2 | PAUSED | a call (`AutoPause`'s rule: audio mode not normal and not Buddy's own, or a call notification) | closed |
| 3 | PAUSED | a **foreign recording** while the detector's recorder is open: an active recording configuration that is not the detector's session, not a `HOTWORD`-source client and not one `MicServer` is serving to Buddy's Linux | closed |
| 4 | TURN | a wake turn is on (§6) | fed turn: stays open, detection off, not reopened. Unfed turn: closed |
| 5 | PAUSED | Buddy's own voice path: `Voice.listening()`, `CodexLive.on()`, `MicServer` serving the Terminal; or any other recording while the detector's recorder is closed | closed |
| 6 | SPEAKING | `Voice.phoneSpeaking()` | `VOICE_COMMUNICATION` + `AcousticEchoCanceler` (CodexLive's pattern, `CodexLive.java:838-848`), `setPrivacySensitive(false)` |
| 7 | IDLE | otherwise | `VOICE_RECOGNITION` (not privacy-sensitive, as `MicServer`) |

- **Rows 1-3 beat a turn.** When the state becomes OFF or PAUSED while a turn is on, `WakeWord.cancelTurn()` runs:
  the turn id is cleared, `Voice.stopListening()` cancels the recognizer, `MicServer.release(turn)` drops the adopted
  audio, the capture loop drops what it holds and the recorder closes. Every callback of a wake turn checks its turn
  id first, so a transcript that arrives late is ignored and nothing is sent. Claude's and ChatGPT's dictation read
  `MicServer` and open no recorder of their own, and a recognizer that takes supplied audio opens none either, so in
  a fed turn a foreign recording really is someone else (or the case in §6 "Phone").
- Leaving PAUSED waits until the cause has been gone for 1 s, so a recorder that restarts doesn't make it flap. The
  recorder then opens with `WakeFeatures.reset()`: nothing heard before the pause can trigger it.
- `AutoPause.callNow()` becomes package-visible (`inCall()`), unchanged otherwise.
- One synchronous hook: `Voice.listen` calls `WakeWord.release()` right after its own `stopListening()` (which ends
  an earlier wake turn): it closes the detector's microphone at once unless `MicServer` is reserved for a wake turn
  starting now. Android's recognizer runs in another app and must not start against an open microphone.
- IDLE ↔ SPEAKING reopens the `AudioRecord` (~0.1-0.2 s deaf at the start and end of speech).
- The capture thread reads 40 ms blocks and runs the pipeline inline every third block. No wake lock of our own:
  Android holds one for an active recording (checked in the probe with the screen off).

### 6. A wake turn, and the one-breath request
**The capture thread touches no shared state.** Its logic is a pure class, `WakeLoop` (`src/main`, no `android.*`),
with three modes:
- DETECT: blocks go to `WakeFeatures`; nothing leaves the loop. On a detection → HOLD, and it announces
  `onWake(seq)` to the main thread.
- HOLD: it keeps recording into its own list, starting with the pre-roll (the last 1.5 s, wake phrase included). At
  most 10 s; then it drops the audio and returns to DETECT.
- FEED(turn): what it holds, then every new block, goes to `MicServer.feed(turn, chunk)`. When that returns false
  (the recognizer stopped the capture) → HOLD again, so nothing said while a transcript is awaited is lost. A feed
  longer than 2 minutes asks the main thread to cancel that turn.
The main thread moves it with `feedTo(turn)` (HOLD → FEED) and `drop()` (→ DETECT, buffers cleared).

**`MicServer` gets an owner.** New: `adopt(turn)` (clears the buffer, marks it recording, `owner = turn`),
`feed(turn, chunk)` (appends and calls `levels` only while `owner == turn` and recording; else false),
`release(turn)` (`endCapture()` only if `owner == turn`), `adopted()`. `startCapture()` returns true at once when
there is an owner (it keeps the adopted audio and opens no second `AudioRecord`); `endCapture()` clears the owner.
Nothing else changes: Claude's socket stream and ChatGPT's `pull` read the buffer as they do today, which already
means "everything from the start".

**`WakeWord.onWake(seq)`, on the main thread** (where `Voice` and the bubble live, so no tap can come between its
lines):
1. If the state is no longer IDLE or SPEAKING, or Buddy is already listening, or a Live call is on: `loop.drop()`,
   done.
2. `cutSpeech` = Buddy is speaking now or stopped less than 2 s ago; `Voice.stopSpeaking()` (the barge-in: speech
   stops on the wake word itself, well inside 1 s); a short vibration.
3. A new turn: the next turn id and a `WakeWord.Turn(turn, cutSpeech)`.
4. Open a window: if the input can be fed (`Voice.takesFeed(c)`: Claude with a supported language, ChatGPT, or
   Phone unless it refused, below): `MicServer.adopt(turn)`, `loop.feedTo(turn)`. Otherwise `loop.drop()`; the turn
   is unfed and `Voice.listen`'s hook closes the microphone.
5. `bubble.talk(turn)`: Talk's code with a turn (waves, the "Listening…" pill, `Voice.listen`, end of speech; UI
   calls are skipped when the bubble isn't showing). If `Voice.listen` returns an error (signed out, no recognizer):
   toast as Talk does and `endTurn(turn)`.
6. The window ends in the listener, each callback first checking `turn` is still the current one:
   - `onFinal(text)` or `onEnd(message)` with a message (nothing caught, or an error) → `Turn.result(text or null)`:
     - `SEND(req)`: `endTurn`, then the request goes to the chat (§7).
     - `AGAIN`: **one more window in the same turn**: step 4 and 5 again. The loop is in HOLD since the first
       window's capture stopped, so the second window starts with what was said meanwhile.
     - `END`: `endTurn`.
   - `onEnd(null)` (cancelled: a tap, `cancelTurn`) → `endTurn`.
   `endTurn(turn)`: only if it is the current turn: clear it, `MicServer.release(turn)`, `loop.drop()`.
   Both dictation engines end their capture before they deliver a result (`ClaudeDictation.java:152-156`,
   `CodexDictation.java:124-128`), so adopting again inside the callback is safe; the fed Phone branch keeps that order.

**`WakeWord.Turn`** (pure, unit-tested): `result(text)`: `req = strip(text)`; `req` empty (only the wake phrase, or
nothing) → `AGAIN` the first time, `END` the second; `cutSpeech && isStop(req)` → `END` (speech is already cut;
nothing is sent); otherwise `SEND(req)`. So "hey buddy", a pause, then the request works whether the pause is short
(one window) or long enough for the recognizer to finish on "hey buddy" (the second window), and silence ends it:
at most two windows. A stop word said when nothing was playing is a request like any other.

Pure functions in `WakeWord`, unit-tested:
- `strip(text)`: removes everything up to and including the first wake phrase when it starts within the first 8
  words (hey/hay/hi/a/eh/okay + buddy/buddie/body, any punctuation between and after), or a bare leading "buddy";
  no match → the text unchanged (the detector fired, the recognizer dropped the phrase). Cutting "up to" also drops
  words of Buddy's own speech that leaked into the pre-roll.
- `isStop(req)`: the whole request is a stop command: stop, ok stop, stop talking, ok stop talking, be quiet, quiet,
  shut up, that's enough, never mind, cancel (case, punctuation and a leading ok/okay/please ignored).

**Phone (Android's recognizer, the default input).** In `Voice.listen`'s phone branch, when `MicServer.adopted()`:
- a pipe (`ParcelFileDescriptor.createPipe()`); its read end goes into the intent as `EXTRA_AUDIO_SOURCE`, with
  `EXTRA_AUDIO_SOURCE_SAMPLING_RATE` 16000, `_CHANNEL_COUNT` 1, `_ENCODING` `ENCODING_PCM_16BIT`;
- `MicServer.startCapture()` (keeps the adopted audio) and a small pump thread: `MicServer.pull()` → the write end,
  until the capture has stopped and is drained, then it closes the pipe (the capture thread never blocks on it);
- end of speech as for Claude and ChatGPT: `MicServer.levels` → `Voice.EndOfSpeech` → `finishListening()`, which
  for a fed recognizer also calls `MicServer.stopCapture()`; the recognizer's own `onResults` may come first;
- `finish()` also does `MicServer.endCapture()` for a fed recognizer.
The words said while the recognizer starts are in the pipe, so nothing is lost. What the plan cannot know from
documentation: whether this phone's recognizer (`com.google.android.tts/…GoogleTTSRecognitionService`, Android 16)
honours the extra. **Probe gate P6** decides before step 7 is written. The docs say a recognizer without support
"will open the mic" itself. That case is handled at run time:
- a foreign recording during a fed Phone turn (row 3) cancels that turn and sets `Voice.phoneFeedRefused` (in
  memory, until Buddy restarts);
- from then on Phone turns are unfed: the detector lets go, `Voice.listen` runs as Talk does. "hey buddy", a short
  pause, the request works; words in the same breath can be lost. The guide says so.
If P6 fails with the default recognizer, the probe tries `SpeechRecognizer.createOnDeviceSpeechRecognizer` (the
phone has `com.google.android.as`); if that takes the audio, fed Phone turns use it (recorded in `decisions.md`).
If neither does, the pipe code is not shipped, Phone is pause-only, and that is Nicholas's call (Q1) before T2 can
pass.

### 7. Which chat, and speaking the reply
- **Chat: exactly Talk's.** The request goes to `sessions.current`; if there is none, a new chat with the default
  agent is created and selected (`Bubble.java:1650-1657`), then `hub.send`. So a wake turn after a Talk turn, or after
  another wake turn, lands in the same chat and has its context. What follows from that, as for Talk and typing: a
  request to a chat that is working is added to that task as an instruction, and the chat's approvals mode applies,
  Bypass included (R8, Q2).
- **Speaking:** `read_aloud` is off for Nicholas, so a wake turn sets a transient `Session.spokenRun`; `ChatHub.add`
  speaks when `Prefs.speakReplies(app) || ses.spokenRun` (the other conditions unchanged) and the flag is cleared
  where `scheduledRun`/`quietRun` are, at the run's end. Speech goes through `Voice.speak` as today, so it follows
  Voice output and Audio device (Q4).

### 8. Settings and the quick toggle
- `Prefs.wakeWord` (key `wake_word`, default **true**).
- Settings → Voice: a `switchRow` "Hey Buddy" with a two-line note (always listening on this phone, nothing leaves it
  before the wake word, uses more battery, paused in calls and while another app records). Turning it on without the
  microphone permission asks for it (`Voice.askMic`); the grant starts it (§4); while it is on without the
  permission the note says "Needs the microphone".
- `buddy_settings`: `hey_buddy` (on, off) in `SettingsTool.scalars()`; its setter calls `WakeWord.ensure`; "on" is
  refused where `available()` is false.
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
- A separate sink per recognizer (a pipe for Phone, `MicServer` for the others): two hand-offs to keep correct;
  `MicServer` as the one seam serves all three.
- For Phone input, quietly transcribe wake turns with Claude instead: sends audio to a service the user didn't choose.
- A dedicated "Hey Buddy" chat (round 0): the brief says the same chat as Talk.
- A second, always-on streaming STT for stop words during speech: a second voice stack; cutting on the wake word meets
  "within ~1 s".
- Detector on `VOICE_COMMUNICATION` all the time: privacy-sensitive by default and processed for calls; kept to the
  seconds Buddy speaks.
- Android 11-13 with a "start only from a visible screen" rule: unverifiable here; not offered instead.

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
   SPEAKING microphone setups while it plays a spoken paragraph, the list of active recording configurations, the
   time per hop, and a button that feeds a recorded "hey buddy what's the weather like" through a pipe into the
   recognizer (P6). Results go to `docs/features/032-hey-buddy-wake-word/probe.md`. Gates P1-P6 are in the Test plan.
   If P1 fails, stop and tell Nicholas (R1). If P6 fails, see §6 "Phone". If the probe can't be built, say so in
   `decisions.md`; its checks then run after the switch and are fixed forward.
3. **Pure logic.** `main/java/.../WakeWord.java` (`strip`, `isStop`, `state`, `Turn`, `available`);
   `test/.../WakeWordTest.java`.
4. **The hand-off seam.** `main/java/.../WakeLoop.java`; `MicServer.java` (`adopt`, `feed`, `release`, `adopted`, the
   owner in `startCapture`/`endCapture`); tests `test/.../WakeLoopTest.java` (fake engine: modes, the 10 s hold, the
   second window), `test/.../MicServerTest.java` (ownership) and `testGithub/.../WakeIdleTest.java` (real models:
   nothing reaches `MicServer` before a wake; the pre-roll after one).
5. **Setting.** `Prefs.java`, `SettingsTool.java`, `SetupActivity.java` (`voiceSection`), `res/values/strings.xml`
   (English only, as CONTRIBUTING says), a case in `SettingsCoverageTest.java`.
6. **Service.** `watchlink/AndroidManifest.xml` (service, two permissions); `main/java/.../WakeService.java`;
   `WakeWord.ensure/release/cancelTurn`; `BuddyApp.java` (activity callback); `BuddyService.java` (connect,
   unbind/destroy); `HiddenNotice.java` (Turn off); `AutoPause.java` (`inCall()` visible);
   `.github/workflows/build.yml` (the manifest and asset check of T9). After this step Buddy detects and logs, and
   pauses and resumes; nothing is sent yet.
7. **Wake turn.** `Voice.java` (`takesFeed`, the `release()` hook, the fed phone recognizer if P6 allows);
   `Bubble.java` (`talk(turn)`); `Sessions.java` (`spokenRun`); `ChatHub.java` (speak condition, reset);
   `WakeWord.onWake/endTurn`.
8. **Quick toggle.** `Bubble.java` (`openMenu`), `res/drawable/ms_mic_off.xml`, strings.
9. **Docs.** `docs/GUIDE.md` (Voice: a "Hey Buddy" entry: Android 14+, the battery cost, it talks to the open chat
   with that chat's approvals, the Phone-input limit if any; the "there's no wake word" sentence), `docs/PRIVACY.md`
   (Voice and the Microphone permission line; the two new permissions), `README.md`, `AssistActivity.java`'s comment,
   `docs/features/032-hey-buddy-wake-word/battery.md` (figure, method, what drives it).

## Test plan
Where each check runs: **CI** (`build.yml` on the branch), **probe** (step 2, before release), **after-switch** (the
released fork on the phone; rows that need it stay `pending` in `verify.md` until then).

Probe gates: **P1** 10 "hey buddy" at arm's length in a quiet room, ≥ 8 detected; 30 min of TV or podcast speech,
≤ 2 false detections. **P2** while the phone speaks a paragraph at media volume 60%, 10 "hey buddy": detections per
microphone setup; Buddy's own speech must not trigger it; the SPEAKING setup must reach ≥ 7/10, else the setup is
revisited before step 6. **P3** the recording configurations at idle show no always-on recorder that would pause it
for good. **P4** `dumpsys batterystats --reset`, 1 h unplugged with the screen off and the probe listening, then its
mAh; the same hour without. **P5** ms per hop. **P6** the recorded sentence fed through the pipe, the first 1.5 s
written before `startListening` and the rest in real time: pass = the transcript has "weather", the session ends
after the pipe closes, and no second recording configuration appears; for the default recognizer, then for the
on-device one if the default fails.

| # | Done-when | Proof |
|---|---|---|
| T1 | On-device engine, model in the APK, foreground service + notification, no audio out while idle | CI: `OnnxWakeEngineTest` runs the real models over the clips through `WakeFeatures`: both positive clips peak ≥ 0.5; the sound-alike clip, silence and white noise stay < 0.5; `SHA256SUMS` matches the assets. CI: `WakeIdleTest` runs `WakeLoop` with the real models over the sound-alike clip, noise and silence: `MicServer` holds nothing (`pull` is empty, `adopted()` false) and no wake was announced; over a positive clip, after `adopt` + `feedTo`: the first audio out of `MicServer` is the pre-roll, ≤ 1.5 s (48000 bytes), then the rest in order. P1. After-switch: the notification shows; airplane mode on, detection still works; with Wi-Fi on and `voice_input` = claude, 2 min of TV without the phrase: no `turn` line in `logcat -s BuddyWake` (each adopt logs one). |
| T2 | One breath sends the request without the phrase; alone starts listening | CI: `WakeWordTest` strip table ("Hey buddy, what's the weather like" → "what's the weather like"; "hey body what…", "…anyway hey buddy what…", "buddy, what…", "hey buddy" → "", no phrase → unchanged). CI: `Turn` table: "hey buddy" → AGAIN, then "what's the weather" → SEND; "hey buddy" → AGAIN, then nothing → END; "hey buddy" twice → END. CI: `WakeLoopTest`: audio arriving between two windows is the start of the second. P6. After-switch, **with `voice_input` = claude and again with `voice_input` = phone** (set through `buddy_settings`, put back after): "hey buddy what's the weather like" in one breath, 5 times each: the chat shows the request without the phrase and the reply is spoken; "hey buddy", a 3 s pause, the request: same, without a second "hey buddy". Phone passes only through the fed path, unless Nicholas's answer to Q1 is in the brief's Clarifications. |
| T3 | Follow-up goes to the same chat as Talk | After-switch: in an open chat, a Talk turn ("remember the word pineapple"), then "hey buddy, what word did I ask you to remember": the answer lands in that chat and has the word; then "hey buddy, what about tomorrow" after a weather question: same chat. CI: none (it is Talk's own lookup). |
| T4 | "hey buddy, stop" cuts speech ≤ ~1 s; AEC during playback | CI: `isStop` table (stop, ok stop talking, be quiet → true; "stop the timer", "what's the weather" → false); `Turn` with the same transcript "stop": `cutSpeech` → END, not `cutSpeech` → SEND. P2. After-switch: stopwatch from the end of "buddy" to silence, 5 tries, each ≤ 1 s; "stop" is not sent to the chat. |
| T5 | Pauses for calls and other recorders, resumes; Buddy's own paths don't fight | CI: `WakeWordTest` state table: every row of §5, including a call, a foreign recording and the switch going off **during a turn** (→ PAUSED/OFF, not TURN), Buddy's own listening during a turn (→ TURN), and the 1 s resume delay. CI: `MicServerTest`: `adopt(1)`, `feed(1,a)`, a manual capture's `endCapture()`, then `feed(1,b)` is refused and `release(1)` leaves the newer capture alone; `adopt(1)`, `release(1)` (an immediate rejection), then `adopt(2)` + `feed(2,…)` works. After-switch, with `logcat -s BuddyWake`: a phone call → PAUSED, back to IDLE after it; Samsung Voice Recorder records a clean 20 s memo while the notification says paused, then IDLE; **start the recorder (or take a call) 2 s into a wake turn**: the pill goes, nothing is sent, and after it "hey buddy" works; tap Talk during a wake turn: Talk's words are sent once, and the next wake works; tap Talk, chat dictation, a Live call: each works as before and the detector returns. P3. |
| T6 | On by default; Settings switch; quick toggle; survives restart and reboot | CI: default true; `state` is OFF with the setting off (tests). After-switch: fresh update → listening without touching settings (or the tap notification); the switch and the hold-menu item stop and start it and stay in step; kill Buddy → back; reboot → the `BuddyWake` log says whether the accessibility start was allowed; if not, one tap on the notification turns it on **and a spoken "hey buddy" is then detected**; switch off, reboot → no service, no notification, no tap prompt; microphone permission taken away in Android's settings, then the switch turned on in Settings → Voice and the permission granted there → "hey buddy" works without opening Buddy's home screen. The outcome goes into `verify.md` and the guide. |
| T7 | `buddy_settings` reads and changes it | CI: `SettingsCoverageTest` case (`hey_buddy` found; accepts on/off; refuses others). After-switch: `buddy_settings hey_buddy` reads, and changes with a card. |
| T8 | Battery in the docs and the folder | P4, P5. Both, the method and the drivers (CPU kept awake by the open microphone; inference 8 times a second) go into `battery.md` and the guide. If the hour can't be had before release, the guide carries the sourced range from research, marked as an estimate, and the measured figure replaces it after the switch. |
| T9 | Unit tests and green CI | CI: `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease` green, including "Check the F-Droid APK has no Google code" and "Check the F-Droid APK is reproducible". `assembleFdroidRelease` has no ONNX Runtime on its classpath, so it also proves `src/main` compiles without it, which is what `build.sh` needs. A new `build.yml` step, next to the Google-code check: the F-Droid APK has no `libonnxruntime.so`, no `assets/wakeword/`, no `FOREGROUND_SERVICE` permission and no `WakeService` (`unzip -l`, and `aapt2 dump permissions` / `dump xmltree` from the SDK's build-tools); the GitHub APK has the three models, `lib/arm64-v8a/libonnxruntime.so` for arm64 only, both permissions and the service. |

## Risks
- **R1. The pretrained model may not suit Nicholas's voice or rooms.** It is trained on synthetic voices; nobody has
  measured it here. P1 decides before anything ships. If it fails: tune `THRESHOLD`; else stop and tell Nicholas.
  The way out then is a trained model, which is feature-sized: `heybuddy train` or openWakeWord's notebook on a GPU
  (25-72 GB of data), or a `workflow_dispatch` CPU job on a reduced dataset, which is unproven (runner disk and the
  6-hour limit) and can only run once its workflow file is on `main`.
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
  version) could clash. If they do, keep the fake-engine tests, move the real-model checks (T1) to the probe, and
  record it.
- **R8. No speaker check, and the open chat's approvals apply.** Anyone in earshot, or a video saying "hey buddy",
  can start a turn in whichever chat is open. In a chat with default approvals risky actions still show a card; in a
  chat set to Bypass they don't. This follows from the brief (same chat as Talk); the guide says it, and Q2 asks.
- **R9. Size and memory.** APK about +10 MB; ONNX Runtime and three sessions stay loaded in Buddy's process.
- **R10. Android's recognizer may not take supplied audio** (§6 "Phone"). P6 decides for this phone; elsewhere the
  run-time fallback costs one cancelled turn per Buddy start, then pause-only.
- **R11. Claude Code's microphone test** when its hidden session starts may open a recorder through `MicServer`'s
  socket during a wake turn. Row 3 leaves `MicServer`-served recordings out for that reason; checked in T2 with a
  cold Claude session (first wake after Buddy starts).

## Open questions
None that block. What is still unknown is on this phone and is settled by the probe (P1-P6) or by the run-time
fallbacks: detection quality, echo cancelling, whether Android's recognizer takes supplied audio, whether the
accessibility service may start the microphone service after a reboot, and the battery figure.

## Questions for Nicholas
1. **Only if the probe shows Android's own recognizer can't take Buddy's audio: what should "hey buddy" do when
   Voice input is set to Phone?** (You use Claude, where one breath works.) (a) Accept "hey buddy", a short pause,
   then the request, for Phone input only, and say so in the guide (recommended: the other ways are worse). (b) Use
   Claude's dictation for wake turns when Phone can't, which sends that audio to Anthropic. (c) Hold the feature
   until Phone works in one breath.
2. **"Hey buddy" talks to whichever chat is open, like Talk. If that chat is set to Bypass, a request heard from
   anyone nearby runs without approval cards.** (a) Leave it, and warn in the guide (recommended: it is what the
   brief asks, and you choose Bypass per chat). (b) In a Bypass chat, a wake request shows one confirm card first.
3. **Should it answer while the phone is locked?** (a) Yes (recommended: that is "always on, like Ok Google"; anyone
   in earshot can ask, and approval cards still need your unlock). (b) Only while unlocked.
4. **Where is the answer spoken?** (a) Wherever your Audio device setting sends read-aloud today: headset, else
   watch, else phone speaker (recommended: no new rule). (b) Always the phone's speaker for "hey buddy" answers.
