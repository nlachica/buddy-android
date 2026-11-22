# Plan: 033-watch-worn-audio

## Goal
Auto's audio order becomes: a connected headset, then the Galaxy Watch **only while it is worn**, then the phone
speaker, the earpiece only when nothing else exists. The watch app reads its off-body sensor and tells the phone over
the Data Layer; the phone keeps the answer with a timestamp, and unknown, stale or "no such sensor" counts as not worn.
Read aloud, a phone live call and the "Auto is using ... now" note all skip a watch that is off the wrist; a
hand-picked Watch is unchanged. Done when: the brief's six items (watch reports, phone state + staleness test, order
tests, both routes skip, the note shows it, CI green).

How done-when 1 is read here: "on change, and at start / reconnect, so the phone isn't left with a stale state" is met
by the phone **asking the watch each time Auto is about to use it** (the orchestrator's run instruction allows this
form). The phone therefore never acts on an old state, including after a phone restart or a reconnect. The watch does
not push changes on its own. (Orchestrator: worth one line in the brief's Clarifications so the Verifier reads it the
same way.)

Non-goals: new settings or a priority UI; any change to explicit device picks; changes to `AudioRoute.pick`,
`ReadQueue`, `LiveWatch.Call`, the audio streams or the earpiece rules; a permanent watch service; a sensor permission
prompt on the watch; F-Droid (no watch there).

## Design

### Where the gate goes: the existing "does a watch answer" step
Auto already decides the watch in two steps. `pick()` says "the watch is next" (today that only means this build has
the link), then every route calls the async `Link.find` to see whether a watch really answers, and falls to the phone
when none does: `ReadQueue.start` (`find` null on a quiet reading: phone, no notice), `LiveWatch.Call.attempt/found`
("none found: phone"), `AudioRoute.autoNow` (label). The worn check is the same kind of fact (ask the watch, wait), so
it joins that step: **for Auto, "a watch answers" becomes "a watch answers and says it is worn"**.

Result: `pick()`, `livePick()`, `target()`, `liveTarget()`, `ReadQueue` and `LiveWatch.Call` are not touched, so every
016/017/018/026/030/031 test stays as it is, and the fallback paths (speaker, never the earpiece) are the ones already
tested.

Why not through `pick()`'s `watch` boolean, as the run instruction suggested: that boolean is read synchronously
*before* anything is asked. With an on-demand query the phone's state is stale at that moment almost every time, so a
strict "stale = not worn" there would send the first reading to the speaker even on the wrist, and nothing would ever
trigger the ask. The instruction's purpose (pick's signature and tests stay) is met more fully: pick is not edited.

### Phone, Google-free (`src/main`)
- New `Worn.java` (about 40 lines, main thread, clock injected for tests). It holds the last answer:
  `{node, Boolean worn, at}` and:
  - `on(node)`: the rule. True only when this node said "worn" at most `FRESH_MS` = 30 s ago. Never told, another
    node, "not worn", unknown (`null`) or older: false.
  - `find(link, c, out)`: `link.find`; no node: `out(null)`. A node with an answer (of any kind, also "no answer")
    younger than 30 s: `out(on(node) ? node : null)` without asking. Else `link.worn(c, node, w -> { store; out(...) })`.
- `WatchVoice.Link` gets `default void worn(Context c, String node, Consumer<Boolean> worn)`: true, false, or null
  when the watch doesn't say (no sensor, no reply in time). The default answers null.
- `WatchVoice.find(c, node)`: the one place that knows the difference: `KEY.equals(Prefs.audioDevice(c))` (Watch is
  the pick): plain `link().find`, as today. Anything else (Auto, or a pick that is away): `Worn.find`.
  `WatchVoice.Phone.find` and `LiveWatch.Phone.find` call it; `AudioRoute.autoNow` calls the worn form directly (it
  describes Auto). `SetupActivity`'s check for a picked Watch keeps the plain `find`.

30 s: long enough that a burst of readings, the 5 s retries of a call and the settings note's 5 s poll share one ask,
and that a watch app that never answers costs its timeout once, not per reading; short enough that a watch taken off
is noticed by the next reply.

