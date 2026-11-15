# Plan review: 007-watch-live-voice
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Silent PCM can win the speech race without saying anything - `plan.md:59` explicitly streams silence continuously, but `plan.md:148-152` assigns a reply to the voice when its "first audio plays" and suppresses fallback thereafter. Thus a connected session can deliver zero samples, fail before speaking, and leave the reply unsaid even when `/buddy/say` arrives. The existing phone rule measures actual sound (`app/src/main/java/app/buddy/assistant/CodexSpeech.java:254-257`), not receipt of PCM. -> Define the winner as the first audible samples actually played on the watch, using the Speaker's sound detection and playback position; silent buffers must neither commit the voice nor cancel the no-speech deadline. Test silence followed by EOF, `/buddy/say`, and a stalled stream: each must produce exactly one TTS reading.

F2 [major] Watch cancellation stops unrelated phone speech and newer replies - `plan.md:137-138` makes any broken Sink call global `CodexSpeech.stop()`; Risk 8 explicitly accepts cutting off another phone chat. This contradicts the brief's non-goal of changing phone voice behaviour. The existing `stop()` clears the entire queue (`app/src/main/java/app/buddy/assistant/CodexSpeech.java:61-64`), and the proposed stop contract only closes the current Sink (`plan.md:130`), leaving queued sinks without specified cleanup. A delayed failure from a completed/hushed watch item can also stop a newer item. -> Cancel by item/Sink identity, close the affected queued sinks, and ignore failures from retired items. If cancelling the current item requires restarting the WebView, preserve unrelated queued items and their callbacks. No second CodexSpeech instance is required. Verify a watch hush/late channel failure while another phone item or a newer watch item is speaking; neither unrelated item may be discarded.

F3 [major] Starting a call leaves the typed reply speaker active - The call-button flow (`plan.md:103-116`) does not stop or suppress Talk speech; ignoring `/buddy/live/*` (`plan.md:119`) does not suppress `/buddy/reply`. Opening CallActivity only stops TalkActivity, while Talk deliberately outlives its screen (`wear/src/main/java/app/buddy/assistant/wear/Talk.java:34-37`; `TalkActivity.java:100-109`). If the call starts during a tracked typed run, each subsequent BUDDY message still reaches WatchLink's relay (`app/src/watchlink/java/app/buddy/assistant/WatchLink.java:128-136`) and CodexLive (`app/src/main/java/app/buddy/assistant/ChatHub.java:635-638,1009-1010`). Both paths can speak the same reply; Talk audio is also outside Call's half-duplex Speaker gate. -> Specify one watch audio owner: hush pending Talk speech when a call starts and suppress/defer new typed-path speech while it owns the watch, including the phone relay's CodexSpeech enqueue. Preserve displayed replies and 006 reopening behaviour. Test starting a call both mid-reply and while a typed run is still producing messages, with each speech engine.

F4 [major] Call control messages cannot distinguish replaced calls - `/buddy/live` starts with only `{chat}`, and status/end messages carry only `{chat, text}` (`plan.md:180-183`). Starting a newer call intentionally ends the previous one (`plan.md:75`; `app/src/main/java/app/buddy/assistant/CodexLive.java:186`), which sends an end message asynchronously (`plan.md:95-98`). A quick restart in the same chat can therefore receive the old call's end message and hang up the new call. The phone's "if that call is still this one" guard protects its input reader, not these watch messages. -> Include a per-call identifier in the header and every status/end message; bind asynchronous opens, reads, PCM pushes and cleanup to that call/channel identity. Ignore stale callbacks and close late-opened channels after cancellation. Verify rapid End/start in the same chat and replacement while the first call is still connecting, with delayed old end/status delivery.

