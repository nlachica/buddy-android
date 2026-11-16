# Research: 012-watch-audio-device
<!-- Researcher (claude-sonnet-5-5), 2026-11-16. -->

## Summary
- **Watch can't be a real Android audio device (platform limit, already measured).** 001's verify checked on the
  Watch4 on 2026-11-15: Settings shows "Connected for calls", but `AudioRoute.choices()` lists only Phone and Speaker;
  Samsung relays watch calls itself, so no app can route audio there (`docs/features/001-audio-io-selector/verify.md:11,18-22`).
  So "Watch" has to be a **new virtual entry** that sends the voice over the Wear Data Layer (the 007 mechanism), not
  `setCommunicationDevice`.
- **The device list is built in one place**: `AudioRoute.choices(Context)` (`AudioRoute.java:34-43`) returns `{key,label}`
  pairs, Auto (key `""`) first, then Android's communication devices (API 31+ only). All three UIs read it: Setup
  segmented row (`SetupActivity.java:614-628`, hidden when `devices.length <= 1`), voice bar tap-to-cycle
  (`VoiceBar.java:82-84,166-170`), and `buddy_settings audio_device` (`SettingsTool.java:342-351`, its allowed values
  come from `choices` and are checked by `SettingsCoverageTest.java:41`). The saved value is one string pref
  `audio_device` (`Prefs.java:778-785`). A reserved key (e.g. `"watch"`) added here shows in all three; every consumer of
  the key must treat it as "not a communication device" (`reconcile()` looks the key up in
  `getAvailableCommunicationDevices()` and would find nothing → `drop()`, `AudioRoute.java:107-116`; Setup shows
  "isn't connected" for an unmatched saved key, `SetupActivity.java:625-627`, and `label` splits the key at `:`).
- **Main source can't see the watch link.** `WatchLink`, `WatchCall`, `PcmOut` are in `app/src/watchlink` (github
  flavor only; Play services) (`app/build.gradle.kts:53-57`). `src/main` has no reference to them
  (`grep WatchLink app/src/main` → only comments). F-Droid also doesn't compile them. A Watch option plus the
  read-aloud hook needs a **seam in main** (interface/hook set by the github flavor) or a flavor split like `Flavor.java`
  (`app/src/{github,fdroid}/.../Flavor.java`). Non-goal: F-Droid shows no Watch.
- **Read aloud has one entry**: `Voice.speak(Context,String)` (`Voice.java:232-246`): cleans text with `spoken()`,
  `SpeechPause.begin`, `AudioRoute.use`, then ChatGPT voice (`CodexSpeech.speak`, only if `speech_engine=chatgpt` and
  Codex signed in) or phone TTS (`phoneSpeak`). Callers: chat replies `ChatHub.add` (`ChatHub.java:1055-1059`, gated by
  `Prefs.speakReplies && !CodexLive.on() && parent.isEmpty() && !ses.watch`), Bubble (`Bubble.java:2436,2480`), Setup
  samples (`SetupActivity.java:610,623`). Intercepting inside `Voice.speak` covers all of them.
- **ChatGPT voice → watch already exists on the phone**: `CodexSpeech.speak(c, text, Fallback, Sink)`
  (`CodexSpeech.java:89`) with `Sink{pcm(byte[16k mono]),close()}` (`:54-58`) mutes the page and streams PCM;
  `WatchLink.speakOnWatch` (`WatchLink.java:195-213`) opens a channel `/buddy/voice/<id>` to a node, backs it with
  `PcmOut(VOICE_CAP=320000, live=false, ABANDON_MS=60000, onBroken→CodexSpeech.cancel)`, and on voice failure sends
  `/buddy/say {chat,id}` so the watch says the text itself. Reusable almost as-is, but it is private to `WatchLink`, tied
  to `tracked` chats (the node comes from `tracked.get(ses.id)`), and `/buddy/reply` carries the chat id.
- **Phone TTS → watch has no audio path**: 007 only did ChatGPT voice; with it off the *watch's own TTS* says the text
  (`/buddy/reply` without `voice`; `Talk.say`). The phone's TTS engine output is not streamed anywhere (`Voice.phoneSpeak`
  speaks via `TextToSpeech`, `Voice.java:248-266`). The brief says "both voices (phone TTS and ChatGPT voice) work" and
  "in the phone's chosen voice": for phone TTS the only existing route is the watch's TTS reading the text. Streaming
  phone-TTS audio would be new (`TextToSpeech.synthesizeToFile`/stream; unverified feasibility). **Planner must decide**
  (see Open points).
