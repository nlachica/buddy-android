# Plan: 012-watch-audio-device
<!-- Planner (claude-opus-5-5), 2026-11-16. Sources: brief.md, research.md, code read at 256c952. -->

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
notification; replaying the rest of a reading on the phone after the link drops mid-reading.

## Design

### The fact that shapes everything
The watch can't be an Android audio device (001 verify: Samsung relays watch calls itself, `AudioRoute.choices()`
never lists it). So Watch is a **virtual entry**: key `"watch"` (device keys are `type:name` and always hold a `:`, so
no clash). For Android routing it means Auto; for read aloud it means "stream the voice to the watch over the Wear
Data Layer", the 007 plumbing (`PcmOut` on the phone, `Speaker` on the watch, PCM 16-bit mono 16 kHz LE).

### Seam between main and the watch link
`src/main` can't reference `src/watchlink` (F-Droid and `build.sh` don't compile it). One new main class,
`WatchVoice`, holds a small interface `WatchVoice.Link` and loads its github implementation once by reflection:
`Class.forName("app.buddy.assistant.WatchVoiceLink")`, `null` when absent (F-Droid, `build.sh`). Minify is off
(`app/build.gradle.kts`), so no keep rule. `Link` has two calls:
- `find(Context, Consumer<String> node)`: the node id of a reachable watch whose Buddy app has 012
  (`CapabilityClient.getCapability("buddy_watch_read", FILTER_REACHABLE)`; the watch declares that capability), or
  null; main thread; answers within 3 s or says null.
- `Stream open(Context, String node, Events e)`: one reading's audio stream to the watch. `Stream` extends
  `CodexSpeech.Sink` (`pcm`, `close` = normal end) with `cancel()` and `done()`. `Events` (main thread, at most one
  of them, never after `cancel()`): `played()` (the watch closed the channel normally after the end),
  `refused()` (nothing was played: the phone must read it), `broken()` (stopped partway: nobody reads the rest).

Alternatives: a manifest `ContentProvider` in watchlink that registers the hook at start (more parts than one
`Class.forName`); `Flavor.java` returning the hook (breaks `build.sh`, which compiles `src/github` without
watchlink).

### Main side: `WatchVoice` (coordinator, main thread)
- `chosen(c)`: `"watch".equals(Prefs.audioDevice(c)) && link() != null`.
- `Voice.speak` keeps cleaning, `SpeechPause.begin`, `AudioRoute.use`, then: Watch chosen → `WatchVoice.speak(c, clean)`;
  else the existing branch, moved unchanged into `Voice.here(c, clean)` (ChatGPT voice with phone fallback, or phone
  TTS). Hooking `Voice.speak` covers every caller (ChatHub replies, Bubble cards, Setup samples); ChatHub's existing
  `!ses.watch` and `!CodexLive.on()` gates stay, so a watch-run reply is never read twice.
- `speak`: texts join a FIFO; one `find` serves everything queued while it runs, then each text goes, in order, to
  `onWatch(node, text)` or `onPhone(text)`. (Without the FIFO two quick replies could swap order.)
- `onWatch`: `open` a stream, then produce into it with the phone's chosen voice:
  ChatGPT voice (`Voice.chatgptVoice(c)`, the test `Voice.speak` uses today) → `CodexSpeech.speak(app, text, fallback,
  stream)`, whose fallback (the voice failed before starting) starts a new phone-TTS reading on the same node (same
  rules, its own stream) and marks this reading handed on; phone TTS → `Voice.phoneSpeak(app, text, stream)` (below).
  Events: `refused` → `CodexSpeech.cancel(stream)`, then `onPhone(text)` unless handed on; `broken` →
  `CodexSpeech.cancel(stream)`; `played` → the "said once" flag resets.
- `onPhone(text)`: `SpeechPause.begin` + `AudioRoute.use` (a refusal can come 10 s after `speak`), then, if the notice
  hasn't been said in this episode, `Voice.here(notice)` as its own utterance (so the reply's language detection isn't
  mixed up), then `Voice.here(text)`. An episode ends when a watch reading is `played`. That is "say so once".
