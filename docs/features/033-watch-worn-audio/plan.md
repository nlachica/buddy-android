# Plan: 033-watch-worn-audio

## Goal
Auto's audio order becomes: a connected headset, then the Galaxy Watch **only while it is worn**, then the phone
speaker. The watch app reads its off-body sensor and tells the phone over the Data Layer; the phone keeps the answer
with a timestamp, and unknown, stale or "no such sensor" counts as not worn. Read aloud, a phone live call and the
"Auto is using ... now" note all skip a watch that is off the wrist; a hand-picked Watch is unchanged. Done when: the
brief's six items (watch reports, phone state + staleness test, order tests, both routes skip, the note shows it, CI
green).

Two readings of the brief this plan relies on. **Gate before step 1:** the orchestrator appends both to the brief's
Clarifications (the second with Nicholas's answer to Q2), so the Verifier has one expected outcome.
- **Done-when 1** ("on change, and at start / reconnect"): *the phone asks the watch each time Auto is about to use
  it; the watch sends changes by itself only while it is playing a reading or a call's voice.* Allowed by the run
  instruction. The phone never acts on a sample older than 3 s, also after a restart or a reconnect.
- **Done-when 3's earpiece clause**: *Auto never picks the earpiece while a speaker route exists; a Live call with no
  speaker at all ends, as 030 decided.* The brief also says 030 stays and the 030/031 tests still pass, and 031's test
  pins that case. This is Q2's recommended answer; the other answer adds step 4b.

Non-goals: new settings or a priority UI; any change to explicit device picks; changes to `AudioRoute.pick`,
`ReadQueue`, the audio streams or (unless Q2 says so) the earpiece rules; a permanent watch service; a sensor
permission prompt on the watch (unless the probe shows it is needed, Q1); following a headset or a watch that is put
back on during a call; moving a read-aloud reading that already started; F-Droid (no watch there).

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
- New `Worn.java` (about 70 lines, main thread). All the rules are here so they are tested in `src/test`:
  - State: the last answer `{node, Boolean worn, at}`. `worn` null = can't tell. `at` = `sentAt + ms`: when the ask
    was sent (phone clock) plus the watch's own time from receiving it to the sensor event (`ms`, clamped to
    0..elapsed). That is the earliest moment the sample can have been taken, so `now - at` is an upper bound of its age.
  - `on(node)`: the rule. True only when this node said "worn" and `now - at <= FRESH_MS` = 3 s.
  - `check(node, out)`: an ask in flight for this node: join it. Else ask: a new id, `port.send(node, id, failed)`,
    "can't tell" after `ASK_MS` = 8 s or when the send fails. No reuse of an earlier answer: every placement gets its
    own sample.
  - `reply(node, id, worn, ms, why)`: accepted only for the pending ask's node and id (a late reply to an older ask
    is dropped); stores the answer, gives all its callers `on(node)`, logs one line.
  - `find(finder, picked, out)`: Auto's "a watch answers". The finder's node; then, read **when each answer arrives**:
    no node or `picked` (Watch is the user's pick) gives the node as is; else `check`, and "not worn" gives null
    unless `picked` turned true meanwhile.
  - `Port`: `send`, `after`, `now`, `log` (the fake in the test). Log lines (logcat tag `Worn`): `worn`, `not worn`,
    `stale (age N ms)`, `can't tell: <why>`, `no reply`, `send failed`.
- `WatchVoice.Link` gets `default void askWorn(Context c, String node, long id, Runnable failed)` (default: failed).
- `WatchVoice` holds the `Worn` and its port, and gets `find(c, out)` (`Worn.find` over `link().find`, `picked` =
  `KEY.equals(Prefs.audioDevice(c))`) and `wornReply(...)` for the link. `WatchVoice.Phone.find`,
  `LiveWatch.Phone.find` and `AudioRoute.autoNow` call `WatchVoice.find` (the note only shows when Watch isn't the
  pick).

3 s: Android's bound for the sensor to notice a removal, so a 3 s old sample is as current as a new one could be. A
second watch is not handled (a caller for another node during an ask gets "not worn").

### Phone, github build (`src/watchlink`)
- `WatchVoiceLink.askWorn`: `sendMessage(node, "/buddy/worn", {"id": id})`, `failed` on failure. Nothing else.
- `WatchLink.onMessageReceived`: one more `case "/buddy/worn"` (the manifest filter `/buddy` already covers it; the
  path joins `SEND` in the `holdWake` line). With an `id`: `WatchVoice.wornReply(node, id, worn or null, ms, why)`.
  Without: `LiveWatch.worn(node, worn)`.

### Watch (`wear/`)
New `Worn.java`: registers a listener on the first sensor of `getSensorList(TYPE_LOW_LATENCY_OFFBODY_DETECT)` (main
looper), hands each event on as a boolean (`values[0] >= 0.5`), and says why when it can't: `no sensor`, `refused`
(registration false or `SecurityException`). Two users, both in `ReadAloud` (already the watch end of the Watch audio
device and a `WearableListenerService`):
- **Asked** (placement). A message filter for the exact path `/buddy/worn` and `onMessageReceived`: an 8 s partial
  wake lock, register, wait on the service's callback thread up to 5 s for the first event (Android's bound for the
  initial event), unregister, reply to the sender: `{id, worn, ms}` or `{id, why}` (`no sensor`, `refused`,
  `no event`).
