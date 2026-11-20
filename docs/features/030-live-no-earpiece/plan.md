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
- LiveWatch's watch decision: when the voice goes to the watch, fallback and retries (018, 026).
- Playing a call on A2DP-only buds. Android plays a call on Bluetooth only over SCO (018).

## Design
Two changes, so that one code path handles every phone live call:

1. **`LiveWatch.noEarpiece()` means "a phone live call is on"** (`Call.on`). It takes no `saved` argument. The existing
   rule in `reconcile()` (`liveRule(mode, noEarpiece)`, `AudioRoute.java:286`) then sends every phone live call to
   `live()`, whatever the setting. Ringing and phone calls stay excluded by `liveRule`'s mode check. The `took` flag goes:
   `noEarpiece` was its only reader.
2. **`live()` uses the pick when it is connected.** `liveDevice` gets the saved key. It returns that device first when
   the device is among Android's communication devices now, isn't in `liveRefused`, and isn't
   `TYPE_BUILTIN_EARPIECE`. Otherwise it keeps today's order: a headset's call device, else the speaker, else `NONE`,
   which ends the call with a reason (018). "Connected and chosen" is then applied by the code that can't return the
   earpiece, and that already has a refused set and a 2-second slip check.

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
- Test `CodexLive.on()` in AudioRoute instead of `LiveWatch`: that is also true for watch-started calls, which must not
  be routed (007).
- Honour an explicit "Phone" (earpiece) pick in a call: rejected, see decisions.md. The goal's "never" wins.

Timing: `CodexLive.start` calls `AudioRoute.use()`, which only posts to the main thread. It then calls
`LiveWatch.begin()` in the same main-thread turn (`CodexLive.java:224,253`), so the first `reconcile()` already sees the
call on. When the page opens the mic, Chromium picks the earpiece. `connected()`'s `use()` (`CodexLive.java:771`) clears
`applied` and `liveRefused` and applies the device again (018). The 300 ms poll calls `live()` on every tick. That picks
up mid-call changes: a pick that connects or leaves, a headset, and a setting changed by the voice bar or `buddy_settings`.

Expected live.log for done-when 1: `watch: looking`, `watch: none found: phone`, and `route: 2:<phone model> applied`
(2 = `TYPE_BUILTIN_SPEAKER`). The route line can come before or after the watch lines, because the two run
independently.

## Steps
1. **The pick goes first in `live()`. Nothing changes on its own, because `"watch"` and `""` match no device key.**
   - `AudioRoute.java`: change the signature to `liveDevice(String saved, int[] comm, String[] commName, int[] out,
     String[] outName, Set<String> refused)`. Add a first loop that returns `i` when
     `key(comm[i], commName[i]).equals(saved)`, `comm[i] != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE`, and the key isn't in
     `refused`. The current loop follows, unchanged.
   - `liveAttempt(String saved, ...)` passes `saved` through. A refusal adds the key, as now.
   - `live(am)` passes `Prefs.audioDevice(app)`.
   - Javadocs of `liveDevice`, `liveAttempt` and `live`: the device a phone live call uses is the pick if connected (not
     the earpiece), else a headset's call device, else the speaker.
   - Tests: the `AudioRouteTest` helper `live(...)`, the `liveAttempt` calls and the two `liveDevice` calls in
     `LiveWatchTest.anAutoCallThatLosesTheWatchUsesTheSpeakerNotTheEarpiece` get `""` as the saved key. Add the new
     `AudioRouteTest` cases listed in the Test plan.
2. **Every phone live call takes the live rule.**
   - `LiveWatch.java`: the static `noEarpiece()` and `Call.noEarpiece()` (`synchronized boolean noEarpiece() { return
     on; }`) lose their parameter.
   - Remove `took`: the field (`:92`), `clear()` (`:127`) and `move()` (`:217`).
   - Javadocs: the class (`:13-17`) and `noEarpiece`, "a phone live call is on: AudioRoute keeps it off the earpiece,
     whatever the setting (030)".
   - `AudioRoute.java`: `use()` (`:246`) and `reconcile()` (`:286`) call `LiveWatch.noEarpiece()`. Update the class
     javadoc (`:27-29`) and the `liveRule` and `live` javadocs: the live rule covers every phone live call, with the pick
     first when it is connected.
   - Tests: `LiveWatchTest` calls `noEarpiece()`. Asserts that expected `false` for Auto during a call now expect `true`
     (`:130`, `:148`). Rewrite `noEarpiece()` (`:692-709`) and add the two chain tests listed in the Test plan.
3. **Docs.**
   - `README.md`, the "Audio device Auto" bullet (`:105`): add one sentence. "A live voice call started on the phone
     (Android 12+) never plays on the earpiece: it uses the device you picked while it is connected (except Phone, the
     earpiece), else a connected headset, else the phone speaker."
   - `SettingsTool.java` `allowed()` (`:365-368`): append one clause. "; on Android 12+ a phone live voice call never
     uses the earpiece: the picked device while it is connected (not the earpiece), else a headset, else the phone
     speaker".

