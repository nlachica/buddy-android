# Plan: 012-watch-audio-device
<!-- Planner (claude-opus-5-5), 2026-11-16. Sources: brief.md, research.md, code read at 256c952 and b12c5aa.
     Revised for plan-review round 1 (F1-F7). -->

## Goal
Add **Watch** to Buddy's audio-device choice (Auto / Speaker / Phone…, feature 001's `AudioRoute.choices`). With Watch
chosen, whatever Buddy reads aloud on the phone (chat replies, read-aloud cards, the Setup sample) plays on the Galaxy
Watch speaker in the phone's chosen voice (phone TTS or ChatGPT's), once, and the phone stays silent. If no watch can
play it, the phone reads it with its normal output and says so once. Done when: (1) the list shows Watch in the github
build, always, with a clear unreachable state; (2) Watch + read aloud: a phone chat reply is heard from the watch once,
phone silent, with both voices; (3) watch unreachable: the reply is read on the phone instead, once; (4) 001/003/007/009
unchanged, CI green.

Non-goals: calls, mic input or other phone sounds on the watch (with Watch chosen, dictation and live voice behave as
Auto); a Watch option in the F-Droid build or in `build.sh` APKs (no watch link there); a stop button on the watch
notification; replaying any of a reading on the phone once the watch has started on it (a link drop or hush there ends
it); readings longer than about 10 minutes of speech.

## Design

### The fact that shapes everything
The watch can't be an Android audio device (001 verify: Samsung relays watch calls itself, `AudioRoute.choices()`
never lists it). So Watch is a **virtual entry**: key `"watch"` (device keys are `type:name` and always hold a `:`, so
no clash). For Android routing it means Auto; for read aloud it means "stream the voice to the watch over the Wear
Data Layer", the 007 plumbing (`PcmOut` on the phone, `Speaker` on the watch, PCM 16-bit mono 16 kHz LE).

### Seam between main and the watch link
`src/main` can't reference `src/watchlink` (F-Droid and `build.sh` don't compile it). The main class `WatchVoice` holds a
small interface `WatchVoice.Link` and loads its github implementation once by reflection:
`Class.forName("app.buddy.assistant.WatchVoiceLink")`, `null` when absent (F-Droid, `build.sh`). Minify is off
(`app/build.gradle.kts:68`), so no keep rule is needed. `Link` has two calls:
- `find(Context, Consumer<String> node)`: the node id of a reachable watch whose Buddy app has 012
  (`CapabilityClient.getCapability("buddy_watch_read", FILTER_REACHABLE)`; the watch declares that capability), or
  null. Answers on the main thread within 3 s, null if the lookup takes longer.
- `ReadQueue.Stream open(Context, String node, ReadQueue.Events e)`: one reading's audio stream to the watch.

`ReadQueue.Stream` extends `CodexSpeech.Sink` (`pcm`; `close` = the producer has finished) and adds `cancel()`.
`ReadQueue.Events` has `played()` (the watch confirmed it played the reading to the end), `refused()` (nothing was
played: the phone must read it) and `broken()` (stopped partway, or the outcome is unknown: nobody reads the rest). At
most one event fires per stream, on the main thread. It is never run from inside a `Stream` call and never after
`cancel()`.

Alternatives: a manifest `ContentProvider` in watchlink that registers the hook at start (more parts than one
`Class.forName`); `Flavor.java` returning the hook (breaks `build.sh`, which compiles `src/github` without
watchlink).

### Main side: `ReadQueue` (pure Java, the rules) and `WatchVoice` (the Android shell)
**One reading in flight.** Texts wait in a FIFO, and the next one starts only after the current one has settled. This
is the one rule that keeps replies in order, keeps the notice before its reply, and means only one TTS request or watch
channel exists at a time (round 1 F1). The rules live in `ReadQueue` so they can be unit-tested. It has no Android types
and is used on the main thread only. Its effects go through one interface, `ReadQueue.Out`, with two implementations:
`WatchVoice` in production and a fake in `ReadQueueTest`.
- `Out`: `find(Consumer<String>)`; `Stream open(node, Events)`; `chatgpt(text, Stream, Runnable failed)` →
  `CodexSpeech.speak(app, text, t -> failed.run(), stream)`; `tts(text, Stream orNull, Runnable done)` → `Voice.routed`
  (null: on the phone, after `SpeechPause.begin`); `stopProducers(Stream orNull)` → `CodexSpeech.cancel(s)` +
  `Voice.dropRouted()`; `after(ms, Runnable)` (main Handler); `notice()` (the string).
