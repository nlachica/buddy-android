# Plan: 003-wear-companion
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->
<!-- Planner (claude-opus-5-5), 2026-11-15. Revised for plan review round 1 (F1-F7, see plan-review.md). -->

## Goal
A Buddy app for Nicholas's Galaxy Watch4 (Wear OS 3+) that talks to Buddy on the phone over Google's Wear Data Layer.
Phase 1 only: on the watch, pick one of the phone's chats, tap and speak; the words go to that chat and run like a
typed message; Buddy's reply comes back, is shown on the watch and spoken by the watch's own speaker. Done when:
(1) a `wear` module in the fork builds in CI and installs on the Watch4; (2) the watch lists the phone's chats, and a
spoken message lands in the picked chat and runs; (3) the reply is shown and spoken on the watch; (4) this works with
the phone locked in a pocket, and the watch says clearly when the phone or Buddy can't be reached; (5) the F-Droid
build has no Google code and still builds, and the GitHub build carries the link; (6) it looks like Buddy and follows
the repo's style, with nothing extra.

Non-goals: live voice on the watch (phase 2), except that nothing here may block it; anything Google in the F-Droid
build; approvals, cards, settings, new-chat or other Buddy screens on the watch; making the official (upstream-signed)
Buddy talk to the watch, which is impossible (same-key rule, below); watch-side translations; Java unit-test
infrastructure (there is none); a watch app that works without the phone; a backup subsystem or an updater for the
fork (the switch uses a checklist, updates are manual: Steps, "Switching the phone").

## Design

### Shape
```
 Galaxy Watch4: app.buddy.assistant (module :wear)          Phone: app.buddy.assistant, github flavor
 ChatsActivity ──┐                                           WatchLink (WearableListenerService, github only)
 TalkActivity ───┼─ Phone.java ── MessageClient (BT/Wi-Fi) ─►   ├─ /buddy/chats  → ChatHub.sessions
   listen() A|B  │                                               ├─ /buddy/send   → ChatHub.send(ses, text)
 Talk (service) ◄┘  the conversation while it waits:      ◄──   ├─ /buddy/status → ses.running
   listener, TTS, deadlines, lines                               └─ ChatHub.Listener → heard/step/reply/done
```
The Data Layer only delivers between apps with the **same package name and the same signing certificate**
(research, "Data Layer hard rule"). So the watch app's `applicationId` is `app.buddy.assistant`, and both APKs are
signed with the fork's own release key. The watch can therefore only talk to a fork build signed with Nicholas's key,
never to the official Buddy he runs today (orchestrator notes); see Step 0, "Switching the phone" and Open questions.

### Phone side: `WatchLink`, kept where build.sh can't see it
- New source folder **`app/src/watchlink/`** (`java/`, `res/`, `AndroidManifest.xml`), added **only to the github
  source set** in `app/build.gradle.kts`, with `"githubImplementation"("com.google.android.gms:play-services-wearable:20.0.1")`.
  The fdroid variant never compiles it and has no Play services on its classpath. `build.sh` compiles only
  `app/src/main` and `app/src/$FLAVOR/java` with javac against android.jar (`build.sh:35-36`), so it never sees
  `watchlink/` and `FLAVOR=github ./build.sh` keeps working unchanged. **Trade-off (stated):** an on-phone build.sh
  APK has no watch link; it can't have one, since build.sh has no way to fetch Play services.
- `WatchLink extends WearableListenerService`, in package `app.buddy.assistant` (ChatHub is package-private). Play
  services starts/binds it when a watch message arrives, so main code never references it. `onMessageReceived` runs
  off the main thread; it takes the wake lock first (for `/buddy/send`), then posts to the main looper, since
  ChatHub is main-thread only (`ChatHub.java:14`).
- `/buddy/chats`: top-level (`parent` empty), not archived; pinned first, then `sessions.all` order (most recent
  first, `Sessions.java:148`); each `{id, title: label(), running}`, plus `accent: Prefs.accent(app)` so the watch
  uses the user's accent colour (`Prefs.java:372`). **Every** such chat, no count cap (a chat past a cut-off couldn't
  be picked at all); titles cut to 40 characters, so a row is about 100 bytes. A guard stops adding rows at 90 KB
  (about 900 chats), under MessageClient's 100 KB limit, so the reply itself can't fail; that ceiling is the stated
  limit.
