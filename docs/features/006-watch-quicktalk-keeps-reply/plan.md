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

Joining keeps `lines`, the speech queue (`pending`, `early`), `run` and, while still waiting, the registered phone
listener. It calls the live service directly (`instance.join(wasWaiting)`, no `startForegroundService`: the service is
already in the foreground). `wasWaiting` is `waiting` before `begin` set it again.

**Words are sent one at a time** (plan-review F1). `Talk.sender` becomes `queue`, a `List<Sender>` of words still to
send, oldest first. `begin` adds the new words to it (a non-join `begin` clears it first). The service's `next()` sends
the first queued words: `heard = false`, re-arm `noHeard`, then send them once the phone listener is registered. Words
in flight are **settled** by their `heard`, by a refusal (`/buddy/error`, which the phone sends only for refused words,
`WatchLink.refuse`), by a failed send, or by `noHeard` giving up on them. Only after that are the next queued words
sent. So:
- a join while earlier words are still in flight (registration, discovery, or `heard` not back yet) just queues: the
  earlier send's callbacks stay valid, and they keep going in order;
- `heard` can only confirm the words in flight. There is still one `heard` flag, and it now means "nothing in flight";
- `run` goes up only on a new conversation (`begin`, as today), in `settle` (below) and in `end()`. So late callbacks
  of words already given up can't send them after "didn't answer" or settle the next words, and callbacks of an ended
  wait can't act after a join starts waiting again.

Registration is no longer guessed from `waiting`. `register()` stores the `Task` from `addListener` in a field
`registration`. Its failure ends the wait (`talk_connect`, as today) only if it is still the current registration while
waiting. `next()` hangs its send on `registration.addOnSuccessListener` (it runs at once if registration is done).
`onStartCommand` keeps its 003 reset (clear handler, remove listener, `hush()`), channel, `startForeground`, wake lock,
`ceiling` and TTS creation, then calls `register(); next();`.

`join(wasWaiting)`:
- if the wait was over (the reply is still being said): remove `forceStop`, put "Waiting" back on the notification,
  `register()`, `next()`;
- else if nothing is in flight (`heard`), `next()`; otherwise the words wait in the queue;
- in every case: re-arm `ceiling` and re-acquire the wake lock.

Joining also when the wait is over but the reply is still being said matters for done-when 1. The story's reply and
its `done` arrive together, so while the user says "what is two plus two" the wait may already have ended with the
story mid-speech; replacing would cut it off and clear its lines. Replies of the new message are queued after it
(`QUEUE_ADD`), so each is said once and in order.

`stop()` clears `instance` at once (if it is this one): a service that is going away must not be joined; `begin` then
starts a new one as before.

**A failure of some words is not a failure of the conversation** (plan-review F2). A new static `accepted` means the
phone took words of this conversation (`heard`) and hasn't sent `done` since, so a run of ours is still going and will
send replies and a `done`.
- Failures that belong to the words in flight go through `settle(error)`: a failed send (the `Sent` callback),
  `/buddy/error`, and `noHeard` ("Buddy on your phone didn't answer."). `settle` does `run++`, `heard = true`, removes
  `noHeard`, and shows and says the error. Then, if words are queued, `next()`. Otherwise, if `accepted`, the wait goes
  on with the listener, speech, `quiet`/status checks and `ceiling` untouched. Otherwise `end()`. Nothing is resent.
- Failures that belong to the conversation still `fail` and `end()` as in 003: registration failure, `ceiling`, lost
  contact (`noStatus`), and `status running:false` with nothing in flight. So does the screen being closed (`left()`).
- `end()` also clears `queue` (words not yet sent go with the wait) and `accepted`, and bumps `run`.

Messages from the phone (`onPhone`, `Talk.java:188-237`):
- `HEARD`: `heard = true`, `accepted = true`, remove `noHeard`, add the YOU line, then `next()` if words are queued.
  `noHeard` is no longer removed by every message (today `:197`): during a join the earlier run's steps and replies
  prove nothing about the new words.
- `DONE`: say "stopped" if so (as today), then `accepted = false`. If nothing is in flight, `end()`. Otherwise the
  `done` belongs to the run before the words in flight (the phone's `done` crossed the watch's send, research R4), so the
  wait goes on and those words decide it: their `heard` starts a run with its own replies and `done`; their failure
  (`settle`) finds `accepted` false and ends the wait. It can't get stuck: `noHeard` bounds the words in flight.
- `ERROR`: `settle(text)`.
- `STATUS` `running: false`: `fail(talk_stopped_working)` only if nothing is in flight. While words are in flight it is
  ignored: a status asked before the join can answer after it, and the new words will start their own run.
- `STEP` and `REPLY` as today; the `if (heard)` quiet re-arm stays.

Effect on a fresh (non-join) conversation, with one set of words: the phone always sends `heard` (or `error`) first, so
nothing changes except two edge cases, both in Risks: a stale `done` before `heard` no longer ends a new conversation
(better), and a lost `heard` now ends with "didn't answer" even if steps arrive. A failed send, a refusal or `noHeard`
end it as today (`accepted` is false). A non-join `begin` sets `accepted = false` and `heard = false`.

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
- Send joined words at once and count unacknowledged ones (`unheard`): no order between two sends in flight, and a late
  failure needs per-send ids anyway; one in flight at a time is simpler and keeps order (plan-review F1).
- Refuse or replace new words while earlier ones are in flight: drops words, or the first answer, in a 1-10 s window.

