# Plan: 018-live-voice-watch
<!-- Planner (claude-opus-5-5), 2026-11-17. Sources: brief.md, diagnosis.md, research.md, code read at d1d3226
     (AudioRoute, CodexLive incl. PAGE, CodexSpeech.TAP_JS, WatchVoice, watchlink WatchVoiceLink/ReadStream/PcmOut/
     WatchCall, wear ReadAloud/Speaker, VoiceBar, SettingsTool, SetupActivity), 007/012/016 decisions, 007 verify.
     Revised after plan review round 1 (F1-F4): headset call device chosen and confirmed, comm-mode-safe Auto decision,
     playback-aware mic mute, Android 12+ scope (001). Revised after round 2 (F1, F2): route attempts bounded by a
     refused set, with the call stopped when no safe device is left; one 012 reading per utterance, and the mic reopens
     on the watch's own answer. -->

## Goal
During a live voice call started **on the phone** (CodexLive without a Remote, the WebView holding
`MODE_IN_COMMUNICATION`), the audio device setting must be honoured. (1) **Bug:** with **Watch** picked, the call's
voice must never fall to the earpiece. Today `AudioRoute.want()` turns `watch` into `""`, so `reconcile()` → `drop()` →
`clearCommunicationDevice()` and Android uses the earpiece (diagnosis). While the watch isn't carrying the voice, Watch
means the phone loudspeaker, and a connected headset still comes first (016). (2) **Feature:** when the setting is
Watch, or Auto with no headset and a reachable watch (016 order), the call's voice plays on the Galaxy Watch speaker.
The microphone stays the phone's. If the watch can't take it or drops, the voice goes to the phone loudspeaker.
Done when: (a) a phone live call with Watch picked never uses the earpiece, the voice is heard on the watch, and with
the watch unreachable it plays on the phone loudspeaker; (b) picking Watch mid-call doesn't clear the route to the
earpiece, and ending the call restores normal mode; (c) read aloud on the watch (012) and 016 Auto are unchanged, and
CI is green. Platform: (a) and (b) are for Android 12+, the scope 001 set for all audio routing ("On API 30, Buddy
behaves as today", 001 decisions). On Android 11 a phone live call stays exactly as today and the Watch texts say so
(Open questions).

Non-goals (brief and research): calls started from the watch (007: they keep their Remote, routing and half-duplex);
the watch's microphone for phone-started calls (research: 007's Remote is all-or-nothing and watch-initiated, so it
doesn't fit simply); voices; Auto's order itself, including Auto during live voice when no watch takes the voice (it
stays on Android's routing as today, see Open questions); moving the voice when a headset connects or disconnects
mid-call (only a change of the setting moves it; the phone route does follow headsets, Part 1); any change to the watch
app (the 012 receiver is reused as it is); a legacy Android 11 routing path (001).

## Design

### Overview
```
phone chat ─ CodexLive page (WebView): phone mic ─► call            voice ◄─ call
                                                              │
                     out.muted = fwd, tap(...) while fwd ◄────┘
                     Buddy.toWatch() polled every 70 ms ──► LiveWatch (Google-free): bit 1 = the voice is on the
                       (fwd = bit 1; mic track off while bit 2)  watch, bit 2 = the mic is closed (watch not done)
                     Buddy.pcm(b64) ─► LiveWatch.pcm ─► silence gate ─► one 012 reading per utterance
                                         answer (played/refused/broken) ◄─┤ WatchVoice.link().open, as for read aloud
                                         channel /buddy/read/<id>, closed by ◄┘ (watchlink: ReadStream + PcmOut, as is)
                                         watch ReadAloud + Speaker (012, unchanged) once its player is silent
AudioRoute.reconcile(): Watch picked (or Auto once the watch took the voice) in a phone call, Android 12+
                   → its own branch: a headset's own call device (wired/USB/BLE/hearing aid, or the SCO of a connected
                     media headset), confirmed; else the built-in speaker. Never the earpiece; nothing safe → the call stops.
```

### Part 1: routing during a phone live call (`AudioRoute`)
In communication mode with no communication device set, Android plays the call on a wired or USB headset or a hearing
aid by itself, else on the earpiece. It never plays it on A2DP: a Bluetooth headset gets the call only over SCO, which
the app must select (`setCommunicationDevice`, API 31+). This is from Android's routing rules, reasoned and not
verified on the device; D8 checks it. So "a headset keeps priority" in a call means selecting that headset's own
communication device, and checking that it took.

- New pure rule `static int liveDevice(int[] comm, String[] commName, int[] out, String[] outName, Set<String>
  refused)`. `comm`/`commName` are the types and product names of `getAvailableCommunicationDevices()`, and
  `out`/`outName` those of `getDevices(GET_DEVICES_OUTPUTS)`. It returns an index in `comm`, or `NONE`. It skips any
  entry whose key (`type + ":" + name`, as `key()`) is in `refused`, and picks the first match of:
  1. **A headset's own call device:** a non-SCO type that is a headset (`headset(type)`: wired headset/headphones,
     USB headset, hearing aid, BLE headset/speaker; A2DP never appears in this list), or a `TYPE_BLUETOOTH_SCO` whose
     name equals the name of a connected media headset output (`headset(out[j])`), which is the call link of connected
     buds. The watch has no media output, so its SCO never matches (016 excluded it for the same reason). Buds without
     a call profile have no communication device and fall through. With several headsets, Android's list order decides
     (rare; apps see no connection times, 016).
  2. **Else the first `TYPE_BUILTIN_SPEAKER`.**
  3. **Else `NONE`.** It never picks the earpiece.
- **New pure `static int liveAttempt(int[] comm, String[] commName, int[] out, String[] outName, Set<String> refused,
  IntPredicate apply)` (review round 2 F1).** A loop, no recursion: `i = liveDevice(…, refused)`. `NONE` → return
  `NONE`. `apply.test(i)` → return `i`. Else add `key(i)` to `refused` and pick again. Each failed pass adds a key that
  wasn't in the set, and the list is finite, so it ends: buds → speaker → `NONE` at worst. The set is kept, so a later
  call with it tries nothing already refused.
- New pure `static boolean slipped(String applied, String actual, long sinceMs)`: `applied != null && sinceMs >=
  CONFIRM_MS (2000) && !applied.equals(actual)`.
- `want()` stays as today (Watch → `""`). The live rule doesn't go through it, so it never meets `""` → `drop()`.
- **`reconcile()` gets one branch**, after `afterLive()`/`sawLive`: when `LiveWatch.noEarpiece(Prefs.audioDevice(app))`
  and the mode is `NORMAL` or `IN_COMMUNICATION`, it runs the new `@TargetApi(31) live(am)` and returns. Otherwise,
  including a ringing or phone call, today's lines run unchanged, so 001/016 picks keep today's code.
  `LiveWatch.noEarpiece(saved)` holds when a **phone** live call is on (LiveWatch tracks it, so watch calls with a
  Remote never match) and either `saved` is Watch, or `saved` is Auto and this call's voice has been on the watch
  (`took`). With Auto before or without the watch, today's code runs (non-goal). With Auto after the watch took the
  voice, a drop then lands on the headset's call device or the loudspeaker, as the brief's "falls back to the phone
  loudspeaker" asks. The rule doesn't depend on whether the voice is on the watch right now, so a drop needs no route
  change.
- **`live(am)`:**
  1. **Confirm:** when `slipped(applied, key(am.getCommunicationDevice()), now - appliedAt)`: if `applied` isn't the
     speaker, add it to `liveRefused` (the headset's call link failed). Then `applied = null`, so the speaker, or the
     same speaker again if Chromium re-picked the earpiece, is applied below in this tick. One re-apply per
     `CONFIRM_MS` at most, because applying resets `appliedAt`.
  2. `i = liveAttempt(…, liveRefused, j -> key(comm.get(j)).equals(applied) || apply(am, comm.get(j)))`. `apply` calls
     `setCommunicationDevice`; true: `applied`, `appliedAt`, `dirty = true`. The WebView owns the mode in live voice,
     so `live()` never sets it.
  3. **`NONE`** (the speaker refused or missing): log "route: no safe device", and once (`stuck`) post
     `CodexLive.end(getString(R.string.live_ended_no_speaker))` ("the phone speaker couldn't be used"). `live()` has no
     `drop()`, so there is no `clearCommunicationDevice()` during the call. On API 31+ the only other way to keep the
     voice off the earpiece is the deprecated `setSpeakerphoneOn`, which 001 rejected. So the call stops and says why.
  - Each route it applies, refuses, finds slipped or can't find is logged as `CodexLive.log("route: …")` in live.log,
    which is the evidence the device checks read.
- New state `liveRefused` (a set), `stuck` and `appliedAt`. `liveRefused` and `stuck` are cleared where `refused` is
  (`use()`, `drop()`, `afterLive()`, `finish()`). In `use()`, `if (!want(a).isEmpty()) applied = null;` gains `||
  LiveWatch.noEarpiece(saved)`. So `use()` (CodexLive.start, `connected()`, the voice bar chip) forces a re-apply
  over Chromium's earpiece pick and tries a refused headset again. The 300 ms poll's `reconcile()` keeps it applied.
  Picking Watch mid-call while a Speaker pick is applied: `liveAttempt` finds the speaker, which is already `applied`,
  so nothing is set and nothing is cleared. With a headset it moves to the headset's call device, and never through
  `drop()`. Coming from Auto or Earpiece, the poll applies it. This is done-when (b)'s first half. When the call
  ends, `LiveWatch` clears its state, the branch no longer runs, and `drop()`/`afterLive()`/`finish()` put things back
  as today. The WebView sets `MODE_NORMAL` on teardown (done-when (b)'s second half). Research risk 3 is covered: the
  one `clearCommunicationDevice` comes from `drop()`, and `finish()` clears only if `dirty`, as today.
- When the page opens the mic, Chromium picks the earpiece itself. Buddy puts the device back at `connected()`
  (`CodexLive.java:746`), as it does today for a Speaker pick (diagnosis: 0.8 s). The voice can't speak before
  `connected()`, because the texts queued for it wait for that call. A later re-pick is caught by the confirm check
  within a tick.
- The class javadoc's Watch sentence gains "except in a phone live call (017): a headset's call device, else the
  speaker".
- **The forwarding decision, comm-mode-safe (round 1 F2).** New pure `static String livePick(String saved, boolean
  savedHere, int[] connected, boolean watch)` = `pick(saved, savedHere, headsetIn(null, connected) != NONE, watch)`,
  with 016's media-headset types read from `getDevices(OUTPUTS)` only. New `static String liveTarget(Context c)` feeds
  it (Prefs, `indexOf(choices(c), saved) >= 0`, outputs, `WatchVoice.link() != null`). `target()`/`headsetNow()` ask
  Android's API 33 routed answer, which in communication mode follows the device Buddy set and hides a connected
  headset (`headsetIn` returns `NONE` for a routed `{SPEAKER}`, `AudioRouteTest:29`). They stay as they are for 016's
  normal mode.

### Part 2: the call's voice to the watch (`LiveWatch`, main, Google-free)
One class holds the rules, behind an `Out` interface so a fake can drive it in JVM tests (ReadQueue's pattern, 012). It
is main-thread only, except `toWatch()` and `pcm()` (the JavaBridge thread).

**Each utterance is one 012 reading (review round 2 F2).** The installed watch app closes a reading's channel with its
code only once its `Speaker` is silent: 2 after the playhead reached the last frame and the track was released
(`Speaker.java:144-159`), 0 after `stop()` paused and flushed the track (hush, replaced) or the stream broke, and 1 when
nothing sounded or it was busy (`ReadAloud.java:49-53,124-127,149-156`). The phone's `ReadStream` turns these into
`played`/`broken`/`refused` (012). So the end of each reading is the watch's own "the voice is quiet now", over the
existing link, with no watch update. An open-ended stream never ends, so it never gets that answer, and the phone
can't see audio held in the Data Layer (round 1's design). An utterance is what the silence gate passes: from an
audible block until 1 s of silence.

```java
interface Out {
    String saved();                       // Prefs.audioDevice(app): the setting now (cheap, polled)
    boolean watchWins();                  // WatchVoice.KEY.equals(AudioRoute.liveTarget(app)): round 1 F2
    void find(Consumer<String> node);     // WatchVoice.link().find
    ReadQueue.Stream open(String node, ReadQueue.Events e, Runnable attached); // link().open: one 012 reading
    void after(long ms, Runnable r);      // main Handler
    long now();                           // SystemClock.elapsedRealtime()
    void log(String line);                // CodexLive.log("watch: " + line) → ~/.buddy/live.log
}
```
State: `call` (a phone live call is on), `gen` (call number, for stale polls), `saved` (the setting this call last
decided on), `turn` (token of the current attempt, null when none), `node`, `watch` (the voice plays on the watch: the
phone is muted and forwards), `took` (it did in this call), `gate`, and the readings, guarded by the instance lock
(the bridge thread and main both touch them):
- `cur`: the open reading that takes audio now, or null. It is opened ahead, before the voice speaks.
- `fed`: `cur` has been given audio. The gate only opens on an audible block, so this means an utterance is going on.
- `drain`: the finished reading whose answer hasn't come yet, or null. There is at most one, and `cur` and `drain`
  never both exist: the watch plays one reading at a time and a new channel replaces the playing one there
  (`ReadAloud.java:55-62`), so the next reading is opened only after the answer.
- `held`: blocks that came while `drain` waits, in order. `heldEnded`: the gate closed after them.
- `openAt`: until when the mic stays closed after the last answer.

- **begin** (CodexLive.start of a phone call): `call = true`, `took = false`, `saved = out.saved()`, `decide()`, then
  the poll every `POLL_MS = 500`.
- **poll:** stale `gen` or no call → stop. If `out.saved()` differs from `saved`, store it and `decide()`. Only a
  change of the setting re-decides. This covers the voice bar chip, Setup and `buddy_settings` (SettingsTool doesn't
  call `use()`) with no hook in any of them.
- **decide:** `want = out.watchWins()`. If `want`, `turn == null` and `drain == null`, then `attempt()` (while a
  reading drains, its answer runs `decide()` again). If `!want` and `turn != null`, then `stop()`. A change between
  Watch and Auto that keeps the watch first changes nothing. With no link (F-Droid, `build.sh`), `liveTarget()` never
  returns Watch, so nothing is attempted.
- **attempt:** new `turn` token `t`; log "looking". `out.find(n -> …)`: a stale `t` → ignore. `null` → `turn =
  null`, log "none found: phone". Otherwise `node = n`, `openNext(t)`.
- **openNext(t)** (main): `cur = out.open(node, events(t, s), attached(t))`, `fed = false`. Then the `held` blocks go
  into it in order (`fed` if any); if `heldEnded`, it is finished at once (as at the gate's close, below).
- **attached(t)** (main, the reading's channel is open): if `turn == t` and `!watch`: `gate` reset, `watch = took =
  true`, log "playing there". The phone mutes on attach, not before. So an unreachable watch never silences the phone
  (the brief's "watch unreachable → phone loudspeaker"). A busy watch refuses in a moment, so little voice is lost.
- **events(t, s)** (main), by which reading answered:
  - `s == drain`, any answer: `drain = null`, `openAt = now + TAIL`, log "played n (+X ms after its end)" or the
    reason. Then: `played` and `turn == t` → `openNext(t)` (the next reading, opened ahead, with the held blocks);
    `refused`/`broken` and `turn == t` → `fallback`; `turn != t` (the setting moved meanwhile) → `decide()`. A
    waiting reading's answer counts whatever attempt it came from, because it is what reopens the mic.
  - `s == cur` and `turn == t`, `refused`/`broken`: `fallback`. ReadStream already turns every way the watch can't
    play into one of these: refusal code 1 (busy with its own talk, call or dictation, or its foreground start
    refused), close code 0 (`ReadAloud.silence()` when the watch's mic is used, a disconnect, another reading
    replacing it), a channel not open in 10 s, and a write failure or overflow after the 3-s grace.
  - Anything else (an old attempt's open reading) → ignored.
- **fallback:** `cur.cancel()` if any, `cur = null`, `held` cleared, `turn = null`, `watch = false`, log the reason.
  If `fed` was set, `fed = false` and `openAt = now + TAIL` (the watch answered after its player went silent).
  `took` stays set, so AudioRoute keeps the live rule (a headset's call device, else the speaker) under Auto too.
  There is no retry in this call until the setting changes.
- **pcm(b)** (bridge thread, under the lock): `!watch` → return. `keep = gate.keep(b)`.
  - `keep`: to `cur` if there is one (`fed = true`), else to `held` if `drain` waits, else dropped (no reading yet).
  - `!keep` and `fed` (the gate closed after an utterance): `cur.close()` (012's finish: the queue drains, the stream
    ends, the watch plays to the end and answers), `drain = cur`, `cur = null`, `fed = false`, and
    `out.after(ANSWER_MS, give-up check for this drain)`.
  - `!keep`, `drain` waits and `held` isn't empty: `heldEnded = true`.
- **stop** (the setting moved away): `turn = null`, `watch = false` (the voice is back on the phone now), `held`
  cleared. A fed `cur` is finished, not cancelled, and becomes `drain`; an unfed one is cancelled. `drain` keeps
  waiting: the mic stays closed until the watch has finished the utterance it has (`toWatch()` 2).
- **give-up** (`ANSWER_MS = 30_000` after a reading's end, still no answer: a link that neither plays nor disconnects):
  if it is still `drain`, cancel it, clear it and `held`, `openAt = now + CANCEL_TAIL (1000)`, log "no answer:
  phone", and `fallback` if on the watch. A cancel closes the channel, which makes the watch's reads fail
  (ChannelClient close contract, 007 plan review round 2), so its `Speaker` releases the track.
- **end** (CodexLive.end): cancel `cur` and `drain`, clear the state, `call = false`, `took = false`, `gen++`.
- **toWatch()** (bridge thread), two bits. Bit 1: `watch`. Bit 2 (the mic is closed): `fed || drain != null ||
  !held.isEmpty() || now < openAt`. So 0 is today's call; 1 the voice on the watch, mic open; 3 on the watch, mic
  closed; 2 the voice back on the phone while the watch finishes an utterance. The mic closes at the first audible
  block given to the watch and reopens `TAIL = 300` ms after the watch said that reading is played. `TAIL` covers the
  watch speaker's own output after the track is released; the answer's transit comes on top. A reading ends after the
  gate's 1 s of silence, which the watch plays too, so the mic reopens about 1.3 s after the voice's last sound on the
  watch, as round 1's tail did. One threshold, `Gate.loud` = peak > 0.01 of full scale (the watch's
  `Speaker.AUDIBLE`), decides what starts an utterance. The page's 0.02 `voice` flag only drives the bar.
- **noEarpiece(saved)**: `call && (WatchVoice.KEY.equals(saved) || (saved.isEmpty() && took))`.
- **Gate** (nested, pure): `static loud(pcm16)`, and `keep(pcm16)`, which returns true while the last loud block is at
  most `HANGOVER = 32_000` bytes (1 s) behind. It starts closed, so the silence before the voice first speaks isn't
  sent. It cuts the voice into readings, and it keeps delay from piling up: the watch's 012 reading player never
  drops (blocking writes), and the tap streams continuous silence. Short pauses inside speech are kept.
- **Failure policy.** What the watch answers decides the mic; nothing is guessed in normal flow. Lag inside one long
  utterance (a Bluetooth stall; 012's reading queue holds up to 10 min) only delays the voice. It can't open the mic,
  which waits for the answer. The next reading waits for that answer, so lag never makes two readings overlap. A
  refusal, a break or a disconnect falls back to the phone loudspeaker after the watch's player is silent or gone. A
  watch silent for 30 s is given up (above).
- A static facade (one instance, created on the first call with the Android `Out`) gives CodexLive and AudioRoute
  static entry points. The facade's `begin` returns at once below API 31 (round 1 F4, 001's platform rule), so on
  Android 11 nothing changes. The instance class is what the tests build. Names are left to the Coder.

### Watch link: an `attached` callback (`WatchVoice.Link`, watchlink)
- `WatchVoice.Link` gets `ReadQueue.Stream open(Context c, String node, ReadQueue.Events e, Runnable attached)`. The
  existing 3-argument `open` becomes a default method that passes `null`, so ReadQueue (012) is unchanged.
- `WatchVoiceLink.open`: in the output stream's success listener, after `s.attached(o, …)`, it runs `attached` if one
  was given. Google task listeners run on the main thread. Nothing else changes: same path `/buddy/read/<seq>`, same
  `READ_CAP`, same `ReadStream` and `PcmOut`. The watch app already installed (012+, capability `buddy_watch_read`)
  plays each utterance as a reading.

### CodexLive (upstream file: additive, a null-safe no-op for watch calls and F-Droid)
1. `start()`: in the existing `if (r == null)` block after `openPage()` (next to the screen-off receiver), call
   `LiveWatch` begin. `AudioRoute.use(app)` earlier in `start()` only posts its reconcile, so it already sees the call.
2. `end()`: call `LiveWatch` end right after `if (s == null) return;`.
3. Bridge `pcm(b64)`: if `web != mine`, return; decode; give it to `r.pcm(b)` when `remote != null`, else to
   `LiveWatch.pcm(b)`.
4. New bridge `@JavascriptInterface int toWatch()`: `web == mine && remote == null ? LiveWatch.toWatch() : 0`.
5. `PAGE` JS (the only part not unit-tested):
   - Globals `fwd = false` (the voice goes to the watch, muted here), `voiceIn = null` (the remote stream), `tapped =
     false`, `sendTrack = null` (the track given to `pc`, mic or `ownTrack`). `rem` (from `Buddy.remote()`) is read
     once at the top of `start()`.
   - `function route(on)`: `fwd = on; out.muted = on`; if `on && voiceIn && !tapped`, then `tapped = true;
     tap(ctx, voiceIn, () => fwd)`; log the switch with `L(...)`. The tap is added lazily, so phone-only calls run
     exactly today's audio graph.
   - `start()`: `sendTrack = stream.getAudioTracks()[0]`, which `pc.addTrack(sendTrack, stream)` uses.
   - `ontrack`: `voiceIn = s`; `out.muted = rem || fwd`; if `rem`, the existing tap as today; else if `fwd && !tapped`,
     tap with `() => fwd`.
   - The existing 70 ms `timer`, only when `!rem`: `const w = Buddy.toWatch(), on = (w & 1) !== 0, m = (w & 2) === 0;
     if (on !== fwd) route(on); if (sendTrack && sendTrack.enabled !== m) { sendTrack.enabled = m; L('mic ' + (m ?
     'open' : 'closed')); }`. This is **half-duplex** while the voice is on the watch. The phone's echo canceller only
     knows what the phone plays (nothing: muted), so the phone mic would hear the watch speaker on the wrist and the
     voice would interrupt itself, or its words would reach the chat as the user's. Java decides when the mic reopens,
     from the watch's answer (Part 2). It is 007's choice for the same echo problem (007 decisions: half-duplex), and
     Nicholas may overrule it after trying.
   - Java never calls `evaluateJavascript` for this. The page pulls the state, so a change made before the page has
     loaded can't be lost, and `ontrack` applies `fwd` whenever it comes.

### Texts and docs
- `strings.xml` (English only; no translation has these keys):
  - `setup_audio_device_info_watch`: "…else your watch for read aloud and live voice (Android 12+), else the phone."
  - `setup_audio_device_watch`: "Watch: read aloud, and on Android 12+ live voice calls, play on your Galaxy Watch (it
    needs the watch app from the same version; in a call the phone's mic still listens). If the watch can't play it,
    the phone reads it instead, and a call's voice moves to a headset or the phone speaker."
  - New `live_ended_no_speaker`: "the phone speaker couldn't be used" (used with `live_ended_why`).
- `SettingsTool` `audio_device` `allowed()`: watch also plays a phone live call's voice on Android 12+ (a headset or
  the phone speaker when it can't), and Auto's watch step covers read aloud and live voice.
- README, Galaxy Watch section: a **Live voice on the watch** bullet after "Read aloud on the watch". It says what
  plays where (Android 12+), that the phone mic listens and pauses while the voice speaks on the watch until the watch
  has finished, a headset or the phone speaker on failure (never the earpiece), that changing the device mid-call
  moves the voice, and that no watch update is needed.

### Alternatives considered (one line each)
- Diagnosis' fix only (Watch = speaker in live): that is Part 1; Part 2 is in the brief.
- 007's full Remote for phone-started calls (watch mic too): needs a phone→watch call handshake and a watch update; the
  brief keeps the mic unless that fits simply, and it doesn't.
- A new watch receiver (live `Speaker`, call notification, longer wake lock): bounds latency on the watch, but every
  change needs an adb reinstall of the watch app. The 012 receiver plus the phone-side gate meets the brief.
- Keep the phone playing and add the watch as a copy: two voices; rejected.
- `evaluateJavascript("route(…)")` pushed from Java: it races with the page's load. The page polls `Buddy.toWatch()`
  in its existing timer instead.
- Mute as soon as the stream is opened (before attach): a 10-s open timeout would silence the voice; mute on attach.
- Retry the watch after a drop: the voice would flap between devices. A change of the setting retries.
- Re-decide the watch on headset changes too: not asked. The phone route does follow headsets, through `live()` on
  each poll.
- Auto during live voice → speaker even with no watch: changes Auto (001/016 "as before"); left to Nicholas.
- A headset connected → Android's routing (round 0): in a call Android doesn't use A2DP, and Bluetooth only gets the
  call over SCO when the app selects it, so this was the earpiece path (review round 1 F1). Replaced by choosing the
  headset's call device, telling buds' SCO from the watch's by the matching media output's name.
- Auto's watch step from `target()`: its API 33 routed answer follows Buddy's device in communication mode (round 1 F2).
- Retrying a refused device by calling `reconcile()` again with one `refused` key (round 1): two refused devices
  alternate forever (round 2 F1). A refused set and a loop end it.
- No safe device → `drop()` to Android's routing: that is the earpiece. Muting the page instead would leave a silent
  call with no reason given. The call stops and says why.
- Mic reopening 1 s after the page last heard the voice (round 0): production time, blind to the watch's backlog
  (round 1 F3).
- One open-ended stream with a phone-side copy of the watch's playhead from completed writes (round 1): audio held in
  the Data Layer is invisible to the phone, so the mic could reopen while the watch still speaks (round 2 F2).
- A watch-side "audible" message: exact, but a watch update over adb from a computer. The readings' close codes give
  the same answer at the end of each utterance with the installed app.
- A fixed tail long enough for the worst backlog: no backlog bound exists to size it, and every turn would wait.
- An Android 11 path with `setSpeakerphoneOn`/`startBluetoothSco`: 001 rejected the same thing (deprecated, a second
  code path, no API 30 device to check it on).

## Steps
1. **Routing fix (Part 1).** New `app/src/main/java/app/buddy/assistant/LiveWatch.java` with only the call tracking
   (`begin`/`end` with the facade's API 31 check, `took` always false for now) and `noEarpiece`. `AudioRoute.java`:
   `liveDevice`, `liveAttempt`, `slipped`, the `live(am)` branch in `reconcile()`, `liveRefused`/`stuck`/`appliedAt`,
   the `use()` line, the `route:` log lines, and the javadoc. `strings.xml`: `live_ended_no_speaker`. `CodexLive.java`:
   the begin/end hooks (CodexLive items 1-2). Tests: `AudioRouteTest.liveDevice`, `.liveAttempt`, `.slipped` and
   `LiveWatchTest.noEarpiece`. This step alone fixes the bug, headsets included.
2. **`attached` in the link.** `WatchVoice.java` (`Link.open` with `attached`, the old one as a default),
   `app/src/watchlink/.../WatchVoiceLink.java` (run `attached` after `s.attached`). `ReadStream`, `PcmOut` and their
   tests are not touched.
3. **LiveWatch rules.** `AudioRoute.java`: `livePick`, `liveTarget` (round 1 F2). `LiveWatch.java`: attempt,
   openNext, attached, events, fallback, poll, decide, stop, give-up, Gate (`loud`, `keep`), `pcm`, `toWatch()` as two
   bits, and the Android `Out` (Prefs, `AudioRoute.liveTarget`, `WatchVoice.link()`, main Handler, `elapsedRealtime`,
   `CodexLive.log`). `app/src/test/.../LiveWatchTest.java` and `AudioRouteTest.livePick` (cases in the Test plan).
4. **The page.** `CodexLive.java`: bridge `int toWatch()`, the `pcm` routing, and the `PAGE` JS (CodexLive items 3-5).
5. **Texts and docs.** `app/src/main/res/values/strings.xml` (the Watch texts), `SettingsTool.java` `allowed()`,
   `README.md`.

Ship (orchestrator, after the PR merges): `BuildInfo` `2.3.10-fork.9`, `VERSION_CODE` 220, tag, release,
`update_buddy`. The watch app is not reinstalled.

## Test plan
CI = `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease`
(`build.yml:63`); no Android SDK on the phone, so CI is the build and test run. **Device** = after the release is
installed on the phone (`install_apk` refuses Buddy's own APK, so the check comes after ship; fix forward). The watch
keeps its installed 012+ app. Verifier: `phone_shell` for `dumpsys audio` (communication device, mode owner) and
`~/.buddy/live.log` (`route:`, `watch:`, `mic open/closed` and `page voice on the …` lines). Nicholas, wearing the
watch, says where he hears the voice. Device checks are on his phone (Android 12+).

**Unit tests (JVM, CI):**
- **`AudioRouteTest.liveDevice`:**
  - comm `[EARPIECE, SPEAKER, SCO "Watch4"]` with outputs `[SPEAKER]` → the speaker; the watch's SCO alone isn't a
    headset;
  - comm `[EARPIECE, SPEAKER, SCO "Watch4", SCO "Buds2"]` with outputs `[SPEAKER, A2DP "Buds2"]` → the buds' SCO;
  - the same with `refused = {"7:Buds2"}` → the speaker;
  - outputs `[A2DP "Buds2"]` with no buds SCO in comm (no call profile) → the speaker;
  - comm `[EARPIECE, SPEAKER, WIRED_HEADSET]` → the wired headset;
  - comm `[EARPIECE]` → `NONE`. Never the earpiece's index, in any case.
- **`AudioRouteTest.liveAttempt` (round 2 F1)**, with a fake `apply` that records its calls:
  - buds' SCO and the speaker, `apply` always false → `NONE`, `apply` called exactly twice (the buds, then the
    speaker), and `refused` holds both keys;
  - again with that set → `NONE` and no `apply` call (the failure is kept; nothing is retried until `use()` clears it);
  - only the earpiece and the speaker, `apply` false → `NONE` after one call;
  - the buds refused, the speaker accepted → the speaker's index, `refused` = the buds;
  - the earpiece's index is never passed to `apply`.
- **`AudioRouteTest.slipped`:** false before 2 s, false when actual equals applied, true after 2 s with a different
  actual or `null`, false with nothing applied.
- **`AudioRouteTest.livePick` (round 1 F2):**
  - Auto with outputs `{SPEAKER, A2DP}` → `""`: Speaker → Auto and Watch → Auto with buds send nothing to the watch.
    The routed `{SPEAKER}` answer that hid the buds in `target()` isn't an input; `headsetIn`'s line 29 stays as 016's
    contrast;
  - Auto with outputs `{SPEAKER, SCO}` → watch;
  - `"watch"` picked with A2DP → watch;
  - `"2:Speaker"` picked → itself.
- **`LiveWatchTest`** (fake `Out` with a clock; fake readings take every write at once, so the phone side is never
  behind, and answer only when the test says):
  1. watch found and attached → bit 1 set, `took`, an audible block reaches the fake reading;
  2. find → null → no open, `toWatch()` 0, `noEarpiece("watch")` true;
  3. refused after attach → `toWatch()` 0, `took` stays, and a poll with the same setting doesn't find again;
  4. broken → the same;
  5. a late find answer after `end` → no open; `attached` after a refusal → ignored; an old attempt's `attached` or
     its open reading's events after a new attempt → ignored; a waiting reading's answer counts whatever attempt it
     came from;
  6. `end` → `cancel()` on the open and the waiting reading, and the poll stops;
  7. settings: `"2:Speaker"` → `watch` mid-call → find; `watch` → `"2:Speaker"` while on the watch with no utterance
     going on → `cancel()`, `toWatch()` 0; `watch` → `""` with `watchWins` still true → nothing; `watch` → `""` with
     `watchWins` false (buds, round 1 F2) → `cancel()`;
  8. **mic (round 2 F2):**
     - attached, no voice yet → 1;
     - a loud block → given to the reading, 3;
     - the gate closes (1 s of silence after it) → `close()` on that reading, still 3, and no new reading is opened;
     - its `played` comes 3 s later → 3 for all those 3 s (the review's case: writes taken at once, playback late),
       still 3 for `TAIL`, then 1, and a new reading is opened ahead;
     - loud blocks while it waits → held, given to no reading; on `played` the new reading gets them in order; if the
       gate closed meanwhile, that reading is finished at once;
     - a block of peak 0.015 (between 0.01 and the page's 0.02) starts an utterance → 3; one of 0.005 → not
       forwarded, stays 1;
     - `broken` on the waiting reading → fallback, 2 for `TAIL`, then 0; `refused` on the open reading → 0;
     - no answer 30 s after the end → `cancel()` on it, fallback, 2 for 1 s, then 0; at 29 s still 3;
     - the setting moved away during an utterance → that reading is finished, not cancelled; 2 until its answer plus
       `TAIL`, then 0; moved back to Watch before the answer → no new reading until the answer, then one is opened.
  9. Gate: leading silence dropped, a loud block kept, 1 s of silence after it kept, the next block dropped, loud
     again kept.
  10. `noEarpiece`: true for `watch` during a phone call, false after `end`, false for `""` until `took`, false for a
      device key.
- **`ReadQueueTest`, `ReadStreamTest`, `PcmOutTest`:** unchanged, and they pass. The call reuses 012's readings as
  they are.

| Done-when item | Proof |
|---|---|
| (a) Watch picked: no earpiece; heard on the watch; unreachable → loudspeaker | **Unit:** `liveDevice`, `liveAttempt`, `slipped`, `LiveWatchTest` 1-6 and 8. **Code review:** `live()` has no `drop()`; `NONE` posts one `CodexLive.end`. **Device:** **D1:** the watch's Bluetooth off, Watch picked, phone live call, ask a question → the voice from the loudspeaker. `dumpsys audio` shows communication device = speaker after `connected`, never the earpiece while the voice plays; live.log "watch: none found", "route: 2:…". **D2:** the watch on the wrist, Watch picked → the voice on the watch, phone silent. Ask two things, and speak again right after the voice ends → answered. **D2b (round 2 F2, a gate):** a long answer (ask for a story) with the phone held in the watch hand. Then the same while walking away with the watch until its voice stutters, and back. live.log has each utterance's "watch: played n (+X ms after its end)" line, with X > 1000 in at least one utterance of the walk run, and every page "mic open" line comes after the "played" line before it. In D2, D2b and D3, no user line in live.log is made of the voice's own words, and the voice never cuts itself off. Any such line fails done-when (a). **D3:** during D2, start a talk on the watch (its mic silences the reading) or turn its Bluetooth off → the voice continues on the phone loudspeaker; `dumpsys`: speaker. **D8 (round 1 F1):** buds with a call profile connected, the watch connected, Watch picked, phone call, then start a talk on the watch so it refuses → `dumpsys audio` communication device = the buds' SCO (their name, not the watch's), the voice in the buds, live.log "route: 7:<buds>". Disconnect the buds → within ~2 s the speaker, never the earpiece. |
| (b) Watch mid-call keeps the route; end restores normal | **Unit:** `LiveWatchTest` 7, `liveAttempt`. **Code review:** with no headset, `liveAttempt` finds the speaker already `applied` when Watch replaces a Speaker pick, so nothing is set or cleared (Part 1). **Device:** **D4:** Speaker picked, call. Set Watch with `buddy_settings`, then with the voice bar chip → `dumpsys` never shows the earpiece as communication device; within ~4 s the voice is on the watch. Set Speaker → back on the phone. **D5:** End → within ~2 s `dumpsys audio` shows mode `NORMAL` and no communication device set by Buddy. |
| (c) 012 read aloud, 016 Auto unchanged; CI green | **Unit:** `ReadQueueTest`, `ReadStreamTest`, `PcmOutTest` unchanged, `AudioRouteTest.autoOrder/pickedDevice/headsetIn` pass unchanged. `noEarpiece` is false outside a phone call, so `reconcile()` runs today's lines there. **Code review:** the facade's API 31 check (Android 11: no change); `Link.open` without `attached` is the old call. **CI** green incl. `assembleFdroidRelease` (`LiveWatch` is Google-free; no link → never attempts). **Device:** **D6:** Auto, no headset, watch reachable → the call's voice on the watch. Auto with buds connected → no watch, Android's routing as today. Speaker → Auto mid-call with buds → no "watch: looking" line (round 1 F2). **D7:** no call, Watch picked → a reply read aloud plays on the watch; Settings → Voice → Audio device sample plays. |

## Risks
1. **Echo and half-duplex.** The phone mic is closed from the first audible block given to the watch until the watch
   says that reading is played, plus 300 ms, so the user can't talk over the voice (as in 007 watch calls). This is
   the watch's own answer, so transport delay can't open the mic early (round 2 F2). What is left: the watch
   speaker's output after the track is released (`TAIL`), and the 30-s give-up. There the cancel closes the channel,
   the watch's reads fail and its player stops, and the mic waits 1 s for the close to arrive. A watch more than 30 s
   behind on a link that is alive but slower than real time is the one case that could still reach the mic. D2b
   checks the normal and the lagging run.
2. **Delay.** The watch's reading player never drops, so a stall inside one utterance stays as delay until that
   utterance ends. An utterance that starts while the last one is still playing on the watch waits for its answer.
   Each utterance after a pause of more than 1 s gets a new channel and the watch's 300 ms prebuffer. The reading is
   opened ahead, so usually only the prebuffer shows.
3. **The watch between utterances.** Each reading renews 012's 11-min wake lock, and the reading opened ahead waits
   through the user's turn. A call ends after 5 min with nothing said (`IDLE_MS`), well within it.
4. **Watch notification.** 012's "Reading Buddy's reply" ongoing notification comes and goes with each utterance
   (cosmetic).
5. **Drop detection time** depends on the Data Layer's close callback, or on a failed write plus the 3-s grace. The
   voice is lost meanwhile, and the mic stays closed until the answer or the fallback.
6. **Headset call links.** Telling the buds' SCO from the watch's relies on the SCO device and the A2DP output having
   the same product name (both are the Bluetooth device's name). If a headset reports different names, it gets the
   speaker: never the earpiece, but no headset priority (D8 shows it). A headset whose call link fails after
   `setCommunicationDevice` accepted it plays on Android's default for up to 2 s (`CONFIRM_MS`) before the speaker.
   With Watch picked and a headset with a call profile connected, the headset's mic is the call's mic, as with any
   headset in a call.
7. **No safe device** (round 2 F1): if Android refuses the built-in speaker too, the call ends with "the phone speaker
   couldn't be used", even if the voice is on the watch at that moment. Not seen on any device; it would be a device
   fault. Only unit tests and code review cover it.
8. **Android 11:** a phone live call stays as today, Watch being Auto there (round 1 F4, 001's platform rule). Only
   Nicholas's decision would change that (Open questions).
9. **The Setup sample during a call** with Watch picked opens a reading, which replaces the call's reading on the
   watch (012: newest reading wins, code 0). The voice then stays on the phone until the setting changes again (rare).
10. **Chromium picks the earpiece** when it opens the mic. Buddy re-applies at `connected()`, before any voice (as for
    Speaker today), and the confirm check re-applies after any later re-pick within a poll tick.
11. **Upstream merge friction** in `CodexLive.java`: two hooks, one bridge method, the `pcm` branch, the page globals
    and timer lines, all additive.

## Open questions
None blocking. Defaults recorded in decisions.md that Nicholas may overrule after trying:
- half-duplex on the phone mic while the voice is on the watch;
- no automatic retry of the watch within a call after it refused, dropped or went silent (changing the setting
  retries);
- Auto during live voice with no watch taking the voice stays on Android's routing (the earpiece without a headset),
  as today. The diagnosis left that one to him, and the brief keeps Auto unchanged;
- the live-call part of Watch is for Android 12+ only, following 001's platform decision. His phone is API 31+, so the
  done-when items are checked there. Supporting Android 11 would add a legacy `setSpeakerphoneOn`/SCO path that 001
  rejected.
