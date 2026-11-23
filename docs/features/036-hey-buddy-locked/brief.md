---
type: brief
feature: 036-hey-buddy-locked
created: 2026-11-22
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 036-hey-buddy-locked

## Ask (verbatim, never edit)
Also, I noticed that Hey Buddy doesn't work when my phone screen is locked. Is there any way around this?

## Goal
RESCOPED 2026-11-22 (see Clarifications): the always-on "hey buddy" wake-word listener must run only while the
screen is on (interactive) or the phone is charging. When the screen turns off on battery, stop the mic capture,
release its wakelock(s) and let the device deep-sleep; resume promptly when the screen turns on or power is
connected. Locked-screen wake word on battery is dropped.

## Non-goals
- Making hey buddy work with the screen off on battery (the old 036 goal; superseded).
- Changing other Buddy features, settings screens or the live-call audio path (035).

## Done when
- [ ] Screen off + on battery: no mic capture by Buddy, no Buddy partial wakelock held (dumpsys power / audio show none).
- [ ] Screen on (locked or unlocked): hey buddy listens again within ~2 s.
- [ ] Charging with screen off: hey buddy keeps listening; unplugging with screen off stops it.
- [ ] The user's hey-buddy on/off setting is still respected; if off, nothing listens in any state.
- [ ] CI build green; unit tests for the state decision (screenOn, charging, enabled) -> listen.

## Clarifications
- 2026-11-22: battery stats since last charge (6 h on battery): Buddy = 1372 of 4783 mAh (29%). Foreground-service
  audio 4h11m (390 mAh), 2h22m of it with the screen off (221 mAh), fgs CPU 354 mAh, fgs wakelock 1h50m; device
  uptime = 100% of battery time (never deep-slept). Hey buddy did not even trigger while locked, so screen-off
  listening was pure cost. Nicholas chose: "listen only when the screen is on or when the phone is charging."

## Context
- 032 decided "answer while locked = yes" (approval cards still need unlock), but on the phone (S21 FE, SM-G990W)
  "hey buddy" doesn't trigger while the screen is locked. Unknown whether the detector stops (mic foreground service
  paused/killed, screen-off/Doze, Samsung battery optimization, a lock check in the code) or it detects but the turn is
  suppressed while locked. Find which with evidence, then fix. Keep 032 battery.md's budget. If the cause is only a
  phone setting, say exactly which and stop there.
- Feature 035 (live speaker loudness) runs in parallel in ../buddy-fork on its own branch; don't touch it.

## Done when
"hey buddy, what time is it" works with the screen locked and off, answered by voice, verified on the phone.
