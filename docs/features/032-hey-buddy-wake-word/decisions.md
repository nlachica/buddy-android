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

## 2026-11-22 - Held audio lasts the whole wait for a transcript; only "nothing heard" opens the second window
- Options: drop the hold after 10 s (round 1); keep it for the turn with a limit above every engine's own wait; no
  limit.
- Choice: keep it, limit 90 s (Claude's own waits add up to 80 s), and at the limit cancel the turn instead of going
  on with audio missing. Android's recognizer marks the end of input like the other engines (`onEndOfSpeech` →
  `MicServer.stopCapture()`). `Voice.Listener.onNothing` (default: `onEnd`) tells "nothing heard" from an error; an
  error ends the turn with its message, no retry. A type, not a comparison of message strings, so a reworded string
  can't change behaviour.
- By: Planner (claude-fable-5-2), on Plan Reviewer round 2 F1

## 2026-11-22 - Whether Phone turns are fed is decided by the probe and compiled in; nothing is learned at run time
- Options: downgrade to unfed after a foreign recording in a fed Phone turn (round 1); probe the recognizer at run
  time with a bundled clip; decide once from probe gate P6 (`Voice.PHONE_FEED`).
- Choice: P6, because recording configurations don't say who is recording, so an unrelated recorder would have
  downgraded every later turn, and a run-time probe is a second recognizer session nobody asked for. A foreign
  recording cancels one turn, no more. Cost accepted: on a recognizer that ignores supplied audio (not this phone's,
  if P6 passes) Phone-input turns are cancelled each time and the guide says to use Claude or ChatGPT. The unfed
  path exists only if P6 fails and Nicholas accepts pause-only (Q1); there the state function expects the
  recognizer's one recording and cancels on a second.
- By: Planner (claude-fable-5-2), on Plan Reviewer round 2 F2

## 2026-11-22 - A listening window has an identity: the recognizer instance and a MicServer capture id
- Options: check the turn id in the bubble's listener only (round 1); a window object per engine; the recognizer
  instance + a capture id per `adopt`/`startCapture`.
- Choice: the last. Phone callbacks check `recognizer != mine` before `finish()`; `MicServer.feed/release/pull` take
  the capture id and refuse a stale one without touching the buffer or cursor; the second window of a turn gets its
  own id. Fixes a late callback destroying a newer recognizer for manual Talk as well.
- By: Planner (claude-fable-5-2), on Plan Reviewer round 2 F3

## 2026-11-22 - MicServer's captured buffer goes to the socket only for Claude's dictation
- Options: leave `serve` as is; mark each capture with its consumer; gate on `ClaudeDictation.active()`.
- Choice: the gate, one condition, no new state. A Terminal recording during a Phone or ChatGPT turn (or a manual
  ChatGPT dictation) opens its own recorder and never sees the pre-roll; the turn goes on beside it. Not solved, as
  today: Claude's hidden session and a Terminal `/voice` during Claude dictation use the same program and token.
- By: Planner (claude-fable-5-2), on Plan Reviewer round 2 F4

## 2026-11-22 - The probe comes after the shared capture code, and P1, P2, P6 block
- Options: probe as step 2 with copies and an "after the release" way out (round 1); probe after `WakeLoop`/`WakeMic`
  exist, compiled from the same files, gates blocking.
- Choice: the second. The service and the wake turn are written only with P1 (model), P2 (microphone while
  speaking) and P6 (Phone feed) measured; a gate that can't be measured stops the pipeline for Nicholas. Fallback
  build route: a temporary Gradle module built by CI on the branch. `WakeMic` holds the two microphone setups so the
  probe measures the code that ships.
- By: Planner (claude-fable-5-2), on Plan Reviewer round 2 F5

## 2026-11-22 - A stop word counts only when playback overlapped the wake phrase
- Options: speaking at the wake or up to 2 s before (round 1); speaking at the detection or speech ended within the
  phrase's span (1 s) before the capture thread's detection time.
- Choice: the overlap (`WakeWord.overlapped`, pure), so "hey buddy, cancel" said after a reply has ended is sent as
  a request. The service knows the end of speech to 100 ms (it checks that often while Buddy speaks).
- By: Planner (claude-fable-5-2), on Plan Reviewer round 2 F6

## 2026-11-22 - Orchestrator rulings after plan review round 3 (no APPROVE in 3 rounds)
These override plan.md wherever they differ. Tie-breaker: the brief; keep it simple. The phone probe (step 4) can't be
run by the pipeline (it needs Nicholas speaking to the phone), so its blocking gates become run-time fallbacks plus
post-release device checks in `verify.md`. The pipeline does not stop for Nicholas.

- **Step 4 (the probe app `app.buddy.wakeprobe`) is dropped**, with its temporary Gradle module / build.yml route.
  Steps 7 and 8 are no longer gated on `probe.md`. P1-P6 move to `verify.md` as post-release device checks
  (pending until the switch). Supersedes "The probe comes after the shared capture code, and P1, P2, P6 block".
