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
- No pending/active tracking on the dictation path (round 2 F3).
- No change to when `use()` clears refusals.
- Read aloud under Auto stays on Android's media routing (A2DP).
- Watch, wired, USB and BLE picks keep their dictation routing.

## Design

### Root causes
- **Live.** 030's `grace()` (`AudioRoute.java:229-232`) gives a headset not seen yet 0 ms when Android reports the
  earpiece. Android reports the earpiece while a SCO link connects, so the SCO apply is refused at the first 300 ms
  poll:
  - log `0.26 7:LS2 Freecom applied`, then `0.56 slipped ... to 1:SM-G990W`, then the speaker is applied;
  - again at `7.59` -> `8.44`, and after each of Nicholas's setting changes.

  Mic peak is 0.000 for the whole call (the communication device was the speaker). The `2.99` fight (speaker re-applied
  over the LS2 that Chromium had just made the device) only follows that wrong refusal: LS2 refused and `applied` =
  speaker, so Android reporting LS2 counted as the speaker slipping.
- **Live, a second fault (round 2 F2).** Once a headset is seen, a loss is measured from its apply, not from when it was
  last seen. One earpiece report more than 2 s after the apply refuses a working headset for the rest of its listing.
  Pre-030 code had this too. It isn't what broke LS2, but it breaks DW1's "stays".
- **Dictation.** 030 changed nothing on the non-live path (research). Under Auto, `reconcile()` calls `drop()`, which is
  normal-mode routing. Normal mode never opens a Bluetooth headset's call link, so the phone's own mic is used. That is
  016's chosen cost: "under Auto, voice input stays on Android's default mic" (`docs/features/016*/decisions.md:14`).
  Nicholas switched `audio_device` from `7:LS2 Freecom` to Auto today, so dictation lost the headset mic. Saved = LS2
  goes through unchanged code (`setCommunicationDevice` once, call mode), the route that worked before.
- **A fact the plan relies on.** In the log, the setting is Auto (`watch: setting auto -> ...`) and LS2 was applied at
  `0.26`. With saved = "", `liveDevice` reaches a SCO only through `mediaHeadset`, so LS2 also has a media output named
  "LS2 Freecom" (A2DP). Its SCO therefore counts as "a headset's call link" under the existing rule. The watch's SCO has
  no media output, so it does not.

### Fix 1 (Live): a headset not seen yet gets 5 s while Android reports the earpiece
`grace(applied, seen, actual)` keeps its signature. Only the 0 changes:
- `CONFIRM_MS` (2 s): the speaker, and a route Android has reported once (unchanged).
- A device not seen yet: `EARPIECE_MS` (5 s, new) while Android reports the earpiece (was 0), and `SETUP_MS` (30 s)
  while it reports a safe output (unchanged).

Effect on the log:
- LS2 applied at 0.26 is kept while Android reports the earpiece. `liveAttempt` finds `k.equals(applied)` and doesn't
  re-apply.
- Once Android reports LS2 (`2.99`), `seen` is set and the route holds.
- The `2.99` speaker fight can't happen, because LS2 is never refused.
- A listed headset whose link never comes plays on the earpiece for at most 5 s after the latest request. Then it slips
  and is refused, and `liveAttempt` takes the speaker (never the earpiece: `liveDevice` excludes it).

**Why 5 s.** LS2's link came within about 2.7 s: applied at `0.26`, reported at `2.99`. Buddy had already moved to the
speaker at `0.56`; Chromium's own request when the page mic opened (`2.71`) brought LS2 in.
- 2 s (030 round 3, and pre-030 for every device) is under that.
- 30 s for the earpiece case too (the previous plan) means up to 30 s of a reply on the earpiece for a listed headset
  that never connects (round 2 F1).
- 5 s covers LS2 with about 2x margin and bounds the earpiece. It's one constant: Nicholas may pick another
  (decisions.md).

**Loss clock (round 2 F2).** A new pure `lossFrom(applied, actual, from, now)` returns `now` when Android reports the
applied device and it isn't the speaker, else `from`. In `live()`, before the slip check:
`appliedAt = lossFrom(applied, curKey, appliedAt, now)`. The apply callback still sets `appliedAt = now` and
`seen = false`. So a loss counts from:
- the latest request, for a device not seen yet;
- the last poll that saw it, for a seen headset: a one-poll loss is no slip, 2 s of sustained loss is (refused, then the
  speaker);
- its apply, for the speaker, as in 030: another app's pick over it is undone at the next poll after its first 2 s.

