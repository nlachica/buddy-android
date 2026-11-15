# Decisions: 007-watch-live-voice
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-15 - Both parts keep the call in the phone's WebView and bridge its audio to the watch
- Options: Codex app-server `websocket` transport (PCM through JSON-RPC, no WebView); WebRTC on the watch; the
  `existingCall` transport; the phone's existing WebRTC page with its audio bridged (mic in through `ownTrack`/`pull`,
  voice out through a Web Audio tap).
- Choice: the bridge, because the Planner's probe (2026-11-15, codex-cli 0.169.0, ChatGPT-plan sign-in) got
  "realtime conversation requires API key auth" for `websocket`, while `webrtc` reached OpenAI. An API key would be a
  new provider (brief non-goal); Wear OS has no WebView and libwebrtc is a large native dependency; `existingCall`
  needs the client's own OpenAI credentials.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - One audio format: PCM 16-bit mono 16 kHz over ChannelClient, both ways, uncompressed
- Options: PCM 16 kHz; the voice's native 24 kHz down; μ-law 8-bit; Opus via MediaCodec.
- Choice: PCM 16 kHz, because the page's own-mic path already takes it and the watch records it; 32 KB/s each way.
  μ-law only if the Step 4 gate shows Bluetooth can't carry it.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Main code gets two null-safe seams: `CodexLive.Remote` and `CodexSpeech.Sink`
- Options: interfaces in main implemented in `src/watchlink`; watch code in main (breaks F-Droid); copies of the pages
  in watchlink.
