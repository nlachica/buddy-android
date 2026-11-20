# Plan review: 031-headset-route
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6.2-astra)

F1 [major] A failed headset can play an entire reply on the earpiece - DW2 requires speaker fallback and says “never the earpiece” (brief.md:34). Step 2 tests only the device selected at 30 s. The remote audio element is already playing at 7.38 s in live-ls2.log:21, and plan.md:129 explicitly allows audible earpiece playback for the remaining wait. LS2 needs time to connect, but the setup deadline alone cannot establish where a reply plays during that time. -> Keep the SCO setup window and protect phone playback while the reported route is the earpiece. Test a reply arriving before confirmation for both eventual LS2 success and timeout to speaker. If the intended trade-off is audible earpiece playback, get Nicholas's explicit change to DW2 before adopting it.

F2 [major] Dictation starts capturing before Buddy requests the headset - AudioRoute.java:268 posts the route change. In the same main-thread turn, Voice.java:186 starts the recognizer, and ClaudeDictation.java:56 and CodexDictation.java:74 start MicServer capture, which calls startRecording() immediately (MicServer.java:118). A short utterance can therefore finish on the handset before SCO connects. The proposed want() test passes even in that sequence. -> For Auto and saved LS2, initiate routing before capture and tie the ready-to-speak signal and capture start to headset confirmation, with bounded failure and cancellation. Test delayed confirmation and an immediate short utterance. For MicServer the active recorder's input can also be checked on the phone. [Android AudioRecord.getRoutedDevice](https://developer.android.com/reference/android/media/AudioRecord#getRoutedDevice())

F3 [major] An immediate read-aloud can still choose the watch after dictation - plan.md claims “the next poll sees \"\" and releases it.” An idle poll skips reconcile() and waits for finish() after the quiet interval (AudioRoute.java:448–461). Voice.speak() posts use() and then dispatches the reading (Voice.java:323–325). A watch decision can therefore see the dictation SCO even after listening becomes false, the same condition plan.md:136 identifies during dictation. -> Release the temporary Auto selection before resolving the next reading's target. Exercise a reading immediately after dictation with both LS2 and the watch connected, without waiting for the idle timeout.

F4 [minor] Auto dictation changes unrelated device choices - Step 3 opts explicit Watch and wired/USB devices into the new call-mode behavior (plan.md:96,138), while DW3 specifies Auto and saved LS2. liveDevice() can also return wired headphones or a BLE speaker (AudioRoute.java:61–70,189), so its return value alone does not establish that the device has a microphone. -> Limit the new override to Auto and a matching Bluetooth headset call device. Preserve the existing saved-pick path and cover explicit Watch and wired-only configurations as unchanged cases.

F5 [minor] The proposed setup test omits the second route request in the recorded call - CodexLive.java:771 calls use() again when the page connects. AudioRoute.java:278 clears applied during Live, and a successful reapply restarts appliedAt and clears seen (AudioRoute.java:401–405). The log shows LS2 applied again at 7.59 s (live-ls2.log:25). A timeout measured only from the first apply and a test that stops at 2.73 s miss this transition. -> Include the connection callback in the success and timeout traces. Verify that an already confirmed LS2 stays usable and state precisely which request starts the failure deadline.

VERDICT: REVISE

### Planner response (round 1)

F1: rejected - no playback hold. The brief's DW1 asks for exactly this: the headset's SCO "gets the time Android needs to
connect, not refused at the first poll while Android still reports the earpiece". Protecting playback while the earpiece
is reported is the hold/mute that 030's orchestrator dropped because Nicholas wants it simple (030 decisions.md,
2026-11-20). 030's code-review rounds 3-4 (earpiece reported -> speaker at once) are what broke LS2. DW2's "never the
earpiece" is about where a failed headset ends up: the speaker, which step 2's test proves. Routing doesn't depend on
whether a reply is playing, so steps 1-2's success and timeout traces already are the "reply before confirmation" tests.
Risk 1 now gives the exact worst case (30 s from the latest request, see F5), and README says it. A shorter earpiece-only
bound is still a one-constant change if Nicholas wants it (decisions.md); it doesn't block coding.

