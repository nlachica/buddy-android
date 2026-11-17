# Plan: 017-live-voice-watch
<!-- Planner (claude-opus-5-5), 2026-11-17. Sources: brief.md, diagnosis.md, research.md, code read at d1d3226
     (AudioRoute, CodexLive incl. PAGE, CodexSpeech.TAP_JS, WatchVoice, watchlink WatchVoiceLink/ReadStream/PcmOut/
     WatchCall, wear ReadAloud/Speaker, VoiceBar, SettingsTool, SetupActivity), 007/012/016 decisions, 007 verify.
     Revised after plan review round 1 (F1-F4): headset call device chosen and confirmed, comm-mode-safe Auto decision,
     playback-aware mic mute, Android 12+ scope (001). -->

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
                     Buddy.toWatch() polled every 70 ms ──► LiveWatch (main, Google-free): 0 phone,
                       (mic: track enabled unless 2)          1 watch + mic open, 2 watch + mic closed
                     Buddy.pcm(b64) ─► LiveWatch.pcm ─► silence gate ─► ReadQueue.Stream (1-s cap, breaks past it)
                                       LiveWatch.sent ◄── each block written ─┤ WatchVoice.Link.openCall
                                         channel /buddy/read/<id> ◄───────────┘ (watchlink: ReadStream + PcmOut)
                                         watch ReadAloud + Speaker (012, unchanged)
AudioRoute.want(): Watch picked (or Auto once the watch took the voice) in a phone call, Android 12+
                   → a headset's own call device (wired/USB/BLE/hearing aid, or the SCO of a connected media
                     headset), confirmed; else the built-in speaker key. Never the earpiece.