- `active()`: FIFO not empty, a `find` running, or a stream not `done()`. `Voice.isSpeaking()` adds it, so
  SpeechPause keeps media paused while the watch reads, the bubble offers "stop voice", and "answer cards by voice"
  waits for the watch to finish. `stop()`: clears the FIFO and `cancel()`s every stream; `Voice.stopSpeaking()` calls
  it (with `CodexSpeech.stop()` and `tts.stop()` as now), so a hush on the phone or the start of dictation stops the
  watch.

### Phone TTS to PCM (Voice)
`phoneSpeak`/`say` get an optional `Sink`. Same voice choice as now (language detection, best voice), then for a sink
`tts.synthesizeToFile(text, params, cacheDir/<id>.wav, id)` instead of `tts.speak`: nothing plays on the phone. One
`UtteranceProgressListener` set at engine init handles only ids in a `taps` map (`ConcurrentHashMap`, callbacks come
on binder threads): `onBeginSynthesis(rate, format, channels)` creates a `Pcm16k` converter; `onAudioAvailable` feeds
it and writes the result to the sink; `onDone`/`onError`/`onStop` close the sink and delete the file. Phone utterances
(`buddy-…` ids) are untouched. The cold-engine `pending` slot becomes `{text, sink}`; a pending watch text that gets
replaced has its sink closed (the existing "only the last text while the engine starts" rule, now without a stuck
stream). `onAudioAvailable` is documented for `synthesizeToFile` since API 24 ("a copy of what will be ... written to
the file system", developer.android.com UtteranceProgressListener); minSdk is 30.

`Pcm16k` (main, pure Java, unit-tested): engine PCM (8-bit unsigned, 16-bit LE or float; any rate; 1 or 2 channels)
→ 16-bit mono 16 kHz LE, linear interpolation with the phase carried across chunks (the same method as `TAP_JS` in
CodexSpeech) and an odd trailing byte carried to the next chunk.

Alternative: the watch's own TTS reads the text (`/buddy/say`-like). Rejected: the brief asks for "the phone's chosen
voice", and it would need TTS in the new watch service besides the PCM player.

### AudioRoute and the three UIs
- `AudioRoute.choices`: Watch (`audio_watch`, "Watch") is appended **last** when `WatchVoice.link() != null`, so 001's
  devices keep their order and index. On Android < 12 the list becomes Auto + Watch, so Setup's row and the voice bar
  chip now show there too (the `> 1` tests are right as they are; only the "Android 12+" comments change).
- `AudioRoute.use`/`reconcile` read the wanted device through one helper that maps `"watch"` to `""`, so Watch is
  exactly Auto: no communication device, no communication mode. (Without it, `use()` would null `applied` for the
  non-empty key and a Speaker chosen just before would not be cleared until the session ends.)
- Setup → Voice: when Watch is saved, the note under the row says what Watch does; a `find` then replaces it with the
  unreachable sentence if no watch can be found. That is the "clear unreachable state" (the voice bar chip has no room;
  it shows "Watch"). Tapping Watch plays the sample on the watch, as for any device.
- `buddy_settings audio_device`: "watch" appears in the allowed values by itself (they come from `choices`); its
  `allowed()` text adds what it means, like `chatgpt_voice` does. The approval card per change (010) is unchanged.

### Watch link, phone side: `WatchVoiceLink` (src/watchlink, public, no-arg constructor)
- `open`: path `/buddy/read/<id>` (own sequence, a namespace Talk ignores: it only takes `/buddy/voice/`). A
  `PcmOut(READ_CAP = 10 min of PCM ≈ 19.2 MB, live=false, READ_ABANDON_MS = 11 min, onBroken)`. Phone TTS is faster
  than real time and the watch pulls at playback speed (its non-live `Speaker` blocks), so the whole reading may queue
  here: 007's 10 s cap and 60 s abandon would cut long readings. Channel open as in `speakOnWatch`; not open within
  10 s → `refused`.
- Its own `ChannelClient.ChannelCallback` (registered once) maps `/buddy/read/` closes to the stream:
  app-specific code `NOT_PLAYED = 1` → `refused` (also after `finish()`); any other close → `PcmOut.peerClosed()`
  (before `finish()`: `broken`; after: `played`). 007's `WatchLink.watchChannels` ignores these paths (its map has no
  such keys); `WatchLink` itself is not touched.
- The outcome rules live in a small Google-free class `ReadStream` (opening/open/finished, one event, none after
  `cancel`) next to `PcmOut`, unit-tested in `testGithub` like `PcmOutTest`.
