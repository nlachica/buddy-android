# Research: 034-hey-buddy-false-wakes
Diagnosis by the orchestrator (skill diagnosing-bugs). No phone logs were available (no phone_shell); evidence is
code reading plus the real models run in CI on real speech.

## Root cause
`WakeLoop.block` wakes on a **single** 120 ms hop scoring >= `WakeFeatures.THRESHOLD` = 0.12 (no smoothing, no
patience). The hey-buddy classifier produces isolated one-hop spikes on ordinary speech in a noisy room (TV, people
talking, mic hiss); one spike past 0.12 is a wake. A real "hey buddy" holds a high score for ~4 consecutive hops.

## Evidence (CI runs 45037613941, 45038001918; diagnostic test on branch `diag/034-false-wakes`, not merged)
Real models through `WakeFeatures`, 7.1 min of LibriSpeech (73 utterances, `hf-internal-testing/librispeech_asr_dummy`,
CC BY 4.0) at 16 kHz, 3557 hops per variant. Wakes counted as "N consecutive hops >= t", 1.4 s skipped after a wake.

| Audio (7.1 min each) | max | 0.12x1 (shipped) | 0.3x2 | 0.5x2 | 0.5x3 | 0.7x3 |
|---|---|---|---|---|---|---|
| speech, clean / x3 clipped / quiet | 0.033 | 0 | 0 | 0 | 0 | 0 |
| noise only, white or brown, sd 100-3000 | 0.022 | 0 | 0 | 0 | 0 | 0 |
| speech + white noise sd 300 (2 seeds) | 0.52 | 2, 3 | 0, 0 | 0, 1 | 0, 0 | 0, 0 |
| speech + white noise sd 800 (2 seeds) | 0.75 | 21, 21 | 2, 1 | 1, 0 | 0, 0 | 0, 0 |
| speech + white noise sd 1500 (2 seeds) | 0.82 | 39, 53 | 1, 4 | 1, 1 | 0, 1 | 0, 0 |
| speech + white noise sd 3000 (2 seeds) | 0.84 | 78, 85 | 5, 8 | 2, 2 | 0, 0 | 0, 0 |

Positives (peak hops around the detection):
- `hey_buddy.wav`: 0.985 0.991 0.989 0.964 (4 hops; same with noise sd 300-1500: >= 0.958 on 4 hops).
- `hey_buddy_augmented.wav` (clipped, 3x louder): 0.021 0.172 0.120 0.009: only 2 hops >= 0.12; misses at any sane rule.
- `hey_buddy_adv.wav` (sound-alike) + noise sd 300: 0.000 **0.972** 0.010: a single-hop spike near 1.0. No threshold
  alone separates this; requiring consecutive hops does.

So: ~300 false wakes in 57 min of noisy speech under the shipped rule; 1 at 0.5x3; 0 at 0.7x3.

## Ruled out (code reading)
- A wake without a detection: `WakeWord.onWake` has one caller, `WakeService.onWake` <- `WakeLoop.block` on a score
  >= THRESHOLD. Service start, every microphone (re)open (`capture()`), a turn's end and `closeNow` all `drop()`, which
  resets `WakeFeatures` (a full 1.08 s window + 4 groups must refill: no stale audio can score). Reboot only calls
  `WakeWord.ensure` (starts the service). Live/Talk/dictation close the detector (`WakeWord.release`); `onWake` refuses
  while they run.
- Amplifier, not cause: a false wake whose first window hears nothing opens a second window (Turn.AGAIN), so each
  false wake looks like ~two listening periods. A false wake that hears TV speech sends it (strip keeps text without a
  phrase). Both are out of scope (brief non-goals); fixing the detector removes the trigger.
- Idle <-> speaking microphone switch keeps the ring (audio of two sources in one window): only around Buddy's own
  speech, not "randomly"; not changed.
