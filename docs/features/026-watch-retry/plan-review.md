# Plan review: 026-watch-retry
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Auto speakerphone fallback acceptance is missing - `brief.md:27-28` explicitly adds the reported Auto-to-earpiece failure and requires a test proving speakerphone output after a watch drop with no headset. The design restricts Android changes to a CodexLive accessor (`plan.md:27-28`), and neither the implementation steps nor T1-T6 addresses this requirement. This needs investigation, not simply another assertion that `toWatch() == 0`: `LiveWatch.java:123-125,243-254` already retains `took` after a fallback, so `noEarpiece("")` should remain true; `AudioRoute.java:277-280,317-355` conditionally applies the headset/speaker route. Existing `LiveWatchTest.java:148-152` already asserts that retained flag, yet the user reports earpiece output. -> Add a focused diagnosis of the actual Auto fallback routing path and the smallest correction needed, reusing `AudioRoute.live`/`liveDevice`. Plan a regression test covering the routing failure rather than only the flag, and explicitly verify speakerphone output with Auto and no headset after a watch drop (plus preservation of headset preference).

F2 [major] The proposed quiet signal can switch during audible speech - `plan.md:30-33` derives the quiet gap from the page's `v > 0.02` signal, and Risk 1 (`plan.md:165-167`) knowingly accepts a switch during a soft word. That does not meet `brief.md:19` ("never mid-utterance"). The mismatch is concrete: `LiveWatch.Gate.loud` uses 0.01 (`app/src/main/java/app/buddy/assistant/LiveWatch.java:316-320`), and `LiveWatchTest.java:325-330` explicitly proves a sample of 500 (~0.015) is audible. Such speech lasting over a second leaves `lastAudible` stale under `CodexLive.java:995-996,733-736`, so both planned quiet checks can pass while speech continues. T2 only toggles a fake boolean and cannot detect this. -> Align the retry's quiet signal with the existing audible threshold; the simplest option to assess is using 0.01 in the existing page meter rather than introducing another audio stream. Add a focused test of the real quiet-signal calculation: sustained audio around 0.015 must prevent retry/switch, and a full one-second quiet gap must permit it. Update the accessor-only assumption and risks accordingly.

F3 [minor] Device verification searches for log lines that are not emitted - `plan.md:155-156` requires the last `page voice audible`/`quiet` line before the switch. These transitions are logged directly as `voice audible` / `voice quiet` in `app/src/main/java/app/buddy/assistant/CodexLive.java:737-741`; only calls through the separate JavaScript log bridge acquire the `page ` prefix (`CodexLive.java:748-751`). Also, the first quiet sample can occur up to one meter tick after the last audible sample, while the proposed interval is measured from the latter. -> Use the actual log names and define the one-second check against the chosen quiet signal, accounting for the meter's sampling interval, so a valid switch does not fail the manual check.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - diagnosed, and fixed in `AudioRoute` (plan Design "Auto's fallback route", Step 1, T7, device check D1).
The W2 call's log (copy at `/tmp/live-w2.log`, 0-~160 s; the rotated original is gone) has no `route:` line at all.
`live()` logs every device it applies or refuses, a slip, and "no safe device". At call start `applied` is null:
`noEarpiece("")` is false before the attach, so the first `reconcile()` runs `drop()`. The first `live()` after
`playing there` (0.52 s) would therefore have logged. So `reconcile()` never took the live branch. Auto fell through
to `drop()`, which in communication mode is Android's routing, the earpiece. The flag was right (LiveWatchTest:148-152),
but AudioRoute never acted on it. The branch's guard is `!other && mode is NORMAL or IN_COMMUNICATION && noEarpiece`:
- `noEarpiece("")` is true after the attach (`took`).
- The mode passes in a Buddy call on this phone. 018's device log (`/tmp/live-018.log`, 2.3.11-fork.3) has
  `route: 2:SM-G990W applied` 0.3 s after `playing there`, and again after `connected`.
- That leaves `other`. It is a latch, set by the first `use()` of an AudioRoute session when the phone isn't in NORMAL
  mode, and cleared only by `finish()` (AudioRoute.java:238-241, 420). So a session that began during another mode
  (for example a previous call's WebView that hadn't let go of communication mode yet) keeps the live rule off for the
  whole call.

The log doesn't record `other`, so D1 confirms it. Smallest fix: the live branch stops reading `other`. A pure
`AudioRoute.liveRule(mode, noEarpiece)` makes the decision. Ringing and phone calls are still excluded by the mode, and
`other` keeps guarding the device branch. When `other` is set, the `route: … applied` line gets
` (session began in another mode)` added, so D1 shows the cause. Tests:
- `AudioRouteTest.liveRule`.
- T7 `anAutoCallThatLosesTheWatchUsesTheSpeakerNotTheEarpiece` runs the chain: a drop under Auto, then
  `noEarpiece("")`, then `liveRule(MODE_IN_COMMUNICATION, …)`, then `liveDevice` picks the speaker over the earpiece,
  or the buds' SCO when they are connected.

D1 is Auto, no headset, a call started right after a watch call, then a watch drop. It passes on the speaker
(`dumpsys audio`), never the earpiece, and the buds variant passes on the buds.

F2: accepted, with the threshold in Java rather than a changed page literal (Design "The pause signal", Steps 2-4, T2).
Each 70 ms tick, the page passes the voice's peak (the meter's `v`, already computed) through the fork's own
`Buddy.toWatch(v)` call. `LiveWatch.Call` stamps `loudAt` when the peak is above `Gate.AUDIBLE`. That is 0.01, the
constant `Gate.loud` now uses too, so one constant decides both an utterance and a pause. `quiet()` is
`now - loudAt >= QUIET_MS`, inside Call. I chose this over setting the page's 0.02 to 0.01 because:
- 0.02 is upstream's line and also drives Speaking… and the `voice audible/quiet` lines;
- a JS comparison can't be unit-tested;
- the whole calculation is now covered through the Fake's clock;
- `Out` needs no `voiceQuiet()`, and the `CodexLive.lastAudible()` accessor is dropped (`toWatch` and its page call
  are 017's fork lines).

T2 holds a 0.015 peak every 70 ms past the retry time and through the attach: no retry, no switch. A 0.005 peak
doesn't hold either. A full 1 s with no peak above 0.01 allows both. Risk 1 is now the meter's sampling: a ~21 ms
window per 70 ms tick.

F3: accepted - D2 now reads LiveWatch's own numbers: `watch: retry n (quiet X ms)`, and for a retry's move
`watch: playing there (quiet Y ms)`, with X, Y ≥ 1000. Both are measured from the last tick with a peak above 0.01.
That is the same signal and clock the decision uses, so there is no tick offset to allow for. The
`voice audible`/`voice quiet` lines (logged without a `page ` prefix) come from upstream's 0.02 status meter, and D2 no
longer uses them.
