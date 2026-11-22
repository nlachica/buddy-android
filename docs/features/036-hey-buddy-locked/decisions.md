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
