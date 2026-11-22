# Code review: 032-hey-buddy-wake-word
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6.2-astra)

F1 [blocker] The shipped detector misses the augmented positive clip - `app/src/testGithub/java/app/buddy/assistant/OnnxWakeEngineTest.java:87`. CI run 45000867734 on `02eb9b9` failed here. The clean positive passed line 86, but the augmented phrase scored below the production threshold of 0.5. The negative controls passed. This fails the agreed P1 pre-release gate and prevents the release APK checks from running. -> Compare both positive peaks and the negative peaks against the reference pipeline, correct the preprocessing or calibrate the production threshold with those scores, then rerun the unchanged clip tests and the full build.

F2 [major] Reboot and accessibility reconnect leave Hey Buddy off - `app/src/main/java/app/buddy/assistant/BuddyService.java:86`. Connection sets the service instance and creates the bubble, but never calls `WakeWord.ensure`. Unbinding explicitly stops the detector. On reconnect with no Buddy activity resumed, nothing attempts a foreground start or posts the promised tap-to-start notification. -> Call `ensure(this)` after connection setup. Check reconnect with the saved switch both on and off.

F3 [major] A cancelled ChatGPT window can still supply words to the next one - `app/src/main/java/app/buddy/assistant/CodexDictation.java:428`. Transcript callbacks post only text. Once the next window is connected, `heard` appends a late transcript to its buffer. The error handler at line 434 likewise passes whichever listener is current to `end`, so its new identity guard accepts the stale error. Successive calls reuse the dictation thread ID. -> Give each realtime call a distinguishable session identity and check it when dispatching its transcript, SDP and error events. Exercise a cancelled call whose transcript and error arrive after its replacement connects.

F4 [major] Manual and unfed input can open against the detector's live microphone - `app/src/main/java/app/buddy/assistant/WakeService.java:77`. `closeNow()` sets `want = 0` and drops the buffer. The capture thread releases the actual recorder on a later iteration. Meanwhile `Voice.listen` can immediately start another recorder, including the synchronous `MicServer.startCapture` used by Claude and ChatGPT. There is no ordering between those two opens and closes. -> Start the new input after the capture thread acknowledges that the old recorder is closed. Test with a delayed detector read and assert release precedes the new open.

F5 [major] A fed Phone window can stop before the recognizer receives the request - `app/src/main/java/app/buddy/assistant/Voice.java:461`. Finishing input calls `recognizer.stopListening()` immediately after marking the capture complete. The worker at line 244 may still be writing pre-roll or a second window's buffered request to a slow reader. Android recognizes the speech received up to the stop, so the final words can be lost. -> End capture, let the worker drain and close the write end, then request a stop on main if still needed for that same window. Test with a delayed reader and a distinctive final word. [Android speech recognizer contract](https://developer.android.com/reference/android/speech/SpeechRecognizer#stopListening())

F6 [major] Switching playback microphones discards a wake phrase in progress - `app/src/main/java/app/buddy/assistant/WakeService.java:165`. Both IDLE → SPEAKING and SPEAKING → IDLE call `drop()`, which clears the pre-roll and model history. The first possible score afterwards requires 1.08 s of samples plus three 120 ms hops. If “hey” precedes the end of playback and “buddy” follows the switch, the detector loses “hey” before the tested overlap rule can run. -> Preserve detection history across playback-source changes. Keep the reset for actual pauses and stops. Exercise a positive clip that spans the source transition.

F7 [major] Forced read-aloud can carry into a later typed turn - `app/src/main/java/app/buddy/assistant/ChatHub.java:1382`. A wake sets `spokenRun` on the session until `runEnded`. A `result` can finish the wake answer while a background job keeps the process running. A typed follow-up then goes through `sendLive` and its answer is also spoken with read-aloud off. Conversely, a queued wake request retried after `buddy_done` loses the flag before its answer. -> Associate forced speech with the submitted turn, carry it through queued delivery, and clear it when that turn's answer ends. Test a wake followed by typed input in a still-running chat and a queued wake delivered in a fresh run.

F8 [minor] Nonconsecutive failures permanently disable fed Phone input - `app/src/main/java/app/buddy/assistant/Voice.java:140`. A failure outside three seconds leaves `fails` untouched, as does a cancelled window. An early failure, a late failure and another early failure therefore disable feeding for the rest of the process. -> Reset the streak on every intervening fed window that ends without a qualifying early failure. Add the early–late–early sequence to `VoiceWindowTest`.

F9 [minor] A wake turn displays a separate waveform when the bubble is hidden - `app/src/main/java/app/buddy/assistant/Bubble.java:1720`. `applyEnabled` hides the bubble root when locked or disabled, but every successful wake calls `showWaves(true)`, which adds a separate accessibility overlay directly to the window manager. -> Show waves only while the bubble is visible and remove them when it becomes hidden. Keep listening and sending the request while locked.

