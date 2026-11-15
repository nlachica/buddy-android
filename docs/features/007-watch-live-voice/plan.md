# Plan: 007-watch-live-voice
<!-- Planner (claude-opus-5-5), 2026-11-15. Revised for plan-review rounds 1 and 2. -->

## Goal
Phase 2 of 003, two parts. **(a) Calls from the watch:** from the Galaxy Watch4, start Buddy's live voice call
(CodexLive) in a chat; the watch mic feeds the call, the voice plays on the watch speaker, End on the watch hangs up,
and it works with the phone locked. **(b) Buddy's voice on the watch:** when the phone's speech engine is ChatGPT's
voice (CodexSpeech), a typed-path (003 talk) reply is spoken on the watch in that voice; with it off, or when that
voice or the link fails, the watch's own TTS says it, and says it once. The plan states what (b) costs in latency and
battery (Nicholas's question), and verify.md measures it. Done when: (1) call from the watch, hear the voice on the
watch, end it from the watch, phone locked works; (2) ChatGPT voice on → the reply in that voice on the watch; off or
failing → watch TTS, once; (3) (b)'s battery/latency cost measured or estimated in verify.md; (4) 003/006 behaviour
unchanged, CI green, F-Droid check green.

Non-goals: upstream sync; new voice providers or auth (no OpenAI API key); changing the phone's own call or read-aloud
behaviour; anything Google in the F-Droid build; talking over the voice on the watch (barge-in, see Design); watch-side
transcripts of the call (the phone chat keeps them); approvals on the watch screen (spoken answers already work
through the call).

## Design

### The fact that shapes everything (Planner probe, 2026-11-15)
Buddy's ChatGPT voice never hands audio to Java: CodexLive and CodexSpeech play it in a hidden WebView doing WebRTC
(research, Summary). Codex's app server also offers a `websocket` transport that would stream PCM through JSON-RPC
(`thread/realtime/appendAudio`, `outputAudio/delta`). Probed against the phone's own `codex app-server` (codex-cli
0.169.0, signed in with the ChatGPT plan, scratch script `/tmp/probe007/probe.py`, not in the repo):
- `thread/realtime/start` with `transport: {type: "websocket"}` → `thread/realtime/error`: **"realtime conversation
  requires API key auth"** (`account/updated: authMode chatgpt`).
- The same call with `transport: {type: "webrtc", sdp: <dummy>}` gets past auth and is rejected by OpenAI for the SDP
  itself ("Offer did not have an audio media section").

So with Nicholas's sign-in only WebRTC works, and an API key would be a new provider (non-goal). **Both parts keep the
call on the phone's WebView and bridge its audio to the watch.** That is the research's fallback design.

### Shape
```
 Watch (:wear)                                    Phone (github build)
 TalkActivity ─ call button ─► CallActivity       WatchLink (WearableListenerService)
 Call (FGS microphone)                              onChannelOpened("/buddy/live") ─► WatchCall
   AudioRecord 16k ──► channel /buddy/live ──────►    header {chat, call}, then PCM ─► CodexLive.push() ─► page ownTrack ─► WebRTC
   Speaker ◄──────────── same channel, back ◄─────    CodexLive page tap(remote track) ─► Remote.pcm() ─► PcmOut
   ◄── /buddy/live/status, /buddy/live/end ───────    Remote.status()/ended()
 Talk (003)                                         relay (003): BUDDY reply + ChatGPT voice on
   /buddy/reply {voice: id} ◄─────────────────────    ─► /buddy/reply {..., voice: id}, then CodexSpeech.speak(text, fallback, sink)
   Speaker ◄──── channel /buddy/voice/<id> ◄──────    CodexSpeech page tap ─► Sink.pcm() ─► PcmOut (openChannel)
   watch TTS ◄── /buddy/say {id} ◄────────────────    per-item Fallback (the voice failed before starting it)
```

### Audio format and transport
- One format everywhere: **PCM 16-bit mono 16 kHz, little-endian**. It is what the page's own-mic path already pulls
  (`CodexLive.java:748-813`, `ownTrack` resamples 16 kHz to the page rate) and what the watch records. 32 KB/s each
  way; ChannelClient (streams, `/buddy/live` was reserved for this in 003). No compression: μ-law (halves it) only if
  the gate in Step 4 shows Bluetooth can't carry it, and that goes back to the Planner.
- Voice out of the page: a shared JS snippet `TAP_JS` (a constant in `CodexSpeech`, used by both pages) taps the
  remote track: `createMediaStreamSource` → `ScriptProcessor` (≈2048 frames) with a zeroed output connected to
  `ctx.destination` (the phone stays silent) → linear resample from `ctx.sampleRate` to 16 kHz, carrying the phase
  across blocks as `ownTrack` does → Int16 → base64 → `Buddy.pcm(b64)`. The page's `<audio>` element keeps the stream
  attached but **muted** (Chromium only feeds a remote WebRTC track into Web Audio when a media element holds it;
  Risk 2). No silence gating: the stream is continuous while tapped.
