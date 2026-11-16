# Plan: 009-watch-open-screen
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal
Opening Buddy from the watch's app list shows the talk screen (mic + call buttons) for the default chat. The recognizer
does not start; one tap on mic or call starts it. The default chat is the most recent of the last chat talked to (words
sent or a call) and the chat whose reply last reached the watch (a reply the watch takes: shows and says). Picking a chat
without talking doesn't change it. One obvious control on that screen opens the chat list, and picking a chat makes
it the talk screen's chat. The Home double-press keeps going straight into listening (003). A reopen while a reply is
awaited keeps that reply (006). CI is green.

Non-goals: calls, voices, the phone side, the list itself (beyond picking), the notification path, Back ending the wait
(003), a tile, and a second app-list icon (only as the fallback in R1, with Nicholas's OK).

## Design
**1. Telling the double-press from the app list: a component of its own.** Add an `<activity-alias>` `.QuickTalk`
(target `.ChatsActivity`, `exported="true"`, label "Talk to Buddy", **no intent filter**, so it is not in the app
list). The Home double-press is pointed at it once with adb (`setting_home_key_double_press`, which per the orchestrator
can hold any component). Android puts the alias name in the start intent's component (`getIntent()` in `onCreate`, the
new intent in `onNewIntent`), and an alias of a singleTask activity reuses the live `ChatsActivity`. So "this start is
quick talk" can be read from the component name, with no guessing. The app list keeps starting `ChatsActivity`.
Unmarked starts take the new behaviour (no listening), so an unmapped or reset double-press only costs one extra tap.
Alternatives: probing `getReferrer()` or the intent flags (undocumented, depends on the firmware, and the two may be
identical); moving LAUNCHER to a new alias and leaving `ChatsActivity` as the double-press target (the icon's identity
changes, and re-picking Buddy in Samsung's menu would silently pick the non-listening entry); a trampoline activity (an
extra class, and the same remap); a setting (it doesn't separate the two paths at all).

**2. 006 comes in first.** 006 was verified (SHIP) but never merged (research). Done-when 4 needs it, and 009's
reopen paths are built on its mechanism: `ChatsActivity.relaunches`, the `speak` extra, ending the wait only from a
finishing `onStop` of a screen not cleared by a relaunch, and `Talk.begin` joining a live conversation. Step 1 merges
`feat/006-watch-quicktalk-keeps-reply` into this branch. `ChatsActivity` and `TalkActivity` merge cleanly. `Talk.java`
has 7 conflict hunks against 007, resolved by the rules in step 1. Alternatives: a separate 006 PR first (same work,
two device sessions; fine if the orchestrator prefers it, see Q1); porting only 006's relaunch half (no `Talk.java`
conflict, but a second spoken message while waiting would again drop the first answer, which is 006's own bug).

**3. Routing in `ChatsActivity`.** `onCreate` (fresh only) and `onNewIntent` (after `relaunches++`, which stays
first and unconditional) both call one `route(Intent)`:
- extra `"list"` set: do nothing, so the list shows (the chat button's start);
- otherwise: `talkToLast(quick)`, where `quick` = the intent's component class is the `.QuickTalk` alias.

`talkToLast(speak)` puts `"speak"` on the talk intent only when `speak` is set. No saved chat still means the list.

**4. The talk screen listens on its own only for quick talk.** `TalkActivity.onCreate` keeps 006's `news` rule and
adds `speak`: `if (b == null && speak && !news) listen();`. App list, list pick and notification never start the
recognizer. Quick talk behaves as in 003/006 (listens even while waiting; an unseen reply is shown first). Picking a
chat in the list now lands on that chat's talk screen without the mic, so the user can choose mic or call (decision
below).

**5. Default chat = the last write to the `last` prefs.** Each of the brief's two events overwrites the one `last`
record (chat, title, accent), so the latest write is the most recent event, and it survives process death without
timestamps. A new `static void remember(Context, chat, title, accent)` in `ChatsActivity` (the owner of `last`) is
called on:
- words sent (`TalkActivity.onActivityResult`, before `Talk.begin`);
- a call started (`TalkActivity.call()`, before `Call.start`);
- a reply the watch takes (`Talk.onPhone`, case `Phone.REPLY`, after its chat filter, with `Talk.chat/title/accent`).

A list pick does **not** write it: `open()` loses today's write and only passes the chat in the talk intent. Picking is
neither of the brief's events, so a pick not followed by words or a call leaves the default where it was. No record yet
(fresh install): the list, as today. An install updated from 003 starts from the record 003's last pick wrote.

"Reached the watch" means a reply `Talk` takes, shown and said. The phone relays every chat the watch sent to until
that run ends (`WatchLink.java:127-128`, `:143-156`, `:230-236`). So after words to A and then to B, A's replies still
come, but `Talk` holds one conversation and drops another chat's messages (`Talk.java:242`). Such a reply doesn't
count. Talk only hears it while B is live (B's wait or B's speech; after that only `/buddy/say` gets through and then
nothing listens). In that window the user is waiting for or hearing B, and opening A would put an empty screen (A's
reply was never shown) over B's conversation. A minute later the same reply wouldn't count anyway. It isn't the ask's
"the chat that I'm working on that just responded to me" either. Counting it would also need a stored title per chat:
the reply carries only the id and the text. (Q3.) In today's `Talk` the reply write repeats the last send's chat
(Talk takes only that chat's replies, and a call ends its wait first). It keeps the brief's rule true on its own, in
one line.

**6. The chat button.** A pill fixed at the top of the talk screen, outside the scroll view, shows the chat's title and a
chevron: `CARD` fill, one line, ellipsized, 14sp, `contentDescription` "Pick a chat". It replaces the title line that
`draw()` puts inside the scroll today, so it stays visible however long the conversation is. Tapping it does
`startActivity(new Intent(this, ChatsActivity.class).putExtra("list", true))`. With the list below in the task
(normal), singleTask clears the talk screen and `onNewIntent` bumps `relaunches` first. 006's rule then treats the
cleared screen as not left, so **the wait goes on** and the reply is still spoken (and counted, done-when 2). With the talk screen as a task root (opened from the notification), the list opens on top of it,
which is fine too. Alternatives: a third 48dp bottom button (three don't fit the bottom of a 40mm round screen); the
title inside the scroll (it scrolls away with a conversation); a swipe or tapping the face (not obvious); `finish()`
with a flag (exits the app when the talk screen is a task root).

## Steps
1. **Merge 006.** `git merge --no-ff feat/006-watch-quicktalk-keeps-reply`. Check that the clean auto-merges of
   `ChatsActivity`/`TalkActivity` match 006's diff. Resolve `wear/.../Talk.java`:
   - imports: keep `ChannelClient` (007) and `Task` (006);
   - class doc: 007's voice sentences plus 006's end conditions (a relaunch doesn't end the wait; a failure of later
     words doesn't end a run the phone accepted);
   - fields: keep `channelCallback` (007); take `registration`, `queue`, `accepted`, `sends` (006, replacing
     `sender`); `noHeard` calls `settle(getString(R.string.talk_no_answer))`;
   - `begin`: `Call.hangUp()` first (007, also for a join), then 006's join-or-replace body;
   - `onStartCommand` tail: `registerChannelCallback(channelCallback)` (007), then `register(); next();` (006);
   - `onPhone`: keep 007's `Phone.SAY` branch before the switch; drop the blanket `removeCallbacks(noHeard)` (006: only
     `HEARD` cancels it);
   - `end()`: keep 007's rule that the listener stays until stop (`/buddy/say` comes after the end), so **no**
     `removeListener` there; take 006's `queue.clear(); accepted = false; run++;`. Because of that, `join(false)` calls
     `register()` again **only if the kept registration failed or was cancelled**
     (`registration.isComplete() && !registration.isSuccessful()`), then `next()`. A registration that succeeded is
     reused: the listener is still registered, and adding it twice may be refused as a duplicate
     (`WearableStatusCodes.DUPLICATE_LISTENER`), which would fail the wait. A pending one is waited on: `next()` attaches
     to it, and its failure handler fails the joined wait as on a fresh start;
   - `stop()` auto-merges to both (`instance = null` and `release()`): keep it.

   The commit message lists each conflict and its resolution. Append one line to this feature's `decisions.md`.
2. **Quick-talk entry.** `wear/src/main/AndroidManifest.xml`: the `.QuickTalk` alias after `ChatsActivity`.
   `ChatsActivity.java`: `route(Intent)`, `talkToLast(boolean speak)`, the `"list"` extra; update the comments on
   `relaunches`/`onNewIntent` ("explicit starts of the live task: app list, quick talk, the chat button").
   `res/values/strings.xml`: `quick_talk` = "Talk to Buddy".
3. **Default chat.** `ChatsActivity.remember(...)`; `open()` drops its prefs write. Calls in
   `TalkActivity.onActivityResult` (after the result checks, before `Talk.begin`), `TalkActivity.call()` (after the
   permission check, before `Call.start`) and `Talk.onPhone` `REPLY` (after the chat filter). Update the `talkToLast`
   doc: "the default chat: the last one talked to or whose reply the watch took; a pick alone doesn't change it".
4. **Talk screen.** `TalkActivity.java`: `&& speak` on the auto-listen, with the comment updated; the top chat pill in
   `onCreate` (root = pill, scroll with weight 1, buttons); remove the `name` line from `draw()`; trim the body's
   round top padding now that the pill takes the top inset. `strings.xml`: `talk_chats` = "Pick a chat".
5. **README** (watch section): opening the app shows your last chat's talk screen (tap its name for the others). Quick
   talk: point the Home double-press at `app.buddy.assistant/app.buddy.assistant.wear.QuickTalk` with the adb command.
   The Verifier corrects the exact namespace/format after the device check (a docs-only commit).

## Test plan
No local Android SDK and no watch unit tests in the repo (research), so: CI compile plus device checks on the Watch4.
- **Build:** after the orchestrator pushes the branch, run `gh workflow run build.yml -R nlachica/buddy-android --ref
  feat/009-watch-open-screen` (it builds `:wear:assembleRelease`, signed with the fork key), then the PR checks. Run it
  after step 1 too, if the orchestrator wants merge errors isolated.
- **Install:** `buddy-watch-*.apk` from that run, `adb install -r` (`-d` if the version code is equal). The phone side
  is unchanged, so the installed fork release (2.3.10-fork.1, same key) pairs with it. No phone release is needed.
- **Map the double-press:** first read the current value (`adb shell settings list system`/`secure`/`global` | grep
  -i double) to learn the namespace and format. Then put `app.buddy.assistant/app.buddy.assistant.wear.QuickTalk` in
  the same format. Emulators: `adb shell am start -n app.buddy.assistant/.wear.ChatsActivity -a
  android.intent.action.MAIN -c android.intent.category.LAUNCHER` (the app list) and `adb shell am start -n
  app.buddy.assistant/.wear.QuickTalk` (quick talk). They test the code paths; the real tap and key are checked too.
- **Merge trace (Code Reviewer, step 1):** a registration failure can't be forced on the watch, so walk it in the code.
  `addListener` fails → `fail(talk_connect)` (wait over, the error still being said, service alive) → quick talk to
  the same chat → `begin` joins → `join(false)` finds the registration complete and unsuccessful → `register()` →
  `next()` sends on the new task's success. Also: a registration that succeeded → `join(false)` only calls `next()`,
  no second `addListener`; one still pending → `next()` waits on it.

| Done-when | Proof |
|---|---|
| 1 app list → talk screen, no mic | Real app-list tap: cold (`am force-stop` first), warm with the talk screen on top, warm with the list on top → talk screen of the default chat, no recognizer; one tap on mic → recognizer; one tap on call → call. |
| 2 default chat | Talk to A with a long task ("write a 300-word story about a fox"); the pill → list → pick B, don't speak; A's reply is spoken; Back out; app-list tap → **A**. Then talk to B ("what is two plus two"), reply, app-list tap → **B**. `am force-stop`, app-list tap → still **B** (prefs). Pick without talking: talk to B, its answer comes; the pill → pick A, no words or call; Back out; app-list tap → **B**. Two chats at once: talk to A (long story); the pill → pick B, "what is two plus two"; let A's reply come during or after B's; app-list tap → **B** (A's reply is dropped by Talk, not counted, Design 5). |
| 3 the pill | One tap on the pill opens the list; pick a chat → its talk screen (pill shows its title), no recognizer; Back → list. |
| 4 double-press + 006 | Real double-press, cold and warm → recognizer for the default chat. 006's checks again: long story, double-press while waiting, "what is two plus two" → story then four, each once; double-press while waiting, cancel → story still spoken; app-list tap while waiting → conversation shown, story still spoken. Join during speech (the merge's `join(false)`): while a long answer is still being said after its done, double-press and say "what is two plus two" → heard and answered, earlier lines kept, no "can't reach"/"no answer". 003: Back on the talk screen while waiting still ends the wait. |
| 5 CI green | All PR checks pass (the build job includes `:wear:assembleRelease`). |

## Risks
- **R1** Samsung's double-press may not launch a component that isn't in the app list, or the key/format may differ.
  Verifier checks this first. Fallback (needs Nicholas's OK, it is visible): give the alias a MAIN/LAUNCHER filter so
  "Talk to Buddy" appears in the app list and can be picked in Settings → Advanced features → Customize keys.
- **R2** The component of an alias start in `onNewIntent` is from AOSP's start path, not tested on this watch: the `am
  start` emulators in the test plan show it at once.
- **R3** The Wear app list might bring a live task to the front without `onNewIntent`. 006 verified the double-press
  path only. If so, whatever was on top shows (the list, after a Back). Case 1 "warm, list on top" catches it.
- **R4** The 006/007 merge: a join while voice replies are queued, the listener kept after the end, and a join after a
  failed registration. Review the step 1 rules closely (merge trace above); device case 4 covers the rest with the
  phone's current speech engine.
- **R5** Until the double-press is remapped after install, it opens the talk screen without listening (one extra tap).
  Tell Nicholas when installing.
- **R6** While chat B's screen is open, A's reply is spoken, and B's open screen clears `Talk.unseen` for A. The reply
  was heard, so a later quick talk to A listens instead of showing it first. Accepted.
- **R7** The pill takes about 36dp from the conversation on a 40mm round screen; long titles are ellipsized.

## Open questions
- **Q1 (orchestrator, not blocking):** merge 006 inside this branch (step 1, recommended) or as its own PR first? If its
  own PR, step 1 moves there and the rest is unchanged.
- **Q2 (Verifier, device):** the namespace/format of `setting_home_key_double_press`, and whether it starts the alias
  (R1). A "no" needs Nicholas's answer on the fallback. The current value can be read before coding if the watch is on
  adb: a stored `package/class` means the firmware starts a component; a bare package means R1's fallback.
- **Q3 (orchestrator to confirm with Nicholas, not blocking; the plan goes ahead with (a)):** which replies make a chat
  the default? (a) A reply the watch shows and says (planned). In practice the default is then the last chat talked to.
  (b) Also a reply the watch drops because he has since talked to another chat. It is only caught while that other
  chat's conversation is live, and then it opens the dropped chat's empty screen over it. Needs a stored title per
  chat. (c) Any reply from a chat talked to from the watch, whenever it lands. Needs an always-on watch listener
  service and a way to show that reply: a feature of its own.