- `speak(text, chatgpt)`: append to the FIFO, then `pump`. `pump`: when nothing is current and the FIFO isn't empty, the
  head becomes the current **reading**, `after(READ_MAX_MS = 11 min, expire)` is set, and `find` is called.
- Node found → **watch**: `s = open(node, events)`. Then either ChatGPT voice → `chatgpt(text, s, failed)`, where
  `failed` (the voice failed before starting) cancels `s` and runs **watch** again with phone TTS, at most once. Or
  phone TTS → `tts(text, s, done)`, where `done` → `s.close()`.
- No node → **phone**.
- Events: `played` → the notice becomes due again on the next fallback, and the reading ends. `refused` →
  `stopProducers(s)`, `s.cancel()`, **phone**. `broken` → `stopProducers(s)`, and the reading ends.
- **phone**: if the notice is due, `tts(notice(), null, …)` runs first and marks it said, and the reply's
  `tts(text, null, …)` starts only from the notice's `done`. Otherwise the reply is spoken directly. The reply's `done`
  ends the reading.
- `expire` (the cap, which does not depend on the producer ever finishing): `s.cancel()`, `stopProducers(s)`, and the
  reading ends.
- Reading ends → current = null, then `pump`.
- `stop()`: clears the FIFO, cancels the current stream, calls `stopProducers` and drops the current reading.
- `active()`: a reading is current or the FIFO isn't empty.
- **Tokens (round 1 F6):** every callback above captures its reading and stream and does nothing unless both are still
  current. That covers a late `find` answer, events from a cancelled or replaced stream, a producer `done` after stop,
  the ChatGPT fallback after a refusal, and a cap firing after the reading ended.

`WatchVoice` (main, static): `link()` (the loader); `chosen(c)` = `"watch".equals(Prefs.audioDevice(c)) && link() !=
null`; `speak(c, clean)` → `queue.speak(clean, Voice.chatgptVoice(c))`; `active()`; `stop()`; and `Out` built on
Link/CodexSpeech/Voice/Handler.

Hooks in `Voice`: `speak` keeps the cleaning, `SpeechPause.begin` and `AudioRoute.use`. Then, when Watch is chosen, it
calls `WatchVoice.speak(c, clean)`; otherwise it runs the existing branch, moved unchanged into `here(c, clean)`
(ChatGPT voice with phone fallback, or phone TTS). Hooking `Voice.speak` covers every caller (ChatHub replies, Bubble
cards, Setup samples). ChatHub's existing `!ses.watch` and `!CodexLive.on()` gates stay, so a watch-run reply is never
read twice. `isSpeaking()` adds `WatchVoice.active()`, so SpeechPause keeps media paused while the watch reads, the
bubble offers "stop voice", and "answer cards by voice" waits. `stopSpeaking()` adds `WatchVoice.stop()` and
`dropRouted()` to what it does now, so a hush on the phone or the start of dictation also stops the watch.

"Say so once": the notice is due at first, said before the first phone fallback, and due again only after a watch
reading is `played`.

### Phone TTS for the new route (`Voice.routed`)
`routed(c, text, Sink sinkOrNull, Runnable done)` is used only by `WatchVoice`. It makes the same voice choice as `say`
(language detection, best voice). With a sink it calls `tts.synthesizeToFile(text, params, cacheDir/read.wav, id)`
instead of `tts.speak`, so nothing plays on the phone. Without a sink it calls `AudioRoute.use` and then
`tts.speak(text, QUEUE_ADD, params, id)`.
- One `UtteranceProgressListener`, set at engine init, handles only ids in a `taps` map (`ConcurrentHashMap`; the
  callbacks come on binder threads). `onBeginSynthesis(rate, format, channels)` creates a `Pcm16k` converter, and
  `onAudioAvailable` feeds it and writes the result to the sink. Phone utterances (`buddy-…` ids) have no entry and are
  untouched.
- **`done` runs exactly once, on main (round 1 F5):** on `onDone`/`onError`/`onStop` of its id; when
  `synthesizeToFile`/`speak` returns ERROR; or at once when the engine failed to start (new `ttsFailed`, set by the init
  callback). Each exit removes the tap and deletes `read.wav`. `routed` never closes the sink: `ReadQueue` closes the
  stream on `done`, and a stream that got no audio is a refusal (below), which sends the text to the phone.
