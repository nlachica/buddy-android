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
With the screen off on battery nothing of Buddy's wake word records: the detector's microphone is closed and a wake
turn in progress is cancelled, so the audio server's wake lock for Buddy goes and the phone can deep-sleep. It listens
again within ~2 s of the screen turning on or power being connected, read as: a "hey buddy" begun about 2 s after
either is detected. The Hey Buddy switch still wins: off means nothing listens in any state. Proven by unit tests of
the state decision, a green CI build and the checks on the phone below.

Non-goals: hey buddy with the screen off on battery (the old 036 goal); any change to other features, the settings
screen's controls, or the live-call audio path (035); the wake locks of rules, schedules and the watch.

## Design
**One new row in the pure state function, one new state, one receiver that runs the tick.** `WakeWord.state(Inputs)` already decides
what the detector's microphone should be (`OFF, PAUSED, TURN, SPEAKING, IDLE`, `WakeWord.java:323`), and
`WakeService.tick` already applies it every 500 ms (`WakeService.java:279`). Add a state `ASLEEP` and the inputs
`screenOn` and `charging`:

```
OFF       !enabled
PAUSED    a cause (call, foreign recording, Buddy's own voice path)        unchanged
ASLEEP    !screenOn && !charging                                            new: beats a turn, which is cancelled
TURN      a wake turn is on                                                 unchanged
PAUSED    msSinceCause < RESUME_MS                                          unchanged
SPEAKING / IDLE                                                             unchanged
```

In `tick`, `ASLEEP` cancels a wake turn that is on (`WakeWord.cancelTurn()`: releases the feed, stops the recognizer,
ignores a late transcript; what `PAUSED` does today, without `Voice.fedCancelled()`) and closes the microphone
(`closeNow()` when `want != 0`; an unfed turn already has `want == 0`, so entering the state is logged by itself, not
with the close). Nothing else: the notification text is not changed (nobody reads it with the screen
off, and it is right again within one tick of the screen coming on), and a request already sent to the chat is not
touched.

