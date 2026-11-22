---
type: brief
feature: 034-hey-buddy-false-wakes
created: 2026-11-22
skipped_stages: [research, plan-review]   # a fix stage (the ask): diagnosis in research.md by the orchestrator
---
# Brief: 034-hey-buddy-false-wakes

## Ask (verbatim, never edit)
Bug in buddy-fork 2.3.18-fork.1, feature 032 hey-buddy (docs/features/032-hey-buddy-wake-word): Nicholas reports 'Buddy will randomly start listening even though I didn't say hey buddy or anything at all'. The ship threshold was 0.12 (see the report /tmp/hey-buddy-report.md: the augmented clip scored 0.17, the sound-alike 0.057). Diagnose (skill diagnosing-bugs). Look at the detector's scoring, smoothing and patience, at whether a wake can fire without a detection (service start, mic hand-off, Live or barge-in paths retriggering, a reboot), and at the phone's logs: phone_shell isn't available to you, so ask nothing; read code and tests. Fix it through the feature pipeline (a fix stage: plan, code-loop --ci, max 2 rounds), as feature 034-hey-buddy-false-wakes on a new branch. It must at least: raise the default threshold to a sane value with a requirement of N consecutive frames above it, add a sensitivity setting (low/medium/high) in Settings → Voice under the Hey Buddy switch and in buddy_settings, and log each wake with its score (tag HeyBuddy) so we can tune on the device. Keep it small. Push and open the PR, and since it's a hotfix, also merge it (squash), bump to 2.3.18-fork.2, tag, and wait for the release CI. Report in 6 lines to /tmp/hb-fix.md, including the root cause.

## Goal
Stop "hey buddy" from waking on its own. A detection needs a score at or above a threshold on N consecutive 120 ms
hops (not one hop at 0.12), with the pair set by a sensitivity setting (low / medium / high, default medium), and
every wake is logged with its score so the threshold can be tuned on the phone. Root cause and numbers: research.md.

## Non-goals
- Retraining the model, a VAD gate, a transcript check of the phrase, changing the turn (two windows) or the service.
- Any change to how a wake turn listens, sends or barges in.

## Done when
- [ ] `WakeLoop` wakes only after N consecutive scored hops >= the threshold (a hop below resets the count; a drop
      resets it too). Defaults (medium): 0.5 x 3 hops. Low: 0.7 x 3. High: 0.3 x 2. `WakeFeatures.THRESHOLD` 0.12 is gone.
- [ ] A sensitivity pref (`low|medium|high`, default `medium`) is read by the capture thread for each hop or on change
      (no service restart needed); invalid stored values read as medium.
- [ ] Settings -> Voice shows a Low / Medium / High choice right under the Hey Buddy switch (only where the switch is
      shown); `buddy_settings` key `hey_buddy_sensitivity` reads and sets it (one card per change as usual);
      SettingsCoverageTest covers the key and default.
- [ ] Each wake logs one line, tag `HeyBuddy`: the sensitivity, the threshold, N, the peak score and the scores of the
      hops of the run (e.g. `wake 3: medium (0.50 x 3) peak 0.991 hops 0.985 0.991 0.989`). A run that reached the
      threshold but not N logs one `near miss` line with its scores (for tuning), no more than one line per run.
- [ ] Regression test on the real models (testGithub, the WakeLoop seam, counting `Host.onWake`): 3 min of LibriSpeech
      speech (`librispeech-3min.ulaw`, 8-bit mu-law 16 kHz, CC BY 4.0, attributed next to it) plus white noise sd 800,
      fixed seed: the old rule (0.12 x 1) wakes at least once (shows the clip is a real challenge), medium and low wake 0
      times; `hey_buddy.wav` wakes exactly once at low, medium and high. Existing tests stay green (update the ones that
      assumed one hop).
- [ ] Docs: 032's GUIDE/README lines about Hey Buddy mention the sensitivity; feature 032's verify.md P-check list
      points at the `HeyBuddy` log line. CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