- **Cold engine:** a routed request waits in its own slot, `routedPending`. `ReadQueue` keeps one request in flight, so
  one slot is enough and nothing gets overwritten. The existing `pending` slot and its rule are unchanged. On init
  success `routedPending` is submitted; on failure its `done` runs.
- **Cancellation (round 1 F6):** `dropRouted()` bumps `routedGen`, clears `routedPending` and `taps`, deletes
  `read.wav`, and calls `tts.stop()` if the engine is ready (with Watch chosen, every phone TTS request is this route's).
  A request captures `routedGen` and checks it after engine init and after language detection, before submitting. A
  stale request submits nothing.
- `onAudioAvailable` is documented for `synthesizeToFile` since API 24 ("a copy of what will be ... written to the file
  system", developer.android.com UtteranceProgressListener); minSdk is 30.

`Pcm16k` (main, pure Java, unit-tested) converts engine PCM (8-bit unsigned, 16-bit LE or float; any rate; 1 or 2
channels) to 16-bit mono 16 kHz LE. It uses linear interpolation with the phase carried across chunks (the same method
as `TAP_JS` in CodexSpeech), and an odd trailing byte is carried to the next chunk.

Alternative: the watch's own TTS reads the text (`/buddy/say`-like). Rejected: the brief asks for "the phone's chosen
voice", and it would need TTS in the new watch service besides the PCM player.

### AudioRoute and the three UIs
- `AudioRoute.choices`: Watch (`audio_watch`, "Watch") is appended **last** when `WatchVoice.link() != null`, so 001's
  devices keep their order and index. On Android < 12 the list becomes Auto + Watch, so Setup's row and the voice bar
  chip now show there too (the `> 1` tests are right as they are; only the "Android 12+" comments change).
- `AudioRoute.use`/`reconcile` read the wanted device through one helper that maps `"watch"` to `""`, so Watch is
  exactly Auto: no communication device, no communication mode. (Without it, `use()` would null `applied` for the
  non-empty key and a Speaker chosen just before would not be cleared until the session ends.)
- Setup → Voice: when Watch is saved, the note under the row says what Watch does. A `find` then replaces it with the
  unreachable sentence if no watch can be found. That is the "clear unreachable state" (the voice bar chip has no room;
  it shows "Watch"). Tapping Watch plays the sample on the watch, as for any device (Setup's `stopSpeaking` + `speak`
  is the stop-then-new-reading case the tokens cover).
- `buddy_settings audio_device`: "watch" appears in the allowed values by itself (they come from `choices`); its
  `allowed()` text adds what it means, like `chatgpt_voice` does. The approval card per change (010) is unchanged.

### Watch link, phone side: `WatchVoiceLink` + `ReadStream` (src/watchlink)
`WatchVoiceLink` (public, with a no-arg constructor that does no work, so `WatchVoiceTest` can load it) does the
Google side:
- `find`: the capability lookup with its 3-s guard.
- `open`: path `/buddy/read/<seq>` (Talk only takes `/buddy/voice/`, and 007's `WatchLink.watchChannels` finds no such
  key, so neither reacts). It builds a `ReadStream` over `PcmOut(READ_CAP = 10 min of PCM ≈ 19.2 MB, live=false,
  READ_ABANDON_MS = 11 min, onBroken)`. Phone TTS is faster than real time and the watch pulls at playback speed, so the
  whole reading may queue here; 007's 10-s cap and 60-s abandon would cut long readings. The channel opens as in
  `speakOnWatch` (→ `attached(out, closer)`); if it isn't open within 10 s → `openTimedOut()`. PcmOut's `onBroken` posts
  `graceExpired()` 3 s later (`CLOSE_GRACE_MS`).
- Its own `ChannelClient.ChannelCallback`, registered once, passes the close of a `/buddy/read/` channel to its stream as
  `closed(appSpecificErrorCode)` on main. `WatchLink` is not touched.
- A partial wake lock (`buddy:watch-read`, 11-min timeout) is held while a stream is unsettled. With the screen off,
  nothing else keeps the phone's CPU up while the watch pulls a long reading.

`ReadStream` (no Google types, so it is unit-tested in `testGithub` like `PcmOutTest`) implements `ReadQueue.Stream` and
owns the outcome (round 1 F3/F4):
- `pcm` counts bytes and calls `out.write`.
- `close()`: if nothing was written → `refused` + `out.cancel()`; otherwise → `out.finish()`. Finishing says nothing
  about playback.
- `closed(code)`: 2 (PLAYED) → `played`; 1 (NOT_PLAYED) → `refused`; anything else → `broken`. Anything else includes
  a disconnect (code 0), a hush, a break after sound and our own local close. Then `out.cancel()` frees the writer.
- `openTimedOut()` while not yet attached → `refused` + `out.cancel()`.
- PcmOut's `onBroken` (a write failed, or overflow) settles nothing by itself. `graceExpired()` → `broken` only if no
  code came, so a NOT_PLAYED that arrives just after a failed write still means `refused`.
- `cancel()` settles silently and calls `out.cancel()`.
- One outcome per stream. It is handed to an `Executor` (the main Handler in the link, inline in tests) and dropped at
  delivery if `cancel()` came first.

`PcmOut` itself is unchanged.

### Watch side: `ReadAloud` (wear) + two small hooks
- `ReadAloud extends WearableListenerService`, `exported="true"`, with a `CHANNEL_EVENT` filter on `wear://*/buddy/read`
  (Talk keeps its live callback for `/buddy/voice/`). `res/values/wear.xml` declares capability `buddy_watch_read`, so
  the phone finds only watches with this build; an older watch app counts as unreachable (phone + notice). Its state
  (the current channel and speaker) is static and main-thread only.
- **Busy (round 1 F2)** = `Talk.busy()` || `Call.on` || `TalkActivity.dictating()`.
  - `Talk.busy()` (new): `waiting || instance != null`. Talk's service lives until its last reply has been said, so
    this covers replies still playing after `end()`.
  - `TalkActivity.dictating()` (new): `listen()` stores `elapsedRealtime()` before `startActivityForResult`, and
    `onActivityResult` clears it. It counts for at most 60 s, so a recognizer abandoned with Home doesn't block
    readings for long.
- `onChannelOpened` (posted to main): busy → `close(ch, NOT_PLAYED)`. Otherwise this channel becomes the reading. A
  reading still playing is stopped and closed with 0: the phone sends one reading at a time, so it had already given
  that one up. Then `startForegroundService(self)`; if that throws (the Android 12+ background-start rule) →
  `close(ch, NOT_PLAYED)`.
- `onStartCommand`: `startForeground` (type `mediaPlayback`, low-importance channel, "Reading Buddy's reply") and a
  partial wake lock (11 min). If it is busy now (the state may have changed since the channel came) → NOT_PLAYED.
  Otherwise `getInputStream` → `Speaker(false, "read <id>", …)` with `audible()` always true. If `getInputStream` fails
  → NOT_PLAYED.
- `ended(by)` (posted to main, current channel only): `!sounded()` → NOT_PLAYED (1); `eof` → PLAYED (2); otherwise 0
  (broke after sound: nobody replays it). It closes the channel with that code, stops the foreground service, releases
  the wake lock and calls `stopSelf`.
- `ReadAloud.silence()`: `speaker.stop("hush")` (Speaker then reports no `ended`) and `close(ch, 0)` (the phone sees
  `broken`: nobody reads it), then stop. Called from `TalkActivity.listen()` and `TalkActivity.call()`, so the watch's
  mic never hears a reading.
- Manifest: the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission and the service entry. `Speaker` and `Call` are
  unchanged. Talk gets only `busy()`, so 007's behaviour is unchanged.

### New paths and codes (in addition to 003's and 007's tables)
| Path / code | Direction | Meaning |
|---|---|---|
| channel `/buddy/read/<id>` | phone → watch | one reading: PCM 16-bit mono 16 kHz LE; the phone closes its OutputStream at the end, the watch closes the channel with a code |
| close code `1` (NOT_PLAYED) | watch → phone | nothing of it sounded (busy, service refused, empty or silent stream, player failed before sound): the phone reads it |
| close code `2` (PLAYED) | watch → phone | it played to the end of the stream (`eof` after sound) |
| any other close | either | broken or unknown (hush, break after sound, disconnect): nobody reads it again |
| capability `buddy_watch_read` | watch | a watch app that can play readings |

## Steps
1. **`Pcm16k` + tests.** `app/src/main/java/app/buddy/assistant/Pcm16k.java`,
   `app/src/test/java/app/buddy/assistant/Pcm16kTest.java`.
2. **Voice's routed entry (no caller yet).** `Voice.java`: extract `here(c, clean)` and `chatgptVoice(c)`; add `routed`,
   `dropRouted`, `routedPending`, `routedGen`, `ttsFailed`, the `taps` listener and `read.wav`. Nothing calls `routed`
   yet: no behaviour change.
3. **`ReadQueue` + tests.** `app/src/main/java/app/buddy/assistant/ReadQueue.java` (Stream, Events, Out, the rules),
   `app/src/test/java/app/buddy/assistant/ReadQueueTest.java`.
4. **Shell + routing.** New `WatchVoice.java` (Link, loader, `chosen`, `Out` implementation, `speak`/`active`/`stop`).
   `Voice.speak` → `WatchVoice` when chosen; `isSpeaking`/`stopSpeaking` include it. `AudioRoute.java`: Watch last in
   `choices` when the link loads, the `"watch"` → Auto helper in `use`/`reconcile`, doc comment. `strings.xml`:
   `audio_watch`, `voice_watch_unavailable` (English only, the fork rule since 001). Without the link class nothing
   changes yet.
5. **Phone link.** New `app/src/watchlink/java/app/buddy/assistant/WatchVoiceLink.java` (find with its 3-s guard, open,
   channel callback, timers, wake lock) and `ReadStream.java`; `app/src/testGithub/java/app/buddy/assistant/`
   `ReadStreamTest.java` and `WatchVoiceTest.java`. Watch now shows in the github build.
6. **UIs.** `SetupActivity.java` (watch note + async unreachable sentence, comment), `VoiceBar.java` (comment only if
   it still says Android 12+), `SettingsTool.java` (`audio_device` `allowed()` text); `strings.xml`:
   `setup_audio_device_watch`, `setup_audio_device_watch_away`.
7. **Watch app.** New `wear/src/main/java/app/buddy/assistant/wear/ReadAloud.java`; `wear/src/main/AndroidManifest.xml`;
   new `wear/src/main/res/values/wear.xml`; `wear/src/main/res/values/strings.xml` (channel and notification text);
   `Talk.java` (`busy()`); `TalkActivity.java` (`dictating()` stamp/clear, two `ReadAloud.silence()` calls).
8. **Docs.** `README.md` Galaxy Watch section: one bullet (Settings → Voice → Audio device → Watch; both voices; phone
   fallback with a spoken notice; needs the watch app from the same release).
9. **Ship and check** (orchestrator, after review and a green CI): merge, BuildInfo `-fork.<n>`, tag, `update_buddy
   install=true`, `adb install -r buddy-watch.apk` on the watch, then the device checks below.

## Test plan
| Done-when | Proof | Where |
|---|---|---|
| 1. List shows Watch, unreachable state | Setup → Voice → Audio device lists Watch last; the VoiceBar chip cycles to it; a `buddy_settings` read lists `watch`. With the watch's Bluetooth off, Setup's note shows the unreachable sentence. Flavor: `unzip -p <apk> 'classes*.dex' \| grep -ac 'Lapp/buddy/assistant/WatchVoiceLink;'` is 0 for F-Droid and ≥ 1 for github (the type descriptor exists only with the class definition; main's reflection string is the dotted name). `WatchVoiceTest`: the link loads in github, and the loader returns null for a missing class (the F-Droid path, so `choices` leaves Watch out) | CI artifacts + unit; device after ship |
| 2. Heard from the watch once, phone silent, both voices | Phone TTS: send a phone chat message with read aloud on; the reply is heard on the watch only, and watch `logcat -s BuddyTalk` shows one `read <id>: … ended by eof` per reply. Same with Settings → Voice → ChatGPT. Also with the watch screen off and its app closed. Cold start: force-stop Buddy, then send two quick messages: both replies are heard on the watch, in order. "Stop voice" in the bubble during a watch reading: the watch goes silent and the phone doesn't read it | device after ship (+ watch adb) |
| 3. Unreachable → phone, once | Watch Bluetooth off: the reply is read on the phone, the notice first; a second reply has no notice; with the watch back on, the next reply plays on the watch, and a later unreachable reply has the notice again. Busy: (a) during a watch talk, after its answer is shown and while the watch is still saying it (after `/buddy/done`), a phone chat's reply is read on the phone; (b) with the watch's recognizer open, a phone chat's reply is read on the phone and nothing plays on the watch | device after ship |
| 4. 001/003/007/009 unchanged, CI green | CI: `testGithubDebugUnitTest` (new `Pcm16kTest`, `ReadQueueTest`, `ReadStreamTest`, `WatchVoiceTest`; existing `PcmOutTest`, `SettingsCoverageTest`), `assembleGithubRelease assembleFdroidRelease :wear:assembleRelease`, F-Droid reproducibility, `helper-tests`. Device smoke (AGENTS "Install"): Speaker/Phone choices route read aloud as before; a watch talk reply is said on the watch once (not also via 012); a watch call works; the watch app opens its talk screen (009) | CI; device after ship |

Unit tests:
- `Pcm16kTest`: 16 kHz mono 16-bit passes through unchanged; 24 kHz and 22.05 kHz give `n·16000/rate` samples (±1) and
  keep a 1 kHz tone's zero-crossing rate; stereo is averaged; 8-bit and float convert to the same 16-bit values; feeding
  in pieces (odd byte splits included) equals feeding at once.
- `ReadQueueTest` (fake `Out`; the test fires the callbacks):
  - Order: two texts → one `find`, the second only after the first settles.
  - Two texts while the first `tts` hasn't called `done` (cold engine) → the second waits, and both are produced, in
    order.
  - Unreachable: `tts(notice)` first, and `tts(text)` only after the notice's `done`. A second unreachable text gets no
    notice; after a `played` reading the notice is due again.
  - `refused` → `stopProducers`, then the phone; `broken` → `stopProducers`, no phone; `played` → no phone.
  - ChatGPT `failed` → old stream cancelled, phone TTS on a new stream to the same node, the old stream's events
    ignored. A second failure (refused) goes to the phone once, with no loop.
  - `tts` `done` → `stream.close()`.
  - Cap fired → stream cancelled, `stopProducers`, the next text starts; a cap fired after its reading ended does
    nothing.
  - Stop during `find` → the late node is ignored, nothing opened. Stop during a pending `tts` → its late `done` is
    ignored, and the stream is cancelled and `stopProducers` called. Stop then a new text → a new `find`, and every old
    callback (find, events, done, cap) does nothing.
  - `active()` is true from `speak` until the last text settles, and false after `stop`.
- `ReadStreamTest`:
  - Not open within the timeout → `refused` once; a later attach closes the channel.
  - `close()` with nothing written → `refused`. `close()` after audio → nothing yet; then code 2 → `played`.
  - Code 1 → `refused`, before and after `close()`. Code 0 after `close()` (disconnect, hush) → `broken`. Code 0 before
    → `broken`.
  - Write failure then code 1 → `refused`; code 1 then write failure → `refused`. Write failure then `graceExpired` →
    `broken`. Overflow then `graceExpired` → `broken`.
  - Nothing after `cancel()`, including an outcome already handed to the executor; never two events.
- `WatchVoiceTest`: as in row 1.
- Not unit-tested: Voice's TTS exits (TextToSpeech doesn't run in JVM tests; review checks that each exit calls `done`
  once and cleans up) and the watch's code mapping and busy predicate (the wear module has no test setup). These are
  covered by review and the device rows above.

## Risks
1. **The watch may not be allowed to start its foreground service from the background** (Android 12+ rule; which OS
   the Watch4 runs now is unknown, Wear OS 3 to 6). Then every reading comes back NOT_PLAYED and the phone reads it
   with the notice: safe, but Watch does nothing. Found by the first device check. Fix forward, in order: the
   documented exemption "the user turns off battery optimizations for your app", applied once on the watch over adb
   (`adb shell dumpsys deviceidle whitelist +app.buddy.assistant`), plus a README line; if that fails, back to the
   Planner.
2. **TTS engine audio callbacks.** If the phone's engine doesn't call `onAudioAvailable` during `synthesizeToFile`, the
   stream stays empty → refused → the phone reads it with the notice (safe but not Watch). Documented behaviour,
   checked on the device in done-when 2; fix forward if not.
3. **Verification needs a release**: `install_apk` refuses Buddy's own APK (playbook learning), and the watch APK goes
   on by adb. Until the watch has this build it lacks the capability, so the phone reads with the notice (safe).
4. **Link drop or hush once the watch has the stream**: the phone can't tell whether anything was heard, so it is
   `broken` with no replay ("once" wins), even if the drop came before the first sound. The next reply tries again.
5. **Memory**: a long reading can queue up to 10 min of PCM (≈19 MB) on the phone, and a longer one breaks at the cap.
6. **Hung producer**: a TTS engine or ChatGPT page that never calls back holds the queue until the 11-min cap, and
   nothing is heard for that reading; "stop voice" ends it sooner. The cap also cuts a phone-fallback reading longer
   than about 11 minutes.
7. **Upstream merges**: hunks in `Voice`, `AudioRoute`, `SetupActivity`, `SettingsTool` are small; the rest is new files
   or fork-only watch code.

## Open questions
None blocking. (Decisions that could have gone to Nicholas, Watch always listed and the spoken notice, are recorded in
`decisions.md` with their reasons.)
