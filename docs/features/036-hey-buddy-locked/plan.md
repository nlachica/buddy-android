---
type: plan
feature: 036-hey-buddy-locked
created: 2026-11-22
---
# Plan: 036-hey-buddy-locked (rescoped: listen only with the screen on or while charging)

Replaces `plan-superseded-locked.md` (the locked-screen diagnosis; background only). Code facts are from
`feat/036-hey-buddy-locked` at 0408112.

## Goal
The "hey buddy" detector listens only while the phone's screen is on (locked or unlocked) or the phone is plugged in.
With the screen off on battery its microphone is closed, so the audio server's wake lock for Buddy goes and the phone
can deep-sleep; it listens again within ~2 s of the screen turning on or power being connected. The Hey Buddy switch
still wins: off means nothing listens in any state. Proven by unit tests of the state decision, a green CI build and
the checks on the phone below.

Non-goals: hey buddy with the screen off on battery (the old 036 goal); any change to other features, the settings
screen's controls, or the live-call audio path (035); the wake locks of rules, schedules and the watch.

## Design
**One new row in the pure state function, one new state, no new lifecycle.** `WakeWord.state(Inputs)` already decides
what the detector's microphone should be (`OFF, PAUSED, TURN, SPEAKING, IDLE`, `WakeWord.java:323`), and
`WakeService.tick` already applies it every 500 ms (`WakeService.java:279`). Add a state `ASLEEP` and the inputs
`screenOn`, `charging` and `msSinceRequest`:

```
OFF       !enabled
PAUSED    a cause (call, foreign recording, Buddy's own voice path)        unchanged
TURN      a wake turn is on                                                 unchanged: a turn in flight finishes
ASLEEP    !screenOn && !charging && msSinceRequest >= ANSWER_MS             new
PAUSED    msSinceCause < RESUME_MS                                          unchanged
SPEAKING / IDLE                                                             unchanged
```

In `tick`, `ASLEEP` does what `PAUSED` does to the microphone (`closeNow()` when `want != 0`) and nothing else: the
notification text is not changed (nobody reads it with the screen off, and it is right again within one tick of the
screen coming on), and the turn is not cancelled (the row sits below `TURN`).