F2: rejected - no capture gating. Dictation with saved = LS2 has always run in this order: `use()` posted, then the
recognizer or MicServer starts, then the SCO comes up 0.3-2 s later. That is the route that worked before 030 (brief;
research: 030 didn't touch the non-live path, and `Voice.listen` is unchanged). Running `use()` synchronously gains
nothing against a 0.3-2 s SCO setup. Tying ready-to-speak and capture start to confirmation would need new
wait/timeout/cancel code in Voice, ClaudeDictation, CodexDictation and MicServer, which goes beyond the brief's "keep it
simple" and its non-goals. Risk 3 stays. The DW3 phone check adds a short phrase spoken as soon as the mic opens, so the
real size of that risk is seen.

F3: accepted - the plan's "the next poll sees "" and releases it" was wrong: the idle branch doesn't reconcile. Step 3 adds
one branch to the poll's idle path (`AudioRoute.java:461`): no live call seen (`!sawLive`), `applied != null` and
`want()` is "" -> `drop()`. Auto's dictation SCO is released on the first poll after listening ends (<= 300 ms), before a
model's reply can arrive. For a reading inside that window, ReadQueue already asks `watchWins()` again when the watch
find returns (`ReadQueue.java:187`), after `use()`'s `drop()` has run. The DW3 phone check now covers it: LS2 and the
watch both connected, dictate in a voice chat, and the reply read aloud plays in the headset, not on the watch.

F4: accepted - the override applies only to Auto (saved ""), and only to a BLUETOOTH_SCO whose name matches a connected
media headset output (the existing `mediaHeadset`). Watch, wired, USB and BLE stay on Android's routing, as now. Step 3's
test adds Watch + listening -> "" and Auto + listening with only a wired headset -> "". Risk 5 is gone; decision appended.

F5: accepted - the failure deadline runs from the latest accepted request for that device. `live()`'s apply callback sets
`appliedAt` and clears `seen` (`AudioRoute.java:401-405`, unchanged), and during a call every `use()` forces that
request (`:278`): at call start (`CodexLive.java:224`), at page connect (`:771`), and on a setting change. Step 1's
trace now includes the page-connect re-apply over a confirmed LS2: unseen again, earpiece reported 850 ms later (as at
`8.44`) and kept, then LS2 reported and seen. Step 2's timeout trace counts from the re-apply. Risk 1 says so.

## Round 2 - Plan Reviewer (gpt-6.2-astra)

F1 [major] A failed headset can still play a full reply on the earpiece - The 030 decision cited to reject round 1 F1 accepted “up to a fraction of a word” on disconnect. Its later ruling explicitly required safe output during setup (docs/features/030-live-no-earpiece/decisions.md:100,136). With the watch inactive, CodexLive.java:1023 starts remote audio unmuted. The revised plan allows 30 seconds of audible earpiece playback after page connect (plan.md:220–225). Turning LS2 off for the phone check skips this case entirely. -> Keep the setup window for a healthy LS2 and protect phone playback while the earpiece is reported. Exercise an audible reply with LS2 still listed but unconfirmed, through both confirmation and timeout to speaker. Accepting an entire reply on the earpiece requires Nicholas to change DW2.

F2 [major] A single poll can refuse LS2 immediately after it connects - plan.md:58 promises two seconds on a later loss. AudioRoute.java:370–376 marks the route seen but still measures elapsed time from the apply at line 403. If LS2 first confirms at 2.73 s, one earpiece report at 3.03 s immediately refuses LS2 and selects the speaker, even if LS2 returns on the following poll. The page-connect test resets the clock before checking a mismatch and misses this case. -> Exercise that sequence without a second apply. For a confirmed headset still listed, measure the loss interval from its last confirmation and retain the existing fallback for a sustained loss or disconnect.

F3 [major] Dictation can treat an unconfirmed SCO as active for the whole utterance - The non-Live path records the key when Android accepts the request (AudioRoute.java:351–353), then returns on every poll matching that cached key (line 345). If Android continues reporting the handset, the proposed `want()` test still passes for both Auto and saved LS2. This extends beyond the startup delay discussed in round 1. -> Test an accepted request with the handset still reported across multiple polls, followed by LS2 confirmation or timeout. Use the reported route to distinguish pending from active, define bounded failure handling, and make the short headset-only phrase a DW3 pass condition for both settings. [Android communication-device guidance](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager#set-communication-device)

F4 [minor] The Live mic check cannot identify which mic picked up the voice - plan.md:194–197 accepts an applied request, a positive mic peak and audible headset output. The meter samples whichever input stream the page opened (CodexLive.java:999–1005), and the log reads it once every ten seconds (lines 1042–1043). A handset mic can pass, and a working headset can produce a zero sample between words. -> For Auto and saved LS2, place the handset out of earshot and ask Live to repeat a short phrase spoken into the headset. Use the route log and meter as diagnostics.

VERDICT: REVISE

### Planner response (round 2)

F1: partly accepted - the earpiece wait is now 5 s, not 30 s. There is still no playback hold.
- Changed: `grace()` keeps 030's earpiece branch and its 3-arg signature. For a headset not seen yet while Android
  reports the earpiece, it returns a new `EARPIECE_MS` (5 s) instead of 0. The 30 s window stays only while Android
  reports a safe output, as 030 had it. A listed headset whose link never comes now plays at most 5 s on the earpiece
  after the latest request (page connect included), then goes to the speaker. LS2's link came within about 2.7 s in the
  log (applied `0.26`, reported `2.99`), so 5 s covers it with about 2x margin. On main this is one constant in place of
  0, the smallest diff that restores LS2.
- Rejected: protecting playback (hold/mute) while the earpiece is reported. Nicholas dropped it in 030 (030
  decisions.md, orchestrator "plan accepted after round 3, simplified"). The 030 ruling cited at `:136` ("switch to the
  speaker at once" while the earpiece is reported) is exactly what this brief names as the bug (DW1: "not refused at the
  first poll while Android still reports the earpiece"). With 0 s ruled out (the bug) and a hold ruled out, a short
  bound is what's left. I agree that accepting up to 5 s on the earpiece is Nicholas's call, so it is recorded as the
  one non-blocking question (plan Open questions, decisions.md). A shorter bound is a one-constant change.
- Tests: step 2's `aHeadsetPendingWhileTheEarpieceIsReportedFallsBackToTheSpeaker` now has the earpiece reported, not
  slipped at 4 999 ms, slipped at 5 000, refused, and `liveAttempt` taking the speaker and never index 0. Step 1's
  confirmation trace stays.
- New phone check, an audible reply through confirmation: while Live is speaking on the speaker, switch the setting to
  LS2. The voice moves to the headset within a second or two, and the log shows no `slipped from 7:LS2`. A listed
  headset that never connects can't be produced on demand, so the unit test is its proof (Risk 1 says so).

F2: accepted - a confirmed headset's loss now counts from the last poll that saw it.
- A pure `lossFrom(applied, actual, from, now)`, in the same style as `grace` and `refuseSlip`, returns `now` when
  Android reports the applied device and it isn't the speaker, else `from`. Before the slip check, `live()` sets
  `appliedAt = lossFrom(...)`.
- In the sequence you describe (LS2 confirmed at 2.73 s, earpiece reported at 3.03 s, no second apply), the loss is
  300 ms, not 2.77 s, so there is no slip. 2 s of sustained loss still slips, is refused, and takes the speaker.
- A disconnect is unchanged: LS2 leaves the list, and `liveAttempt` applies the next device on that same poll, with no
  slip clock.
- The speaker keeps 030's clock from its apply, so another app's pick over it is still undone at the next poll after
  its first 2 s.
- New step 2 test, `aConfirmedHeadsetSurvivesAOnePollLoss`, with no second apply.

F3: rejected - no pending/active tracking on the dictation path.
- Knowing "pending" would leave Buddy two things to do. Waiting is capture gating (round 1 F2, rejected). Falling back
  is `drop()`, which is Android's routing and the same phone mic a pending SCO already uses. Neither puts the input in
  the headset. Only the link coming up does that.
- The state ends with the dictation. Under Auto it is released at the first idle poll (step 3). A saved pick is
  released at session end, as it has been since 001.
- Accepted from the test plan: the headset-only phrase is now a DW3 pass condition for Auto and for saved = LS2. With
  the phone covered or across the room, a short phrase spoken quietly into the headset after the mic opens must come
  through. The phrase spoken right as the mic opens stays a Risk 3 diagnostic, not a pass condition.

F4: accepted - DW1's phone pass condition, for Auto and for saved = LS2, is now:
- the phone is across the room, out of earshot of a quiet voice;
- Nicholas asks Live to repeat a short phrase spoken quietly into the headset;
- Live repeats it, and its voice is in the headset.

The route log and the `mic peak` line are diagnostics only.

## Round 3 - Plan Reviewer (gpt-6.2-astra)

F1 [major] Buddy can override LS2 after it finally connects - With the headset continuously listed, the new five-second cutoff can refuse LS2 and select the speaker. If Android then reports LS2 at eight seconds, `liveRefused` still excludes it, and the speaker slip handler reapplies the speaker (AudioRoute.java:211,374–399). The log already shows that override at 2.99 s under the old cutoff (live-ls2.log:12–13). Moving the cutoff leaves the same failure possible within Android’s setup window, contrary to DW1 (brief.md:33). -> Test late confirmation after speaker fallback for Auto and saved LS2. When Android reports the still-preferred LS2, let it take the call without requiring a settings change. Keep speaker fallback for a headset that never confirms. [Android communication-device guidance](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager#set-communication-device)

F2 [major] The five-second test ends before speaker playback is confirmed - Step 2 proves which device Buddy requests. Android can still report the earpiece after accepting the speaker request, and Buddy gives the speaker two seconds before retrying it once (AudioRoute.java:229–235,374–405). Phone Live plays the remote track during that interval (CodexLive.java:1023–1026). Even the five-second exposure described in plan.md:266–270 can therefore be exceeded, and DW2 still says “never the earpiece” (brief.md:34). -> Exercise a reply already playing with LS2 pending, then a speaker request accepted while the earpiece remains active. Keep phone playback protected until a safe output is confirmed, or obtain Nicholas’s explicit acceptance of audible earpiece playback and record the agreed condition before approval.

F3 [major] Auto can leave the voice on the watch after LS2 connects - Step 4 checks device selection, but an Auto call already playing on the watch follows a different decision. With the setting unchanged, LiveWatch.poll() checks `pickApplied(saved)` (LiveWatch.java:139–149). Auto’s empty key cannot match the confirmed LS2 key (AudioRoute.java:298–302), so the watch keeps the voice while the phone output stays muted (CodexLive.java:943–947). LiveWatchTest.java:770–776 explicitly expects the watch to keep playing when a headset arrives. This misses DW4’s headset-first order even when Android routes LS2 successfully. -> Add a call-level test that starts on the watch under Auto, then confirms LS2 without a settings change. Use the existing watch handoff when that headset becomes active, preserving explicit Watch selection.

VERDICT: REVISE
