# Plan: 016-audio-auto-priority
<!-- Planner (claude-opus-5-5), 2026-11-16. Sources: brief.md, research.md, code read at bd595f3, 001/012 decisions and
     verify, Android reference for AudioManager/AudioDeviceInfo (fetched 2026-11-16, facts quoted under Design).
     Revised for plan review round 1 (F1-F6) and round 2 (F1). -->

## Goal
Buddy's audio device setting (001's selector plus 012's Watch) must never stay stuck on a device that is gone. **Auto**
uses the best output that is connected *when a reading starts*, in the order phones use: a headset Android plays media
on (Galaxy Buds, LS2 FreeCom over Bluetooth, or wired/USB; when several are connected, the one Android picks, which is
the latest), then the Galaxy Watch (012 path, github build) when it answers, then the phone speaker. A **picked**
device is used while it is connected. While it is gone Buddy follows the same order, and it uses the picked device
again once it reconnects.
Done when: (1) Auto with buds goes to the buds; buds off → watch (when reachable); watch unreachable → speaker; nothing
stale at the moment of speaking. (2) Picked device disconnected → falls back by the order with no silence; reconnected
→ used again. (3) The setting shows what Auto is using now; the F-Droid build is the same minus Watch; CI green; unit
tests for the order.

Non-goals: a user-editable priority list (brief; research: stock Android has none); routing calls or other apps' audio;
moving a reading that is already playing to another device mid-sentence (the order applies from the next reading);
Auto taking a headset's **microphone** (Auto's voice input stays on Android's default, as today; picking the headset
gives its mic, see Risk 4); the voice bar chip (it stays as today: the pick's label or "Auto"; it is a dictation and
live-voice surface where Auto's watch step never applies); the voice bar on the watch.

## Design

### What Android already does, and what is left for Buddy
- With nothing applied (Auto today: `AudioRoute.reconcile` → `drop()`), Android plays Buddy's voice (TTS
  `USAGE_ASSISTANT`, ChatGPT's voice in the WebView) by its own media routing. That routing already prefers a connected
  media headset over the speaker and takes the most recently connected one when there are several. This is the "most
  recently connected wins" rule from the brief and research, and Buddy can't do it better: apps see no connection
  times. **So Auto's headset step is Android's own routing, unchanged.** Buddy only needs to know *whether* Android
  would play on a headset, so that it can decide between "leave it to Android" and "try the watch".
- Only a **media** output counts. A Bluetooth device connected for calls only (HFP, listed as `TYPE_BLUETOOTH_SCO`) gets
  no media from Android in normal mode, and Auto stays in normal mode. The Galaxy Watch's own call link is such a
  device, so counting SCO would also block the watch step. A call-only headset can still be picked (001 applies it in
  call mode).
- The watch is not an Android audio device (001 verify; 012). Only Buddy can put it in the order, between headset and
  speaker. Only read aloud can go there (012 non-goal: mic and live voice never use the watch).
- A picked device that is gone is already dropped for routing (`reconcile`: not in `getAvailableCommunicationDevices`
  → `drop()`), and re-applied on reconnect (001). Two things are missing. Buddy's voice falls back to Android's choice
  only, so the watch step is skipped. And the UI shows only the saved name.

### The order: pure functions
In `AudioRoute` (main source, no Android types in the signatures, JVM-testable):

```java
/** Media outputs Android plays on before the phone itself. Not BLUETOOTH_SCO: a call-only link gets no media. */
static boolean headset(int type)   // WIRED_HEADSET, WIRED_HEADPHONES, USB_HEADSET, BLUETOOTH_A2DP, HEARING_AID,
                                   // BLE_HEADSET, BLE_SPEAKER → true; everything else (SCO included) false

static final int NONE = -1, UNNAMED = -2;

/**
 * The headset Android plays Buddy's voice on, from output types. routed: Android's own answer for Buddy's voice
 * (API 33+), null or empty when there is none; it wins, even when it names only the speaker. Without it, any connected
 * headset counts, but which one Android uses can't be seen. Returns the index in routed, UNNAMED, or NONE (the phone).
 */
static int headsetIn(int[] routed, int[] connected)

/** Where Buddy's voice goes. Returns the saved key, WatchVoice.KEY, or "" (Android's own routing). */
static String pick(String saved, boolean savedHere, boolean headset, boolean watch) {
    if (!saved.isEmpty() && savedHere) return saved;   // a pick in effect (Watch: when this build has the link)
    if (headset || !watch) return "";                  // a headset (Android picks the latest), or no watch link
    return WatchVoice.KEY;                             // the watch; the phone (speaker) when none answers
}
```

`TYPE_*` are compile-time constants (inlined, so the test loads no `AudioDeviceInfo`). `TYPE_BLE_*` are API 31 and
never match on API 30.

Android side, also in `AudioRoute`:
- `headsetNow(Context)` → `headsetIn` over the types of `getAudioDevicesForAttributes(USAGE_ASSISTANT /
  CONTENT_TYPE_SPEECH)` on API 33+ ("Get the audio devices that would be used for the routing of the given audio
  attributes", public, no permission) and of `getDevices(GET_DEVICES_OUTPUTS)` ("the audio devices currently
  connected", API 23, so also API 30, the minSdk). Result: the routed device, unnamed, or none.
- `target(Context)` = `pick(saved, indexOf(choices(c), saved) >= 0, headsetNow(c) != NONE, WatchVoice.link() != null)`.
  `choices()` already lists exactly the connected communication devices plus Watch when the link exists, so
  `savedHere` needs no new code. Computed fresh on every call, with no cache. It's a few binder calls on the main
  thread, the same kind `choices()` makes today.
- `androidNow(Context)` → the label of what Android's routing uses now: the routed headset's `label()` (product name,
  or "Headset" for wired), "Headset" when unnamed, else "Speaker".

Routing (`use`/`want`/`reconcile`) **does not change**: a pick in effect is applied as in 001, everything else drops to
Android's routing.

### Speaking: every reading decides when it starts
`Voice.speak` (`Voice.java:234-241`) today sends the text to the watch only when Watch is saved. New:

```java
if (WatchVoice.link() != null) WatchVoice.speak(c, clean, !WatchVoice.KEY.equals(Prefs.audioDevice(c))); // quiet = Watch not picked
else here(c, clean); // F-Droid: no watch step; Android's routing when the voice starts already is the order
```

In the github build **every reading goes through `ReadQueue`**, whatever its device, so readings go one at a time and in
order. `ReadQueue` decides where a reading goes **when it becomes current, and again when its find returns** (the find
takes up to 3 s). Nothing waits in the phone's own queues with an old answer: a reading reaches the phone only when
nothing is playing there, so the answer is the one at the moment of speaking (round 2 F1). New `Out` methods:
- `boolean watchWins()`: `AudioRoute.target` is Watch now. False when a headset connected or the pick is in effect.
  Then the reading takes the quiet phone path below: Android's routing or the pick.
- `boolean phoneBusy()`: the phone is still speaking (`CodexSpeech.active()` or the TTS `isSpeaking()`; new
  `Voice.phoneSpeaking()`). A quiet reading **waits** while it is: it stays current, so the readings behind it wait too,
  and `ReadQueue` asks again every `WAIT_MS` = 300 ms (`out.after`, like 001's route poll). Once the phone is quiet it
  decides by the order. Example: the buds are taken out while a plays in them. b goes to the watch once a is done,
  whether b came before or after the buds left. This also handles the reverse transition: a watch that becomes
  reachable while the phone still speaks can't make two voices talk at once or swap their order. A picked Watch ignores
  it (012 unchanged).
- `void here(String text)`: ChatGPT's voice on the phone (`SpeechPause.begin` + `Voice.here`). Not waited for.

The rule in `ReadQueue`, `start(r)` (`quiet` = the user didn't pick Watch):
- `quiet && phoneBusy()` → wait `WAIT_MS`, then `start(r)` again (dropped if `r` is no longer current);
- `!watchWins()` → quiet phone path, no find;
- else find. When it returns: if the answer changed meanwhile (`!watchWins()`, or `quiet && phoneBusy()`), `start(r)`
  again. No node: quiet → quiet phone path, picked → 012's `phone(r)` (notice once, then phone TTS). A node: play on
  the watch, exactly as in 012;
- the watch refused: quiet → quiet phone path; picked → 012's `phone(r)`.
- **Quiet phone path**, no notice, `noticeDue` untouched (the 012 notice still comes the first time a *picked* Watch
  can't play): a reading in the phone's voice uses the existing waitable `out.tts(text, null, () -> end(r))`
  (`Voice.routed`, one request at a time, so a cold engine can't drop one). A reading in ChatGPT's voice goes to
  `out.here(text)` and ends at once; the next reading then waits on `phoneBusy` until that voice is done (its call
  closes), and `fail()` still hands a failed one to the phone voice. ChatGPT's voice has no "done" callback, and
  swapping in the phone's voice would be a regression for Auto users.

The 11-min cap (`READ_MAX_MS`) is still armed once, when a reading becomes current, so it bounds a wait too.

With Watch picked, nothing changes (012: notice, then the phone TTS). Its phone fallback plays through Android's
routing, so it already follows the order: a connected headset, else the speaker.

### What the setting shows
One helper, `AudioRoute.autoNow(Context, Consumer<String>)`: the label of what Auto uses right now. It runs `pick("",
false, headsetNow != NONE, link != null)`. If that gives "", it returns `androidNow` at once. If it gives Watch, it asks
the link and then returns "Watch" or `androidNow`. No cache. Used only when Auto is in effect (saved empty, or a pick
that is away).
- **Settings → Voice → Audio device** (`SetupActivity.java:613-634`). The label and note now show whenever the voice
  page is shown. The segments still need two or more choices, so F-Droid on API 30, which has only Auto, gets the
  read-only status. Under Auto or an away pick, the note is the existing text (the rule, or "<name> isn't connected:
  Auto until it is.") plus "Auto is using <X> now.". A picked Watch keeps 012's note and its unreachable variant.
  Freshness: headset changes already re-render (`AudioDeviceCallback`, `:46-58`). The watch isn't an Android device,
  so while the Auto note is on screen Setup calls `autoNow` again every 5 s (posted in render, removed in `onPause`,
  started again by `onResume`'s render). A result is applied only if it belongs to the newest ask and the note is still
  attached. The rule text gives the order: github "…Auto uses a connected headset (the latest one), else your watch for
  read aloud, else the phone"; F-Droid (no link) the same without the watch.
- **`buddy_settings` read** (`SettingsTool.java:342-356`): `read()` adds a line `now: <X>`: the pick's label; or `auto
  → <androidNow>`; or `auto → watch, or <androidNow> when no watch answers`; plus `(<saved> isn't connected)` for an away
  pick. It's synchronous, so it states the watch step rather than asking. `get()` is unchanged (it's the snapshot the
  approval card compares). `allowed()` gains one clause describing Auto's order.

### Alternatives considered (one line each)
- Auto applies the top-ranked headset as the communication device (mic + speaker over SCO): needs connection order
  Buddy can't see, forces call mode and SCO quality on read aloud, changes 001's "Auto behaves as before". Rejected.
- Rank by `getAvailableCommunicationDevices()` or count SCO as a headset: call-only links (the watch's own included)
  get no media in normal mode, so Auto would skip a reachable watch and play on the speaker. Rejected for detection;
  still used for `savedHere`.
- Prefer the connected list over Android's API 33 answer: names a headset Android isn't using. Rejected.
- A cached watch reachability (listener or periodic) checked in `Voice.speak`: stale at speak time. Rejected.
- Deciding watch-or-phone once at submission: stale by the time a queued reading starts (round 1 F1). Rejected.
- A quiet reading follows on the phone while the phone speaks (round 1), or skips ReadQueue when a headset wins: it
  waits in CodexSpeech's queue with an answer that can be gone by its turn (round 2 F1). Rejected.
- Only readings that come while the phone or the queue is busy go through ReadQueue: the same call cost as Risk 9, and
  a phone-voice reading sent directly isn't waited for, so the TTS gap stays open. Rejected.
- A route check at CodexSpeech's dequeue (`next()`) that hands a reading back to ReadQueue when the watch wins: keeps
  one call for overlapping replies, but adds a hook, a hand-back and new call-closing cases to CodexSpeech's threaded
  code. It's the follow-up if Risk 9 is noticeable.
- Quiet fallback ends at once for every voice: a cold phone TTS keeps only the last text (round 1 F3). Rejected.
- Quiet fallback through phone TTS for ChatGPT readings too: swaps ChatGPT's voice for the phone's. Rejected.
- Clearing the saved pick when its device disconnects: breaks "reconnects → used again". Rejected.
- "Auto: <X>" on the voice bar chip: that bar is dictation or live voice, where the watch step never applies and Auto's
  mic is the default one, so it would mislead (round 1 F5). Rejected.

## Steps
1. **The order, pure, with tests.** `AudioRoute.java`: add `headset(int)`, `headsetIn(int[], int[])` and `pick(...)`
   as above, and update the class comment (Auto = the order, not "Android decides"). New
   `app/src/test/java/app/buddy/assistant/AudioRouteTest.java` (see Test plan). If loading `AudioRoute` in the JVM trips
   on its static `Handler`, make it lazy like `Voice.post` (`isReturnDefaultValues = true` should make that
   unnecessary).
2. **Android side of the order.** `AudioRoute.java`: `headsetNow` (private), `target`, `androidNow`, `autoNow`, each with
   a one-line comment pointing at this plan. No change to `use`/`want`/`reconcile`.
3. **Speaking in the order.** `ReadQueue.java`: `speak(text, chatgpt)` delegates to new `speak(text, chatgpt, quiet)`;
   `Item.quiet`; `Out.watchWins()`, `Out.phoneBusy()`, `Out.here(String)`; `WAIT_MS`; `start(r)` (called by `pump`
   after it arms the cap, again after a wait, and when the find's answer changed) and the quiet phone path as above;
   class comment covers every reading in the github build and Auto's step. `WatchVoice.java`: `speak(c, clean, quiet)`; `Phone`
   implements the three methods (`watchWins` = `KEY.equals(AudioRoute.target(app))`, `phoneBusy` =
   `Voice.phoneSpeaking()`, `here` = `SpeechPause.begin(app); Voice.here(app, text)`); remove `chosen()` (its only
   caller is `Voice.speak`). `Voice.java`: `here` becomes package-private; new `phoneSpeaking()` (`isSpeaking()` minus
   `WatchVoice.active()`, which `isSpeaking()` then reuses); `speak` routes as above (link → ReadQueue, else `here`).
   `ReadQueueTest.java`: the Fake gets `watchWins`/`phoneBusy` fields (default true/false, so existing tests are
   unchanged) and logs `here`, plus the new tests.
4. **Showing it.** `SetupActivity.java` (audio device section: label and note always, segments with two or more choices,
   the Auto line, the 5-s refresh and its `onPause` removal); `res/values/strings.xml`: new `setup_audio_device_now`
   ("Auto is using %1$s now."), `setup_audio_device_info_watch`; reword `setup_audio_device_info` (rule without watch).
   English only, as all audio strings are today (no `values-*` copies exist). `VoiceBar.java` is not touched.
5. **Settings tool and docs.** `SettingsTool.java`: `read()` override and the `allowed()` clause for `audio_device`.
   `README.md:100` area: one bullet "Audio device Auto" (the order, a picked device falls back and comes back, Settings
   shows what Auto is using).

## Test plan
| Done-when | Proof |
|---|---|
| 1. Auto: buds → buds; off → watch; unreachable → speaker; not stale | **Unit** `AudioRouteTest`. `pick("", false, true, true)` = "" (headset wins over watch); `pick("", false, false, true)` = "watch"; `pick("", false, false, false)` = "" (speaker / F-Droid). `headset()` is true for the 7 media types and false for BLUETOOTH_SCO, BUILTIN_SPEAKER, BUILTIN_EARPIECE, BUILTIN_SPEAKER_SAFE, TELEPHONY, REMOTE_SUBMIX. `headsetIn`: routed [SPEAKER] with connected [SPEAKER, SCO, A2DP] → NONE (Android's answer wins); routed [A2DP] with connected [A2DP, A2DP] → 0 (the routed one, not the first enumerated); routed null with connected [SPEAKER, SCO] → NONE (call-only link); routed empty with connected [SPEAKER, A2DP] → UNNAMED. **Unit** `ReadQueueTest`. Quiet, phone voice, no node → `[find, phone a]`, no NOTICE, b's find waits for a's done. Quiet, ChatGPT voice, no node → `[find, here a]`, and a ends at once (with `phoneBusy` false in the fake, b's find starts). Quiet + refused → stopProducers, then the quiet phone path. Quiet + node → plays on the watch. A quiet fallback, then a picked one with no node → NOTICE still said. *Fresh at start:* a plays on the watch, b queued, `watchWins` turns false → after `played`, b goes `phone b` with no find. *Fresh after find:* `watchWins` turns false while a's find is pending, find returns "w" → no open, `phone a`. *Phone busy, route changed before b (round 2 F1):* quiet ChatGPT a with `watchWins` false → `here a`, a ends; `phoneBusy` true, `watchWins` true, then quiet b → nothing logged; run the wait → still nothing; `phoneBusy` false, run the wait → `find`, "w" → `open w`, `chatgpt b`. The log has `here a` once and no `here b`: a reached the phone queue once and b never did. *Phone busy, route changed after b was submitted:* the same, but `watchWins` is still false when b comes and turns true during the wait → the same log. *Phone busy, still the phone's:* `watchWins` stays false → after the wait `here b`, no find. *Busy after find:* quiet a, find pending, `phoneBusy` turns true, find returns "w" → no open; `phoneBusy` false, run the wait → a second `find`, "w" → `open w`. *Picked:* picked a with `phoneBusy` true → find at once (012 unchanged). **Device** (Verifier with Nicholas, github build): Auto, Buds2 on → a reply heard in the buds. Buds in case → next reply on the watch, no notice. Watch Bluetooth off → next reply on the speaker, no notice, delay to speech noted (Risk 1). Two replies in a row while the buds connect → the second in the buds. ChatGPT voice, buds on: a long reply, then a second one while it plays; take the buds out during the first → the first finishes on the speaker (Risk 6), the second plays on the watch after it, not on the speaker. Buds2 on with "Media audio" off in Bluetooth settings → reply on the watch (call-only link ignored). |
| 2. Picked gone → order, no silence; back → used | **Unit**: `pick("7:LS2 FreeCom", false, true, true)` = ""; `pick("7:LS2 FreeCom", false, false, true)` = "watch"; `pick("7:LS2 FreeCom", true, true, true)` = the key (a pick beats a headset); `pick("watch", true, true, true)` = "watch"; `pick("watch", false, false, false)` = "" (stale "watch" in a build without the link). The "fresh at start" ReadQueue test covers a pick reconnecting while a reading waits. **Device**: pick the FreeCom (or Buds2) in Settings. Turn it off → reply on the other headset / watch / speaker by the order, and Settings says "… isn't connected: Auto until it is. Auto is using <X> now.". Turn it on → next reply on it, its segment highlighted. While it's off, `buddy_settings` read's allowed list must not include it (Risk 2). |
| 3. Shows what Auto uses; F-Droid minus Watch; CI; order tests | **Device**: the Settings note shows "Auto is using <X> now." for each step above, and `buddy_settings` read shows `now: …`. Leave the voice page open with Auto and no headset, turn the watch's Bluetooth off, then on → the note goes to Speaker and back to Watch within ~10 s, with no headset change. **F-Droid**: no link → `pick(…, watch=false)` never returns "watch" (unit). The rule text without the watch, and the label + note shown with only Auto (API 30), are checked by code review: there is no API 30 device or emulator here. `assembleFdroidRelease` in CI compiles it. **CI**: `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease` (`build.yml:63`) green. No Android SDK on the phone: CI is the build and test run. |

001/012 regressions to re-check on device: a picked Speaker/Phone still applies (call mode) and cleans up after;
Watch picked still plays on the watch and gives the notice when it can't; live voice unchanged; the voice bar chip
unchanged; picking a device in Settings still plays the sample on it (in the github build it now goes through
ReadQueue too).

## Risks
1. **Delay in Auto's watch step with no watch around.** Each reading waits for `find` before the phone speaks. With the
   watch disconnected, the capability lookup should answer from Play services' local node list in well under a second,
   but the guard is 3 s. Verifier notes the delay. If it's noticeable, the follow-up is a quick "any connected node?"
   pre-check, not part of this plan.
2. **Why the FreeCom looked stuck is not reproduced** (research). If Android keeps listing a device that is off in
   `getAvailableCommunicationDevices()`, Buddy and Android both treat it as connected and route there. The Verifier
   checks this with the FreeCom off. If it is still listed, a follow-up guard (drop a pick whose communication device
   never comes up) needs on-device evidence first.
3. **What counts as a headset.** On API 33+ Android's answer decides, so any media output it routes to counts if its
   type is in the list. BT A2DP covers cars and BT speakers: Android plays there anyway, so Auto follows, as phones do.
   USB DACs (`TYPE_USB_DEVICE`), HDMI and docks are not in the list, so there Auto may pick the watch while Android
   would use that output. Rare; accepted.
4. **Auto doesn't take the headset's mic.** Output follows the headset. Voice input under Auto stays on Android's
   default, as today (live voice: Chromium picks the BT headset itself). On a helmet (FreeCom), Nicholas can pick the
   FreeCom to get its mic. If he wants Auto to do that too, it's a follow-up with call-mode cost (SCO quality on read
   aloud). Not blocking: the done-when is about output.
5. **API 30-32 label.** There's no Android answer below API 33, so a connected headset is labeled "Headset", not by
   name. The order itself is right. Nicholas's phone is API 33+.
6. **Mid-reading changes.** A picked device that leaves mid-reading: the 300-ms poll drops it, and Android's routing
   finishes the reading on a headset or the speaker (no silence). The watch step applies only from the next reading.
   Readings lined up behind a long reading (watch or phone) wait for it, even if a headset connects meanwhile (order
   over speed). If a phone reading runs past the 11-min cap, the reading waiting behind it is dropped, as a stuck one
   is today.
7. **`phoneBusy` gap.** TTS reports speaking only once audio starts. When ChatGPT's voice fails, `fail()` hands its text
   to the phone TTS, which isn't waited for, so a quiet reading could reach the watch in the short gap while that
   voice starts and detects a language. The re-check after the find narrows the gap. It needs that failure and a
   reachable watch at that moment. Accepted.
8. **Profile flapping** (research): a headset whose communication type changes (SCO ↔ LE) changes its key, so a pick
   can look away for a moment. Auto then plays on the same headset through Android anyway (output), and the pick
   returns on the next match.
9. **Overlapping ChatGPT readings no longer share one call** (github build). Today a reply that comes while one is
   read joins the open ChatGPT voice call. Now it waits until that call is done and closed, then opens its own, so
   there's a pause of the voice's connect time between them. It's the price of choosing each reading's device when it
   starts (round 2 F1). The phone voice has no such cost: its next reading starts on the done callback. The Verifier
   notes the pause in the two-replies device check. If it's noticeable, the follow-up is the dequeue check under
   Alternatives.

## Open questions
None blocking. (Risk 4, Auto also taking the headset's mic, is for Nicholas after he tries it; the plan doesn't need
the answer.)
