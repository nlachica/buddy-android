# Plan: 036-hey-buddy-locked
Planner: claude-fable-5-2, 2026-11-22. Round 2 (answers plan-review round 2).

## Goal
Nicholas says "hey buddy" doesn't work while the phone's screen is locked. Find out why with evidence, then fix it,
inside 032's battery budget. Done when: "hey buddy, what time is it" works with the screen locked and off, answered by
voice, verified on the phone.

Non-goals: turning the screen on or showing anything on the lock screen; a wake lock or any other change to idle
listening unless the baseline (step 0) shows that stage failing; a wake cue or spoken errors unless Nicholas asks for
them (Q1); a faster transcript or answer; a log file; sensitivity changes; anything in feature 035's files.

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

(The words were someone else's talk, not a request to Buddy, and the open chat was this pipeline's, with its agent
working. So this shows that each stage can run on a locked phone. It is not the time question, and not the attempt
Nicholas reported.)

**Still listening deeper into sleep.** At 13:53:12 and 13:56:14 (7 and 10 min after screen-off; `mWakefulness=Dozing`,
keyguard showing, `mIsPowered=false`, light doze `IDLE`, deep doze `INACTIVE`): `dumpsys audio` lists Buddy's recording
`active? true … VOICE_RECOGNITION … silenced:false`; AudioFlinger's input thread `Standby: no`; `dumpsys power` holds
`PARTIAL_WAKE_LOCK 'AudioIn'` for Buddy's uid since the last mic open. So the open recording does keep the CPU awake
(032 had this as "unverified") and Android doesn't silence the microphone.

**What this settles and what it doesn't.** Settled for these conditions (1-10 min locked, unplugged, light doze): the
microphone isn't silenced, the CPU stays awake, and a wake can run through dictation, send and speech. **Not settled:
why Nicholas's attempt failed.** No request reached the open chat between 12:31 and his report at 12:44, and that
attempt's log is gone (logcat here holds ~10 minutes; it starts at 13:42). It may not have been detected, may have
been dropped, may have died in dictation, or may have been answered late or on another device. Not sampled: deep doze
(30+ min still), the service not running after an update ("Tap to turn on Hey Buddy"), battery saver, a chat with no
agent working, the watch connected. Phone facts at the time: ringer silent, battery 5%, `voice_input=claude`,
`voice_output=chatgpt`, `audio_device=auto`.

**What a locked turn is like for the user today** (a candidate explanation, not a diagnosis): the phone stays dark and
silent until the answer is spoken (22.7 s at 13:47). `Bubble.showWaves` skips the waves when the bubble is hidden
(`Bubble.java:1799`), a wake makes no sound, and every failure is a toast (`WakeWord.java:206,213,236`,
`Bubble.java:1741`), which a locked phone doesn't show.

## Design
**Diagnose first; no code before the baseline.** The reported failure has no diagnosis, so the first decision point is
the exact request from the brief, on the installed build (2.3.18-fork.2), locked and screen off, with Nicholas
speaking and the attempt traced end to end (step 0). What ships depends only on where that attempt stops:

| Step 0 result | Meaning | What follows |
|---|---|---|
| Before the attempt: the notification says "paused: …" or "Tap to turn on Hey Buddy", or there is none | The listener isn't running | One locked attempt as it is, then change only that one thing (tap the notification; end the other recording) and repeat. If that alone restores the spoken time it is the cause: back to the Planner with the state and the `BuddyWake` lines (why it stopped, whether Buddy should recover by itself) |
| The time is spoken, screen stayed off | It works in these conditions | Record the pass. Ask Nicholas what was different when it failed (step 0b) and retest in that condition. Nothing reproduces: no code; run the test plan's spoken-answer rows, report "could not reproduce", ask Q1 and Q2 |
| A phone setting alone restores the spoken answer | The brief's stop case | Write down the exact setting (GUIDE.md, and tell Nicholas). Stop |
| No `wake` line at the attempt's time | Not detected | Collect the detector evidence (step 0, "not detected"), back to the Planner with it |
| `wake N`, no `turn` | `WakeWord.onWake` dropped it; no log says why | Step 1 (drop reasons), release, retest |
| `turn N`, no request in the chat after 2 min | Dictation: nothing heard or an error; no log says which | Step 1 (turn outcomes), release, retest |
| A request in the chat that doesn't mean "what time is it" | Dictation heard something else | Back to the Planner with what he said, the request's text and `voice_input`. No diagnostic release |
| The time question in the chat; the reply isn't the time, or isn't what he heard | The agent's answer, or the reading | Back to the Planner with the request, the reply and what he heard. No diagnostic release |
| Request in the chat, no reply | The agent run, not the wake path | Back to the Planner with the chat's state |
| Reply in the chat, not heard on the phone | Playback or route (phone, headset or watch) | Collect the playback evidence (step 0, "not heard"), back to the Planner |