- **P1 (does the model ship):** the pre-release gate is CI only: `OnnxWakeEngineTest` on the real clips (positives
  ≥ THRESHOLD, sound-alike/silence/noise below) and `WakeIdleTest`. `THRESHOLD` stays a constant (0.5, or what the CI
  clips justify, recorded). Detection rate, false triggers and the screen-off check are post-release; if poor, the
  switch turns it off and tuning/training is a follow-up feature.
- **P2 / round 3 F1 (accepted): echo cancellation is required while Buddy speaks, as the brief says.** `WakeMic.open(true)`
  uses `VOICE_COMMUNICATION` + `AcousticEchoCanceler` (enabled when `isAvailable()`), `setPrivacySensitive(false)`.
  Run-time fallback, never a "pass": if that recorder fails to initialise, it opens `VOICE_RECOGNITION` with an
  `AcousticEchoCanceler` attempt so the detector isn't deaf. Every open logs one `BuddyWake` line with the source and
  whether the echo canceller is enabled; the post-release P2 check reads it. Plain `VOICE_RECOGNITION` is never
  chosen as the design.
- **P6 (is Phone input fed):** ship the fed path (`EXTRA_AUDIO_SOURCE` pipe, documented since API 33, and the feature
  is Android 14+). Nicholas answered Q1 (a) on 2026-11-22, so the unfed path ("hey buddy", pause, request) is an
  accepted fallback. Run-time fallback: if two fed Phone windows **in a row** fail within 3 s of `startListening`
  (a recognizer error other than no-match/speech-timeout, or a row-3 foreign recording that cancels the turn),
  Phone turns are unfed for the rest of the process, logged once. A pipe that can't be created makes that one window
  unfed. Two early failures in a row are needed so one unrelated recorder (round 2 F2) can't downgrade it, and a
  restart resets it. The on-device recognizer alternative is dropped. `Voice.PHONE_FEED` is a flag set by that
  fallback, not a compile-time probe result. Supersedes "Whether Phone turns are fed is decided by the probe".
- **Round 3 F4 (P6 probe could hang):** moot, the probe is dropped. The shipping pump already writes from a worker
  after `startListening`; keep it that way.
- **Round 3 F2 (accepted): ChatGPT events are bound to their session.** In `CodexDictation`, a transcript, a startup
  failure or `end(l, …)` from a listener that is no longer the current one changes nothing (no `heard`, no teardown
  of the current WebView or capture). Unit test if `CodexDictation` can be exercised in a JVM test; otherwise the
  check is code review plus a post-release device check.
- **Round 3 F3 (accepted): a socket reader is bound to the capture it was admitted for.** `MicServer.streamCapture`
  takes the capture id at admission and stops, without reading or setting `drained`, once the current capture id
  differs. `MicServerTest`: a reader admitted for capture a, then release + re-adopt (b): the reader gets none of
  b's audio and b isn't marked drained. Device check (post-release) uses the bundled `arecord`'s raw stdout:
  `arecord | head -c 96000 > /tmp/t.pcm` (16 kHz mono PCM16, 3 s), a word said after it started is there, the
  earlier "hey buddy" isn't.
- **Q2 = (a)** (Nicholas, brief Clarifications: Bypass chats running without approval cards is fine), Q3 = (a) answer
  while locked, Q4 = (a). The guide states the Bypass consequence.
- **Battery (T8):** before release, `battery.md` and the guide carry research's sourced estimate, marked as an
  estimate, with what drives it; the measured hour (P4) is a post-release check that replaces it.
- **Execution:** the coder runs the remaining steps in chunks (1-3, 5-6, 7-8, 9-10) so each fits the 1 h run limit,
  then `code-loop --ci` reviews the whole branch.
- By: orchestrator (claude-opus-5-5)

## 2026-11-22 - Step 1 details the plan left open (coder)
- `WakeFeatures.feed` returns the **highest** score of the hops completed in that call (not the newest), so a block
  spanning two hops can't hide a detection; with 40 ms blocks it is the same. Before four groups are in, a hop scores 0.
- One 24000-sample ring serves both the 1.08 s mel window and the 1.5 s pre-roll. `WakeFeatures.THRESHOLD` (0.5) and
  `WakeFeatures.SCALE` (1 = int16 range, as in training) are the two constants a CI failure of `OnnxWakeEngineTest`
  would flip (SCALE to 1/32768f). **Not run locally**: ONNX Runtime's glibc native library does not initialise on the
  phone's Alpine (gcompat tried: the library loads, `OrtEnvironment` creation then throws). So the clip test is CI-only
  and the scale is unverified until CI runs.
- Gradle: the unit-test runtime classpath of the github flavor excludes `onnxruntime-android` (the JVM
  `onnxruntime` jar, same classes, is what loads natively on the CI runner). CI-only, unverified here.
- `OnnxWakeEngine` is public with a public constructor `(classifier, mel, embedding)` so `src/main` can reach it by name.