- The watch side: one `Speaker` class (Call and Talk both use it) plays an InputStream of that PCM through an
  `AudioTrack` (`USAGE_ASSISTANT`, stream mode), starts after ≈300 ms buffered (and again after running dry), and
  reports: the first audible block (a 40 ms block whose peak is over 0.01 of full scale, the phone page's own
  threshold, `CodexSpeech.java:257`) as it is written to the AudioTrack; whether it is audible now (such a block within
  the last 300 ms); and when the stream ends or breaks. A `live` flag: **live (Call)** drops audio when more than ≈1 s
  behind, to stay a conversation; **not live (Talk)** never drops: every sample plays in order, and a full AudioTrack
  blocks the reading thread, which pushes back through the channel to the phone's bounded `PcmOut` (below). Each
  stream logs one line at its end: bytes received, played, dropped; audible at (ms) or never; ended by (`eof` = the
  phone's normal end read; `closed` = the channel closed or failed under it; `say`, `timeout`, `hush`).
- The phone side: one `PcmOut` class (WatchCall and the relay both use it). Its core takes an `OutputStream` and a
  closer, so it is unit-tested without Play services. `write(byte[])` never blocks (the caller is the WebView's
  JavaBridge thread): a bounded queue and its own writer thread, which flushes after the header and after every chunk.
  Bounds: **a call** keeps 2 s (64 KB) and drops the oldest past that (live); **a reply** keeps 10 s (320 KB) and past
  that is broken. **A normal end is not a cancel.** `ChannelClient.close(channel)` makes the peer's reads fail (its
  reference: "will cause remote reads and writes to the channel to fail", error code 0 included), so a normal end
  never closes the channel. `finish()` (no more audio): the writer drains the queue, then closes only the channel's
  OutputStream. The watch reads every byte and then the end of the stream (its `onInputClosed`, `CLOSE_REASON_NORMAL`:
  "the OutputStream on the remote node was closed normally"). The channel stays open until the watch closes it, after
  it reads that end (or when it hushes or turns the reply into TTS). If the watch hasn't closed it 60 s after
  `finish()` (abandoned), PcmOut cancels. 60 s is longer than any honest wait: a waiting reply only sits behind a
  reply's tail or a watch TTS line, and the watch's own deadline at the head is 35 s. A link drop closes every channel
  on its own (`onChannelClosed`, DISCONNECTED). A reply cancelled by that bound before anything was audible becomes
  the watch TTS by the watch's rule, so the bound can't lose a reply. `cancel()` discards the queue and closes the
  stream and the channel at once, which also unblocks a write stuck on a peer that stopped reading. A one-time
  `broken` callback fires on overflow, an IOException, or the watch closing the channel before `finish()` (a
  `ChannelClient.ChannelCallback` registered for it, so a queued reply the watch closed is known before anything is
  written to it). The writer thread ends on any of these, or after it closes the OutputStream. The 60 s bound is a
  timer in PcmOut's core (its length a constructor argument, so the test shortens it) that the watch's close cancels.
  Calls never `finish()`: a call's end is a hang-up (`cancel()`).

### (a) Calls from the watch
**Main code: a seam in CodexLive, no Google types.** `interface Remote { void pcm(byte[] pcm16k); void status(String
text); void ended(String text); }` and `static String start(Context c, Sessions.Session s, Remote r)`; the old
`start(c, s)` calls it with null and every null path is today's code. With a Remote:
- no `Voice.micAllowed` check (the phone mic isn't used), no `AudioRoute.use` (`:190`, `:691`), no
  `ACTION_SCREEN_OFF` receiver (`:206-213`): that rule stops pocket calls on the phone; a watch call's mic is the
  watch's. Pause/stop of Buddy still ends it (`BuddyService.java:587,652`), and so do idle (5 min), no-connect (30 s),
  the phone's End (notification, live bar) and a newer call, all as today.
- the page asks `Buddy.remote()`: true → skip `getUserMedia` and go straight to `ownTrack()` (whose `Buddy.ownMic()`
  returns true without `startCapture()`; the AudioRecord is never opened); in `ontrack` mute `<audio>` and `tap()` the
  stream into `Buddy.pcm`, which calls `remote.pcm(bytes)` while the page is still the call's.
- `static void push(Remote r, byte[] pcm)` (any thread) adds watch audio to the same `chunks` queue `pullCapture`
  reads, with the same 5 s cap, only while `r` is the current call's Remote (a replaced call's reader can't feed the
  new one). `static boolean on(Remote r)` says the same for callers. Chunks are 1280 bytes (40 ms), under `pull(6400)`.
- `status()` also calls `remote.status(t)` when the text changes; `end(why)` calls `remote.ended(text)` with the same
  sentence it notes in the chat (`live_ended` / `live_ended_why`), then drops the Remote.
