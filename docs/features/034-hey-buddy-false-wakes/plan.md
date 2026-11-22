# Plan: 034-hey-buddy-false-wakes

## Goal
Stop "hey buddy" from waking on its own. `WakeLoop` wakes only after N consecutive scored 120 ms hops at or above a
threshold (a hop below, or a `drop()`, resets the count), the pair set by a sensitivity pref: low 0.7 x 3, medium
0.5 x 3 (default), high 0.3 x 2; `WakeFeatures.THRESHOLD` (0.12) goes. The pref is read for each hop (no service
restart), is offered as Low / Medium / High under the Hey Buddy switch in Settings → Voice and as `buddy_settings`
key `hey_buddy_sensitivity`, and invalid stored values read as medium. Each wake, and each run that reached the
threshold but not N, logs one line under tag `HeyBuddy` with its scores. A regression test on the real models proves
the old rule wakes on 3 min of noisy LibriSpeech while medium and low don't, and that `hey_buddy.wav` wakes once at
every level. Docs mention the sensitivity; CI green.

Non-goals: retraining, a VAD gate, a transcript check, any change to the turn, the service's microphone handling,
or how a wake turn listens, sends or barges in. The merge, version bump, tag and `/tmp/hb-fix.md` are the
orchestrator's, after the code loop.

## Design
**The rule is a small value, `WakeLoop.Rule` (name, threshold, hops), and the loop asks its host for it on every
scored hop.** `Rule.LOW/MEDIUM/HIGH` are constants; `Rule.of(String)` maps `low`/`high`, anything else (null too)
to `MEDIUM`. `WakeLoop.Host` gets two methods: `Rule rule()` and `void log(String line)`. `WakeService` answers
them with `Rule.of(Prefs.wakeSensitivity(this))` and `Log.i("HeyBuddy", line)`. The loop stays free of `android.*`,
so the counting and the exact log text are unit-tested, and the regression test can pass the old rule
(`new Rule("old", 0.12f, 1)`) through the same seam.

In `WakeLoop.block`, `DETECT` (a sketch; the coder may tidy it):

```java
if (score < 0) break;                          // no hop completed in this block (blocks are 40 ms, a hop 120 ms)
Rule r = host.rule();
if (r != rule) { rule = r; run = 0; }          // the sensitivity changed: start over, no line
if (score >= r.threshold) {
    if (run++ == 0) { peak = 0; hops.setLength(0); }
    peak = Math.max(peak, score);
    hops.append(' ').append(fmt(score));       // String.format(Locale.ROOT, "%.3f", score)
    if (run >= r.hops) {
        run = 0;
        mode = Mode.HOLD;  /* pre-roll as today */
        host.log("wake " + ++seq + ": " + r + " peak " + fmt(peak) + " hops" + hops);
        host.onWake(seq, now);
    }
} else if (run > 0) {
    run = 0;
    host.log("near miss: " + r + " peak " + fmt(peak) + " hops" + hops + " then " + fmt(score));
}
```

`Rule.toString()` is `medium (0.50 x 3)` (`Locale.ROOT`, so no decimal comma). `drop()` adds `run = 0` (silent).
Lines look like `wake 3: medium (0.50 x 3) peak 0.991 hops 0.985 0.991 0.989` and
`near miss: medium (0.50 x 3) peak 0.972 hops 0.972 then 0.010`.

`Prefs.wakeSensitivity(c)` returns `WakeLoop.Rule.of(stored).name`, so the screen, `buddy_settings` and the detector
all see `medium` for a bad stored value. Reading it on the capture thread 8 times a second is an in-memory lookup.

Alternatives, one line each:
- An enum of the three levels: can't express 0.12 x 1, which the regression test needs through the loop.
- A `volatile` level pushed into the loop from `WakeService.tick`: up to 500 ms stale and a second writer; no gain.
- Logging in `WakeService.onWake` from extra `onWake` arguments: the near-miss line would need its own callback
  anyway, and the text would be untested.
