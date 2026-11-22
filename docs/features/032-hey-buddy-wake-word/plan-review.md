# Plan review: 032-hey-buddy-wake-word
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6.2-astra)

F1 [blocker] One-breath requests lose words on the default input - Phone is the fresh-install default (`Prefs.java:758–760`). The fallback releases the recorder and starts a recognizer after detection, explicitly allowing the first request words to disappear (`plan.md:153–157`). T2 can pass on Claude alone, while the first example in `brief.md:43–44` fails on Phone. -> Probe the selected phone recognizer with buffered audio, including words spoken during startup, and run T2 on Phone as well as the raw-audio path. Specify what works on older APIs and recognizers that ignore supplied audio. Any pause-only exception needs Nicholas’s decision in the brief before it counts as a pass. [Android recognizer audio-source API](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)

F2 [blocker] T3 verifies a different chat from Talk - The brief requires the same chat as Talk (`brief.md:23–24,45–46`). Talk uses `sessions.current` and selects a new chat only when needed (`Bubble.java:1650–1657`). T3 explicitly expects the wake request to bypass an open chat and land in a dedicated one (`plan.md:159–165,236`). Q1 is unanswered. The dedicated chat can itself be running or changed to Bypass (`ChatHub.java:305–307,788–796`), so the claimed protection also fails. -> Reuse Talk’s chat resolution and test a Talk turn followed by a wake turn that needs its context. If Nicholas chooses different routing, record that change first and align T3 and the claims about approvals with the chosen behavior.

F3 [major] A call or competing recorder can start during transcription - TURN keeps feeding audio and takes precedence over PAUSED (`plan.md:109–119`). A call starting just after detection can therefore leave the wake recorder forwarding audio to dictation until the turn ends. T5 only describes interruption of the idle detector (`plan.md:238`). -> Give a call, competing recording, or disable action precedence over an adopted turn. Cancel that turn, discard its buffered audio, and ignore its late transcript. Test an interruption during TURN, then verify that listening resumes with fresh audio.

F4 [major] A failed or racing handoff can leave the wrong capture active - The capture thread adopts audio before the main thread checks for an existing listener (`plan.md:126–139`). `MicServer` has one shared chunk list and recording flag (`MicServer.java:43–48,186–194`). A simultaneous Talk tap can have its buffer replaced or cleared. Either dictation engine can also reject a signed-out user immediately (`ClaudeDictation.java:45–46`, `CodexDictation.java:63–65`), and the Talk error path only toasts and returns (`Bubble.java:1678–1680`), leaving the adopted feed active. -> Reserve the wake handoff before changing shared capture state, make cleanup and the timeout apply only to the owning turn, and release it on every start error. Test a concurrent manual listen and an immediate rejection, including whether the next wake succeeds.

F5 [major] The first microphone grant can leave the switch on with no listener - The switch calls `ensure` while permission is missing and then requests permission (`plan.md:82–85,172–175`). A grant returns to Settings or Chat. Neither the planned hooks nor the existing callbacks retry startup there (`SetupActivity.java:92–107`, `ChatActivity.java:2156–2159`). -> Reconcile the service when a visible activity receives the mic grant, preserving any Talk or Live capture in progress. Test enabling from Settings with permission initially denied, granting it, and waking Buddy without visiting MainActivity.

F6 [major] Older supported Android versions can report listening without usable audio - The plan knowingly leaves background mic denial undetected on Android 11–13 (`plan.md:90–101`, `app/build.gradle.kts:22`). A successful service start alone does not establish microphone access on these versions. -> Use a visible or user-initiated start on these versions when necessary. Handle recorder initialization, start and read failures, and reuse the planned tap-to-start recovery. Verify actual detection after recovery and verify that a persisted off setting survives reboot. [Android foreground-service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions)

F7 [major] A wake phrase on its own can end the request window - An engine can finalize just “hey buddy” after the user pauses. The proposed final callback strips it and returns to idle (`plan.md:140–143`), so a request spoken next needs a second wake. Existing recognizers end their turn on silence (`Voice.java:41–60,142–148`). -> Keep one bounded request window when the wake phrase is the entire first result. Test a recognizer that finalizes the phrase, followed by a separate request without another wake, plus silence until timeout.

F8 [minor] Idle requests can be swallowed as playback controls - The plan saves `wasSpeaking` but drops every exact stop command, including “cancel” and “never mind”, even when nothing was playing (`plan.md:136–151`). The brief ties this control to active speech (`brief.md:47–48`). -> Consume a playback command only for a wake that interrupted speech. Test the same transcript with playback active and idle.

