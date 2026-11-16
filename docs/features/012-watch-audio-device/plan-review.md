# Plan review: 012-watch-audio-device
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] The FIFO does not preserve speech through cold start or guarantee the fallback notice - `plan.md:48` drains texts into asynchronous producers, while `plan.md:71` deliberately retains a single cold-engine pending slot. Two Watch replies arriving while TTS starts therefore discard the first reply, contradicting brief done-when 2; calling this existing behaviour (`plan.md:188`) does not make the new Watch route deliver it. More immediately, `onPhone` calls `Voice.here(notice)` then `Voice.here(text)` (`plan.md:56`): with cold phone TTS, the text overwrites the notice in `Voice.java:250-265`, so the required notice is never heard. With a warm engine, separate language-detection threads (`Voice.java:272-289`) can enqueue them backwards. Opening multiple watch channels concurrently also makes their arrival order, rather than the text FIFO, determine playback order. -> Specify one ordered dispatch/completion rule for Watch requests and their fallback utterances, preserving accepted texts through engine startup and keeping notice before reply. A single in-flight reading in the existing coordinator is simpler than concurrent streams plus receiver-side reordering; keep this scoped to the new route. Add checks for two cold-start replies and cold/warm unreachable fallback with notice exactly once.

F2 [major] The proposed busy check allows readings over watch speech and dictation - `plan.md:115` assumes `Talk.waiting || Call.on` covers speaker ownership. In fact, `Talk.end()` sets `waiting = false` while its replies continue playing (`wear/src/main/java/app/buddy/assistant/wear/Talk.java:390-408`). A new phone reading can then play over a 003/007 reply. Likewise, `TalkActivity.listen()` launches the recognizer, but `Talk.begin()` is only called after recognition returns (`wear/src/main/java/app/buddy/assistant/wear/TalkActivity.java:174-200`): silencing once on entry does not stop a new reading arriving during dictation. Checking only at channel arrival also misses ownership changes before a queued reading starts. -> Expose a minimal busy predicate covering pending/playing Talk audio and the recognizer lifetime; check it when accepting and starting a reading, and retain the planned interruption on user talk/call. Allow the small Talk change this needs instead of promising Talk remains untouched. Test a reading arriving after `/buddy/done` while Talk still speaks, and one arriving while the recognizer is open.