F10 [minor] A failed microphone open can leak its recorder and echo canceller - `app/src/main/java/app/buddy/assistant/WakeMic.java:47`. The new objects are still local when enabling AEC or starting recording can throw. The catch returns false, leaving those resources outside the reach of `close()` as the fallback opens a second recorder. -> Release everything acquired by each failed attempt in a finally block, transferring ownership only on success.

F11 [minor] The Terminal recording count can suppress the required pause - `app/src/main/java/app/buddy/assistant/MicServer.java:374`. A recorder that fails initialization or throws in `startRecording()` reaches `listening(-1)` without ever incrementing. The next successful Terminal recorder can then leave `serving()` false, while its session is also excluded from the foreign-recording count. Concurrent clients can additionally lose updates to `active += d`. -> Pair each decrement with a successful increment and serialize the count update. Check a failed client followed by a successful one.

F12 [minor] The guide presents unverified compatibility and misattributes the battery rate - `docs/GUIDE.md:49`. The guide says Google's recognizer accepts the supplied audio even though P6 was deferred. It also attributes an hourly figure to Home Assistant, whose reported change has no stated time window in `battery.md`. -> Describe Phone compatibility as pending the device check and attribute the approximately 1.2%/h and 25%/h estimates to the developer measurements already documented in the feature folder.

F13 [minor] The settings tests miss the supported default-on path - `app/src/test/java/app/buddy/assistant/SettingsCoverageTest.java:483`. The case checks discovery, acceptance of “off”, and rejection of “on” in the unsupported JVM environment. It would still pass if a fresh install defaulted off or if enabling the feature were rejected on every device. -> Cover an unset preference reading true and an eligible configuration accepting “on”, alongside the existing unavailable case, as T6 and T7 specify.

F14 [minor] Wake and Talk have separate implementations of the same chat selection - `app/src/main/java/app/buddy/assistant/WakeWord.java:235` repeats the current-chat lookup, default chat creation, selection, and send in `Bubble.java:1678`. T3 relies on these entry points always choosing the same chat. -> Share Talk's destination and send logic when making the turn-specific speech change in F7.

On the reviewed head, 67 tests passed across the wake logic, capture, voice-window and settings suites in the local Android-stub JVM harness. All three shipped model checksums matched. The real-model result above is from the supplied CI run. Device checks remain deferred under the orchestrator's ruling.

VERDICT: REVISE

### Coder response (round 1)
Commits are named under "Commits" below; details are in decisions.md ("Code review round 1").

- F1: **not fixed, open: needs one CI run.** The phone can't run ONNX Runtime and the failed run's log has no score
  (only "AssertionError at line 87"), so a threshold or scale change would be a guess. `SCALE` = 1 is what hey-buddy's
  Python does (`audio × 32767`), the clean clip passes with it, and the augmented clip is full-scale clipped. Changed so
  the next CI run gives the numbers: the test's failure message carries clean, augmented and sound-alike peaks, and
  `app/build.gradle.kts` prints failed assertions in full (CI-only, not verified here). Then set `THRESHOLD` from them.
- F2: fixed in the commit "wake: start at connect, mic hand-off, history across switch" (`BuddyService` calls
  `WakeWord.ensure(this)` after the bubble is up; it stops itself when the switch is off).
- F3: fixed in "ChatGPT dictation: one conversation per call" (each call has its own Codex thread; events of any other
  thread are ignored). Not unit-tested: needs a WebView and a Codex server.
- F4: fixed in the wake commit: `WakeService.closeNow()` waits for the capture thread to release the recorder (300 ms
  cap) before `Voice.listen` goes on. No unit test: needs a Service and an AudioRecord.
- F5: fixed in "Phone feed: stop after the audio is written, streak reset" (the pump calls `stopListening()` after
  the pipe is closed). No unit test: needs a recognizer.
- F6: fixed in the wake commit (the capture thread drops history only when the microphone was closed, not on idle <->
  speaking). No unit test: the capture thread isn't testable without an AudioRecord.
- F7: fixed in "One send for Talk and hey buddy, reply speech tied to the request" (`spokenAsk` carries the request to
  the turn that starts with it, `result` clears it). No unit test: `ChatHub` needs a service.
- F8: fixed in the Phone feed commit; `VoiceWindowTest.aLateOrCancelledWindowBreaksTheStreak` has early-late-early.
- F9: fixed in "Bubble waves, mic open, Terminal count, docs, settings test" (waves only while the bubble is visible, removed when it hides).
- F10: fixed in the same commit (a `finally` releases the recorder and echo canceller unless the open succeeded).
- F11: fixed in the same commit (`listening(-1)` only after a counted `+1`; the count is synchronized).
- F12: fixed in the same commit (GUIDE: Phone compatibility is "not yet checked on a phone"; the 1.2%/h and 25%/h
  figures are the developer write-up's, Home Assistant's is its unstated-window rise).
