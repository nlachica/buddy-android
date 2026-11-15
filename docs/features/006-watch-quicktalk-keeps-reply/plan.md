# Plan: 006-watch-quicktalk-keeps-reply
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal
On the watch, opening Buddy again (Home double-press quick talk, or the launcher) while a talk screen waits for a reply
must not end that wait. The first answer is still shown and spoken; whatever the user then says goes to the phone (as
a note if the chat is busy) and its answer follows, in the same conversation. Done when, on the Watch4: (1) waiting on
"write a 400-word story about a fox", a quick-talk reopen plus "what is two plus two" → the story is spoken, then
"four", each once, and the phone chat holds both messages once; (2) a reopen while waiting with the mic cancelled still
lets the first answer be spoken; (3) 003 unchanged: quick talk with nothing pending listens, an unseen reply is shown
first; (4) CI green and the watch APK installed on the Watch4 for the check.

Non-goals: what quick talk opens (still the last chat), the phone side (`WatchLink`, `ChatHub` notes), live voice,
anything outside the watch's reopen/second-message path; no redesign of `Talk`/`TalkActivity`; no pausing of speech
while the mic listens (Risks R1); no watch unit-test setup.

## Design
Two separate things lose the first answer today (research, Summary). Both are fixed on the watch only; the phone already
takes a second message on a running chat as a note and keeps relaying replies and one final `done` on the same chat key
(`WatchLink.java:93-117,155-164`, `ChatHub.java:283-285`).

### A. A relaunch is not a leave (`TalkActivity`, `ChatsActivity`)
`ChatsActivity` is `singleTask`, so a launcher open of the live task finishes the talk screen above it
(`isFinishing()` is true), and `TalkActivity.onDestroy` (`:107-110`) then calls `Talk.left()`, which ends the wait.
The fix tells "the user closed the screen" (Back or a swipe, which keeps the 003 rule that this ends the wait) apart from
"the relaunch cleared it", without relying on the order of the old screen's destroy and the new screen's create
(research R1). There are only two orders, and each has a reliable signal:

- **Screen out of sight at the reopen** (watch face showing, the usual quick-talk case): the old screen was stopped
  long ago with `isFinishing()` false, and the clear destroys it directly; it never gets a finishing `onStop`. A screen
  that is not showing cannot be swiped away.
- **Screen showing at the reopen**: the old screen is paused as finishing; the system resumes `ChatsActivity` (its
  pending new intent is delivered with that resume, so `onNewIntent` runs) and only afterwards stops and destroys the
  old screen. So `ChatsActivity.onNewIntent` always runs before the old screen's `onStop`.

Rule: the talk screen ends the wait from **`onStop`** when `isFinishing()` and no relaunch has happened since it was
created: `ChatsActivity` keeps a static counter `relaunches`, bumped first thing in `onNewIntent`; `TalkActivity`
snapshots it in `onCreate`. Every relaunch clears every talk screen above `ChatsActivity` (singleTask), so a changed
counter means exactly "this screen was cleared by a relaunch". `onDestroy` no longer calls `left()`.

Two small fixes in the same path:
- The new screen listens. Today a fresh open with `Talk.waiting` for the same chat only shows the lines
  (`TalkActivity.java:86-88`); done-when 1 and 2 need the mic. `talkToLast()` adds an extra `"speak"`; with it, waiting
  no longer blocks listening, and after the wait the 003 rule stays (an unseen reply is shown first). Without the extra
  (chat-list tap, the ongoing notification) nothing changes: `news = mine && (Talk.waiting ? !speak : Talk.unseen)`.
- `Talk.onChange` belongs to the screen that set it. With the screen showing at the reopen, the old screen's `onStop`
  comes after the new screen's `onStart` and would null the new screen's redraw. Each screen keeps its redraw in a
  final field and clears `onChange` only if it is still its own.

### B. New words join a live conversation (`Talk`)
`Talk.begin` + `onStartCommand` (`Talk.java:101-114,143-172`) wipe the lines, remove the listener, `hush()` speech and
bump `run`: a second message replaces the first conversation by design. New rule in `begin`: if this chat's
conversation is still **live** (`instance != null && chat.equals(Talk.chat)`: still waiting, or the service is still
saying its replies), the new words **join** it; otherwise everything works as in 003 (lines cleared, old speech
hushed, service started).