F3 [major] Producer completion is not evidence that the watch played the reply - `plan.md:103-104` maps every non-refusal channel close after `finish()` to `played`; `PcmOut.finish()` only marks synthesis complete and starts draining (`app/src/watchlink/java/app/buddy/assistant/PcmOut.java:91-98`). Fast phone TTS can finish before the watch plays any sound. A disconnect or the planned code-0 `silence()` then incorrectly reports success and resets the notice episode. On the receiver, unconditional code 0 from `Speaker.ended` (`plan.md:120`) also reports success for an empty stream or AudioTrack failure: `Speaker` reports both `eof` and `closed`, and exposes `sounded()` (`wear/src/main/java/app/buddy/assistant/wear/Speaker.java:24-25,63-65,151-159`). Thus a reachable watch that cannot play can silently lose a whole reply. Google also specifies that disconnects carry app-specific code 0, not a playback acknowledgment ([ChannelCallback reference](https://developers.google.com/android/reference/com/google/android/gms/wearable/ChannelClient.ChannelCallback#onChannelClosed(com.google.android.gms.wearable.ChannelClient.Channel,int,int))). -> Make receiver-confirmed playback completion distinct from interruption/disconnect; send NOT_PLAYED on input/player failure before any audio sounded, and never replay after sound or intentional hush. Use the existing `Speaker` evidence, as Talk already does at `Talk.java:511-527`. Replace the tests that currently enshrine “after finish means played” with empty stream, first-write failure, disconnect-after-synthesis, normal playback, and hush cases.

F4 [major] A writer failure can beat the explicit refusal and suppress phone fallback - `plan.md:34-36` permits only one terminal event, while `plan.md:98-107` combines PcmOut's `onBroken` with the asynchronous channel-close callback. When the watch closes a busy reading with NOT_PLAYED, a concurrent write can throw first; `PcmOut.java:177-178` invokes `onBroken` immediately. If that settles the reading as `broken`, the later code-1 callback cannot deliver `refused`, so nothing is heard on either device. The planned tests exercise close codes and overflow separately, not this race. -> Define how transport-write failure and the authoritative refusal callback settle one outcome without discarding NOT_PLAYED. Keep local overflow/cancellation distinguishable from peer closure, and add a deterministic test delivering writer failure and NOT_PLAYED in both orders; each must produce one phone fallback and no watch replay.

F5 [major] TTS failures before a completion callback can leave the new route active indefinitely - `plan.md:65-74` handles only progress callbacks and treats `onError` like normal completion; it does not settle engine-init failure, synchronous `synthesizeToFile` rejection, or a producer that never completes. `Voice.java:252-260` currently drops pending work when init fails, which would strand its new sink. The claimed 11-minute bound does not cover this: PcmOut schedules abandon only inside `finish()` (`PcmOut.java:92-96`), not on open. Android documents a separate ERROR/SUCCESS return for queuing synthesis ([TextToSpeech reference](https://developer.android.com/reference/android/speech/tts/TextToSpeech#synthesizeToFile(java.lang.CharSequence,android.os.Bundle,java.io.File,java.lang.String))). -> Specify settlement and cache/tap cleanup for init failure, immediate rejection, asynchronous error, and producer timeout; do not signal a successful empty reading. Bound the whole active reading independently of producer completion, and route known-no-audio failures through the single-owner fallback policy without retry loops. Test these paths and assert that active state, channels, and temporary files are released.

F6 [major] Stop needs to invalidate asynchronous work, not only existing streams - `plan.md:61-63` clears the FIFO and cancels streams but gives no cancellation rule for an in-flight `find`, cold-engine pending work, or the language-detection callback retained from `Voice.say`. The latter posts new speech after work on another thread (`Voice.java:272-289`), while current `stopSpeaking()` only stops already submitted speech (`Voice.java:314-316`). A late lookup can interfere with a new request after stop, and a late phone fallback can begin after hush/dictation. This undermines the plan's stated stop guarantee and existing voice interaction. -> Give new-route requests a cancellation generation/token checked after discovery, TTS initialization, language detection, and before fallback/submission; clear their pending work on stop, and release late-arriving resources. Add coordinator tests for stop during discovery, stop during cold start/language detection, and stop followed immediately by a new reading. No generic cancellation framework is needed.

F7 [minor] The F-Droid DEX-string test would fail even with correct flavor isolation - `plan.md:160` expects zero occurrences of `WatchVoiceLink` in F-Droid DEX, but `plan.md:28` deliberately puts `Class.forName("app.buddy.assistant.WatchVoiceLink")` in main, which F-Droid compiles with minification disabled (`app/build.gradle.kts:72-74`). The class-name string therefore remains even though the class definition is absent. -> Inspect DEX class definitions rather than arbitrary strings, and verify that F-Droid's actual choices omit Watch. Keep the github counterpart checking that the implementation class exists.

VERDICT: REVISE

### Planner response (round 1)
F1: accepted - one reading in flight. A new pure-Java core, `ReadQueue` (main), keeps the FIFO and starts the next text
only after the current one has settled (played, broken, cap reached, or read on the phone up to its TTS `done`). The
notice and the reply stay two phone utterances, but the reply goes to TTS only after the notice's `done`, so cold and
warm engines take the same path. Voice's new route has a cold-start slot of its own (at most one request, because only
one reading is in flight), so the existing single `pending` slot keeps its rule and no accepted text is dropped. The
watch keeps no queue: a new channel replaces the old one. `ReadQueueTest` covers two replies during a cold start,
notice-then-reply order, the notice exactly once, and the reset after a played reading.

F2: accepted - busy = `Talk.busy()` (new one-liner `waiting || instance != null`, true until Talk's last reply has been
said) `|| Call.on || TalkActivity.dictating()` (`listen()` stamps the time, `onActivityResult` clears it, and it counts
for 60 s at most so a recognizer left behind by Home can't block readings forever). It is checked when a channel arrives
and again in `onStartCommand` before playing; `silence()` on listen/call stays. The plan no longer claims Talk is
untouched. Added device checks: a reading arriving after `/buddy/done` while Talk still speaks, and one arriving while the
recognizer is open. There is no watch unit test because the wear module has no test setup (research.md "Tests").

F3: accepted - the watch now confirms playback explicitly. Close code 2 (PLAYED) is sent only when `Speaker` ended by
`eof` and `sounded()`. Code 1 (NOT_PLAYED) is sent when nothing sounded: an empty or silent stream, a failed
`getInputStream` or AudioTrack, busy, or a refused service start. Code 0 means it broke after sound, or a hush. On the
phone, 2 → played, 1 → refused, anything else (a disconnect's code 0 included) → broken. Synthesis finishing no longer
implies anything. `ReadStream` also refuses on its own when the producer closes after writing nothing. The tests that
assumed "after finish means played" are replaced.

F4: accepted - PcmOut's `onBroken` (write failure, overflow) no longer settles the reading. `ReadStream` waits up to 3 s
(`CLOSE_GRACE_MS`) for the channel's close code and only then calls it broken, so a NOT_PLAYED that arrives after a
failed write still wins. `ReadStreamTest` delivers write failure and code 1 in both orders and expects one `refused`.

F5: accepted - `Voice.routed` calls `done` exactly once on every exit: engine init failed (new `ttsFailed`),
`synthesizeToFile`/`speak` returned ERROR, or `onDone`/`onError`/`onStop`. It never closes the stream. `ReadQueue` closes
the stream on `done`. An empty stream is a refusal, the refusal goes to a phone utterance, and that utterance's `done`
(or its failure) ends the reading. The chain is bounded (ChatGPT voice → phone TTS to the watch → phone) with no retry.
`ReadQueue` also has a whole-reading cap, `READ_MAX_MS` = 11 min, counted from the reading's start and not tied to
`finish()`: it cancels the stream, stops the producers and moves on. There is one fixed temp file (`cache/read.wav`,
since one reading is in flight), deleted at `done` and on drop, and the tap entry is removed with it. `ReadQueueTest`
covers no-audio, the cap and the cleanup calls. Voice's own exits are checked in review, because TextToSpeech can't run
in JVM tests.

F6: accepted - each reading is an object, and every callback (find, stream events, producer `done`, ChatGPT fallback,
cap) is dropped unless its reading is still the current one and its stream the current stream. Routed Voice requests
carry a generation that `dropRouted()` bumps (called by stop and on refused/broken/cap). The generation is checked after
engine init and after language detection, before anything is submitted, and the routed slot is cleared. Tests: stop
during find, stop during a pending TTS request, and stop immediately followed by a new reading (Setup's device tap does
exactly this, `SetupActivity.java:609-610`).

F7: accepted - grep the DEX for the type descriptor `Lapp/buddy/assistant/WatchVoiceLink;`. It is present only when the
class is defined; the reflection string is the dotted name. Expected: F-Droid 0, github ≥ 1. New `WatchVoiceTest`
(testGithub) checks that `link()` loads, which catches a rename that would silently hide Watch, and that the loader
returns null for a missing class, which is the F-Droid path where `choices` leaves Watch out.

## Round 2 - Plan Reviewer (gpt-6-astra)

Reviewed at `e3286e5`. `plan.md` and `decisions.md` are unchanged from the Planner's `92fd076` commit; no Round 1 Planner response is recorded. The findings below retain their Round 1 identifiers and remain open after checking the current code. No rejected finding is being reopened.

F1 [major] Speech ordering and cold-start loss remain unresolved - `plan.md:48-58,71-73` still dispatches multiple asynchronous producers and uses separate notice/reply calls with one cold-engine pending slot. `app/src/main/java/app/buddy/assistant/Voice.java:250-265` overwrites that slot, and `:272-289` submits utterances after independent language-detection threads. Thus a reply or the required notice can disappear, and enqueue order can reverse. -> Keep one new-route reading in flight through playback/fallback completion, preserve accepted texts during initialization, and sequence the notice before the reply. Test two cold-start replies and notice ordering with cold and warm TTS.

F2 [major] The busy predicate still misses existing watch audio and dictation - `plan.md:115,122-124` checks only `Talk.waiting || Call.on` and silences readings once when entering listen/call. `wear/src/main/java/app/buddy/assistant/wear/Talk.java:399-408` clears `waiting` before pending audio finishes; `wear/src/main/java/app/buddy/assistant/wear/TalkActivity.java:174-200` opens recognition before starting Talk. A new reading can therefore overlap either activity, violating the regression requirement. -> Cover Talk's pending/playing audio and the recognizer lifetime in a minimal busy predicate; check it on acceptance and before playback. Test arrivals after `/buddy/done` while audio remains and during recognition.

F3 [major] Playback success is still inferred from synthesis completion - `plan.md:103-104,120-123,169` maps a non-refusal close after `finish()` to `played`, including intentional hush and playback failure. `app/src/watchlink/java/app/buddy/assistant/PcmOut.java:91-98` only starts draining at `finish()`; `wear/src/main/java/app/buddy/assistant/wear/Speaker.java:151-159` also calls `ended` after errors. Disconnects carry app-specific code 0 ([ChannelCallback reference](https://developers.google.com/android/reference/com/google/android/gms/wearable/ChannelClient.ChannelCallback)). -> Distinguish receiver-confirmed playback from disconnect/interruption; use `Speaker.sounded()` and its end reason to report known-no-audio failure, as `Talk.java:511-527` does. Test empty audio, first-write failure, disconnect after synthesis, normal completion, and hush; never replay after sound or intentional hush.

F4 [major] A write failure can still consume a later refusal - `plan.md:34-36,98-107` requires one terminal event without specifying arbitration between PcmOut failure and channel-close callbacks. `app/src/watchlink/java/app/buddy/assistant/PcmOut.java:177-178` immediately invokes `onBroken` on write failure, so it can settle `broken` before NOT_PLAYED arrives and suppress the required phone fallback. -> Specify settlement that preserves an authoritative refusal despite a preceding writer failure; distinguish local overflow/cancellation. Test both event orders and require exactly one fallback.

F5 [major] TTS startup and producer failures still lack bounded settlement - `plan.md:65-74` handles progress callbacks but leaves init failure, immediate synthesis rejection, and missing completion unspecified. `app/src/main/java/app/buddy/assistant/Voice.java:252-260` discards pending work on failed initialization; `app/src/watchlink/java/app/buddy/assistant/PcmOut.java:92-96` schedules abandonment only after producer completion. The synthesis call also returns a separate queueing error ([TextToSpeech reference](https://developer.android.com/reference/android/speech/tts/TextToSpeech#synthesizeToFile(java.lang.CharSequence,android.os.Bundle,java.io.File,java.lang.String))). -> Settle each failure, clean taps/files/channels, and bound the active request independently of `finish()`. Test init failure, synchronous rejection, asynchronous error and timeout, without success for empty audio or fallback retry loops.

F6 [major] Stop still leaves asynchronous requests able to restart speech - `plan.md:61-63` cancels streams and clears the FIFO without invalidating discovery, engine initialization or language detection. `app/src/main/java/app/buddy/assistant/Voice.java:272-289,314-316` demonstrates why stopping submitted TTS does not prevent a pending worker from submitting later. -> Use a request token/generation for the new route, checked at each asynchronous continuation and before fallback/submission; discard late resources and clear owned pending work. Test stop during discovery, cold start and detection, plus stop immediately followed by a new reading.

F7 [minor] Flavor verification still searches the wrong DEX evidence - `plan.md:160` still expects the reflection target string to be absent from F-Droid, although `plan.md:28` puts it in main and `app/build.gradle.kts:68` disables minification. -> Check class definitions and actual device choices instead of arbitrary string occurrences. The corrected build-file citation is line 68.

Review was static: no implementation, build or device test was performed. All six major findings remain open; the plan cannot yet substantiate the required playback, fallback and regression guarantees.

VERDICT: REVISE

### Planner response (round 2)
F1-F7: accepted. Round 2 repeats round 1's findings because no response had been recorded yet. Each finding's answer is
under "Planner response (round 1)" above, and `plan.md` and `decisions.md` now include them (F7's build-file citation
is line 68).
