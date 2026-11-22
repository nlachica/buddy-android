# Battery: what "hey buddy" costs

**Status: an estimate, not measured on this phone.** The measured hour (verify.md, P4) replaces the figure below. The
guide (`docs/GUIDE.md`, "Hey Buddy") says the same.

## Figure (estimate, sourced)
No controlled phone benchmark exists for this kind of wake word. The sourced reports (research.md, "Battery figures"):
- Home Assistant's Android app: battery use rose from about 1% to about 15% with its wake word on (Open Home
  Foundation; window unstated). Its docs: "uses more battery than Ok Google" (which has a low-power chip).
- One developer write-up (secondhand): 0.41% a minute (~25% an hour) for an unoptimised ONNX detector, 0.02% a minute
  (~1.2% an hour) after native optimisation.

Buddy runs ONNX Runtime on the CPU with three small models, so it sits between the two: expect a few percent an hour
with the screen off, until measured. Nothing here is a promise.

## What drives it
- **The open microphone.** A microphone foreground service records all day; Android keeps the CPU from deep sleep while
  a recording is active (no wake lock of our own). This is the floor, whatever the model costs.
- **Inference 8 times a second.** Every 120 ms the last 1.08 s goes through the mel model, four embeddings and the
  classifier (`WakeFeatures`). Cost per hop is unmeasured (P5); the plan's trigger for an energy gate (skip inference
  while the room is quiet) is more than 10 ms per hop.
- **Speaking.** While Buddy speaks the recorder reopens with the echo canceller (`VOICE_COMMUNICATION`), a short
  extra cost for the seconds of speech only.
- **Paused = free.** Calls, another app recording, Talk/dictation/Live use the microphone, and the setting off: the
  recorder is closed and nothing runs.

## How to measure (verify.md P4)
1. `adb shell dumpsys batterystats --reset`, unplug, screen off, Hey Buddy on, one hour, then read Buddy's mAh from
   `dumpsys batterystats` (per-UID).
2. The same hour with the switch off. The difference is the cost.
3. P5, time per hop: the service does not log it. Measure with a temporary timing log around `WakeFeatures.feed` (or a
   profiler) on the same run, and record the figure here.