- **The watch has no always-on receiver.** Its manifest has only activities and two services, both `exported=false`
  (`Call`, `Talk`); no `WearableListenerService` (`wear/src/main/AndroidManifest.xml:41-82`; no grep hit for
  `WearableListenerService` in `wear/src`). `Talk` is a per-chat foreground service started when the user talks from
  the watch; it ignores messages for other chats and, after the wait ends, accepts only `/buddy/say`
  (`Talk.java:306-316`, `onPhone`), and it receives `/buddy/voice/<id>` channels (`Talk.java:557-600`). So a reply read
  aloud for a phone-initiated chat (no Talk running) **has nobody listening on the watch**: a new watch-side receiver
  (WearableListenerService, or start a service on message) is required. Key facts for it: foreground-service types
  declared are `microphone`, `remoteMessaging` only (`AndroidManifest.xml` as above); playing audio from a background
  listener on Wear OS 3/Android 11 is allowed with a foreground service or short wake (unverified, device test).
- **Phone → watch node discovery doesn't exist.** The phone only learns a node when a message arrives
  (`tracked` map, `WatchLink.java:44`, `deliver`). `grep getNodeClient|getConnectedNodes|CapabilityClient app/src` →
  none. The phone needs `Wearable.getNodeClient(...).getConnectedNodes()` or a watch capability (the watch declares none;
  the phone declares `buddy_phone` in `app/src/watchlink/res/values/wear.xml:4-6`, used by `Phone.find`,
  `wear/.../Phone.java:36-50`). That lookup also gives the "list shows Watch when connected / unreachable state" signal.
