# Decisions: {{SLUG}}
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-17 - How the page keeps `<audio id=out>` playing
- Options: resume in `onpause` only; in the 70 ms timer only; both, through one `keepPlaying()` with an in-flight flag.
- Choice: both, because `onpause` is event-driven (immediate even if a hidden offscreen page throttles timers to ~1 s)
  and the timer retries a rejected `play()` or a pause without an event; the flag keeps `play()` promises from piling up.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - How the rtp line shows a silent tap
- Options: a peak getter added to the shared `TAP_JS` (diagnosis A-2); the max of the existing `said()` meter over 10 s.
- Choice: `said()` max (`voice peak`), because it reads the remote stream through the same Web Audio path as the tap and
  leaves `TAP_JS`, shared with read-aloud (which works), untouched.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Cap the pause/playing log lines at 5 per call
- Options: log every pause; cap and count.
- Choice: log the first 5, count all in the rtp line, so a pause loop can't flood live.log yet stays visible.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Previous call's log name and how
- Options: `live.prev.log` (diagnosis); `live.log.1` (brief); a testable rotate helper vs one `renameTo` in `logStartNew`.
- Choice: `live.log.1` (the brief wins), one `File.renameTo` in the logger task, result ignored (a failed rename must
  not cost the new call its log); proven on the device, no helper for one line.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Page JS syntax check
- Options: none (today); a committed tool wired into CI; a one-off node script in the test plan.
- Choice: one-off node script (in plan.md Test plan), run by Coder and Verifier: a typo in `PAGE` breaks every call and
  the device check only comes after release; wiring it into CI would grow scope beyond the brief.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Log `document.visibilityState` once per call
- Options: no; add it to the first `audio context` line.
- Choice: add it: one word that tests the diagnosis's unproven "hidden offscreen WebView" idea, so a failed resume next
  time points at the right follow-up.
- By: Planner (claude-opus-5-5)
