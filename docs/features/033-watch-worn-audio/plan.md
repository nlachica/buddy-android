# Plan: 033-watch-worn-audio

## Goal
Auto's audio order becomes: a connected headset, then the Galaxy Watch **only while it is worn**, then the phone
speaker. The watch app reads its off-body sensor and tells the phone over the Data Layer; the phone keeps the answer
with a timestamp, and unknown, stale or "no such sensor" counts as not worn. Read aloud, a phone live call and the
"Auto is using ... now" note all skip a watch that is off the wrist; a hand-picked Watch is unchanged. Done when: the
brief's six items (watch reports, phone state + staleness test, order tests, both routes skip, the note shows it, CI
green).

Two readings of the brief this plan relies on. Orchestrator: please append them to the brief's Clarifications so the
Verifier reads it the same way.
- **Done-when 1** ("on change, and at start / reconnect") is met on demand: *the phone asks the watch whenever Auto is
  about to use it and after each utterance of a call; the watch does not push changes.* Allowed by the run
  instruction. The phone never acts on an answer older than 3 s, also after a restart or a reconnect.
- **Done-when 3's earpiece clause** means: *Auto never picks the earpiece while a speaker route exists; a Live call
  with no speaker at all ends, as 030 decided.* The brief also says 030 stays and the 030/031 tests still pass, and
  031's test pins that case. Pending Q2.

Non-goals: new settings or a priority UI; any change to explicit device picks; changes to `AudioRoute.pick`,
`liveDevice`, `ReadQueue`, the audio streams or the earpiece rules; a permanent watch service; a sensor permission
prompt on the watch (unless the probe shows it is needed, Q1); F-Droid (no watch there).

## Design

### Where the gate goes: the existing "does a watch answer" step
Auto already decides the watch in two steps. `pick()` says "the watch is next" (today: this build has the link), then
every route calls the async `Link.find` to see whether a watch really answers and falls to the phone when none does:
`ReadQueue.start`, `LiveWatch.Call.attempt/found` ("none found: phone"), `AudioRoute.autoNow` (label). Worn is the
same kind of fact (ask the watch, wait), so **for Auto, "a watch answers" becomes "a watch answers and says it is
worn"**. `pick()`, `livePick()`, `target()`, `liveTarget()` and `ReadQueue` are not touched, so their tests stay, and
the fallback (speaker, never the earpiece) is the path already tested.

Not through `pick()`'s `watch` boolean, as the run instruction words it: that boolean is read synchronously before
anything can be asked, so with an on-demand answer it would be stale at the first reading and send it to the speaker
on the wrist. The instruction's purpose (pick's signature and tests stay) is kept: pick is not edited.

### Phone, Google-free (`src/main`)
- New `Worn.java` (about 60 lines, main thread). All the logic is here so it is tested in `src/test`:
  - State: the last answer `{node, Boolean worn, at}` (`at`: the phone's clock when it arrived; `null` = can't tell).
  - `on(node)`: the rule. True only when this node said "worn" at most `FRESH_MS` = 3 s ago.
  - `check(node, out)`: an answer of any kind younger than 3 s: `out(on(node))` at once. An ask in flight for this
    node: join it. Else ask: a new id, `port.send(node, id, failed)`, and "can't tell" after `ASK_MS` = 8 s or when
    the send fails.
  - `reply(node, id, worn)`: accepted only for the pending ask's node and id (a late reply to an older ask is
    dropped); stores the answer, answers all its callers.
  - `find(finder, out)`: Auto's "a watch answers": the finder's node, then `check`; no node or not worn gives null.
  - `Port`: `send`, `after`, `now` (the fake in the test).
- `WatchVoice.Link` gets `default void askWorn(Context c, String node, long id, Runnable failed)` (default: failed).
- `WatchVoice` holds the `Worn` and is the one place that knows a pick from Auto:
  - `find(c, out)`: Watch is the pick (`KEY.equals(Prefs.audioDevice(c))`): plain `link().find`, as today. Anything
    else (Auto, or a pick that is away): `Worn.find` over `link().find`.
  - `worn(c, node, out)`: Watch is the pick: true. Else `Worn.check`.
  - `wornReply(node, id, worn)` for the link; logs a "can't tell" reason (logcat tag `Worn`).
  `WatchVoice.Phone.find` and `LiveWatch.Phone.find` call `find`; `AudioRoute.autoNow` calls the Auto form (it
  describes Auto). `SetupActivity`'s check for a picked Watch keeps the plain `find`.

3 s, not longer: Android's bound for the sensor to notice a removal is 3 s, so a 3 s old answer is as current as a new
sample could be, and it lets a reading, a call's utterance check and the settings note share one ask. A second watch
is not handled (a caller for another node during an ask gets "not worn").

### Phone, github build (`src/watchlink`)
- `WatchVoiceLink.askWorn`: `sendMessage(node, "/buddy/worn", {"id": id})`, `failed` on failure. Nothing else.
- `WatchLink.onMessageReceived`: one more `case "/buddy/worn"`:
  `WatchVoice.wornReply(node, in.optLong("id"), in.has("worn") ? in.optBoolean("worn") : null)` (the manifest filter
  `/buddy` already covers it).