Why this shape:
- **The service stays, only the recorder closes.** The CPU is held awake by the open recording (the audio server's
  `AudioIn` wake lock for Buddy's uid; the service has no wake lock of its own, research.md "Summary"). Closing the
  `AudioRecord` releases it; this is what `PAUSED` does today ("Paused = free", `032 battery.md`). Stopping the
  foreground service instead would need a restart from the background on screen-on, which Android 14+ refuses for a
  microphone service (`WakeWord.ensure` → "Tap to turn on Hey Buddy", `WakeWord.java:68-70`). Reopening the recorder
  inside the running service is the existing `PAUSED → IDLE` path.
- **Polled in the tick, no receivers.** `tick` reposts itself with `Handler.postDelayed` (uptime clock): it does not
  run in deep sleep and holds nothing awake, and it runs at most 500 ms after the phone wakes. So screen-on and
  plug-in are seen within 500 ms and the recorder is open well inside ~2 s, with no receiver to register, unregister
  or miss. Polling is also self-healing.
- **Screen: `BuddyService.screenOn(Context)`** (`BuddyService.java:168`, the default display is `STATE_ON`), not
  `PowerManager.isInteractive()`: the latter stays true while a background chat's display is awake
  (`Triggers.java:1141`), which would keep the microphone open through a screen-off agent run. The always-on display
  counts as off.
- **Charging = plugged in:** `EXTRA_PLUGGED != 0` from the sticky `ACTION_BATTERY_CHANGED` (the read `PhoneTools.java:315`
  already does), not `BatteryManager.isCharging()`, which can be false while plugged (full battery, Samsung's charge
  limit, a weak charger). The brief's own words are "power is connected" and "unplugging".
- **A turn in flight finishes, and the detector stays up `ANSWER_MS` = 2 min after a request was sent.** The lock
  screen goes dark a few seconds after a wake; without this the mic (the only thing keeping the CPU up) would close
  the moment the request is sent and the run could stall before the answer is spoken. The house precedent is that a
  run without the screen needs the CPU held (`Triggers.java:1146`, `Schedules.java:833`, `WatchLink.java:379`). Keeping
  the detector open reuses the one mechanism there is, keeps "hey buddy, stop" and a follow-up working while Buddy
  answers, and is a pure, unit-tested row. Only a sent request starts it (`WakeWord.send`), so a false wake that
  sends nothing does not. See Questions for Nicholas, Q1.
- **Not a cause.** `ASLEEP` does not touch `lastCause`, so there is no `RESUME_MS` wait when the screen comes on.

Alternatives, one line each:
- Stop and restart the service on screen changes: refused from the background on Android 14+; rejected.
- `SCREEN_ON/OFF` and `POWER_CONNECTED/DISCONNECTED` receivers: more lifecycle for no gain over a 500 ms tick; rejected.
- A timed `PARTIAL_WAKE_LOCK` for the answer instead of the 2 min rule: a new Buddy wake lock (the done-when says
  none), no barge-in while dark; rejected.
- Screen-off cancels a turn: breaks a request spoken on the lock screen as it times out; rejected.
- A setting for the rule: not asked for; rejected.

## Steps
1. **State rule and tests.** `app/src/main/java/app/buddy/assistant/WakeWord.java`: `State.ASLEEP`; `Inputs.screenOn`,
   `Inputs.charging`, `Inputs.msSinceRequest` (default `Long.MAX_VALUE`); `ANSWER_MS = 120_000`; the new row in
   `state()` with its javadoc; a `private static long requestAt` set to `SystemClock.elapsedRealtime()` in `send()`
   when `sendTalk` returned null, and a package-private `msSinceRequest(long now)`. `cause()` is not changed.
   `app/src/test/java/app/buddy/assistant/WakeWordTest.java`: `in()` sets `screenOn = true`; new tests (Test plan, T1).
2. **Service applies it.** `app/src/main/java/app/buddy/assistant/WakeService.java`: `inputs()` fills
   `screenOn = BuddyService.screenOn(this)`, `charging` (sticky `ACTION_BATTERY_CHANGED`, `EXTRA_PLUGGED != 0`; a null
   intent counts as charging, so a failed read never makes it deaf) and `msSinceRequest`; `tick()` gets
   `case ASLEEP: if (want != 0) { closeNow(); Log.i(TAG, "asleep: screen off, on battery"); } break;` and logs
   `"awake"` once when it leaves `ASLEEP` (one boolean field). The class comment gains one sentence. Nothing else:
   `WakeWord.onWake` already drops a detection unless the state is `IDLE` or `SPEAKING`.
3. **Words.** `app/src/main/res/values/strings.xml` `setup_wake_info` ("Always listening…" → "Listens on this phone for
   \"hey buddy\" while the screen is on or the phone is charging; …"; English only, there are no translations of it).
   `docs/GUIDE.md:49`: "listens all the time" and the Battery sentences say the same rule, including "for two minutes
   after a request" and that the always-on display counts as off. `docs/PRIVACY.md:46,65`: "continuously" → the rule.
   `docs/features/032-hey-buddy-wake-word/battery.md`: one dated paragraph with the measured figure from the brief
   (1372 of 4783 mAh in 6 h on battery, never deep-slept) and the pointer to 036.
4. **Release and phone checks** (orchestrator, after review): normal fork release per AGENTS.md ("Updating Buddy on the
   phone", release check first), `update_buddy install=true`, then P1-P6 below into `verify.md`.

## Test plan
| Done-when | Proof |
|---|---|
| Unit tests for the state decision | **T1** in `WakeWordTest` (CI only, no JVM on the phone): all 8 combinations of (enabled, screenOn, charging) → `OFF` when not enabled, else `IDLE` when screenOn or charging, else `ASLEEP`; a turn with the screen off on battery stays `TURN`; a call with the screen off is `PAUSED` (row order); `msSinceRequest` below `ANSWER_MS` → `IDLE` (and `SPEAKING` when speaking), at `ANSWER_MS` → `ASLEEP`; `cause()` is false for screen-off inputs (no resume delay); `msSinceCause < RESUME_MS` with the screen off → `ASLEEP`. Existing state tests pass unchanged apart from `in()`. |
| CI build green | `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease` in `build.yml` on the PR; F-Droid build unaffected (the wake classes compile in both, the service is github-only). |
| Screen off + battery: no capture, no wake lock | **P1** on the phone, unplugged, no rule/schedule/watch run going, more than 2 min after the last request: screen off 30 s, then `phone_shell`: `logcat -d -s BuddyWake` shows `asleep: screen off, on battery`; `dumpsys power` lists no `AudioIn` wake lock for Buddy's uid and no `buddy:*`; `dumpsys audio` (recording activity) shows no active recording by `app.buddy.assistant`. **P2** after 30+ min screen off on battery: `dumpsys batterystats` shows deep sleep (uptime well below realtime) and Buddy's screen-off audio time not growing. |
| Screen on: listens within ~2 s | **P3** screen on, still locked: `logcat` shows `awake` and `mic open` within 2 s of the screen-on time; "hey buddy, what time is it" on the lock screen is answered by voice even though the screen goes dark meanwhile (this is the `ANSWER_MS` row). Repeat unlocked. |
| Charging, screen off: listens; unplug stops | **P4** plugged in, screen off 1 min: no `asleep` line, recording active, "hey buddy" triggers (`HeyBuddy` wake line). **P5** unplug with the screen off: `asleep` within ~2 s, P1's dumpsys checks again. Plug back in with the screen off: `awake` and `mic open`. |
| Setting respected | **P6** Hey Buddy off (`buddy_settings hey_buddy`): `BuddyWake: off`, no notification, no recording with the screen on, off, plugged or not. On again: P1 and P3 still hold. Unit: the `!enabled → OFF` rows of T1. |

## Risks
- **Something else keeps the phone awake.** The brief's "never deep-slept" may not be the detector alone. If P2 still
  shows no deep sleep with no Buddy wake lock or recording, report what `dumpsys power` names; that is outside this
  feature (non-goal) unless it is the listener.
- **Idle polling.** While `ASLEEP` the tick (2 a second) and the capture thread's 50 ms poll keep running whenever the
  CPU is awake for other reasons, as in `PAUSED` today. Neither holds the CPU. If batterystats shows Buddy CPU time
  with the screen off, slow them in a follow-up.
- **`BuddyService.screenOn` during a hidden-display run.** It reads the default display only, so it should be right;
  P1 on a quiet phone doesn't cover it. Covered by the comment at `Triggers.java:1141`, not re-tested.
- **A run longer than 2 min after a lock-screen request** continues as a bubble Talk request does today with the
  screen off (no CPU hold); unchanged behaviour, stated in the GUIDE.
- **Phone checks need Nicholas's phone and a release**; the unit tests and CI cannot show wake locks.

## Questions for Nicholas
- **Q1. You say "hey buddy" with the screen on, and the screen goes dark before Buddy has answered (on the lock
  screen that takes a few seconds). How long should it keep listening on battery?**
  1. **Two minutes after each request (recommended, planned):** the answer is spoken, "hey buddy, stop" and a
     follow-up work while it's dark; costs about 2 min of listening per request.
  2. Not at all: strictest on battery, but an answer can come late or only when you pick the phone up, and "stop"
     doesn't work once dark.
  3. Only until the answer has been spoken: saves a little more, more code and more ways to get stuck listening.
