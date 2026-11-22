---
type: brief
feature: 033-watch-worn-audio
created: 2026-11-21
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 033-watch-worn-audio

## Ask (verbatim, never edit)
For Buddy's Auto audio input, use the Watch only when it detects that I'm wearing the watch. If I'm not wearing the watch it'd use speakerphone. Priority: Bluetooth, then Watch (if wearing), then speakerphone, then last resort the phone held up to the ear speaker.

## Goal
Buddy's Auto audio device (features 001/016, with 012's Watch, 018's Live-on-watch, 030/031 routing) should pick the
Galaxy Watch only while Nicholas is wearing it. The Wear OS companion (feature 003) reads the watch's off-body sensor
(`Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT` or equivalent) and reports worn / not worn to the phone over the existing
Data Layer channel. On the phone, Auto's order becomes: connected Bluetooth (or wired) headset > Watch, only if worn >
phone speaker > phone earpiece only as a last resort (when no speaker route exists; 030's "a Live call never plays on
the earpiece when the speaker is available" stays). The watch counts as not worn when its state is unknown, stale
(no fresh report), or the watch has no such sensor. "Input" in the ask means the Auto device choice as a whole
(read-aloud output, dictation input and Live calls), wherever Auto currently chooses the watch.

## Non-goals
- New settings, a priority-list UI, or changes to the explicit (non-Auto) device choices: picking "Watch" by hand still
  uses the watch whether worn or not.
- Redesigning AudioRoute, the watch audio stream (007/012/018) or the Data Layer protocol beyond one worn-state message.
- F-Droid flavor (no watch there).

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] Watch app: reads the off-body sensor and sends worn/not-worn to the phone over the existing Data Layer channel on
  change (and at start / reconnect so the phone isn't left with a stale state); a watch without the sensor reports
  nothing or "unknown".
- [ ] Phone: keeps the latest worn state with a timestamp; unknown or stale (older than a bound the plan picks, e.g.
  watch not heard from) = not worn. Unit test.
- [ ] Auto order (unit tests): BT headset connected -> headset; no headset, watch reachable and worn -> watch; watch
  reachable but not worn / unknown / stale -> phone speaker; earpiece only when no speaker route is possible.
- [ ] Wherever Auto currently routes to the watch (read-aloud, Live call taking on the watch), a not-worn watch is
  skipped; a hand-picked Watch is unchanged. 016/018/030/031 tests still pass.
- [ ] The setting's "Auto is using ..." display reflects the skip (e.g. speaker when the watch is off-wrist).
- [ ] CI green (both APKs build); diff limited to the brief.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
