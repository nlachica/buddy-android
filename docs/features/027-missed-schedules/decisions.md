# Decisions: {{SLUG}}
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-18 - Other chats at work no longer make a due scheduled task wait
- Options: (a) drop the `hub.runningCount() > 0` wait entirely; (b) skip it only for chats in background mode;
  (c) wait only while an agent drives the real screen (`svc.controlActive()`); (d) keep the wait but start anyway after
  the hour instead of skipping; (e) still run scheduled tasks one at a time among themselves.
- Choice: (a), because the brief wants "on time ... regardless of other chats being busy". (b) still drops Linux-only
  tasks like the brief and the curator in normal chats. (c) doesn't work: once a phone tool is used, the chip stays on
  while any Buddy chat runs (ControlIndicator tick), so it is true for hours on pipeline days. (d) is still hours late.
  (e) means a long scheduled run delays or drops the next one again. Running beside other chats is safe enough. Buddy
  already lets chats run side by side (the user's own, sub-agents, outside sessions), with no lock on the screen. Every
  tap is refused while a card waits (Tools.guard, guardStroke). A pause holds the screen tools (Tools.call0 ->
  awaitResume). Background-mode chats have a screen of their own.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - A card waiting for the user no longer makes a due task wait either
- Options: keep `svc.bubble().confirming()` in the wait; drop it.
- Choice: drop it. A card can't be the due task's own: a task whose chat is working is skipped, or waits for that
  chat. So the card always belongs to another chat or session, which is the same class of bug. An unanswered card from
  a pipeline chat overnight would drop the 07:30 brief an hour later. Taps are already refused while a card shows,
  and cards from several chats queue (Bubble.Card).
- By: Planner (claude-opus-5-5)

## 2026-11-18 - The task's own chat keeps today's behaviour
- Options: leave it alone; make a recurring task wait for its own chat instead of being skipped; send an agent's own
  message straight into a chat whose turn is over but whose process lives on (023 `ChatHub.liveInput`).
- Choice: leave it alone. The brief and the audit are about other chats. A recurring task whose own chat is still
  working is skipped, with a notification, as upstream does. An agent's own message waits up to an hour for its chat's
  run to end. Since 023 that includes a chat whose agent process stays alive for background jobs. Possible follow-up
  if Nicholas wants "never dropped" for that case too.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Test seam: a pure `Schedules.waits(...)` plus a source guard, no Robolectric
- Options: Robolectric to drive `pump()` end to end; a pure static decision that pump calls, plus a source-text guard.
- Choice: the pure static `waits(Sessions.Session s, boolean paused, boolean stopped, boolean online)` that pump calls,
  plus a test that fails if `Schedules.java` calls `runningCount(` or `confirming(` again. A new test framework for one
  test isn't worth it. `Sessions.Session` builds in JVM tests (TurnTrackTest does it). `Schedules.java` is upstream's
  file, so the guard catches an upstream sync that brings the old wait back. ChatScrollTest (017) does the same.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Docs: one fork sentence in GUIDE.md, strings unchanged
- Options: reword `schedule_skip_busy` ("another task, a pause, or no internet") in 18 languages; leave it alone.
- Choice: leave it alone. "Another task" still fits the one case left, the chat's own previous run. GUIDE.md ships to
  agents as /root/.buddy/GUIDE.md, so it gets the fork's sentence (AGENTS.md: upstream's text plus the fork's sentence).
- By: Planner (claude-opus-5-5)

## 2026-11-18 - A task whose own chat is working waits for it, however long (replaces "The task's own chat keeps today's behaviour")
- Options:
  - (a) Keep today's behaviour: skip a recurring task at once, and give up an agent's own message after an hour.
  - (b) Wait for the own chat for an hour at most.
  - (c) Wait with no deadline, restarting the task's hour on each look while its chat works.
  - (d) Send into a 023 live chat whose turn is over.
  - (e) Ask Nicholas to narrow the brief to idle destinations.
- Choice: (c), because the brief's done-when says "(on time if its chat is idle), never dropped", and its goal says
  "at least run as soon as possible and never be dropped" (plan-review F1).
  - (a) and (b) still drop the task.
  - Restarting the hour matters: a Stop that ends a long run would otherwise give the task up at once, during Stop's
    minute.
  - (d) is a new delivery path that the brief doesn't need.
  - (e) isn't needed: the brief is clear enough.
  - GUIDE.md's fork sentence now also says that a run waits for its chat instead of being skipped.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Two times of one chat that both wait still become one run
- Options: keep upstream's merge (`fire`: `waiting.put(id, ...)`); queue each time as its own run.
- Choice: keep it. It's upstream's deliberate behaviour and GUIDE.md documents it. Both are the same saved message in
  the same chat, and the one run starts after both times, so the message is not dropped. A second run straight after
  the first would only repeat it (plan-review F1, last point).
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Test seam: `Schedules.due(waiting, key, session, paused, stopped, online, now)` (replaces `waits(...)`)
- Options:
  - `waits(...)` as before: a boolean of the conditions only.
  - `due(...)` over the waiting map: START, WAIT or GIVE_UP, and it takes the key out of the map when the task starts
    or is given up.
  - Robolectric around `pump()`.
- Choice: `due(...)`.
  - It holds the whole decision `pump()` makes for a key, so a JVM test can run it pass by pass over a real map (plan-review F2):
    - B starts on the first pass beside a running A;
    - B waits past the old hour while it runs itself;
    - B starts once when it is free.
  - The key-to-session lookup stays upstream's unchanged code.
  - The source guard stays, for upstream syncs.
  - Robolectric would be a new framework for one test.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Smart review reads the calling chat's request
- Options:
  - leave `reviewContext()` on `latestRunning()`, a bug that already exists with two chats;
  - pass the calling chat (`McpServer.CALLER_CHAT`) and use its session, mapping a sub-agent or helper to its parent,
    with no caller falling back to `latestRunning()`;
  - a new per-call context object.
- Choice: the calling chat, with the parent mapping and the fallback.
  - This feature makes a scheduled run beside another chat routine. With Smart review, an unattended run's taps would
    otherwise be judged against the other chat's request, and the other way round (plan-review F3).
  - The caller id already exists on the tool's thread, so the fix is a few lines and a pure `ChatHub.reviewed(...)`
    that a JVM test covers.
  - The parent mapping and the fallback keep today's rules for sub-agents and outside sessions.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - A waiting Schedule is kept across a restart, as a prefs mirror of the waiting map
- Options:
  - (a) leave waits in memory, an accepted risk;
  - (b) a `held` occurrence field in `Plan`'s JSON;
  - (c) a string set `schedules_held` in Buddy's prefs, derived from `waiting`'s Schedule keys, written when it changes
    and restored in `serviceStarted`.
- Choice: (c) (plan-review round 2, F1).
  - Not (a): this feature makes a recurring run wait for its own chat, possibly for hours, where it used to be skipped.
    A restart during that wait would drop it silently, against "never dropped".
  - Not (b): it touches upstream's `toJson`, `of` and `copy`, and every path that ends a wait would need to clear it.
  - (c) can't drift from the map, which already holds the rules: one key per chat, so two times still become one run;
    `cancel` for a pause, a removed schedule or a deleted chat.
  - An agent's own message needs nothing: it stays in `s.later`, and `sync` arms its past alarm at service start.
  - `busy_chats` in `Prefs` is the precedent.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - The key-to-chat lookup is a pure `Schedules.chat(all, key)` that pump() and the tests share
- Options: keep `hub.sessions.find(...)` in `pump()` and pass B directly in the tests; a pure `chat(all, key)` used by
  both; fold the lookup into `due()`.
- Choice: `chat(all, key)` (plan-review round 2, F2). The two-chat tests pass both chats and each key form through the
  same selection `pump()` makes, so a wrong pick fails them. Folding it into `due()` doesn't work: `pump()` still needs
  the session for the sign-in skip and `start`.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Element ids only work for the chat whose read_screen made them
- Options:
  - leave the shared `lastNodes` as it is (a bug that already exists with two chats);
  - one node cache per chat;
  - record the reader's screen key and display, and refuse a lookup from another one with "Call read_screen again".
- Choice: the owner check (plan-review round 2, F3).
  - This feature makes unattended runs beside other chats routine. A tap resolved against another chat's read could
    land on the wrong display or the wrong element.
  - The check is a few lines and a pure `Tools.sameRead(...)` that a JVM test covers.
  - One cache per chat would need cleanup when a chat ends.
  - The cost is an extra `read_screen` when two chats drive screens at once.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Orchestrator: plan accepted after round 3, with the two round-3 findings as coder must-dos
- Round 3 found two real edge cases, both small and local: (F1) persist held runs per chat id so a boot-time cancel of another schedule can't wipe them; (F2) editing a chat's Schedule clears that chat's waiting run before arming the new one. Coder implements both with the tests the reviewer named. Keep everything else as planned; no further scope.
