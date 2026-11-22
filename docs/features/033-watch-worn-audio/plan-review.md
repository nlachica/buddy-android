# Plan review: 033-watch-worn-audio
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6.2-astra)

F1 [blocker] Wrist changes and reconnects can reuse the previous worn state - `brief.md:30-34` requires a report on change, start and reconnect. `plan.md:11-15` substitutes a query at each routing decision, but `plan.md:42-45` skips that query for 30 s. Take the watch off immediately after a positive answer, disconnect and reconnect the same watch, then start a reading. It can still play on the table. The manual test at `plan.md:122` waits out the exact window that exposes this. -> Specify the change and reconnect reporting lifecycle and invalidate the previous connection's answer. Test removal and same-node reconnect inside the freshness window. If on-demand sampling is the agreed alternative, record that clarification and require a fresh answer at each new Auto placement, sharing an in-flight sample across concurrent callers.

F2 [blocker] The last-resort earpiece case currently ends the call - `brief.md:16-18,35-36` puts the earpiece last. `AudioRoute.liveDevice` returns `NONE` for an earpiece-only list, `AudioRoute.live` then ends the call, and the exact test cited as proof expects `NONE` (`AudioRoute.java:187-202,450-453`, `AudioRouteTest.java:353-356`, `plan.md:121`). -> Reconcile the conflicting acceptance checks explicitly. For the stated order, cover Auto with only an earpiece available and with both speaker and earpiece available. Preserve 030/031's handling of a listed speaker slipping or being refused, and leave explicit choices alone. If the older unconditional Live rule must stand, record the exception in the brief.

F3 [major] An active Auto call can keep opening channels on an off-wrist watch indefinitely - `plan.md:81-84` only checks worn in `find`. After each played utterance, `LiveWatch.java:268-275` calls `openNext` directly. Even switching from explicit Watch to Auto while off-wrist can leave the existing turn active because `decide()` sees the watch link and an existing turn (`LiveWatch.java:142-149,165-169`). -> Re-evaluate Auto eligibility on that setting transition and before routing subsequent utterances when the worn state has changed or expired. Reuse the existing return-to-phone and microphone-drain behavior. Test a worn-to-off transition across two utterances and explicit Watch switching to Auto while off-wrist.