## Steps
1. **`Talk`: new words join a live conversation, sent one at a time** (`wear/src/main/java/app/buddy/assistant/wear/Talk.java`).
   - Statics: `sender` becomes `private static final List<Sender> queue`. Add `static boolean accepted`. Docs: `heard`
     means "nothing in flight (the latest words were heard, refused or given up)"; `run` "goes up on a new conversation,
     an ended wait and words given up: callbacks from before must not touch what follows".
   - `begin`: compute `join = instance != null && chat.equals(Talk.chat)` and `wasWaiting = waiting` first. If not
     joining: set chat/title/accent, `lines.clear()`, `queue.clear()`, `heard = false`, `accepted = false`, `run++`.
     Always: `waiting = true`, `status = ""`, `unseen = false`, `queue.add(send)`. Then `instance.join(wasWaiting)` if
     joining, else `startForegroundService` as today; `changed()`.
   - Service: `private Task<Void> registration` and `register()`, which stores `addListener(listener)` and fails with
     `talk_connect` on its failure only if `waiting && registration == thatTask`. `next()`: take `queue.remove(0)`,
     `heard = false`, re-arm `noHeard`, and in `registration.addOnSuccessListener` send it with
     `valid = () -> active(mine)` (`mine = run`, captured, not bumped) and `error -> { if (error != null &&
     active(mine)) settle(error); }`. `settle(String)` as in Design B. `noHeard` becomes `() -> settle(getString(
     R.string.talk_no_answer))`.
   - `onStartCommand`: keep the 003 reset, channel, `startForeground`, wake lock, `ceiling` and TTS lines; replace the
     listener/send block with `register(); next();`. Add `void join(boolean wasWaiting)` as in Design B.
   - `end()`: also `queue.clear(); accepted = false; run++;`. `stop()`: `if (instance == this) instance = null;` first.
   - `onPhone`: drop the blanket `h.removeCallbacks(noHeard)`. `HEARD`: `heard = accepted = true`, remove `noHeard`, YOU
     line, `if (!queue.isEmpty()) next()`. `DONE`: stopped line, `accepted = false`, `if (heard) end(); else changed();` return.
     `ERROR`: `settle(text); return;`. `STATUS`: `if (!running) { if (heard) fail(talk_stopped_working); return; }`. Keep
     the `if (heard)` quiet re-arm.
   - Docs: class Javadoc ("the screen being swiped away" → "the screen being closed while it shows (Back or a swipe; a
     relaunch doesn't count)"; a failure of later words doesn't end an accepted run). The statics comment "one at a
     time, a new one replaces it" becomes: new words for the same chat join it while it is live, sent one at a time;
     anything else replaces it. Comments on `join`/`next`/`settle` and the `DONE` deferral.
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

**Event-order traces (from plan-review F1/F2; the Code Reviewer checks each row against the code).** A = earlier
words, B = joined words.

| # | Events | Expected |
|---|---|---|
| T1 | A begun; B said while A's registration, discovery or `heard` is pending | B queued; A's callbacks stay valid and A is sent; A `heard` → YOU A, then B sent; B `heard` → YOU B; each sent once, in order |
| T2 | A heard; B sent; A `done` while B in flight; B `heard`, B reply, B `done` | A's `done` deferred (`accepted` false, still waiting); B's reply said after A's; B's `done` ends the wait |
| T3 | A heard (accepted); B's send fails | error said, still waiting, no resend; A's later reply shown and said once; A's `done` ends the wait |
| T4 | A `done` deferred (B in flight); then B's send fails | `settle` finds nothing accepted → wait ends (not stuck) |
| T5 | A accepted; B refused (`/buddy/error`), or no `heard` for B in 10 s | as T3 |
| T6 | `status running:false` while B in flight / with nothing in flight | ignored / "stopped working", wait ends |
| T7 | Fresh conversation: its only words fail to send, are refused, or get no `heard` | error, wait ends (003) |
| T8 | Wait over, A still being said; B said | listener registered again, B sent after it succeeds; A's speech goes on |
| T9 | Words given up by `noHeard`, then their late send failure or `valid` check | no second error line, never sent late (`run` bumped) |

**Probe runs on the Watch4 (Verifier; not done-when items).** A throwaway branch `probe/006` off the feature branch
adds one commit in `Phone.send`, never merged: if the body's text contains "slow", discovery starts 7 s later (main
`Handler.postDelayed`, under the 10 s `noHeard`); if it contains "broken", `sent.done("Probe: send failed")` without sending. Build it with
`gh workflow run build.yml --ref probe/006` (artifact `buddy-watch-probe-006.apk`), `adb install -r`, run the three
probes below, then reinstall the feature APK and delete the branch.
- P1 (T1): say "slow, write a 400-word story about a fox"; within 7 s double-press and say "what is two plus two" →
  YOU lines in that order, the story then "four", each once; the phone chat has each message once.
- P2 (T3/F2): ask for the story; once "You: …" shows, double-press and say "broken, what is two plus two" → "Probe: send
  failed" is said, the screen keeps waiting, the story is said once; the phone chat has only the story message.
- P3 (T2): ask "what is the capital of France, in one sentence"; at once double-press and say "slow, what is two plus
  two" → "Paris", then "four", each once; the wait ends after "four". It counts only if "Paris" comes after the second
  words were said (its `done` then lands while they are held back); if it came first, that was T8: repeat.

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
- R7: An acknowledgement (`heard` or a refusal) that comes more than 10 s late, after its words were given up, is taken
  for the words in flight then. Worst case: those words' own deadline is lost, or (a refusal) they are settled early and
  dropped if not yet sent. The ceiling and status checks still bound the wait; nothing is said twice.
- R8: Words queued behind words in flight are lost if the wait ends for the whole conversation (ceiling, lost contact,
  the screen closed) before their turn; the error is said, the queued words get no YOU line.

## Open questions
None blocking. Two visible behaviour changes Nicholas may want to know about (decisions.md): quick talk while waiting now
opens the mic instead of only showing the lines, and a mic tap while waiting no longer drops the first answer.

