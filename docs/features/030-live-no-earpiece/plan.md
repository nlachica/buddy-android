# Plan: 030-live-no-earpiece
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal
A phone live voice call (started on the phone, Android 12+) never plays on the earpiece. Today `AudioRoute.reconcile()`
sends a call to `live()` (a headset's call device, else the speaker, never the earpiece) only while
`LiveWatch.Call.noEarpiece(saved)` holds: Watch is picked, or Auto is picked and the watch took the voice
(`LiveWatch.java:133`). Every other call falls to `drop()` (`AudioRoute.java:291-304`). In communication mode that is
Android's own routing, which picks the earpiece. That covers Auto when no watch takes the voice, and a picked device that
isn't connected.

Evidence: `~/.buddy/live.log` of 2026-11-19 23:28 (2.3.14-fork.1, the brief's call). It logs `watch: none found: phone`
at 0.45 s and again after `watch: setting 7:LS2 Freecom -> auto` at 66.54 s. There is no `route:` line in the whole call,
so `live()` never ran.

Done when:
1. Auto, no watch, no headset: the speaker.
2. The picked device isn't connected: a connected headset, else the speaker, never the earpiece.
3. The picked device is connected: that device.
4. Tests cover it and CI is green.

Non-goals:
- Android 11 and below. 001's platform rule applies: `AudioRoute.use()` and `LiveWatch.begin()` return early there.
- Calls started from the watch. 007's Remote uses the watch's mic and speaker, and `LiveWatch.begin` is never called for
  them.
- Read aloud and dictation routing outside a live call (001, 016).
- LiveWatch's watch decision: when the voice goes to the watch, fallback and retries (018, 026). One exception: an
  explicit pick that comes back takes the voice from the watch (Design 5, done-when 3).
- Playing a call on A2DP-only buds. Android plays a call on Bluetooth only over SCO (018).
- Keeping words spoken while the voice is held for a device switch (Design 4). They are muted, not buffered.
- Silencing a switch Android makes before Buddy can see it (Design 4, Open question 2).

## Design
Five changes, so that one code path handles every phone live call, its voice plays only on a route Android has
confirmed, and a pick that comes back gets the voice:

1. **`LiveWatch.noEarpiece()` means "a phone live call is on"** (`Call.on`). It takes no `saved` argument. The existing
   rule in `reconcile()` (`liveRule(mode, noEarpiece)`, `AudioRoute.java:286`) then sends every phone live call to
   `live()`, whatever the setting. Ringing and phone calls stay excluded by `liveRule`'s mode check. The `took` flag goes:
   `noEarpiece` was its only reader.
2. **`live()` uses the pick when it is connected.** `liveDevice` gets the saved key. It returns that device first when
   the device is among Android's communication devices now, isn't in `liveRefused`, and isn't
   `TYPE_BUILTIN_EARPIECE`. Otherwise it keeps today's order: a headset's call device, else the speaker, else `NONE`,
   which ends the call with a reason (018). "Connected and chosen" is then applied by the code that can't return the
   earpiece, and that already has a refused set and a 2-second slip check.
3. **A refusal lasts while the device is listed** (plan review round 1 F1). `liveAttempt` first drops from `refused` the
   keys that aren't among the communication devices it got (`refused.retainAll(listed)`). A pick that is switched off
   slips: Android reports another device, and `live()` refuses it. But it isn't listed any more, so the same tick removes
   the refusal, and when it comes back the first loop tries it again, with no `use()`. A device that refuses or slips
   while it stays listed stays refused until it leaves or until `use()`, so the fallback is still bounded. This refines
   018's "each is tried once" to "once per connection".
4. **The phone's voice plays only on a confirmed route** (round 1 F2; round 2 F1, F2, F4).
   - **Held for the whole call unless confirmed.** `reconcile()`'s first statement sets a volatile
     `AudioRoute.held = LiveWatch.noEarpiece()`, before anything is read from Android. Only the live branch clears it:
     `held = !live(am)`. `live()` returns `confirmed(applied, key(cur))`, where `cur` is `getCommunicationDevice()` read
     at the tick's start and `applied` is what the tick ends with. So during a phone live call the voice is muted unless
     the last tick saw Android report the device Buddy applied. That covers:
     - before the first apply, and an apply that Android hasn't reported yet (the 2 s slip grace, Chromium's earpiece pick
       before `connected()`'s re-apply, a SCO link still setting up);
     - ringing and phone-call modes: the live rule is false there, `live()` isn't called, and the other call's routing is
       left as today (round 2 F2);
     - a read that throws: both callers swallow it, and `held` is already true (round 2 F2).

     Outside a phone live call `held` is false, and `finish()` sets it false too.
   - **The page.** The bridge's `toWatch` adds bit 4 while `held` is set. The page starts with `held = true` (round 2
     F1) and mutes `out` while `fwd || held`, the lever 017 uses for the watch. `CodexLive.start` posts the first
     `reconcile()` before the page loads, and the remote track needs the network's answer, so the first playback always
     follows a confirmed tick. The tap and the meter read the stream, so they are unaffected. That also means the page's
     `voice audible` line says speech arrived, not that it was heard (round 2 F5).
   - **Speaker slips are bounded** (round 2 F4). A slip refuses the applied device, as today, except the speaker's
     first slip since the last confirmed tick: the speaker is applied again once (Chromium's earpiece pick when the page
     opens the mic). A second slip in a row refuses the speaker like any device. With no headset, `liveAttempt` then
     gives `NONE`, and the existing `route: no safe device` ends the call with "the phone speaker couldn't be used". The
     pure `refuseSlip(applied, speakerSlipped)` decides this. The flag `speakerSlipped` is set by that first slip,
     cleared by a confirmed tick and wherever `liveRefused` is cleared. A held call therefore ends after about 4 s (two
     2 s graces) instead of staying silent.
   - **What it can't cover** (round 2 F1). The page doesn't choose its output device: Android routes the WebView's call
     audio. Android moves that audio as soon as the communication device changes, and Buddy sees the change at its next
     300 ms poll, and the page at its next 70 ms tick. So a switch Buddy didn't make, such as a headset that drops
     mid-sentence or Chromium re-picking the earpiece after a confirmed tick, can play up to ~0.4 s on the earpiece
     before the hold. Switches Buddy makes are held from the tick that makes them. This is Open question 2.
5. **A pick that comes back takes the voice back from the watch** (round 2 F3). `LiveWatch.Call.poll()` decides again
   only when the setting changes. So with LS2 picked but away, a reachable watch takes the voice (an away pick is Auto),
   and when LS2 comes back the page keeps forwarding to the watch and muting the phone. A new branch: while the watch has
   the voice (`watch`), the pick is an explicit device (`saved` isn't `""`), and `out.watchWins()` is now false, `poll()`
   logs `<pick> is here: phone` and calls `decide()`. Its existing `stop()` hands the voice back to the phone (017's
   handoff, with the mic closed until the watch is quiet), where `live()` has already applied the pick (Design 2). Auto
   keeps today's rule: a headset that arrives during an Auto call on the watch changes nothing. A Watch pick never
   matches, because `watchWins()` stays true while the link exists.

The order in a phone live call becomes:
1. The picked device, if connected and not the earpiece.
2. A headset's call device.
3. The speaker.

This matches 016's `pick()`, where a picked device that is here beats a headset. Watch picked behaves as today, because
`"watch"` is no device key and never matches. Auto (`""`) never matches either. Outside a live call, the device branch
and `drop()` keep serving read aloud and dictation as before.

Why not the brief's literal split (live() unless the pick is connected, then the device branch): in a call, the device
branch has three ways back to the earpiece:
- `other`: a voice session that began outside NORMAL mode skips the device and goes to `drop()`. This is 026's W2 log.
- A refused device: the branch sets `refused` and calls `dropMode()`, and no device is set.
- Chromium re-picking the earpiece after the device was applied: the branch never notices, because
  `want.equals(applied)` returns early.

`live()` handles all three: no `other` gate (since 026), `liveRefused`, and `slipped`. The behaviour is the one the brief
asks for, with fewer lines.

Alternatives considered:
- Make `drop()` call `live()` while a call is on: the same rule, hidden in a second place.
- Round 1 F1: clear every refusal when the device list changes. That retries a device that really refuses whenever
  anything else connects.
- Round 1 F2: confirm the speaker with no grace. That covers only the speaker, not SCO setup or a switch. Buffering the
  voice while it is held means far more page code for words in a ≤0.4 s gap.
- Round 2 F1: listen for Android's communication-device changes (API 31) instead of waiting for the poll. It cuts the
  window to ~0.1 s, not to zero. Offered to Nicholas (Open question 2), not built.
- Round 2 F3: decide again on every `watchWins()` change, Auto included. That changes 018/026's Auto behaviour, a
  non-goal.
- Round 2 F4: cap how long the voice may be held. It needs the same state plus a timer, and it duplicates the slip grace.
- Test `CodexLive.on()` in AudioRoute instead of `LiveWatch`: that is also true for watch-started calls, which must not
  be routed (007).
- Honour an explicit "Phone" (earpiece) pick in a call: rejected, see decisions.md. The goal's "never" wins.

Timing: `CodexLive.start` calls `AudioRoute.use()`, which only posts to the main thread. It then calls
`LiveWatch.begin()` in the same main-thread turn (`CodexLive.java:224,253`), so the first `reconcile()` already sees the
call on. When the page opens the mic, Chromium picks the earpiece. `connected()`'s `use()` (`CodexLive.java:771`) clears
`applied` and `liveRefused` and applies the device again (018). The 300 ms poll calls `live()` on every tick. That picks
up mid-call changes: a pick that connects or leaves, a headset, and a setting changed by the voice bar or `buddy_settings`.
LiveWatch's own 500 ms poll moves the voice off the watch when an explicit pick comes back (Design 5).

Expected live.log for done-when 1: `watch: looking`, `watch: none found: phone`, `route: 2:<phone model> applied`
(2 = `TYPE_BUILTIN_SPEAKER`), then `page voice released` (the page starts held). The route line can come before or after
the watch lines, because the two run independently. Around each later apply there can be `page voice held: route not
confirmed`, then `page voice released` one or two ticks later.

## Steps
1. **The pick goes first in `live()`. Nothing changes on its own, because `"watch"` and `""` match no device key.**
   - `AudioRoute.java`: change the signature to `liveDevice(String saved, int[] comm, String[] commName, int[] out,
     String[] outName, Set<String> refused)`. Add a first loop that returns `i` when
     `key(comm[i], commName[i]).equals(saved)`, `comm[i] != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE`, and the key isn't in
     `refused`. The current loop follows, unchanged.
   - `liveAttempt(String saved, ...)` passes `saved` through. A refusal adds the key, as now. Before its loop it builds
     the set of `key(comm[i], commName[i])` and calls `refused.retainAll(that set)` (Design 3; `retainAll` is Java 1.2,
     so the `--release 8` check passes).
   - `live(am)` passes `Prefs.audioDevice(app)`.
   - Javadocs of `liveDevice`, `liveAttempt` and `live`: the device a phone live call uses is the pick if connected (not
     the earpiece), else a headset's call device, else the speaker.
   - Tests: the `AudioRouteTest` helper `live(...)`, the `liveAttempt` calls and the two `liveDevice` calls in
     `LiveWatchTest.anAutoCallThatLosesTheWatchUsesTheSpeakerNotTheEarpiece` get `""` as the saved key. The existing
     `liveAttempt` test refuses only devices that are listed, so the prune keeps them and it passes unchanged. Add the new
     `AudioRouteTest` cases listed in the Test plan, the reconnect chain included.
2. **Every phone live call takes the live rule, and a pick that comes back takes the voice from the watch.**
   - `LiveWatch.java`: the static `noEarpiece()` and `Call.noEarpiece()` (`synchronized boolean noEarpiece() { return
     on; }`) lose their parameter.
   - Remove `took`: the field (`:92`), `clear()` (`:127`) and `move()` (`:217`).
   - `Call.poll()` (`:137-153`): after the setting-change branch, add `else if (watch && !saved.isEmpty() &&
     !out.watchWins()) { out.log(saved + " is here: phone"); decide(); }` (Design 5).
   - Javadocs: the class (`:13-17`), `noEarpiece` ("a phone live call is on: AudioRoute keeps it off the earpiece,
     whatever the setting (030)"), and `poll`'s comment: a setting change decides again, and so does an explicit pick
     that comes back while the watch has the voice.
   - `AudioRoute.java`: `use()` (`:246`) and `reconcile()` (`:286`) call `LiveWatch.noEarpiece()`. Update the class
     javadoc (`:27-29`) and the `liveRule` and `live` javadocs: the live rule covers every phone live call, with the pick
     first when it is connected.
   - Tests: `LiveWatchTest` calls `noEarpiece()`. Asserts that expected `false` for Auto during a call now expect `true`
     (`:130`, `:148`). Rewrite `noEarpiece()` (`:692-709`) and add the chain tests listed in the Test plan, including
     `aPickThatComesBackTakesTheVoiceFromTheWatch`.
3. **The voice plays only on a confirmed route** (Design 4).
   - `AudioRoute.java`:
     - add `private static volatile boolean held` and `static boolean held()`, with the javadoc "a phone live call's
       voice is muted: no tick has confirmed that Android's communication device is the one live() applied (030)";
     - add `private static boolean speakerSlipped`;
     - add the pure `static boolean confirmed(String applied, String actual)`, which returns `applied != null &&
       applied.equals(actual)`;
     - add the pure `static boolean refuseSlip(String applied, boolean speakerSlipped)`, which returns
       `!applied.startsWith(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER + ":") || speakerSlipped`.
   - `reconcile()`: the first statements are `boolean call = LiveWatch.noEarpiece(); held = call;`, then `liveRule(mode,
     call)`. The live branch becomes `held = !live(am); return;`.
   - `live()` returns `boolean`. Its slip branch replaces the speaker exemption (`:330`) with `if (refuseSlip(applied,
     speakerSlipped)) liveRefused.add(applied); else speakerSlipped = true;`. Its end computes `boolean ok =
     confirmed(applied, cur == null ? null : key(cur))`, with the `cur` read at its start, then `if (ok) speakerSlipped
     = false;` and `return ok;`.
   - `speakerSlipped = false` goes next to each `liveRefused.clear()` (`use`, `afterLive`, `drop`, `finish`).
     `finish()` also sets `held = false`.
   - `CodexLive.java`: the bridge's `toWatch` (`:739`) returns `LiveWatch.toWatch(voicePeak) | (AudioRoute.held() ? 4 :
     0)` for a phone call, 0 as now otherwise. Its javadoc (`:735`) gets "bit 4 = the phone's route isn't confirmed
     (mute here, 030)". In `PAGE`:
     - declare `held = true` (`:939`);
     - `route(on)` sets `muted = on || held` (`:945`);
     - `ontrack` sets `muted = rem || fwd || held` (`:1023`);
     - the timer (`:1034-1035`) reads `h = (w & 4) !== 0`. When `h !== held` it sets `held = h`, logs
       `L(h ? 'voice held: route not confirmed' : 'voice released')`, and sets `out.muted = fwd || held`.
   - Tests: the new `AudioRouteTest.confirmed` and `AudioRouteTest.aSpeakerThatNeverTakesTheRouteEndsTheCall` (Test
     plan). `reconcile()` and the page have no unit test. They are covered by code review and the device checks.
4. **Docs** (round 1 F3: the order is what the phone plays on, and the watch keeps its Auto role; round 2 F1: the window
   is stated, not hidden behind "never").
   - `README.md`, the "Audio device Auto" bullet (`:105`): add. "A live voice call started on the phone (Android 12+)
     doesn't use the earpiece: when the phone plays the voice, it uses the device you picked while it is connected
     (except Phone, the earpiece), else a connected headset, else the phone speaker. The voice pauses for a moment while
     Android switches devices, and if a headset drops mid-sentence, a fraction of a second can reach the earpiece before
     Buddy moves it."
   - `SettingsTool.java` `allowed()` (`:365-368`): append one clause. "; on Android 12+ a phone live voice call doesn't
     use the earpiece: when the phone plays the voice, the picked device while it is connected (not the earpiece), else
     a headset, else the phone speaker".

Check each step with the local javac + JUnit harness (026's `check.sh` pattern). Use `javac --release 8`: 029's CI
failure was a Java 8 API that only `--release 8` catches.

## Test plan
| Done-when | Proof |
|---|---|
| 1. Auto, no watch, no headset: speaker | **Unit**, new `LiveWatchTest.anAutoCallWithNoWatchUsesTheSpeaker`: set `f.saved = ""` and `f.node = null`, then `begin()`. The log has `none found: phone`. `AudioRoute.liveRule(MODE_IN_COMMUNICATION, c.noEarpiece())` and `liveRule(MODE_NORMAL, c.noEarpiece())` are true. `liveDevice("", {EARPIECE, SPEAKER, SCO "Watch4"}, out {SPEAKER})` is 1, the speaker. **Device** D1. |
| 2. Pick not connected: headset, else speaker, never earpiece | **Unit**, new `LiveWatchTest.aPickedDeviceThatIsAwayUsesAHeadsetOrTheSpeaker`: set `f.saved = "7:LS2 Freecom"` and `f.node = null`, then `begin()`. `noEarpiece()` is true. `liveDevice("7:LS2 Freecom", {EARPIECE, SPEAKER}, out {SPEAKER})` is 1. With buds, `{EARPIECE, SPEAKER, SCO "Buds2"}` and out `{SPEAKER, A2DP "Buds2"}`, it is 2. **Unit** `AudioRouteTest.liveDevice`: the pick connected but in `refused` gives the speaker. **Device** D2, D4. |
| 3. Pick connected: that device | **Unit** `AudioRouteTest.liveDevice`, with the pick `"7:LS2 Freecom"`: `{EARPIECE, SPEAKER, SCO "LS2 Freecom"}`, out `{SPEAKER}`, gives 2. That SCO has no media output, so without the pick it isn't a headset and the speaker would win. `{EARPIECE, SPEAKER, SCO "Buds2", SCO "LS2 Freecom"}`, out `{SPEAKER, A2DP "Buds2"}`, gives 3: the pick beats a headset. The pick `"2:Speaker"` with the buds connected gives 1. **Unit** `AudioRouteTest.liveAttempt`: when the pick's apply fails, the LS2 index and then the speaker's go to `apply`, in that order, and `refused` holds `7:LS2 Freecom`. A second call, with LS2 still listed, never passes LS2's index to `apply`: the bounded fallback. **Unit**, new `AudioRouteTest.aPickThatLeavesIsTriedAgainWhenBack` (round 1 F1), with one `refused` set and no `clear()` in between, all with the pick `"7:LS2 Freecom"` and an `apply` that succeeds: (a) `{EARPIECE, SPEAKER, SCO "LS2 Freecom"}` gives 2; (b) add `7:LS2 Freecom` to `refused`, as `live()`'s slip does when LS2 goes off, then `{EARPIECE, SPEAKER}` gives 1 and `refused` is empty; (c) `{EARPIECE, SPEAKER, SCO "LS2 Freecom"}` gives 2 again. **Unit**, new `LiveWatchTest.aPickThatComesBackTakesTheVoiceFromTheWatch` (round 2 F3): `f.saved = "7:LS2 Freecom"`, `f.wins = true` (LS2 away, the watch first), `onWatch()`, so `toWatch()` is 1. Then `f.wins = false` with `f.saved` unchanged (LS2 back) and `advance(POLL)`: bit 1 of `toWatch()` is clear, the reading is cancelled (no utterance going on), the log has `7:LS2 Freecom is here: phone` and no `setting` line. The same with `c.pcm(LOUD)` first: the reading is closed, not cancelled, and `toWatch()` is 2 (the mic waits for the watch). With `f.saved = ""` (Auto) and the watch playing, `f.wins = false` changes nothing: `toWatch()` stays 1. **Device** D3, D4, D5. |
| Goal: never the earpiece | **Unit** `AudioRouteTest.liveDevice`: the pick `"1:Earpiece"` with `{EARPIECE, SPEAKER}` gives 1. `{EARPIECE}` alone gives `NONE`. **Unit** `liveAttempt` with the pick `"1:Earpiece"`: the earpiece's index is never passed to `apply`. **Unit** `LiveWatchTest.noEarpiece`: false before `begin()`. After `begin()` it is true for `""`, `"watch"`, `"2:Speaker"`, `"7:LS2 Freecom"` and `"1:Earpiece"`, with a watch found and with none. False after `end()`. **Unit**, new `AudioRouteTest.confirmed` (round 1 F2): `confirmed(null, "1:Earpiece")` is false (startup, nothing applied). `confirmed("2:Speaker", "1:Earpiece")` is false: the voice is held, although `slipped("2:Speaker", "1:Earpiece", 1999)` is still false. `confirmed("7:LS2 Freecom", "1:Earpiece")` is false (SCO still setting up, or LS2 just dropped). `confirmed("2:Speaker", null)` is false. `confirmed("2:Speaker", "2:Speaker")` is true. **Unit**, new `AudioRouteTest.aSpeakerThatNeverTakesTheRouteEndsTheCall` (round 2 F4), with one `refused` set: `refuseSlip("7:LS2 Freecom", false)` is true; `refuseSlip("2:SM-G990W", false)` is false (the first slip: applied again); `refuseSlip("2:SM-G990W", true)` is true (the second in a row). After adding `2:SM-G990W` to `refused`, `liveAttempt("", {EARPIECE, SPEAKER "SM-G990W"}, ...)` gives `NONE` and never passes the earpiece to `apply`: the "no safe device" end. With buds also listed, it gives the buds. **Code review** (round 2 F1, F2): `held = call` is `reconcile()`'s first assignment, before any read from Android; only `held = !live(am)` clears it; `live()` returns `confirmed()` of the `cur` read at its start; the page declares `held = true` and mutes `out` while `fwd \|\| held`. The excluded modes rest on the existing `liveRule` cases (`MODE_RINGTONE`, `MODE_IN_CALL` false). **Device** D1, D3, D4: the first words and each switch, by ear. |
| Unchanged elsewhere | The existing `AudioRouteTest` tests (`autoOrder`, `pickedDevice`, `headsetIn`, `livePick`, `liveRule`, `slipped`) and the retry, fallback and gate tests in `LiveWatchTest` pass unchanged, apart from the added `""` argument. Existing `LiveWatchTest` cases set `f.wins = false` only together with a setting change, or for Auto, so Design 5's branch doesn't fire in them. |
| 4. Tests; CI green | **Local:** javac `--release 8` + JUnit over `AudioRouteTest`, `LiveWatchTest` and `CodexLiveTest`. **CI:** `build.yml` (`testGithubDebugUnitTest`, assemble, the reproducibility check) passes on the PR. |

Device checks run after the build is installed. Nicholas makes the call, and the Verifier reads `~/.buddy/live.log`, plus
`dumpsys audio`'s communication device via `phone_shell` when it is allowed. `voice audible` in live.log only shows that
speech arrived. Whether it was heard, and where, is Nicholas's ear (round 2 F5).
- **D1:** Auto, the watch's Bluetooth off or out of range, no headset. Start a phone live call and ask something.
  Nicholas listens at the earpiece for the first reply, then holds the phone away. The reply plays on the loudspeaker
  from its first word, and nothing comes from the earpiece. live.log has `watch: none found: phone`, `route: 2:<phone>
  applied`, and `page voice released` after that route line. Every later `page voice held` is followed by `page voice
  released`, and no route ends on anything but the speaker.
- **D2:** LS2 Freecom picked and switched off, the watch unreachable (its Bluetooth off), no other headset. The
  loudspeaker plays, and live.log has `route: 2:… applied`.
- **D3:** LS2 Freecom picked and on (the watch's Bluetooth off). The voice is heard in LS2 from the first word to the
  end, and live.log has `route: 7:LS2 Freecom applied`. Any `route: slipped from 7:LS2 Freecom`, a later speaker route
  while LS2 stays on, or the voice heard from the phone fails D3 (Risk 1).
- **D4 (round 1 F1, F2; round 2 F1):** as D3, in one call, with no voice bar or settings change. Ask for a long answer
  (a story). Mid-answer, switch LS2 off: the voice moves to the loudspeaker. Switch LS2 back on mid-answer: the voice
  returns to LS2. Nicholas listens at the earpiece around each switch. The voice may pause. When LS2 comes back (a
  switch Buddy makes), nothing comes from the earpiece. When LS2 goes off (a switch Android makes first), at most a
  split second may come from the earpiece (Open question 2). A word there, or anything longer than about half a
  second, fails D4. live.log has, in order, `route: 7:LS2 Freecom applied`, `route: slipped from 7:LS2 Freecom to …`,
  `route: 2:… applied` and `route: 7:LS2 Freecom applied`, and a `page voice held` / `page voice released` pair may
  come at each switch.
- **D5 (round 2 F3):** LS2 Freecom picked and switched off, the watch on the wrist and reachable. Start a phone live
  call. The voice plays on the watch. Switch LS2 on, with no voice bar or settings change. The current reply may finish
  on the watch. The next reply is heard in LS2, and nothing more comes from the watch. live.log has `watch: 7:LS2 Freecom
  is here: phone`, `watch: stop: the setting moved`, `page voice on the phone` and `route: 7:LS2 Freecom applied`.
- **Regression, optional:** Watch picked and the watch on the wrist. The voice plays on the watch, as in 018.

## Risks
1. **Chromium's earpiece pick counts as a slip.** If Chromium picks the earpiece over an applied pick (LS2) while LS2
   stays connected, that counts as a slip, and the pick is refused until it leaves or the next `use()` (`connected()`,
   the voice bar chip). Meanwhile a headset or the speaker plays. 018 accepted the same trade-off for buds: a failed
   SCO link looks the same to Buddy. Chromium picks when the page opens the mic, which is before `connected()`, and that
   `use()` clears `liveRefused` and applies the pick again. It never lands on the earpiece, but D3 now fails on it,
   because a healthy LS2 must play. If D3 shows a later slip, that comes back as a finding, not a pass.
2. **A refused speaker now ends any phone live call** ("the phone speaker couldn't be used"), not only Watch or
   after-the-watch calls. The speaker is now refused after two slips in a row with no confirmed tick between them
   (Design 4). If Chromium re-picked the earpiece again right after the re-apply, before a tick saw the speaker, a
   working speaker would end the call. No device has shown a speaker slip so far (018's log), and `connected()`'s
   `use()` resets the flag.
3. **Buddy now sets the communication device in every phone live call.** Before, Auto without the watch left it to
   Android. A wired headset ends up the same: Android picked it anyway, and now Buddy applies it explicitly.
   A2DP-only buds now get the speaker, where they got the earpiece before.
4. **A stale `Call.on` would route a later Talk, now for any setting** (028's Q3). `on` is set only by
   `CodexLive.start`, after `ses` is set. It is cleared by `CodexLive.end`, the only path that clears `ses` after
   `begin` (`CodexLive.java:273`; the `:240` path runs before `begin`). `live()` sets only the device, never the mode.
   A stale `on` would also hold the page's voice, but no page exists without a call.
5. **The F-Droid flavour gets the same rule,** because `LiveWatch` and `AudioRoute` are in `main`. That is intended. CI
   runs the github flavour's unit tests only, but the classes are shared, and on F-Droid `watchWins()` is always false,
   so Design 5's branch never fires there.
6. **Held voice is lost voice** (Design 4). Words spoken while `held` is set are muted, not delayed. That is at most
   ~370 ms after each apply (the 300 ms poll plus the page's 70 ms tick), or longer while a SCO link sets up. A ringing
   or phone-call mode mutes the live call's voice until the mode returns. A device whose `getCommunicationDevice()` key
   never equals the applied key is held until the 2 s slip refuses it. The speaker is applied again once and then
   refused, so the call ends with its reason after about 4 s, instead of playing on the earpiece or staying silent.
   018's device log (026 plan.md:63) applied `route: 2:SM-G990W` with no slip after it, so the speaker confirms on this
   phone.
7. **A switch Buddy didn't make can reach the earpiece for up to ~0.4 s** (Design 4, Open question 2). This happens
   when a headset drops mid-sentence, or if Chromium re-picks the earpiece after a confirmed tick (not seen so far).
   D4 bounds it by ear.
8. **The voice doesn't return to the watch by itself** (Design 5). When the pick comes back, the voice leaves the watch.
   If the pick then goes away again, `stop()` armed no retry, so the phone keeps the voice (speaker or a headset) until
   the setting changes. That is 018's rule for a setting change, and it never lands on the earpiece.

## Open questions
1. **For Nicholas, not blocking (the default below is what gets built):** during a phone live call, an explicit
   **Phone** (earpiece) pick is skipped, and the call uses a headset or the speaker. Phone still works for read aloud
   and dictation. This ends 001's C1 behaviour, where switching the voice bar chip to Phone mid-call gave the earpiece.
   If he wants Phone honoured in calls, it is one condition in `liveDevice`.
2. **For Nicholas, not blocking (round 2 F1; the default is what gets built):** "never on the earpiece" holds for every
   route Buddy picks and for the start of each reply. It can't hold for a switch Android makes on its own. When a
   headset drops mid-sentence, Android moves the call to the earpiece at once. Buddy sees it at its next poll, and
   muting and re-routing take up to ~0.4 s, which may play there. The page can't pin its output to a device. Default:
   accept this, and the README says so. If he wants it shorter, Buddy can listen for Android's communication-device
   change instead of polling. That cuts the window to ~0.1 s (the page's tick), not to zero, for a few more lines.
