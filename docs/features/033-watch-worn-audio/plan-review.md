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

### Planner response (round 2)

F1: accepted - the check after `openNext` is gone, and so is asking during a call. While a reading's channel is open
the watch's `ReadAloud` is already a foreground service under a wake lock, and during a call a reading is always open
(the next one is opened ahead). It now keeps the off-body listener registered for that time and sends every event
(the first one, then each change) to the phone as `/buddy/worn {worn}`. `LiveWatch.Call.worn(node, false)` on an Auto
call runs the existing `stop()` at once, whatever the utterance state: an idle reading opened ahead is cancelled (a
reply after an off-wrist pause plays on the phone from its first block), a reading under way is closed and the
microphone waits for its answer (the drain is kept), held voice is not forwarded. Placement is unchanged: the phone
plays until a positive answer has arrived and the channel is attached, so unknown is always audible on the phone.
What is left is the sensor's own lag (Android: up to 3 s to notice a removal), which no ask could beat either. Voice
held for the watch at the moment of leaving is dropped, as `stop()` does on a setting change today; recorded as a
risk. Tests added: taken off in a pause (first block and all later ones never reach a reading, `toWatch()` is 0);
taken off with a whole reply queued (never forwarded, nothing more opened); taken off mid-utterance (drain kept).

F2: accepted - freshness is now about the sample, not the delivery. The watch returns `ms`, the time from receiving
the ask to the sensor event; the phone stamps the answer `at = sentAt + ms` on its own clock, the earliest moment the
sample can have been taken, and an answer counts only while `now - at <= 3 s`. A positive sampled at once and
delivered 7 s later is not worn. Reuse is gone: every placement sends its own ask (callers during an ask still join
it, under the same bound), so a reconnect has nothing cached to revive. Tests added: delayed positive (3 s + 1 ms:
not worn); slow sensor (`ms` 4 s, arrives at 6 s: worn); a second placement 1 s after a positive one asks again and
its "not worn" wins.

F3: accepted - nothing in `Call` waits on a worn answer any more, so no stale callback can stop a picked Watch: a push
is judged on arrival against the setting then (a picked Watch ignores it; the last push is kept, so Watch switched to
Auto off the wrist leaves at that poll). For placement, `Worn.find` reads "is Watch the pick" when each answer
arrives, not when the find started: Watch picked while Auto's ask is pending and then "not worn" still gives the node.
`LiveWatch.found` rechecks `watchWins()` and stays on the phone when a headset came meanwhile (the check `ReadQueue`
already makes after its find). Tests for both with the answer held pending.

F4: accepted - Q2 is now a gate in front of step 1: the orchestrator records Nicholas's answer in the brief's
Clarifications before the Coder starts (the Planner may not edit the brief). The plan covers both answers: 030 stands
(recommended, no code), or step 4b makes the earpiece the last resort when Android lists no speaker and no headset,
with the earpiece-only test.

F5: accepted - `why` travels with the reply into `Worn.reply`, and `Worn` logs one line per answer under the tag
`Worn`: `worn`, `not worn`, `stale (age N ms)`, `can't tell: no sensor | refused | no event`, `no reply`,
`send failed`. Test: a refused registration and a missing reply give different lines.

## Round 3 - Plan Reviewer (gpt-6.2-astra)

F1 [blocker] Switching a hand-picked Watch to Auto can keep an unverified watch for the entire call - Explicit Watch bypasses the sensor query. An older companion can play every reading without ever sending a wrist report (`plan.md:50-52,98-103,190`). On the switch to Auto, the existing turn prevents a new find and the cleared `off` flag prevents leaving. The test at `LiveWatchTest.java:238-246` actually requires Watch playback to continue with no wrist report. -> On entry to Auto, require fresh positive evidence for the active watch. Use the phone while that evidence is missing. Split the transition test into a confirmed-worn case and a silent-companion case. In the latter, the next voice block must play on the phone.

F2 [major] A missed removal report can send the next Live reply to the watch after a long pause - The plan relies on the next reading's initial event to recover a lost push (`plan.md:194`). During a pause the next reading is already open. `LiveWatch.java:327-339` forwards resumed speech to that reading before any new registration or wrist check. The proposed pause test injects `worn(false)` directly, so it misses this failure. Wear OS sends these messages on a best-effort basis. -> Give an active Auto call a bounded way to revalidate wrist state after silence. Test a dropped removal message, advance past the freshness bound, then resume speech. The phone must play the first block while wrist state is unknown. [Wear OS Data Layer delivery semantics](https://developer.android.com/training/wearables/data/client-types#message-client)

F3 [major] The proposed wrist-message handler keeps the phone awake for up to 15 minutes after a settings check - `plan.md:65-68` adds every wrist message to `WatchLink.holdWake`. That method acquires the shared 15-minute wake lock, whose early releases belong to tracked chat and call lifecycles (`WatchLink.java:40,251,352,366,370-383`). A settings query has no corresponding release, and its five-second poll renews the lock. -> Hand the wrist message to the main thread without borrowing the chat lock. If a wake lock is needed for that handoff, give it a short independent lifetime and release it after dispatch. Verify that leaving the Auto settings screen leaves no wrist-related lock held.

F4 [major] Read-aloud can choose a worn watch while a Bluetooth headset is connected - The order test in `plan.md:169` supplies an already-decided headset boolean. In the actual read-aloud and settings paths, `headsetIn` ignores connected outputs whenever Android reports a media route. The existing test explicitly returns `NONE` for a routed speaker with connected A2DP (`AudioRoute.java:86-92,129-132,156-162`, `AudioRouteTest.java:34-41`). Auto then asks the watch and can select it, contrary to `brief.md:35-36`. -> Reconcile that concrete case with the connected-headset requirement. Exercise it through Auto's decision and label with a worn watch present. If the existing Android media-route preference is intentional, record the exception before treating the order test as proof.

VERDICT: REVISE