- `/buddy/send` → `deliver(node, chatId, text)` (the one entry point; path B's transcription also ends here):
  1. empty text → `/buddy/error`; chat not found → `watch_chat_gone`; `BuddyService.get() == null` →
     `watch_buddy_off`. Agent events only reach ChatHub through BuddyService's MCP server (`BuddyService.java:102,542`),
     so without it a run could never answer; saying so is done-when 4's "Buddy isn't reachable".
  2. `String err = hub.send(ses, text)`: exactly what the bubble and chat screen do (`Bubble.java:1377`,
     `ChatActivity.java:603`), so it "runs like a typed message", including lifting a Stop. A running chat turns it
     into a note (`ChatHub.java:281-284`): the agent gets it with its next phone action, or, if the run ends first,
     it becomes a second run (relay, below). `err != null` → `/buddy/error {text: err}` (already the user-facing text).
  3. Success: `/buddy/heard {chat, text}`, then track `chatId → sourceNodeId`, set `ses.watch = true`, take the wake
     lock. Tracking starts after `send` returns, when `ses.running` is true in both paths (`startRun` runs inside
     `send`; a note means a run is already going).
- `/buddy/status {chat}` → `/buddy/status {chat, running}`: `running` is "the chat exists and `ses.running`". It needs
  no BuddyService. If Buddy's process died and Play services restarted it just to answer, `running` is false
  (`Session.running` is runtime-only, `Sessions.java:103-104`), which is true: that run is gone.
- Relay: one static `ChatHub.Listener`, added on the first watch message and kept for the process (same pattern as
  `Schedules.listen`, `Schedules.java:524-528`). `onMessage`, for tracked chats only: `BUDDY` → `/buddy/reply`,
  `ERROR` → `/buddy/reply` with `error: true`, `STEP` → `/buddy/step` (shown as a status line, not spoken, e.g.
  "Opening Calendar", "Still waiting for the model", the confirm step of an approval).
  `onChatsChanged`: for a tracked chat that is gone or not running, it **posts** an end check to the main looper
  instead of deciding at once. Why: when a run ends with notes the agent never got (the watch's message came after
  the agent's last phone action), `buddy_done` posts `sendNoteAsMessage` (`ChatHub.java:719-722`) before its
  `changed()` (`:729`), and nothing between `running = false` (`:701`) and that post calls `changed()`. So the end
  check always runs after the continuation has had its turn:
  - it started a second run → the chat is running again and stays tracked; `ses.watch` stays true, so that run's
    answer (the one to the watch's question) goes to the watch and the phone stays quiet;
  - otherwise it is the end → `/buddy/done {outcome: ses.outcome}` (`""`/`failed`/`stopped`), untrack,
    `ses.watch = false`; no tracked chats left → release the wake lock.
  The queued note's two other endings need nothing extra: a continuation that can't start adds an ERROR
  (`ChatHub.java:801-803`), relayed as a spoken error, and stays idle, so the check ends it; Stop puts the notes back
  in the message box with the `hub_note_not_sent` step (`:714-717`), relayed as a status line, and ends with
  `stopped`. The check is idempotent (an untracked chat is skipped), since several `changed()` calls can post it.
  Step 3 puts a comment at ChatHub's post saying the watch link relies on it coming before `changed()`.
  ChatHub always ends a run (done, give-up at 180 s / 15 min local, helper-gone check from 002), so `done` comes while
  the link and Buddy are up; when they aren't, the watch's status check notices (`Talk`, below).
- Reply text is cleaned for speech **on the phone** by `Voice.spoken(c, text)`, a static method extracted from
  `Voice.speak` (`Voice.java:220-228`, code blocks and links become the localized "code"/"link" words), and cut at
  3000 chars + "…". One `text` field is both shown and spoken (raw markdown and URLs are useless on a watch).
- Wake lock: a partial `"buddy:watch"` lock with a 15-minute cap while any watch run is tracked, the same as
  notification rules use (`Triggers.java:1106-1113`). Buddy is already exempt from battery optimization (setup).
  Together with the accessibility service keeping the process alive, a run started from the watch survives a locked
  phone in a pocket.
- A watch run doesn't speak on the phone, and doesn't post the phone's "run ended" notification: the watch already
  speaks and shows it, and Galaxy Wearable mirrors phone notifications, which would buzz the wrist a second time.
  New runtime-only field `Sessions.Session.watch` (main code, no Google types), checked in `ChatHub.add`'s
  read-aloud condition (`ChatHub.java:~983`) and in `runEnded`'s notify condition (`ChatHub.java:~966`); `runEnded`
  runs before the relay's end check clears the flag, and the flag stays set across a queued-note continuation.
- `app/src/watchlink/res/values/wear.xml`: `android_wear_capabilities` = `buddy_phone` (`translatable="false"`).
  `app/src/watchlink/AndroidManifest.xml`: the service, `exported="true"`, intent filter
  `com.google.android.gms.wearable.MESSAGE_RECEIVED`, `<data android:scheme="wear" android:host="*" android:pathPrefix="/buddy"/>`.
- The github build makes no Play-services call until a watch app signed with the same key messages it; nothing runs
  at startup.

### Protocol (MessageClient, UTF-8 JSON, all paths under `/buddy`)
| Path | Way | Body | Meaning |
|---|---|---|---|
| `/buddy/chats` | W→P | `{}` | send me the chat list |
| `/buddy/chats` | P→W | `{accent, chats:[{id,title,running}]}` | every eligible chat (90 KB guard) |
| `/buddy/send` | W→P | `{chat, text}` | the user's message; runs like a typed one |
| `/buddy/heard` | P→W | `{chat, text}` | accepted and sent to the chat (path B: what was heard) |
| `/buddy/step` | P→W | `{chat, text}` | a status line from the run |
| `/buddy/reply` | P→W | `{chat, text, error?}` | Buddy's words (or an error message), cleaned for speech |
| `/buddy/done` | P→W | `{chat, outcome}` | the run is over (after any queued-note continuation) |
| `/buddy/error` | P→W | `{chat, text}` | refused (Buddy off, chat gone, `send`'s own error) |
| `/buddy/status` | W→P | `{chat}` | still working on it? (after 30 s with nothing from the phone) |
| `/buddy/status` | P→W | `{chat, running}` | the answer |
| `/buddy/talk` (channel) | W→P | line `{chat}\n`, then PCM 16 kHz mono s16le | **path B only**: audio for the phone to transcribe |

The phone always answers to the message's `sourceNodeId`, so the watch needs no capability of its own. `heard` makes
both speech paths look the same to everything after them. Phase 2 adds a `/buddy/live` ChannelClient stream both ways
(watch mic → phone's live voice, `CodexLive`; voice audio → watch speaker), handled by the same service and the same
watch module. Nothing in phase 1 has to change for it. No protocol version field: both APKs come from the same
CI run and are installed together (Risks).

### Watch app: module `:wear`
- `com.android.application`, **namespace `app.buddy.assistant.wear`** (its own R and Java package, so its activities
  don't share names with the phone's), **`applicationId = "app.buddy.assistant"`**, minSdk 30 (Wear OS 3, the
  Watch4's floor), compile/target 36, Java 8, `buildConfig=false`, `vcsInfo.include=false`, `dependenciesInfo` off
  (as `:app`). Version name and code read from `app/.../BuildInfo.java` with the same regex as `:app`, so the pair
  always shows the same version. The signing block is the same env-based one as `:app` (`app/build.gradle.kts:30-41`),
  copied with a comment saying why it must be the same key. Its only dependency is `play-services-wearable:20.0.1`.
  No Kotlin, Compose or androidx.wear widgets: plain `Activity` and views built in code, like the phone app.
- `android.useAndroidX=true` in `gradle.properties` (project-wide). Play services pulls `androidx.core:1.0.0`, and
  AGP refuses AndroidX dependencies with the flag off. `:app` fdroid has no dependencies at all, so the flag changes
  nothing in its APK. Step 1 proves this by comparing the fdroid APK with main's, before any main-code edit.
- Manifest: `uses-feature android.hardware.type.watch`; `com.google.android.wearable.standalone=false` (needs the
  phone); `<queries>` for `TTS_SERVICE` and `RECOGNIZE_SPEECH`; theme `@android:style/Theme.DeviceDefault` (dark,
  swipe-to-dismiss = back); launcher `ChatsActivity`, plus `TalkActivity` and the `Talk` service (below).
- `Phone.java`: finds the phone with `CapabilityClient.getCapability("buddy_phone", FILTER_REACHABLE)`; when that's
  empty, `NodeClient.getConnectedNodes()` tells the two failure cases apart. No connected node: "Can't reach your
  phone. Check Bluetooth." Connected but no capability: "Buddy's watch link isn't on your phone. It needs Buddy's
  GitHub version, from the same release as this watch app." This is also what the official Buddy or a differently
  signed build looks like. It also sends JSON to a path (failure → "Couldn't send. Is your phone nearby?") and holds
  the path constants. Used by both activities and `Talk`.
- `Look.java`: Buddy's dark palette from `Theme.java` (black background for the OLED, text `#F3EFE8`, secondary
  `#C9C3B9`, the phone's accent or coral `#D97757`), padding for the round screen
  (`Configuration.isScreenRound()`), and a text-view helper. Two activities use it.
- `ChatsActivity`: "Buddy" plus the face (`bubble_face`); asks for `/buddy/chats` with its own MessageClient listener
  while on screen (a request it only needs while visible). With no answer in 8 s: "Buddy on your phone isn't
  answering." Errors get a Retry button. The list is a `ScrollView` of rows (title, plus "working" for running
  chats); it takes focus so the Watch4's bezel scrolls it. Tap → `TalkActivity(chat id, title, accent)`.
- **`Talk`**, a `Service` that is foreground while a conversation waits for the phone (about 130 lines). It, not the
  activity, receives and speaks, because the wait outlives the screen: Wear OS returns to the watch face after the
  wrist goes down, and Android may reclaim a stopped activity's process without `onDestroy`.
  - The conversation, process-wide: chat id and title, its lines (you / Buddy / error / status), whether it waits,
    and an `unseen` mark (lines that arrived while no activity showed them). One at a time; a new one replaces it.
  - `Talk.begin(context, chat, title)`, called by `TalkActivity` right before it sends (A: `/buddy/send`; B: the
    `/buddy/talk` stream). The activity is on screen then, so starting a foreground service is allowed on every
    Android version. The service goes foreground at once with a silent ongoing notification ("Waiting for Buddy ·
    <chat>", tap → `TalkActivity` for that chat), registers the MessageClient listener for `/buddy`, and starts
    `TextToSpeech`.
  - Each `reply` is added to the lines and spoken there and then, once (`QUEUE_ADD`, `USAGE_ASSISTANT`); `error`
    replies too, since the user may not be looking. The activity only draws lines, so showing them again never
    repeats speech.
  - Deadlines, one Handler: no `heard`/`error` 10 s after sending → "Buddy on your phone didn't answer." After
    `heard`, whenever 30 s pass with nothing from the phone, it sends `/buddy/status {chat}`: `running: true` → keep
    waiting; `running: false` and no `done` → "Buddy on your phone stopped working on this, but the answer didn't reach
    the watch. It's in the chat on your phone."; no answer in 10 s, or the send fails → "Lost contact with your phone.
    Your message got there; Buddy's answer will be in the chat on your phone." It never re-sends the message: the
    phone accepted it, and sending again would run it twice.
  - It ends (listener removed, TTS shut down once its queue is spoken, foreground and notification dropped, the lines
    kept for the activity) on: `done` ("Stopped on your phone" for `stopped`); `/buddy/error`; either status-check
    failure above; 15 minutes after `begin`, the phone's wake-lock cap ("Still working on your phone after 15
    minutes. The answer will be in the chat there."); or the user swiping `TalkActivity` away (`isFinishing()` in
    `onDestroy`). The wrist going down, the watch face coming back, or Android destroying the activity don't end it.
  - Manifest: the service with `android:foregroundServiceType="remoteMessaging"` (Android 14+ requires a type; this
    one is for passing a user's messages between their devices and needs no runtime permission), plus
    `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_REMOTE_MESSAGING`. No `POST_NOTIFICATIONS` request: the service runs
    without it, Wear OS 4+ only hides the notification.
  - TTS init fails → replies are shown only, with one line "This watch can't read aloud (no voice installed)".
- `TalkActivity`, a view of `Talk`: on a fresh open it starts listening at once (the "tap to speak" is the tap on the
  chat), unless `Talk` has something for this chat the user hasn't seen (still waiting, or `unseen` lines), or Android
  is recreating it (`savedInstanceState != null`); then it just shows the conversation. A round accent mic button
  (`ms_mic`) asks follow-ups in the same chat; tapping it also stops speech. It draws `Talk`'s lines on `onStart` and
  follows changes until `onStop`: "Sending…" until `heard` ("You: …"); then the thinking face and `step` lines; each
  reply appended (`error` replies in the error colour); the end restores the face. `FLAG_KEEP_SCREEN_ON` while `Talk`
  waits, but at most 120 s with nothing from the phone (each step/reply resets the clock). After that the screen may
  sleep and the status reads "Still working on your phone. I'll read the answer out when it comes.", which holds,
  since `Talk` keeps waiting and speaking.

### The speech piece: swappable, **pending the Watch4 probe**
Everything after "the words reached the phone" is shared, because the phone answers `heard` in both cases. Only
`TalkActivity.listen()` and what it calls differ. The Coder builds **one** of these, whichever the probe allows:
- **A, the watch recognizer (preferred).** Use it if the probe gets text back from `ACTION_RECOGNIZE_SPEECH` on the
  Watch4. `startActivityForResult` with `LANGUAGE_MODEL_FREE_FORM` and the prompt "Ask Buddy"; RESULT_OK →
  `Talk.begin(…)`, then `Phone.send("/buddy/send", {chat, text})`; cancel → stay with the mic button;
  `ActivityNotFoundException` → "This watch has no speech recognizer." The system's voice UI holds the microphone, so
  the watch app needs **no `RECORD_AUDIO`**. About 25 lines.
- **B, the phone transcribes.** Use it if A fails (no handler, or `isRecognitionAvailable` false *and* the intent
  returns nothing). The watch records with `AudioRecord` (16 kHz mono PCM16, `RECORD_AUDIO` asked at runtime on the
  watch). It stops on a tap or on end of speech (a copy of `Voice.EndOfSpeech`, `Voice.java:39-60`, about 25 lines)
  and, after `Talk.begin(…)`, streams over `ChannelClient.openChannel(node, "/buddy/talk")`: a header line, then the
  PCM. Recording happens while the activity is on screen, so no microphone foreground service is needed. On the
  phone, the manifest adds `CHANNEL_EVENT` for `/buddy`, and `WatchLink.onChannelOpened` reads the stream into a new
  `MicServer` feed. `MicServer.useFeed()` makes the next `startCapture` take pushed chunks instead of opening the
  phone's mic (`MicServer.java:106-150`); the PCM format is already the same. Then `Voice.listen` runs with the
  user's chosen engine (Claude or ChatGPT dictation read `MicServer`), and `onFinal(text)` → `deliver(...)`. With the
  phone's Android recognizer as the engine: "From the watch, pick Claude's or ChatGPT's dictation in Buddy's
  Settings → Voice." About 150 lines across both sides. It also lays the ChannelClient groundwork for phase 2.
  **B has one unknown of its own:** whether those dictation engines work with the phone locked (ChatGPT's uses a
  WebView). If the probe forces B, the orchestrator checks that on the phone first (or the Android recognizer's
  `EXTRA_AUDIO_SOURCE`, API 33+, instead), and the Planner pins B down before Step 7.
- **TTS on the watch** is also in the probe. If the Watch4 has no TTS engine, that is a new open question (the phone
  would synthesize with `synthesizeToFile` and stream the audio over ChannelClient); it is not designed here.

### Alternatives considered (one line each)
- Watch → phone over Wi-Fi/HTTP to Buddy's MCP server: no Play services, but the server is loopback-only, the watch
  is on Bluetooth most of the time, and the brief chose the Data Layer.
- Standalone watch app calling a model API (WristAssist style): it wouldn't be Buddy's chats or agents.
- Kotlin/Compose for Wear, androidx.wear widgets, ambient mode: new toolchain and dependencies for 3 small screens.
- `DataClient` (synced state) for the chat list: needs change tracking; request/reply is simpler and always fresh.
- `MessageClient.sendRequest` RPC: one more API; replies are a stream of several messages anyway.
- Phone link code in `app/src/github/java`: breaks `FLAVOR=github ./build.sh` (javac has no Play services).
- Patching build.sh to skip the link files: an exclusion rule in a script, where a separate folder needs none.
- Sharing the icon/face drawables via a resource source dir pointing into `app/`: drags every phone resource
  (1378 strings × 19 languages) into the watch APK; copying 7 small vector files is simpler.
- Speaking each reply on the phone and routing audio to the watch: impossible, 001 showed the Watch4 is not an
  audio device for phone apps.
- Watch listener held by `TalkActivity` until `onDestroy`: dies with the screen or a reclaimed process.
- Watch `WearableListenerService` as the receiver: survives process death, but its process is cached again as soon
  as delivery returns, so speech can still be cut; the foreground `Talk` keeps the process for the whole wait.
- A Session flag marking "note continuation pending": the posted end check gets the same answer with no main-code state.
- A "More" page for the chat list: every eligible chat fits in one message.

## Steps
Each step is one commit on `feat/003-wear-companion`.

0. **Fork release key and CI secrets: orchestrator, only after Nicholas's OK** (not the Coder; no secrets in any
   file). Create a release keystore for the fork (`keytool -genkeypair … -keyalg RSA -keysize 4096 -validity 10000
   -alias buddy`, as `build.sh:58-67` does). Nicholas chooses where the backup lives, since losing it means the
   installed fork can only be replaced by uninstalling. Set `BUDDY_KEYSTORE_BASE64` and `BUDDY_KEYSTORE_PASSWORD` on
   `nlachica/buddy-android` with `gh secret set`. This also makes CI's reproducibility step pass on the fork
   (playbook learning "fork CI dormant"). Steps 1-8 don't wait for it; installing and verification do.
1. **Wear module skeleton and build switches.** `gradle.properties` (`android.useAndroidX=true`, with a comment on
   why the fdroid APK is unaffected); `settings.gradle.kts` (`include(":app", ":wear")`); `wear/build.gradle.kts`;
   `wear/src/main/AndroidManifest.xml`; a minimal `ChatsActivity` showing "Buddy" and the face; copies of
   `mipmap-anydpi/ic_launcher.xml`, `drawable/ic_launcher_{background,foreground,monochrome}.xml`, `bubble_face.xml`,
   `bubble_face_thinking.xml` and `ms_mic.xml` into `wear/src/main/res/`; `wear/src/main/res/values/strings.xml`;
   `.github/workflows/build.yml`: `:wear:assembleRelease` added to the Gradle line (`build.yml:59`), so CI builds
   the module from this commit on. Gate: CI builds it, and the fdroid APK equals main's (Test plan 5a).
2. **CI output.** `.github/workflows/build.yml`: collect the watch APK as `dist/buddy-watch-<ref>.apk`, plus
   `dist/buddy-watch.apk` on tags, next to the others. Add a "Which file?" sentence: `buddy-watch.apk` is for Wear OS
   watches and talks only to `buddy.apk` from the same release; install it with adb (README). Add a step after
   "Collect APK" that fails if the fdroid APK has Google code:
   `! unzip -p "$FDROID_APK" 'classes*.dex' | grep -aq 'Lcom/google/android/gms/'`.
3. **Phone main-code hooks (no Google types).** `Voice.java`: extract `static String spoken(Context, String)` from
   `speak` (behaviour unchanged). `Sessions.java`: runtime-only `boolean watch` on `Session`, with a why-comment.
   `ChatHub.java`: `&& !ses.watch` in `add`'s read-aloud condition and in `runEnded`'s notify condition; a comment at
   `buddy_done`'s note post (`ChatHub.java:719-722`) that it must stay before the `changed()` below (the watch link's
   end check relies on it). `res/values/strings.xml`: `watch_buddy_off` ("Buddy's accessibility service is off on
   your phone. Turn it on there, then try again.") and `watch_chat_gone` ("That chat isn't on your phone any more."),
   with translator comments (other languages fall back to English).
4. **Phone watch link.** `app/build.gradle.kts`: github source set gets `java.srcDir("src/watchlink/java")`,
   `res.srcDir("src/watchlink/res")`, `manifest.srcFile("src/watchlink/AndroidManifest.xml")`, plus the
   `githubImplementation` line. The comment says why it isn't `src/github` (build.sh). New
   `app/src/watchlink/AndroidManifest.xml`, `app/src/watchlink/res/values/wear.xml`, and
   `app/src/watchlink/java/app/buddy/assistant/WatchLink.java` (chats, deliver, status, relay with the posted end
   check, wake lock, as in Design; about 190 lines).
5. **Watch: chat list.** `wear/src/main/java/app/buddy/assistant/wear/Phone.java`, `Look.java`, the full
   `ChatsActivity.java`, and the strings they use.
6. **Watch: the conversation.** `wear/.../Talk.java` (state, listener, TTS, deadlines and status check, ongoing
   notification, endings, as in Design), its manifest entry and permissions, and its strings. Independent of the
   probe.
7. **Watch: talk screen. Wait for the probe result** (Open question 1). `TalkActivity.java`, its manifest entry and
   its strings, with **7A**, the watch recognizer; or **7B**: `TalkActivity` recording + `RECORD_AUDIO`, the phone's
   `WatchLink.onChannelOpened` + `CHANNEL_EVENT` filter, and the `MicServer.useFeed()` seam (`MicServer.java`).
   For 7B, split the phone part into its own commit.
8. **Docs.** `README.md`: a short "Galaxy Watch (Wear OS)" section. It covers what the watch app does (phase 1), that
   it needs `buddy.apk` (GitHub version) from the same release on the phone, and install with adb over Wi-Fi
   (watch: Developer options → ADB debugging + Wireless debugging; `adb pair <ip:port>` with the code from the watch,
   `adb connect <ip:port>`, `adb -s <watch> install buddy-watch.apk`; Wear OS 4+ needs pairing). Updating: install
   both APKs from the same fork release (phone: open `buddy.apk`; watch: `adb install -r`). Buddy's own updater only
   knows the official repository and its `buddy.apk` (`Updates.java:41-45,65-77`), which Android refuses over the
   fork, so turn off Settings → Update notices and don't use "update Buddy" on the fork. "For developers" gets
   "build.sh builds the phone app without the watch link". `docs/PRIVACY.md`: a "Watch" section. With the watch app
   (GitHub version only), chat titles, your words and Buddy's replies pass between phone and watch through Google
   Play services' Wear OS link (Bluetooth, or Google's servers, encrypted, when out of range). In path A, the watch's
   speech recognizer turns your voice into text. `CONTRIBUTING.md` project layout: rows for `wear/` and
   `app/src/watchlink/`.

### Switching the phone to the fork (checklist, not a commit; after Nicholas's OK, Open question 3)
Needed before done-when 2-4 can be verified. Buddy's Backup (`Backup.java:28-30`) keeps only `root/.buddy/sessions`,
`root/.buddy/work`, `root/.agents/skills`, `root/.claude/projects`, `root/.codex/sessions` and OpenCode's db from
Linux, and restores that part only if Linux is already set up (`Backup.java:88-89,111-116`). Uninstalling deletes the
rest. **This pipeline runs inside the official Buddy's Linux**, so the uninstall ends the session: everything below up
to step 5 is done (and checked) before it, and Nicholas does steps 6-11 himself. Paths and commands may go in the
feature docs; file contents and credentials never do.
1. **Inventory** (orchestrator): compare `ls -a /root /root/.claude /root/.codex /root/.config /root/.local` with the
   Backup's list and note the setup it misses. Known so far: `/root/.claude/settings.json`, `/root/.codex/config.toml`,
   `/root/.local/bin`; check also `~/.claude/CLAUDE.md`, `~/.claude/skills`, `~/.claude/agents`, `~/.gitconfig`,
   crontabs, `/etc/apk/world` and `/etc/apk/repositories` (the installed Alpine packages). Leave out sign-ins
   (`~/.claude/.credentials.json`, `~/.codex/auth.json`, `~/.config/gh/hosts.yml`, keys): they're redone after the
   switch. Write the path list, the chat count and the list of sign-ins to redo (names only) into
   `docs/features/003-wear-companion/migration.md`, and commit it.
2. **Push everything**: every git repo under `/root/.buddy/work` committed and pushed (`git status`,
   `git log @{u}..` empty), this branch included.
3. **Extras archive**: `tar czf /root/.buddy/work/migration-extras.tgz <the paths from 1>`. It sits inside
   `root/.buddy/work`, which the Backup carries; its name avoids the Backup's excludes (`build`, `node_modules`,
   `.gradle`, `.cxx`).
4. **Backup**: Buddy → Settings → Backup writes `Download/Buddy/buddy-backup-<date>.zip`, outside the app's storage, so
   the uninstall leaves it.
5. **Check it** (phone shell): the zip lists `linux.tar.gz`, and that archive lists
   `root/.buddy/work/migration-extras.tgz` and the repos. Only then ask Nicholas to go on.
6. Uninstall the official Buddy; install the fork's `buddy.apk` (the release matching the watch APK).
7. Run Buddy's setup **including Linux**, the accessibility service and the battery exemption.
8. Settings → Restore the backup (Buddy restarts).
9. In Buddy's Linux: `tar xzf /root/.buddy/work/migration-extras.tgz -C /`, then `apk add $(cat /etc/apk/world)` to
   bring back the packages.
10. Sign in again (agents, API keys), from the list in `migration.md`. Turn off Settings → Update notices
    (Docs, Step 8).
11. Check: the chat count matches `migration.md`; a repo in `/root/.buddy/work` shows a clean `git status`;
    `settings.json` and `config.toml` are back; a tool from `/root/.local/bin` runs; a typed message gets an answer.

## Test plan
| Done-when | Proof |
|---|---|
| 1 builds in CI, installs | CI run on the branch green with `:wear:assembleRelease` (from Step 1); artifact `buddy-apk` contains `buddy-watch-<ref>.apk` (from Step 2). After Step 0: `apksigner verify --print-certs` shows the same SHA-256 cert digest for `buddy-watch-*.apk` and `buddy-*.apk`. `adb -s <watch> install` → `Success`; the app opens on the Watch4 (screenshot via `adb -s <watch> exec-out screencap -p`). |
| 2 list, speak, runs | On the devices: the watch list matches the phone's chats (top-level, unarchived, pinned first), all of them, with more than 20 eligible chats on the phone (count equal). Speak "what time is it" into a chat → the phone chat shows it as the user's message and the run starts (screenshot of the phone chat). **Busy chat, note as a second run:** on the phone, ask a chat for a long answer with no phone actions (e.g. "write a 400-word story about a lighthouse"); while it writes, speak "what is two plus two" into that chat from the watch → shown as a note; the first run ends, the note runs as a second run; the watch stays "working" in between, speaks the story and then "four", and gets `done` only after the second run; the phone speaks neither. Repeat, and press Stop on the phone while the note waits → the watch shows the "note not sent" line and "Stopped on your phone". |
| 3 reply shown + spoken | The reply text appears on the watch and is heard from the watch speaker, once: reopening the talk screen shows it without speaking it again. With Settings → read replies aloud **on**, the phone stays silent and posts no "finished" notification. A multi-part reply arrives part by part; status lines show during tool use. |
| 4 locked phone, clear errors | Phone locked, screen off for 5+ min, in a pocket → a watch question still gets its answer. Watch Bluetooth and Wi-Fi off → "Can't reach your phone…". Official Buddy / no fork on the phone → "Buddy's watch link isn't on your phone…" (can be checked **today**, before any switch). Accessibility service off → the phone's `watch_buddy_off` text. **Slow answer:** a question that takes over 2 minutes; the screen is released after 120 s and the wrist lowered until the watch face is back → the answer is still spoken, and reopening shows it without repeating it. Same with the watch's Developer options → "Don't keep activities" on (the activity is destroyed on leaving), and with `adb -s <watch> shell am kill app.buddy.assistant` while waiting (it kills only cached processes: the foreground `Talk` keeps the process, and `dumpsys activity services app.buddy.assistant` shows it foreground). **After `heard`:** the phone in airplane mode (or the watch's Bluetooth and Wi-Fi off) → within about 40 s "Lost contact with your phone…" and the working state ends; `adb shell am force-stop app.buddy.assistant` on the phone → the watch leaves the working state within about 40 s, with the "stopped working" or "lost contact" text; in both, the phone chat holds the message once (no re-send). |
| 5 F-Droid clean, GitHub has link | (a) Step 1 commit: unsigned `assembleFdroidRelease` APK from the branch equals main's, entry by entry (fork CI without secrets gives unsigned APKs to compare directly; else `apksigcopier compare --unsigned`). (b) Every later commit: the new CI step finds no `Lcom/google/android/gms/` in the fdroid dex; `aapt2 dump xmltree --file AndroidManifest.xml` of the fdroid APK has no `WatchLink`/gms entries, while the github APK has both. (c) CI "F-Droid APK is reproducible" green (once secrets exist; before that it fails on the fork for a known reason). (d) `FLAVOR=github bash build.sh` (or, without native libs, its step-3 javac line) compiles on the phone with Buddy's javac/aapt2/d8. |
| 6 looks like Buddy, style, YAGNI | Watch screenshots: black background, Buddy face and icon, the phone's accent colour. Code review against the repo style: single-file package-private classes, why-comments, strings in resources, no new dependencies beyond Play services in github/wear. The diff holds only the files in Steps 1-8 (plus `migration.md`). |

Optional, before switching the phone: a throwaway link probe pair (same package on phone and watch, signed with a
scratch key, echoing `/buddy/send`) checks locked-phone delivery and the wake lock without touching the official Buddy
(the probe-app approach from the 001 learning). The orchestrator decides.

## Risks
1. **The phone must run the fork to use this.** The official Buddy (upstream key) can't install over a fork-signed
   one, and uninstalling it deletes its Linux and chats. Buddy's Backup misses setup outside its list and restores
   Linux only once Linux is set up; the checklist ("Switching the phone") covers both, and sign-ins and API keys must
   be redone (`Backup.java:20-24`). The uninstall also ends this pipeline's own session, so everything is pushed
   first and Nicholas runs the switch. Nicholas decides (Open question 3); until then only the "not on your phone"
   path can be verified.
2. **Speech on the Watch4** may need path B, which is about 6× the code of A and adds a phone-side unknown (Design).
3. **`android.useAndroidX=true` is global.** Proven harmless for fdroid by Test 5a. If AGP 9.4.1 also wants
   something else (Jetifier, a lint rule for the Wear manifest), the first CI run shows it and the Coder fixes it in
   Step 1.
4. **No persistence in MessageClient.** A reply is lost on the watch (never in the phone chat) only when the watch is
   out of range (then the status check says so), after `Talk` has ended (swiped away, 15 minutes), or if Android kills
   the foreground service under extreme memory pressure.
5. **Phone tools that need the screen**, while the phone is locked, behave as for any locked-phone run today (they may
   ask to unlock on the phone). An approval the agent waits for shows as a status line on the watch and must be given
   on the phone (approvals on the watch are a non-goal).
6. **Version drift.** A watch app and phone app from different releases may disagree. Unversioned on purpose: one
   CI run builds both and the release note says to install them together.
7. **F-Droid upstream.** The fork isn't on F-Droid. If this were ever upstreamed, F-Droid's scanner would flag
   `com.google.android.gms` in `wear/` and `app/build.gradle.kts`; the fdroiddata recipe would need `scandelete`
   and a `prebuild` line. Out of scope.
8. **Battery on the watch:** at most 120 s of forced screen-on per question; the foreground `Talk` for at most 15
   minutes, with one small status message per 30 s of silence; TTS and the listener cost little.
9. **Install friction:** watch adb needs Wi-Fi on the watch (same network, no client isolation) and a pairing code
   read off the watch, which only Nicholas can do (research, constraint 15).
10. **The `remoteMessaging` service type on Wear OS** is untested here. If the Watch4 refuses it, the first device
    run shows it; `mediaPlayback` (it does play the answer) with its permission is the fallback.
11. **Buddy's updater on the fork** announces official releases it can't install (different key). Mitigation: Update
    notices off and manual updates of the matching pair (Step 8, checklist step 10); a fork-aware updater is a
    separate feature.

## Open questions
These are not research questions; the plan can go to review now. Q1 gates Step 7 only, Q2 and Q3 gate
installing/verifying only.
1. **Watch4 probe result (orchestrator, running now):** does `ACTION_RECOGNIZE_SPEECH` return text, and does
   `TextToSpeech` initialise with an engine? A → Step 7A. Otherwise → 7B, after the phone-locked check of the
   dictation engines named under path B. No TTS → back to the Planner.
2. **Nicholas: OK to create the fork's release key and store it as the fork's GitHub secrets (Step 0)? And where
   should the backup of the key live?** (Recommended: yes; backup in his password manager.)
3. **Nicholas: OK to switch the phone from the official Buddy to the fork's GitHub build**, following the checklist
   ("Switching the phone": inventory, push, extras archive, Backup and check, then he uninstalls, installs, sets up
   Linux, restores and signs in again), when the build is ready? Without it the watch app can't talk to Buddy at all.
