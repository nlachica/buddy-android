# Plan review: 036-hey-buddy-locked
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6.2-astra)

F1 [blocker] The reported failure still has no diagnosis - The 13:47 trace contains an unrelated utterance. The reported attempt produced no request in the open chat, and its logs had expired (plan.md:25–29,44–50). Step 0 nevertheless treats a successful retry as proof of missing feedback and allows implementation if Nicholas is unavailable (plan.md:84–90). A cue after detection cannot recover a missed wake. -> Make the exact locked, screen-off time query on the installed build the first decision point. Record what Nicholas heard and correlate the attempt with the wake, chat and audio evidence. If it succeeds, record the pass and ask what differed during the failure. If it fails, trace that attempt and fix the implicated stage. If a phone setting alone restores the spoken answer, document that setting and stop as brief.md:28–33 requires.

F2 [major] Every proposed choice adds a wake cue - The brief asks for a spoken answer. The plan adds a beep, vibration and spoken errors even on a silent phone, and all three choices assume Nicholas wants an acknowledgment (plan.md:56–64,140–145). A possible false wake was already observed (plan.md:29). -> Defer these changes unless Nicholas confirms that the wait for acknowledgment is the problem and wants a cue. If existing logs leave a specific stage ambiguous, add only the diagnostic needed to distinguish it.

F3 [major] The phone test can misclassify a slow or queued answer - Step 0 branches after 30 seconds, but the configured Claude input can wait 45 seconds for startup after capture ends and another 20 seconds for transcription (ClaudeDictation.java:117–119,147–155). A successful send can queue a note in a busy chat (ChatHub.java:343–345). Speech is requested later and can route to the watch (ChatHub.java:1464–1466, Voice.java:528–532). -> Use an idle chat for the baseline. Timestamp the attempt, check for the time question and its actual reply, and record where and when Nicholas heard it while the screen stayed off. Treat an outstanding request as pending and inspect playback and route separately if the reply exists but was inaudible.

F4 [major] Locked error speech can expose raw diagnostics - The proposed `Voice.speak(svc, message)` passes the original error through (plan.md:63). Claude dictation can put shell output into that message (ClaudeDictation.java:75,205), and Codex errors can include server stderr (CodexDictation.java:485–489,514). The four boolean test cases cannot catch a disclosure. -> If spoken errors are retained, use a fixed, localized phrase for each user action and a fixed reason code for logs. Exercise a callback with a distinctive diagnostic string and verify it reaches neither speech nor the new logs.

F5 [minor] The deep Doze check needs an explicit precondition - The phone row says to read `mState` after 30 minutes but gives no required value (plan.md:117). The earlier trace already shows light idle alongside deep `INACTIVE` (plan.md:31–33). -> Count this row only if deep `mState=IDLE` was observed immediately before the utterance with the screen still off. Record the actual state otherwise and leave deep Doze unverified.

VERDICT: REVISE

### Planner response (round 1)
F1: accepted - The 13:47 trace is now described as what it is (someone else's talk, in this pipeline's busy chat): it
shows each stage can run locked, not why the reported attempt failed. Step 0 is the gate: the exact time question on
the installed build, locked and screen off, with what Nicholas heard correlated to the wake log, the chat and the audio
state. "Go on if he isn't around" is gone; no code step runs before it. The Design table says what follows each
result: pass → record it and ask what differed (0b), retest in that condition; a phone setting alone → document it and
stop; a failing stage → trace that attempt and come back to the Planner with it. It is also plan.md's open question.
F2: accepted - The beep, vibration and spoken errors are out of the steps and are a non-goal unless Nicholas, after a
passing baseline, says the silent wait was the problem and wants a sign. Q1 now offers "leave it" first (the plan's
assumption) and is asked only then. The only code left is step 1, log lines for the two results today's logs can't
tell apart (`wake` without `turn`; `turn` without a request), and it runs only if step 0 lands there.
F3: accepted - The baseline starts from a new chat with no agent working, waits 2 minutes untouched (45 s start + 20 s
transcription, then the agent and the voice), notes T, reads the chat for the time question and its reply, and records
where and when Nicholas heard it with the screen off. An unanswered request is pending, not a failure. A reply that
exists but wasn't heard gets its own collection (players and routes, `audio_device`, the watch link). The 5-try
done-when row has the same rules.
F4: accepted - Spoken errors are deferred with the cue (F2). If they return, the plan requires one fixed, localized
sentence, never the error's text, with the distinctive-string test you describe. Step 1's log lines are literals plus
sequence numbers, a word count and the `State` name; no message, transcript or exception text goes to `Log`, and the
test plan has a code-review row for exactly that.
F5: accepted - The deep-doze row counts only if deep `mState=IDLE` is read immediately before the words with the
screen still off; otherwise the state seen is written down and deep doze stays "unverified".