Step 1 is the only code this plan authorises, and only for the two rows where today's logs can't tell the stage apart.
A fix for a failing stage is planned when the stage is known, in a new Planner round with that evidence.

**Deferred: the wake cue and spoken errors** (round 0's design). The brief asks for a spoken answer, not an
acknowledgment, and a possible false wake was seen at 13:47, so a beep on a silent phone has a real cost. They come
back only if Nicholas says, after a passing baseline, that the silent wait was the problem and he wants a sign (Q1).
Then, through a new Planner round: the cue as in round 0, and any spoken failure is one fixed, localized sentence
(a new string, e.g. "Sorry, that didn't work"), never the error's own text: dictation errors carry shell output
(`ClaudeDictation.java:75`, `CodexDictation.java:514`). Its test would pass a distinctive error string through the
callback and check that it reaches neither speech nor the log.

**Log lines carry no error text.** Step 1's lines are literals at each call site plus numbers (wake and turn
sequence, word count, the `State` name). No `message`, transcript or exception text is passed to `Log`.

Alternatives, one line each:
- Build the cue now and test afterwards (round 0): fixes nothing if the wake was never detected; rejected in review.
- Ship step 1's log lines before the baseline: a release for lines that are needed in only two of the outcomes.
- `PARTIAL_WAKE_LOCK` while idle: the audio server already holds `AudioIn` for Buddy; nothing shows it is needed.
- Turn the screen on / `WakeActivity` over the lock screen: new behaviour 032 didn't accept; battery and privacy.
- A persistent log file: only worth it if a failure recurs that nobody can reproduce within logcat's ~10 minutes.