- A partial wake lock (`buddy:watch-read`, timeout 11 min) while any stream is open: with the screen off nothing else
  keeps the phone's CPU up while the watch pulls a long reading.

### Watch side: `ReadAloud` (wear, one class)
- `ReadAloud extends WearableListenerService`, `exported="true"`, a `CHANNEL_EVENT` filter on `wear://*/buddy/read`
  (Talk keeps its live callback for `/buddy/voice/`). `res/values/wear.xml` declares capability `buddy_watch_read`, so
  the phone finds only watches with this build (an older watch app counts as unreachable: phone + notice).
- `onChannelOpened` (posted to main): if `Talk.waiting || Call.on` → `close(ch, NOT_PLAYED)` (a watch conversation or
  call owns the speaker; the phone reads it). Else queue it and `startForegroundService(self)`; if that throws (Android
  12+ background-start rule) → close every queued channel with `NOT_PLAYED`.
- `onStartCommand`: `startForeground` (type `mediaPlayback`, low-importance channel, "Reading Buddy's reply"),
  partial wake lock (11 min), play the queue in arrival order, one at a time: `Speaker(false, "read <id>", …)` with
  `audible()` always true; on `ended` close the channel normally (code 0) and play the next; queue empty → stop
  foreground, release, `stopSelf`.
- `ReadAloud.silence()`: stops the speaker and closes every channel with code 0 (the phone sees `broken`: nobody
  reads it). Called from `TalkActivity.listen()` and `TalkActivity.call()`, so the watch's mic never hears a reading.