- Smoothing (a moving average) instead of consecutive hops: the measured rule in research.md is consecutive hops.

## Steps
1. **`WakeLoop` rule, counting, log lines; `THRESHOLD` gone.**
   `app/src/main/java/app/buddy/assistant/WakeLoop.java`: `Rule`, `Host.rule()`, `Host.log(String)`, the counting
   above, `run = 0` in `drop()`, class comment ("On a detection" → N consecutive hops).
   `WakeFeatures.java`: delete `THRESHOLD` and its comment.
   `WakeService.java`: implement `rule()` and `log()` (needs step 2's pref; do both in one commit if simpler).
   `app/src/test/.../WakeLoopTest.java`: `Rig` gets `WakeLoop.Rule rule = WakeLoop.Rule.MEDIUM` and
   `List<String> lines`; `wake()` feeds 9 blocks (hops at blocks 39, 42, 45). Update the two tests that assumed one
   hop: `wakeHoldsPreRollThenFeedsInOrder` (`wakeEnd = 45 * 1280`), `dropForgetsEverythingHeardSoItCannotRetrigger`
   (after the drop: 3 blocks, then 39 more, for the second wake). New tests:
   - two hops at 0.9 then one at 0: no wake, mode `DETECT`, one line
     `near miss: medium (0.50 x 3) peak 0.900 hops 0.900 0.900 then 0.000`; two more hops at 0.9: still no wake.
   - three hops at 0.9: one wake, line `wake 1: medium (0.50 x 3) peak 0.900 hops 0.900 0.900 0.900`.
   - `Rule.of`: `low` 0.7 x 3, `high` 0.3 x 2, `medium`, null and `loud` are `MEDIUM`; high wakes on two hops at
     0.35; low doesn't on five hops at 0.6 and logs nothing.
   - two hops at 0.9, `drop()`, then 39 blocks at 0.9 (two real hops after the refill): no wake and no line.
   - rule `LOW`, five hops at 0.6, then the rig's rule becomes `MEDIUM`: a wake three hops later (read per hop).
2. **Pref.** `Prefs.java` next to `wakeWord`: `WAKE_SENSITIVITY_DEFAULT = "medium"`, `wakeSensitivity(c)` (key
   `wake_sensitivity`, normalised through `WakeLoop.Rule.of`), `setWakeSensitivity(c, v)`.
3. **Settings row and `buddy_settings` key.**
   `SetupActivity.voiceSection`: inside `if (WakeWord.available())`, after the switch row: `fieldLabel` +
   `segmented` (`low`/`medium`/`high`, labels `models_effort_low/medium/high`, already translated) + a muted note;
   on pick `Prefs.setWakeSensitivity` and `render()`.
   `res/values/strings.xml` (English only, as the other `setup_wake*`): `setup_wake_sensitivity` "Sensitivity",
   `setup_wake_sensitivity_info` "Low wakes least by mistake. High hears a quiet or far \"hey buddy\" but wakes by
   mistake more often."
   `SettingsTool.voice`: after `hey_buddy`, `new Scalar("hey_buddy_sensitivity", () -> Prefs.wakeSensitivity(svc),
   v -> Prefs.setWakeSensitivity(svc, v), " Used from the next sound on; low wakes least by mistake.", "low",
   "medium", "high")` (no availability gate: the value is unused where Hey Buddy isn't offered).
   `SettingsCoverageTest`: the key is in `scalars(null)`, `check("HIGH")` is `high`, `max` is refused,
   `Prefs.WAKE_SENSITIVITY_DEFAULT` is `medium`.
4. **Real-model tests (testGithub).**
   `WakeIdleTest.java`: the host gets a `rule` field (default `MEDIUM`) and prints `log` lines; helpers `ulaw(name)`
   (G.711 decode) and `mix(pcm, sd, seed)` (white noise, clipped), both as in `FalseWakeDiagTest` on branch
   `diag/034-false-wakes`. New tests, each count through `loop()` + `run()` with a fresh loop and `wakes = 0`:
   - `librispeech-3min.ulaw` + white noise sd 800, **seed 33**: rule `new Rule("old", 0.12f, 1)` wakes >= 1;
     `MEDIUM` and `LOW` wake 0 times.
   - `hey_buddy.wav` wakes exactly once at `LOW`, `MEDIUM` and `HIGH`.
   `OnnxWakeEngineTest.java`: `THRESHOLD` is gone: assert the clean clip's peak >= `Rule.LOW.threshold`, and
   sound-alike, silence and noise peaks < `Rule.HIGH.threshold`; the augmented clip is printed, not asserted (it
   reaches 0.172 on two hops: a known miss at every level, research.md).
   `app/src/testGithub/resources/wakeword/README.md` already attributes the clip: no change.
5. **Docs.** `docs/GUIDE.md:49` (one sentence: wakes by mistake or misses you → Settings → Voice → Sensitivity, or
   `buddy_settings` `hey_buddy_sensitivity`; Medium is the default). `README.md:48` (the Voice row: "sensitivity in
   Settings"). `docs/features/032-hey-buddy-wake-word/verify.md` P1: replace the `THRESHOLD 0.12` remark with
   "`logcat -s HeyBuddy` shows each wake and near miss with its scores; tune with the sensitivity (034)".

After the code loop (orchestrator): squash-merge, `tools/release-check.sh`, bump to `2.3.18-fork.2`, tag, release
CI, `/tmp/hb-fix.md`.

## Test plan
| Done-when | Proof |
|---|---|
| N consecutive hops, reset on a low hop and on a drop, `THRESHOLD` gone | `WakeLoopTest` new tests (step 1); `grep -r THRESHOLD app/src` empty |
| Pref read per hop, invalid → medium | `WakeLoopTest` rule-change test and `Rule.of` test; `Prefs.wakeSensitivity` goes through `Rule.of` (code review) |
| Settings row, `hey_buddy_sensitivity`, coverage test | `SettingsCoverageTest`; the row on the phone after install (orchestrator's verify) |
| `HeyBuddy` wake and near-miss lines | `WakeLoopTest` asserts the exact text; `logcat -s HeyBuddy` on the phone after install |
| Real-model regression | `WakeIdleTest` (step 4) in CI: `./gradlew testGithubDebugUnitTest` |
| Existing tests green, docs, CI | the same CI run (`build.yml`), diff of the three doc files |

Local run where a JDK is at hand: `./gradlew testGithubDebugUnitTest --tests '*Wake*' --tests '*SettingsCoverageTest'`;
otherwise CI (`code-loop --ci`).

## Risks
- **The old rule on the 3 min clip.** The clip is byte-for-byte the first 3 min of the diagnostic run's audio
  (checked with `cmp`), and seed 33 is that run's seed for sd 800, so medium and low give 0 for certain (0 in the
  full 7.1 min). The old rule gave 21 wakes in 7.1 min; >= 1 in the first 3 is near certain but was not counted
  separately. If CI says 0: use seed 43 (the run's second seed, also 0 at 0.5 x 3 and 0.7 x 3); if still 0, stop
  and tell the orchestrator, don't tune the noise.
- **A real phrase needs 360 ms above the threshold.** The clean clip holds >= 0.96 for 4 hops, also with noise up
  to sd 1500; a clipped or far voice may miss at medium. That is what High and the `HeyBuddy` near-miss lines are
  for; the device check (032 P1) decides.
- **Log volume.** At most one line per run of hops above the threshold; in a loud room at High that is a few a
  minute, scores only, no audio or text.
- **CI time.** Three passes over 1500 hops plus three over the short clip: seconds (the diagnostic ran ~100 000
  hops in one job).

## Open questions
None.
