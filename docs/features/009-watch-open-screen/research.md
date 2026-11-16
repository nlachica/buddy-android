# Research: 009-watch-open-screen
<!-- Researcher writes here. Sources on every claim. -->

## 2026-11-16 Research (claude-sonnet-5-5)

## Summary
- **Why it listens today:** the launcher activity `ChatsActivity` (singleTask) calls `talkToLast()` in `onCreate` (fresh) and `onNewIntent` (live task); it starts `TalkActivity` for the saved chat (`wear/.../ChatsActivity.java:55-72`). `TalkActivity.onCreate` then calls `listen()` (the Google voice screen) unless `Talk` has news for this chat (`TalkActivity.java:~104-105`). Cancel returns to the talk screen (mic + call buttons); Back from it lands on the list. That is the user's "swipe left, then see mic/call".
- **Launcher tap and Home double-press are the same intent** (MAIN/LAUNCHER of `ChatsActivity`, `AndroidManifest.xml:40-47`). Double-press "can open any app" (003 brief.md:38-39); nothing in the repo tells the two apart, and no Watch4 evidence that the double-press sends a different extra/flag (unverified). Done-when 4 (double-press keeps listening) and 1 (app-list tap doesn't) need a distinguishing signal; the Planner must decide how (see Open point A). Adding a second launcher activity or a setting are options, not facts.
- **The talk screen already is the "mic + call" screen** (`TalkActivity.java:~64-90`, buttons built in onCreate); the only change for item 1 is not calling `listen()` on a launcher open. Mic button already does `Talk.silence(); listen()`; call button does `call()`.
- **Last chat** is in SharedPreferences `last` (`chat`,`title`,`accent`), written ONLY in `ChatsActivity.open()` when a row is tapped (`ChatsActivity.java:159-163`). Sending from the talk screen never rewrites it, and nothing records "a reply arrived".
- **"Chat whose reply reached the watch" is the same chat as the last talked-to, in practice:** the phone relays a run's messages only to the node that asked (`WatchLink.java:143-148`, `tracked.get(ses.id)`), and `Talk.onPhone` drops messages for other chats (`Talk.java:~239`). The watch only ever receives replies for chats it sent to (so `Talk.chat`, set in `Talk.begin`, `Talk.java:143-157`). A reply for a chat the watch didn't message has no path today; supporting it means phone-side work (non-goal says phone side unchanged; unverified that no other path exists). `Talk.chat/title/accent` are process-static (lost on process death), so "most recent wins" needs a persisted timestamp/choice (prefs), not just `Talk.chat`.
- **Chat picker:** `ChatsActivity` already is the list; `open()` saves `last` and starts the talk screen (`ChatsActivity.java:159-163`). Nothing from `TalkActivity` goes to the list except Back (talk screen sits above the list in the task). A visible control is new UI: `TalkActivity` has no title-tap or list button (title is a plain TextView, `draw()` ~`TalkActivity.java:196`). Picking from the list starts a *new* TalkActivity (stacked on top of the old one), it doesn't change the current screen's chat.
- **006 IS NOT IN MAIN.** `feat/006-watch-quicktalk-keeps-reply` (commits 40e019b, 9eac73f, docs) was never merged: `git merge-base --is-ancestor feat/006... origin/main` fails; `v2.3.9-fork.4` and main have 0 occurrences of `relaunches`; `Talk.begin` still does `lines.clear()` (`Talk.java:147`). Tag `v2.3.9-fork.3` (8c5ec09) was a BuildInfo bump on the 006 branch; fork.4 (007) came from main without it. Consequence: in main today a launcher reopen while waiting finishes the talk screen and ends the wait (`TalkActivity.onDestroy` -> `Talk.left()`, `TalkActivity.java:~118`; 003 decisions.md "Cost"), i.e. the brief's "006 reopen-while-waiting still keeps the reply" is not true on this base. Planner/orchestrator must either merge 006 first or fold its behaviour in (006 mechanism: `ChatsActivity.relaunches` counter + `speak` extra + `Talk.begin` appending while waiting; see `git show 40e019b 9eac73f`, `git show feat/006-watch-quicktalk-keeps-reply:docs/features/006-watch-quicktalk-keeps-reply/decisions.md`). Note main also has 007 changes (Call, ms_graphic_eq, Talk rewritten) so 006 will conflict in `Talk.java`/`TalkActivity.java` (`git diff HEAD feat/006... --stat -- wear`: Talk.java ~400 lines differ).
- **Tests:** the repo has no watch unit tests (003 code-review.md:33,59,99); verification is compile + device (Watch4). CI builds `:wear:assembleRelease` (`.github/workflows/build.yml:63`). `check-wear.sh` named in old docs is not in the repo now (`git ls-files` finds none; unverified where it went).

## Repo map
- `wear/src/main/AndroidManifest.xml:40-47` ChatsActivity: launcher, singleTask, exported. `:50` TalkActivity not exported, default mode. `:53` CallActivity singleTop.
- `wear/.../ChatsActivity.java` list + redirect (`talkToLast` 64-72, `onNewIntent` 59-62, `open` 159-163, list rows 134-150).
- `wear/.../TalkActivity.java` talk screen; extras `chat`,`title`,`accent` (`Talk.intent`, `Talk.java:160-163`); `news` rule + `listen()` in onCreate; `onStart` sets `Talk.onChange`, clears `Talk.unseen`.
- `wear/.../Talk.java` service/state: `begin` 143, `left` 166, `unseen` 74, notification PendingIntent -> `TalkActivity` directly (224-228).
- `wear/.../Look.java` helpers (`text`, `pill`, `dp`, colors); strings in `wear/src/main/res/values/strings.xml` (`talk_speak`, `call_button`, `chats_*`; English only, `app_name` Buddy).
- Phone side (not to change): `app/src/watchlink/java/.../WatchLink.java` CHATS list 95-106 (excludes archived/pinned/child sessions), relay 143-158.
- Prior features: 003 decisions.md:179-199 (launcher redirect, singleTask), 006 on its branch (above), 007 call button.

## Prior art
- Only the repo's own history (above). Wear OS: double-press Home launches a configurable app via normal launcher intent (assumption from 003 brief.md:38-39, not re-verified on Samsung docs, `(unverified)`). Android singleTask: launcher start of a live task -> `onNewIntent`, clears activities above (https://developer.android.com/guide/components/activities/tasks-and-back-stack#ManifestForTasks, cited by 003 decisions.md:192-195; not re-fetched).

## Constraints and risks
- No on-device way to separate double-press from app-list tap is documented; any distinguishing design (e.g. a second launcher alias/activity bound to nothing, or an intent extra) must be checked on the Watch4. `getIntent`/`getReferrer` on the launcher start are the candidates to probe (unverified). If they can't be told apart, the brief's two goals conflict and the user must choose (needs his answer).
- `singleTask` + `onNewIntent` re-fires `talkToLast` on every launcher open; a no-listen open must still not break the 003/006 cases: no saved chat -> list; waiting reply -> keep it.
- Process death clears `Talk.*` and `unseen`; default-chat logic must use prefs for anything that must survive.
- Mic not auto-started means recognizer-first `Talk.unseen` logic changes meaning: with no auto-listen, the `news` check in `TalkActivity.onCreate` only matters for the double-press path.
- Small round/square screens: TalkActivity body is a ScrollView above a fixed bottom button row (`TalkActivity.java:~56-90`); a list control needs space (a third button or tappable chat title) without crowding 48dp buttons.
- Strings are English-only in the watch module; list control needs a new string + contentDescription.

## Answers to open questions
None (Planner has not written plan.md yet).

## Open point A (for the Planner)
How to tell double-press from launcher tap, and what to do on 006 (merge first vs reimplement). Both need the orchestrator/Nicholas if no probe is possible.