Everything else is reused unchanged: transcripts → `utterance()` → `hub.send` (as typed), agent replies → `tell()`,
spoken approval answers (`answerCard`), the watchdog, the live bar and notification on the phone, `live.log`.

**Phone watch link** (`app/src/watchlink/`, github only):
- Manifest: the WatchLink service gets a second intent filter, `com.google.android.gms.wearable.CHANNEL_EVENT`, same
  `wear://*/buddy` data. `WatchLink.onChannelOpened(channel)` for path `/buddy/live` → `WatchCall.open(app, channel)`.
- `WatchCall` (new, about 140 lines): a thread reads the channel's input: the header line `{"chat": id, "call": n}`
  (within 5 s, else the channel is closed), then 1280-byte frames → `CodexLive.push(remote, …)`. On the main thread it
  checks, in 003's order and with its strings: chat exists (`watch_chat_gone`), `BuddyService.get() != null`
  (`watch_buddy_off`), then `CodexLive.start(app, ses, remote)`; an error string → `/buddy/live/end {chat, call, text}`
  and close the channel. Once on, the call owns its chat: `WatchLink.untrack(chat)` takes it off the 003 relay, so
  its replies reach the watch only through the call's voice (`agentSaid`), never as `/buddy/reply` or CodexSpeech.
  While on: `ses.watch = true` (the phone stays quiet: no run-ended notification, `ChatHub.java:989`; read-aloud is
  already off in any call, `:1006`), the `buddy:watch` wake lock held and re-acquired every minute while audio comes
  in. `Remote.pcm` → a call `PcmOut` on the channel's output stream; `Remote.status` → `/buddy/live/status {chat, call,
  text}`; `Remote.ended` → `/buddy/live/end {chat, call, text}`, then the `PcmOut` is cancelled and the channel closed
  ~500 ms later (so the reason usually lands first). Cleanup has two separate rules: its own chat's `ses.watch` is
  cleared unless the live watch call is in that same chat (a replacement) or a 003 talk run tracks that chat; the
  shared `buddy:watch` wake lock is released only when no watch call is on and no talk run is tracked (WatchLink's
  `releaseWake` rule, below). A call sets its own chat's flag and takes the wake lock once it is on, so the old call's
  cleanup running before or after the new call's start gives the same result. The input
  ending (the watch hung up or vanished) → `CodexLive.end(null)` on main, only if `CodexLive.on(remote)`.
- `WatchLink`: `releaseWake()` callers also check that no watch call is on; `endCheck` leaves `ses.watch` set for a
  chat in a watch call; `untrack(chat)` (package-private).

**Watch:**
- **One audio owner on the watch, the last one started wins.** The call button ends Talk's conversation and hushes
  it (`Talk.left()` + `Talk.silence()`): nothing more is said, its lines stay, so reopening the chat shows them as in
  006. `Talk.begin` (a new typed talk, any entry point) first hangs up a call that is on (`Call.hangUp`).
- `TalkActivity`: a second round button next to the mic, the phone's live icon (`ms_graphic_eq`, copied), described
  "Call Buddy". Tap → `RECORD_AUDIO` asked once (system dialog on the watch; denied → a line "Calls need the
  microphone") → Talk yields (above) → `Call.start(...)` and `CallActivity`. Starting a microphone foreground service
  from a visible activity is allowed.
- `Call` (new service, `foregroundServiceType="microphone"`, about 190 lines): each start picks a call id (a counter
  seeded from the clock) and replaces a call that is on. It finds the phone (`Phone.find`, the capability lookup
  factored out of `Phone.send`, same error sentences), `openChannel(node, "/buddy/live")`, writes the header `{chat,
  call}` (open plus header within 10 s, else it ends with 003's `talk_connect` sentence; a channel that opens after
  its call was ended or replaced is closed at once), then records `AudioRecord` (`VOICE_COMMUNICATION`, else `MIC`;
  16 kHz mono, 40 ms reads) into the channel. **Half-duplex:** while the Speaker is audible, and 300 ms after, mic
  frames are read and dropped, not sent (the watch's mic and speaker are 3 cm apart; the page's own echo cancellation
  only knows the phone's audio). The channel's input goes to a live Speaker. A MessageClient listener takes
  `/buddy/live/status` (shown) and `/buddy/live/end` (shown, then hang up), only when their `call` is this call's.
  Partial wake lock `buddy:call`, ongoing notification ("Call with Buddy · <chat>", tap → `CallActivity`). Ends on:
  End, `CallActivity` swiped away (`isFinishing()`, as Talk's `left()`), `/buddy/live/end`, the stream ending or
  breaking ("Lost contact with your phone"), a new talk, or a 30-minute cap. Ending closes the channel (which also
  ends the reading and writing threads), releases mic, AudioTrack and wake lock, and stops. Wrist down and the watch
  face coming back do not end it.
- `CallActivity` (new, about 90 lines, Look's style): the face, the chat title, the phone's status line
  ("Connecting…", "Listening…", "Speaking…", "Working on it…") or the end sentence, and a red "End" pill.
- Talk's listener ignores `/buddy/live/*` before touching its deadlines (today any message for its chat cancels
  `noHeard`, `Talk.java` `onPhone`).

### (b) Buddy's voice for watch replies
**Main code: CodexSpeech routes per text.** The queue holds `Item {text, Fallback, Sink}` instead of strings, and the
single static `fallback` goes. `interface Sink { void pcm(byte[] pcm16k); void close(); }`;
`speak(c, text, f)` stays and means `speak(c, text, f, null)` (the phone's speaker, today's path). Before `next()`
gives the voice an item, it sets the page's route with `route(remote)` (`<audio>` muted and the tap forwarding to
`Buddy.pcm`, or unmuted and not forwarding). Items only change after the previous one went quiet (`watchdog`), so the
switch is clean. The watchdog's "read and quiet" also closes the item's Sink. `fail()` keeps its rule: the current
item's text is handed on only if the voice never started it (`lastSound < sentAt`), every queued item's always; the
phone's items (no Sink) are joined into one text for the first one's Fallback, exactly as today (Voice's TTS keeps
only the last text while it initialises, so one call per item would change the phone); each watch item gets its own
Fallback; every Sink is closed. `stop()` (the phone's own stop) is unchanged and also closes every item's Sink, no
fallbacks (a watch item the voice hadn't started then falls to the watch TTS by the watch's own rule, below).
`cancel(Sink s)` (main thread) retires one watch reply and nothing else: a queued item with that Sink is dropped (Sink
closed, no Fallback); if it is the current item, the call is hung up and, when other items wait, called again with
the queue intact (today's path when a text comes after an idle hang-up); a Sink matching no item (a reply already
read or dropped) is ignored, so a late failure can't touch a newer item or a phone read-aloud.

**The re-call doesn't inherit the old call's late events.** Today's code routes notifications by thread id only
(`CodexDictation.java:395`), reuses `threadId` (`CodexSpeech.java:116`), stops on a separate thread (`:232`), and posts
`fail()` and the notification handlers without checking again when they run (`:152`, `:161-173`). Today that only
matters after an idle hang-up; with `cancel()` an appendText or a start can still be pending. Three rules, all in
CodexSpeech:
1. *A fresh thread for the re-call.* `cancel()` sets `threadId = null` before `open()`, so `startCall` makes a new
   Codex thread (one `thread/start` on the warm server). Every late event of the old call then carries the old id.
   CodexSpeech keeps claiming the threads it retired this way (`owns()`; a small set, emptied when the server
   changes), so their late SDP, closed and error events are dropped there and don't reach dictation's handlers.
2. *Every posted runnable checks again when it runs.* Page callbacks and the start/appendText failures check
   `web == mine`, the page they were started for (`next()` captures it as `startCall` already does). Notifications
   check that their thread is the live call's (`t.equals(sessionThread)`); `owns()` claiming a retired thread only
   routes its events here to be dropped.
3. *A stale start worker can't take the call.* `startCall` picks its thread id locally, then publishes `threadId` and
   `sessionThread` under the class lock, only if its page is still `web`. `close()` clears `web` and `sessionThread`
   under the same lock.

A dropped stale event logs one line (`BuddySpeech: stale <event> ignored`). The phone's own path is unchanged: without
`cancel()` the thread is reused as today, and the rules only drop events that would have failed a newer call.

**Phone relay** (`WatchLink.relay`, 003): for a `BUDDY` message to a tracked chat, when ChatGPT's voice is the
phone's engine (the same test as `Voice.speak`: `"chatgpt".equals(Prefs.speechEngine(app)) &&
SetupState.planSignedIn(app, "codex")`, `Voice.java:237`) and no live call is on (`!CodexLive.on()`, the phone's own
rule for read-aloud, `ChatHub.java:1006`; so never two realtime sessions), it picks a voice id (a counter seeded from
the clock, unique across phone restarts), sends `/buddy/reply {chat, text, voice: id}` first, then
`CodexSpeech.speak(app, text, fallback, sink)`: the Fallback sends `/buddy/say {chat, id}`; the Sink is a reply
`PcmOut` on `openChannel(watch, "/buddy/voice/<id>")` (opened within 10 s, else broken; audio until then waits in its
queue). A broken Sink (the watch closed it: hushed, or said it itself; or 10 s backed up: the watch isn't playing
it) → `CodexSpeech.cancel(sink)`: only that reply stops, so a long text isn't read into nothing while later ones
wait. `ERROR` replies keep the watch TTS (they often come from the failures that also break the voice). `deliver()` also calls
`CodexDictation.prepare(app)` when ChatGPT's voice is on: the Codex server is warm by the time the answer comes (a
cold server cost ~5 s of the 7.7 s call setup in `live.log`). No `AudioRoute`/`SpeechPause`: nothing plays on the phone.

**Watch Talk: one ordered speech queue.** `early` becomes `toSay`, a list of `{text, voiceId}` (−1 = TTS):
- A TTS item at the head goes straight to the engine (`QUEUE_ADD`, counted in `pending`) as today, or waits for init
  as today; so with ChatGPT's voice off nothing changes.
- A voice item at the head waits until `pending` is empty (earlier TTS said), then plays its channel through a
  not-live Speaker (nothing dropped); the items after it wait. Its channel can come before its reply message:
  channels are kept by id until the watch closes them: after its Speaker read the end of the stream (said), on
  conversion to TTS, on hush, or when Talk stops. The phone's normal end (`onInputClosed`, NORMAL) doesn't drop a kept
  channel: its audio is still there to be read. Only the channel closing (`onChannelClosed`: the phone cancelled it, or
  the link dropped) drops it; with nothing audible yet that is "its channel ending" below.
- **Said once; the watch alone decides, by whichever comes first:** its first audible block written to the
  AudioTrack → it is the voice's (a break later ends it, never re-spoken: CodexSpeech's own rule,
  `CodexSpeech.java:208`). Silent PCM (the tap streams silence before the voice starts) neither commits it nor resets
  its deadline. `/buddy/say`, its channel ending with nothing audible yet, or 35 s at the head with nothing audible (the
  phone's fallback normally comes sooner: a dead Codex server or a failed request fails at once) → it becomes a TTS
  item, and its channel is closed unread (which retires it in the phone's CodexSpeech, above). `/buddy/say` for an item
  already the voice's is ignored.
- When the wait ends by failure (lost contact, no answer, ceiling), waiting voice items become TTS at once.
- The MessageClient listener stays until Talk stops, not just until the end, but after the end it only takes
  `/buddy/say`. A `ChannelClient.ChannelCallback` is registered from start to stop. `hush()` (mic button, a new
  conversation) also stops the Speaker and closes channels; stopping closes any channel still kept.
  `maybeStop()` counts voice items. The 5-minute force stop
  after the end still caps it.
- Log lines (`BuddyTalk`): reply arrived, first sound (voice or TTS) per reply, for the latency measurement; the
  Speaker's per-stream line (bytes, audible at, dropped, ended by eof / closed / say / timeout / hush).

### Cost of (b), Nicholas's question
Yes, it costs something, mostly time:
- **Latency.** In `~/.buddy/live.log` on this phone (2026-11-15) a call took 7.7 s from start to connected (Codex's
  `thread/realtime/start` returned at 6.3 s, the server likely cold), and inside an open call an agent text was audible
  0.6-2.2 s later. CodexSpeech sets up the same call, opened per burst of replies and closed when idle. Estimate: the
  first reply starts **3-4 s** after its text reaches the watch with Codex warm (the `prepare()` above), up to ~10 s
  cold; later replies in the same burst 1-2 s; plus ~0.5 s for the channel and prebuffer. The watch TTS starts in about
  1 s (estimate). The text shows at once either way.
- **Watch battery:** about neutral. TTS synthesizes on the watch's CPU; (b) moves that off the watch but keeps the
  Bluetooth radio receiving 32 KB/s while it speaks (a 20 s reply ≈ 640 KB) and the watch awake a few seconds longer
  per reply.
- **Phone:** one realtime WebRTC call (WebView, Codex app server, network) per burst, which is what ChatGPT read-aloud
  costs on the phone today, and it counts against the ChatGPT plan's voice use.
- (a) for scale: a call streams 32 KB/s each way for its whole length with the watch mic, speaker and CPU on.
verify.md measures latency (watch log lines) and battery (`dumpsys batterystats` per app on watch and phone) for both
settings (Test plan 3).

### Protocol additions (003's table stays as is)
| Path | Way | Body | Meaning |
|---|---|---|---|
| `/buddy/live` (channel) | W→P | line `{chat, call}\n`, then PCM 16 kHz mono s16le; nothing while the voice is audible | the call; closing it hangs up |
| `/buddy/live` (channel) | P→W | PCM 16 kHz mono s16le | the voice |
| `/buddy/live/status` | P→W | `{chat, call, text}` | the phone's call status line |
| `/buddy/live/end` | P→W | `{chat, call, text}` | the call ended or was refused; text as noted in the chat |
| `/buddy/reply` | P→W | adds `voice: id` | ChatGPT's voice will say this reply |
| `/buddy/voice/<id>` (channel) | P→W | PCM 16 kHz mono s16le | that reply's voice; the end of the stream = said |
| `/buddy/say` | P→W | `{chat, id}` | the voice couldn't say reply `id`: the watch says it |

### Alternatives considered (one line each)
- Codex `websocket` transport (PCM through Java, no WebView): refused for ChatGPT-plan auth (probe above).
- WebRTC on the watch (signalling through the phone): no WebView on Wear OS; libwebrtc is a large native dependency.
- `existingCall` transport: the client would need its own OpenAI credentials.
- Watch-side echo cancellation (`VOICE_COMMUNICATION` + AEC) for full duplex: unknown on the Watch4, and a weak AEC
  makes the voice interrupt itself; half-duplex is predictable. Can be tried later.
- A second CodexSpeech instance for the watch: a static-to-instance rewrite of an upstream file; per-item routing is
  smaller.
- One channel per conversation with framed audio for (b): needs framing and ids inside the stream; a channel per
  reply carries both for free.
- Only the watch's timeout for (b) failures (no `/buddy/say`): a failing voice would mean 35 s of silence.
- Call button per row in the chat list, or long-press: more UI; the talk screen already has the round button row.
- Phone call streaming silence-gated audio: saves radio in pauses but makes resume choppy; not needed at 32 KB/s.

## Steps
One commit each on `feat/007-watch-live-voice`. Main-code steps must keep `FLAVOR=github ./build.sh` compiling
(no watchlink types in `app/src/main`).

1. **CodexLive remote mode (main).** `CodexLive.java`: `Remote`, `start(c, s, r)`, `push(r, pcm)`, `on(r)`, `Buddy.remote()` /
   `ownMic()` / `pcm()` interface methods, the skips listed in Design, `remote.status/ended`, PAGE: remote branch in
   `start()` and `ontrack`. `CodexSpeech.java`: the `TAP_JS` constant only. Log lines in `live.log` mark a watch call.
2. **Phone: the call bridge (watchlink).** New `PcmOut.java` (bounds; `finish()` = drain then end the OutputStream, the
   channel kept until the watch closes it or 60 s; `cancel()`; peer-close callback), new `WatchCall.java` (call id,
   header timeout, `untrack`, identity-bound cleanup with separate flag and wake-lock rules); `WatchLink.java` (`onChannelOpened`,
   `untrack`, wake-lock and `ses.watch` rules); `app/src/watchlink/AndroidManifest.xml` (`CHANNEL_EVENT`). New
   `app/src/testGithub/java/app/buddy/assistant/PcmOutTest.java` (github-only test source set: PcmOut isn't in the
   F-Droid build; CI's `testGithubDebugUnitTest` runs it).
3. **Watch: Speaker and phone lookup.** New `wear/.../Speaker.java` (`live` flag, first audible block, per-stream log
   line with `eof` vs `closed`); `Phone.java`: `find()` factored out of `send()` (same behaviour), the new path constants.
4. **Watch: the call.** New `Call.java` (call id, setup bound, stale-message and late-channel rules),
   `CallActivity.java`; `TalkActivity.java` (call button: permission, Talk yields); `Talk.java` (ignore
   `/buddy/live/*`; `begin` hangs up a call); `wear/src/main/AndroidManifest.xml` (`RECORD_AUDIO`,
   `FOREGROUND_SERVICE_MICROPHONE`, the service with type `microphone`, the activity); `strings.xml`;
   `res/drawable/ms_graphic_eq.xml` (copied from `app/`).
   **Gate (orchestrator, before Step 5's device work, not before its code):** CI build installed on phone and Watch4;
   Test plan 1 incl. the locked phone, plus channel throughput (`live.log` rtp lines keep coming, the watch's Speaker
   doesn't starve). A WebView call that dies with the screen off, or a link that can't carry 32 KB/s, goes back to the
   Planner (Risks 1, 3) before (b) is verified.
5. **CodexSpeech per-item routing (main).** `CodexSpeech.java`: `Item`, `Sink`, `speak(c, text, f, sink)`, `route()`
   in its page plus `TAP_JS`, `cancel(sink)` with the re-call rules 1-3 (fresh thread, posted runnables checked when
   they run, start published under the lock, the stale-event log line), fail (phone items joined as today) / stop / close as in Design. Phone
   read-aloud unchanged.
6. **Phone: watch replies in ChatGPT's voice (watchlink).** `WatchLink.java`: the relay's voice branch (not during a
   call), `/buddy/say`, per-reply `PcmOut` channel, broken Sink → `CodexSpeech.cancel(sink)`, `CodexDictation.prepare`
   in `deliver()`.
7. **Watch: voice replies in Talk.** `Talk.java`: the ordered queue, channels kept until read to their end or closed by the
   watch, channel callback, the audible commit rule,
   `/buddy/say`, timeouts, failure conversion, `hush()`, `maybeStop()`, log lines (as in Design).
8. **Docs.** `README.md` watch section: calls (the button, mic permission, End/swipe hangs up, half-duplex, needs Codex
   signed in), and that replies use ChatGPT's voice when it is the phone's engine. `docs/PRIVACY.md` watch section:
   during a call the watch's microphone audio goes to the phone and on to ChatGPT, as the phone's live voice does; the
   reply voice comes from ChatGPT through the phone. `WatchLink.java`'s class comment points at this plan's table too.
9. **Release prep (orchestrator, after review):** `BuildInfo.java` `VERSION` `2.3.9-fork.4`, `VERSION_CODE`
   max(fork 210, upstream) + 1, per AGENTS.md.

## Test plan
Which checks are CI and which are on the devices (phone runs the fork since 003; Watch4 over wireless adb, kept awake
during the session, playbook `buddy-fork-verify`).

| Done-when | Proof |
|---|---|
| 1 call from the watch, phone locked | Devices. Phone screen off and locked for 1+ min, in a pocket. Watch: chat → call button → (first time) allow the mic → "Connecting…" then "Listening…"; ask "what time is it" → the voice answers from the **watch** speaker; the phone stays silent; the phone chat shows the words as the user's message and the reply. Talk for 2+ min with the wrist down part of the time. `~/.buddy/live.log`: the watch-call line, no `getUserMedia`, `rtp 10 s` lines all along, no "screen off". End on the watch → the call ends within 2 s: the phone's live notification and live bar go, the chat notes "Live voice ended.", watch `dumpsys activity services app.buddy.assistant` shows no `Call`, `dumpsys power` on both shows no `buddy:call` / `buddy:watch`. Also: End on the phone's notification → the watch shows "Live voice ended." and stops; swipe `CallActivity` away → hangs up; Codex signed out → the watch shows `dictation_codex_sign_in`'s text; Bluetooth off mid-call → the watch says it lost the phone, the phone ends the call. **Replaced calls:** End then the call button again within 1 s, and the call button tapped again while "Connecting…": the newest call stays on, the watch never shows an end that isn't its call's (`BuddyCall` log: "ignored end/status for call n"), one `CodexLive` call in `live.log`. End during "Connecting…" → within 2 s no `Call`, no `buddy:call`/`buddy:watch`, the phone's live notification gone. **Across chats:** a call in chat A, then from the watch a call in chat B while A is on, and again while A is still "Connecting…" → B stays on and `buddy:watch` stays held while it is; after B ends, `dumpsys power` shows no `buddy:watch`, and a message sent to A from the phone with the screen off ends with A's run-ended notification (A's `ses.watch` was cleared, `ChatHub.java:989`). **One audio owner, each engine:** a typed talk, then the call button while its reply is being said → the reply stops at once and is not said again, the call starts; the call button while that typed run is still working → its later replies are heard only in the call's voice, once (no watch TTS, no `/buddy/reply` in the phone's relay log); a new typed talk during a call hangs the call up. |
| 2 replies in ChatGPT's voice, else watch TTS, once | Devices. Phone Settings → Voice → ChatGPT voice. Watch talk "tell me a short joke" → the reply text shows, then is heard in ChatGPT's voice (Juniper etc., clearly not the watch TTS) from the watch; the phone is silent; `BuddyTalk` log: one "voice" first-sound line for the id, no "tts" line. Engine set back to the phone voice → the same question is said by the watch TTS (003 path, no `voice` in the reply). **Failing, once each:** (i) `pkill -f "codex app-server"` in Buddy's Linux the moment the reply text shows on the watch (not right after sending: `CodexDictation.ensureServer` restarts a dead server, `:249`), so the speech call is connecting or connected and streaming silence → CodexSpeech fails → `/buddy/say` and the channel ends → the watch TTS says it once (log: one "tts" line, no "voice" line; Speaker line `audible never`, ended by `say` or `eof`). Run it 3 times; a kill that lands after the voice was audible must give a cut-off voice and no TTS, never both; (ii) watch Bluetooth and Wi-Fi off once the text has shown → the watch TTS says it within ~40 s, once; (iii) a long reply, Bluetooth off mid-sentence → the voice stops, nothing is repeated; (iv) the mic button mid-reply → silence, and the next question's reply comes without waiting for the old one (the phone's `CodexSpeech` retired that reply only). **Not cutting others:** a phone chat read aloud in ChatGPT's voice (read-aloud on) and, while it plays, a watch talk whose reply queues behind it; the mic button on the watch → the phone keeps reading its text to the end. Reversed: the watch reply plays, a phone read-aloud waits; the mic button → the phone's text is still read in ChatGPT's voice after a reconnect, not by the phone's TTS; 3 runs, and `BuddySpeech` shows the re-call on a new thread id (and any `stale … ignored` lines). The late-event rules (Design, rules 1-3) are by construction, since a device can't force an old event to arrive late: the Code Reviewer checks that every runnable CodexSpeech posts checks its call when it runs and that `startCall` publishes only under the lock. **No words lost:** a reply in two messages ("answer in two separate messages: …") is heard in order, whole; every Speaker line in the run says `dropped 0`. **A reply that waits keeps its voice and its end:** engine set to the phone's voice; watch talk "answer in two separate messages: first about 80 words on anything, then only the word done"; while the watch TTS is saying the long text, switch the phone's engine to ChatGPT voice (`buddy_settings`) → "done" goes out with `voice`, and its stream ends on the phone (PcmOut log: end sent) well before the watch TTS finishes → after the long text, "done" is heard in ChatGPT's voice, whole, and no "tts" line for it; its Speaker line says `ended by eof`, played = received. Across the row, no voice reply's Speaker line says `closed` except in the Bluetooth-off cases (ii, iii): the phone never closes a channel under a reader. |
| 3 cost of (b) in verify.md | Devices. Latency: 5 replies with each engine; `BuddyTalk` "reply arrived" → "first sound" per reply, median and range, cold and warm. Battery: watch and phone unplugged, `dumpsys batterystats --reset` on both, the same 5-question script per engine, then `dumpsys batterystats --charged` per UID (watch: `app.buddy.assistant` and the TTS engine's package; phone: Buddy). The table plus this plan's estimate go into verify.md. |
| 4 003/006 unchanged, CI, F-Droid | With the phone voice engine: 003's talk row (list, speak, reply shown and said once, reopening shows it without speaking again, 006) and quick talk. The phone's own live call from the chat screen still works and still ends when the screen goes off; phone read-aloud in ChatGPT's voice for a phone chat still plays on the phone. CI `build.yml` on the branch green, including the "F-Droid APK has no Google code" step (`build.yml:80`). `FLAVOR=github bash build.sh`'s javac step compiles `app/src/main` (or `javac` against android.jar as in 003). `PcmOutTest` (CI, `testGithubDebugUnitTest`): a consumer that never reads → queued bytes stay ≤ the cap; a reply PcmOut reports broken once, a call PcmOut drops the oldest; `cancel()` with a write blocked on it ends the writer thread within 1 s and calls the closer; `finish()` drains what a slow consumer reads, then closes the OutputStream but does not call the channel closer; the closer runs when the abandoned bound passes (test-sized), and never if the peer closes first. The JVM test can't show ChannelClient's side, that a closed OutputStream reads as a normal end on the watch while the channel stays open: row 2's `ended by eof` lines show it on the devices. Diff holds only the files in Steps 1-9. |

## Risks
1. **The WebView call with the phone screen off is unverified.** In remote mode the page opens no mic (so Android's
   background-mic rule doesn't apply), the CPU stays on (`buddy:watch` wake lock) and Buddy is battery-exempt; but
   Chromium might still throttle a hidden page. The Step 4 gate checks it first. If it fails there is no cheap way
   around it with ChatGPT-plan auth: it goes to Nicholas (accept "phone screen on" for calls, or an API key, which is
   out of this brief). (b) degrades safely: CodexSpeech's watchdog (Java, main thread) fails within 20-30 s and the
   watch TTS says the reply.
2. **Muted `<audio>` and Web Audio.** If the tap gets silence with the element muted, use `volume = 0` unmuted
   instead (Coder checks on the first device run).
3. **Bluetooth throughput and jitter** for 32 KB/s each way on ChannelClient are unmeasured. The Speaker's prebuffer
   absorbs jitter, plus the stay-live drop in calls; in replies a stall longer than the phone's 10 s queue ends that
   reply (never re-spoken, or the watch TTS if nothing was audible yet). A link that can't carry it → μ-law (back to
   the Planner).
4. **Half-duplex:** the user can't interrupt the voice by talking; they wait for it (status "Speaking…").
5. **Open mic on the wrist:** ambient speech is transcribed and sent to the chat like the user's words, and keeps the
   call from going idle. Bounded by End, swipe-away, the 30-minute cap and the ongoing notification; a call is what
   the user asked for.
6. **Experimental Codex realtime API:** all of `thread/realtime/*` is marked EXPERIMENTAL, the webrtc path Buddy uses
   today included; nothing new here, but a Codex update can break both parts at once (watch TTS stays for (b)).
7. **ChatGPT plan voice use:** (b) opens a realtime session per burst of watch replies.
8. **Retiring the reply being read costs a reconnect** for whatever waits behind it (`cancel()` hangs up and calls
   again, ~1-3 s warm). A watch reply queued more than 10 s behind a watch TTS line (a long error) is said by the
   watch TTS instead (its queue overflows before it plays), and so is one whose stream ended more than 60 s before the
   watch got to it (PcmOut's abandoned bound); and if the watch stopped listening (talk swiped away
   while its run goes on), at most 10 s of voice is read into nothing before the reply is retired.
9. **Phone notification mirrored to the watch:** the phone's ongoing live notification may also appear on the watch
   during a call (Galaxy Wearable). Seen in the gate; a follow-up if it bothers.
10. **Upstream merge friction** in `CodexLive.java`/`CodexSpeech.java`: kept additive (null = today's code).

## Open questions
None that block. Two defaults Nicholas may overrule once he has tried it (in decisions.md): the call button on the
talk screen, and half-duplex calls.