F4 [major] Both deadlines expire before a valid sensor reading can arrive - `plan.md:58-71` gives the sensor 2 s and the phone 4 s. Android allows 5 s from activation for the initial reading, before Data Layer return time. A compliant worn watch can repeatedly return unknown. The settings poll also discards a callback once the next 5 s poll starts (`SetupActivity.java:128-137`). -> Budget the full initial-event window plus delivery time, including the wake lock. Keep a settings query in flight until it completes or times out. Test an initial event arriving near 5 s and a silent watch. [Android SensorEvent reference](https://developer.android.com/reference/android/hardware/SensorEvent#values)

F5 [major] The permission fallback can ship without a working Galaxy Watch path - Samsung's off-body tutorial specifies a runtime body-sensor grant. `plan.md:126-136` says to stop release on failure, while the recommended answer at `plan.md:146-153` allows shipping the phone-only outcome. The watch targets API 36 (`wear/build.gradle.kts:18-19`). -> Move the paired-watch, screen-off check ahead of sign-off. If an ungranted app cannot read the sensor, include the minimum grant flow required on the affected OS in this feature and verify both granted and denied cases. [Samsung Galaxy Watch off-body tutorial](https://developer.samsung.com/sdp/blog/en/2023/06/14/detect-when-a-galaxy-watch-is-being-worn)

F6 [major] A late worn reply can satisfy a newer query - `plan.md:58-60,78-79` matches replies to all waiters for a node. If query A times out and its positive reply arrives while query B is pending, the old reading can authorize playback and get a fresh phone timestamp. -> If retaining request/reply, echo a per-request ID on the same path, accept a reply only for the pending node and ID, and test timeout A → start B → late positive A → off-wrist B.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - on-demand sampling stays (the orchestrator's run instruction allows it; `plan.md` Goal gives the
clarification line for the brief), but the 30 s reuse is gone. An answer is reused for 3 s only, which is Android's own
bound for the sensor to notice a removal, so a reused answer is never older than a new sample could be. Past 3 s every
placement asks again; callers during an ask join it (one message). A reconnect cannot revive an answer: nothing older
than 3 s is ever used and a reply must carry the pending ask's id (F6). Tests added: an answer 3 s + 1 ms old is not
used and the new "not worn" wins; two callers share one ask. The manual check no longer waits out a window (take the
watch off, 6 s later Auto is on the speaker). Not accepted: a push on change or reconnect from the watch; it needs the
permanent service the on-demand design exists to avoid.

F2: accepted as a reconciliation, no code change - the brief says both "earpiece only when no speaker route is
possible" and "030 stays, 030/031 tests still pass, AudioRoute is not redesigned"; 031's test pins earpiece-only to
`NONE` (the call ends). The plan now states the reading: Auto never picks the earpiece while a speaker route exists
(both listed: speaker), and a Live call with no speaker ends as 030 decided. The test plan cites both existing
assertions for what they prove and no longer calls `NONE` "the earpiece". Whether the earpiece should become a real
last resort is Nicholas's call (plan Q2, recommendation: keep 030); the plan gives the clarification line for the brief.

F3: accepted - an Auto call now asks "still worn?" on a setting change while the voice is on the watch and after each
played utterance; "no" or "can't tell" runs the existing `stop()` (voice back on the phone, microphone waits for the
watch to be quiet). One `default` method on `LiveWatch.Out` (a picked Watch always answers yes), so the existing tests
and their fake stay. Both requested tests added. Decided simply: once off the wrist the call stays on the phone;
putting the watch on mid-call is not followed.

F4: accepted - the watch waits the full 5 s for the first event under an 8 s wake lock; the phone waits 8 s (5 s plus
delivery both ways and the service start). The settings note no longer starts a new ask while one is in flight, and
the answer goes to the note on screen. Tests: a reply just before 8 s counts, a silent watch answers "not worn" at 8 s.

F5: accepted in part - the on-watch check moves ahead of sign-off and blocks it: a probe (`dumpsys sensorservice` on
the watch, possible whenever the watch app is installed over adb) as step 0 or the Verifier's first check, then worn +
screen off must give "Watch". If the sensor needs a permission the feature is not done: it goes back to the Planner
and the grant flow joins this feature before any release (Q1's recommended answer now says so; "ship the phone-only
outcome" is gone). Not accepted: building the grant flow now. The run instruction says to report unknown and ask
Nicholas rather than build it unless trivial; it is not (a runtime grant, renamed on Wear OS 6), and Android's pages
list no permission for this sensor. The unknown reply now carries a reason so the gate is diagnosable from the phone.

F6: accepted - each ask has an id, the watch echoes it, and only the pending ask's node and id are accepted. The ask,
timeout and matching move into the Google-free `Worn` class so the requested test (A times out, B asked, A's late
"worn" ignored, B's "not worn" decides) runs in `src/test`.

## Round 2 - Plan Reviewer (gpt-6.2-astra)

F1 [blocker] A whole Live reply can play on the table - The new check runs *after* `openNext` (`plan.md:90-96`). That method sends all held audio and can close the next reading immediately. An existing test demonstrates that an entire second utterance can already be queued when the first reports played (`LiveWatch.java:195-205`, `LiveWatchTest.java:303-313`). There is also no check when speech resumes on an idle, already-open channel. Take the watch off during a long pause and the next reply can finish there before another check runs. -> Specify when a fresh positive answer authorizes forwarding audio. Keep the phone audible when eligibility is unknown, preserve the microphone drain for audio already sent, and test both a fully queued reply and a reply after an off-wrist pause. Assert where the audio plays, including its first block.

F2 [major] A late positive answer gets a new three-second lifetime - The revised test accepts a reply at 8 s minus 1 ms and the cache starts its clock on receipt (`plan.md:40-46,143`). A watch can sample immediately, be taken off, and have its positive reply delivered seven seconds later. The node and request ID still match. A same-node reconnect can also reuse a positive answer inside the cache window without contacting the watch (`plan.md:42-47`). Android's three-second bound concerns the sensor detecting removal. It places no bound on message delivery. -> Define freshness across sampling, delivery and reuse. For example, have the watch return its elapsed time from request receipt to sampling so the phone can conservatively bound sample age using its own request clock. Invalidate cached positives across reconnects or require a new answer for a new placement. Test a delayed positive and an off-wrist same-node reconnect inside the reuse window. [Android SensorEvent reference](https://developer.android.com/reference/android/hardware/SensorEvent#values)

F3 [major] A pending Auto check can override a hand-picked Watch or a newly connected headset - `plan.md:90-93` guards the worn callback by turn. Watch and Auto can share that same turn across a setting change (`LiveWatchTest.java:238-246`). Select Watch while Auto is waiting, then deliver the old negative reply. It can still call `stop()` on the explicit Watch. Conversely, if a headset connects during the initial worn query, `found` opens the watch without checking whether it still wins (`LiveWatch.java:183-192`). -> At completion, validate the current routing choice as well as the turn. Restart the decision when its inputs changed. Test both changes with the worn reply held pending.

F4 [major] The earpiece acceptance case still awaits a decision - Q2 now identifies the conflict, but its recommended outcome ends the call. The recorded ask puts the earpiece last, and the brief's Clarifications section is still empty (`brief.md:10,16-18,35-36,42-43`, `plan.md:16-18,178-183`). -> Have the orchestrator record Nicholas's Q2 answer before coding. If he keeps the stated last resort, plan and test the Auto case where Android lists only an earpiece. If he keeps call termination, record that exception in the brief so the verifier has a single expected outcome.

F5 [minor] The phone drops the sensor diagnostic needed for sign-off - The watch returns `why` for an unknown state, but the proposed phone handler passes only node, ID and nullable worn state (`plan.md:54,64-66,73-75`). All three sensor failures therefore look identical in the phone log that the verifier is told to inspect (`plan.md:149-152`). -> Carry the reason through the matched reply and log it on the phone. Check that a refused registration and a missing reply yield distinguishable diagnostics.

VERDICT: REVISE
