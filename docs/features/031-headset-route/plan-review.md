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
