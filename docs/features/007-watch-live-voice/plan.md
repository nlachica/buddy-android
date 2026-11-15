# Plan: 007-watch-live-voice
<!-- Planner (claude-opus-5-5), 2026-11-15. -->

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
   AudioRecord 16k ──► channel /buddy/live ──────►    header {chat}, then PCM ─► CodexLive.push() ─► page ownTrack ─► WebRTC
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
  `AudioTrack` (`USAGE_ASSISTANT`, stream mode), starts after ≈300 ms buffered (and again after running dry), drops
  audio when more than ≈1 s behind to stay live, and reports whether it is audible now (peak over a threshold within
  the last 300 ms) and when the stream ends or breaks.
- The phone side: one `PcmOut` class (WatchCall and the relay both use it): `write(byte[])` never blocks (an unbounded
  queue and its own thread, since the caller is the WebView's JavaBridge thread), `close()` drains then closes, and a
  one-time `broken` callback on an IOException (the watch closed or vanished).

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
- `static void push(byte[] pcm)` (any thread) adds watch audio to the same `chunks` queue `pullCapture` reads, with the
  same 5 s cap. Chunks are 1280 bytes (40 ms), under `pull(6400)`.
- `status()` also calls `remote.status(t)` when the text changes; `end(why)` calls `remote.ended(text)` with the same
  sentence it notes in the chat (`live_ended` / `live_ended_why`), then drops the Remote.
Everything else is reused unchanged: transcripts → `utterance()` → `hub.send` (as typed), agent replies → `tell()`,
spoken approval answers (`answerCard`), the watchdog, the live bar and notification on the phone, `live.log`.

**Phone watch link** (`app/src/watchlink/`, github only):
- Manifest: the WatchLink service gets a second intent filter, `com.google.android.gms.wearable.CHANNEL_EVENT`, same
  `wear://*/buddy` data. `WatchLink.onChannelOpened(channel)` for path `/buddy/live` → `WatchCall.open(app, channel)`.
- `WatchCall` (new, about 130 lines): a thread reads the channel's input: the header line `{"chat": id}`, then 1280-byte
  frames → `CodexLive.push`. On the main thread it checks, in 003's order and with its strings: chat exists
  (`watch_chat_gone`), `BuddyService.get() != null` (`watch_buddy_off`), then `CodexLive.start(app, ses, remote)`; an
  error string → `/buddy/live/end {chat, text}` and close the channel. While on: `ses.watch = true` (the phone stays
  quiet: no run-ended notification, `ChatHub.java:989`; read-aloud is already off in any call, `:1006`), the
  `buddy:watch` wake lock held and re-acquired every minute while audio comes in. `Remote.pcm` → a `PcmOut` on the
  channel's output stream; `Remote.status` → `/buddy/live/status {chat, text}`; `Remote.ended` → `/buddy/live/end
  {chat, text}`, then the channel is closed ~500 ms later (so the reason usually lands first), `ses.watch` cleared
  unless a 003 talk run still tracks the chat, wake lock released unless one does. The input ending (the watch hung up
  or vanished) → `CodexLive.end(null)` on main, if that call is still this one.
- `WatchLink`: `releaseWake()` callers also check that no watch call is on; `endCheck` leaves `ses.watch` set for a
  chat in a watch call.

**Watch:**
- `TalkActivity`: a second round button next to the mic, the phone's live icon (`ms_graphic_eq`, copied), described
  "Call Buddy". Tap → `RECORD_AUDIO` asked once (system dialog on the watch; denied → a line "Calls need the
  microphone") → `Call.start(...)` and `CallActivity`. Starting a microphone foreground service from a visible activity
  is allowed.
- `Call` (new service, `foregroundServiceType="microphone"`, about 180 lines): finds the phone (`Phone.find`, the
  capability lookup factored out of `Phone.send`, same error sentences), `openChannel(node, "/buddy/live")`, writes
  the header, then records `AudioRecord` (`VOICE_COMMUNICATION`, else `MIC`; 16 kHz mono, 40 ms reads) into the
  channel. **Half-duplex:** while the Speaker is audible, and 300 ms after, mic frames are read and dropped, not sent
  (the watch's mic and speaker are 3 cm apart; the page's own echo cancellation only knows the phone's audio). The
  channel's input goes to a Speaker. A MessageClient listener takes `/buddy/live/status` (shown) and `/buddy/live/end`
  (shown, then hang up). Partial wake lock `buddy:call`, ongoing notification ("Call with Buddy · <chat>", tap →
  `CallActivity`). Ends on: End, `CallActivity` swiped away (`isFinishing()`, as Talk's `left()`), `/buddy/live/end`,
  the stream ending or breaking ("Lost contact with your phone"), or a 30-minute cap. Ending closes the channel,
  releases mic, AudioTrack and wake lock, and stops. Wrist down and the watch face coming back do not end it.
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
switch is clean. The watchdog's "read and quiet" also closes the item's Sink. `fail()` keeps its rule per item: the
current item gets its own Fallback only if the voice never started it (`lastSound < sentAt`), each queued item gets its
own, a started item's Sink is just closed. `stop()` closes the current Sink, no fallbacks.

**Phone relay** (`WatchLink.relay`, 003): for a `BUDDY` message to a tracked chat, when ChatGPT's voice is the
phone's engine (the same test as `Voice.speak`: `"chatgpt".equals(Prefs.speechEngine(app)) &&
SetupState.planSignedIn(app, "codex")`, `Voice.java:237`), it picks a voice id (a counter seeded from the clock, unique
across phone restarts), sends `/buddy/reply {chat, text, voice: id}` first, then `CodexSpeech.speak(app, text,
fallback, sink)`: the Fallback sends `/buddy/say {chat, id}`; the Sink is a `PcmOut` on
`openChannel(watch, "/buddy/voice/<id>")`. A broken Sink (the watch closed it: hushed, or already said it itself) →
`CodexSpeech.stop()`, so a long text isn't read into nothing while later replies wait. `ERROR` replies keep the watch
TTS (they often come from the failures that also break the voice). `deliver()` also calls
`CodexDictation.prepare(app)` when ChatGPT's voice is on: the Codex server is warm by the time the answer comes (a
cold server cost ~5 s of the 7.7 s call setup in `live.log`). No `AudioRoute`/`SpeechPause`: nothing plays on the phone.

**Watch Talk: one ordered speech queue.** `early` becomes `toSay`, a list of `{text, voiceId}` (−1 = TTS):
- A TTS item at the head goes straight to the engine (`QUEUE_ADD`, counted in `pending`) as today, or waits for init
  as today; so with ChatGPT's voice off nothing changes.
- A voice item at the head waits until `pending` is empty (earlier TTS said), then plays its channel through the
  Speaker; the items after it wait. Its channel can come before its reply message: channels are kept by id.
- **Said once, by whichever comes first:** its first audio plays → it is the voice's (a break later ends it, never
  re-spoken: CodexSpeech's own rule, `CodexSpeech.java:208`); `/buddy/say`, its channel ending before any audio, or
  35 s at the head without audio (the phone's fallback normally comes sooner: a dead Codex server or a failed request
  fails at once) → it becomes a TTS item, and audio that comes for it later is closed unread (which stops the phone's
  CodexSpeech, above).
- When the wait ends by failure (lost contact, no answer, ceiling), waiting voice items become TTS at once.
- The MessageClient listener stays until Talk stops, not just until the end, but after the end it only takes
  `/buddy/say`. A `ChannelClient.ChannelCallback` is registered from start to stop. `hush()` (mic button, a new
  conversation) also stops the Speaker and closes channels. `maybeStop()` counts voice items. The 5-minute force stop
  after the end still caps it.
- Log lines (`BuddyTalk`): reply arrived, first sound (voice or TTS) per reply, for the latency measurement.

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
| `/buddy/live` (channel) | W→P | line `{chat}\n`, then PCM 16 kHz mono s16le; nothing while the voice is audible | the call; closing it hangs up |
| `/buddy/live` (channel) | P→W | PCM 16 kHz mono s16le | the voice |
| `/buddy/live/status` | P→W | `{chat, text}` | the phone's call status line |
| `/buddy/live/end` | P→W | `{chat, text}` | the call ended or was refused; text as noted in the chat |
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

1. **CodexLive remote mode (main).** `CodexLive.java`: `Remote`, `start(c, s, r)`, `push()`, `Buddy.remote()` /
   `ownMic()` / `pcm()` interface methods, the skips listed in Design, `remote.status/ended`, PAGE: remote branch in
   `start()` and `ontrack`. `CodexSpeech.java`: the `TAP_JS` constant only. Log lines in `live.log` mark a watch call.
2. **Phone: the call bridge (watchlink).** New `PcmOut.java`, new `WatchCall.java`; `WatchLink.java`
   (`onChannelOpened`, wake-lock and `ses.watch` rules); `app/src/watchlink/AndroidManifest.xml` (`CHANNEL_EVENT`).
3. **Watch: Speaker and phone lookup.** New `wear/.../Speaker.java`; `Phone.java`: `find()` factored out of `send()`
   (same behaviour), the new path constants.
4. **Watch: the call.** New `Call.java`, `CallActivity.java`; `TalkActivity.java` (call button, permission);
   `Talk.java` (ignore `/buddy/live/*`); `wear/src/main/AndroidManifest.xml` (`RECORD_AUDIO`,
   `FOREGROUND_SERVICE_MICROPHONE`, the service with type `microphone`, the activity); `strings.xml`;
   `res/drawable/ms_graphic_eq.xml` (copied from `app/`).
   **Gate (orchestrator, before Step 5's device work, not before its code):** CI build installed on phone and Watch4;
   Test plan 1 incl. the locked phone, plus channel throughput (`live.log` rtp lines keep coming, the watch's Speaker
   doesn't starve). A WebView call that dies with the screen off, or a link that can't carry 32 KB/s, goes back to the
   Planner (Risks 1, 3) before (b) is verified.
5. **CodexSpeech per-item routing (main).** `CodexSpeech.java`: `Item`, `Sink`, `speak(c, text, f, sink)`, `route()`
   in its page plus `TAP_JS`, per-item fail/stop/close as in Design. Phone read-aloud unchanged.
6. **Phone: watch replies in ChatGPT's voice (watchlink).** `WatchLink.java`: the relay's voice branch, `/buddy/say`,
   per-reply `PcmOut` channel, broken Sink → `CodexSpeech.stop()`, `CodexDictation.prepare` in `deliver()`.
7. **Watch: voice replies in Talk.** `Talk.java`: the ordered queue, channel callback, `/buddy/say`, timeouts,
   failure conversion, `hush()`, `maybeStop()`, log lines (as in Design).
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
| 1 call from the watch, phone locked | Devices. Phone screen off and locked for 1+ min, in a pocket. Watch: chat → call button → (first time) allow the mic → "Connecting…" then "Listening…"; ask "what time is it" → the voice answers from the **watch** speaker; the phone stays silent; the phone chat shows the words as the user's message and the reply. Talk for 2+ min with the wrist down part of the time. `~/.buddy/live.log`: the watch-call line, no `getUserMedia`, `rtp 10 s` lines all along, no "screen off". End on the watch → the call ends within 2 s: the phone's live notification and live bar go, the chat notes "Live voice ended.", watch `dumpsys activity services app.buddy.assistant` shows no `Call`, `dumpsys power` on both shows no `buddy:call` / `buddy:watch`. Also: End on the phone's notification → the watch shows "Live voice ended." and stops; swipe `CallActivity` away → hangs up; Codex signed out → the watch shows `dictation_codex_sign_in`'s text; Bluetooth off mid-call → the watch says it lost the phone, the phone ends the call. |
| 2 replies in ChatGPT's voice, else watch TTS, once | Devices. Phone Settings → Voice → ChatGPT voice. Watch talk "tell me a short joke" → the reply text shows, then is heard in ChatGPT's voice (Juniper etc., clearly not the watch TTS) from the watch; the phone is silent; `BuddyTalk` log: one "voice" first-sound line for the id, no "tts" line. Engine set back to the phone voice → the same question is said by the watch TTS (003 path, no `voice` in the reply). **Failing, once each:** (i) `pkill -f "codex app-server"` in Buddy's Linux right after sending → CodexSpeech fails → `/buddy/say` → the watch TTS says it once (log: one "tts" line, no "voice" line); (ii) watch Bluetooth and Wi-Fi off once the text has shown → the watch TTS says it within ~40 s, once; (iii) a long reply, Bluetooth off mid-sentence → the voice stops, nothing is repeated; (iv) the mic button mid-reply → silence, and the next question's reply comes without waiting for the old one (the phone's `CodexSpeech` was stopped). |
| 3 cost of (b) in verify.md | Devices. Latency: 5 replies with each engine; `BuddyTalk` "reply arrived" → "first sound" per reply, median and range, cold and warm. Battery: watch and phone unplugged, `dumpsys batterystats --reset` on both, the same 5-question script per engine, then `dumpsys batterystats --charged` per UID (watch: `app.buddy.assistant` and the TTS engine's package; phone: Buddy). The table plus this plan's estimate go into verify.md. |
| 4 003/006 unchanged, CI, F-Droid | With the phone voice engine: 003's talk row (list, speak, reply shown and said once, reopening shows it without speaking again, 006) and quick talk. The phone's own live call from the chat screen still works and still ends when the screen goes off; phone read-aloud in ChatGPT's voice for a phone chat still plays on the phone. CI `build.yml` on the branch green, including the "F-Droid APK has no Google code" step (`build.yml:80`). `FLAVOR=github bash build.sh`'s javac step compiles `app/src/main` (or `javac` against android.jar as in 003). Diff holds only the files in Steps 1-9. |

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
   and stay-live drop absorb jitter; a link that can't carry it → μ-law (back to the Planner).
4. **Half-duplex:** the user can't interrupt the voice by talking; they wait for it (status "Speaking…").
5. **Open mic on the wrist:** ambient speech is transcribed and sent to the chat like the user's words, and keeps the
   call from going idle. Bounded by End, swipe-away, the 30-minute cap and the ongoing notification; a call is what
   the user asked for.
6. **Experimental Codex realtime API:** all of `thread/realtime/*` is marked EXPERIMENTAL, the webrtc path Buddy uses
   today included; nothing new here, but a Codex update can break both parts at once (watch TTS stays for (b)).
7. **ChatGPT plan voice use:** (b) opens a realtime session per burst of watch replies.
8. **A broken watch Sink stops all CodexSpeech** (`stop()`), including a phone read-aloud of another chat playing at
   that moment. Rare; accepted for a much simpler CodexSpeech.
9. **Phone notification mirrored to the watch:** the phone's ongoing live notification may also appear on the watch
   during a call (Galaxy Wearable). Seen in the gate; a follow-up if it bothers.
10. **Upstream merge friction** in `CodexLive.java`/`CodexSpeech.java`: kept additive (null = today's code).

## Open questions
None that block. Two defaults Nicholas may overrule once he has tried it (in decisions.md): the call button on the
talk screen, and half-duplex calls.
