# Decisions: 012-watch-audio-device
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-16 - Watch is a virtual entry, Auto for routing, listed last, always in the github build
- Options: a real Android device (impossible: 001 verify, Samsung relays watch calls); show Watch only while a watch
  is connected (needs a cached async state for the synchronous `choices()`); show it always in builds with the link,
  with the unreachable state in Setup's note and the spoken fallback notice.
- Choice: key `"watch"`, appended last in `AudioRoute.choices` when the link class loads; `use`/`reconcile` treat it as
  `""` (Auto) because the brief offers "always with a clear unreachable state", it keeps `choices()` synchronous, and
  last keeps 001's devices in their order.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Seam: `WatchVoice.Link`, loaded by reflection
- Options: `Class.forName` of a watchlink class; a manifest ContentProvider that registers the hook at start;
  `Flavor.java` returning it.
- Choice: `Class.forName("app.buddy.assistant.WatchVoiceLink")`, null when absent, because it is one line, works in
  all three builds (Gradle github, F-Droid, `build.sh`, which compiles `src/github` without watchlink) and minify is off.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Phone TTS reaches the watch as audio (synthesizeToFile + onAudioAvailable), not as watch TTS
- Options: the watch's own TTS reads the text (`/buddy/say`-like); the phone synthesizes silently and streams PCM.
- Choice: stream, because the brief asks for "the phone's chosen voice" (language detection and voice pick stay the
  phone's), and the watch then needs one PCM player for both voices. `onAudioAvailable` is documented for
  `synthesizeToFile` (API 24); engine behaviour is a device check (plan Risk 2).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Hook in `Voice.speak`, not only ChatHub
- Options: `ChatHub.add` only (replies); `Voice.speak` (replies, read-aloud cards, Setup samples).
- Choice: `Voice.speak`, because the device choice means "where Buddy speaks", the Setup sample then tests Watch on
  tap, and ChatHub's `!ses.watch` / `!CodexLive.on()` gates still prevent double speech.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The phone finds the watch by a new capability `buddy_watch_read`
- Options: `NodeClient.getConnectedNodes()` (any connected watch, even one without this build); a capability the new
  watch build declares.
- Choice: capability with `FILTER_REACHABLE`, because an older watch app can't play readings and must count as
  unreachable (phone + notice), and the watch already finds the phone the same way (`buddy_phone`).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Watch receiver: `ReadAloud` (WearableListenerService + mediaPlayback foreground service); refusal by close code
- Options: reuse Talk (per-chat, started only from the watch screen, 007 state machine); a new receiver. For "the
  watch didn't play it": a new message path, or `ChannelClient.close(channel, code)`.
- Choice: new `ReadAloud`, channel `/buddy/read/<id>`, close code 1 = NOT_PLAYED, because Talk/Call/Speaker stay
  untouched (007 unchanged) and the close code needs no new message. It refuses while a watch talk waits or a call is
  on, and when Android refuses its foreground start; `TalkActivity` listen/call silence a reading.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - "Say so once": a spoken notice before the first phone fallback of an episode
- Options: a toast; a chat line; a notification; a spoken sentence.
- Choice: spoken, as its own utterance before the reply, once until a watch reading plays again, because the user of
  read aloud is listening, not looking, and it works the same for both voices.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Fallback only when nothing was played; readings get their own PcmOut bounds
- Options: replay on the phone after any break; replay only when the watch played nothing. Bounds: 007's (10 s cap,
  60 s abandon) or larger ones for readings.
- Choice: the phone reads it only on `refused` (no watch found, channel not open in 10 s, close code 1); a break
  partway ends the reading, because the phone can't know what was heard and the brief says "once". Readings use a
  10-min cap and 11-min abandon because phone TTS is faster than real time and 007's bounds would cut long replies;
  `PcmOut` itself is unchanged.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - One reading in flight, rules in a pure-Java `ReadQueue` (plan-review round 1 F1, F6)
- Options: a FIFO feeding concurrent watch streams and phone utterances (first plan); an ordered queue inside `Voice`
  for every caller; one reading in flight in the new route only.
- Choice: one in flight. The next text starts only once the current one has settled (played, broken, cap reached, or
  read on the phone up to its TTS `done`), and the reply follows the notice only after the notice's `done`. This keeps
  order through cold engines and language detection, gives Voice at most one routed request (its own slot, so the
  existing `pending` rule stays), and lets the watch keep no queue. The rules sit in `ReadQueue` behind one `Out`
  interface so they can be unit-tested with a fake (the second implementation). Every callback is dropped unless its
  reading and stream are still current, which makes stop safe against late callbacks. Supersedes the FIFO and
  "one find serves everything queued" parts of the coordinator entry above.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The watch confirms playback with close codes; `ReadStream` owns the outcome (round 1 F3, F4)
- Options: infer `played` from any close after `finish()` (first plan); a confirmation message path; close codes.
- Choice: codes. 2 PLAYED (`eof` after sound), 1 NOT_PLAYED (nothing sounded, busy, service refused, player failed
  before sound), anything else broken (hush, break after sound, disconnect's code 0). The phone's `ReadStream` also
  refuses when its producer wrote nothing, and on PcmOut's write failure or overflow it waits up to 3 s for the code
  before calling it broken, so a refusal is never lost to a write race. No new message path; synthesis finishing proves
  nothing. Supersedes "any other close after finish = played" in the two entries above.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Watch busy covers Talk until its last reply and the recognizer; newest reading wins (round 1 F2)
- Options: `Talk.waiting || Call.on` only (first plan); a busy predicate with small hooks in Talk/TalkActivity.
- Choice: `Talk.busy()` (`waiting || instance != null`) || `Call.on` || `TalkActivity.dictating()` (stamped in `listen()`,
  cleared in `onActivityResult`, 60 s at most), checked on channel arrival and again before playing. Talk gets one
  static method and TalkActivity a timestamp, with no behaviour change to 007. With one reading in flight on the phone,
  a new channel replaces any old one instead of queuing.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Routed TTS settles on every exit; one 11-minute cap per reading (round 1 F5)
- Options: rely on progress callbacks and PcmOut's abandon timer (first plan); settle every exit and cap the reading in
  the coordinator.
- Choice: `Voice.routed` runs `done` once for engine-init failure (`ttsFailed`), a synchronous ERROR,
  `onDone`/`onError`/`onStop`. An empty stream is a refusal, so a phone-TTS failure on the watch path falls back to the
  phone once, and the chain ChatGPT voice → phone TTS on the watch → phone ends without retry. `ReadQueue` caps each
  reading at 11 min from its start, independent of the producer, so a hung engine or page can't hold the queue. One
  fixed `cache/read.wav` (one reading in flight) is deleted on every exit.
- By: Planner (claude-opus-5-5)