### Phone, github build (`src/watchlink`)
- `WatchVoiceLink.worn`: sends `/buddy/worn` `{}` to the node, keeps the waiter (node + answer-once), answers null
  after `ASK_MS` = 4 s or when the send fails. `wornReply(node, json)` answers that node's waiters:
  `json.has("worn") ? json.getBoolean : null`.
- `WatchLink.onMessageReceived`: one more `case "/buddy/worn"` calling `WatchVoiceLink.wornReply(node, in)` (the
  manifest filter `/buddy` already covers it; a reply also arrives when Buddy is in the background).

### Watch (`wear/`)
- Owner of the sensor: **nobody, permanently**. `ReadAloud` (already the watch end of the Watch audio device and a
  `WearableListenerService`) gets a message filter for the exact path `/buddy/worn` and an `onMessageReceived` that
  hands over to a new `Worn.java`: take a 5 s partial wake lock, take the first sensor of
  `getSensorList(TYPE_LOW_LATENCY_OFFBODY_DETECT)`, register, wait on the service's callback thread up to 2 s for the
  first event (an on-change sensor delivers its current value on registration), unregister, reply `/buddy/worn` to the
  sender: `{"worn": true|false}` (`values[0] >= 0.5`), or `{}` when there is no sensor, registration returns false or
  throws `SecurityException`, or no event came.
- No new permission, no new service, no notification, nothing running between asks.

