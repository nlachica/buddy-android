# Code review: {{SLUG}}
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6.2-astra)

F1 [major] The watch releases audio before the selected headset takes the call - app/src/main/java/app/buddy/assistant/AudioRoute.java:279 compares the saved key with `applied`, which line 373 sets as soon as Android accepts the request. If LS2 returns while the watch is playing and Android still reports the speaker, the next 500 ms watch poll can stop forwarding and play the reply aloud. The fake in `LiveWatchTest` models only the requested key. -> Require both the applied key and Android’s current communication device to match the saved key before stopping the watch. Test an accepted LS2 request with the speaker still active, followed by actual LS2 confirmation. [Android routing guide](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager)

F2 [major] A speaker request that never takes effect leaves the call on the earpiece indefinitely - app/src/main/java/app/buddy/assistant/AudioRoute.java:348 exempts the speaker from refusal. In an Auto call with no watch or headset, if Android repeatedly accepts the speaker request but continues to report the earpiece, every two-second slip clears and reapplies the same speaker. Each accepted request restarts the timer, so the existing no-safe-device exit is unreachable. This exceeds the accepted brief exposure on headset disconnect. -> Allow one speaker retry, reset the retry after observing the speaker, and use the existing fallback or end-call path if the retry also fails to take effect. Test consecutive accepted requests with the actual route held on the earpiece.

F3 [major] A slow Bluetooth connection can lose the explicit pick for the rest of the call - app/src/main/java/app/buddy/assistant/AudioRoute.java:346 now runs the saved headset through the existing two-second slip check even before Android has reported that headset once. If LS2 needs three seconds to establish call audio, the first poll after two seconds refuses it and requests the speaker. LS2 stays in the refused set as long as it remains listed. The previous explicit-device path left the accepted request in place. -> Distinguish initial connection from loss of an established route. Give a pending Bluetooth request a bounded setup interval, and test a headset that becomes active after three seconds while continuously listed. [Android routing guide](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager)

F4 [minor] The device checklist still requires deleted log messages - docs/features/030-live-no-earpiece/plan.md:225 requires `page voice released` for D1, although the orchestrator removed the mechanism that emits it. A call that plays correctly on the speaker cannot satisfy that check. -> Update the active verification checklist to use the observed route and listening checks, and mark the hold-related assertions as superseded.