F9 [minor] No-network-while-idle has no direct check - T1’s airplane-mode run proves offline detection. It cannot reveal audio sent while connectivity is available (`brief.md:40–42`, `plan.md:128–133,234`). -> Observe the dictation handoff with connectivity available and a non-triggering clip. Assert that no idle frames reach either STT consumer before detection, then verify the bounded pre-roll on a genuine wake.

F10 [minor] GitHub-only service permissions leak into other builds - The plan puts both foreground-service permissions and the service in the common manifest because GitHub already has a manifest (`plan.md:68–77`). Gradle points GitHub at an editable manifest that already declares a flavor-only service (`app/build.gradle.kts:53–56`, `app/src/watchlink/AndroidManifest.xml:4–10`). -> Put the declarations in that existing overlay and check the merged manifests alongside the APK asset checks.

VERDICT: REVISE

### Planner response (round 1)
<!-- claude-fable-5-2, 2026-11-21. Section numbers are plan.md's after this round. -->

F1: accepted - Phone input now gets the detector's audio too. Every wake turn hands its audio to `MicServer`
(pre-roll first); for Phone, `Voice.listen` pumps that into Android's recognizer through a pipe
(`EXTRA_AUDIO_SOURCE` + rate, channels, encoding), so words said while it starts are in the pipe (§6). Older APIs:
the feature is only offered on Android 14+ (see F6), so the API 33 extra always exists. A recognizer that ignores
the audio and opens the microphone itself is noticed (a foreign recording during a fed Phone turn): that turn is
cancelled, and until Buddy restarts Phone turns use the plain path ("hey buddy", pause, request). Whether this
phone's recognizer (`com.google.android.tts`, Android 16; checked with `settings get secure
voice_recognition_service`) takes the audio is not documented anywhere I could find, so it is probe gate P6, before
the wake-turn step. T2 now runs on Phone and on Claude. If P6 fails for both recognizers on the phone, the pause-only
behaviour for Phone needs Nicholas's answer (Q1) recorded in the brief before T2 can pass; the plan does not assume it.

F2: accepted - the dedicated chat, `Prefs.wakeChat` and old Q1 are gone. A wake turn resolves its chat exactly as
Talk does (`sessions.current`, a new chat only if none) and sends with `hub.send` (§7). T3 is now a Talk turn
followed by a wake turn that needs its context. The claim that approvals protect a wake request is withdrawn: it
runs with the open chat's approvals, Bypass included (R8, the guide, and Q2 for Nicholas).

F3: accepted - `WakeWord.state` now puts OFF and PAUSED-for-a-call-or-foreign-recording above TURN (§5). When that
happens during a turn, `cancelTurn` ends it: the recognizer is cancelled, the held and adopted audio dropped, and a
transcript that arrives late is ignored (every callback checks its turn id). Listening resumes with cleared
buffers. Tests: state-table rows for call, recorder and switch-off during TURN (CI), and an interruption during a
turn on the phone (T5).

F4: accepted - the capture thread no longer touches shared capture state. After a detection it only holds its own
audio; the main thread (where `Voice` and the bubble live) checks nothing else is listening, reserves `MicServer`
for that turn id and calls `Voice.listen` in the same call, so a tap cannot come between (§6). `feed`, `release` and
the 2-minute cut only act for the owning turn; any start error releases it. CI tests on `MicServer`: a manual capture
during a wake turn keeps its own buffer, an immediate rejection frees the reservation, and the next wake adopts.

F5: accepted - `BuddyApp` registers one activity callback: every Buddy activity's `onResume` calls
`WakeWord.ensure`. A permission dialog closing resumes the activity that asked, so a grant from Settings or a chat
starts the service without MainActivity (it replaces the MainActivity hook). `ensure` only starts the service; the
state function keeps the microphone closed while Talk or Live has it. T6 has the case.

F6: accepted, by narrowing - the wake word is offered on Android 14+ only (`WakeWord.available()`); below that there
is no switch and `hey_buddy` refuses "on". There a refused start is an exception, never silence, so "listening" is
never shown without audio. Why not a visible-start rule for 11-13: no device to verify it on, and Nicholas's phone
is Android 16. On 14+: a refused start, a recorder that fails to initialise or start, and a failing read all stop
the recorder and post the tap-to-start notification; T6 checks a real detection after the tap and that "off"
survives a reboot.

F7: accepted - a first window that ends without a request (only the wake phrase, or nothing caught) opens exactly
one more window in the same turn, fed with what was said since the first one stopped recording (§6). The decision
is a pure class (`WakeWord.Turn`), tested in CI: phrase then request, phrase then silence, phrase twice.

F8: accepted - a stop command is consumed only when the wake cut speech (speaking at the wake, or it ended less
than 2 s before). Otherwise it is sent like any request. CI: the same transcript both ways.

F9: accepted - all three recognizers read only from `MicServer`'s buffer, so the check is on that seam: a CI test
runs the real models and the capture loop over the sound-alike clip, speech-free noise and silence and asserts
`MicServer` holds nothing and no turn was announced; then over a positive clip and asserts the first audio is the
pre-roll, at most 1.5 s. On the phone, with Wi-Fi on: 2 minutes of TV without the phrase gives no `turn` line in
`logcat -s BuddyWake` (T1).

F10: accepted - the service and both foreground-service permissions move to the github-only manifest
(`app/src/watchlink/AndroidManifest.xml`). CI checks the merged manifests: the F-Droid APK has neither, the GitHub
APK has both (T9).

## Round 2 - Plan Reviewer (gpt-6.2-astra)

F1 [major] The request after “hey buddy” can still disappear - The second window assumes HOLD contains everything spoken since the first window stopped. HOLD discards its audio after 10 s (`plan.md:150–155,177–186`), while Claude can wait 45 s for startup and another 20 s for a transcript (`ClaudeDictation.java:117–149`). On Phone, Android's own `onEndOfSpeech` only sets `inputDone` (`Voice.java:170–175`). Words spoken between that event and the final result can stay in the first buffer until `endCapture()` clears it. -> Mark the end of input for each engine, including Phone's callback, and retain subsequent audio for the active turn. If its bound expires, explicitly end the turn. Test a delayed phrase-only result with the user's next words already buffered, and the timeout path. Retry for an empty result or no-match only. A transcription error should end the turn with its cause.

F2 [major] An unrelated recorder can permanently downgrade Phone input for the rest of the process - Starting Samsung Voice Recorder during a working fed Phone turn meets the exact condition that sets `phoneFeedRefused` (`plan.md:212–217`). After Recorder closes, every one-breath request falls back to the path known to lose words. Public recording configurations omit the client package and UID, so that event cannot identify the recognizer. In the unfed fallback, row 3 requires the detector's recorder to be open, and TURN wins before row 5 can notice a second recorder (`plan.md:121–125`). -> Treat a competing recording as a cancellation. Determine feed capability from a controlled recognizer probe. For any approved unfed fallback, account for its expected recorder and cancel on additional recording activity. Extend T5 to start and stop Recorder during Phone input and verify the *entire next one-breath request*. [Android recording configuration anonymization](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/media/java/android/media/AudioRecordingConfiguration.java#L139-L152)

F3 [major] Late Phone callbacks can close a newer listener or consume its words - The turn-id check runs in the bubble listener (`plan.md:177–186`), after Android's `onResults` and `onError` have already called `Voice.finish()`, which destroys the globally current recognizer (`Voice.java:143–155,305–310`). The planned pipe pump also uses `MicServer.pull()`, whose cursor is shared across captures (`plan.md:202–209`, `MicServer.java:157–175`). A late callback or pump from a cancelled wake can affect manual Talk or the next window, which even reuses the same turn id. -> Check the originating listening window before cleanup or reads, and close that window's pipe on cancellation. Test a cancelled Phone wake followed immediately by manual Talk, then deliver the old error and resume the old pump. The new listener must keep its audio and deliver its result once.

F4 [major] The Terminal can receive the wake buffer instead of starting its own recording - `TerminalActivity.java:262–286` opens the same socket used by Claude dictation. For a matching recording format, `MicServer.serve` streams the current buffer and returns before it marks a recorder active (`MicServer.java:237–247`). Extending that buffer to Phone and ChatGPT while leaving socket readers unchanged (`plan.md:157–162`) lets a Terminal recording started mid-turn receive pre-roll from before its own start. No new Android recording configuration appears to trigger the pause rule. -> Keep in-process wake buffers private to the chosen consumer. Distinguish Claude's intended socket reader from Terminal recording, give the latter normal mic handoff, and test a Terminal request during a Phone or ChatGPT wake turn. The Terminal's first sample should come from its own recording.

F5 [major] The release can bypass the phone checks that decide the design - Step 2 says a probe build failure moves its checks until after release (`plan.md:275–282`), while P1 decides whether the model can ship, P2 decides the playback microphone setup, and P6 decides which Phone path to implement (`plan.md:212–221,309–317,332–335`). P6 also passes on the single word “weather”, even if the recognizer lost “what's the” during startup. -> Build the probe after its shared capture code exists or run equivalent checks in a side-by-side build. Keep those three decisions pending until measured or the brief is changed. In P6, put a distinctive request word wholly in the buffered prefix and require it and a later live word in order. Include a spoken wake with the screen off to check always-on detection directly.

F6 [minor] The two-second grace period still swallows idle requests - `plan.md:168–169,188–192` classifies “hey buddy, cancel” as a playback control even when the user starts speaking after the reply has finished. T4 tests the boolean after classification, so it misses this boundary. -> Tie the decision to whether playback overlapped the detected wake phrase. Test both a phrase that straddles the end of playback and one that begins just afterwards.

VERDICT: REVISE

### Planner response (round 2)
<!-- claude-fable-5-2, 2026-11-22. Section numbers are plan.md's after this round. -->

F1: accepted - three changes (§6). (a) The audio held while a transcript is awaited is kept for the whole wait: the
hold's limit is 90 s, longer than any engine allows itself (Claude 45 s to start + 15 s to deliver + 20 s to read;
ChatGPT 30 + 20), and when it runs out the turn is cancelled outright (recognizer cancelled, pill gone, a log line),
never continued with missing audio. (b) Phone's `onEndOfSpeech` now marks the end of input like the other engines'
`finish()`: in a fed window it calls `MicServer.stopCapture()`, so the capture loop holds everything from that
moment. (c) Only "nothing heard" opens the second window. `Voice.Listener` gets `onNothing(message)` (default:
`onEnd(message)`), called where the three engines report an empty result or no-match; any other `onEnd` message is
an error: it is shown as Talk shows it and ends the turn. Tests (CI): 30 s of blocks arriving after the first
window stopped are all in the second window, in order; the 90 s limit drops the audio and reports once; `Turn`:
nothing heard → AGAIN, then END; "nothing heard" and an error reach the listener as different callbacks, also
through `Voice.endpoint`'s wrapper (an error never reaches `Turn`).

F2: accepted - `Voice.phoneFeedRefused` and the run-time downgrade are gone. A foreign recording during a turn only
cancels that turn; the next turn is fed again. Whether Phone turns are fed is decided once, by the controlled probe
P6 (§6 "Phone"), and compiled in. The unfed path exists only if P6 fails for both recognizers and Nicholas accepts
it (Q1); in it the turn expects exactly one other recording (the recognizer's) and a second one cancels it: row 3
of the state table now counts against what the turn expects, and it still ranks above TURN. T5 gains: Samsung
Voice Recorder started and stopped during a Phone-input turn, then a whole one-breath request must arrive.

F3: accepted - a listening window now has an identity that is checked before anything else (§6 "Windows"). In
`Voice`'s phone branch every recognizer callback starts with `if (recognizer != mine) return;`, before `finish()`,
so a late `onResults`/`onError` of a cancelled recognizer can neither destroy the newer one nor deliver. The
window's pipe is closed in `finish()`. `MicServer` ownership is by capture id, new for every window (the second
window no longer shares the first one's id), and the pump reads with `pull(capture, max)`, which returns null
without moving the cursor once that capture is over; the pump then closes its pipe and exits. Tests (CI): a stale
capture id can't feed, pull or release while a newer capture keeps all its audio; a cancelled phone window's late
error leaves the next window alone, which delivers once.

F4: accepted - `MicServer.serve` hands the captured buffer to a socket reader only while Claude's dictation is the
engine listening (`ClaudeDictation.active()`). In a Phone or ChatGPT turn (and in a manual ChatGPT dictation, where
this was already possible) a Terminal recording opens its own `AudioRecord` as it does when nothing else records,
and is counted as Buddy's own (row 5 after the turn). Tests: the predicate in CI; on the phone, `arecord` in the
Terminal during a Phone-input wake turn records only what is said after it started (T5). Not changed: during a
Claude turn the hidden session and the Terminal can't be told apart on the socket (same token, same program); that
is today's behaviour for manual Claude dictation.

F5: accepted - the "if the probe can't be built, check after the release" sentence is deleted. The probe is now
step 4, after the capture code it shares (`WakeLoop`, `WakeMic`, the engine), compiled from the same files, not
copies. P1, P2 and P6 are gates: steps 7 and 8 (the service, the wake turn) are not written until `probe.md` has
their results, and if a gate
can't be measured the pipeline stops for Nicholas; nothing is released on an assumption. A second way to build the
probe (a temporary Gradle module built by CI on the branch) is named so "can't be built on the phone" isn't the
end. P6's clip is two recordings: "hey buddy what's the weather" written whole into the pipe before
`startListening`, then "like in Lisbon tomorrow" in real time; pass needs "weather" and then "tomorrow" in the
transcript. P1 gains 5 spoken "hey buddy" after 5 minutes with the screen off, unplugged.

F6: accepted - the 2 s grace is gone. A stop word is swallowed only when playback overlapped the wake phrase:
Buddy was speaking at the detection, or its speech ended less than 1 s (the phrase's span) before the detection
time, which the capture thread stamps. `WakeWord.overlapped(speechEndedAt, detectedAt)` is pure; CI tests a phrase
that straddles the end of playback (cut → swallowed) and one that begins just after it (sent).