Check each step with the local javac + JUnit harness (026's `check.sh` pattern). Use `javac --release 8`: 029's CI
failure was a Java 8 API that only `--release 8` catches.

## Test plan
| Done-when | Proof |
|---|---|
| 1. Auto, no watch, no headset: speaker | **Unit**, new `LiveWatchTest.anAutoCallWithNoWatchUsesTheSpeaker`: set `f.saved = ""` and `f.node = null`, then `begin()`. The log has `none found: phone`. `AudioRoute.liveRule(MODE_IN_COMMUNICATION, c.noEarpiece())` and `liveRule(MODE_NORMAL, c.noEarpiece())` are true. `liveDevice("", {EARPIECE, SPEAKER, SCO "Watch4"}, out {SPEAKER})` is 1, the speaker. **Device** D1. |
| 2. Pick not connected: headset, else speaker, never earpiece | **Unit**, new `LiveWatchTest.aPickedDeviceThatIsAwayUsesAHeadsetOrTheSpeaker`: set `f.saved = "7:LS2 Freecom"` and `f.node = null`, then `begin()`. `noEarpiece()` is true. `liveDevice("7:LS2 Freecom", {EARPIECE, SPEAKER}, out {SPEAKER})` is 1. With buds, `{EARPIECE, SPEAKER, SCO "Buds2"}` and out `{SPEAKER, A2DP "Buds2"}`, it is 2. **Unit** `AudioRouteTest.liveDevice`: the pick connected but in `refused` gives the speaker. **Device** D2. |
| 3. Pick connected: that device | **Unit** `AudioRouteTest.liveDevice`, with the pick `"7:LS2 Freecom"`: `{EARPIECE, SPEAKER, SCO "LS2 Freecom"}`, out `{SPEAKER}`, gives 2. That SCO has no media output, so without the pick it isn't a headset and the speaker would win. `{EARPIECE, SPEAKER, SCO "Buds2", SCO "LS2 Freecom"}`, out `{SPEAKER, A2DP "Buds2"}`, gives 3: the pick beats a headset. The pick `"2:Speaker"` with the buds connected gives 1. **Unit** `AudioRouteTest.liveAttempt`: when the pick's apply fails, the LS2 index and then the speaker's go to `apply`, in that order, and `refused` holds `7:LS2 Freecom`. **Device** D3. |
| Goal: never the earpiece | **Unit** `AudioRouteTest.liveDevice`: the pick `"1:Earpiece"` with `{EARPIECE, SPEAKER}` gives 1. `{EARPIECE}` alone gives `NONE`. **Unit** `liveAttempt` with the pick `"1:Earpiece"`: the earpiece's index is never passed to `apply`. **Unit** `LiveWatchTest.noEarpiece`: false before `begin()`. After `begin()` it is true for `""`, `"watch"`, `"2:Speaker"`, `"7:LS2 Freecom"` and `"1:Earpiece"`, with a watch found and with none. False after `end()`. |
| Unchanged elsewhere | The existing `AudioRouteTest` tests (`autoOrder`, `pickedDevice`, `headsetIn`, `livePick`, `liveRule`, `slipped`) and the retry, fallback and gate tests in `LiveWatchTest` pass unchanged, apart from the added `""` argument. |
| 4. Tests; CI green | **Local:** javac `--release 8` + JUnit over `AudioRouteTest`, `LiveWatchTest` and `CodexLiveTest`. **CI:** `build.yml` (`testGithubDebugUnitTest`, assemble, the reproducibility check) passes on the PR. |

Device checks run after the build is installed. Nicholas makes the call, and the Verifier reads `~/.buddy/live.log`, plus
`dumpsys audio`'s communication device via `phone_shell` when it is allowed:
- **D1:** Auto, the watch's Bluetooth off or out of range, no headset. Start a phone live call and ask something. The
  voice plays on the loudspeaker. live.log has `watch: none found: phone` and `route: 2:<phone> applied`, and no later
  route that ends on anything but the speaker.
- **D2:** LS2 Freecom picked and switched off. The loudspeaker plays, and live.log has `route: 2:… applied`.
- **D3:** LS2 Freecom picked and on. The voice plays in LS2, and live.log has `route: 7:LS2 Freecom applied`. If
  `route: slipped from 7:LS2 Freecom` follows, the next route line must be the speaker or a headset (Risk 1).
- **Regression, optional:** Watch picked and the watch on the wrist. The voice plays on the watch, as in 018.

## Risks
1. **Chromium's earpiece pick counts as a slip.** If Chromium picks the earpiece over an applied pick (LS2), that counts
   as a slip, and the pick is refused until the next `use()` (`connected()`, the voice bar chip). Meanwhile a headset or
   the speaker plays. 018 accepted the same trade-off for buds. Chromium picks when the page opens the mic, which is
   before `connected()`, and that `use()` clears `liveRefused` and applies the pick again. Either way it never lands on
   the earpiece. D3 checks it.
2. **A refused speaker now ends any phone live call** ("the phone speaker couldn't be used"), not only Watch or
   after-the-watch calls. No device has shown this so far (018).
3. **Buddy now sets the communication device in every phone live call.** Before, Auto without the watch left it to
   Android. A wired headset ends up the same: Android picked it anyway, and now Buddy applies it explicitly.
   A2DP-only buds now get the speaker, where they got the earpiece before.
4. **A stale `Call.on` would route a later Talk, now for any setting** (028's Q3). `on` is set only by
   `CodexLive.start`, after `ses` is set. It is cleared by `CodexLive.end`, the only path that clears `ses` after
   `begin` (`CodexLive.java:273`; the `:240` path runs before `begin`). `live()` sets only the device, never the mode.
5. **The F-Droid flavour gets the same rule,** because `LiveWatch` and `AudioRoute` are in `main`. That is intended. CI
   runs the github flavour's unit tests only, but the classes are shared, and on F-Droid `watchWins()` is always false.

## Open questions
- **For Nicholas, not blocking (the default below is what gets built):** during a phone live call, an explicit **Phone**
  (earpiece) pick is skipped, and the call uses a headset or the speaker. Phone still works for read aloud and dictation.
  This ends 001's C1 behaviour, where switching the voice bar chip to Phone mid-call gave the earpiece. If he wants Phone
  honoured in calls, it is one condition in `liveDevice`.
