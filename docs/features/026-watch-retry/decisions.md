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

## 2026-11-18 - Auto's fallback route: the live rule doesn't depend on the voice session's `other` latch
- Options: re-read `other` at each call start; clear the latch in `afterLive`; take `other` out of the live branch's
  condition (pure `AudioRoute.liveRule(mode, noEarpiece)`).
- Choice: take it out. W2's log has no `route:` line in the whole call, so `live()` never ran. `noEarpiece("")` was
  true after the attach, and the mode passes in a Buddy call on this phone (018's log applied the speaker), which
  leaves the latch. It is set when an AudioRoute session begins outside NORMAL mode and kept until `finish()`, and with
  it Auto fell through to `drop()`, which is the earpiece. `noEarpiece` is only true during Buddy's own phone live call,
  and the mode check still keeps ringing and phone calls out. `use()` can't tell a call start from dictation, and
  `afterLive` runs after a call, not before one, so the other two options don't hold. `live()`'s log line notes
  "(session began in another mode)" so the device check can confirm the cause. Cost: another app's VoIP call already
  on when Buddy's call starts is no longer left alone (plan Risk 7).
- By: Planner (claude-opus-5-5), plan-review round 1 F1

## 2026-11-18 - The pause signal is the voice's peak, passed through `toWatch(v)`, against the gate's threshold (replaces the "`lastAudible`, read through `Out.voiceQuiet()`" entry above)
- Options: the page's `voice` flag (`v > 0.02`) through `CodexLive.lastAudible`; the same with the page literal
  changed to 0.01 (review F2's suggestion); the meter's peak passed to Java in the fork's per-tick `Buddy.toWatch(v)`,
  compared with `Gate.AUDIBLE` (0.01) in `LiveWatch.Call`.
- Choice: the peak through `toWatch`. 0.02 let speech between 0.01 and 0.02 count as a pause, which breaks "never
  mid-utterance". The page literal is upstream's and drives Speaking… and the `voice audible/quiet` lines, and a JS
  comparison can't be unit-tested. With the peak, one Java constant decides utterances and pauses, the calculation runs
  on the Fake's clock, `Out` gets no new method, CodexLive gets no accessor, and only 017's fork lines change. The retry
  and the retry's move log the quiet time (`retry n (quiet X ms)`, `playing there (quiet Y ms)`), so the device check
  reads the same number the decision used.
- By: Planner (claude-opus-5-5), plan-review round 1 F2/F3
