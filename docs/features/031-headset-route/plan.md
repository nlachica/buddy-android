# Plan: 031-headset-route

## Goal
Nicholas's Bluetooth headset "LS2 Freecom" (a SCO call link, plus a media output with the same name) must carry Buddy's
voice again, in and out, in phone Live calls and in dictation.

Done when:
1. In a Live call (Auto, or saved = LS2), the headset becomes the communication device and stays it. Its SCO gets the
   time Android needs to connect, instead of being refused at the first poll while Android still reports the earpiece.
   A unit test of the log's sequence fails on main.
2. A headset that never connects within its setup time still ends on the speaker, never the earpiece, and 030's
   done-when tests still pass.
3. Dictation with the headset connected (Auto, and saved = LS2) takes its input from the headset (communication device
   = its SCO). The root cause is fixed, with a test.
4. Auto's order is connected BT headset > watch > speaker, never the earpiece (test).
5. CI is green, and the diff is limited to the routing fix, its tests and its docs.

Non-goals:
- No redesign of AudioRoute, LiveWatch or the watch flow (017/026).
- No new setting or UI.
- No change to 030's no-headset behaviour (speaker, never earpiece).
- No hold or mute of the call's voice during setup (030 dropped it).
- No gating of dictation capture on headset confirmation (plan review round 1 F2).
- No change to when `use()` clears refusals.
- Read aloud under Auto stays on Android's media routing (A2DP).
- Watch, wired, USB and BLE picks keep their dictation routing.

## Design

### Root causes
- **Live.** 030's `grace()` (`AudioRoute.java:229-232`) gives a headset not seen yet 0 ms when Android reports the
  earpiece. Android reports the earpiece for the whole time a SCO link connects, so every SCO apply is refused at the
  first 300 ms poll:
  - log `0.26 7:LS2 Freecom applied`, then `0.56 slipped ... to 1:SM-G990W`, then the speaker is applied;
  - again at `7.59` -> `8.44`, and after each of Nicholas's setting changes.

  Mic peak is 0.000 for the whole call (the communication device was the speaker). The `2.99` fight (speaker re-applied
  over the LS2 that Chromium had just made the device) only follows that wrong refusal: LS2 refused and `applied` =
  speaker, so Android reporting LS2 counted as the speaker slipping.
- **Dictation.** 030 changed nothing on the non-live path (research). Under Auto, `reconcile()` calls `drop()`, which is
  normal-mode routing. Normal mode never opens a Bluetooth headset's call link, so the phone's own mic is used. That is
  016's chosen cost: "under Auto, voice input stays on Android's default mic" (`docs/features/016*/decisions.md:14`).
  Nicholas switched `audio_device` from `7:LS2 Freecom` to Auto today, so dictation lost the headset mic. Saved = LS2
  goes through unchanged code (`setCommunicationDevice` once, call mode), the route that worked before.
- **A fact the plan relies on.** In the log, the setting is Auto (`watch: setting auto -> ...`) and LS2 was applied at
  `0.26`. With saved = "", `liveDevice` reaches a SCO only through `mediaHeadset`, so LS2 also has a media output named
  "LS2 Freecom" (A2DP). Its SCO therefore counts as "a headset's call link" under the existing rule. The watch's SCO has
  no media output, so it does not.

