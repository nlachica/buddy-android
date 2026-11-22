# Decisions: 032-hey-buddy-wake-word
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-21 - Wake model: the pretrained hey-buddy.onnx, no training
- Options: train with openWakeWord's notebook (GPU, Nicholas); train in a GitHub Actions CPU job; use an existing model.
- Choice: `models/hey-buddy.onnx` from `huggingface.co/benjamin-paine/hey-buddy` (revision 68a13d58, sha256 dfeb1222…;
  Apache-2.0 per the code repo's README, CC BY 4.0 per the model card: attributed), because it exists, is licensed for
  any use, and needs no GPU. Research had found none; `fwartner/home-assistant-wakewords-collection` has no hey_buddy.
  Its two feature models are byte-identical to openWakeWord v0.5.1's ONNX files. Fallback if the phone probe fails:
  ask Nicholas for a trained model.
- By: Planner (claude-fable-5-2)

## 2026-11-21 - Runtime: ONNX Runtime Android 1.22.0, not LiteRT (overrules research)
- Options: LiteRT + classifier converted with onnx2tf; ONNX Runtime latest (1.32.0); ONNX Runtime 1.22.0.
- Choice: ONNX Runtime 1.22.0, because the models are ONNX and were trained against these exact ONNX feature models,
  and a conversion needs TensorFlow (not on the phone's Alpine; a dispatch-only CI job can't run before it is on main).
  1.22.0 over 1.32.0 for size: 1.32.0's arm64 library is 36.3 MB raw / 13.7 MB zipped (measured), 1.22.0 about half.
  Cost accepted: APK about 7.5 → 17 MB.
- By: Planner (claude-fable-5-2)

## 2026-11-21 - Feature pipeline follows hey-buddy's Python, not openWakeWord's streaming
- Options: openWakeWord's 80 ms / 16-uniform-embeddings scheme (the Kotlin ports); hey-buddy's own scheme.
- Choice: hey-buddy's (`embeddings.py`): every 120 ms, the last 1.08 s → mel → 4 embeddings; the last 4 groups → the
  classifier. It is what the model was trained on. Input scale: int16 range per the Python; the CI test on real clips
  decides if the JS's -1..1 is right instead.
- By: Planner (claude-fable-5-2)

## 2026-11-21 - One-breath request: the detector's recorder feeds MicServer, pre-roll first
- Options: release the mic and call Voice.listen (loses words); Android 13+ EXTRA_AUDIO_SOURCE into the phone's
  recognizer; hand the detector's audio to the PCM path Claude's and ChatGPT's dictation already read (MicServer).
- Choice: MicServer, with a 1.5 s pre-roll that includes the wake phrase, stripped from the transcript afterwards. No
  second AudioRecord, no gap, and it is the path Nicholas uses (`voice_input = claude`). With `voice_input = phone` it
  falls back to Voice.listen ("hey buddy", pause, request); EXTRA_AUDIO_SOURCE is left as a question for Nicholas.
- By: Planner (claude-fable-5-2)

## 2026-11-21 - Reboot/update: try to start, fall back to a tap
- Options: assume the accessibility service may start a microphone FGS; always require a tap; try and handle refusal.
- Choice: try (`BuddyService.onServiceConnected`), catch ForegroundServiceStartNotAllowedException/SecurityException,
  then post "Tap to turn on Hey Buddy" (a notification tap is an exemption); opening Buddy starts it too. The
  after-switch reboot check records which path One UI 8 takes.
- By: Planner (claude-fable-5-2)

## 2026-11-21 - Pausing: any recording that isn't the detector's own
- Options: hooks in each of Buddy's voice paths + AudioRecordingCallback.isClientSilenced; one rule on
  AudioManager's active recording configurations.
- Choice: the one rule (minus our session and HOTWORD clients), plus AutoPause's call test and one synchronous hook in
  Voice.listen. Decided by a pure function `WakeWord.state`, which is what the unit tests cover.
- By: Planner (claude-fable-5-2)

## 2026-11-21 - Barge-in: the wake word itself cuts speech; echo-cancelled mic only while Buddy speaks
- Options: streaming STT during speech to hear "stop"; cut on the wake word, then treat the rest as a turn.
- Choice: cut on the wake word (`Voice.stopSpeaking()` first), swallow the request when it is a stop command. While
  the phone speaks the recorder is VOICE_COMMUNICATION + AcousticEchoCanceler with setPrivacySensitive(false)
  (CodexLive's pattern); VOICE_RECOGNITION otherwise.
- By: Planner (claude-fable-5-2)

## 2026-11-21 - Wake turns use one dedicated "Hey Buddy" chat and force the reply to be spoken
- Options: the chat that is open (as Talk); a dedicated ongoing chat; a fresh chat after a quiet gap.
- Choice: a dedicated chat remembered in Prefs, not made current, because a request heard across the room must not
  land in a running or Bypass chat; `Session.spokenRun` speaks the reply with read_aloud off. Asked of Nicholas (Q1).
- By: Planner (claude-fable-5-2)

## 2026-11-21 - Quick toggle: bubble hold menu + "Turn off" on the notification; no Quick Settings tile
- Options: hold menu; QS tile; both.
- Choice: hold menu (Buddy's own quick-action surface, one Action) and the notification action that comes for free. A
  tile is a new exported service the user must add by hand.
- By: Planner (claude-fable-5-2)

## 2026-11-21 - Flavors: github only
- Options: both flavors; github only.
- Choice: github only (engine and models in `src/wakeword`, loaded by name as the watch link is), so the F-Droid APK
  gains no native library or model and its reproducibility check is untouched, and `build.sh` still compiles.
- By: Planner (claude-fable-5-2)

## 2026-11-21 - Wake turns go to Talk's chat (replaces "one dedicated Hey Buddy chat")
- Options: a dedicated chat (round 0); the chat Talk uses.
- Choice: Talk's (`sessions.current`, a new chat only if none), because the brief's done-when says "same chat as
  Buddy's voice/Talk path" (plan review F2). Consequence accepted and documented: the open chat's approvals apply,
  Bypass included; asked of Nicholas (Q2). `Prefs.wakeChat` is dropped; `Session.spokenRun` stays.
- By: Planner (claude-fable-5-2), on Plan Reviewer F2

## 2026-11-21 - One hand-off seam for all three inputs; Phone is fed through EXTRA_AUDIO_SOURCE
- Options: Phone input pause-only (round 0); a pipe for Phone beside MicServer for the others; MicServer as the one
  seam, pumped into a pipe for Phone; transcribe Phone users' wake turns with Claude.
- Choice: MicServer as the one seam. The detector's audio (pre-roll first) goes into MicServer for every input;
  for Phone, `Voice.listen` pumps it into the recognizer's `EXTRA_AUDIO_SOURCE` pipe. Because Phone is the default
  input and a one-breath request must not lose words there (F1). Unproven on this phone's recognizer
  (`com.google.android.tts`): probe gate P6, then the on-device recognizer, else Phone is pause-only and that is
  Nicholas's decision (Q1). A recognizer that opens the microphone itself is noticed at run time (one cancelled
  turn, then unfed turns until Buddy restarts).
- By: Planner (claude-fable-5-2), on Plan Reviewer F1

## 2026-11-21 - The wake word is offered on Android 14+ only
- Options: all of minSdk 30+; a visible-start rule for Android 11-13; Android 14+ only.
- Choice: Android 14+ (`WakeWord.available()`), because below it a background start of the microphone service gives
  silence without an error, there is no device to verify a workaround on, and Nicholas's phone is Android 16. It
  also guarantees API 33's `EXTRA_AUDIO_SOURCE`. Below 14: no switch, `hey_buddy` refuses "on".
- By: Planner (claude-fable-5-2), on Plan Reviewer F6

## 2026-11-21 - A wake turn is owned by a turn id, reserved on the main thread; calls and recorders beat it
- Options: adopt MicServer on the capture thread at detection (round 0); hold the audio in the capture loop and
  reserve on the main thread.
- Choice: the capture loop (`WakeLoop`, pure) only holds audio; the main thread reserves `MicServer.adopt(turn)` and
  calls `Voice.listen` in one go, so a tap can't interleave; `feed`/`release`/the 2-minute cut act only for the
  owner. In `WakeWord.state`, OFF, a call and a foreign recording rank above TURN and cancel it; late transcripts
  are dropped by the turn-id check (F3, F4).
- By: Planner (claude-fable-5-2), on Plan Reviewer F3/F4

## 2026-11-21 - "hey buddy" alone: one extra listening window; stop words only count when speech was cut
- Options: end the turn when the first result is only the wake phrase (round 0); hold the first window open by
  ignoring the pre-roll's loudness; one more window in the same turn.
- Choice: one more window (pure `WakeWord.Turn`: AGAIN once, then END), started from the audio held since the first
  window stopped, because Android's recognizer ends on its own and the same rule then serves all inputs (F7). A stop
  command is swallowed only if the wake cut speech (speaking then, or up to 2 s before); otherwise it is sent (F8).
- By: Planner (claude-fable-5-2), on Plan Reviewer F7/F8

## 2026-11-21 - Service start on every Buddy screen's resume; service declared in the github-only manifest
- Options: hooks in each activity's permission callback; one `ActivityLifecycleCallbacks` in `BuddyApp`. Main
  manifest; the github overlay manifest.
- Choice: the one callback (a permission dialog closing resumes the asker, F5), and the overlay
  `app/src/watchlink/AndroidManifest.xml` for the service and both foreground-service permissions, checked in CI on
  the merged manifests (F10).
- By: Planner (claude-fable-5-2), on Plan Reviewer F5/F10