- F13: fixed in the same commit (`Prefs.WAKE_WORD_DEFAULT` and `SettingsTool.heyBuddyReady` are the seams;
  `SettingsCoverageTest` checks default-on, "on" accepted where offered, refused where not).
- F14: fixed with F7: `ChatHub.sendTalk` is the one destination and send of Talk and wake.

## Round 2 - Code Reviewer (gpt-6.2-astra)

F1 [blocker] The shipped threshold still misses the augmented wake phrase - `app/src/main/java/app/buddy/assistant/WakeFeatures.java:22`. CI run 45002190404 tested the reviewed head, `938a8bd`. The clean positive peaked at 0.991189, the augmented positive at 0.17219818, and the sound-alike at 0.057454735. At 0.5 the augmented phrase cannot trigger. This was the sole failure in 329 tests and blocks T1 and T9. -> Try a production threshold between the sound-alike and augmented peaks, such as 0.12. Keep the fixtures and assertions, verify silence and both noise controls at that threshold, record the calibration, and rerun the full APK workflow.

F2 [major] An old ChatGPT call can still end the next dictation - `app/src/main/java/app/buddy/assistant/CodexDictation.java:443`. The server reader checks the thread ID before posting to the main thread. If the user replaces call A while A's error is queued, the posted callback passes B's current listener to `end`, which accepts it and tears down B. The SDP and transcript callbacks also lose their thread ID when posted. -> Capture the event's thread ID and check it inside each main-thread callback, immediately before accessing the listener or WebView. Test an error queued for A, replace A with B, then dispatch the error and verify B stays active.

F3 [major] An unfed recognizer can still start while the detector owns the microphone - `app/src/main/java/app/buddy/assistant/Voice.java:235`. If pipe creation fails, the earlier `WakeWord.release()` has already skipped closure because the capture was adopted. The catch releases the buffer and drops the loop, then line 220 starts the unfed recognizer. Only a later service tick requests recorder closure. Even ordinary handoffs return after 300 ms with no indication of whether the recorder closed (`WakeService.java:91`). -> Have both paths start input only after the capture thread confirms release. A timeout can fail the window cleanly. Exercise a pipe failure and a delayed recorder close, checking that the new recorder opens after release in both cases.

F4 [major] A queued wake request can speak the wrong answer and lose speech on retry - `app/src/main/java/app/buddy/assistant/ChatHub.java:275`. Queuing a wake note immediately enables speech on the running turn, even if its answer arrives before the agent takes that note. On the live-send path, `turnStarts` consumes `spokenAsk` before the helper confirms delivery. A failed delivery goes through `leftover` with text alone. A local JVM check exercised the production turn-start and retry-routing methods with the same request and got `spokenRun=true` on the first attempt and `false` on retry. -> Carry the speech flag with the pending note through delivery and retry. Enable speech when the agent takes the note or starts its turn. Test a typed answer arriving before note delivery and a wake request retried into a new run with read-aloud off.

F5 [major] An inference error can crash Buddy during idle listening - `app/src/main/java/app/buddy/assistant/WakeService.java:201`. Model loading is guarded, but scoring is outside that guard. `OnnxWakeEngine.run` turns an `OrtException` into an unchecked exception that escapes the capture thread, bypassing microphone and session cleanup and reaching Android's uncaught-exception handler. The default-on detector can take an unrelated chat down with it. -> Handle inference failure at the capture-loop boundary, release the microphone and engine in a finally block, and stop the detector for this process. Exercise a fake engine that throws on a hop.

F6 [minor] Restarting the listener leaks native session options - `app/src/wakeword/java/app/buddy/assistant/OnnxWakeEngine.java:20`. Each engine allocates a native `SessionOptions`; `close()` releases only the three sessions. If loading the second or third model fails, the earlier sessions also have no owner to close them. -> Retain the options until all sessions close, then close them. On construction failure, close the sessions already created and then the options. [ONNX Runtime session-options lifetime](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html)

F7 [nit] The recorder exposes an unused state check - `app/src/main/java/app/buddy/assistant/WakeMic.java:82`. No production or test caller uses `isOpen()`. -> Remove the method.

All 68 tests in the six local wake logic, capture, voice-window and settings suites passed against the reviewed Java changes using Android stubs. All three model checksums matched. The real-model scores are from CI. The release APK checks did not complete, and the physical-phone checks remain pending under the orchestrator's ruling.

VERDICT: REVISE

