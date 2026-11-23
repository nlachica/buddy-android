## Round 1 - Plan Reviewer (gpt-6.2-astra)

F1 [blocker] Screen-off on battery can leave the microphone recording - The rescoped brief says to stop capture when the screen turns off on battery (brief.md:13–16, 23–25, 33). The proposed state table gives an active turn priority over that rule and permits fresh detections for two minutes after a sent request (plan.md:28–33). A fed turn keeps the detector recorder open, and an unfed turn can have a separate recognizer recording even when `want == 0` (WakeService.java:308–310, WakeWord.java:168–190). P1 waits out the exception before checking (plan.md:101). -> Remove the request timer and its documentation. On screen-off or unplug, apply the gate to an active wake turn as well as idle and speaking detection. Reuse `WakeWord.cancelTurn()` to release the feed, stop the recognizer and discard stale callbacks (WakeWord.java:106–126, 193–200). Leave already-sent chat work alone. Test the transition during a fed and an unfed wake turn and immediately after a successful send, and check that the recorder and its attributed wake lock are released without a two-minute wait.

F2 [major] An unknown power or display state permits capture - Step 2 treats a null battery intent as plugged in (plan.md:81–84). The proposed screen helper also returns true when the default display is absent (BuddyService.java:171–175). Either fallback can enable capture without evidence of either condition in the brief. -> Require a known-on default display or a known nonzero plugged value. Keep the existing display helper's behavior for its other callers. Cover missing readings with the other input both true and false, and re-evaluate when the next reading arrives.

F3 [major] Plug-in after deep sleep has no bounded response in the plan - The 500 ms bound at plan.md:47–50 is measured in awake time. If connecting power leaves the display dark and the CPU suspends again before the pending tick, the recorder stays closed until a later wake. P5 reconnects immediately after unplugging and inspecting the phone, so it can pass without exercising this case (plan.md:103). -> Start the plug-in trial after the unplugged deep-sleep interval, without opening Buddy. Record the physical display state and the power-to-recorder timestamps. For a prompt response independent of the pending timer, have the running service handle power connection changes and re-evaluate the gate on delivery. [Android Handler timing](https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,%20long))

F4 [major] The two-second check times recorder startup, leaving wake-word readiness unmeasured - P3 times `mic open` but puts no time on when the tester says the phrase (plan.md:102). Reopening resets the detector (WakeService.java:183, WakeLoop.java:170–175). It then needs 1.08 seconds of fresh audio plus three 120 ms hops before the first classifier score (WakeFeatures.java:21–25, 49–52, 68–69). A phrase spoken later can pass even if one spoken at the acceptance boundary is missed. -> On both the locked and unlocked screen, start a known wake phrase about two seconds after the screen-on event and verify the corresponding detection. Keep the recorder timestamp to distinguish a late open from detector warm-up. Use the same timing on a dark-screen power connection.

F5 [minor] The setting-off check requires a log line that a successful stop can skip - P6 requires `BuddyWake: off` (plan.md:104). The settings tool calls `WakeWord.ensure()`, which calls `stopService()` directly when disabled (SettingsTool.java:361–363, WakeWord.java:57–78). Only the tick's OFF branch emits that line (WakeService.java:296–300). -> Check that the service and notification disappear and recording stays absent across screen and power changes. Treat the log line as optional.

F6 [minor] The deep-sleep check needs a baseline - P2 calls for a single batterystats reading after 30 minutes to show that Buddy's screen-off recording time did not grow and the phone slept (plan.md:101). Totals accumulated since the last charge also include activity before P2 (brief.md:30–32). -> Capture counters at both ends of the same unplugged interval. Compare elapsed-time and uptime deltas for sleep, and Buddy's audio-time delta for recording. This preserves the phone's battery history.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - the brief wins ("screen off + on battery: no mic capture"). `ANSWER_MS`, `msSinceRequest`, `requestAt`
and Q1 are gone. `ASLEEP` now sits above `TURN`, and the tick's `ASLEEP` case calls `WakeWord.cancelTurn()` (feed
released, recognizer stopped, stale callbacks dropped) before `closeNow()`. A sent request is left alone. Unit rows: a
fed turn, an unfed turn, no turn (just sent) and speaking are all `ASLEEP` with the screen off on battery. Phone: P1
turns the screen off right after a send and P2 during a turn, both read at once, no two-minute wait. What Nicholas
will notice (a request cut when the lock screen goes dark mid-sentence, a possibly late answer) is in Risks and the GUIDE.

