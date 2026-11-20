---
type: brief
feature: 031-headset-route
created: 2026-11-20
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 031-headset-route

## Ask (verbatim, never edit)
Bug (Nicholas, 2026-11-20): since release 2.3.14-fork.2 (feature 030 live-no-earpiece, PR #34), his Bluetooth headset "LS2 Freecom" no longer works with Buddy voice: both dictation/mic input and the Live call are broken (no audio in/out through the headset). Before 030 it worked. Evidence: /tmp/live-ls2.log (copy of ~/.buddy/live.log) shows route flapping: "route: 7:LS2 Freecom applied" then "slipped from 7:LS2 Freecom to 1:SM-G990W", "watch: setting ... -> ...", and "mic peak 0.000; voice peak 0.000". Note: audio_device setting was changed from "7:LS2 Freecom" to "auto" earlier today. Device ids like "7:" may be unstable indices. Intended order: connected BT headset > watch > speaker, never earpiece; headset mic must be used for input (SCO/communication device) for both dictation and Live. Keep it simple.

## Goal
Nicholas's Bluetooth headset (LS2 Freecom, a phone-call SCO headset) must carry Buddy's voice again, in and out, for
both dictation (mic input) and phone Live calls. It worked before 030 (PR #34, 2.3.14-fork.2).

Orchestrator's first read of the log (to be confirmed by research): device keys are `<AudioDeviceInfo type>:<name>`
(7 = BLUETOOTH_SCO, 1 = BUILTIN_EARPIECE, 2 = BUILTIN_SPEAKER), not indices. 030 added `AudioRoute.grace()`: a device
applied but not yet seen gets 0 ms when Android reports the earpiece. A SCO link reports the earpiece while it connects
(about 0.3-2 s), so at the first poll the headset is "slipped", refused, and the call goes to the speaker
(`route: 7:LS2 Freecom applied` then `slipped ... to 1:SM-G990W` 0.3 s later, `2:SM-G990W applied`). Before 030 every
device had CONFIRM_MS (2 s). Mic peak 0.000 for the whole call. Why dictation broke too (Auto, no live call) is open:
research must find it (e.g. a shared refusal state, or Auto's order no longer picking the SCO headset).
The `watch: setting X -> Y` lines are Nicholas changing the setting in the UI during the test call.

Intended order (Nicholas): connected BT headset > watch > speaker, never earpiece; the headset's mic is the input
(SCO / communication device) for dictation and Live.

## Non-goals
- Redesigning AudioRoute or the watch flow (026/017); no new settings or UI.
- Changing the earpiece guarantee of 030 for the cases it fixed (no headset: speaker, never earpiece).

## Done when
- [ ] Live call, Auto or saved = the headset, BT SCO headset connected: the headset becomes and stays the communication device (its SCO gets the time Android needs to connect, not refused at the first poll while Android still reports the earpiece); voice out and mic in through it. Unit test reproduces the log's sequence (applied, earpiece reported at 0.3 s, headset reported later) and fails on main.
- [ ] Live call, a headset that really fails (never reported within its setup time) still falls to the speaker, never the earpiece; 030's done-when tests still pass.
- [ ] Dictation with the headset connected (Auto and saved = headset): the input comes from the headset (communication device = headset SCO). Root cause found and fixed, with a test.
- [ ] Order for Auto: connected BT headset > watch > speaker, never earpiece (test).
- [ ] CI green; diff limited to the routing fix and its tests/docs.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
