# Plan: 036-hey-buddy-locked
Planner: claude-fable-5-2, 2026-11-22. Round 0.

## Goal
Nicholas says "hey buddy" doesn't work while the phone's screen is locked. Find out why with evidence, then fix it,
inside 032's battery budget. Done when: "hey buddy, what time is it" works with the screen locked and off, answered by
voice, verified on the phone.

Non-goals: turning the screen on or showing anything on the lock screen; a wake lock or any other change to idle
listening; a faster transcript or answer; a cue while the phone is unlocked (the waves show there); a log file;
sensitivity changes; anything in feature 035's files.

## Evidence (new, from the phone, 2026-11-22 13:52-13:59)
`phone_shell` does give `logcat -d` and global `dumpsys` (research had neither). Commands that name Buddy's package
(`dumpsys activity … app.buddy.assistant`, `cmd appops get …`) are BLOCKED by Buddy; not worked around.

**The whole locked flow ran on this phone today, on 2.3.18-fork.2, unplugged:**

| Time | What the log shows |
|---|---|
| 13:45:49.9 | screen off (display state 2→1), bubble window goes `View.GONE` (lock) |
| 13:47:00.835 | `HeyBuddy: wake 3: medium (0.50 x 3) peak 0.870 hops 0.773 0.870 0.624`, 71 s after screen-off |
| 13:47:00.838 | `BuddyWake: turn 3 (wake 3)` |
| 13:47:00.9-13:47:11 | the hidden bubble's width changes 9 times: dictation partials arriving (Claude input) |
| 13:47:18.8 | the request arrives in the open chat ("Wait, aren't they still wireless? No.") |
| 13:47:22.2 | the agent's reply |
| 13:47:23.6-13:47:36.7 | Buddy speaks it on the phone (`mic open: VOICE_COMMUNICATION, echo canceller on`, two AAudio streams) |

(The wake may have been a false one; it doesn't matter: detector, turn, dictation, send and speech all ran locked.)

**Still listening deeper into sleep.** At 13:53:12 and 13:56:14 (7 and 10 min after screen-off; `mWakefulness=Dozing`,
keyguard showing, `mIsPowered=false`, light doze `IDLE`, deep doze `INACTIVE`): `dumpsys audio` lists Buddy's recording
`active? true … VOICE_RECOGNITION … silenced:false`; AudioFlinger's input thread `Standby: no`; `dumpsys power` holds
`PARTIAL_WAKE_LOCK 'AudioIn'` for Buddy's uid since the last mic open. So the open recording does keep the CPU awake
(032 had this as "unverified") and Android doesn't silence the microphone.

**So research's three candidates are each refuted for these conditions:** A (detector deaf): no. B (turn can't run
locked): no. C (reply not spoken): no. It is not a phone setting either.