F2: accepted - unknown is off and unplugged. `WakeService.inputs` reads the default display itself
(`d != null && d.getState() == STATE_ON`; `BuddyService.screenOn` and its callers untouched) and a null battery intent
is not charging. Both are read fresh on every tick, so the next reading is the re-evaluation. No extra test seam: an
unknown reading is the input `false`, and T1 has every combination of the two inputs.

F3: accepted - one receiver in the running service for `ACTION_POWER_CONNECTED` (and `ACTION_SCREEN_ON`, same line)
that runs the tick on delivery; the tick stays the one place that decides. P5 now plugs in at the end of P3's 30 min
dark interval with the phone untouched, and records whether the display lit, the system's plug time, and Buddy's
`awake` and `mic open` times.

F4: accepted - the ~2 s check is now a detection: "hey buddy" begun about 2 s after screen-on (locked, then unlocked)
and after a dark plug-in must give a `HeyBuddy` wake line, 4 of 5; `mic open` is kept to tell a late open from the
1.44 s warm-up. The Goal states this reading of "listens again within ~2 s".

F5: accepted - P6 checks that the service and its notification are gone and nothing records across screen and power
changes; the `off` line is not required.

F6: accepted - P3 takes `dumpsys batterystats` at both ends of the same unplugged dark interval and compares deltas
(screen-off realtime against uptime, Buddy's audio time); nothing is reset.

## Round 2 - Plan Reviewer (gpt-6.2-astra)

F1 [major] Two phone checks cannot run through the specified tool - P3 and P6 pass `app.buddy.assistant` to `dumpsys` through `phone_shell` (plan.md:108, 116, 119). The tool rejects commands containing that package name before execution (Tools.java:713–715, 730–735). The earlier phone trial also records this refusal (plan-superseded-locked.md:14–15). -> Use the permitted device-wide battery, audio and power observations. For P6, use the setting readback, notification disappearance and absence of wake-word recording across transitions. If a package-specific dump is needed, have the tester collect it through their own ADB session and record it in verification.

F2 [major] A refused microphone service start can crash Buddy during cleanup - Step 2 registers the receiver inside the successful start path and unregisters it in `onDestroy` (plan.md:91–93). On a fresh instance, `startForeground` can throw before registration. The catch calls `stopSelf()`, posts the recovery notification and returns (WakeService.java:103–117). Unregistering an unregistered receiver then throws on the service's main thread. -> Pair registration in `onCreate` with unregistration in `onDestroy`. The existing `tick()` already returns while the service is inactive (WakeService.java:279–284). Check that a refused first start leaves the tap-to-start fallback usable and that a subsequent successful start registers once. [Android receiver cleanup contract](https://developer.android.com/reference/android/content/Context#unregisterReceiver(android.content.BroadcastReceiver))

F3 [major] P5 can pass without demonstrating that a dark plug-in resumes capture - P3 takes a shell reading at the end of the sleep interval, waking the CPU for the measurement. P5 then accepts a plug-in that lights the screen (plan.md:116–118). The screen-on receiver alone could reopen the recorder and pass that trial even if power connection never restarted it. The later screen-off check starts with capture already running. -> Give P5 its own untouched unplugged interval. Plug in before taking another reading, then inspect the timestamps afterwards. Count a power-only resume when the default display stayed off through recorder startup. If every physical plug-in lights the display, record the dark resume as unverified and report continued listening while plugged as a separate result.

F4 [minor] The unfed-turn trial requires a log line its success path skips - P2 requires an `asleep` line and infers whether the turn was fed from `voice_input` (plan.md:116). An unfed window already has `want == 0` (WakeService.java:308–310), so step 2 cancels the recognizer but skips the log inside `if (want != 0)` (plan.md:89–90). Phone input can run either fed or unfed depending on runtime fallback (Voice.java:163–166, 233–242). -> Use the planned state-transition flag to log entry to `ASLEEP` once, independently of recorder closure. For P2, identify the recorder owner while the turn is active, then verify its release after screen-off. Mark an unavailable input path as unverified.

VERDICT: REVISE