- Choice: the interfaces, because Google code must stay out of main (003) and a null Remote/Sink is today's code
  exactly. A watch call skips the phone-mic check, `AudioRoute` and the screen-off end (its mic is the watch's); every
  other end rule stays.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Watch calls are half-duplex
- Options: full duplex with `VOICE_COMMUNICATION` + AcousticEchoCanceler on the watch; half-duplex (mic frames
  dropped while the voice is audible and 300 ms after).
- Choice: half-duplex, because the Watch4's AEC is unknown and mic and speaker sit 3 cm apart: a weak AEC makes the
  voice interrupt itself. Cost: no talking over the voice. Nicholas may ask for full duplex after trying it.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Watch call UI: a call button on the talk screen; End or swipe-away hangs up; 30-minute cap
- Options: button on the talk screen; per-row button or long-press in the chat list. Swipe-away ends or keeps the call.
- Choice: the talk screen's round button row (`ms_graphic_eq`, the phone's live icon) opening `CallActivity`;
  `RECORD_AUDIO` asked on the first call. Swipe-away hangs up like Talk's `left()`, so a mic is never left open
  unseen; wrist down keeps it (it's a call). A 30-minute cap bounds the wake lock and the open mic.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Watch replies in ChatGPT's voice: per-reply id, text first, audio on `/buddy/voice/<id>`, said once
- Options: one channel per conversation with framing; a channel per reply; fallback by the watch's timeout only, or
  also a phone signal.
- Choice: `/buddy/reply` carries `voice: id`, then the phone streams that reply on its own channel; CodexSpeech's
  per-item Fallback sends `/buddy/say {id}`, and the watch also falls back after 35 s at the head of its queue (dead
  link). Whichever comes first wins; audio that started is never re-spoken (CodexSpeech's rule). Only BUDDY replies;
  errors and the watch's own lines keep the watch TTS. One ordered speech queue in Talk keeps TTS items flowing to the
  engine exactly as before, so with ChatGPT's voice off nothing changes.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - CodexSpeech routes per item instead of a second instance
- Options: a second CodexSpeech instance for the watch (static → instance rewrite); per-item `{text, Fallback, Sink}`
  in the one queue, the page's output switched between items.
- Choice: per-item routing, because it is a smaller additive change to an upstream file. A broken watch Sink calls
  `stop()` (drops the rest, no fallbacks), so a hushed reply isn't read into nothing while later ones wait; a phone
  read-aloud playing at that moment stops too (rare, accepted).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - A watch call keeps the phone quiet via `ses.watch`; a watch send warms Codex when ChatGPT's voice is on
- Options: a new `CodexLive` condition in `runEnded`; reuse 003's `ses.watch` flag. Warm-up: none, or the existing
  `CodexDictation.prepare`.
- Choice: `ses.watch` set for the call (cleared at its end unless a talk run tracks the chat), so no run-ended
  notification buzzes the wrist mid-call and the phone's own call behaviour doesn't change. `prepare()` on `deliver()`
  cuts the cold-server part (~5 s of 7.7 s in `live.log`) of (b)'s first-reply latency; one existing call.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The watch alone decides who says a reply; the voice owns it from its first audible block (round 1, F1)
- Options: first PCM received; first audible block written to the AudioTrack; AudioTrack playback position; the
  phone's own `lastSound` sent over.
- Choice: the first audible block (peak > 0.01 of full scale, the phone page's threshold) written to the not-live
  Speaker, because the tap streams silence from the moment an item is given, and in reply mode nothing written is ever
  dropped, so written means heard unless hushed. Playback position adds nothing over that. `/buddy/say` only converts an
  item that isn't the voice's yet, so the phone and the watch can't both say it.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - CodexSpeech retires one watch reply by its Sink; replaces "a broken Sink calls `stop()`" (round 1, F2)
- Options: global `stop()` (accepted cutting a phone read-aloud); `cancel(sink)` that lets the current text read into
  nothing; `cancel(sink)` that hangs up and calls again with the queue kept; a second CodexSpeech instance.
- Choice: `cancel(sink)` with hang-up and re-call, because the brief keeps the phone's voice behaviour unchanged and a
  re-call is today's path after an idle hang-up (~1-3 s, only when something waits). A Sink matching no item is
  ignored. The phone's leftover texts stay joined into one Fallback call, as today (Voice's TTS keeps only the last
  text while initialising).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - One audio owner on the watch: the last one started wins; a call owns its chat (round 1, F3)
- Options: a call and a typed talk side by side with a shared mixer; defer typed speech until the call ends; the last
  started ends the other.
- Choice: the last started wins (call button: Talk ends and hushes, lines kept; a new talk hangs up the call), as 003
  already does when a new talk replaces one still being said. On the phone the call takes its chat off the 003 relay
  (its replies reach the watch through the call's voice, as on the phone) and the relay's voice branch is off during
  any call, as the phone's own read-aloud is (`ChatHub.java:1006`).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Bounded audio queues: call 2 s drop-oldest, reply 10 s then broken; replies never drop (round 1, F5/F6)
- Options: unbounded with drain-only close (round 0); large reply buffers (a whole reply, minutes); 10 s.
- Choice: a call keeps 2 s and drops the oldest (it must stay live; the watch Speaker also drops past 1 s); a reply
  keeps 10 s and then fails, which the watch turns into its TTS when nothing was audible yet. 10 s, because a queued
  reply only waits behind the previous reply's tail or a short watch TTS line, and a watch that stopped listening
  (talk swiped away while the run goes on) then wastes at most 10 s of voice. `cancel()` discards and closes the
  channel to unblock a stuck write; `finish()` drains for at most the queue's length plus 5 s.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - A reply's normal end is EOF on the stream; the watch closes the channel; 60 s abandoned bound (round 2, F1)
- Options: drain then `close(channel)` (round 1; per ChannelClient's reference it makes the peer's reads fail, so a
  waiting reply or its buffered tail is lost); drain then close only the OutputStream, the watch closes the channel
  after reading EOF, plus a bound for a watch that never does.
- Choice: EOF plus the watch's close, with PcmOut cancelling 60 s after `finish()` if the channel is still open. It
  replaces round 1's drain bound (queue length plus 5 s). 60 s, because it is past the watch's 35 s head deadline and
  any honest wait. A reply cut by that bound with nothing audible becomes the watch TTS, so the bound only changes
  which voice says it. Calls keep cancel-only ends (a hang-up).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - CodexSpeech's re-call is isolated by a fresh thread and session checks; no JVM test of it (round 2, F2)
- Options: wait for the old stop to finish before reusing the thread (depends on the server's event order); a fresh
  thread for the re-call, with every posted runnable checked again when it runs and the start worker publishing only
  under a lock; Robolectric or seams to unit-test CodexSpeech.
- Choice: fresh thread plus checks, because the isolation holds on our side by construction. Retired threads stay
  claimed so their late events don't reach dictation. No unit test: no Robolectric in the app, and CodexSpeech is a
  static WebView class in an upstream file. Code review checks the rules, and a device run checks the outcome.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - A watch call's cleanup clears its own chat's flag and the shared wake lock by separate rules (round 2, F3)
- Options: keep both while any newer watch call is on (round 1); a per-chat rule for `ses.watch` and a global one for
  `buddy:watch`.
- Choice: separate. `ses.watch` stays only if the live call is in the same chat or a talk run tracks it. The wake lock
  stays while any watch call is on or any run is tracked. Otherwise chat A, already untracked, would stay muted on the
  phone with nothing left to clear it.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Coder: details the plan left open
- Options / Choice (all small, none changes the design):
  - `Speaker` (watch): a live call that is over 1 s behind drops the **newest** audio (non-blocking `AudioTrack.write`,
    1 s track) instead of flushing the old: no playback-head rebasing, same bound. "Audible now" is the last audible
    block's play-out time (write time + the track's lag), not 300 ms after its write, so the call's half-duplex gate
    (+300 ms) covers audio still queued in the track. At EOF the Speaker lets the buffered audio play out before it
    reports `eof`; on a break (`closed`) it stops at once.
  - `PcmOut` takes its stream late (`attach(OutputStream, Closer)`) so audio written while the channel opens waits in
    its queue; it has no header (the header is the watch's, on the other direction). `broken()` lets the owner fail it
    (the channel never opened). A reply's channel callback is one `ChannelCallback` registered on first use and keyed by
    the channel path.
  - `TAP_JS` is `tap(ctx, stream, on)`: `on()` decides per block whether to forward, so CodexSpeech's page flips it with
    `route()` and CodexLive's is always on. The page's `<audio>` is muted (not `volume = 0`) as the plan says; Risk 2's
    fallback is unchanged and unexercised (needs a device).
  - CodexSpeech: `current` stays set until the item has been read and has gone quiet (a `read` flag replaces
    `current == null` as "text read"), so `cancel(sink)` and the tap can find the item.
  - Watch `Call`: state for the screen is static (like `Talk`); the newest `start()` closes the older `Run` before it
    publishes its own, so `onStartCommand` only has to begin `current`.
- By: Coder (claude-sonnet-5-5)