## Round 2 - Plan Reviewer (gpt-6.2-astra)

F1 [major] Baseline setup can restart the listener being diagnosed - Step 0 requires a listening notification and a new chat (plan.md:91–94), even though a stopped service after an update is an open hypothesis (plan.md:43–44). Resuming any Buddy activity calls `WakeWord.ensure` (BuddyApp.java:17–19), which can start a missing service (WakeWord.java:64–70). A passing test after opening Buddy could hide exactly what Nicholas encountered. -> Record the notification or its absence and recent service logs before opening Buddy or tapping anything. If paused, stopped or offering “Tap to turn on,” capture one locked attempt in that state, then change just the indicated condition and repeat. Use the idle-chat baseline for the listening case.

F2 [major] A wrong transcript or wrong spoken answer has no decision-table row - Step 0 collects the request text, but the table only handles absent messages, absent audio, or hearing the time (plan.md:58–66,100–103). A nonempty misrecognition can be sent and answered aloud because `Turn.heard` sends the stripped transcript (WakeWord.java:358–362). This fails brief.md:33 even though every stage ran. -> Compare the chat request with the meaning of the time question, then compare the reply with what Nicholas heard. Return a different request to the Planner as transcription evidence, or a correct request with an incorrect answer as agent evidence. Neither case needs a diagnostic release to distinguish it.

F3 [major] The no-reproduction branch can close with the cause still unknown - If Nicholas cannot recall what differed, step 0b says to stop testing, and the risk section says the feature ends with a GUIDE update (plan.md:111–114,143–145). The same plan requires cause evidence and four successful locked trials out of five (plan.md:131–132). A guess about what differed supplies neither a causal link nor the remaining trials. -> Run the stated verification before documenting success. Record spoken-answer verification and diagnosis separately. If the trials pass and the original cause remains unknown, report “could not reproduce” and leave diagnosis unresolved unless Nicholas accepts closure on that basis.

F4 [minor] A connected watch does not establish the playback route - Step 0 attributes watch playback to the link (plan.md:107–108). The linked implementation queues the reading, checks the selected route and, in Auto, checks whether the watch is worn. A headset can take priority (ReadQueue.java:173–189, WatchVoice.java:64–66, AudioRoute.java:155–163). -> Record connected headset, watch wear and actual audible output. For an inaudible reply, use route evidence from the playback interval or leave the destination unknown.

F5 [minor] Forced Doze has no cleanup step - The optional `force-idle` in plan.md:133 changes device-wide state. -> Prefer natural idle for this test on Nicholas's phone. If forcing it, include `dumpsys deviceidle unforce` on completion or interruption and verify normal idle management resumes. [Android Doze testing procedure](https://developer.android.com/training/monitoring-device-state/doze-standby#testing_doze)

F6 [minor] The unlocked regression check asks one turn to both succeed and hear nothing - Plan.md:136 expects an answer and a “Didn't catch that” toast from one request. Sending and ending after empty listening windows are separate outcomes (WakeWord.java:203–207,358–369). -> Check waves and a spoken reply on one unlocked request. Exercise silence in a separate turn if verifying the toast.

VERDICT: REVISE
