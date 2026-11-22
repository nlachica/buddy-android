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