**What a locked turn is like for the user:** nothing. `Bubble.showWaves` skips the waves when the bubble is hidden
(`Bubble.java:1799`), there is no sound or vibration on a wake (`WakeWord.onWake`, vibration only when it cuts speech),
and every failure is a toast (`WakeWord.java:206,213,236`, `Bubble.java:1741`), which a locked phone doesn't show.
At 13:47 the phone stayed dark and silent for 22.7 s between the wake and the first spoken word. A request that
worked, one that was misheard and a detector that never woke are indistinguishable. No request reached the open chat
(the Orchestrator's Claude session) between 12:31 and Nicholas's report at 12:44, so that attempt either wasn't
detected or ended in an unseen toast.

**Not captured:** the failing attempt itself (logcat on this phone holds ~10 minutes; it starts at 13:42). Not
sampled: deep doze (30+ min still), the service not running after an update ("Tap to turn on Hey Buddy"), battery
saver. Phone facts: ringer silent, battery 5%, `voice_input=claude`, `voice_output=chatgpt`, `audio_device=auto`.

## Design
The detector and the turn already work locked, so nothing changes there. The fix makes a locked turn perceivable and
its failures audible, and makes the next "it didn't work" readable from the log:

1. **A cue on the wake when the phone can't show it.** In `WakeWord.onWake`, when `Triggers.locked(svc)` (keyguard
   up or screen off, the existing helper): one short beep (`ToneGenerator`, `STREAM_MUSIC`, `TONE_PROP_BEEP`, the
   stream the answer follows) and the 30 ms vibration that a speech-cutting wake already gives. Unlocked: unchanged.
   It plays whatever the ringer mode is, like the spoken answer does (Nicholas's phone is on silent; see Q1).
2. **Failures are spoken when the phone can't show a toast.** A pure rule `WakeWord.tell(hidden, error)` →
   `TOAST` (not hidden), `SPEAK` (hidden, an error: listening couldn't start or broke, the request couldn't be sent),
   `NONE` (hidden, nothing heard: a false wake must not make the phone say "Didn't catch that" to an empty room).
   `SPEAK` is `Voice.speak(svc, message)`, after the turn has ended. `Bubble.talk`'s immediate `Voice.listen` error
   goes through `WakeWord.ended` for a wake turn, so it follows the same rule.
3. **One `BuddyWake` log line per turn outcome and per dropped wake** (today both are silent): dropped and why,
   heard (word count only, never the text), listening again, sent / not sent and why, nothing heard, ended and why,
   cancelled. Reading `logcat -s HeyBuddy BuddyWake` then says which stage a failed attempt died in.

Why this and not more: the evidence shows no stage failing, so a wake lock, a screen wake or a restart watchdog would
fix nothing observed and cost battery. If the device checks below show a stage that does fail (deep doze, say), the
new log names it and the plan comes back with that evidence.

Alternatives, one line each:
- Change nothing, tell Nicholas it works: leaves the dark, silent 20 s that reads as broken, and no way to tell next time.
- `PARTIAL_WAKE_LOCK` while idle (research's A fix): the audio server already holds `AudioIn` for Buddy; redundant.
- Turn the screen on / `WakeActivity` over the lock screen: new behaviour 032 didn't accept; battery and privacy.
- Vibration only: silent, but not noticeable from across a room, which is where hands-free is used (Q1 option b).
- Speak "Didn't catch that" for nothing-heard too: false wakes would talk to an empty room.
- A persistent log file: `phone_shell` logcat within ~10 min is enough for the Verifier; no second use.
- Cue keyed on the bubble's visibility (covers "bubble off, unlocked" too): a second notion of hidden and a Bubble
  change for a case the brief doesn't ask about.

## Steps
0. **Orchestrator with Nicholas, in parallel with plan review; not a gate.** Ask him: lock the phone, wait a minute,
   say in one breath "hey buddy, what time is it", wait 30 s. Then `phone_shell`:
   `logcat -d -v time -s HeyBuddy:V BuddyWake:V | tail -30`. Answered (expected): the cause is the missing feedback;
   go on. Not answered: the log says where it stopped (no `wake` line = not detected; `wake` without `turn` = dropped;
   `turn` and no speech = dictation, send or voice). Add `dumpsys power | grep -E "mWakefulness|AudioIn"` and
   `dumpsys audio | sed -n '/RecordActivityMonitor/,/^$/p'`, append it to research.md and rerun the Planner. If he
   isn't around, go on: steps 1-3 are what makes a later failure diagnosable.
1. **The rule and its test.** `WakeWord.java`: `enum Tell { TOAST, SPEAK, NONE }`, `static Tell tell(boolean hidden,
   boolean error)`. `WakeWordTest.java`: the four input pairs.
2. **Failures follow the rule; outcomes are logged.** `WakeWord.java`: replace `toast(message)` with
   `tell(message, error)` (`hidden = Triggers.locked(svc)`): `nothing()` → error false; `ended()` and `send()` → error
   true, called after `endTurn`. Add the log lines of Design 3 (`turn <turnSeq> …`, `wake <seq> dropped: …` including
   the early `return` at `:147`). `Bubble.java:1740-1743`: for a wake turn call `WakeWord.ended(turn, window, err)`
   instead of `toast(err)` + `endTurn`.
3. **The cue.** `WakeService.java`: `void cue()` (main thread): `ToneGenerator(STREAM_MUSIC, 80)`,
   `startTone(TONE_PROP_BEEP)` (check the javadoc: the shortest general beep, 150 ms at most), `release()` after
   300 ms on `main`; a `RuntimeException` from the constructor is caught and logged (the vibration still happens).
   `WakeWord.onWake`: `hidden = Triggers.locked(svc)`; vibrate when `cut || hidden`; `s.cue()` when `hidden`; before
   `openWindow()`. No new permission, string or setting.
4. **Docs.** `docs/GUIDE.md` (Hey Buddy paragraph): locked or screen off, Buddy beeps and vibrates when it hears
   "hey buddy", errors are spoken, the answer can take 10-20 s, the beep ignores silent mode. `032 battery.md`: one
   dated line, "the recording holds the audio server's `AudioIn` wake lock (dumpsys power, 2026-11-22); verified".
   This folder's `decisions.md` as decisions come up.
5. **Orchestrator: CI, release, phone rows.** CI on the PR; release per AGENTS.md (`tools/release-check.sh` first;
   035 may be ready for the same release); `update_buddy`; then the phone rows below.

## Test plan
| Done-when | Proof |
|---|---|
| Cause found with evidence | The Evidence section (log lines and dumpsys above); Step 0 confirms or reopens it |
| Rule is right | `WakeWordTest` `tell` cases; CI `testGithubDebugUnitTest` |
| Builds in both flavors, F-Droid path untouched | CI `assembleGithubRelease assembleFdroidRelease`, reproducibility step |
| "hey buddy, what time is it" locked, screen off, unplugged, 1 min after locking: answered by voice | Phone, after release, 5 tries, ≥ 4: beep within 1 s, spoken answer; `logcat -s HeyBuddy BuddyWake` shows `wake N`, `turn N`, `turn N sent` for each |
| Same after 30+ min locked and still (deep doze) | Phone, 1 try; `dumpsys deviceidle \| grep mState` before it. Fails: the log names the stage; back to the Planner |
| "hey buddy" alone, locked: beep, then the request is taken | Phone, 1 try |
| The beep doesn't garble the request | The 5 requests above read correctly in the chat |
| A wake with nothing said, locked: beep, then silence | Phone: say "hey buddy" and nothing else; log `turn N: nothing heard`, nothing spoken |
| Unlocked is unchanged | Phone: waves, no beep; a `Didn't catch that` toast still shows |
| Battery budget kept | By construction: no code runs between wakes that didn't before (no wake lock, timer or receiver added) |

The phone rows need the fork release and Nicholas speaking, so they stay `pending` in verify.md until then
(`VERDICT: PARTIAL` before, per the playbook). Read logcat within ~10 minutes of each try.

## Risks
- **The attempt that failed for Nicholas may be a condition not sampled** (deep doze, service not running after an
  update, battery saver at low charge). Step 0 and the 30-minute row test it; the new log lines name the stage.
- **The beep lands in the audio that is transcribed** (a fed turn keeps the recorder open). It is tens of ms; the
  "doesn't garble" row checks it. Fallback, one line: drop the tone, keep the vibration.
- **A false wake at night beeps at media volume though the ringer is silent.** Sensitivity (034) is the lever; Q1.
- **Buddy's battery mode and FGS state weren't readable** (`phone_shell` blocks Buddy-targeted commands). The audio
  and power dumps make them unlikely causes; Nicholas can read Settings → Apps → Buddy → Battery if Step 0 fails.
- A spoken error while locked starts speech (detector goes SPEAKING with echo cancelling), the same path as a reply.

## Open questions
None blocking.

## Questions for Nicholas
1. **What should a locked phone do the moment it hears "hey buddy"?**
   (a) Recommended: a short beep plus a short vibration, even when the phone is on silent (as yours is today): from
   across a room only a sound tells you it heard you, and the spoken answer ignores silent mode already.
   (b) A short vibration only: quiet, but you won't notice it unless the phone is on you or a hard table.
   (c) The beep every time, also when unlocked.
