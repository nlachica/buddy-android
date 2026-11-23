# Decisions: 036-hey-buddy-locked
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-22 - The cause: a locked turn works but can't be perceived; not a detector, Doze or phone-setting problem
- Options: (A) detector deaf while locked; (B) turn suppressed while locked; (C) reply not spoken; (D) it works and
  nothing tells the user.
- Choice: D, because the phone's own log shows a full locked turn today (screen off 13:45:49, `wake 3` 13:47:00,
  dictation, request in the chat 13:47:18, spoken reply 13:47:23), and 7 and 10 minutes into sleep the recording is
  `active, silenced:false` with the audio server's `AudioIn` wake lock held for Buddy (plan.md, Evidence). The
  failing attempt itself wasn't captured (logcat holds ~10 min), so plan.md step 0 re-tests with Nicholas.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - No wake lock, screen wake or watchdog
- Options: `PARTIAL_WAKE_LOCK` while idle; turn the screen on (`WakeActivity` style); restart-on-silence watchdog; none.
- Choice: none, because no stage was seen failing and `AudioIn` already keeps the CPU awake; each would cost battery
  (032 battery.md) or add lock-screen behaviour 032 didn't accept.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - Wake cue when locked: beep on the media stream plus vibration, whatever the ringer mode
- Options: beep + vibration when `Triggers.locked`; vibration only; beep always; follow the ringer mode.
- Choice: beep + vibration when locked, because hands-free use is from a distance, the phone is on silent today (a
  ringer-following beep would never play) and the spoken answer ignores the ringer already. Unlocked keeps the waves
  only. Open to Nicholas as plan.md Q1.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - Locked failures: errors are spoken, "nothing heard" stays silent
- Options: toast as now (unseen when locked); speak every message; speak errors only.
- Choice: errors only (`WakeWord.tell`), because a lost request should be heard, and a false wake must not make the
  phone say "Didn't catch that" to an empty room.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - Diagnosis by logcat lines, no log file