Why this shape:
- **The service stays, only the recorder closes.** The CPU is held awake by the open recording (the audio server's
  `AudioIn` wake lock for Buddy's uid; the service has no wake lock of its own, research.md "Summary"). Closing the
  `AudioRecord` releases it; this is what `PAUSED` does today ("Paused = free", `032 battery.md`). Stopping the
  foreground service instead would need a restart from the background on screen-on, which Android 14+ refuses for a
  microphone service (`WakeWord.ensure` → "Tap to turn on Hey Buddy", `WakeWord.java:68-70`). Reopening the recorder
  inside the running service is the existing `PAUSED → IDLE` path.
- **No exception for a turn or an answer** (plan review round 1, F1). The brief says no capture with the screen off on
  battery, so a turn in flight is cancelled and nothing keeps listening after a request is sent. The cost is in Risks.
- **Below the cause row.** A call or another app's recording with the screen off stays `PAUSED` exactly as today
  (same text, same `fedCancelled` rule); `ASLEEP` only adds the case where nothing else closes the microphone.
- **The tick decides; one receiver only makes it run sooner.** The tick reads screen and power fresh every time, so it
  is self-healing and there is no state to keep in step. But it is a `Handler.postDelayed` timer on the uptime clock,
  which stands still in deep sleep, so by itself it gives no bound after a dark plug-in (round 1, F3). The service
  therefore has one receiver for `ACTION_POWER_CONNECTED` and `ACTION_SCREEN_ON` whose `onReceive` runs the tick,
  registered in `onCreate` and unregistered in `onDestroy`: a start that `startForeground` refuses still reaches
  `onDestroy`, and unregistering a receiver that was never registered throws (round 2, F2). Before the service is
  running the tick returns at once (`WakeService.java:281`). If the display isn't `STATE_ON` yet when `SCREEN_ON`
  arrives, the next tick (≤ 500 ms) sees it.
- **Time to ready.** After the recorder opens the detector needs 1.08 s of new audio and three more 120 ms hops before
  its first score (`WakeFeatures.WINDOW`, `HOP`, `GROUPS`): 1.44 s. With the tick run on the event, the 50 ms capture
  poll and the recorder's start, it scores from about 1.7 s, inside "~2 s".
- **Screen = the default display is known and `STATE_ON`**, read in `WakeService.inputs`
  (`d != null && d.getState() == Display.STATE_ON`). Not `PowerManager.isInteractive()`: it stays true while a
  background chat's display is awake (`Triggers.java:1141`), which would keep the microphone open through a
  screen-off agent run. Not `BuddyService.screenOn`: it counts a missing display as on, right for its callers, wrong
  here (round 1, F2). The always-on display counts as off.
- **Charging = known to be plugged in:** the sticky `ACTION_BATTERY_CHANGED` is there and `EXTRA_PLUGGED != 0` (the
  read `PhoneTools.java:315` does); a null intent is not charging. Not `BatteryManager.isCharging()`, which can be
  false while plugged (full battery, Samsung's charge limit, a weak charger). The brief's words are "power is
  connected" and "unplugging".
- **Not a cause.** `ASLEEP` does not touch `lastCause`, so there is no `RESUME_MS` wait when the screen comes on.

Alternatives, one line each:
- Stop and restart the service on screen changes: refused from the background on Android 14+; rejected.
- Receivers that keep screen and power state themselves: state to keep in step; rejected for a receiver that only
  runs the tick.
- A turn in flight finishes, or listening goes on for 2 min after a request (this plan's first version): records with
  the screen off on battery, against the brief; rejected in review round 1.
- A timed `PARTIAL_WAKE_LOCK` for the answer: a new Buddy wake lock (the done-when says none); rejected.
- A setting for the rule: not asked for; rejected.

## Steps
1. **State rule and tests.** `app/src/main/java/app/buddy/assistant/WakeWord.java`: `State.ASLEEP`; `Inputs.screenOn`,
   `Inputs.charging`; the new row in `state()` with its javadoc. `cause()` is not changed.
   `app/src/test/java/app/buddy/assistant/WakeWordTest.java`: `in()` sets `screenOn = true`; new tests (Test plan, T1).
2. **Service applies it.** `app/src/main/java/app/buddy/assistant/WakeService.java`:
   - `inputs()` fills `screenOn` (default display not null and `STATE_ON`) and `charging` (battery intent not null and
     `EXTRA_PLUGGED != 0`).
   - `tick()` gets `case ASLEEP:` cancel the turn if one is on, then `if (want != 0) closeNow();`. One boolean field
     `asleep` holds whether the last tick's state was `ASLEEP`: `Log.i(TAG, "asleep: screen off, on battery")` once
     when it becomes true (whether or not a recorder was closed), `"awake"` once when it becomes false.
   - One `BroadcastReceiver` field for `ACTION_POWER_CONNECTED` and `ACTION_SCREEN_ON` (system broadcasts, registered as
     `BuddyService.java:123` does) whose `onReceive` calls `tick()`; registered in a new `onCreate`, unregistered in
     `onDestroy` (not in `onStartCommand`: its refused-start path returns before the `if (!running)` block).
   - The class comment gains one sentence. `WakeWord.onWake` already drops a detection unless the state is `IDLE` or
     `SPEAKING`.
3. **Words.** `app/src/main/res/values/strings.xml` `setup_wake_info` ("Always listening…" → "Listens on this phone for
   \"hey buddy\" while the screen is on or the phone is charging; …"; English only, there are no translations of it).
   `docs/GUIDE.md:49`: "listens all the time" and "also with the phone locked" say the rule (screen on, locked or not,
   or plugged in; the always-on display counts as off); one sentence that on battery a request still being spoken
   when the screen goes dark is dropped and "hey buddy, stop" needs the screen on; the Battery sentences say the same
   rule. `docs/PRIVACY.md:46,65`: "continuously" → the rule.
   `docs/features/032-hey-buddy-wake-word/battery.md`: one dated paragraph with the measured figure from the brief
   (1372 of 4783 mAh in 6 h on battery, never deep-slept) and the pointer to 036.
4. **Release and phone checks** (orchestrator, after review): normal fork release per AGENTS.md ("Updating Buddy on the
   phone", release check first), `update_buddy install=true`, then P1-P6 below into `verify.md`.

## Test plan
Unit tests run in CI only (no JVM on the phone). Phone checks are read with `phone_shell`; run them in the order P1,
P2, P3, P5, P4, P6, with no rule, schedule or watch run going. Times come from the log: screen and plug events from
the system's own lines (`logcat -b all`, or `dumpsys batterystats --history`), Buddy's from `BuddyWake` and `HeyBuddy`.
`phone_shell` refuses any command that names Buddy's package (`Tools.java:713`), and that is not worked around: every
command is device-wide (`dumpsys audio`, `dumpsys power`, `dumpsys batterystats`, `dumpsys notification`, `logcat`)
and Buddy's lines are found in the output (by package name there, or by its uid, which those lines show). Where only a
package-specific dump would do, Nicholas runs it over his own adb and `verify.md` records it as his.

| Done-when | Proof |
|---|---|
| Unit tests for the state decision | **T1** in `WakeWordTest`: all 8 combinations of (enabled, screenOn, charging) → `OFF` when not enabled, else `IDLE` when screenOn or charging, else `ASLEEP`. With the screen off on battery: a fed turn, an unfed turn (with its one expected recording), no turn and speaking are each `ASLEEP`; the same four with screenOn, and with charging, are `TURN`, `TURN`, `IDLE`, `SPEAKING`; a call or a foreign recording is `PAUSED` (row order); `msSinceCause < RESUME_MS` is `ASLEEP`; `cause()` is false for screen-off inputs (no resume delay). Existing state tests pass unchanged apart from `in()`. |
| CI build green | `./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease` in `build.yml` on the PR; F-Droid build unaffected (the wake classes compile in both, the service is github-only). |
| Screen off + battery: no capture, no wake lock | **P1 right after a request**, unplugged: "hey buddy, what time is it" with the screen on, power button as soon as it is sent. Read at once: `asleep: screen off, on battery` within ~1 s of the screen-off time; `dumpsys audio` shows no active recording by `app.buddy.assistant`; `dumpsys power` lists no `AudioIn` wake lock for Buddy's uid and no `buddy:*`. Whether and when the answer was spoken is written down, not a pass condition. **P2 during a turn:** start a 20 s sampling of `dumpsys audio` (twice a second, each sample with its time); within it say "hey buddy" alone and press the power button while it waits for the request. The samples during the turn name the recording's owner (Buddy's own recorder: a fed turn; the recognizer's: an unfed one); the samples from ~1 s after screen-off show no active recording by that owner or by Buddy, the log has the `asleep` line (logged on entering the state, so an unfed turn has it too), and nothing arrives in the chat. Repeat once with the other kind of turn if a `voice_input` setting gives it; a kind that can't be had is written down as unverified. **P3 deep sleep:** device-wide `dumpsys batterystats` just before the screen goes off (A) and after 30+ min dark, unplugged and untouched, with no chat working (B; nothing is reset). B − A: screen-off uptime well below screen-off realtime (device-wide lines), and the audio time in the section of Buddy's uid about 0; at B `dumpsys audio` and `dumpsys power` as in P1. If the uid's audio time can't be read from the device-wide dump, Nicholas takes A and B with `adb shell dumpsys batterystats app.buddy.assistant` himself. |
| Screen on: listens within ~2 s | **P4**, unplugged, after 1+ min dark: power button, count two seconds, say "hey buddy, what time is it"; 5 times on the lock screen, 5 unlocked. Pass: a `HeyBuddy` wake line for at least 4 of 5 each, and `awake` then `mic open` within ~1 s of screen-on. A miss with a late `mic open` is the gate; with a timely one it is warm-up or the detector: report which. |
| Charging, screen off: listens; unplug stops | **P5a dark plug-in**, an interval of its own: after P3's reading B, unplugged, screen off, untouched and with no reading taken for 10+ min. Plug in and nothing else, count two seconds, say "hey buddy, what time is it"; only then read the log. It counts as a power-only resume when the default display was still off at Buddy's `mic open` (no system screen-on line before it): then pass = `awake` and `mic open` within ~2 s of the system's plug time and a `HeyBuddy` wake line; 3 tries, each after its own untouched dark interval. A try where the plug-in lit the display first doesn't count; if every one does, the dark resume is recorded as **unverified** with that reason. **P5b plugged, screen off** (reported separately): plugged, power button, 1 min dark: no `asleep` line, `dumpsys audio` shows Buddy's recording, a "hey buddy" gives a wake line. **P5c unplug with the screen off:** `asleep` within ~2 s and P1's dumpsys checks. |
| Setting respected | **P6** Hey Buddy off (`buddy_settings hey_buddy`): the setting reads back off, the listening notification is gone (`notifications`, or `dumpsys notification`), and with the screen on, off, plugged and unplugged `dumpsys audio` shows no recording by Buddy and the log no new `mic open` (the `off` log line is optional). On again, once with Buddy not on screen: if the log shows the start refused (`start refused` or `foreground start refused`), Buddy has not crashed (`logcat -b crash` has nothing of Buddy's, the chat still answers), the "Tap to turn on Hey Buddy" notification is there, and a tap gives one `service started` line; if the start isn't refused, that case is written down as not provoked. Then P1 and P4 still hold. Unit: the `!enabled → OFF` rows of T1. |

## Risks
- **What Nicholas will notice on battery.** A request still being spoken when the lock screen goes dark is dropped
  (say it again with the screen on); after a sent request the phone may sleep before the answer is spoken, as after a
  bubble Talk request today; "hey buddy, stop" doesn't work once dark. This is the rule he chose; P1 records what
  happens to the answer. If it bothers him, a short grace is a follow-up, not this feature.
- **A dark plug-in the phone sleeps through.** If the system delivers the broadcast and suspends before the recorder
  is open, P5a fails; that goes back to the Planner (the fix would be a wake lock, which the brief excludes on battery
  but not while charging).
- **Something else keeps the phone awake.** The brief's "never deep-slept" may not be the detector alone. If P3 still
  shows no deep sleep with no Buddy wake lock or recording, report what `dumpsys power` names; that is outside this
  feature (non-goal) unless it is the listener.
- **Idle polling.** While `ASLEEP` the tick (2 a second) and the capture thread's 50 ms poll keep running whenever the
  CPU is awake for other reasons, as in `PAUSED` today. Neither holds the CPU. If batterystats shows Buddy CPU time
  with the screen off, slow them in a follow-up.
- **Phone checks need Nicholas's phone and a release**; the unit tests and CI cannot show wake locks. If this phone
  lights its display on every plug-in, the power-only resume stays unverified (P5a); what he would notice, listening
  after plugging in, is still shown.

## Open questions
None.