- Manifest: `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission, the service entry. Talk, Call and Speaker are not changed.

### New paths and codes (in addition to 003's and 007's tables)
| Path / code | Direction | Meaning |
|---|---|---|
| channel `/buddy/read/<id>` | phone → watch | one reading: PCM 16-bit mono 16 kHz LE; the phone closes its OutputStream at the end, the watch closes the channel after playing it |
| close code `1` (NOT_PLAYED) | watch → phone | the watch didn't play any of it (busy, or couldn't start its service): the phone reads it |
| capability `buddy_watch_read` | watch | a watch app that can play readings |

## Steps
1. **`Pcm16k` + tests.** `app/src/main/java/app/buddy/assistant/Pcm16k.java`,
   `app/src/test/java/app/buddy/assistant/Pcm16kTest.java`.
2. **Phone TTS into a Sink.** `Voice.java`: extract `here(c, clean)` and `chatgptVoice(c)`; `phoneSpeak`/`say` with an
   optional sink (`synthesizeToFile`, `taps`, the listener, `{text, sink}` pending). No caller passes a sink yet: no
   behaviour change.
3. **Coordinator + routing.** New `WatchVoice.java` (Link/Stream/Events, loader, `chosen`, FIFO, `onWatch`/`onPhone`,
   notice once, `active`, `stop`); `Voice.speak` → `WatchVoice` when chosen; `isSpeaking`/`stopSpeaking` include it;
   `AudioRoute.java` (Watch last in `choices` when the link loads, `"watch"` → Auto helper in `use`/`reconcile`, doc
   comment); `strings.xml`: `audio_watch`, `voice_watch_unavailable`. English only (fork rule since 001). Without the
   link class nothing changes yet.
4. **Phone link.** New `app/src/watchlink/java/.../WatchVoiceLink.java` (find with 3 s guard, open, channel callback,
   wake lock) and `ReadStream.java`; `app/src/testGithub/.../ReadStreamTest.java`. Watch now shows in the github build.
5. **UIs.** `SetupActivity.java` (watch note + async unreachable sentence, comment), `VoiceBar.java` (comment only if
   it still says Android 12+), `SettingsTool.java` (`audio_device` `allowed()` text); `strings.xml`:
   `setup_audio_device_watch`, `setup_audio_device_watch_away`.
6. **Watch app.** New `wear/.../ReadAloud.java`; `wear/src/main/AndroidManifest.xml`; new
   `wear/src/main/res/values/wear.xml`; `wear/src/main/res/values/strings.xml` (channel and notification text);
   `TalkActivity.java` (two `ReadAloud.silence()` calls).
7. **Docs.** `README.md` Galaxy Watch section: one bullet (Settings → Voice → Audio device → Watch; both voices; phone
   fallback with a spoken notice; needs the watch app from the same release).
8. **Ship and check** (orchestrator, after review and a green CI): merge, BuildInfo `-fork.<n>`, tag, `update_buddy
   install=true`, `adb install -r buddy-watch.apk` on the watch, then the device checks below.

## Test plan
| Done-when | Proof | Where |
|---|---|---|
| 1. List shows Watch, unreachable state | Setup → Voice → Audio device lists Watch last; VoiceBar chip cycles to it; `buddy_settings` read lists `watch`. Watch BT off: Setup note shows the unreachable sentence. F-Droid: `unzip -p app-fdroid-release.apk 'classes*.dex' \| grep -c WatchVoiceLink` = 0, github APK ≥ 1 | CI artifacts; device after ship |
| 2. Heard from the watch once, phone silent, both voices | Phone TTS: send a phone chat message with read aloud on; reply heard on the watch only; watch `logcat -s BuddyTalk` shows one `read <id>: … ended by eof` per reply. Same with Settings → Voice → ChatGPT. Also with the watch screen off and its app closed | device after ship (+ watch adb) |
| 3. Unreachable → phone, once | Watch Bluetooth off: reply read on the phone, preceded by the notice; a second reply has no notice; watch back on: the next reply plays on the watch. Busy case: during a watch talk, a phone chat's reply is read on the phone | device after ship |
| 4. 001/003/007/009 unchanged, CI green | CI: `testGithubDebugUnitTest` (new `Pcm16kTest`, `ReadStreamTest`, existing `PcmOutTest`, `SettingsCoverageTest`), `assembleGithubRelease assembleFdroidRelease :wear:assembleRelease`, F-Droid reproducibility, `helper-tests`. Device smoke (AGENTS "Install"): Speaker/Phone choices route read aloud as before; a watch talk reply is said on the watch once (not also via 012); a watch call works; the watch app opens its talk screen (009) | CI; device after ship |

Unit tests: `Pcm16kTest`: 16 kHz mono 16-bit passes through unchanged; 24 kHz and 22.05 kHz give `n·16000/rate`
samples (±1) and keep a 1 kHz tone's zero-crossing rate; stereo is averaged; 8-bit and float convert to the same
16-bit values; feeding in pieces (odd byte splits included) equals feeding at once. `ReadStreamTest`: not open within
the timeout → `refused` once and the stream cancelled (a later attach closes the channel); close code 1 → `refused`,
before and after `finish()`; other close before `finish()` → `broken`; after → `played`; overflow → `broken`; nothing
after `cancel()`; never two events.

## Risks
1. **The watch may not be allowed to start its foreground service from the background** (Android 12+ rule; which OS
   the Watch4 runs now is unknown, Wear OS 3 to 6). Then every reading comes back `NOT_PLAYED` and the phone reads it
   with the notice: safe, but Watch does nothing. Found by the first device check. Fix forward, in order: the
   documented exemption "the user turns off battery optimizations for your app", applied once on the watch over adb
   (`adb shell dumpsys deviceidle whitelist +app.buddy.assistant`), plus a README line; if that fails, back to the
   Planner.
2. **TTS engine audio callbacks.** If the phone's engine doesn't call `onAudioAvailable` during `synthesizeToFile`,
   phone-TTS readings reach the watch empty (heard nowhere). Documented behaviour, checked on the device in done-when
   2; fix forward if not.
3. **Verification needs a release**: `install_apk` refuses Buddy's own APK (playbook learning), and the watch APK goes
   on by adb. Until the watch has this build it lacks the capability, so the phone reads with the notice (safe).
4. **Link drop mid-reading**: the rest, and readings queued on the watch, are lost (no replay: "once" wins). The next
   reply tries again.
5. **Memory**: a long reading can queue up to 10 min of PCM (≈19 MB) on the phone. A watch that stops reading without
   closing keeps `isSpeaking()` true until the 11-min abandon; "stop voice" ends it.
6. **Cold TTS engine**: two readings within the engine's start keep only the last (existing behaviour); a dropped watch
   one ends as an empty stream.
7. **Upstream merges**: hunks in `Voice`, `AudioRoute`, `SetupActivity`, `SettingsTool` are small; the rest is new files.

## Open questions
None blocking. (Decisions that could have gone to Nicholas, Watch always listed and the spoken notice, are recorded in
`decisions.md` with their reasons.)