The three focused JUnit classes passed all 47 tests on each flavor with the routing classes and tests compiled using `javac --release 8`. The GitHub Build workflow passed for `a65bab0`, including the APK reproducibility check. Physical playback checks D1–D5 remain unverified. [CI run](https://github.com/nlachica/buddy-android/actions/runs/44692331026)

VERDICT: REVISE

### Coder response (round 1)
- F1: fixed in 1cf0745. `AudioRoute.applied(key)` (behind `pickApplied`) now also reads Android's communication device, and the
  pure `pickOn(pick, applied, actual)` needs both to equal the pick. Test `aPickIsOnOnlyWhenAndroidReportsIt`: accepted
  with the speaker still active is false, then LS2 reported is true.
- F2: fixed in 1cf0745. `refuseSlip`: the speaker's first slip is applied again, the second with no sighting in between is
  refused (`speakerSlipped`, cleared when Android reports the applied device and where `liveRefused` clears). With no
  headset, `liveAttempt` gives `NONE` and the existing "no safe device" ends the call. Test
  `aSpeakerThatNeverTakesTheRouteEndsTheCall`. The hold/page mechanism stays dropped (orchestrator).
- F3: fixed in 1cf0745. `seen` (Android has reported the applied device once) and `grace(applied, seen)`: 2 s for the speaker
  and a route seen once, 8 s for a device not yet seen. `slipped` takes the grace. Test
  `aHeadsetNotSeenYetGetsTimeToConnect`: LS2 at 3 s isn't a slip, a loss after being seen is, a link that never comes
  slips at 8 s. The rule covers any device not yet seen, not only Bluetooth (a wired apply is reported at once).
- F4: fixed in 1cf0745. The plan's D1/D4 checklist and expected log drop `page voice held/released`, marked superseded.

Tests: `AudioRouteTest`, `LiveWatchTest`, `CodexLiveTest` via the local javac+JUnit harness, 50 tests OK on github and
fdroid (compiled `--release 17`, not 8; no new API used). CI not run. Device checks D1-D5 still open.

## Round 2 - Code Reviewer (gpt-6.2-astra)

F1 [major] A pending headset can leave an entire reply on the earpiece - app/src/main/java/app/buddy/assistant/AudioRoute.java:369 waits up to eight seconds even when Android explicitly reports the earpiece. The next selection accepts the cached headset request at line 393. With no watch forwarding, the phone track keeps playing (`CodexLive.java:1023`). The new test at `AudioRouteTest.java:218` exercises exactly that route at three seconds. This can happen when LS2 connects during speech, beyond the fraction of a word accepted on disconnect. -> When a poll observes the earpiece during headset setup, protect phone playback immediately. Keep the headset request pending and resume phone output on confirmation of a safe route. Test speech already playing when LS2 is requested, with the earpiece reported for three seconds before LS2 becomes active.

F2 [major] A healthy Bluetooth pick can still be refused for the rest of the call - app/src/main/java/app/buddy/assistant/AudioRoute.java:226 gives a new Bluetooth route eight seconds. For example, if the speaker remains active and a continuously listed BLE headset needs ten seconds, line 371 refuses the headset at the first poll after eight seconds and requests the speaker. The refusal survives every subsequent poll while the headset stays listed. Android documents a setup window of up to 30 seconds. -> Allow a pending Bluetooth request the documented setup window while playback is safe. Test confirmation at ten seconds and fallback when the full window expires. [Android audio routing guide](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager#set-communication-device)

All 50 focused JUnit tests passed on each flavor with the changed Java sources and the three test classes compiled using `javac --release 8`. CI passed for `c793ee5`, including APK reproducibility. Physical playback checks D1–D5 remain unverified. [Build run](https://github.com/nlachica/buddy-android/actions/runs/44693538444)

VERDICT: REVISE

### Coder response (round 2)
- F1: rejected - the fix asks for the hold/mute of phone playback that the orchestrator dropped on purpose
  (decisions.md, "Orchestrator: plan accepted after round 3, simplified": "drop the hold/mute machinery ... Accepted
  risk: up to a fraction of a word on the earpiece"; brief non-goal on pausing). Pausing the phone track during setup is
  the old Design 4 (page change, bridge bit, resume on confirmation). Honest note: F2's longer window makes the exposure
  last as long as a headset takes to connect while speech is playing, not a fraction of a word. If Nicholas wants that
  closed, it is a new decision (hold only during a pending headset), not a patch.
- F2: fixed in this round's commit. `SETUP_MS` is now 30 s, Android's documented setup window for a Bluetooth call
  device. Test `aHeadsetNotSeenYetGetsTimeToConnect` extended: LS2 confirmed at 10 s (speaker reported meanwhile) is
  not a slip; still not a slip at 29.999 s; a slip at 30 s (the window over, LS2 refused, the speaker requested).

Tests: `AudioRouteTest`, `LiveWatchTest`, `CodexLiveTest` via the local javac+JUnit harness, 50 tests OK on github;
fdroid also 50 OK. CI not run. Device checks D1-D5 still open.

## Round 3 - Code Reviewer (gpt-6.2-astra)

F1 [major] A reply can play on the earpiece for the headset’s full 30-second setup window - app/src/main/java/app/buddy/assistant/AudioRoute.java:369 gives an unconfirmed LS2 request the full grace period even when Android reports the earpiece. Line 393 then accepts the cached request on every poll. With the watch absent, phone playback continues (CodexLive.java:1023). A focused probe with LS2 still listed and the earpiece reported returned no slip at 10,000 or 29,999 ms. The orchestrator accepted a fraction of a word when a headset disconnects. This case occurs while a headset connects and can last an entire reply, failing device check D4. -> Keep the 30-second wait while Android reports a safe output. If a poll reports the earpiece during setup, use the existing speaker fallback immediately and end the call if a safe route cannot be established. Add a regression test that checks the fallback request with speech in progress and retains the slow-headset case with the speaker active.

All 50 focused JUnit tests passed on each flavor with the routing sources and tests compiled using `javac --release 8`. CI passed at `1fc1dbb`. Physical playback checks D1–D5 remain unverified. [CI run](https://github.com/nlachica/buddy-android/actions/runs/44694806708)

VERDICT: REVISE

### Coder response (round 3)
- F1: fixed in the commit that follows this note. `grace(applied, seen, actual)` gives the 30 s setup window only while
  Android reports a safe output; if the poll reports the earpiece, the wait is `CONFIRM_MS` (2 s from the apply, so the
  apply's own moment isn't a slip). Then the existing slip path refuses the headset and `liveAttempt` takes the speaker
  (no safe device ends the call). Slow headset with the speaker active still gets 30 s. Test
  `aHeadsetPendingWhileTheEarpieceIsReportedFallsBackToTheSpeaker`; `aHeadsetNotSeenYetGetsTimeToConnect` updated for the
  new signature. Trade-off: a headset that is still connecting while Android reports the earpiece is refused after 2 s
  for the rest of its listing (it is tried again if it leaves and comes back).

## Round 4 - Code Reviewer (gpt-6.2-astra)

F1 [major] A pending headset leaves speech on the earpiece for two seconds - `app/src/main/java/app/buddy/assistant/AudioRoute.java:230` returns a 2,000 ms grace period when Android reports the earpiece. If LS2 is requested mid-reply and the first poll 300 ms later sees the earpiece, line 397 reuses the cached LS2 request. Phone playback continues. The regression test at `AudioRouteTest.java:231` explicitly expects no fallback even at 1,999 ms. The orchestrator required fallback on the first such poll. -> Give an unconfirmed headset zero grace when the poll reports the earpiece. The slip check at line 373 already runs before the new request at line 398, so this still gives Android until the next poll to apply it. Test that the first 300 ms poll requests the speaker and retain the slow-headset test with the speaker active.

All 51 focused JUnit tests passed on each flavor with the changed Java sources compiled using `javac --release 8`. CI passed at `5ee6896`. Physical playback checks D1–D5 remain unverified. [Build run](https://github.com/nlachica/buddy-android/actions/runs/44696164206)

VERDICT: REVISE

### Coder response (round 4)
- F1: fixed in the commit that follows this note. `grace(applied, seen, actual)` now returns 0 for a headset not seen yet
  when Android reports the earpiece, so the first poll after the apply (300 ms) slips, refuses the headset and
  `liveAttempt` takes the speaker (no safe device ends the call). Speaker and a route seen once keep 2 s; a slow headset
  with the speaker active keeps 30 s. Test `aHeadsetPendingWhileTheEarpieceIsReportedFallsBackToTheSpeaker` now asserts
  grace 0 and a slip at 300 ms.
  Tests: `AudioRouteTest`, `LiveWatchTest`, `CodexLiveTest` via the local javac+JUnit harness, 51 tests OK on github and
  fdroid (`--release 17`). CI not run. Device checks D1-D5 still open.