### Coder response (round 2)
Details in decisions.md ("Code review round 2"). No JVM runs on this phone (Gradle: "Failed to mark memory page as
executable"), so every test below is **CI-only, not run here**.

- F1: fixed in 083d29b: `THRESHOLD` = 0.12 (between the sound-alike 0.057 and the augmented 0.172). Fixtures and
  assertions kept; silence and noise at 0.12 are checked by the next CI run (the message prints them), unverified here.
- F2: fixed in fe2bd31: each main-thread callback re-checks the event's thread against the current call; `CodexDictationTest`
  queues an error for A, replaces A with B, dispatches it and checks B stays.
- F3: fixed in f2c4a48: `closeNow()`/`WakeWord.release()` report whether the recorder is released; `Voice.listen` fails the
  window (`dictation_mic_failed`) if not, and the pipe-failure path closes the detector's recorder the same way before
  the unfed recognizer starts. No unit test: needs a Service, an AudioRecord and a recognizer; post-release device check.
- F4: fixed in 0231a9b: speech is set when the agent takes the note or a turn starts with that text; a live send consumes
  the request only after delivery. `SpokenAskTest`: queued note leaves the running turn silent, taking the note speaks,
  a failed delivery retried as a new run speaks, another turn clears the wait.
- F5: fixed in a7837fc: `WakeLoop` catches a scoring failure, goes dead and calls `Host.onFailed`; the service marks the
  detector broken and shuts down (microphone and engine released by the capture thread's end). `WakeLoopTest` has a
  throwing engine.
- F6: fixed in a7837fc: the engine owns and closes its `SessionOptions`, also when the constructor fails.
- F7: fixed in a7837fc: `WakeMic.isOpen()` removed.

## Round 3 - Code Reviewer (gpt-6.2-astra)

F1 [major] A typed message can silence the next wake reply - `app/src/main/java/app/buddy/assistant/ChatHub.java:1195`. Sending typed text to a live chat sets `turnOver = false`. A wake request arriving while that delivery is pending therefore queues as a note. When the typed delivery succeeds, its callback calls `turnStarts`, which clears the queued request's `spokenAsk` at line 282. A JVM check dispatched that callback's turn-start logic, then delivered the wake note through `notesTaken`. The resulting `spokenRun` was false, so with read-aloud off the user hears no answer. -> Carry the speech flag with the message being delivered and its retries. A completed delivery should consume only its own flag. Cover a typed delivery completing between queuing and taking a wake note.

F2 [major] Two current Phone recognizer errors can disable every wake attempt without activating the fallback - `app/src/main/java/app/buddy/assistant/Voice.java:345`. A live window reporting `ERROR_CLIENT` skips `fedFailed()` and ends silently. In a JVM check, two consecutive fed windows received that error immediately. Both ended with a null message and Phone feeding stayed enabled. The P6 ruling excludes only no-match and speech-timeout. Android defines `ERROR_CLIENT` as a general client error, and line 341 already discards callbacks from cancelled windows. -> Count this error on the current fed window, report the failure, and test that the next wake uses the unfed path after two early failures. Keep the stale-window cancellation test. [Android SpeechRecognizer error contract](https://developer.android.com/reference/android/speech/SpeechRecognizer#ERROR_CLIENT)

F3 [major] A cancelled ChatGPT startup can still take ownership of the replacement call - `app/src/main/java/app/buddy/assistant/CodexDictation.java:235`. The worker checks `web == page` and publishes `callThread` in separate operations. If A passes the check, then the main thread cancels A and opens B before A publishes, A can install its ID and start its realtime call while B's listener is current. An error from A now passes both thread-ID checks and ends B. The new test starts after B already owns the ID, so it misses this ordering. -> Serialize the page check and call-ID publication with cancellation, as `CodexSpeech.startCall` and `hangUp` already do. Test cancelling A during startup, opening B, and resuming A's worker.

F4 [major] Live voice can open the phone microphone before the detector releases it - `app/src/main/java/app/buddy/assistant/CodexLive.java:221`. An idle detector has no `Voice` listener, so Live's `Voice.stopListening()` leaves its recorder open. Live proceeds to `openPage()`, where the WebView requests the microphone and can try a native recorder as fallback. The detector first learns of Live at its next tick, up to 500 ms later. Both paths can therefore request the microphone at once during call setup. -> Use the same confirmed detector release as Talk before opening a phone Live page. Keep watch calls on their existing path. Check with a delayed detector close that the phone Live input starts only after release succeeds.

All 74 tests in the eight changed local JVM suites passed with Android stubs. All three model checksums matched. The Build workflow passed on the reviewed commit `b438bdc`, including the real-model tests, both release APKs, flavor isolation and F-Droid reproducibility. Physical-phone checks remain scheduled for after release under the orchestrator's ruling. [Build run 45003087939](https://github.com/nlachica/buddy-android/actions/runs/45003087939)

VERDICT: REVISE