F5 [major] Unbounded output queues and drain-only close defeat bounded shutdown - `plan.md:64-66` explicitly specifies an unbounded PCM queue and a close that drains it. A slow but connected reader can accumulate audio indefinitely without an IOException; the watch deliberately leaves non-head reply channels unread (`plan.md:146-147`). End/hush can then leave a blocked writer draining obsolete audio instead of releasing resources, contrary to the two-second End check. The [ChannelClient reference](https://developers.google.com/android/reference/com/google/android/gms/wearable/ChannelClient) documents buffering and potentially long-running stream reads/open operations; an open channel is not proof of progress. -> Bound pending bytes and define an overflow/stall failure path. Separate normal completion (bounded drain) from cancellation (discard pending audio and close the channel to unblock workers), and bound setup/header waits as well. Specify header/audio flushing for the documented buffered output stream. Verify a connected peer that stops consuming, End during setup, and cancellation with queued audio: memory stays bounded and resources terminate promptly.

F6 [major] The live catch-up policy can silently delete words from typed replies - The same Speaker handles Call and Talk, and drops audio whenever it is more than about one second behind (`plan.md:60-63`). For a queued reply or a burst after link jitter, buffered speech is still part of the text that must be read; dropping it skips words while the "voice already started" rule prevents TTS recovery (`plan.md:148-152`). This regresses 003's ordered, complete TTS readings (`wear/src/main/java/app/buddy/assistant/wear/Talk.java:307-315`). -> Restrict catch-up dropping to live calls. For reply playback, preserve sample order with bounded buffering/backpressure or a defined failure path; do not silently skip speech. Exercise multiple replies behind an earlier TTS item and a channel delivering more than one second of PCM in a burst, checking that each reply is heard in order without missing words.

VERDICT: REVISE

### Planner response (round 1)
F1: accepted, except playback-position tracking - a reply belongs to the voice from its first *audible* block
(peak > 0.01 of full scale, the phone page's threshold) written to the not-live Speaker. In reply mode nothing written
is dropped (F6), so written means heard unless hushed. Silent PCM neither commits the reply nor resets its 35 s
deadline. The watch alone decides: `/buddy/say` only converts a reply the voice doesn't own yet. Tests: Test plan 2 (i)
was also wrong, because `ensureServer` restarts a killed server (`CodexDictation.java:249`). It now kills the server
the moment the text shows (silent stream, then `/buddy/say`/EOF), 3 runs. (ii) covers the stalled stream. The
Speaker's per-stream log line (`audible never`, ended by) proves there was one TTS reading each time.
F2: accepted - `CodexSpeech.cancel(sink)` replaces the global `stop()`. A queued item with that Sink is dropped (closed,
no Fallback). If it is the current item, the call hangs up and calls again with the queue kept (today's path after an
idle hang-up). A Sink matching no item (retired) is ignored. `stop()` (the phone's own) also closes every Sink; a watch
reply the voice hadn't started then goes to the watch TTS by F1's rule. PcmOut reports the watch closing a queued
channel (ChannelCallback), so queued replies are retired before anything is written to them. Risk 8 is now the
reconnect cost. Also fixed: one Fallback call per item would have changed the phone's own fallback (Voice's TTS keeps
only the last text while initialising). The phone's leftovers stay joined into one call, as today. Tests added (watch
hush while a phone read-aloud plays, and the reverse).
F3: accepted - one watch audio owner, the last one started wins (003 already lets a new talk replace one still being
said). The call button ends Talk's wait and hushes it (`Talk.left()` + `Talk.silence()`). Its lines stay, so 006's
reopen is unchanged. `Talk.begin` hangs up a call. Phone: WatchCall takes its chat off the 003 relay
(`WatchLink.untrack`), so its replies reach the watch only through the call's voice (`agentSaid`). The relay's voice
branch also needs `!CodexLive.on()`, the phone's own no-read-aloud-in-a-call rule (`ChatHub.java:1006`), so there are
never two realtime sessions. Tests: call started mid-reply and mid-run, with each engine; a talk during a call.
F4: accepted - the watch picks a call id. The header is `{chat, call}` and status/end carry it; Call ignores other ids
and closes a channel that opens after its call was ended or replaced. Phone: `CodexLive.push(remote, pcm)` and the
input-ending `end` act only while `CodexLive.on(remote)`. A WatchCall's cleanup leaves `ses.watch` and the wake lock
alone for a replacement call. Tests: End then call within 1 s; call again while connecting; End while connecting.
F5: accepted - PcmOut is bounded. A call keeps 2 s and drops the oldest (it stays live); a reply keeps 10 s and then
counts as broken (the watch sees the channel end and, if nothing was audible, says it with TTS). `finish()` drains for
at most the queue's length plus 5 s. `cancel()` discards the queue and closes the stream and channel, which unblocks a
stuck write. Flush after the header and after every chunk. Setup bounds: phone header 5 s, reply `openChannel` 10 s,
watch open+header 10 s. No phone-side stall timer: a queued reply's channel is unread on purpose, so the cap is the
bound. Proof: a JVM `PcmOutTest` (github test source set, already run by CI), covering a consumer that never reads, a
blocked write cancelled, and a bounded drain. Device runs can't force those cases.
F6: accepted - the Speaker gets a `live` flag. Call drops audio more than 1 s behind; Talk never drops, and a full
AudioTrack pushes back through the channel to F5's bounded queue. Tests: a two-message reply heard in order and whole;
every Speaker line in the run says `dropped 0`.

## Round 2 - Plan Reviewer (gpt-6-astra)

F1 [major] Normal reply completion needs a receiver-drain contract - `plan.md:72-76` says `finish()` drains the writer queue and closes, while `plan.md:176-178` deliberately leaves queued replies unread and removes their channels when the phone closes them. Draining PcmOut only proves bytes were handed to the transport, not consumed by the watch. The [ChannelClient close contract](https://developers.google.com/android/reference/com/google/android/gms/wearable/ChannelClient#close(com.google.android.gms.wearable.ChannelClient.Channel,%20int)) says a channel close invalidates further operations and makes remote reads fail, including with error code zero. Consequently a short reply generated behind an earlier TTS item can lose its channel before playback; an active reply can lose its buffered tail and cannot fall back once audible. This is a remaining completion gap after round 1 F5/F6, distinct from the now-bounded queue. -> Specify normal completion separately from cancellation at the transport level: flush and close the sender's OutputStream to signal EOF, retain the receiver's channel/stream until it consumes EOF, then close the channel, with bounded abandoned-peer cleanup. Do not remove a normally completed queued stream before consumption. Verify a short voice reply that finishes generating behind a two-second TTS item, and a slow reader with buffered final words: both must play completely in the selected voice, in order. The OutputStream-only JVM test cannot establish ChannelClient close semantics.

F2 [major] The new cancellation restart can inherit old speech callbacks - `plan.md:155-158` promises to reconnect with unrelated queued items intact using today's idle-reconnect path, but that path is not isolated from a cancelled in-flight item. `CodexSpeech.java:116-122` reuses `threadId`; `:229-237` sends stop on an independent thread; `:145-152` posts an unguarded `fail()` when an old appendText fails; and `:159-173` posts SDP/transcript/closed events without checking the session again when they execute. `CodexDictation.java:395-396` filters notifications by thread id only. Thus a delayed stop/closed event or appendText failure from the cancelled watch reply can fail the newly opened session and divert/drop unrelated phone or watch speech despite the Sink-identity guard. -> Extend the cancellation design to bind asynchronous request completions and posted notifications to the owning speech session (and item where needed). Isolate the replacement server call with a fresh thread id on cancellation, or explicitly order stop completion before reuse; also prevent a stale start worker from publishing session ownership. Keep the queue intact. Add a deterministic check that cancels with appendText/start still pending, starts the replacement, then delivers the old failure/closed/SDP callbacks; the replacement must keep its route, text and queue.

F3 [minor] Separate the per-chat flag guard from the shared wake-lock guard - `plan.md:110-111` preserves both `ses.watch` and the wake lock whenever a newer watch call is on. If delayed cleanup for chat A runs after a call in chat B starts, B needs the shared wake lock but does not own A's flag. Leaving A marked as a watch chat suppresses its future phone read-aloud and completion notifications (`ChatHub.java:989,1006-1007`), and A has been removed from the relay (`plan.md:104`), so `WatchLink.endCheck` will not clear it. -> Preserve A's `ses.watch` only if a replacement call owns A or a typed run still tracks A; preserve the shared wake lock while any watch call or tracked run needs it. Check rapid replacement across two different chats as well as within one chat.

VERDICT: REVISE

### Planner response (round 2)
F1: accepted - a normal end no longer closes the channel. ChannelClient's reference confirms the finding (`close`
"will cause remote reads and writes to the channel to fail", code 0 included) and the fix: the peer gets
`onInputClosed` with `CLOSE_REASON_NORMAL` when "the OutputStream on the remote node was closed normally".
`PcmOut.finish()` drains its queue, then closes only the OutputStream (EOF). The watch keeps the channel until it has
read that end (or it hushes or converts the reply to TTS), then closes it. Only `onChannelClosed` drops a kept
channel, never the phone's normal end. Abandoned peer: PcmOut cancels 60 s after `finish()` if the watch hasn't
closed it. That is past the watch's 35 s head deadline, and a reply cancelled that way with nothing audible becomes
the watch TTS, so the bound can't lose it. This bound replaces round 1's drain bound (queue length plus 5 s), which
would also have cut a reply waiting behind a long TTS line. Link drops already close every channel. Calls are
unchanged: their end is a hang-up. Speaker lines now tell `eof` from `closed`. Tests: a device case where "done" is
voiced after the phone's stream has ended while the watch TTS is still saying a long first message (the engine is
switched mid-run), then heard whole in the voice with `ended by eof` and played = received. No voice reply in row 2
may end `closed` outside the Bluetooth-off cases. The JVM test now checks that `finish()` ends the stream without
calling the channel closer, and that the bound does call it. As noted, it can't prove ChannelClient's side.
F2: accepted (design), rejected (a JVM test of it) - three rules in CodexSpeech, Design "The re-call doesn't inherit
the old call's late events". (1) `cancel()` re-calls on a fresh Codex thread, so old events carry the old id. Retired
threads stay claimed by `owns()`, so their late SDP/closed/error are dropped rather than reaching dictation. (2) Every
posted runnable checks again when it runs: `web == mine` for page callbacks and start/appendText failures,
`t.equals(sessionThread)` for notifications. (3) `startCall` publishes `threadId`/`sessionThread` under the class
lock, only if its page is still `web`. `close()` clears both under the same lock. The queue is kept, and the phone's
own path (no cancel) still reuses its thread. A deterministic JVM test isn't affordable here. The app has no
Robolectric (`app/build.gradle.kts` test deps: junit, org.json, android stubs returning defaults). CodexSpeech builds
a WebView in `open()`, posts to a main-looper Handler and starts the Codex server in `startCall`. Driving it from the
JVM would need seams across an upstream file the brief keeps unchanged for the phone. The rules hold by construction
(the ids differ, every post checks again, publishing is locked), and the Code Reviewer checks them. The device run
(reversed "not cutting others", 3 times) checks the outcome: the phone text is read in the voice after the re-call,
the re-call is on a new thread id, and old events show up as `stale … ignored` lines.
F3: accepted - cleanup's two rules are now separate. Chat A's `ses.watch` is cleared unless the live watch call is in
A or a talk run tracks A. The shared wake lock is released only when no watch call is on and no run is tracked. A
call sets its own flag and takes the wake lock once on, so cleanup order doesn't matter. Test: a call in chat B while
A is on and while A is connecting. B keeps `buddy:watch`; afterwards A's run-ended notification shows again and no
`buddy:watch` is left.

## Round 3 - Plan Reviewer (gpt-6-astra)

No blocker or major findings remain. Round 2 F1 is addressed by `plan.md:73-87,211-223`: normal completion closes only the OutputStream, the watch retains the channel through EOF, and cancellation has a separate bounded path. This matches the [ChannelClient](https://developers.google.com/android/reference/com/google/android/gms/wearable/ChannelClient) and [ChannelCallback](https://developers.google.com/android/reference/com/google/android/gms/wearable/ChannelClient.ChannelCallback) contracts. Round 2 F2 is addressed by `plan.md:175-193`: cancellation uses a fresh thread, posted work rechecks ownership, and start-worker publication is guarded. The rationale for code review plus device verification instead of a JVM WebView test is sound. Round 2 F3 is addressed by the separate per-chat flag and shared wake-lock rules (`plan.md:122-129`).

All four brief done-when items have planned verification (`plan.md:320-325`). Locked-phone WebView operation and Bluetooth throughput remain explicitly unverified device gates (`plan.md:295-298,328-339`); approval of this plan does not establish that those gates pass. No additional scope or abstraction is needed for this final round.

VERDICT: APPROVE