### Fix 1 (Live): a headset not seen yet gets the setup window, whatever Android reports
`grace(applied, seen)`:
- `CONFIRM_MS` (2 s) for the speaker and for a route Android has reported once;
- `SETUP_MS` (30 s, Android's documented window for a Bluetooth call device) for a device not seen yet.

The `actual` parameter and the earpiece branch are removed. Effect on the log:
- LS2 applied at 0.26 is kept while Android reports the earpiece. `liveAttempt` finds `k.equals(applied)` and doesn't
  re-apply.
- Once Android reports LS2 (`2.99`), `seen` is set and the route holds, with 2 s on any later loss.
- The `2.99` speaker fight can't happen, because LS2 is never refused.
- A headset whose link never comes slips at 30 s and is refused, and `liveAttempt` takes the speaker (never the
  earpiece: `liveDevice` excludes it).

**The failure deadline** (round 1 F5) runs from the latest accepted request for the device. `live()`'s apply callback
sets `appliedAt = now` and `seen = false` (`AudioRoute.java:401-405`, unchanged). During a call, every `use()` forces
that request, because `:278` clears `applied` under `noEarpiece()`. `use()` runs at call start (`CodexLive.java:224`), at
page connect (`:771`), and on a setting change.

An LS2 that is already confirmed and gets re-applied (as at `7.59`) is unseen again. A transient earpiece report then
gets the 30 s window, not 2 s, and the next poll that sees LS2 confirms it again. A headset that never connects is
dropped 30 s after the last of those requests.

No playback hold while the earpiece is reported (round 1 F1): the brief's DW1 asks for the setup time "while Android
still reports the earpiece", and the hold/mute is out by Nicholas's choice in 030.

Alternatives:
- Keep 0 ms: that is the bug.
- 2 s, as in 030 round 3: a guess shorter than a slow SCO's setup time, so the same failure.
- A shorter earpiece-only bound such as 5 s: still a guess at headset setup time, and one more branch.
- Un-refuse a device Android reports (research's second fault): not needed once LS2 isn't wrongly refused; YAGNI.

### Fix 2 (dictation): Auto while dictating uses a connected Bluetooth headset's call link
`want()` is the device Android routing should take. It becomes (round 1 F4: Auto only, Bluetooth headset only):
- Auto (saved "") while `Voice.listening()`: the key of the first `BLUETOOTH_SCO` among the communication devices whose
  name matches a connected media headset output. The existing `mediaHeadset` decides this; `liveDevice` uses it to tell
  a headset's SCO from the watch's. With no such SCO, the answer is "".
- Otherwise, as now: Watch -> "", a pick -> itself, Auto -> "".

Everything after `want()` is the existing pick path: call mode, `setCommunicationDevice`, `applied`. `use()` already
reads `want(a)`, so a dictation start forces the apply, as a pick does.

- **Release** (round 1 F3). `reconcile()` only runs while voice is active, so the poll's idle branch (`:461`) gets one
  more branch. If no live call was seen (`!sawLive`), `applied != null` and `want()` is "", it calls `drop()`. Auto's
  dictation SCO is therefore released on the first poll after listening ends (<= 300 ms). A read aloud that starts
  first is also covered: its `use()` (`Voice.java:323`) gives `want()` "", keeps `applied`, and `reconcile()` drops it.
  `watchWins()` is asked again when the watch find returns (`ReadQueue.java:187`), after that drop. Read aloud keeps
  A2DP because `Voice.speak` runs with `listening()` false.
- **Testable as one pure overload**, `want(String saved, boolean listening, int[] comm, String[] commName, int[] out,
  String[] outName)`, which returns a key or "". The private `want(Context)` builds the arrays and passes
  `Voice.listening()`.
- **`listening()` is true when `reconcile` runs.** `Voice.listen` calls `use()` before it sets its listener, but `use()`
  posts to the main thread and `listen` runs on it (Bubble, ChatActivity). Claude and ChatGPT dictation set their
  listener synchronously in `listen` (`ClaudeDictation.java:43`, `CodexDictation.java:61`).

Alternatives:
- Keep 016 (phone mic): fails done-when 3.
- The SCO for the whole voice session: read aloud drops to call quality. Also, on API 33+ `target()` would see Android
  route assistant audio to the SCO, which isn't a media headset, and send readings to the watch.
- Reuse `liveDevice` (round 1 F4): it can answer a wired headset, a BLE speaker or the speaker, so call mode would spread
  to devices that don't need it.
- Gate capture on confirmation (round 1 F2): new wait/cancel code in four classes. Saved = LS2 already runs in this
  order and worked before 030.

### Order for Auto (done-when 4)
No new logic:
- **Live:** `liveDevice("")` gives the headset's call device, else the speaker, never the earpiece. LiveWatch's watch
  step (`livePick`) yields to a connected media headset.
- **Read aloud:** `pick()` gives headset > watch > speaker.
- **Dictation:** `want()` gives the Bluetooth headset's call link, else Android's routing (no watch mic, never the
  earpiece as a choice).

One test states all of it, with LS2 and the watch's SCO both listed.

### Small reuse
Add `names(List<AudioDeviceInfo>)` next to the existing `types(List)`. `live()` and `want(Context)` both build their
arrays with them. That is the second use, and `live()` loses its two copy loops.

## Steps
1. **Red test.** In `app/src/test/java/app/buddy/assistant/AudioRouteTest.java`, add
   `theLs2ConnectingWhileTheEarpieceIsReportedIsNotRefused`: the log's sequence, using the current 3-arg `grace`, with
   times counted from the latest apply.
   - LS2 applied. At 300 ms and at 2 400 ms Android reports the earpiece (`1:SM-G990W`):
     `slipped(LS2, earpiece, t, grace(LS2, false, earpiece))` is false.
   - At 2 730 ms Android reports LS2. It is seen, so `slipped(LS2, LS2, ..., grace(LS2, true, LS2))` is false.
   - Page connect (log `7.59`, round 1 F5): LS2 is re-applied and unseen again. At 850 ms after it (log `8.44`), the
     earpiece is reported, and `slipped(LS2, earpiece, 850, grace(LS2, false, earpiece))` is false. Then LS2 is
     reported, and it is not slipped.
   - `liveAttempt("", {EARPIECE, SPEAKER, SCO}, {"SM-G990W","SM-G990W","LS2 Freecom"}, {SPEAKER, A2DP},
     {"SM-G990W","LS2 Freecom"}, {}, apply)` returns the LS2 index without trying another device.

   Run it and see it fail on the 300 ms assertion. Commit it alone ("031 step 1: failing test for the LS2 log
   sequence"), so the red run can be reproduced at that commit.
2. **Fix 1.**
   - `AudioRoute.java`: `grace(String applied, boolean seen)`, per the design. Update its javadoc (including which
     request starts the deadline) and the call in `live()`.
   - `AudioRouteTest.java`: switch step 1's test and `aHeadsetNotSeenYetGetsTimeToConnect` to the 2-arg form. The
     earpiece lines there now expect `SETUP_MS` for an unseen device.
   - Replace `aHeadsetPendingWhileTheEarpieceIsReportedFallsBackToTheSpeaker` (it asserts the bug; it came from 030
     code-review round 3, not from a done-when item) with `aHeadsetThatNeverConnectsFallsToTheSpeaker`. Times count
     from the latest apply (the page-connect re-apply).
     - Unseen LS2, earpiece reported: not slipped at 29 999 ms, slipped at 30 000.
     - `refuseSlip` is true.
     - `liveAttempt` with LS2 refused returns the speaker and never offers index 0 (the earpiece).

   Green.
3. **Fix 2.** `AudioRoute.java`:
   - Add `names(List)`.
   - Add the pure `want(saved, listening, comm, commName, out, outName)`.
   - `want(Context)` builds the arrays (`getAvailableCommunicationDevices`, `getDevices(OUTPUTS)`) and delegates
     (`@TargetApi(31)`; its callers are `use()`, `reconcile()` and the poll, all API 31+ only).
   - `live()` uses `types`/`names`.
   - Add the idle-branch release in `poll`: `if (sawLive) afterLive(...); else if (applied != null &&
     want(app).isEmpty()) drop(am);`.

   Test `autoWhileDictatingUsesTheHeadsetsCallLink`, with comm {EARPIECE "SM-G990W", SPEAKER "SM-G990W", SCO
   "LS2 Freecom", SCO "Galaxy Watch4"} and out {SPEAKER, A2DP "LS2 Freecom"}:
   - Auto + listening -> `7:LS2 Freecom`; Auto not listening -> "".
   - `watch` + listening -> "" (unchanged, F4).
   - Saved `2:SM-G990W` + listening -> `2:SM-G990W`; saved LS2, listening or not -> LS2.
   - Without LS2's media output (out {SPEAKER}), Auto + listening -> "": never the watch's SCO, never the earpiece, and
     the speaker is left to Android's routing.
   - Only a wired headset (comm {EARPIECE, SPEAKER, WIRED_HEADSET}, out {SPEAKER, WIRED_HEADSET}), Auto + listening ->
     "" (unchanged, F4).
4. **Order test.** `autoOrderPutsTheHeadsetBeforeTheWatchAndNeverTheEarpiece`, with the same two lists:
   - `liveDevice("")` -> LS2; without LS2's media output -> speaker; earpiece only -> NONE.
   - `pick("", false, true, true)` -> ""; `pick("", false, false, true)` -> `watch`; `pick("", false, false, false)`
     -> "".
   - `want("", true, ...)` -> LS2.

   It may share fixtures with step 3, and one commit with step 3 is fine.
5. **Docs.**
   - README Auto bullet (`README.md:105`): while you dictate, Auto uses a connected Bluetooth headset's microphone (its
     call link), and read aloud stays on its media link. Also: while a Bluetooth headset's call link connects (usually a
     second or two), Android can play on the earpiece, and if it never connects within 30 s the phone speaker takes
     over.
   - `SettingsTool.java` `allowed()` Auto clause: "+ while dictating, a Bluetooth headset's microphone".
   - `AudioRoute` class javadoc: the same Auto sentence (031).
   - The Coder appends its own choices to `decisions.md`.

## Test plan
- **DW1.**
  - Step 1's test fails at the step-1 commit and passes from step 2 on:
    `./gradlew testGithubDebugUnitTest --tests 'app.buddy.assistant.AudioRouteTest'`.
  - On the phone (Verifier/Nicholas): a Live call with Auto and LS2 on, then one with saved = LS2. It passes when:
    - `~/.buddy/live.log` shows `route: 7:LS2 Freecom applied` (also again at page connect) with no
      `slipped from 7:LS2 Freecom` and no `2:SM-G990W applied` after it;
    - the `page rtp` lines show `mic peak` above 0.000 while Nicholas talks;
    - the voice plays in the headset.
- **DW2.**
  - Step 2's `aHeadsetThatNeverConnectsFallsToTheSpeaker`.
  - The 030 tests stay as they are and pass: `liveDevice`, `aSavedDeviceThatIsConnectedIsChosenFirst`,
    `aPickThatRefusesIsTriedOnceThenAPickThatLeavesIsTriedAgainWhenBack`, `liveAttempt`, `liveRule`,
    `aSpeakerThatNeverTakesTheRouteEndsTheCall`, `aPickIsOnOnlyWhenAndroidReportsIt`, and `LiveWatchTest`.
  - On the phone: LS2 off, Live call with Auto -> speaker (030's case, unchanged).
- **DW3.**
  - Step 3's test.
  - On the phone, Auto with LS2 on: dictate in a chat (the phone recognizer, and Claude/ChatGPT dictation if he uses
    them) with the phone covered or across the room, speaking quietly into the headset. The words come through.
  - Also a short phrase spoken as soon as the mic opens (F2). If its start is lost, that is Risk 3, the same as
    saved = LS2 before 030.
  - Then saved = LS2, the same checks.
  - With LS2 and the watch both connected, Auto: dictate in a voice chat whose reply is read aloud. The reply plays in
    the headset's media quality, not on the watch (F3).
- **DW4.** Step 4's test.
- **DW5.**
  - PR CI green (`testGithubDebugUnitTest` in `build.yml`).
  - `git diff main --stat` lists only `AudioRoute.java`, `AudioRouteTest.java`, `README.md`, `SettingsTool.java` (text)
    and `docs/features/031-headset-route/`.

## Risks
1. A headset can stay listed while its call link never comes. Its call can then play on the earpiece until 30 s after
   the latest request (the page-connect re-apply, about 7.6 s into the call in the log), then on the speaker. Nothing
   plays before the page connects (log `7.38`), so the audible worst case is about 30 s. This is the price of not
   guessing a setup time. A shorter earpiece-only bound is a one-constant change if Nicholas prefers it (decisions.md).
2. Any SCO apply, healthy or not, can play on the earpiece for the second or two it takes to connect. 030 dropped the
   hold that could prevent it. The steady state is never the earpiece, and README says so.
3. At dictation start, the SCO takes a moment. The first fraction of a second may come from the phone mic or be silent,
   as with a saved pick today (the route that worked before 030). Gating capture on it is a non-goal (F2).
4. A reading can start while Auto is dictating into a headset. On API 33+, Android reports the SCO for assistant audio,
   so `target()` may send that reading to the watch. This is rare, because `listen()` stops speech first.
5. Dictation with saved = LS2 was not seen in a log (research: unverified). Its code is unchanged, and DW3 checks it on
   the phone.

## Open questions
None blocking. Nicholas may overrule the 30 s bound of Risk 1 (decisions.md).
