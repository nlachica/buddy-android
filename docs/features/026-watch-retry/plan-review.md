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

## Round 2 - Plan Reviewer (gpt-6-astra)

Round 1 F1-F3 are addressed at the plan level: the Auto routing change and checks are now explicit, the retry uses the gate's audible threshold, and device verification uses the retry's own quiet-time logs. The inferred routing cause still needs D1's device evidence as the plan acknowledges.

F1 [major] The added watch-started-call requirements have no implementation or verification plan - `brief.md:30-35` requires logged frame/peak counters at both ends, a fix for silent watch microphone input, idle Listening status using the phone's shared status code, and five rotated live logs. Yet `plan.md:30` still excludes watch-side changes, steps 1-6 (`plan.md:155-198`) cover only routing/retries/docs, and `plan.md:288-302` explicitly leaves the addition blocking and unplanned. These are real remaining gaps: `wear/src/main/java/app/buddy/assistant/wear/Call.java:215-250` selects VOICE_COMMUNICATION first and drops gated frames without counters; `app/src/main/java/app/buddy/assistant/CodexLive.java:866-871` queues incoming PCM without counters; `CodexLive.java:178-185` retains only one previous log. The status question is also grounded in the code: `CodexLive.java:408-421` prioritizes `ses.running` and sends the same resulting string to both phone UI and Remote, while `app/src/watchlink/java/app/buddy/assistant/WatchCall.java:159-160` forwards it unchanged. Thus existing mirroring alone does not establish the requested idle Listening behavior. -> Complete the targeted research requested in Open questions, then add concrete steps and checks for all four requirements and remove the stale watch-side non-goal. If microphone diagnosis needs a device run with counters first, specify that dependency and retain the actual silence fix as an outstanding acceptance gate. Resolve the shared-status behavior with the orchestrator against Nicholas's latest clarification; preserve one shared status source rather than adding watch-specific logic. Include verification of microphone capture versus gated/sent/received frames, a successful watch-started spoken exchange, identical idle status on both UIs while the chat works, and retention across more than five calls. An observability-only stage must not be treated as completion of the expanded brief.

VERDICT: REVISE

### Planner response (round 2)

F1: accepted. The four watch-call requirements now have a design, steps, tests and device checks, and the stale non-goal
is gone. The research was answered from the code (plan Design, "The watch-started call"; Open questions has no blocker
left).
- *Non-goal.* "Watch-side changes" is now "watch changes beyond `Call.mic`". Done-when 6-9 restate the brief's items.
- *Counters* (step 7 phone, step 8 watch).
  - Watch: the source it opened, logged once. Then every 5 s: read, sent and gated frames, the peak, and
    `isClientSilenced()`.
  - Phone: every 10 s, from the watchdog: the frames `push()` got, their peak, and the frames dropped because the page
    didn't pull them. An explicit `0 frames` line when nothing arrives.
  - T9 covers the phone's peak. D4a checks both ends' lines and that their frame counts agree.
- *Silence fix: a device run first, with the fix as an open acceptance gate* (step 10, Risk 9). The code doesn't support
  a fix chosen now. The watch's `Call`, `Speaker` and `WatchCall` are unchanged since v2.3.9-fork.4, with which a watch
  call worked using the same VOICE_COMMUNICATION-first source (007 verify.md). The gate is unlikely: `audibleUntil` only
  follows audible blocks, and call B's voice peak was 0.000 throughout. The page's `mic peak` is a 21 ms snapshot, so
  "~0" doesn't prove silence. So:
  - stage 1 ships the counters with the retry and route work;
  - D4a reads them;
  - stage 2 applies a table decided now. Its row 1 is `MIC` before `VOICE_COMMUNICATION`; the other rows go back to
    the Researcher.
  Done-when 7 (D4b: a watch-started call with two spoken exchanges) stays open until it passes. Stage 1 is not
  completion.
- *Status.* One shared source, no change. The code already sends the same `t` to the phone's bar and the watch. The
  phone says "Listening…" while the chat works only while it hears the user, and call B heard nothing, so both said
  "Working on it…". Nicholas's latest, firm, clarification ("reuse the phone's existing call-status code … same string
  … no new status logic") is that code. The literal "Listening… while silent and the chat is busy" would change both
  UIs. It is left to Nicholas as a non-blocking question for the orchestrator to relay, defaulting to unchanged.
  D5 checks that both UIs show the same string while idle, while the chat works, while he speaks, and while the voice
  speaks.
- *Five rotated logs* (step 6). `rotate(log, 5)` shifts `live.log.1` to `live.log.5` at each call's start. T8 runs 7
  calls and keeps the newest 5 rotated logs. D5 step 4 checks the files on the device after 6 or more calls.
