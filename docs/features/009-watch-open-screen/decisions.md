# Decisions: 009-watch-open-screen
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-16 - Quick talk is told apart by its own component: an activity-alias `.QuickTalk` the double-press points at
- Options: (a) probe `getReferrer()`/intent flags of the two starts; (b) an exported `<activity-alias>` `.QuickTalk`
  (target `ChatsActivity`, no intent filter, not in the app list), the double-press remapped to it with adb, the app
  list unchanged; (c) move MAIN/LAUNCHER to a new alias and keep `ChatsActivity` as the double-press target; (d) a
  trampoline activity putting an extra; (e) a setting.
- Choice: (b). The component name is in the start intent (also the one `onNewIntent` gets) and an alias of a singleTask
  activity reuses the live instance, so the signal is explicit and not a firmware guess. (a) is undocumented and the
  two starts may be identical. (c) avoids a remap, but changes the icon's identity and makes a later re-pick in Samsung's
  menu silently choose the non-listening entry. (d) is an extra class with the same remap. (e) doesn't separate the
  paths. Unmarked starts default to no listening, so a missing remap costs one tap. Device check first (plan R1).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - 006 is merged into this branch first, with explicit rules for its `Talk.java` conflict with 007
- Options: (a) `git merge feat/006-watch-quicktalk-keeps-reply` as step 1 here; (b) a separate 006 PR before 009;
  (c) port only 006's relaunch half (`ChatsActivity`/`TalkActivity`, no conflict).
- Choice: (a), subject to the orchestrator (plan Q1). Done-when 4 assumes 006 is shipped and it isn't (research); 009's
  reopen and chat-button paths reuse its relaunch counter. (b) is the same work with two device sessions. (c) brings
  back 006's bug: a second spoken message while waiting drops the first answer. Key rule: 007 keeps the message
  listener after the end (for `/buddy/say`), so 006's `end()` must not remove it and its `join` must not re-register.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The default chat is the last write to the `last` prefs, written on pick, words sent, call and reply
- Options: (a) one `last` record overwritten at each event; (b) two records (talked-to, replied) with timestamps,
  compared on open; (c) `Talk.chat` in memory.
- Choice: (a). The latest write is the most recent event, so "most recent of the two" holds by construction. It
  survives process death and needs no timestamps. (b) gives the same answer with more code. (c) is lost on process
  death. The writes are done by one `ChatsActivity.remember(...)` (owner of `last`) from `open()`, `TalkActivity`
  (send, call) and `Talk.onPhone` `REPLY`. A call counts as talking to the chat.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Only quick talk starts the recognizer by itself; a list pick lands on the talk screen without it
- Options: (a) auto-listen only with the `speak` extra (quick talk); (b) keep 003's list pick listening.
- Choice: (a). Done-when 3 says picking makes the chat "the talk screen's chat", and that screen is the mic-or-call
  screen of done-when 1. The ask is to choose between mic and call, which an auto-started recognizer takes away.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The chat control is a pill with the chat's title, fixed at the top of the talk screen
- Options: (a) a fixed top pill (title and chevron) outside the scroll; (b) a third 48dp button in the bottom row;
  (c) make the existing title line in the scroll tappable; (d) a swipe, or tapping the face.
- Choice: (a). It also shows which chat the screen is for, which the default-chat rule makes important. (b) doesn't
  fit three buttons at the bottom of a 40mm round screen. (c) scrolls out of sight with a conversation. (d) isn't
  obvious.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The chat button opens the list by starting `ChatsActivity` with `"list"`, and the wait goes on
- Options: (a) a singleTask start of `ChatsActivity` with a `"list"` extra (no redirect), so 006's relaunch counter
  marks the cleared talk screen as not left; (b) `finish()` plus a "don't end the wait" flag; (c) `finish()` ending the
  wait like Back.
- Choice: (a). It works whether the list is below the talk screen or not (notification-opened screen), and needs no new
  state in `TalkActivity`. (b) exits the app when the talk screen is a task root. (c) makes done-when 2's "a reply
  reached the watch" case unreachable: no reply can arrive once the wait has ended.
- By: Planner (claude-opus-5-5)