Message table addition (the 003 table's format):

| Path | Dir | Body | Meaning |
|---|---|---|---|
| `/buddy/worn` | P→W | `{}` | are you on the wrist? |
| `/buddy/worn` | W→P | `{worn?: bool}` | the off-body sensor now; no `worn` = can't tell |

### Mid-call
A phone live call checks worn when it places the voice: at the start, at a setting change and at each of 026's
retries (all go through `find`). Taking the watch off mid-call leaves the voice on it; putting it on mid-call does not
pull the voice over, except through a retry already pending. Same as a headset that connects mid-call today.

### Wording
`setup_audio_device_info_watch`, the `audio_device` setting's description and `SettingsTool.audioNow`'s watch line
("auto → watch while you wear it, or <android>"), `AudioRoute`'s and `WatchVoice`'s class comments, README's two Auto
paragraphs (and: Auto's watch step needs the watch app from this release; an older one never answers, so Auto uses the
phone).

### Alternatives considered
- A permanent foreground service on the watch pushing changes: an always-on notification, a boot receiver, a
  foreground-service type that itself wants a sensor permission on Android 14+, and a heartbeat or disconnect tracking
  on the phone to know a silent watch from a worn one. Much more code and battery for the same answer.
- Strict gate in `pick()`'s boolean plus a refresh before each decision: needs a wait in front of `ReadQueue.start`
  and `LiveWatch.Call.decide`, both tested state machines.
- Both gates (boolean skips a watch known to be off, `find` decides the rest): two mechanisms for one rule; the
  boolean would only save a capability lookup.
- Health Services: no on-body API (research).

## Steps
1. **Phone state and rule.** `app/src/main/java/app/buddy/assistant/Worn.java` (new), `WatchVoice.java` (`Link.worn`
   default, `find`, the `Worn` instance on `SystemClock.elapsedRealtime`). Test `app/src/test/.../WornTest.java` with a
   fake `Link` and a fake clock.
2. **Phone link.** `app/src/watchlink/.../WatchVoiceLink.java` (`worn`, waiters, `wornReply`), `WatchLink.java` (the
   case, the path constant, a pointer to this plan in the class comment). Test
   `app/src/testGithub/.../WatchVoiceLinkTest.java` (new): waiter and reply, split so the test needs no Google client.
3. **Watch.** `wear/.../Worn.java` (new), `Phone.java` (`WORN`), `ReadAloud.java` (`onMessageReceived`),
   `wear/src/main/AndroidManifest.xml` (the `MESSAGE_RECEIVED` filter, `android:path="/buddy/worn"`). Test
   `wear/src/test/.../WornTest.java`: event value to reply body, none to `{}`.
4. **Auto uses it.** `WatchVoice.java` (`Phone.find`), `LiveWatch.java` (`Phone.find`), `AudioRoute.java` (`autoNow`,
   class comment), `SettingsTool.java` (two texts), `res/values/strings.xml` (`setup_audio_device_info_watch`).
5. **Docs.** `README.md` (the Live-voice-on-the-watch and Audio-device-Auto paragraphs), `decisions.md`.

## Test plan
| Done-when | Proof |
|---|---|
| Watch reads the sensor and reports; no sensor = unknown | `wear` `WornTest` (mapping, `{}`); code review of `Worn.java`; on the watch: the note check below, and `adb shell dumpsys sensorservice` shows the off-body sensor |
| Phone keeps state + timestamp; unknown or stale = not worn | `WornTest`: worn then 30 s + 1 ms later `on` is false and `find` asks again; `null` and `false` give null; another node's answer doesn't count; a second `find` inside 30 s doesn't ask |
| Auto order | existing `AudioRouteTest.autoOrder`, `autoOrderPutsTheHeadsetBeforeTheWatchAndNeverTheEarpiece`, `liveDevice` (headset first, earpiece never while a speaker exists) unchanged; `WornTest`: reachable + worn gives the node, reachable + not worn / unknown / stale gives null; existing `ReadQueueTest.quietNoWatchReadsOnThePhoneWithNoNotice` and `LiveWatchTest.noWatchFoundStaysOnThePhone`, `anAutoCallWithNoWatchUsesTheSpeaker` prove null means the phone speaker |
| Both routes skip; a picked Watch unchanged; 016/018/030/031 tests pass | `./gradlew testGithubDebugUnitTest` (no existing test edited); review that `WatchVoice.find` is the only caller choice. Manual on the phone + watch: Auto, watch on the wrist: a reply reads on the watch and a call's voice plays there; watch on the table for 30 s: both on the phone speaker; Watch picked, watch on the table: still on the watch |
| "Auto is using ..." reflects it | Manual: Settings → Voice with Auto, take the watch off: the note changes from Watch to Speaker within about 35 s, and back when worn |
| CI green, diff limited | `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease :wear:testDebugUnitTest`; `git diff main --stat` shows only the files above |

**Release gate (manual, before any tag):** with the new watch app installed and the watch worn, the note must say
Watch. If it says Speaker, the watch is answering "can't tell" (see Risks 1 and 2): do not release; go to Q1.

## Risks
1. **The sensor may be hidden without a permission.** Android's pages list none for this sensor; a Samsung post says
   `BODY_SENSORS`. If Samsung requires it, the sensor list is empty, the watch answers `{}` and Auto never uses the
   watch (safe, but worse than today). Not checkable from here (no watch on adb during planning). The release gate
   catches it; Q1 is the way out.
2. **Sensor events to a background app.** Android 9+ withholds sensor events from idle apps. The read runs inside a
   Play-services-bound service callback, which should count as active, and holds a wake lock; unverified on the watch.
   Same symptom and gate as 1. Way out if it fails: do the 2 s read in a short foreground service.
3. **Watch app must be updated with the phone.** An older watch app ignores `/buddy/worn`, so Auto skips the watch
   (4 s wait once per 30 s, then the phone). README says so; a picked Watch keeps working.
4. **Up to 30 s of trust.** A watch taken off less than 30 s before a reply still gets that reply.
5. **Added delay.** The first reading after 30 s waits for one round trip to the watch (typically well under a
   second; 4 s at worst) before it starts.

## Open questions
None.

## Questions for Nicholas
1. If your Galaxy Watch only shows the "is it worn" sensor to apps that hold the body-sensors permission, should the
   watch app ask you for that permission?
   - **Ship without it, check on the watch first (recommended):** nothing to approve on the watch if it isn't needed,
     and Android's own pages say it isn't. If the check fails, Auto simply uses the phone until a small follow-up adds
     the prompt.
   - Add the permission and a one-time prompt on the watch now: works either way, but asks you for health-sensor
     access that may be unnecessary, and the permission's name changes on the newest Wear OS, so it is more code.
