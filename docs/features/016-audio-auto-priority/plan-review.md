# Plan review: 016-audio-auto-priority
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] The route is fresh at submission, not when a queued reading starts - `plan.md:76-83` resolves `target` in `Voice.speak` and claims ReadQueue's later watch lookup supplies the playback-time check. But `ReadQueue.java:122-124,140-153` can hold an item behind another reading and then checks only watch reachability, never the saved device or connected headsets. Example: enqueue two Auto readings without buds; while the first plays on Watch, reconnect the buds (or the missing saved headset). The second still starts on Watch, violating `brief.md:20-21`. The same race exists during the asynchronous find. -> Re-evaluate the effective device when a waiting reading becomes current and after the find returns, before committing it to Watch; route it to the phone/headset path when that now wins. Specify how the reverse transition during delayed phone startup is handled. Add transition tests with a queued item and a pending find; the current truth-table tests cannot establish playback-time freshness.

F2 [major] A connected output is not necessarily the output Android will use - `plan.md:41-42,56-67` treats any listed SCO headset as proof that normal media playback uses a headset, and even replaces an API 33 routing result containing only Speaker with the first connected headset from `getDevices`. Then `plan.md:69-70` leaves routing unchanged. Android distinguishes connected devices from the devices selected for particular attributes ([AudioManager reference](https://developer.android.com/reference/android/media/AudioManager#getAudioDevicesForAttributes(android.media.AudioAttributes))); a call-only Bluetooth connection does not establish a media route, and SCO needs an audio connection ([SCO documentation](https://developer.android.com/reference/android/media/AudioManager#startBluetoothSco())). Buddy's phone TTS uses `USAGE_ASSISTANT` (`Voice.java:280-282`) and Auto calls `drop()` (`AudioRoute.java:121-124`). Thus the plan can skip a reachable Watch and report a headset while playing on Speaker. On API 30-32, taking the first connected device also cannot identify the active one among multiple outputs. -> Separate connected-device detection from actual-route labeling. Preserve a successful platform routing answer instead of overriding it with the connected list; define and verify the treatment of communication-only headsets before claiming headset priority is satisfied. Test the case where the routed output is Speaker but a SCO device is listed, and two connected headsets whose enumeration order differs from the active route. Delegating normal media routing to Android can stay; the unsupported equivalence cannot.

F3 [major] Immediate quiet fallback completion can discard a reply during TTS startup - `plan.md:87-89,135-138` calls `Voice.here` and immediately ends the reading on the assumption that the phone path queues text. For the phone voice, `Voice.java:261-270` instead assigns one `pending` string while TTS initializes; two quiet no-watch results before initialization completes overwrite the first reply. `CodexSpeech.java:330-332,347-357` explicitly compensates for this same limitation by joining phone fallbacks. Existing ReadQueue phone fallback waits for completion (`ReadQueue.java:191-192`), so the new path loses that protection. -> For items using the phone voice, reuse the existing waitable phone path while suppressing the notice; retain ChatGPT's selected voice and its existing queue for ChatGPT items. Alternatively make the handoff retain all pending texts. This does not require the rejected change of replacing ChatGPT's voice with TTS. Add a test covering two quiet fallbacks with a cold phone engine and assert both texts survive in order, rather than merely logging two `here` calls.

F4 [major] The open Settings page can show Watch forever after it disconnects - `plan.md:96-104` queries `autoNow` when rendering and relies on the existing AudioDeviceCallback for further renders. The Watch route is explicitly not an Android audio device (`AudioRoute.java:19`, `WatchVoiceLink.java:44-54`), so losing or regaining Wear capability need not produce that callback. Expiring the five-second memo does not cause a caller to run again. With the voice page left open, its new “Auto is using Watch now” text can therefore remain indefinitely while subsequent replies use Speaker, failing `brief.md:22`. -> Refresh the status while this page is visible, or subscribe to Watch reachability changes, with lifecycle cleanup and protection against obsolete async results. Add a verification case that leaves the page open while Watch becomes unreachable and reachable without any Android headset change.

F5 [minor] The live-voice chip would announce a Watch route that live voice never uses - `plan.md:107-109` applies the read-aloud `autoNow` result to every VoiceBar. The same component is constructed for live voice (`VoiceBar.java:38-39,91-105`), while `plan.md:30-31` explicitly keeps live voice off Watch. With Auto, no headset and a reachable Watch, the live call would say “Auto: Watch” while using the phone. -> Keep the live/recording chip tied to its applicable Android route, or clearly qualify a read-aloud-only target instead of presenting it as the current call device. Include this case in the display check.

F6 [minor] The new Auto status remains hidden on F-Droid API 30 - `AudioRoute.java:35-44` returns only Auto on API 30 without the Watch link. `SetupActivity.java:615` hides the entire device row when that is the only choice, and `VoiceBar.java:168` hides the chip. The plan adds status inside these existing surfaces without changing their visibility rules, despite explicitly supporting API 30 (`plan.md:60-61`) and promising the same status without Watch on F-Droid. -> Show the read-only Auto status even when there is no device to select; the redundant selector can remain hidden. Add an API 30/no-link display check.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - A reading now decides watch-or-phone when it becomes current and again when its find returns
(`Out.watchWins()`, which re-runs `AudioRoute.target`). A queued or pending reading therefore goes to a headset, or to
a pick that has reconnected, if one is there by then. While the queue is busy, new readings line up behind it
(`Voice.speak`), so order holds across the switch. Reverse transition (phone still starting or speaking): an Auto
(quiet) reading goes to the watch only when the phone isn't speaking (`Out.phoneBusy()`), otherwise it follows on the
phone. Phone-voice fallbacks are waited for anyway (F3). A picked Watch ignores `phoneBusy`. New tests: a queued reading
with a headset arriving while the first plays on the watch; a pending find with a headset arriving; a quiet reading
while the phone is busy; a picked Watch while the phone is busy.

F2: accepted - Detection and label now follow one rule. On API 33+ Android's own answer
(`getAudioDevicesForAttributes(USAGE_ASSISTANT)`) wins, even when it names only the speaker. `getDevices` is used only
when there is no answer (API 30-32, or an empty list), and then the label is the generic "Headset", because Buddy can't
see which one Android uses. `BLUETOOTH_SCO` is no longer a headset type: a call-only (HFP) link isn't a media output, so
Auto treats it as not connected (watch, then speaker). Picking it (001) still routes there through call mode. This also
fixes a case the plan missed: the Galaxy Watch's own call link is listed as SCO and would have blocked the watch step.
New pure `AudioRoute.headsetIn(routed, connected)`, unit-tested for: routed speaker with SCO listed → none; two
headsets, the active one second in enumeration → the routed one; no answer and SCO only → none; no answer and A2DP →
an unnamed headset. Device check added: Buds2 with "Media audio" off in Bluetooth settings → Auto uses the watch.

F3: accepted - A quiet fallback in the phone's voice now reuses ReadQueue's waitable path (`out.tts(text, null, done)`
→ `Voice.routed`, one request at a time), without the notice. Only ChatGPT-voice readings go to `Out.here`, ending at
once. CodexSpeech's queue keeps them in order and its `fail()` joins the phone fallbacks. ChatGPT's voice is kept. New
test: two quiet phone-voice fallbacks; the second isn't handed over until the first's done; both arrive, in order.

F4: accepted - While the Auto note is on screen, Setup asks `autoNow` again every 5 s (posted in render, removed in
onPause, restarted by onResume's render). A result is applied only for the newest ask and only while the note is
attached. The 5-s display memo is dropped: it existed only for the chip (F5). Device check added: page left open, watch
Bluetooth off then on, no headset change → the note follows within ~10 s.

F5: accepted - The voice bar chip is out of this feature and stays as it is today (the pick's label, or "Auto"). The bar
is a dictation/live-voice surface. Auto's watch step is read aloud only, and Auto's mic is Android's default (Risk 4),
so naming an output there would mislead either way. The done-when asks for the setting, and Setup plus `buddy_settings`
cover it. This drops the chip width change and the `audio_auto_now` string.

F6: accepted - Setup shows the Audio device label and the Auto note whenever the voice page is shown. The segments
still need two or more choices. Proof: code review plus CI's F-Droid build. There is no API 30 device or emulator here,
and the test plan says so.

## Round 2 - Plan Reviewer (gpt-6-astra)

F1 [major] Phone-side queued readings still keep a stale route - Round 1 F1 is only partly resolved. `plan.md:90-92` bypasses ReadQueue when a headset currently wins, and `plan.md:101-115` sends a new Auto reading to the phone immediately whenever the phone is busy. Example: Auto reads a through the buds in ChatGPT's voice; disconnect the buds while a is playing, with Watch reachable; submit b before a finishes. The proposed `phoneBusy` check sends b through `Out.here` and ends its ReadQueue item. `CodexSpeech.java:89-93` queues b with a null sink; `:242-251,303-312` later starts it on the phone without any route check. Thus b starts on Speaker even though Watch has priority, violating `brief.md:20` and the plan's own promise that the next reading switches (`plan.md:210-212`). The same happens if b was submitted while the buds were still connected and was queued directly in CodexSpeech. This is a whole-reading delay, not the small language-detection gap accepted in Risk 7. -> Keep an unstarted Auto/fallback reading eligible for routing until the previous phone reading finishes, then re-evaluate the order. A small phone-completion handoff to ReadQueue, or a route-selection hook at the phone queue's dequeue point, can preserve ChatGPT's voice and order without moving an already playing reading. Cover both cases (buds removed before b is submitted, and after b is queued), asserting that a finishes once and b starts on Watch. A fake `phoneBusy=true → here` test currently enshrines the wrong behavior; include the phone queue's eventual start in the test.

Round 1 F2-F6 are addressed by the revised plan. Review was static; no implementation or device tests were run.

VERDICT: REVISE

### Planner response (round 2)

F1: accepted - Readings no longer wait with an old answer in the phone's own queues. In the github build every
reading goes through ReadQueue (`Voice.speak`: link → `WatchVoice.speak(c, clean, quiet)`, else `here` as today), so
none skips it when a headset wins. A quiet reading that becomes current while the phone speaks (`phoneBusy`) now
**waits** instead of going to the phone. It stays current, so later readings wait behind it, and ReadQueue asks again
every 300 ms (`out.after`). Once the phone is quiet it decides by the order. The same re-decision runs when a find
returns and `phoneBusy` or `watchWins` changed meanwhile. So in both of your cases (buds removed before b, or after b
was submitted) b goes to the watch once a is done, and a reaches the phone once. Phone-voice readings are waited for
anyway (round 1 F3). ChatGPT-voice readings still end at once on hand-off, and the next one waits for that call to
close. A picked Watch ignores `phoneBusy` (012 unchanged). The round 1 test that sent b to the phone while it was
busy is replaced. New tests, with the fake's phone queue (`here`) in the log: the route changed before b; the route
changed after b, during the wait; still the phone's after the wait (`here b`, no find); busy after the find (a
second find, then the watch). Each asserts `here a` once and no `here b`. Device check added: ChatGPT voice, two
replies, buds taken out during the first → the second plays on the watch. Cost, recorded as Risk 9 and in
decisions.md: overlapping ChatGPT readings no longer share one call (a connect pause between them). A route check at
CodexSpeech's dequeue would keep the shared call, but it's more code in CodexSpeech's threaded part, so it is the
follow-up if the pause is noticeable. Risks 6 and 7 updated (the 11-min cap bounds a wait; the gap is now only a
ChatGPT-to-TTS failure).

## Round 3 - Plan Reviewer (gpt-6-astra)

No open blocker or major findings.

Round 2 F1 is addressed by `plan.md:90-120,177-186,198`: every github-build reading enters ReadQueue, a quiet reading waits while the phone is busy, and routing is re-evaluated after that wait and after the asynchronous watch lookup. The proposed busy check matches the current ChatGPT voice lifecycle: `CodexSpeech.java:76-77,89-93,150-154` makes it active synchronously on handoff, including call startup; `:303-310,360-375` keeps it active until completion and teardown. The next reading therefore stays eligible for routing instead of entering the phone queue with the previous route. Planned tests cover headset removal both before and after the next reading is submitted, plus a busy phone when the lookup returns. Phone-voice readings retain the waitable completion path (`Voice.java:412-440`).

Round 1 F2-F6 remain addressed. Each done-when item has planned unit, device or CI evidence (`plan.md:195-205`), including selected-device reconnection, visible Watch status refresh and the no-link build. The API 33 routing query and empty-result fallback agree with the [Android AudioManager reference](https://developer.android.com/reference/android/media/AudioManager#getAudioDevicesForAttributes(android.media.AudioAttributes)).

Review was static; no implementation, CI or device tests were run. The documented FreeCom availability uncertainty, ChatGPT-to-TTS startup gap and extra connection pause remain verification risks, not newly discovered blocker/major findings in this final round.

VERDICT: APPROVE
