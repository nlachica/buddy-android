# Decisions: 026-watch-retry
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-18 - What arms a retry
- Options: every `fallback()` only (diagnosis §3); every fallback plus a retry that finds no watch; any time the watch
  wins while the voice is on the phone (call start included).
- Choice: every fallback (refused, broken, stopped by the watch, the 30-s give-up, a refusal before attach included)
  plus a retry whose find returns none. Without the second, a retry during a brief unreachability ends the
  retrying and the "3 tries" are not real. `stop()` (setting moved away) and a "none found" at call start or after a
  setting change don't arm, so Auto with no watch around stays one find, as today.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Retry spacing and cap
- Options: fixed 5 s after the drop or the failed retry; exponential backoff (5/10/20 s); reset the count after a
  successful return.
- Choice: fixed 5 s (polled every 500 ms), at most 3 retries per call whatever their outcome, reset only by `begin()`.
  The brief fixes 3 per call and "≥5 s after a drop". Fixed spacing keeps "back within ~10 s once reachable" while
  tries remain, and backoff would break it at the third try. Cost: an outage longer than ~15-45 s stays on the phone.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - The pause gates both the retry's start and the switch, for retries only
- Options: gate only the start of the retry (diagnosis §3); also gate the switch at attach (the channel opens and
  waits, the voice moves at the next pause); gate every attach, including call start and setting changes.
- Choice: start and switch, retries only. Find plus open can take up to 13 s, so a start-only gate can still switch
  mid-word, and "never mid-utterance" is a done-when. A waiting channel idles as 018's readings opened ahead already
  do. Call-start and setting-change attaches stay immediate (the user's own change, 018's tests).
- By: Planner (claude-opus-5-5)

## 2026-11-18 - The pause signal is CodexLive's `lastAudible`, read through `Out.voiceQuiet()`
- Options: `LiveWatch.pcm()` (gets nothing after a fallback: the page tap forwards only while `fwd`); make the tap
  always forward (page edit, bridge traffic on phone-only calls); the page meter's existing `lastAudible`, via a
  `voiceQuiet()` in CodexLive (diagnosis) or a plain `lastAudible()` accessor.
- Choice: a `static long lastAudible()` accessor in CodexLive, with the 1 s (`QUIET_MS`) and the comparison in
  `LiveWatch.Phone`. The policy stays in LiveWatch next to `RETRY_MS`, the upstream file gets one line, and the fake
  `Out` toggles one boolean. Cost: the meter's threshold is 0.02 while the gate's is 0.01 (plan Risk 1).
- By: Planner (claude-opus-5-5)

## 2026-11-18 - A retry waits while the watch doesn't win; a setting change cancels it
- Options: let `decide()` no-op and use up the try; check `watchWins()` in the retry condition and keep it armed;
  cancel the retry on a setting change or keep it.
- Choice: check `watchWins()` last in the retry condition and keep the retry armed while it is false (Auto with a
  headset that came mid-call), so no try is used up. A setting change sets `retryAt = 0`: its own `decide()` is the
  fresh decision (018's rule), and its attempt is neither counted nor pause-gated.
- By: Planner (claude-opus-5-5)