Joining keeps `lines`, the speech queue (`pending`, `early`) and, when still waiting, the registered phone listener.
It resets only what belongs to the latest message: `waiting = true`, `heard = false`, `status = ""`, `unseen = false`,
`sender`, `run++`. It then calls the live service directly (`instance.join(listening)`, no `startForegroundService`:
the service is already in the foreground) where `listening` is the old `waiting` (the listener is registered exactly
while waiting; `end()` removes it). `join` puts the "Waiting" text back on the ongoing notification and runs the same
wait set-up as `onStartCommand`, factored out as `await(listening)`: remove the timers (`forceStop`, `noHeard`,
`quiet`, `noStatus`, `ceiling`; not `removeCallbacksAndMessages(null)`, which would drop queued `finished()` posts),
re-acquire the wake lock, arm `ceiling` and `noHeard`, then send: straight away if the listener is still registered
(adding it twice could deliver every message twice), else after `addListener` succeeds, as today. `onStartCommand`
keeps its 003 reset (clear handler, remove listener, `hush()`), notification channel, `startForeground`, wake lock and
TTS creation, then calls `await(false)`.

Joining also when the wait is over but the reply is still being said matters for done-when 1: the story's reply and its
`done` arrive together, so while the user says "what is two plus two" the wait may already have ended with the story
mid-speech; replacing would cut it off and clear its lines. Replies of the new message are queued after it
(`QUEUE_ADD`), so each is said once and in order.

`stop()` clears `instance` at once (if it is this one): a service that is going away must not be joined; `begin` then
starts a new one as before.

**While the latest words are unconfirmed (`!heard`), only `noHeard` ends the wait** (`onPhone`, `Talk.java:188-237`):
- `noHeard` is cancelled by `HEARD` alone (and by `end()`), not by any message (today `:197`): during a join the
  earlier run's steps and replies keep coming and prove nothing about the new words.
- `DONE` while `!heard` is ignored: it ends the run before the new words, which start a run (or note) of their own and
  get their own `heard`. This covers the crossing where the phone sends `done` for the story while the watch's send of
  the new words is in flight (research R4): the phone then starts a new run for them and sends `heard`, replies, `done`.
- `STATUS` with `running: false` while `!heard` is ignored for the same reason (a status asked just before the join can
  answer after it). `noHeard` still catches new words that never arrive ("Buddy on your phone didn't answer.").
- `STEP` and `REPLY` are handled as today (status line; shown and said), and so is `ERROR` (said, wait ends).

Effect on a fresh (non-join) conversation: the phone always sends `heard` (or `error`) first for a new message, so
nothing changes except two edge cases, both noted in Risks: a stale `done` before `heard` no longer ends a new
conversation (better), and a lost `heard` now ends with "didn't answer" even if steps arrive.

The mic button keeps `Talk.silence()` (the user cutting speech on purpose); quick-talk auto-listen still doesn't silence.
Because the join rule is on `Talk`'s state, a mic tap while waiting also keeps the first run tracked (same bug, same fix).

### Alternatives considered
- Static "reopening" flag set in `ChatsActivity.onNewIntent`, checked in `onDestroy`: fails from the watch face, where
  the old screen is destroyed before `onNewIntent` runs (R1).
- Delay `left()` by a grace period and cancel it when a new screen appears: a timing guess.
- Stop ending the wait on leave altogether: drops 003's swipe-away rule; not needed.
- Change launch modes / put `TalkActivity` in its own task: Back would no longer land on the list (003); bigger change.
- Override `onBackPressed`/`finish()` to detect a leave: the Wear swipe-to-dismiss path (AOSP
  `Activity.onWindowDismissed`) finishes through a private `finish(int)`, so it would be missed.
- `news = mine && Talk.unseen && !Talk.waiting` for every open: tapping the "Waiting for Buddy" notification would pop
  the mic over the conversation.
- Join only while `waiting`: cuts the story when its `done` lands while the user is still speaking (above).
- Hold replies while the recognizer listens: more state (must be released if the screen dies mid-listen); out of scope.