- **While a reading plays** (a call's voice is readings, and the next one is always opened ahead). `ReadAloud` is then
  a foreground service under a wake lock, so nothing new has to run: where it makes the reading's `Speaker` it
  registers (if not yet), and in `down()` it unregisters. Every event, the first one included, goes to the node that
  opened the channel as `{worn}`. So the phone hears the state at the start of each reading and each change during it.
- No new permission, no new service, no notification, nothing running between an ask and a reading.

Message table addition (the 003 table's format):

| Path | Dir | Body | Meaning |
|---|---|---|---|
| `/buddy/worn` | P→W | `{id}` | are you on the wrist? |
| `/buddy/worn` | W→P | `{id, worn?: bool, ms?: number, why?: string}` | the answer; no `worn` = can't tell; `ms`: receipt to sample |
| `/buddy/worn` | W→P | `{worn: bool}` | the sensor's first event and each change while a reading plays |

### A live call (`LiveWatch`)
Placement (start, setting change, 026's retries) goes through `find`, so it asks. `LiveWatch.Out` and the tests' fake
are not changed. Three small changes in `Call`:
- `found`: a node counts only if `out.watchWins()` still holds (a headset that came while the watch was asked keeps
  the voice on the phone; `ReadQueue` already rechecks the same way after its find).
- `worn(node, w)` (from the watch's push, via the static `LiveWatch.worn`): for the call's node, remember
  `off = !w`, then `leave()`.
- `leave()`: `off`, a turn under way and Watch not the pick (`!KEY.equals(saved)`) runs the existing `stop()`: the
  voice is on the phone at once, an idle reading opened ahead is cancelled, a reading under way is closed and the
  microphone waits for the watch's answer, held voice is not forwarded. Also called in `poll` after a setting
  change's `decide()` (Watch switched to Auto off the wrist). `off` is cleared in `clear()` and at each `attempt`.
- A push is judged when it arrives, against the setting then: nothing in `Call` waits on a worn answer, so nothing
  stale can stop a picked Watch.
- Rule, decided simply: once off the wrist, an Auto call stays on the phone (speaker) until it ends or the setting
  changes; no retry is armed and putting the watch back on mid-call is not followed. The `decide()` that follows a
  drained reading asks again and finds no worn watch: "none found: phone".
- Limits: the sensor's own lag (up to 3 s by Android's bound) can still sound on the watch; voice held for the watch
  at the moment of leaving is dropped, as `stop()` does on a setting change today.

Read aloud is placement only: a reading that began on the wrist finishes there; the next one asks again.

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
- The phone asks before or after each utterance of a call (round 1): either the audio is already sent when the
  answer comes, or the voice has to be held or moved mid-utterance and a pending answer can outlive a setting change
  (review round 2, F1 and F3). The watch's push needs no waiting state on the phone.
- Reusing an answer for a few seconds (rounds 0 and 1): saves a message only for placements less than 3 s apart, and
  needs invalidation on reconnect.
- The watch refuses an Auto reading itself when off the wrist: the watch can't tell Auto from a picked Watch without
  a change to the reading channel, and the settings note needs the ask anyway.
- Health Services: no on-body API (research).

## Steps
0. **Gate and probe (no code).** Q2's answer is in the brief's Clarifications (orchestrator). If the watch is on adb
   (installing the watch app needs that anyway): `adb shell dumpsys sensorservice | grep -i -B1 -A3 offbody`; record
   the sensor's line (exists, `perm:`, wake-up) in `decisions.md`. `perm: n/a`: carry on. A permission: stop, back to
   the Planner with Q1's answer. No watch on adb: carry on; the probe is then the Verifier's first check.
1. **Phone state and rule.** `app/src/main/java/app/buddy/assistant/Worn.java` (new), `WatchVoice.java`
   (`Link.askWorn` default, the `Worn` and its port, `find`, `wornReply`). Test `app/src/test/.../WornTest.java` with
   a fake port.
2. **Phone link.** `app/src/watchlink/.../WatchVoiceLink.java` (`askWorn`), `WatchLink.java` (the case, the path
   constant, `holdWake`, a pointer to this plan in the class comment).
3. **Watch.** `wear/.../Worn.java` (new), `Phone.java` (`WORN`), `ReadAloud.java` (`onMessageReceived`; register
   with the reading's `Speaker`, unregister in `down()`), `wear/src/main/AndroidManifest.xml` (the `MESSAGE_RECEIVED`
   filter, `android:path="/buddy/worn"`). Test `wear/src/test/.../WornTest.java` for the JSON-free part (the wear
   tests have no org.json): sensor value to worn (0.0, 0.49, 0.5, 1.0) and each failure to its `why`.
4. **Auto uses it.** `WatchVoice.java` (`Phone.find`), `LiveWatch.java` (`Phone.find`, static `worn`, `Call.found`,
   `Call.worn`, `leave`, `off`), `AudioRoute.java` (`autoNow`, class comment), `SetupActivity.java` (`autoAsk`),
   `SettingsTool.java` (two texts), `res/values/strings.xml` (`setup_audio_device_info_watch`). Tests: new ones in
   `LiveWatchTest.java`. No existing test is edited; if one fails, stop and report instead of changing it.
   - **4b, only if Q2's answer is "earpiece as the last resort":** `AudioRoute.liveDevice` returns the earpiece when
     `comm` lists no speaker and no headset (a listed speaker that was refused still ends the call, 030/031);
     `AudioRouteTest`: 031's earpiece-only line expects the earpiece, plus speaker + earpiece gives the speaker and a
     refused speaker gives `NONE`; README's "never the earpiece" sentences. If 030's earpiece watchdog in
     `AudioRoute.live` fights it, back to the Planner.
5. **Docs.** `README.md` (the Live-voice-on-the-watch and Audio-device-Auto paragraphs), `decisions.md`.

## Test plan
| Done-when | Proof |
|---|---|
| Watch reads the sensor and reports; no sensor = unknown | `wear` `WornTest`; code review of `wear/.../Worn.java` and `ReadAloud`; on the watch: step 0's probe and the sign-off gate below |
| Phone keeps state + timestamp; unknown or stale = not worn | `WornTest` (fake clock and port): a worn reply gives worn; `false` and no `worn` are not worn; **delayed positive**: `ms` 0 arriving at 3 s is worn, at 3 s + 1 ms is not; **slow sensor**: `ms` 4 s arriving at 6 s is worn; `ms` larger than the elapsed time is clamped; a second `check` 1 s after a positive one sends a new ask and its "not worn" wins; two callers during one ask send one message and both get the answer; no reply by 8 s is not worn; a failed send is not worn at once; ask A times out, ask B is sent, A's late "worn" is dropped and B's "not worn" decides; another node's reply is dropped; a refused registration logs `can't tell: refused` and a silent watch logs `no reply` |
| Auto order | Existing, unchanged: `AudioRouteTest.autoOrder` and `autoOrderPutsTheHeadsetBeforeTheWatchAndNeverTheEarpiece` (headset before the watch; speaker and earpiece both listed: the speaker; earpiece only: `NONE`, the call ends, or step 4b's tests if Q2 says so). New in `WornTest` (`Worn.find`, fake finder): reachable + worn gives the node; reachable + not worn / can't tell / stale gives null; no node gives null without asking; Watch picked gives the node without asking; Watch picked while the ask is pending, then "not worn": the node; Watch switched to Auto while the finder runs: asks. Existing `ReadQueueTest.quietNoWatchReadsOnThePhoneWithNoNotice`, `LiveWatchTest.noWatchFoundStaysOnThePhone`, `anAutoCallWithNoWatchUsesTheSpeaker` prove null means the phone speaker |
| Both routes skip; a picked Watch unchanged; 016/018/030/031 tests pass | `./gradlew testGithubDebugUnitTest`, no existing test edited. New `LiveWatchTest` (Auto unless said): **taken off in a pause** (reading opened ahead, `worn(false)`: it is cancelled, `toWatch()` is 0, the next loud block and all after reach no reading, nothing more is opened, no retry in 10 s); **taken off with a whole reply queued** (first reading draining, second utterance held and ended, `worn(false)`: `toWatch()` is 2; the fake's find then gives no node, as Auto's does off the wrist; after `played` no reading is opened and the held audio went nowhere, the mic opens after the tail); **taken off mid-utterance** (the reading is closed, not cancelled, the mic waits for its answer); a picked Watch ignores `worn(false)`, and switching it to Auto leaves at the next poll; `worn(true)` after it and then Auto: stays; another node's push is ignored; a headset that came while the find was held: nothing opened. Manual: see the gate |
| "Auto is using ..." reflects it | Manual: Settings → Voice with Auto, take the watch off: the note changes from Watch to Speaker within about 15 s, and back when worn |
| CI green, diff limited | `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease :wear:testDebugUnitTest`; `git diff main --stat` shows only the files above |

**Sign-off gate (Verifier, before PASS; not a post-merge check), phone + watch with the new watch app:**
1. Worn, watch screen off: the note says Watch and a reading plays on the watch. If it says Speaker, `logcat -s Worn`
   on the phone gives the reason. Not done then: back to the Planner (Q1 for `no sensor` / `refused`, Risk 2's way out
   for `no event`, Risk 6 for `stale`).
2. Watch on the table for 6 s: a reading and a new call both play on the phone speaker. Watch picked by hand: still
   on the watch.
3. Auto call on the wrist, watch taken off while Buddy speaks: the voice is on the phone speaker within about 5 s.
   Taken off in a pause, 5 s later a question: the whole reply is on the phone.
Nothing is released from a failed gate.

## Risks
1. **The sensor may be hidden without a permission.** Android's pages list none; a Samsung post says `BODY_SENSORS`.
   Not checkable during planning (no watch on adb). Step 0 and the gate catch it; Q1 is the way out.
2. **Sensor events to a background app (the ask only).** Android 9+ withholds sensor events from idle apps. The ask is
   read inside a Play-services-bound service callback under a wake lock, which should count as active; unverified.
   Gate 1. Way out: do the read in a short foreground service. The push runs in `ReadAloud`'s foreground service.
3. **Watch app must be updated with the phone.** An older watch app never answers, so each Auto reading waits 8 s and
   then plays on the phone. README says so; a picked Watch keeps working.
4. **The sensor's lag and lost words on leaving.** Up to 3 s of a call's voice can still sound on a watch just taken
   off, and voice held for the watch at that moment is dropped (existing `stop()` behaviour).
5. **A lost push.** The call stays on the watch until the next reading starts, whose first event repeats the state.
6. **A slow link reads as not worn.** A reply whose sample is older than 3 s on arrival sends an Auto reading to the
   phone though the watch is worn. Safe direction; the log line shows the age, and `FRESH_MS` is the one knob.
7. **Added delay.** Each Auto placement waits one round trip to the watch (typically well under a second).

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
   happen? Needed before coding starts.
   - **End the call with a notice, as today (recommended):** you asked several times that a call never plays in the
     earpiece (030). No code change.
   - Play in the earpiece as the true last resort: only when Android lists no speaker and no headset; a small change
     to 030's rule and its test (step 4b).