A disconnect doesn't use this clock: the headset leaves the list, and `liveAttempt` applies the next device on that
poll (unchanged).

**The failure deadline** (round 1 F5) runs from the latest accepted request for the device. During a call, every `use()`
forces that request, because `:278` clears `applied` under `noEarpiece()`. `use()` runs at call start
(`CodexLive.java:224`), at page connect (`:771`), and on a setting change. A re-applied LS2 is unseen again, so a
transient earpiece report after the page connect re-apply gets the 5 s, and the next poll that sees LS2 confirms it
again.

No playback hold while the earpiece is reported (rounds 1-2 F1): Nicholas dropped the hold/mute in 030, and the brief's
DW1 asks for setup time "while Android still reports the earpiece". The 5 s bound is the exposure.

Alternatives:
- Keep 0 ms: that is the bug.
- 2 s: under LS2's ~2.7 s.
- 30 s whatever Android reports: up to 30 s of earpiece (round 2 F1).
- A hold or mute while the earpiece is reported: dropped by Nicholas in 030.
- Un-refuse a device Android reports (research's second fault): not needed once LS2 isn't wrongly refused; YAGNI.
- For F2, a loss counted from the first sighting only: a single report late in the call would still refuse LS2.
- Also refresh the speaker's clock: it would widen 030's earpiece exposure when another app picks over the speaker.

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
- **Pending isn't tracked** (round 2 F3). While the SCO connects, input is the phone mic. Waiting for the link would be
  capture gating (non-goal). Falling back is `drop()`, which gives the same phone mic. The state ends with the
  dictation (release above; a saved pick at session end, as since 001). The phone check is the proof that input comes
  from the headset.
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
   `theLs2ConnectingWhileTheEarpieceIsReportedIsNotRefused`: the log's sequence, using `grace` (3-arg, unchanged), with
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
   - `AudioRoute.java`:
     - add `EARPIECE_MS = 5_000` with a comment (031: LS2's link took about 2.7 s);
     - in `grace`, the earpiece branch returns `EARPIECE_MS` instead of 0, and the javadoc says 5 s while the earpiece is
       reported, 30 s while a safe output is, counted from the latest request;
     - add the pure `lossFrom(applied, actual, from, now)`;
     - in `live()`, before the slip check, set `appliedAt = lossFrom(applied, curKey, appliedAt, now)`, with a comment
       that `appliedAt` is now "the time a loss counts from".
   - `AudioRouteTest.java`:
     - `aHeadsetPendingWhileTheEarpieceIsReportedFallsBackToTheSpeaker`: `grace` is 5 000; with the earpiece reported,
       not slipped at 4 999 and slipped at 5 000 (both counted from the latest apply). `refuseSlip` and the
       `liveAttempt` -> speaker part stay, plus `assertNotEquals(0, j)` (never the earpiece).
     - `aHeadsetNotSeenYetGetsTimeToConnect` is unchanged (30 s with a safe output reported).
     - New `aConfirmedHeadsetSurvivesAOnePollLoss` (round 2 F2, no second apply), starting from `from` = 0 (the apply).
       LS2 reported at 2 730 -> `lossFrom` gives 2 730. Earpiece at 3 030 -> `lossFrom` stays 2 730, and
       `slipped(LS2, earpiece, 300, grace(LS2, true, earpiece))` is false. LS2 at 3 330 -> 3 330. Earpiece held to
       5 330 -> slipped (2 000 >= 2 000).
     - Also: `lossFrom("2:SM-G990W", "2:SM-G990W", 0, 5 000)` is 0 (the speaker keeps its apply clock), and
       `lossFrom(null, x, f, n)` is `f`.

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
     second or two), Android can play on the earpiece; if it isn't connected within 5 s, the phone speaker takes over.
   - `SettingsTool.java` `allowed()` Auto clause: "+ while dictating, a Bluetooth headset's microphone".
   - `AudioRoute` class javadoc: the same Auto sentence (031).
   - The Coder appends its own choices to `decisions.md`.

## Test plan
- **DW1.**
  - Step 1's test fails at the step-1 commit and passes from step 2 on:
    `./gradlew testGithubDebugUnitTest --tests 'app.buddy.assistant.AudioRouteTest'`.
  - Step 2's `aConfirmedHeadsetSurvivesAOnePollLoss` ("stays").
  - On the phone (Verifier/Nicholas), a Live call with Auto and LS2 on, then one with saved = LS2. Pass condition
    (round 2 F4): the phone is across the room, out of earshot of a quiet voice. Nicholas asks Live to repeat a short
    phrase spoken quietly into the headset, and Live repeats it, with its voice in the headset.
  - Diagnostics only:
    - `~/.buddy/live.log` shows `route: 7:LS2 Freecom applied` (also again at page connect) with no
      `slipped from 7:LS2 Freecom` and no `2:SM-G990W applied` after it;
    - the `page rtp` lines' `mic peak`.
  - Audible reply through confirmation (round 2 F1): while Live is speaking on the speaker (saved = `2:SM-G990W`),
    switch the setting to LS2. The voice moves to the headset within a second or two, and the log shows LS2 applied
    with no slip.
- **DW2.**
  - Step 2's tests:
    - the earpiece reported: speaker at 5 s;
    - a safe output reported: speaker at 30 s (`aHeadsetNotSeenYetGetsTimeToConnect`);
    - refused, then `liveAttempt` never offers the earpiece.
  - The 030 tests stay as they are and pass: `liveDevice`, `aSavedDeviceThatIsConnectedIsChosenFirst`,
    `aPickThatRefusesIsTriedOnceThenAPickThatLeavesIsTriedAgainWhenBack`, `liveAttempt`, `liveRule`,
    `aSpeakerThatNeverTakesTheRouteEndsTheCall`, `aPickIsOnOnlyWhenAndroidReportsIt`, and `LiveWatchTest`.
  - On the phone: LS2 off, Live call with Auto -> speaker (030's case, unchanged).
  - A listed headset that never connects can't be produced on demand, so the unit test is its proof (Risk 1).
- **DW3.**
  - Step 3's test.
  - On the phone, Auto with LS2 on. Pass condition (round 2 F3), with the phone covered or across the room: a short
    phrase spoken quietly into the headset after the mic opens comes through. Test it with the phone recognizer, and
    with Claude/ChatGPT dictation if he uses them.
  - Then saved = LS2, with the same pass condition.
  - Diagnostic: a phrase spoken right as the mic opens. If its start is lost, that is Risk 3.
  - With LS2 and the watch both connected, Auto: dictate in a voice chat whose reply is read aloud. The reply plays in
    the headset's media quality, not on the watch (round 1 F3).
- **DW4.** Step 4's test.
- **DW5.**
  - PR CI green (`testGithubDebugUnitTest` in `build.yml`).
  - `git diff main --stat` lists only `AudioRoute.java`, `AudioRouteTest.java`, `README.md`, `SettingsTool.java` (text)
    and `docs/features/031-headset-route/`.

## Risks
1. A headset can stay listed while its call link never comes. Its call can then play on the earpiece for up to 5 s
   after the latest request, then on the speaker. The page-connect re-apply (about 7.6 s into the log's call) can fall
   while the first reply plays. The headset is tried again at the next request (a setting change, the next call). A
   healthy headset that needs more than 5 s while the earpiece is reported is treated the same. Both are the price of
   one bound instead of a hold; the constant is Nicholas's to change (decisions.md).
2. Any SCO apply, healthy or not, can play on the earpiece for the second or two it takes to connect. That covers a
   setting change mid-call or a reconnect; at call start nothing plays yet. A confirmed headset whose link drops while
   it stays listed can play up to about 2 s on the earpiece before the speaker (round 2 F2's trade). A disconnect moves
   on at the next poll. The steady state is never the earpiece, and README says so.
3. At dictation start, the SCO takes a moment. The first fraction of a second may come from the phone mic or be silent,
   as with a saved pick today (the route that worked before 030). Gating capture on it is a non-goal (F2).
4. A reading can start while Auto is dictating into a headset. On API 33+, Android reports the SCO for assistant audio,
   so `target()` may send that reading to the watch. This is rare, because `listen()` stops speech first.
5. Dictation with saved = LS2 was not seen in a log (research: unverified). Its code is unchanged, and DW3 checks it on
   the phone.

## Open questions
None blocking. One for Nicholas, with a working default: up to 5 s on the earpiece while a listed headset's call link
fails to come (Risk 1). The other options are a different bound, or 030's dropped hold/mute (decisions.md).

## Orchestrator amendment (after round 3, see decisions.md)
- Add to Fix 1 / step 2: in `live()`, before the slip check, if `curKey` is non-null, not the earpiece, and in
  `liveRefused`, remove it from `liveRefused` (a pure helper is fine). Test: LS2 refused after its 5 s, speaker
  applied, then Android reports LS2: the next poll's `liveAttempt` keeps/applies LS2, not the speaker.
- R3 F2 and F3: no change (decisions.md).
