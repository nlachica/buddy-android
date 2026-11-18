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