```

### Part 1: routing during a phone live call (`AudioRoute`)
In communication mode with no communication device set, Android plays the call on a wired or USB headset or a hearing
aid by itself, else on the earpiece. It never plays it on A2DP: a Bluetooth headset gets the call only over SCO, which
the app must select (`setCommunicationDevice`, API 31+). This is from Android's routing rules, reasoned and not
verified on the device; D8 checks it. So "a headset keeps priority" in a call means selecting that headset's own
communication device, and checking that it took.

- New pure rule `static int liveDevice(int[] comm, String[] commName, int[] out, String[] outName, String refused)`.
  `comm`/`commName` are the types and product names of `getAvailableCommunicationDevices()`, and `out`/`outName` those
  of `getDevices(GET_DEVICES_OUTPUTS)`. It returns an index in `comm`, or `NONE`. It skips any entry whose key
  (`type + ":" + name`, as `key()`) equals `refused`, and picks the first match of:
  1. **A headset's own call device:** a non-SCO type that is a headset (`headset(type)`: wired headset/headphones,
     USB headset, hearing aid, BLE headset/speaker; A2DP never appears in this list), or a `TYPE_BLUETOOTH_SCO` whose
     name equals the name of a connected media headset output (`headset(out[j])`), which is the call link of connected
     buds. The watch has no media output, so its SCO never matches (016 excluded it for the same reason). Buds without
     a call profile have no communication device and fall through. With several headsets, Android's list order decides
     (rare; apps see no connection times, 016).
  2. **Else the first `TYPE_BUILTIN_SPEAKER`.**
  3. **Else `NONE`.** It never picks the earpiece.
- New pure `static boolean slipped(String applied, String actual, long sinceMs)`: `applied != null && sinceMs >=
  CONFIRM_MS (2000) && !applied.equals(actual)`.
- `want()` gets `@TargetApi(31)` (only `use()`/`reconcile()` reach it, both behind the SDK check) and becomes:
  ```java
  String w = Prefs.audioDevice(c);
  if (!w.isEmpty() && !WatchVoice.KEY.equals(w)) return w;        // a device pick: as today
  if (!LiveWatch.noEarpiece(w)) return "";                        // as today
  AudioManager am = ...; List<AudioDeviceInfo> comm = am.getAvailableCommunicationDevices();
  List<AudioDeviceInfo> outs = Arrays.asList(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
  int i = liveDevice(types(comm), names(comm), types(outs), names(outs), refused);
  return i >= 0 ? key(comm.get(i)) : "";
  ```
  `LiveWatch.noEarpiece(saved)` holds when a **phone** live call is on (LiveWatch tracks it, so watch calls with a Remote
  never match) and either `saved` is Watch, or `saved` is Auto and this call's voice has been on the watch (`took`).
  With Auto before or without the watch, the result is `""`, the same as today (non-goal). With Auto after the watch
  took the voice, a drop then lands on the headset's call device or the loudspeaker, as the brief's "falls back to the
  phone loudspeaker" asks. The rule doesn't depend on whether the voice is on the watch right now, so a drop needs no
  route change.
- `reconcile()`, **only while `LiveWatch.noEarpiece(saved)`**, so 001/016 picks keep today's code:
  - **Confirm:** before the `want.equals(applied)` early return, when `slipped(applied, key(am.getCommunicationDevice()),
    now - appliedAt)` holds: if `applied` isn't the speaker, `refused = applied` (the headset's call link failed). Then
    `applied = null`, and `want` is computed again, so the speaker, or the same speaker again if Chromium re-picked the
    earpiece, is applied in this tick. `appliedAt` is set where `applied` is.
  - **Refusal:** when `setCommunicationDevice` returns false, `reconcile()` runs once more at once. The next `want()`
    skips the refused key, so the speaker follows in the same tick. This is bounded: a refused speaker returns at the
    `want.equals(refused)` check.
  - Each route the rule applies, refuses or finds slipped is logged as `CodexLive.log("route: …")` in live.log, which
    is the evidence the device checks read.
- Nothing else in `AudioRoute` changes. With `want()` non-empty, `use()` (CodexLive.start, `connected()`, the voice bar
  chip) forces a re-apply and clears `refused`, so a refused headset is tried again then. The 300 ms poll's
  `reconcile()` applies the key. Picking Watch mid-call while a Speaker pick is applied gives the same key when no
  headset is there, so `want.equals(applied)` holds and nothing is cleared. With a headset it moves to the headset's
  call device, and never through `drop()`. Coming from Auto or Earpiece, the poll applies it. This is done-when (b)'s
  first half. When the call ends, `LiveWatch` clears its state, `want()` is `""` again, and `drop()`/`afterLive()`/
  `finish()` put things back as today. The WebView sets `MODE_NORMAL` on teardown (done-when (b)'s second half).
  Research risk 3 is covered: the one `clearCommunicationDevice` comes from `drop()`, and `finish()` clears only if
  `dirty`, as today.
- When the page opens the mic, Chromium picks the earpiece itself. Buddy puts the device back at `connected()`
  (`CodexLive.java:746`), as it does today for a Speaker pick (diagnosis: 0.8 s). The voice can't speak before
  `connected()`, because the texts queued for it wait for that call. A later re-pick is caught by the confirm check
  within a tick.
- The class javadoc's Watch sentence gains "except in a phone live call (017): a headset's call device, else the
  speaker".
- **F2: the forwarding decision, comm-mode-safe.** New pure `static String livePick(String saved, boolean savedHere,
  int[] connected, boolean watch)` = `pick(saved, savedHere, headsetIn(null, connected) != NONE, watch)`, with 016's
  media-headset types read from `getDevices(OUTPUTS)` only. New `static String liveTarget(Context c)` feeds it (Prefs,
  `indexOf(choices(c), saved) >= 0`, outputs, `WatchVoice.link() != null`). `target()`/`headsetNow()` ask Android's
  API 33 routed answer, which in communication mode follows the device Buddy set and hides a connected headset
  (`headsetIn` returns `NONE` for a routed `{SPEAKER}`, `AudioRouteTest:29`). They stay as they are for 016's normal
  mode.

### Part 2: the call's voice to the watch (`LiveWatch`, main, Google-free)
One class holds the rules, behind an `Out` interface so a fake can drive it in JVM tests (ReadQueue's pattern, 012). It
is main-thread only, except `toWatch()` and `pcm()` (the JavaBridge thread) and `sent()` (the stream's writer thread).

```java
interface Out {
    String saved();                       // Prefs.audioDevice(app): the setting now (cheap, polled)
    boolean watchWins();                  // WatchVoice.KEY.equals(AudioRoute.liveTarget(app)): F2, no routed answer
    void find(Consumer<String> node);     // WatchVoice.link().find
    ReadQueue.Stream open(String node, ReadQueue.Events e, Runnable attached, Consumer<byte[]> sent); // link().openCall
    void after(long ms, Runnable r);      // main Handler
    long now();                           // SystemClock.elapsedRealtime()
    void log(String line);                // CodexLive.log("watch: " + line) → ~/.buddy/live.log
}
```
State: `call` (a phone live call is on), `gen` (call number, for stale polls), `saved` (the setting this call last
decided on), `turn` (token of the current find/open attempt, null when none), `stream`, volatile `sink` (the stream
once attached, else null), volatile `watch` (the voice plays on the watch now), `took` (it did in this call), `gate`,
and the mic estimate (below). The mic estimate is guarded by the instance lock, because the bridge thread and the
writer thread both touch it.

- **begin** (CodexLive.start of a phone call): `call = true`, `took = false`, `saved = out.saved()`, `decide()`, then
  the poll every `POLL_MS = 500`.
- **poll:** stale `gen` or no call → stop. If `out.saved()` differs from `saved`, store it and `decide()`. Only a
  change of the setting re-decides. This covers the voice bar chip, Setup and `buddy_settings` (SettingsTool doesn't
  call `use()`) with no hook in any of them.
- **decide:** `want = out.watchWins()`. If `want` and `turn == null`, then `attempt()`. If `!want` and `turn != null`,
  then `stop()`, and the voice goes back to the phone with the new pick's route. A change between Watch and Auto that
  keeps the watch first changes nothing. With no link (F-Droid, `build.sh`), `liveTarget()` never returns Watch, so
  nothing is attempted.
- **attempt:** new `turn` token `t`; log "looking". `out.find(node -> …)`: a stale `t` → ignore. `null` → `turn =
  null`, log "none found: phone". Otherwise `stream = out.open(node, events(t), attached(t), b -> sent(t, b))`.
- **attached(t)** (the channel's output stream is open): if `turn == t`, then reset the mic estimate, `sink = stream`,
  `watch = took = true`, log "playing there". The phone mutes on attach, not before. So an unreachable watch never
  silences the phone (the brief's "watch unreachable → phone loudspeaker"). A busy watch refuses in a moment, so little
  voice is lost.
- **events(t)** `refused` / `broken` / `played` (a call never ends its stream, so `played` can't really come): if
  `turn == t`, then `fallback`: `sink = null`, `stream = null`, `turn = null`, `watch = false`, log the reason. `took`
  stays set, so AudioRoute keeps the live rule (a headset's call device, else the speaker) under Auto too. There is no retry in this call until the setting changes.
  ReadStream already turns every way the watch can't play into one of these: refusal code 1 (busy with its own talk,
  call or dictation, or its foreground start refused), close code 0 (`ReadAloud.silence()` when the watch's mic is
  used, a disconnect), a channel not open in 10 s, and a write failure or a queue overflow (the 1-s cap, below) after
  the 3-s grace.
- **stop** (setting moved away, or the call ended): `stream.cancel()` (silent: no event follows), then clear as in
  fallback.
- **end** (CodexLive.end): `stop()`, `call = false`, `took = false`, `gen++`.
- **pcm(b)** (bridge thread): `s = sink; if (s != null && gate.keep(b))`, then under the lock `given += len`, and if
  `Gate.loud(b)`, `loudEnd = given`. Then `s.pcm(b)`.
- **sent(t, b)** (the stream's writer thread, once `b` is written into the channel; an old attempt's `t` is ignored,
  checked against a volatile copy of `turn`): under the lock `sentBytes += len;
  until = max(now, until) + len / 32` (ms of 16 kHz 16-bit mono). If `Gate.loud(b)`, then `audibleUntil = until`. This
  is the watch `Speaker`'s own `audibleUntil` (written minus played), estimated on the phone from what actually left
  it. Writes only complete as fast as the channel takes them, so a backlog on the phone shows up as a later playhead.
- **toWatch()** (bridge thread): `0` when `!watch` (the voice plays on the phone). Else `2` (mic closed) while
  `sentBytes < loudEnd` (an audible block isn't written yet) or `now < audibleUntil + TAIL`. Else `1`. `TAIL = 1000`
  ms covers what the estimate can't see in normal flow: the watch's 300 ms prebuffer (also after each time it ran dry),
  its track latency and the link, with about 0.5 s of margin. One threshold, `Gate.loud` = peak > 0.01 of full scale
  (the watch's `Speaker.AUDIBLE`), decides both what is forwarded and what keeps the mic closed. Audio between 0.01 and
  the page's 0.02 `voice` flag plays on the watch and keeps the mic closed. The page's flag is left to the bar.
- **noEarpiece(saved)**: `call && (WatchVoice.KEY.equals(saved) || (saved.isEmpty() && took))`.
- **Gate** (nested, pure): `static loud(pcm16)`, and `keep(pcm16)`, which returns true while the last loud block is at
  most `HANGOVER = 32_000` bytes (1 s) behind. It starts closed, so the silence before the voice first speaks isn't
  sent. Why: the watch's 012 reading player never drops (blocking writes), and the tap streams continuous silence, so a
  Bluetooth stall would stay as added delay for the rest of the call. With the silence gated, the watch runs dry at
  each pause longer than 1 s and is back in time. Its `Speaker` re-buffers 300 ms when the voice resumes. Short pauses
  inside speech are kept.
- **Failure policy (F3).** A watch that falls behind stops being used. The call's phone queue is non-live with a 1-s
  cap (below). It only grows when the channel stops taking writes, that is when the watch is behind by everything the
  transport buffers. Past 1 s more, the stream breaks, `broken` arrives after ReadStream's 3-s grace, the voice moves
  to the phone loudspeaker, and the mic opens (state 0). Until then the mic stays closed, because the queued audible
  audio isn't written. Audio held inside the Data Layer that never backs up to the phone is invisible to this estimate
  (Risk 1).
- A static facade (one instance, created on the first call with the Android `Out`) gives CodexLive and AudioRoute
  static entry points. The facade's `begin` returns at once below API 31 (F4, 001's platform rule), so on Android 11
  nothing changes. The instance class is what the tests build. Names are left to the Coder.

### Watch link: an open-ended call stream (`WatchVoice.Link`, watchlink)
- `WatchVoice.Link` gets `ReadQueue.Stream openCall(Context c, String node, ReadQueue.Events e, Runnable attached,
  Consumer<byte[]> sent)`: a phone live call's voice. It is open-ended (the caller only ever `cancel()`s it) with a
  `CALL_CAP = 32_000` byte (1 s) queue that breaks past the cap, which is today's non-live `PcmOut` behaviour, so no
  live flag is needed. `attached` runs on the main thread once the output stream is open. `sent` gets each block on the
  writer thread once it is written.
- `WatchVoiceLink`: `open()` and `openCall()` share one private method `(c, node, e, cap, attached, sent)`. Readings
  pass `READ_CAP`, `null` and `null`. The channel path stays `/buddy/read/<seq>`, so the watch app already installed
  (012+, capability `buddy_watch_read`) plays it as a reading, and no watch update is needed. In the success listener,
  after `s.attached(o, …)`, it runs `attached` if one was given. Google task listeners run on the main thread.
- `ReadStream` gets an added constructor with `Consumer<byte[]> sent`, passed to `PcmOut`. The existing one passes
  `null`, so 012 and its tests are unchanged. `PcmOut` gets the same optional `sent`: its writer calls it after each
  `write` + `flush`, never for audio discarded by `cancel`/`stop`. The existing constructor passes `null`, so 007's
  `WatchCall` is unchanged.

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
     tap(ctx, voiceIn, () => fwd)`; if `!on && sendTrack`, then `sendTrack.enabled = true`; log the switch with `L(...)`.
     The tap is added lazily, so phone-only calls run exactly today's audio graph.
   - `start()`: `sendTrack = stream.getAudioTracks()[0]`, which `pc.addTrack(sendTrack, stream)` uses.
   - `ontrack`: `voiceIn = s`; `out.muted = rem || fwd`; if `rem`, the existing tap as today; else if `fwd && !tapped`,
     tap with `() => fwd`.
   - The existing 70 ms `timer`, only when `!rem`: `const w = Buddy.toWatch(); if ((w > 0) !== fwd) route(w > 0); if
     (fwd && sendTrack) sendTrack.enabled = w !== 2;`. This is **half-duplex** while the voice is on the watch. The
     phone's echo canceller only knows what the phone plays (nothing: muted), so the phone mic would hear the watch
     speaker on the wrist and the voice would interrupt itself, or its words would reach the chat as the user's. Java
     decides when the mic reopens, from the watch's estimated playback (Part 2, `toWatch`), not from what the page
     produced. It is 007's choice for the same echo problem (007 decisions: half-duplex), and Nicholas may overrule it
     after trying.
   - Java never calls `evaluateJavascript` for this. The page pulls the state, so a change made before the page has
     loaded can't be lost, and `ontrack` applies `fwd` whenever it comes.

### Texts and docs
- `strings.xml` (English only; no translation has these keys):
  - `setup_audio_device_info_watch`: "…else your watch for read aloud and live voice (Android 12+), else the phone."
  - `setup_audio_device_watch`: "Watch: read aloud, and on Android 12+ live voice calls, play on your Galaxy Watch (it
    needs the watch app from the same version; in a call the phone's mic still listens). If the watch can't play it,
    the phone reads it instead, and a call's voice moves to a headset or the phone speaker."
- `SettingsTool` `audio_device` `allowed()`: watch also plays a phone live call's voice on Android 12+ (a headset or
  the phone speaker when it can't), and Auto's watch step covers read aloud and live voice.
- README, Galaxy Watch section: a **Live voice on the watch** bullet after "Read aloud on the watch". It says what
  plays where (Android 12+), that the phone mic listens and pauses while the voice speaks on the watch, a headset or
  the phone speaker on failure (never the earpiece), that changing the device mid-call moves the voice, and that no
  watch update is needed.

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
- Re-decide the watch on headset changes too: not asked. The phone route does follow headsets, through `want()` on
  each poll.
- Auto during live voice → speaker even with no watch: changes Auto (001/016 "as before"); left to Nicholas.
- A headset connected → Android's routing (round 0): in a call Android doesn't use A2DP, and Bluetooth only gets the
  call over SCO when the app selects it, so this was the earpiece path (review F1). Replaced by choosing the headset's
  call device, telling buds' SCO from the watch's by the matching media output's name.
- Auto's watch step from `target()`: its API 33 routed answer follows Buddy's device in communication mode (review F2).
- Mic reopening 1 s after the page last heard the voice (round 0): production time, blind to the watch's backlog,
  with a 0.02 threshold (review F3). A watch-side "audible" message would be exact, but it needs a watch update over
  adb from a computer. A fixed tail long enough for the worst backlog (≈3 s) would make every turn wait.
- Drop-oldest 2-s live queue (round 0): bounds delay but needs the mic estimate to account for drops. A 1-s
  breaking cap is today's code and ends the watch path when it lags.
- An Android 11 path with `setSpeakerphoneOn`/`startBluetoothSco`: 001 rejected the same thing (deprecated, a second
  code path, no API 30 device to check it on).

## Steps
1. **Routing fix (Part 1).** New `app/src/main/java/app/buddy/assistant/LiveWatch.java` with only the call tracking
   (`begin`/`end` with the facade's API 31 check, `took` always false for now) and `noEarpiece`. `AudioRoute.java`:
   `liveDevice`, `slipped`, the new `want()`, the confirm and refusal lines in `reconcile()` (live rule only),
   `appliedAt`, the `route:` log lines, and the javadoc. `CodexLive.java`: the begin/end hooks (CodexLive items 1-2).
   Tests: `AudioRouteTest.liveDevice`, `AudioRouteTest.slipped` and `LiveWatchTest.noEarpiece`. This step alone fixes
   the bug, headsets included.
2. **Call stream in the link.** `PcmOut.java` (optional `sent`), `ReadStream.java` (added constructor with `sent`),
   `WatchVoice.java` (`Link.openCall`), `app/src/watchlink/.../WatchVoiceLink.java` (shared private open, `CALL_CAP`,
   `attached`, `sent`). Tests in `app/src/testGithub/.../PcmOutTest.java` and `ReadStreamTest.java` (cases in the
   Test plan). The existing calls stay as they are.
3. **LiveWatch rules.** `AudioRoute.java`: `livePick`, `liveTarget` (F2). `LiveWatch.java`: attempt, attached,
   events, poll, decide, stop, Gate (`loud`, `keep`), `pcm`, `sent`, the mic estimate and `toWatch()` as 0/1/2, and
   the Android `Out` (Prefs, `AudioRoute.liveTarget`, `WatchVoice.link()`, main Handler, `elapsedRealtime`,
   `CodexLive.log`). `app/src/test/.../LiveWatchTest.java` and `AudioRouteTest.livePick` (cases in the Test plan).
4. **The page.** `CodexLive.java`: bridge `int toWatch()`, the `pcm` routing, and the `PAGE` JS (CodexLive items 3-5).
5. **Texts and docs.** `app/src/main/res/values/strings.xml`, `SettingsTool.java` `allowed()`, `README.md`.

Ship (orchestrator, after the PR merges): `BuildInfo` `2.3.10-fork.9`, `VERSION_CODE` 220, tag, release,
`update_buddy`. The watch app is not reinstalled.

## Test plan
CI = `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease`
(`build.yml:63`); no Android SDK on the phone, so CI is the build and test run. **Device** = after the release is
installed on the phone (`install_apk` refuses Buddy's own APK, so the check comes after ship; fix forward). The watch
keeps its installed 012+ app. Verifier: `phone_shell` for `dumpsys audio` (communication device, mode owner) and
`~/.buddy/live.log` (`route:`, `watch:` and `page voice on the …` lines). Nicholas, wearing the watch, says where he
hears the voice. Device checks are on his phone (Android 12+).

**Unit tests (JVM, CI):**
- **`AudioRouteTest.liveDevice`:**
  - comm `[EARPIECE, SPEAKER, SCO "Watch4"]` with outputs `[SPEAKER]` → the speaker; the watch's SCO alone isn't a
    headset;
  - comm `[EARPIECE, SPEAKER, SCO "Watch4", SCO "Buds2"]` with outputs `[SPEAKER, A2DP "Buds2"]` → the buds' SCO;
  - the same with `refused = "7:Buds2"` → the speaker;
  - outputs `[A2DP "Buds2"]` with no buds SCO in comm (no call profile) → the speaker;
  - comm `[EARPIECE, SPEAKER, WIRED_HEADSET]` → the wired headset;
  - comm `[EARPIECE]` → `NONE`. Never the earpiece's index, in any case.
- **`AudioRouteTest.slipped`:** false before 2 s, false when actual equals applied, true after 2 s with a different
  actual or `null`, false with nothing applied.
- **`AudioRouteTest.livePick` (F2):**
  - Auto with outputs `{SPEAKER, A2DP}` → `""`: Speaker → Auto and Watch → Auto with buds send nothing to the watch.
    The routed `{SPEAKER}` answer that hid the buds in `target()` isn't an input; `headsetIn`'s line 29 stays as 016's
    contrast;
  - Auto with outputs `{SPEAKER, SCO}` → watch;
  - `"watch"` picked with A2DP → watch;
  - `"2:Speaker"` picked → itself.
- **`LiveWatchTest`** (fake `Out` with a clock):
  1. watch found and attached → `toWatch()` ≠ 0, `took`, audible pcm reaches the fake stream;
  2. find → null → no open, `toWatch()` 0, `noEarpiece("watch")` true;
  3. refused after attach → `toWatch()` 0, `took` stays, and a poll with the same setting doesn't find again;
  4. broken (the cap's overflow, as the fake reports it) → the same;
  5. a late find answer after `end` → no open; `attached` after a refusal → ignored; events and `sent` of an old
     attempt after a new one → ignored;
  6. `end` → `cancel()` on the stream, and the poll stops;
  7. settings: `"2:Speaker"` → `watch` mid-call → find; `watch` → `"2:Speaker"` while on the watch → `cancel()`,
     `toWatch()` 0; `watch` → `""` with `watchWins` still true → nothing; `watch` → `""` with `watchWins` false
     (buds, F2) → `cancel()`;
  8. **mic (F3):**
     - a loud block given and not yet `sent` → 2;
     - `sent` at once → 2 until its playhead plus 1 s, then 1;
     - a block `sent` 1.5 s late → still 2 through that 1.5 s and until its playhead plus 1 s;
     - three blocks `sent` together after a stall → the playhead stacks, 2 until the last one's end plus 1 s;
     - a block of peak 0.015 (between 0.01 and the page's 0.02) → forwarded and 2;
     - a peak 0.005 block → not forwarded and no effect;
     - after a fallback → 0.
  9. Gate: leading silence dropped, a loud block kept, 1 s of silence after it kept, the next block dropped, loud
     again kept.
  10. `noEarpiece`: true for `watch` during a phone call, false after `end`, false for `""` until `took`, false for a
      device key.
- **`PcmOutTest`/`ReadStreamTest`:**
  - `sent` gets each written block in order and nothing after `cancel()`;
  - a call stream with a 1-s cap and a writer stuck on the peer → overflow → `broken` after the grace;
  - readings (`null` `sent`, `READ_CAP`) behave as today.

| Done-when item | Proof |
|---|---|
| (a) Watch picked: no earpiece; heard on the watch; unreachable → loudspeaker | **Unit:** `liveDevice`, `slipped`, `LiveWatchTest` 1-6 and 8. **Device:** **D1:** the watch's Bluetooth off, Watch picked, phone live call, ask a question → the voice from the loudspeaker. `dumpsys audio` shows communication device = speaker after `connected`, never the earpiece while the voice plays; live.log "watch: none found", "route: 2:…". **D2:** the watch on the wrist, Watch picked → the voice on the watch, phone silent. Ask two things, and speak again right after the voice ends → answered. **D2b:** a long answer (ask for a story) with the phone held in the watch hand → the voice isn't cut off by itself, and live.log has no user line made of the voice's own words (half-duplex; Risk 1). **D3:** during D2, start a talk on the watch (its mic silences the reading) or turn its Bluetooth off → the voice continues on the phone loudspeaker; `dumpsys`: speaker. **D8 (F1):** buds with a call profile connected, the watch connected, Watch picked, phone call, then start a talk on the watch so it refuses → `dumpsys audio` communication device = the buds' SCO (their name, not the watch's), the voice in the buds, live.log "route: 7:<buds>". Disconnect the buds → within ~2 s the speaker, never the earpiece. |
| (b) Watch mid-call keeps the route; end restores normal | **Unit:** `LiveWatchTest` 7. **Code review:** `want()` returns the same key for Speaker and Watch mid-call with no headset, so `reconcile` doesn't clear (Part 1). **Device:** **D4:** Speaker picked, call. Set Watch with `buddy_settings`, then with the voice bar chip → `dumpsys` never shows the earpiece as communication device; within ~4 s the voice is on the watch. Set Speaker → back on the phone. **D5:** End → within ~2 s `dumpsys audio` shows mode `NORMAL` and no communication device set by Buddy. |
| (c) 012 read aloud, 016 Auto unchanged; CI green | **Unit:** `ReadQueueTest`, `ReadStreamTest`, `PcmOutTest` (existing cases unchanged), `AudioRouteTest.autoOrder/pickedDevice/headsetIn` pass unchanged. `noEarpiece` is false outside a phone call, so `want()` and `reconcile()` run today's code there. **Code review:** the facade's API 31 check (Android 11: no change). **CI** green incl. `assembleFdroidRelease` (`LiveWatch` is Google-free; no link → never attempts). **Device:** **D6:** Auto, no headset, watch reachable → the call's voice on the watch. Auto with buds connected → no watch, Android's routing as today. Speaker → Auto mid-call with buds → no "watch: looking" line (F2). **D7:** no call, Watch picked → a reply read aloud plays on the watch; Settings → Voice → Audio device sample plays. |

## Risks
1. **Echo, half-duplex, and what the estimate can't see.** The phone mic is closed while the watch is estimated to be
   playing the voice, plus 1 s, so the user can't talk over the voice (as in 007 watch calls). The estimate follows
   every byte that has left the phone, so the phone queue's backlog is covered (F3). It can't see audio held inside
   the Data Layer transport (its pipe to Google Play services, their buffers) that never backs up to the phone. A
   Bluetooth stall shorter than those buffers can delay the watch by more than the 1-s tail, and the end of an
   utterance may then reach the mic: the voice interrupts itself, or its words become a user line. D2b checks this. If
   it shows up, the exact fix is a watch-side "audible" signal (a watch update, follow-up), or raising `TAIL`. Both
   are single places.
2. **Delay after a stall.** The watch's reading player never drops. The gate lets it catch up at the next pause over
   1 s. Inside one long utterance a stall adds delay until that pause. A stall long enough to fill the transport plus
   the 1-s phone queue ends the watch path (Failure policy).
3. **Long calls on the watch.** `ReadAloud`'s wake lock is 11 min (012). On a longer call with the watch screen off,
   playback relies on the audio player and the Data Layer keeping the watch awake; it may stutter. The fix is a watch
   change (a follow-up if seen). Calls end after 5 min with nothing said (`IDLE_MS`).
4. **Watch notification.** During the call the watch shows 012's "Reading Buddy's reply" ongoing notification
   (cosmetic).
5. **Drop detection time** depends on the Data Layer's close callback, or on a failed write or overflow plus the 3-s
   grace. The voice is lost meanwhile, and the mic stays closed until the fallback.
6. **Headset call links.** Telling the buds' SCO from the watch's relies on the SCO device and the A2DP output having
   the same product name (both are the Bluetooth device's name). If a headset reports different names, it gets the
   speaker: never the earpiece, but no headset priority (D8 shows it). A headset whose call link fails after
   `setCommunicationDevice` accepted it plays on Android's default for up to 2 s (`CONFIRM_MS`) before the speaker.
   With Watch picked and a headset with a call profile connected, the headset's mic is the call's mic, as with any
   headset in a call.
7. **Android 11:** a phone live call stays as today, Watch being Auto there (F4, 001's platform rule). Only Nicholas's
   decision would change that (Open questions).
8. **The Setup sample during a call** with Watch picked opens a reading, which replaces the call's stream on the watch
   (012: newest reading wins). The voice then stays on the phone until the setting changes again (rare).
9. **Chromium picks the earpiece** when it opens the mic. Buddy re-applies at `connected()`, before any voice (as for
   Speaker today), and the confirm check re-applies after any later re-pick within a poll tick.
10. **Upstream merge friction** in `CodexLive.java`: two hooks, one bridge method, the `pcm` branch, the page globals
    and timer lines, all additive.

## Open questions
None blocking. Defaults recorded in decisions.md that Nicholas may overrule after trying:
- half-duplex on the phone mic while the voice is on the watch;
- no automatic retry of the watch within a call after it refused, dropped or lagged (changing the setting retries);
- Auto during live voice with no watch taking the voice stays on Android's routing (the earpiece without a headset),
  as today. The diagnosis left that one to him, and the brief keeps Auto unchanged;
- the live-call part of Watch is for Android 12+ only, following 001's platform decision. His phone is API 31+, so the
  done-when items are checked there. Supporting Android 11 would add a legacy `setSpeakerphoneOn`/SCO path that 001
  rejected.
