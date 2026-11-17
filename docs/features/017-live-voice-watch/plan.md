# Plan: 017-live-voice-watch
<!-- Planner (claude-opus-5-5), 2026-11-17. Sources: brief.md, diagnosis.md, research.md, code read at d1d3226
     (AudioRoute, CodexLive incl. PAGE, CodexSpeech.TAP_JS, WatchVoice, watchlink WatchVoiceLink/ReadStream/PcmOut/
     WatchCall, wear ReadAloud/Speaker, VoiceBar, SettingsTool, SetupActivity), 007/012/016 decisions, 007 verify. -->

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
CI is green.

Non-goals (brief and research): calls started from the watch (007: they keep their Remote, routing and half-duplex);
the watch's microphone for phone-started calls (research: 007's Remote is all-or-nothing and watch-initiated, so it
doesn't fit simply); voices; Auto's order itself, including Auto during live voice when no watch takes the voice (it
stays on Android's routing as today, see Open questions); moving the voice when a headset connects or disconnects
mid-call (only a change of the setting moves it); any change to the watch app (the 012 receiver is reused as it is).

## Design

### Overview
```
phone chat ─ CodexLive page (WebView): phone mic ─► call            voice ◄─ call
                                                              │
                     out.muted = fwd, tap(...) while fwd ◄────┘
                     Buddy.toWatch() polled every 70 ms ──► LiveWatch (main, Google-free)
                     Buddy.pcm(b64) ─► LiveWatch.pcm ─► silence gate ─► ReadQueue.Stream (live, 2 s)
                                                                          │ WatchVoice.Link.openCall
                                         channel /buddy/read/<id> ◄───────┘ (watchlink: ReadStream + PcmOut)
                                         watch ReadAloud + Speaker (012, unchanged)
AudioRoute.want(): Watch picked (or Auto once the watch took the voice) in a phone call
                   → headset connected ? "" (Android, as Auto) : built-in speaker key. Never the earpiece.
```

### Part 1: routing during a phone live call (`AudioRoute`)
- New pure rule `static int liveDevice(int[] comm, int[] connected)`. `comm` holds the types of
  `getAvailableCommunicationDevices()` and `connected` the types of `getDevices(GET_DEVICES_OUTPUTS)`. It returns
  `NONE` (Android's own routing) when `headsetIn(null, connected) != NONE`, so a media headset is connected (016's rule
  and types, SCO excluded, so the watch's own call link doesn't count). Otherwise it returns the index of the first
  `TYPE_BUILTIN_SPEAKER` in `comm`, or `NONE` if there is none. It never picks the earpiece. It uses `connected`, not
  016's `headsetNow()`. On API 33+ that asks Android's routing for `USAGE_ASSISTANT`, which in communication mode
  follows the device Buddy set (the speaker) and would then never see a headset again.
- `want()` gets `@TargetApi(31)` (only `use()`/`reconcile()` reach it, both behind the SDK check) and becomes:
  ```java
  String w = Prefs.audioDevice(c);
  if (!w.isEmpty() && !WatchVoice.KEY.equals(w)) return w;        // a device pick: as today
  if (!LiveWatch.noEarpiece(w)) return "";                        // as today
  AudioManager am = ...; List<AudioDeviceInfo> comm = am.getAvailableCommunicationDevices();
  int i = liveDevice(types(comm), types(Arrays.asList(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))));
  return i >= 0 ? key(comm.get(i)) : "";
  ```
  `LiveWatch.noEarpiece(saved)` holds when a **phone** live call is on (LiveWatch tracks it, so watch calls with a Remote
  never match) and either `saved` is Watch or `saved` is Auto and this call's voice has been on the watch (`took`).
  With Auto before or without the watch, the result is `""`, the same as today (non-goal). With Auto after the watch
  took the voice, a drop then lands on the loudspeaker, as the brief's "falls back to the phone loudspeaker" asks.
- Nothing else in `AudioRoute` changes. With `want()` non-empty, `use()` (CodexLive.start, `connected()`, the voice bar
  chip) forces a re-apply, and the 300 ms poll's `reconcile()` applies the speaker key. Picking Watch mid-call while a
  Speaker pick is applied gives the same key, so `want.equals(applied)` holds and nothing is cleared. Coming from Auto
  or Earpiece, the poll applies the speaker. This is done-when (b)'s first half. When the call ends, `LiveWatch` clears
  its state, `want()` is `""` again, and `drop()`/`afterLive()`/`finish()` put things back as today. The WebView sets
  `MODE_NORMAL` on teardown (done-when (b)'s second half). Research risk 3 is covered: the one `clearCommunicationDevice`
  comes from `drop()`, and `finish()` clears only if `dirty`, as today.
- When the page opens the mic, Chromium picks the earpiece itself; Buddy puts the speaker back at `connected()`
  (`CodexLive.java:746`), as it does today for a Speaker pick (diagnosis: 0.8 s). The voice can't speak before
  `connected()`: the texts queued for it wait for that call. So no voice is heard on the earpiece.
- The class javadoc's Watch sentence gains "except in a phone live call (017)".

### Part 2: the call's voice to the watch (`LiveWatch`, main, Google-free)
One class holds the rules, behind an `Out` interface so a fake can drive it in JVM tests (ReadQueue's pattern, 012). It
is main-thread only, except `toWatch()` and `pcm()`, which the JavaBridge thread calls.

```java
interface Out {
    String saved();                       // Prefs.audioDevice(app): the setting now (cheap, polled)
    boolean watchWins();                  // WatchVoice.KEY.equals(AudioRoute.target(app)): 016's order says watch
    void find(Consumer<String> node);     // WatchVoice.link().find
    ReadQueue.Stream open(String node, ReadQueue.Events e, Runnable attached); // link().openCall
    void after(long ms, Runnable r);      // main Handler
    void log(String line);                // CodexLive.log("watch: " + line) → ~/.buddy/live.log
}
```
State: `call` (a phone live call is on), `gen` (call number, for stale polls), `saved` (the setting this call last
decided on), `turn` (token of the current find/open attempt, null when none), `stream`, volatile `sink` (the stream
once attached, else null), volatile `watch` (the voice plays on the watch now), `took` (it did in this call), and
`gate`.

- **begin** (CodexLive.start of a phone call): `call = true`, `took = false`, `saved = out.saved()`, `decide()`, then
  the poll every `POLL_MS = 500`.
- **poll:** stale `gen` or no call → stop. If `out.saved()` differs from `saved`, store it and `decide()`. Only a
  change of the setting re-decides. This covers the voice bar chip, Setup and `buddy_settings` (SettingsTool doesn't
  call `use()`) with no hook in any of them.
- **decide:** `want = out.watchWins()`. If `want` and `turn == null`, then `attempt()`. If `!want` and `turn != null`,
  then `stop()`, and the voice goes back to the phone with the new pick's route. A change between Watch and Auto that
  keeps the watch first changes nothing. With no link (F-Droid, `build.sh`), `target()` never returns Watch, so nothing
  is attempted.
- **attempt:** new `turn` token `t`; log "looking". `out.find(node -> …)`: a stale `t` → ignore. `null` → `turn =
  null`, log "none found: phone". Otherwise `stream = out.open(node, events(t), attached(t))`.
- **attached(t)** (the channel's output stream is open): if `turn == t`, then `sink = stream`, `watch = took = true`,
  log "playing there". The phone mutes on attach, not before. So an unreachable watch never silences the phone (the
  brief's "watch unreachable → phone loudspeaker"). A busy watch refuses in a moment, so little voice is lost.
- **events(t)** `refused` / `broken` / `played` (a call never ends its stream, so `played` can't really come): if
  `turn == t`, then `fallback`: `sink = null`, `stream = null`, `turn = null`, `watch = false`, log the reason. `took`
  stays set, so AudioRoute keeps the speaker under Auto too. There is no retry in this call until the setting changes.
  ReadStream already turns every way the watch can't play into one of these: refusal code 1 (busy with its own talk,
  call or dictation, or its foreground start refused), close code 0 (`ReadAloud.silence()` when the watch's mic is
  used, a disconnect), a channel not open in 10 s, and a write failure after the 3-s grace.
- **stop** (setting moved away, or the call ended): `stream.cancel()` (silent: no event follows), then clear as in
  fallback.
- **end** (CodexLive.end): `stop()`, `call = false`, `took = false`, `gen++`.
- **toWatch()**: returns `watch`.
- **pcm(b)** (bridge thread): `s = sink; if (s != null && gate.keep(b)) s.pcm(b)`.
- **noEarpiece(saved)**: `call && (WatchVoice.KEY.equals(saved) || (saved.isEmpty() && took))`.
- **Gate** (nested, pure): `keep(pcm16)` returns true while the last audible block (peak > 0.01 of full scale, the
  threshold of the watch's `Speaker.AUDIBLE` and the page) is at most `HANGOVER = 32_000` bytes (1 s) behind. It
  starts closed, so the silence before the voice first speaks isn't sent. Why: the watch's 012 reading player never
  drops (blocking writes), and the tap streams continuous silence, so a Bluetooth stall would stay as added delay for
  the rest of the call. With the silence gated, the watch runs dry at each pause longer than 1 s and is back in time.
  Its `Speaker` re-buffers 300 ms when the voice resumes. Short pauses inside speech are kept.
- A static facade (one instance, created on the first call with the Android `Out`) gives CodexLive and AudioRoute
  static entry points. The instance class is what the tests build. Names are left to the Coder.

### Watch link: an open-ended, live stream (`WatchVoice.Link`, watchlink)
- `WatchVoice.Link` gets `ReadQueue.Stream openCall(Context c, String node, ReadQueue.Events e, Runnable attached)`:
  a phone live call's voice. It is open-ended (the caller only ever `cancel()`s it) and stays live: 2 s
  (`64_000` bytes), dropping the oldest, which is 007's call bound. `attached` runs on the main thread once the output
  stream is open.
- `WatchVoiceLink`: `open()` and `openCall()` share one private method `(c, node, e, cap, live, attached)`. The channel
  path stays `/buddy/read/<seq>`, so the watch app already installed (012+, capability `buddy_watch_read`) plays it as
  a reading, and no watch update is needed. In the success listener, after `s.attached(o, …)`, it runs
  `attached` if one was given. Google task listeners run on the main thread.
- `ReadStream` gets a `boolean live` constructor parameter passed to `new PcmOut(cap, live, abandonMs, …)` (today
  hardcoded `false`). Readings pass `false`, so 012 is unchanged. In a live stream an overflow drops the oldest instead
  of breaking, and every other path (codes, grace, cancel, local close ignored) is the same.

### CodexLive (upstream file: additive, a null-safe no-op for watch calls and F-Droid)
1. `start()`: in the existing `if (r == null)` block after `openPage()` (next to the screen-off receiver), call
   `LiveWatch` begin. `AudioRoute.use(app)` earlier in `start()` only posts its reconcile, so it already sees the call.
2. `end()`: call `LiveWatch` end right after `if (s == null) return;`.
3. Bridge `pcm(b64)`: if `web != mine`, return; decode; give it to `r.pcm(b)` when `remote != null`, else to
   `LiveWatch.pcm(b)`.
4. New bridge `@JavascriptInterface boolean toWatch()`: `web == mine && remote == null && LiveWatch.toWatch()`.
5. `PAGE` JS (the only part not unit-tested):
   - Globals `fwd = false` (the voice goes to the watch, muted here), `voiceIn = null` (the remote stream), `tapped =
     false`, `sendTrack = null` (the track given to `pc`, mic or `ownTrack`), `lastVoice = 0`. `rem` (from
     `Buddy.remote()`) is read once at the top of `start()`.
   - `function route(on)`: `fwd = on; out.muted = on`; if `on && voiceIn && !tapped`, then `tapped = true;
     tap(ctx, voiceIn, () => fwd)`; if `!on && sendTrack`, then `sendTrack.enabled = true`; log the switch with `L(...)`.
     The tap is added lazily, so phone-only calls run exactly today's audio graph.
   - `start()`: `sendTrack = stream.getAudioTracks()[0]`, which `pc.addTrack(sendTrack, stream)` uses.
   - `ontrack`: `voiceIn = s`; `out.muted = rem || fwd`; if `rem`, the existing tap as today; else if `fwd && !tapped`,
     tap with `() => fwd`.
   - The existing 70 ms `timer`, only when `!rem`: `const w = Buddy.toWatch(); if (w !== fwd) route(w);` If `voice`,
     then `lastVoice = Date.now()`. If `fwd && sendTrack`, then `sendTrack.enabled = Date.now() - lastVoice >
     1000`. This is **half-duplex** while the voice is on the watch. The phone's echo canceller only knows what the
     phone plays (nothing: muted), so the phone mic would hear the watch speaker on the wrist and the voice would
     interrupt itself, or its words would reach the chat as the user's. The tail of 1 s past the last audible block
     received covers the watch's playback lag (≈0.3 s prebuffer plus the link). It is 007's choice for the same echo
     problem (007 decisions: half-duplex), and Nicholas may overrule it after trying.
   - Java never calls `evaluateJavascript` for this. The page pulls the state, so a change made before the page has
     loaded can't be lost, and `ontrack` applies `fwd` whenever it comes.

### Texts and docs
- `strings.xml` (English only; no translation has these keys):
  - `setup_audio_device_info_watch`: "…else your watch for read aloud and live voice, else the phone."
  - `setup_audio_device_watch`: "Watch: read aloud and live voice calls play on your Galaxy Watch (it needs the watch
    app from the same version; in a call the phone's mic still listens). If the watch can't play it, the phone reads
    it instead, and a call's voice moves to the phone speaker."
- `SettingsTool` `audio_device` `allowed()`: watch also plays a phone live call's voice (phone speaker when it can't),
  and Auto's watch step covers read aloud and live voice.
- README, Galaxy Watch section: a **Live voice on the watch** bullet after "Read aloud on the watch". It says what
  plays where, that the phone mic listens and pauses while the voice speaks, the phone speaker on failure (never the
  earpiece), that changing the device mid-call moves the voice, and that no watch update is needed.

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
- Re-decide on headset changes too: needs `target()` polling in communication mode, where 016's routed answer is
  skewed (see Part 1). Not asked.
- Auto during live voice → speaker even with no watch: changes Auto (001/016 "as before"); left to Nicholas.
- Strict "never earpiece" with a headset (pick the headset's communication device ourselves): can't tell buds' SCO
  from the watch's SCO. 016 chose Android's routing for headsets, and the brief says "per 016".

## Steps
1. **Routing fix (Part 1).** New `app/src/main/java/app/buddy/assistant/LiveWatch.java` with only the call tracking
   (`begin`/`end`, `took` always false for now) and `noEarpiece`. `AudioRoute.java`: `liveDevice`, the new `want()`,
   and the javadoc. `CodexLive.java`: the begin/end hooks (CodexLive items 1-2). Tests: `AudioRouteTest.liveDevice`
   and `LiveWatchTest.noEarpiece`. This step alone fixes the bug.
2. **Live stream in the link.** `WatchVoice.java` (`Link.openCall`), `app/src/watchlink/.../WatchVoiceLink.java`
   (shared private open, `attached`), `ReadStream.java` (`live`). Update the two `new ReadStream(…)` calls in
   `app/src/testGithub/.../ReadStreamTest.java` (`false`) and add a live test.
3. **LiveWatch rules.** `LiveWatch.java`: attempt, attached, events, poll, decide, stop, Gate, `toWatch`, `pcm`, and
   the Android `Out` (Prefs, `AudioRoute.target`, `WatchVoice.link()`, main Handler, `CodexLive.log`).
   `app/src/test/.../LiveWatchTest.java` (cases in the Test plan).
4. **The page.** `CodexLive.java`: bridge `toWatch()`, the `pcm` routing, and the `PAGE` JS (CodexLive items 3-5).
5. **Texts and docs.** `app/src/main/res/values/strings.xml`, `SettingsTool.java` `allowed()`, `README.md`.

Ship (orchestrator, after the PR merges): `BuildInfo` `2.3.10-fork.9`, `VERSION_CODE` 220, tag, release,
`update_buddy`. The watch app is not reinstalled.

## Test plan
CI = `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease`
(`build.yml:63`); no Android SDK on the phone, so CI is the build and test run. **Device** = after the release is
installed on the phone (`install_apk` refuses Buddy's own APK, so the check comes after ship; fix forward). The watch
keeps its installed 012+ app. Verifier: `phone_shell` for `dumpsys audio` (communication device, mode owner) and
`~/.buddy/live.log` (`watch:` and `page voice on the …` lines). Nicholas, wearing the watch, says where he hears the
voice.

| Done-when item | Proof |
|---|---|
| (a) Watch picked: no earpiece; heard on the watch; unreachable → loudspeaker | **Unit:** `liveDevice`: speaker index with comm `[EARPIECE, SPEAKER, SCO]` and no headset; `NONE` with A2DP or wired in `connected`; SCO alone (the watch's link) still speaker; no speaker → `NONE`, never the earpiece. `noEarpiece`: true for `watch` during a phone call, false after `end`, false for `""` until `took`, false for a device key. `LiveWatchTest`: (1) watch found + attached → `toWatch()`, `took`, audible pcm reaches the fake stream; (2) find → null → no open, `toWatch()` false, `noEarpiece("watch")` true; (3) refused after attach → `toWatch()` false, `took` stays, a poll with the same setting doesn't find again; (4) broken → same; (5) late find answer after `end` → no open; `attached` after a refusal → ignored; events of an old attempt after a new one → ignored; (6) `end` → `cancel()` on the stream, poll stops (fake clock). **Device:** D1, the watch's Bluetooth off, Watch picked, phone live call, ask a question → voice from the loudspeaker; `dumpsys audio` communication device = speaker after `connected`, never earpiece while the voice plays; live.log "watch: none found". D2, watch on the wrist, Watch picked → voice on the watch, phone silent; ask two things, speak again right after the voice ends → answered; live.log shows no user line made of the voice's own words (half-duplex). D3, during D2 start a talk on the watch (its mic silences the reading) or turn its Bluetooth off → voice continues on the phone loudspeaker; `dumpsys`: speaker. |
| (b) Watch mid-call keeps the route; end restores normal | **Unit:** `LiveWatchTest` (7): setting `"2:Speaker"` → `watch` mid-call → find; `watch` → `"2:Speaker"` while on the watch → `cancel()`, `toWatch()` false; `watch` → `""` with `watchWins` still true → nothing. **Code review:** `want()` returns the same key for Speaker and Watch mid-call, so `reconcile` doesn't clear (Part 1). **Device:** D4, Speaker picked, call; set Watch with `buddy_settings`, then with the voice bar chip → `dumpsys` never shows the earpiece as communication device; within ~4 s the voice is on the watch; set Speaker → back on the phone. D5, End → within ~2 s `dumpsys audio` shows mode `NORMAL` and no communication device set by Buddy. |
| (c) 012 read aloud, 016 Auto unchanged; CI green | **Unit:** `ReadQueueTest`, `ReadStreamTest` (readings `live=false`), `AudioRouteTest.autoOrder/pickedDevice` pass unchanged; `noEarpiece` false outside a phone call, so `want()` is today's code there; Gate tests: leading silence dropped, a loud block kept, 1 s of silence after it kept, the next block dropped, loud again kept. **CI** green incl. `assembleFdroidRelease` (`LiveWatch` is Google-free; no link → never attempts). **Device:** D6, Auto, no headset, watch reachable → the call's voice on the watch; Auto with buds connected → no watch, Android's routing as today. D7, no call: Watch picked, a reply read aloud plays on the watch; Settings → Voice → Audio device sample plays. |

## Risks
1. **Echo and half-duplex.** The phone mic is muted while the voice speaks plus 1 s. The user can't talk over the
   voice (as in 007 watch calls). If the link lags more than the tail, the end of the voice may leak into the mic. D2
   checks both. The tail is one constant in the page.
2. **Delay after a stall.** The watch's reading player never drops. The phone keeps at most 2 s (dropping the oldest),
   and the gate lets the watch catch up at the next pause over 1 s. Inside one long utterance a stall can add up to
   ~2-3 s until that pause.
3. **Long calls on the watch.** `ReadAloud`'s wake lock is 11 min (012). On a longer call with the watch screen off,
   playback relies on the audio player and the Data Layer keeping the watch awake; it may stutter. The fix is a watch
   change (a follow-up if seen). Calls end after 5 min with nothing said (`IDLE_MS`).
4. **Watch notification.** During the call the watch shows 012's "Reading Buddy's reply" ongoing notification
   (cosmetic).
5. **Drop detection time** depends on the Data Layer's close callback or a failed write plus the 3-s grace. The voice
   is lost meanwhile.
6. **Headset + Watch picked, watch not carrying the voice:** Android's routing, as Auto does today. In communication
   mode a Bluetooth headset may not get the call audio unless Chromium or Android starts SCO, which is pre-existing
   Auto behaviour.
7. **API 30:** no `setCommunicationDevice`, so the fallback is Android's routing (`use()` returns early, as today).
   The watch path still works there.
8. **The Setup sample during a call** with Watch picked opens a reading, which replaces the call's stream on the watch
   (012: newest reading wins). The voice then stays on the phone until the setting changes again (rare).
9. **Chromium picks the earpiece** when it opens the mic; Buddy re-applies at `connected()`, before any voice (as for
   Speaker today). If a device run shows Chromium re-picking later, it is a follow-up in `reconcile`.
10. **Upstream merge friction** in `CodexLive.java`: two hooks, one bridge method, the `pcm` branch, the page globals
    and timer lines, all additive.

## Open questions
None blocking. Defaults recorded in decisions.md that Nicholas may overrule after trying: half-duplex on the phone mic
while the voice is on the watch; no automatic retry of the watch within a call after it refused or dropped (changing
the setting retries); Auto during live voice with no watch taking the voice stays on Android's routing (the earpiece
without a headset), as today. The diagnosis left that one to him, and the brief keeps Auto unchanged.