- Options: a persistent wake log file; `BuddyWake` lines for every turn outcome and dropped wake.
- Choice: log lines, because `phone_shell` reads logcat (shown today) and a read within ~10 minutes of a try is
  enough for the Verifier; transcripts are never logged, only word counts.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - The cause is not diagnosed yet; a baseline test gates all code (supersedes the first entry's choice D)
- Options: build the cue and test afterwards (round 0); make the exact locked time question on the installed build
  the first decision point.
- Choice: the baseline first (plan.md step 0), because the 13:47 trace was someone else's talk in a busy chat and the
  reported attempt left no log: it shows the stages can run locked, not why his attempt failed. What follows depends on
  where the attempt stops; a phone setting alone ends the feature there (brief). Plan review round 1, F1 and F3.
- By: Planner (claude-fable-5-2), on the Plan Reviewer's finding

## 2026-11-22 - Wake cue and spoken errors deferred (supersedes the two entries on them)
- Options: ship beep + vibration and spoken errors now; defer until Nicholas asks.
- Choice: defer, because the brief asks for a spoken answer, a sign would also fire on a false wake, and nothing shows
  the silent wait was his problem. Asked as Q1 only after a passing baseline, "leave it" being the default. If spoken
  failures ever ship: one fixed, localized sentence, never the error's own text (it can carry shell output). Round 1,
  F2 and F4.
- By: Planner (claude-fable-5-2), on the Plan Reviewer's finding

## 2026-11-22 - Diagnostic log lines only if the baseline needs them, and without error text
- Options: ship `BuddyWake` outcome lines before the baseline; ship them only for a result today's logs can't tell
  apart; lines with the error message; lines with fixed reason codes.
- Choice: only for "`wake` without `turn`" or "`turn` without a request", with literals, sequence numbers, a word count
  and the `State` name, because the other results are readable from today's log, the chat and dumpsys, and error
  messages can hold shell output.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - The baseline records the listener's state before anything changes it
- Options: require "Hey Buddy is listening" and a new chat as setup; record the notification and `BuddyWake` lines
  first and test a non-listening state as it is.
- Choice: record first, because a Buddy screen resuming calls `WakeWord.ensure`, which restarts a missing service, so
  the old setup could hide a stopped listener. Not listening: one locked attempt as is, then change only that and
  repeat. Listening: the idle-chat baseline. A stopped listener after an update or restart is retested by a restart
  with Buddy left unopened (0b). Plan review round 2, F1.
- By: Planner (claude-fable-5-2), on the Plan Reviewer's finding

## 2026-11-22 - Diagnosis and the spoken-answer check are recorded separately; closing with the cause unknown is Nicholas's call
- Options: end at "nothing reproduces" with the GUIDE sentence; run the 5-try and deep-doze rows in every branch and
  report the diagnosis on its own line.
- Choice: the second, because a pass or a guess at what differed is not a cause, and the done-when needs the trials
  either way. No reproduced failure: "could not reproduce, unresolved", closed only if Nicholas accepts (Q2). A wrong
  transcript or wrong spoken answer is a failed try and goes back to the Planner without a diagnostic release. Deep
  doze is reached naturally; forcing needs his agreement and `unforce` afterwards. Round 2, F2, F3 and F5.
- By: Planner (claude-fable-5-2), on the Plan Reviewer's finding

## 2026-11-22 Orchestrator: plan loop ended without APPROVE (exit 3)
- Accepting the diagnose-first plan as the frame; step 0 (a live locked test with Nicholas) runs before any code.
  Remaining round-3 findings go to the next Planner round, which will have step 0's evidence.

## 2026-11-22 - Rescope: every entry above is background; the plan is now the battery gate
- Options: keep the locked-screen diagnosis; follow the rescoped brief.
- Choice: the rescoped brief (listen only with the screen on or while charging). The entries above belong to
  `plan-superseded-locked.md`; "No wake lock, screen wake or watchdog" still holds.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - The gate is a new `ASLEEP` row in `WakeWord.state`; the service stays and only the recorder closes
- Options: stop and restart the foreground service on screen changes; a new state in the existing pure function,
  applied by the existing tick; reuse `PAUSED`.
- Choice: `ASLEEP`, below `TURN` and above the resume delay, because Android 14+ refuses a microphone service started
  from the background (screen-on would end at "Tap to turn on Hey Buddy"), closing the recorder is what releases the
  audio server's wake lock, and `PAUSED` would show "microphone in use" and add the 1 s resume delay.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - Screen and power are polled in the 500 ms tick, no receivers
- Options: `SCREEN_ON/OFF` and `POWER_CONNECTED/DISCONNECTED` receivers; read both in `WakeService.inputs`.
- Choice: polled, because the tick doesn't run in deep sleep and runs within 500 ms of a wake, which meets "~2 s"
  with no receiver to register or miss.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - "Screen on" is the default display's state; "charging" is plugged in
- Options: `PowerManager.isInteractive()` or `BuddyService.screenOn`; `BatteryManager.isCharging()` or
  `EXTRA_PLUGGED != 0`.
- Choice: `BuddyService.screenOn` (isInteractive stays true while a background chat's display is awake,
  `Triggers.java:1141`; the always-on display counts as off) and `EXTRA_PLUGGED` (isCharging can be false while
  plugged; the brief says "power is connected" and "unplugging"). An unreadable battery state counts as charging.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - A turn in flight finishes, and the detector stays up 2 min after a sent request
- Options: close the moment the screen goes off; let the turn finish only; turn plus `ANSWER_MS` of listening after a
  sent request; a timed partial wake lock for the answer.
- Choice: turn plus 2 min, because the lock screen goes dark seconds after a wake and the open recording is the only
  thing holding the CPU for the run (rules, schedules and the watch each hold a wake lock for theirs); it keeps
  "hey buddy, stop" and a follow-up working, adds no Buddy wake lock and is a pure row. Only a sent request starts it.
  Bends "screen off + battery: no capture" for up to 2 min after a request: asked as plan.md Q1.
- By: Planner (claude-fable-5-2)

## 2026-11-22 - No new notification text or setting
- Options: a "sleeping" notification text; a setting for the rule; neither.
- Choice: neither, because the text can't be read with the screen off and is right within one tick of screen-on, and
  the brief asks for one fixed rule.
- By: Planner (claude-fable-5-2)
