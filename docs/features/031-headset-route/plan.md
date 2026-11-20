# Plan: 031-headset-route

## Goal
Nicholas's Bluetooth headset "LS2 Freecom" (SCO call link, plus a media output of the same name) carries Buddy's voice
again, in and out, for phone Live calls and for dictation. Done when: (1) in a Live call (Auto or saved = LS2) the
headset becomes and stays the communication device: its SCO gets the time Android needs to connect instead of being
refused at the first poll while Android still reports the earpiece, with a unit test of the log's sequence that fails on
main; (2) a headset that never connects within its setup time still ends on the speaker, never the earpiece, and 030's
done-when tests still pass; (3) dictation with the headset connected (Auto, and saved = LS2) takes its input from the
headset (communication device = its SCO), root cause fixed with a test; (4) Auto's order is connected BT headset > watch >
speaker, never the earpiece (test); (5) CI green, diff limited to the routing fix, its tests and docs.

Non-goals: no redesign of AudioRoute, LiveWatch or the watch flow (017/026); no new setting or UI; no change to 030's
no-headset behaviour (speaker, never earpiece); no hold/mute of the call's voice during setup (dropped in 030); no change
to when `use()` clears refusals; read aloud under Auto stays on Android's media routing (A2DP).

## Design

### Root causes
- **Live.** 030's `grace()` (`AudioRoute.java:229-232`) gives a headset not seen yet 0 ms when Android reports the
  earpiece. Android reports the earpiece for the whole time a SCO link connects, so every SCO apply is refused at the
  first 300 ms poll: log `0.26 7:LS2 Freecom applied`, `0.56 slipped ... to 1:SM-G990W`, speaker applied; again at
  `7.59` -> `8.44`, and after each of Nicholas's setting changes. Mic peak 0.000 all call (comm device was the speaker).
  The `2.99` fight (speaker re-applied over the LS2 that Chromium had just made the device) only follows that wrong
  refusal: LS2 refused, `applied` = speaker, so Android reporting LS2 counted as the speaker slipping.
- **Dictation.** 030 changed nothing on the non-live path (research). Under Auto, `reconcile()` calls `drop()`:
  normal-mode routing, which never opens a Bluetooth headset's call link, so the phone's own mic is used (016's chosen
  cost: "under Auto, voice input stays on Android's default mic", `docs/features/016*/decisions.md:14`). Nicholas
  switched `audio_device` from `7:LS2 Freecom` to Auto today, so dictation lost the headset mic. Saved = LS2 goes through
  unchanged code (`setCommunicationDevice` once, call mode), the route that worked before.
- Fact the plan relies on: in the log the setting is Auto (`watch: setting auto -> ...`) and LS2 was applied at `0.26`.
  With saved = "", `liveDevice` reaches a SCO only through `mediaHeadset`, so LS2 also has a media output named
  "LS2 Freecom" (A2DP). Its SCO therefore counts as "a headset's call device" under the existing rule, and the watch's
  SCO (no media output) does not.

