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
