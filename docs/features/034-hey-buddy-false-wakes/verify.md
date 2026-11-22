# Verification: 034-hey-buddy-false-wakes

Verifier: orchestrator (Claude Opus 5.5). CI: run 45040570273 on ac7079d, green (unit tests incl. real models, both
release APKs, F-Droid reproducibility). No JVM runs on the phone (Alpine/musl), so tests are CI-only.

| Done-when item | Evidence | Result |
|---|---|---|
| N consecutive hops >= threshold, reset on a low hop and on drop; levels 0.7x3 / 0.5x3 / 0.3x2; THRESHOLD gone | `WakeLoop.Rule`, `WakeLoop.block` DETECT, `drop()` resets `run`; `grep -r THRESHOLD app/src` empty; `WakeLoopTest` near-miss, wake, drop and level tests | pass (CI) |
| Pref read per hop, no restart; invalid reads medium | `WakeService.rule()` reads `Prefs.wakeSensitivity` each hop; `Rule.of` maps anything else to MEDIUM; `WakeLoopTest` rule-change test (round 1 F2 tightened) | pass (CI) |
| Settings -> Voice row under the switch; `hey_buddy_sensitivity`; coverage test | `SetupActivity` segmented Low/Medium/High inside `if (WakeWord.available())` after the switch; `SettingsTool.voice` Scalar; `SettingsCoverageTest` key, default, refusal | pass (CI); on-phone look pending install |
| `HeyBuddy` wake line with scores; one near-miss line per run | `WakeService.log` -> `Log.i("HeyBuddy", ...)`; exact text asserted in `WakeLoopTest` | pass (CI) |
| Real-model regression (WakeLoop seam) | `WakeIdleTest.speechInNoiseWakesTheOldRuleButNotMediumOrLow` (old rule >= 1, medium 0, low 0 on 3 min LibriSpeech + noise sd 800 seed 33); `heyBuddyWakesOnceAtEveryLevel`; clip attributed in `resources/wakeword/README.md` | pass (CI) |
| Docs; CI green | GUIDE Hey Buddy entry (Sensitivity), README Voice row already says "sensitivity in Settings", 032 verify P1 points at `logcat -s HeyBuddy` | pass |

Review: round 1 F1 (wake stamped at the last hop broke the barge-in overlap check) fixed by stamping the first
qualifying hop; F2 fixed. Round 2 APPROVE. Scope matches the brief; no service or turn changes.

Known limits (accepted, research.md): the augmented test clip (clipped, 2 hops at 0.17) misses at every level; real
detection on Nicholas's phone is the device check (032 P1), tuned with the sensitivity and the HeyBuddy lines.

VERDICT: PASS