### Fix 1 (Live): a headset not seen yet gets the setup window whatever Android reports
`grace(applied, seen)`: `CONFIRM_MS` (2 s) for the speaker and for a route Android reported once; `SETUP_MS` (30 s,
Android's documented window for a Bluetooth call device) for a device not seen yet. The `actual` parameter and the
earpiece branch go. Effect on the log: LS2 applied at 0.26 is kept while Android reports the earpiece; `liveAttempt`
finds `k.equals(applied)` and doesn't re-apply; once Android reports LS2 (`2.99`), `seen` is set and the route holds
with 2 s on any later loss. The `2.99` speaker fight can't happen because LS2 is never refused. A headset whose link
never comes slips at 30 s, is refused, and `liveAttempt` takes the speaker (never the earpiece: `liveDevice` excludes it).
- Alternatives: keep 0 ms (the bug); 2 s, 030 round 3 (a guess under a slow SCO's setup time, same failure); a shorter
  earpiece-only bound such as 5 s (still a guess at headset setup time, one more branch). Un-refusing a device Android
  reports (research's second fault): not needed once LS2 isn't wrongly refused; YAGNI.

### Fix 2 (dictation): Auto while dictating uses a connected headset's call device
`want()` (the device Android routing should take) becomes: a pick is itself; Watch counts as Auto (as now); Auto while
`Voice.listening()` is the connected headset's call device, by `liveDevice("", ...)` with no refusals (the rule Live
already uses: a SCO named like a connected media headset, or a wired/USB/BLE/hearing-aid headset), unless that answer is
the speaker or NONE; otherwise "" (Android's routing, unchanged). Everything after `want()` is the existing pick path:
call mode, `setCommunicationDevice`, `applied`, and `drop()` when listening ends (the next poll sees "" and releases it;
`finish()` does at the end of the session). Read aloud keeps A2DP because `Voice.speak` runs with `listening()` false.
`use()` already reads `want(a)`, so a dictation start forces the apply like a pick does.
- Testable as one pure overload, `want(String saved, boolean listening, int[] comm, String[] commName, int[] out,
  String[] outName)`, which returns a key or "". The private `want(Context)` builds the arrays and passes
  `Voice.listening()`.
- `Voice.listen` calls `use()` before it sets its listener, but `use()` posts to the main thread and `listen` runs on
  it (Bubble, ChatActivity), so `listening()` is true when `reconcile` runs. Claude and ChatGPT dictation set their
  listener synchronously in `listen` (`ClaudeDictation.java:43`, `CodexDictation.java:61`).
- Alternatives: keep 016 (phone mic: fails done-when 3); the SCO for the whole voice session (read aloud drops to call
  quality, and on API 33+ `target()` would see Android route assistant audio to the SCO, which isn't a media headset,
  and send readings to the watch); SCO-only detection instead of reusing `liveDevice` (a new predicate; a wired headset's
  mic is the same either way).

### Order for Auto (done-when 4)
No new logic. Live: `liveDevice("")` gives the headset's call device, else the speaker, never the earpiece. LiveWatch's
watch step (`livePick`) yields to a connected media headset. Read aloud: `pick()` gives headset > watch > speaker.
Dictation: `want()` gives the headset's call device, else Android's routing (no watch mic). One test states all of it
with LS2 and the watch's SCO both listed.

### Small reuse
Add `names(List<AudioDeviceInfo>)` next to the existing `types(List)`; `live()` and `want(Context)` both build their
arrays with them. That is the second use, and `live()` loses its two copy loops.

## Steps
1. **Red test.** `app/src/test/java/app/buddy/assistant/AudioRouteTest.java`: add
   `theLs2ConnectingWhileTheEarpieceIsReportedIsNotRefused` (the log's sequence, current 3-arg `grace`). LS2 applied;
   at 300 ms and at 2 400 ms Android reports the earpiece (`1:SM-G990W`), so `slipped(LS2, earpiece, t, grace(LS2, false,
   earpiece))` must be false. At 2 730 ms Android reports LS2: seen, so `slipped(LS2, LS2, ..., grace(LS2, true, LS2))` is
   false. `liveAttempt("", {EARPIECE, SPEAKER, SCO}, {"SM-G990W","SM-G990W","LS2 Freecom"}, {SPEAKER, A2DP},
   {"SM-G990W","LS2 Freecom"}, {}, apply)` returns the LS2 index without trying another device. Run it and see it fail on
   the 300 ms assertion. Commit alone ("031 step 1: failing test for the LS2 log sequence"), so the red run can be
   reproduced at that commit.
2. **Fix 1.** `AudioRoute.java`: `grace(String applied, boolean seen)` per the design. Update its javadoc and the call in
   `live()`. `AudioRouteTest.java`: switch step 1's test and `aHeadsetNotSeenYetGetsTimeToConnect` to the 2-arg form
   (the earpiece lines there now expect `SETUP_MS` for unseen). Replace `aHeadsetPendingWhileTheEarpieceIsReportedFallsBackToTheSpeaker`
   (it asserts the bug) with `aHeadsetThatNeverConnectsFallsToTheSpeaker`: unseen LS2, earpiece reported, not slipped at
   29 999 ms, slipped at 30 000; `refuseSlip` true; `liveAttempt` with LS2 refused returns the speaker and never offers
   index 0 (earpiece). Green.
3. **Fix 2.** `AudioRoute.java`: `names(List)`; the pure `want(saved, listening, comm, commName, out, outName)`;
   `want(Context)` builds the arrays (`getAvailableCommunicationDevices`, `getDevices(OUTPUTS)`) and delegates
   (`@TargetApi(31)`; its callers are `use()` and `reconcile()`, already API 31+); `live()` uses `types`/`names`. Test
   `autoWhileDictatingUsesTheHeadsetsCallDevice`, with comm {EARPIECE "SM-G990W", SPEAKER "SM-G990W", SCO "LS2 Freecom",
   SCO "Galaxy Watch4"} and out {SPEAKER, A2DP "LS2 Freecom"}:
   - Auto + listening -> `7:LS2 Freecom`; Auto not listening -> ""; `watch` + listening -> `7:LS2 Freecom`;
   - saved `2:SM-G990W` + listening -> `2:SM-G990W`; saved LS2, listening or not -> LS2;
   - without LS2 (out {SPEAKER}), Auto + listening -> "": never the watch's SCO, never the earpiece, and the speaker is left
     to Android's routing.
4. **Order test.** `autoOrderPutsTheHeadsetBeforeTheWatchAndNeverTheEarpiece`, the same two lists. `liveDevice("")` ->
   LS2; without LS2's media output -> speaker; earpiece only -> NONE. `pick("", false, true, true)` -> "",
   `pick("", false, false, true)` -> `watch`, `pick("", false, false, false)` -> "". `want("", true, ...)` -> LS2. (It may
   share fixtures with step 3; one commit with step 3 is fine.)
5. **Docs.** README Auto bullet (`README.md:105`): while you dictate, Auto uses a connected headset's microphone (its
   call link); read aloud stays on its media link. And: while a Bluetooth headset's call link connects (usually a second
   or two) Android can play on the earpiece, and if it never connects within 30 s the phone speaker takes over.
   `SettingsTool.java` `allowed()` Auto clause: "+ while dictating, its microphone". `AudioRoute` class javadoc: the
   same Auto sentence (031). Coder appends its own choices to `decisions.md`.

## Test plan
- **DW1.** Step 1's test fails at the step-1 commit and passes from step 2 on:
  `./gradlew testGithubDebugUnitTest --tests 'app.buddy.assistant.AudioRouteTest'`. On the phone (Verifier/Nicholas),
  a Live call with Auto and LS2 on, then one with saved = LS2. Pass when `~/.buddy/live.log` shows
  `route: 7:LS2 Freecom applied` with no `slipped from 7:LS2 Freecom` and no `2:SM-G990W applied` after it, the
  `page rtp` lines show `mic peak` above 0.000 while Nicholas talks, and the voice plays in the headset.
- **DW2.** Step 2's `aHeadsetThatNeverConnectsFallsToTheSpeaker`. The 030 tests stay as they are and pass: `liveDevice`,
  `aSavedDeviceThatIsConnectedIsChosenFirst`, `aPickThatRefusesIsTriedOnceThenAPickThatLeavesIsTriedAgainWhenBack`,
  `liveAttempt`, `liveRule`, `aSpeakerThatNeverTakesTheRouteEndsTheCall`, `aPickIsOnOnlyWhenAndroidReportsIt`, and
  `LiveWatchTest`. On the phone: LS2 off, Live call with Auto -> speaker (030's case unchanged).
- **DW3.** Step 3's test. On the phone: Auto with LS2 on, dictate in a chat (phone recognizer, and Claude/ChatGPT
  dictation if he uses them) with the phone covered or across the room, speaking quietly into the headset: the words
  come through. Then saved = LS2, the same check. After dictation, a read aloud still plays in the headset's media
  quality (not the watch).
- **DW4.** Step 4's test.
- **DW5.** PR CI green (`testGithubDebugUnitTest` in `build.yml`). `git diff main --stat` lists only `AudioRoute.java`,
  `AudioRouteTest.java`, `README.md`, `SettingsTool.java` (text) and `docs/features/031-headset-route/`.

## Risks
1. A headset that stays listed but whose call link never comes can leave a reply on the earpiece for up to 30 s before
   the speaker (at call start nothing plays for ~7 s, log `7.38`). This is the price of not guessing a setup time. A
   shorter earpiece-only bound is a one-line change if Nicholas prefers it (decisions.md).
2. Any SCO apply, healthy or not, can play on the earpiece for the second or two it takes to connect. 030 dropped the
   hold that could prevent it; the steady state is never the earpiece. README says so.
3. At dictation start the SCO takes a moment. The first fraction of a second may come from the phone mic or be silent,
   as with a saved pick today (the route that worked before 030).
4. A reading that starts while Auto is dictating into a headset: on API 33+ Android reports the SCO for assistant audio,
   so `target()` may send that reading to the watch. Rare, because `listen()` stops speech first.
5. Wired, USB and BLE headsets under Auto now also put the phone in call mode while dictating. Same mic as before; the
   volume keys act on the call stream meanwhile.
6. Dictation with saved = LS2 was not seen in a log (research: unverified). Its code is unchanged; DW3 checks it on
   the phone.

## Open questions
None blocking. Nicholas may overrule the 30 s bound of Risk 1 (decisions.md).