## Steps
0. **Baseline. Orchestrator with Nicholas, installed build, no code. Gate: nothing below runs before it.**
   - **First, before Nicholas opens a Buddy screen or taps anything**, the orchestrator records the listener's state:
     `dumpsys notification --noredact | grep "android.title=.*Hey Buddy"` (today: "Hey Buddy is listening"; the
     `notifications` tool doesn't list Buddy's own) and `logcat -d -v time -s BuddyWake:V | tail -40` (`service
     started`, `start requested`/`refused`, `paused: …`, `listening stopped: …`, `off`). A Buddy screen resuming calls
     `WakeWord.ensure` (`BuddyApp.java:19`), which starts a missing service (`WakeWord.java:57-70`), so a test after
     opening Buddy could hide a stopped listener. Limit: the orchestrator reaches Nicholas through a Buddy chat, so a
     screen has usually resumed minutes before (and has since his 12:44 report); 0b covers that case.
   - Not "listening" (paused, "Tap to turn on", no notification): the Design table's first row. Don't open a new chat
     first.
   - Listening: Nicholas opens a **new chat** so the current chat has no agent working (a busy chat queues the request
     as a note, `ChatHub.java:343-345`; this pipeline's chat is busy). With the service running, opening Buddy changes
     nothing (`ensure` returns at `WakeWord.java:65`). Unplugged. Record: `buddy_settings` `voice_input`,
     `voice_output`, `audio_device`; a connected headset (Bluetooth or wired); whether the watch is connected and on
     his wrist; `settings get global low_power`; battery %.
   - Attempt: lock, screen off, leave it one minute, say in one breath "hey buddy, what time is it". Don't touch the
     phone for **2 minutes** (Claude input can take 45 s to start and 20 s to transcribe after the words,
     `ClaudeDictation.java:117-119,147-155`, before the agent and the voice). Note the time T.
   - Nicholas reports: what he heard, from which device (phone, headset, watch), about how long after speaking, and
     whether the screen stayed off.
   - Collect within 10 minutes of T: `logcat -d -v time -s HeyBuddy:V BuddyWake:V | tail -40`; after he unlocks,
     `read_screen` on that chat: the request text, the reply, their times; `dumpsys power | grep -E
     "mWakefulness|AudioIn"`.
   - Classify with the Design table: compare the chat's request with the time question by meaning (other wording of
     the same question is fine), and the chat's reply with what he heard. A request with no reply yet is **pending**,
     not a failure: wait, then classify.
   - Not detected: also the `HeyBuddy` near-miss lines around T (scores against the threshold), the notification's
     text, `dumpsys audio | sed -n '/RecordActivityMonitor/,/^$/p'`, and Nicholas reads Settings → Apps → Buddy →
     Battery and the sleeping-apps lists (`phone_shell` blocks Buddy-targeted commands).
   - Not heard though the reply is in the chat: route evidence from the playback interval only: logcat around the
     reply's time (phone playback at 13:47 showed as `mic open: VOICE_COMMUNICATION` and AAudio streams) and `dumpsys
     audio`'s playback and device logs if they reach back that far. A linked watch doesn't settle it: the destination
     is decided when the reading starts, from `audio_device`, and in Auto a headset first, then the watch only if
     reachable and worn (`ReadQueue.java:173-189`, `WatchVoice.java:64-66`, `AudioRoute.java:155-163`). No evidence
     from the interval: write "destination unknown".
   - Append the record (T, what he heard and where, the log lines, the chat times, the row it fell in) to
     `research.md` under "Baseline, <date>".
   - **0b, after a pass:** ask what was different when it failed: how long the phone had been lying locked, plugged
     in or not, a chat's agent still working, the watch or a headset on, how long he waited, what he expected to hear,
     and whether Buddy had just been updated or the phone restarted and what the Hey Buddy notification said. Repeat
     the attempt in the condition he names. Long idle: the deep-doze row of the test plan. Update or restart: he
     restarts the phone, unlocks, leaves Buddy unopened, reads the notification in the shade himself, tries locked,
     then opens Buddy and reports; logcat is read then. Passes there too, or he can't say: stop trying to reproduce
     and go on to the test plan's spoken-answer rows; the diagnosis is "could not reproduce" (Q2).
1. **Only for the "dropped" or "no request" rows: diagnostic log lines.** `WakeWord.java`, tag `BuddyWake`, literals
   only: in `onWake`'s drop branch `wake <seq> dropped: turn open | state <STATE> | listening | live call | no bubble`
   (and `no service` for the early return at `:147`); `turn <n>: heard <k> words`, `turn <n>: listening again`,
   `turn <n>: nothing heard`, `turn <n>: listen error`, `turn <n>: sent`, `turn <n>: not sent`, `turn <n>: cancelled`.
   `Bubble.java:1740-1743`: for a wake turn call `WakeWord.ended(turn, window, err)` in place of `toast(err)` +
   `endTurn` (same toast, same end, plus the line). Toasts and behaviour unchanged.
2. **Only after step 1. Orchestrator:** CI on the PR, release per AGENTS.md (`tools/release-check.sh` first; 035 may
   be ready for the same release), `update_buddy`, repeat step 0, back to the Planner with the stage the new lines name.
3. **Docs, whichever way step 0 goes.** `032 battery.md`: one dated line, "the open recording holds the audio
   server's `AudioIn` wake lock (dumpsys power, 2026-11-22)", replacing "unverified". Once the test plan's 5-try row
   passes, `docs/GUIDE.md` (Hey Buddy paragraph): one sentence, "With the phone locked Buddy answers by voice; the
   screen stays off and the answer can take 20 seconds or more." This folder's `decisions.md` as decisions come up.

## Test plan
| Done-when | Proof |
|---|---|
| Diagnosis: cause found with evidence (recorded apart from the rows below) | Step 0's record in `research.md`: a failing attempt traced to a Design-table row, or one condition whose change alone restores the spoken answer. A pass, or Nicholas's guess at what differed, is not a cause: then verify.md says "diagnosis: could not reproduce, unresolved" and the feature closes only if Nicholas accepts that (Q2) |
| "hey buddy, what time is it" locked, screen off, unplugged, 1 min after locking: answered by voice | Phone, on the build that ends this feature (the installed one if no code ships): 5 tries, ≥ 4. Each from a chat with no agent working, with T, `wake N` and `turn N` in the log, the time question and its reply in the chat, and Nicholas hearing the time with the screen off (which device is recorded). A request that isn't the time question, or a spoken answer that isn't the time, is a failed try. Up to 2 min per try; an unanswered request stays pending until then. Run in every branch, the no-reproduction one included |
| Same in deep doze | Phone, 1 try. Counts **only if** `dumpsys deviceidle \| grep -E "mState\|mLightState"` shows deep `mState=IDLE` immediately before the words, screen still off (light `IDLE` with deep `INACTIVE`, as at 13:53, is not it). Otherwise write the state seen and "deep doze: unverified". Natural idle: the phone lying still, unplugged, 30+ min. Forcing it (`dumpsys deviceidle force-idle`, device-wide) only if natural idle isn't reached and Nicholas agrees; then `dumpsys deviceidle unforce` when the try ends or is interrupted, and `mState` must read `ACTIVE` with the screen on before the row is closed |
| Step 1 only: log lines name the stage, leak nothing | Code review: every new `Log.i("BuddyWake", …)` operand is a literal, a sequence number, a word count or a `State`. Phone: the retest's lines |
| Step 1 only: builds in both flavors, F-Droid path untouched | CI `testGithubDebugUnitTest`, `assembleGithubRelease assembleFdroidRelease`, reproducibility step |
| Unlocked is unchanged | Step 1 only: phone, two unlocked turns. A "hey buddy" request: waves, spoken reply. Separately, "hey buddy" then silence: the nothing-heard toast still shows |
| Battery budget kept | By construction: no code ships, or only log lines on wake and turn events; nothing runs between wakes that didn't before |

The phone rows need Nicholas speaking, so they stay `pending` in verify.md until then (`VERDICT: PARTIAL` before, per
the playbook). Read logcat within ~10 minutes of each try.

## Risks
- **The baseline passes and the failure can't be reproduced.** Then there is nothing to fix, but the spoken-answer
  rows still run. The report is "works in the tested conditions; could not reproduce; cause unknown", and closing on
  that is Nicholas's call (Q2). If it happens again, he tells Buddy within ten minutes so the log can be read.
- **A planned test can't see a listener that restarted when Nicholas opened Buddy.** The orchestrator's record comes
  first, and 0b's restart retest is the only way to see that state on purpose.
- **The gate needs Nicholas.** The pipeline waits on step 0; no stage can stand in for him speaking to a locked phone.
- **Battery was at 5%.** Battery saver may have been part of the failing attempt; step 0 records `low_power`.
- **Buddy's battery mode and foreground-service state aren't readable** (`phone_shell` blocks Buddy-targeted
  commands). Nicholas reads them in Settings if the attempt isn't detected.
- **A diagnostic release costs a round trip** (step 1 → release → retest) before any fix. Accepted: it is small, and
  guessing the stage costs more.

## Open questions
1. Step 0's result: which row of the Design table does "hey buddy, what time is it" fall in on the locked phone, on
   2.3.18-fork.2? Needs Nicholas and the phone, not the Researcher. No code step runs before it.

## Questions for Nicholas
Q1: only after step 0 passes and 0b reproduces nothing. Q2: only after the spoken-answer rows also pass.
1. **When it works, the locked phone stays dark and silent until the answer, about 20 seconds. Was that what looked
   like "doesn't work", and do you want a sign that it heard you?**
   (a) Recommended: no, leave it. Nothing ships beyond the diagnosis: the brief asks for a spoken answer, and a sign
   would also fire on a false wake.
   (b) Yes: a short vibration when a locked phone hears "hey buddy". Quiet; you notice it only if the phone is on you
   or on a hard table.
   (c) Yes: a short beep plus the vibration, also when the phone is on silent, so you hear it from across a room.
2. **It works every time we test it and we can't make it fail. Close this as "works, cause unknown"?**
   (a) Recommended: yes, close. Costs nothing; if it fails again, tell Buddy within ten minutes and the log will show
   where it stopped.
   (b) Keep it open and add a small Hey Buddy log that lasts longer than ten minutes, so the next failure explains
   itself whenever you report it. One more release.