## Steps
1. **`Talk`: new words join a live conversation** (`wear/src/main/java/app/buddy/assistant/wear/Talk.java`).
   - `begin`: compute `join` and `listening` before touching state; clear `lines` only when not joining; same resets
     otherwise; `instance.join(listening)` when joining, else `startForegroundService` as today; `changed()`.
   - Factor the wait set-up out of `onStartCommand` into `private void await(boolean listening)` (timer removals, wake
     lock, `ceiling`, `noHeard`, listener-then-send or send-now); `onStartCommand` keeps its 003 reset, channel,
     `startForeground`, wake-lock/TTS creation, then `await(false)`. Add `void join(boolean listening)`: notify "Waiting",
     `await(listening)`.
   - `stop()`: `if (instance == this) instance = null;` first.
   - `onPhone`: move `h.removeCallbacks(noHeard)` into `HEARD`; `DONE`: `if (!heard) return;` first; `STATUS`:
     `if (!running) { if (heard) fail(talk_stopped_working); return; }`. Keep the `if (heard)` quiet re-arm as is.
   - Docs: class Javadoc ("the screen being swiped away" → "the screen being closed while it shows (Back or a swipe; a
     relaunch doesn't count)"), the statics comment "one at a time, a new one replaces it" → new words for the same
     chat join it while it is live, anything else replaces it; comments on `join`/`await`/the `!heard` rule.
   Commit: "Watch: a second message joins the conversation still waiting or speaking (feature 006)".
2. **`TalkActivity` + `ChatsActivity`: a relaunch doesn't end the wait; quick talk while waiting listens**
   (`TalkActivity.java`, `ChatsActivity.java`).
   - `ChatsActivity`: `static int relaunches` (main thread; Javadoc: launcher opens of the live task, each clears the
     talk screen above, which is not a leave); `onNewIntent` bumps it before `talkToLast()`; `talkToLast()` adds
     `.putExtra("speak", true)` to `Talk.intent(...)`; update the `onNewIntent` comment (no longer "same as a fresh open"
     for the wait).
   - `TalkActivity`: `private int opened` set in `onCreate` from `ChatsActivity.relaunches`; `private final Runnable
     redraw = this::changed` used in `onStart`, and `onStop` clears `Talk.onChange` only if it is `redraw`; `onStop`
     calls `Talk.left()` when `isFinishing() && opened == ChatsActivity.relaunches && chat.equals(Talk.chat)`, with a
     comment on the two orders (Design A); remove the `onDestroy` override; `onCreate`:
     `boolean speak = getIntent().getBooleanExtra("speak", false); boolean news = chat.equals(Talk.chat) && (Talk.waiting
     ? !speak : Talk.unseen);` and update its comment.
   Commit: "Watch: opening the app again keeps the wait and listens (feature 006)".
3. **Compile and CI.** Push the branch; CI (`.github/workflows/build.yml`: `:wear:assembleRelease` with the phone
   builds) must be green. Optional local check: `javac --release 8` against `/tmp/buddy-build-005/lib/android.jar` plus
   stubs or the Play services jars (as in 003 reviews); CI is the authority. No version bump is needed for the device
   check (`adb install -r` takes the same version code); bump `BuildInfo` only if the orchestrator ships a release
   (AGENTS.md).

## Test plan
No watch unit tests exist and adding them is a non-goal; the logic is device behaviour (lifecycle order, Data Layer,
TTS). Proof is CI plus a scripted Watch4 run with the phone, using the branch's CI watch APK
(`buddy-watch-feat-006-...apk`, `adb install -r` over wireless adb as in 003). For "each once", watch and listen: each
answer is said once and shows once on the talk screen; check the phone chat afterwards.

| Done-when | Check |
|---|---|
| 1 | (a) Screen showing: "write a 400-word story about a fox", keep looking; while it works, double-press Home; say "what is two plus two" → the story is said, then "four", each once; screen shows both YOU lines and both answers once. (b) Same, but lower the wrist after the first ask, raise it, then double-press (the out-of-sight order). Both: the phone chat has each user message once (the second as a note or the next message) and both answers. |
| 2 | Ask for the story; double-press Home; cancel the mic → the story is still said and shown. Once with the screen showing, once from the watch face. |
| 3 | Nothing pending: double-press → mic opens. Ask the time, lower the wrist until the answer is said, raise, double-press → the answer shows, no mic. Regressions: while waiting, swipe the talk screen away → the wait ends (nothing more said, notification goes); tapping the "Waiting" notification shows the lines without the mic. |
| 4 | `gh run watch` on the branch run: green; `adb install -r` on the Watch4: Success. |

Optional (same fix, not a done-when): while waiting, tap the mic and say something → the first answer is still said.

## Risks
- R1: **Speech and the mic overlap.** A reopen while a reply is being said, or a reply arriving while the user speaks,
  plays TTS into the open recognizer (a reply arriving during a mic tap already did in 003). Recognition may pick it up.
  Not fixed here (non-goal); the device run will show whether it bites; a follow-up could hold replies while listening.
- R2: **Lifecycle order is reasoned from AOSP, not measured.** The design does not depend on destroy/create order,
  but it does rely on "a showing screen is stopped only after `ChatsActivity` gets its new intent" and "a screen out of
  sight gets no finishing `onStop`". Done-when 1(a)/(b) and 2 exercise both orders on the Watch4.
- R3: A lost `heard` in a fresh conversation now gives "didn't answer" after 10 s even if steps arrive (before: steps
  cancelled the deadline). Rare (same link, sent first).
- R4: Removing the task from recents (if the watch offers it) no longer ends the wait, since the screen isn't showing;
  the answer is still said, bounded by the 15-minute ceiling.
- R5: If the phone's agent answers the note inside the story's own message, "four" comes in that message, not after
  it: phone/agent behaviour, out of scope; the Verifier records what happened.
- R6: Joining extends the ceiling (15 min from the latest message), matching the phone, whose wake lock is re-taken on
  each send (`WatchLink.holdWake`).

## Open questions
None blocking. Two visible behaviour changes Nicholas may want to know about (decisions.md): quick talk while waiting now
opens the mic instead of only showing the lines, and a mic tap while waiting no longer drops the first answer.