### Watch (`wear/`)
- Owner of the sensor: **nobody, permanently**. `ReadAloud` (already the watch end of the Watch audio device and a
  `WearableListenerService`) gets a message filter for the exact path `/buddy/worn` and an `onMessageReceived` that
  hands over to a new `Worn.java`: take an 8 s partial wake lock, take the first sensor of
  `getSensorList(TYPE_LOW_LATENCY_OFFBODY_DETECT)`, register (listener on the main looper), wait on the service's
  callback thread up to 5 s for the first event (Android's bound for the initial event), unregister, reply to the
  sender on `/buddy/worn`: `{id, "worn": true|false}` (`values[0] >= 0.5`), or `{id, "why": ...}` when it can't tell:
  `no sensor`, `refused` (registration false or `SecurityException`), `no event`.
- No new permission, no new service, no notification, nothing running between asks.

Message table addition (the 003 table's format):

| Path | Dir | Body | Meaning |
|---|---|---|---|
| `/buddy/worn` | P→W | `{id}` | are you on the wrist? |
| `/buddy/worn` | W→P | `{id, worn?: bool, why?: string}` | the off-body sensor now; no `worn` = can't tell |

### A live call (`LiveWatch`)
Placement (start, setting change, 026's retries) goes through `find`, so it checks worn. Two more checks, because a
call's later utterances open their readings without a `find`:
- `LiveWatch.Out` gets `default void worn(String node, Consumer<Boolean> worn)` (default: true, so the tests' fake and
  every existing test stay). `Phone.worn` calls `WatchVoice.worn`.
- `Call` asks it (a) in `decide()` when the watch is still wanted and a turn is under way (a setting change, e.g.
  Watch to Auto), and (b) in `answered()` right after `openNext` for a played utterance. "Not worn" for the turn that
  asked runs the existing `stop()`: the voice is back on the phone, an utterance under way finishes on the watch and
  the microphone waits for it. The `decide()` that follows finds no worn watch: "none found: phone".
- Rule, decided simply: once off the wrist, the call stays on the phone (speaker) until it ends or the setting
  changes; putting the watch on mid-call is not followed. The start of the utterance after a removal can still sound
  on the watch for the length of one round trip.

### Settings note (`SetupActivity.autoAsk`)
An answer can now take up to 11 s (find 3 s + ask 8 s), longer than the 5 s tick, which today drops every answer but
the newest ask's. Change: the tick starts no new ask while one is in flight, and the answer goes to the note on screen.

### Wording
`setup_audio_device_info_watch`, the `audio_device` setting's description and `SettingsTool.audioNow`'s watch line
("auto → watch while you wear it, or <android>"), `AudioRoute`'s, `WatchVoice`'s and `LiveWatch`'s class comments,
README's two Auto paragraphs (and: Auto's watch step needs the watch app from this release; an older one never
answers, so Auto uses the phone).

### Alternatives considered
- A permanent foreground service on the watch pushing changes: an always-on notification, a boot receiver, a
  foreground-service type that itself wants a sensor permission on Android 14+, and a heartbeat or disconnect tracking
  on the phone to know a silent watch from a worn one. Much more code and battery for the same answer.
- Strict gate in `pick()`'s boolean plus a refresh before each decision: needs a wait in front of `ReadQueue.start`
  and `LiveWatch.Call.decide`, both tested state machines.
- No reuse at all (ask at every placement): the same result within the sensor's own 3 s lag, more messages, and the
  brief's "state with a timestamp, stale = not worn" would have no reader.
- The watch refuses an Auto reading itself when off the wrist: no query, but the brief wants the state on the phone,
  the settings note needs a query anyway, and a refused reading has already been voiced once.
- Health Services: no on-body API (research).

## Steps
0. **Probe (no code).** If the watch is on adb (installing the watch app needs that anyway):
   `adb shell dumpsys sensorservice | grep -i -B1 -A3 offbody`. Record the sensor's line (exists, `perm:`, wake-up) in
   `decisions.md`. `perm: n/a`: carry on. A permission: stop, back to the Planner with Q1's answer. No watch on adb:
   carry on; the probe is then the Verifier's first check.
1. **Phone state and rule.** `app/src/main/java/app/buddy/assistant/Worn.java` (new), `WatchVoice.java`
   (`Link.askWorn` default, the `Worn` and its port, `find`, `worn`, `wornReply`). Test
   `app/src/test/.../WornTest.java` with a fake port.
2. **Phone link.** `app/src/watchlink/.../WatchVoiceLink.java` (`askWorn`), `WatchLink.java` (the case, the path
   constant, a pointer to this plan in the class comment).
3. **Watch.** `wear/.../Worn.java` (new), `Phone.java` (`WORN`), `ReadAloud.java` (`onMessageReceived`),
   `wear/src/main/AndroidManifest.xml` (the `MESSAGE_RECEIVED` filter, `android:path="/buddy/worn"`). Test
   `wear/src/test/.../WornTest.java`: event value to reply body, each "can't tell" to its `why`, the id echoed.
4. **Auto uses it.** `WatchVoice.java` (`Phone.find`), `LiveWatch.java` (`Out.worn`, `Phone.find`, `Phone.worn`, the
   two checks in `Call`), `AudioRoute.java` (`autoNow`, class comment), `SetupActivity.java` (`autoAsk`),
   `SettingsTool.java` (two texts), `res/values/strings.xml` (`setup_audio_device_info_watch`). Tests: two new ones in
   `LiveWatchTest.java`.
5. **Docs.** `README.md` (the Live-voice-on-the-watch and Audio-device-Auto paragraphs), `decisions.md`.

## Test plan
| Done-when | Proof |
|---|---|
| Watch reads the sensor and reports; no sensor = unknown | `wear` `WornTest`; code review of `wear/.../Worn.java`; on the watch: step 0's probe and the worn check below |
| Phone keeps state + timestamp; unknown or stale = not worn | `WornTest` (fake clock): worn, then 3 s + 1 ms later `on` is false, `check` asks again and the new "not worn" wins; `null` and `false` are not worn; another node's answer doesn't count; a second `check` inside 3 s sends nothing; two callers during one ask send one message and both get the answer; a reply at 8 s − 1 ms counts, none by 8 s is not worn; a failed send is not worn at once; ask A times out, ask B is sent, A's late "worn" is dropped and B's "not worn" decides |
| Auto order | Existing, unchanged: `AudioRouteTest.autoOrder` and `autoOrderPutsTheHeadsetBeforeTheWatchAndNeverTheEarpiece` (headset before the watch; speaker and earpiece both listed: the speaker; earpiece only: `NONE`, the call ends, see Goal and Q2). New in `WornTest` (`Worn.find` with a fake finder): reachable + worn gives the node; reachable + not worn / unknown / stale gives null. Existing `ReadQueueTest.quietNoWatchReadsOnThePhoneWithNoNotice`, `LiveWatchTest.noWatchFoundStaysOnThePhone`, `anAutoCallWithNoWatchUsesTheSpeaker` prove null means the phone speaker |
| Both routes skip; a picked Watch unchanged; 016/018/030/031 tests pass | `./gradlew testGithubDebugUnitTest`, no existing test edited. New `LiveWatchTest`: the watch taken off between two utterances (the next reading is cancelled, the voice is on the phone, nothing more is opened); Watch switched to Auto off the wrist mid-call (same). Manual, phone + watch: Auto, on the wrist: a reply reads on the watch and a call's voice plays there; watch taken off, 6 s later: both on the phone speaker; taken off mid-call: the next utterance moves to the speaker; Watch picked, watch on the table: still on the watch |
| "Auto is using ..." reflects it | Manual: Settings → Voice with Auto, take the watch off: the note changes from Watch to Speaker within about 15 s, and back when worn |
| CI green, diff limited | `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease :wear:testDebugUnitTest`; `git diff main --stat` shows only the files above |

**Sign-off gate (Verifier, before PASS; not a post-merge check):** with the new watch app installed, the watch worn
and its screen off, the note must say Watch and a reading must play on the watch. If it says Speaker, `logcat -s Worn`
on the phone gives the reason (`no sensor`, `refused`, `no event`, or no reply). The feature is then not done: back to
the Planner (Q1 for `no sensor` / `refused`, Risk 2's way out for `no event`). Nothing is released from a failed gate.

## Risks
1. **The sensor may be hidden without a permission.** Android's pages list none; a Samsung post says `BODY_SENSORS`.
   Not checkable during planning (no watch on adb). Step 0 and the sign-off gate catch it; Q1 is the way out.
2. **Sensor events to a background app.** Android 9+ withholds sensor events from idle apps. The read runs inside a
   Play-services-bound service callback under a wake lock, which should count as active; unverified. Same gate. Way
   out: do the read in a short foreground service.
3. **Watch app must be updated with the phone.** An older watch app never answers, so each Auto reading waits 8 s and
   then plays on the phone. README says so; a picked Watch keeps working.
4. **A lost reply moves a call off the watch.** "Can't tell" mid-call counts as not worn, and the call then stays on
   the phone. Safe direction; the reading channel would usually break in the same situation.
5. **Added delay.** Each Auto placement more than 3 s after the last answer waits one round trip to the watch
   (typically well under a second) before it starts.

## Open questions
None.

## Questions for Nicholas
1. If your watch only lets apps read its "is it worn" sensor after you allow body-sensor access, should the watch app
   ask you for that once?
   - **Check first, ask only if needed (recommended):** one command on the watch tells us. Not needed: nothing for
     you to approve. Needed: a one-time prompt on the watch is added before this is released, and until you allow it
     Auto uses the phone.
   - Add the prompt now regardless: works either way, but may ask for health-sensor access for nothing, and is more
     code (the permission has a new name on the newest Wear OS).
2. In a live call, if the phone has no loudspeaker available at all (practically never on your phone), what should
   happen?
   - **End the call with a notice, as today (recommended):** you asked several times that a call never plays in the
     earpiece (030). No code change.
   - Play in the earpiece as the true last resort: only when Android lists no speaker; a small change to 030's rule
     and its test.
