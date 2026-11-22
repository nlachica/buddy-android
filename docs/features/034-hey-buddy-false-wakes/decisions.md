# Decisions: 034-hey-buddy-false-wakes
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-22 - Detection rule and sensitivity levels from measured CI data
- Options: raise the threshold only; threshold + N consecutive hops; add a VAD gate or a transcript check.
- Choice: threshold x N consecutive hops, levels low 0.7x3, medium (default) 0.5x3, high 0.3x2, because the false wakes
  are single-hop spikes (a sound-alike hits 0.97 for one hop) while a real phrase holds >= 0.96 for 4 hops (research.md
  table: 0.5x3 gives 1 false wake in 57 min of noisy speech vs ~300 for 0.12x1). High keeps N = 2 for distorted or
  far voices (the augmented clip reaches only 2 hops). VAD/transcript checks are bigger changes: not in a hotfix.
- By: orchestrator (Claude Opus 5.5)

## 2026-11-22 - The rule reaches WakeLoop as a value the host returns per hop
- Options: an enum of the three levels inside WakeLoop; a volatile level pushed from `WakeService.tick`; a
  `WakeLoop.Rule` (name, threshold, hops) returned by a new `Host.rule()`, with `Host.log(String)` for the lines.
- Choice: `Rule` + `Host.rule()` + `Host.log()`, because the regression test must run the old rule (0.12 x 1) through
  the same loop (an enum can't), a pushed level is up to 500 ms stale, and with the loop building the log text the
  exact lines are unit-tested without `android.*`. `Prefs.wakeSensitivity` normalises through `Rule.of` (anything
  that isn't low or high is medium), so the screen, `buddy_settings` and the detector agree.
- By: Planner (Claude Fable 5.2)

## 2026-11-22 - What ends a run, and what it logs
- Options: log a near miss whenever a run ends; only when a hop below the threshold ends it.
- Choice: a hop below the threshold logs one `near miss` line (the run's scores, then the score that ended it, which
  says how close it was); a `drop()` or a changed sensitivity resets the count silently, because those aren't
  evidence about the threshold.
- By: Planner (Claude Fable 5.2)

## 2026-11-22 - Regression test: seed 33, no drop after a wake, the augmented clip no longer asserted
- Options: a fresh seed; the diagnostic run's seed. Dropping after each wake to count every wake; leaving the loop
  held as the app does during a turn.
- Choice: white noise sd 800 with seed 33, the diagnostic run's seed: `librispeech-3min.ulaw` is byte-for-byte the
  first 3 min of that run's audio (`cmp`), so medium and low are known to give 0. The loop is left held after a
  wake (the brief needs ">= 1", "0" and "exactly once", and 0 wakes scans the whole clip either way).
  `OnnxWakeEngineTest` stops asserting that `hey_buddy_augmented.wav` detects: it reaches 0.172 on two hops, a miss
  at every level (research.md), accepted with the levels above.
- By: Planner (Claude Fable 5.2)

## 2026-11-22 - Settings labels and the buddy_settings key
- Options: new Low/Medium/High strings (English only); reuse `models_effort_low/medium/high` (translated; medium
  reads "Med"). An availability gate on `hey_buddy_sensitivity` as on `hey_buddy` = on; none.
- Choice: reuse the translated strings; no gate, because the value is simply unused where Hey Buddy isn't offered
  and a refusal would be one more rule to test.
- By: Planner (Claude Fable 5.2)