- **Fallback on unreachable**: the 007 pattern is per-reply (`speakOnWatch`: channel doesn't open in `OPEN_MS=10 s` →
  `sink.out.broken()` → `CodexSpeech.cancel`; fallback → `/buddy/say`). Brief wants: unreachable → read on the phone
  with normal output, once, and "say so once". No such "say so once" notice mechanism exists for audio; strings live in
  `app/src/main/res/values/strings.xml`. The fork's existing 007 design for a chat-less phone voice fallback is the
  `Fallback` callback of `CodexSpeech.speak` (phone voice if ChatGPT's fails: `Voice.java:239-243`).
- **Voice + AudioRoute interplay**: when Watch is the chosen device, `AudioRoute.use(c)` must not apply a communication
  device / mode (the phone must stay silent and the call mode must not engage), and the saved key must not make
  `reconcile()` log "refused". `AudioRoute.use` is called from `Voice.speak`, `Voice.listen`, `Voice.say`, `CodexLive`
  (`Voice.java:104,236,276`, `CodexLive.java:215,746`). Mic input "Watch" is a non-goal; with Watch chosen, listening
  should behave like Auto.

## Repo map
- Device list/pref: `AudioRoute.java:34-43` (choices), `:46-48` (indexOf), `:50-52` (key = `type:productName`), `:54-68`
  (label; strings `audio_auto/speaker/phone/headset`), `Prefs.java:778-785`, `strings.xml:1344-1354`
  (`setup_audio_device`, `_info`, `_missing`, `voice_audio_device`).
- UIs: `SetupActivity.java:614-628`; `VoiceBar.java:82-84` (cycles with `(idx+1)%ch.length`, calls `AudioRoute.use`),
  `:166-170` (`showDevice`); `SettingsTool.java:342-351` (+ approval card per change, 010).
- Tests touching it: `app/src/test/.../SettingsCoverageTest.java:41,292`; `app/src/testGithub/.../PcmOutTest.java`.
- Read aloud: `Voice.java:232-293`; `ChatHub.java:1040-1060` (skips when `ses.watch`); `CodexSpeech.java:54-125`
  (Sink, cancel, stop); `SpeechPause.begin`.
- Phone watch link (github only): `WatchLink.java` (`speakOnWatch` 195, `VoiceSink` 180, `watchChannels` 216, `tell` 275,
  `json` 279, `voiceOn()` 175, `SAY`/`VOICE` constants 37, `OPEN_MS/ABANDON_MS` 40); `PcmOut.java` (cap/live/attach/
  write/finish/cancel/broken/peerClosed); `WatchCall.java`; manifest `app/src/watchlink/AndroidManifest.xml` (one service,
  MESSAGE_RECEIVED + CHANNEL_EVENT filters on `wear://*/buddy`).
- Watch app: `wear/.../Phone.java` (paths 17-20, node lookup), `Talk.java` (service; voice channels 557-600; `onPhone` 306),
  `Speaker.java` (plays a PCM InputStream via AudioTrack; `play/stop/sounded/audibleNow`), `Call.java`.
- Flavor: `app/src/{github,fdroid}/.../Flavor.java` (`FDROID` const); only `CheckActivity`, `BuddyService`,
  `SetupActivity:1009`, `Prefs:156`, `Tools`, `Ui` use it.
- Build/test: `./gradlew --no-daemon testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease
  :wear:assembleRelease` (`.github/workflows/build.yml:63`); `helper-tests` job gates the APK
  (`build.yml:15,26`). F-Droid reproducibility check at `build.yml:124`. Android Studio-less phone build: `build.sh`
  compiles `src/github/java` with javac (no Play services), which is why watchlink has its own folder
  (`app/build.gradle.kts:48-52`).

## Prior art
- Fork features: 001 audio selector (`docs/features/001-audio-io-selector/{plan,decisions,verify}.md`: communication
  device + mode mechanism; watch not listed, platform limit); 003 watch companion (message paths table in
  `docs/features/003-wear-companion/plan.md`); 007 watch live voice (audio over channels: `007/plan.md` "Audio format and
  transport", PcmOut semantics: normal end = close output stream only, never `ChannelClient.close`; watch `Speaker`
  rules; Fallback = watch TTS once). 007 is the reusable plumbing; 012 is the first **phone-initiated, chat-independent**
  use of it.
- 007 plan already probed that Codex realtime over websocket needs an API key; irrelevant here since read-aloud
  ChatGPT voice already yields PCM via `CodexSpeech` Sink (`007/plan.md` "The fact that shapes everything").
- Upstream (`ghorbelhamdi/buddy-android`) has no watch link (the fork's own). Not checked via `gh` this run (unverified).

## Constraints and risks
- **Flavor split / F-Droid**: any new class using Play services must live in `src/watchlink`; the main-side hook must be
  a plain interface or null-checked static so F-Droid and the javac-only `build.sh` still compile (`build.gradle.kts:48-57`).
  F-Droid's APK must stay reproducible (no new dependency).
- **Phone must be silent but chat state intact**: `Voice.speak` also drives `SpeechPause.begin`, `AudioRoute.use`, and
  `Voice.isSpeaking()` (used by `AudioRoute.poll`, `AutoPause`, `Voice.stopSpeaking`); a watch-routed reply must keep
  `isSpeaking()`/`stopSpeaking()` coherent (e.g. hush from the phone should cancel the watch stream; `CodexSpeech.cancel(sink)`
  exists). Auto-listen after read ("read-card → listen") flows watch for `isSpeaking` (unverified, `AudioRoute.java:150-165`).
- **No double speech**: `ChatHub.add` skips `Voice.speak` for `ses.watch` chats (watch-run replies already go to the watch
  via the relay). If Watch audio device is chosen *and* a chat is a watch-run, replies must not be read twice (brief: "once").
- **Unreachable detection is slow**: the existing open timeout is 10 s (`WatchLink.OPEN_MS`); `sendMessage` fails fast
  only if no node (unverified timing). For "reply read on the phone instead, once" the Planner needs a reachability test
  before choosing (node list/capability) plus the 10 s channel-open fallback.
- **Watch side not running**: a phone-initiated `/buddy/say` or `/buddy/voice/<id>` with no watch receiver is lost
  (see Summary). Also the watch's TTS init, `Speaker`, wake lock and foreground service live in `Talk`; extracting them
  for reuse touches 007 code (risk to "007 unchanged").
- **Key semantics**: `audio_device` is a free-form string (`type:name`); `SettingsTool` allowed values, the "is away"
  hint in Setup, `label()`, `indexOf`, backup/restore of prefs (not checked), and `SettingsCoverageTest` assume device
  keys (`SettingsCoverageTest.java:41`). A virtual `"watch"` key must be reserved and not collide with `type:name`.
- **Android <12**: `choices()` returns only Auto below API 31 (`AudioRoute.java:37`); Setup hides the row when
  `devices.length <= 1` (`SetupActivity.java:615`), VoiceBar cycles with that. Watch entry is independent of API level,
  so those assumptions (hide row, "Android 12+" comment) change.
- **Tests**: no watch unit tests (`docs/features/009-watch-open-screen/research.md:14`); phone pure-Java logic is testable
  in `app/src/test` (main), Play-services classes in `app/src/testGithub` (PcmOutTest pattern). Final proof is on-device
  (Watch4, S21 FE).
- **Version/tag**: after merge, bump `BuildInfo` `VERSION`/`VERSION_CODE` per AGENTS.md "Updating Buddy on the phone".

## Open points the Planner must decide (facts, not answers)
1. Phone TTS to the watch: watch TTS reading the text (exists, but not "the phone's voice") vs streaming phone TTS audio
   (new, unverified). `Voice.phoneSpeak` is the only phone-TTS path.
2. Where the hook sits: inside `Voice.speak` (covers chat replies, Bubble, samples) vs only `ChatHub.add`.
3. How the phone finds the watch node (`getNodeClient().getConnectedNodes()` vs a capability the watch must declare via a
   new watch build) and how the picker shows reachability ("always with a clear unreachable state" vs only when connected).
4. Watch receiver for phone-initiated audio when `Talk` isn't running (new `WearableListenerService`/service on the
   watch, manifest, foreground-service type, Speaker reuse) and its path/chat-id contract versus `Talk.onPhone` which
   requires a matching `chat`.
5. Where "say so once" shows (a chat/step line, toast, or notification) and what counts as once (per fallback episode).
