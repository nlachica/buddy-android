# Plan review: {{SLUG}}
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6.2-astra)

F1 [major] A due message can still disappear when both chats are busy - In `brief.md:19`, “on time if its chat is idle” qualifies the timing. The message must still run if the destination is working when it comes due. With chat A working and chat B briefly busy at its alarm, `Schedules.java:419` skips B's recurring run even if B finishes a second later. For a one-shot message, lines 420–428 remove it after an hour. Two pending recurring occurrences can also become one at line 352. The non-goal at `plan.md:20` leaves all three paths in place. -> Start idle destinations on the first pump pass and retain due occurrences held by their own chat until it can accept them. Test both schedule types, including a wait beyond the old deadline and two occurrences becoming due. If the intended guarantee covers only idle destinations, get that clarification recorded in the brief.

F2 [major] The regression test can pass while dispatch is broken - At `plan.md:91` the running pipeline session is created but never used. The assertion exercises only `waits(brief, ...)`. The source check at line 98 would still pass if `pump()` selected the wrong session or skipped an eligible task before `start(s, l)` at `Schedules.java:431`. -> Exercise the production decision that selects due work by destination ID. With A running and B due, assert that B is selected on the first pass. With both running, assert that B stays pending and is selected once after B becomes idle while A keeps running. A JVM test of that decision is sufficient.

F3 [major] Concurrent scheduled runs can give the Smart tap reviewer another chat's request - `Tools.java:1837` obtains the review context on the main thread. `ChatHub.java:961` chooses the most recently active chat, and `Reviewer.java:76` sends that chat's request and note as context for the tap. Once an unattended schedule starts alongside another chat, either chat's next Smart review can use the other's instructions and backend. The card and gesture checks cited at `plan.md:121` do not establish which chat requested the action. -> Capture the calling chat ID before posting to the main thread and use its session for Smart review. Preserve the existing outside-session behavior separately. Check with two active chats that the scheduled chat's review uses its own request even when the other chat has the newest event.

F4 [minor] The phone check depends on a long foreground command - `plan.md:114` relies on foreground sleeps of 600 and 180 seconds. The phone's Claude hook rejects explicit foreground timeouts over 30 seconds (`/root/.claude/hooks/no-long-foreground.sh:10–16`), so a run can finish before the alarm and give a false pass. -> A simpler check is to leave a question card open in A, confirm A is still working when B records its Scheduled message, and then answer the card. This also exercises the proposed removal of the pending-card wait.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted (all but the two-become-one point) - The brief's done-when says "(on time if its chat is idle), never
dropped", and its goal says "at least run as soon as possible and never be dropped". So a task whose own chat is
working must wait for it, not be dropped. The plan no longer leaves today's behaviour in place:
- A recurring task whose chat is working isn't skipped any more (the `schedule_skip_running` branch leaves `pump()`).
- Neither kind has a deadline while its own chat works. Each look puts the task's hour back to `now + WAIT_MS`, so the
  hour for a pause, Stop or no internet counts from when its chat is free. Without that, a Stop that ends a long run
  would give the task up at once (Stop's minute).
- Idle destinations start on the first pass. That was already the case.

All of this sits in one pure `Schedules.due(...)` (see F2). Tests cover both kinds past the old deadline. Rejected: two
times of the same chat becoming one run (`Schedules.java:350-352`). That is upstream's deliberate behaviour and is
documented in GUIDE.md ("the two become one run"). It's the same saved message in the same chat, and the one run starts
after both times, so the message isn't dropped. Running it twice back to back gives nothing more. Recorded in decisions.

F2: accepted - The plan replaces `waits(...)` with `Schedules.due(Map<String, Long> waiting, String key,
Sessions.Session s, boolean paused, boolean stopped, boolean online, long now)`, which returns `START`, `WAIT` or
`GIVE_UP`. `pump()` calls it for each waiting key, with the session it found from the key's chat id. `due()` holds the
whole start, wait and give-up decision, and it takes a key out of `waiting` when it starts or gives up. The JVM test runs
it over a real map, pass by pass:
- A running, B due: B starts on the first pass.
- A and B running: B stays in `waiting`, past the old hour too.
- B idle, A still running: B starts, and its key is gone, so a later pass can't start it again.

This is done for a chat's Schedule key (`b`) and an agent's own message key (`b/x`). The key-to-session lookup in
`pump()` is upstream's code and unchanged. The source guard stays for upstream syncs, and it now also catches
`schedule_skip_running` coming back into `Schedules.java`.

F3: accepted - Real: Smart review reads `latestRunning()` (`ChatHub.java:961`), and a scheduled run beside a pipeline
chat becomes common with this fix. The fix is small, because the calling chat is already known on the tool's thread
(`McpServer.CALLER_CHAT`, which Tools already reads at `:671` and `:819`):
- `Tools.review` reads it before posting to the main thread and calls `reviewContext(chat)`.
- A new pure `ChatHub.reviewed(List<Session> all, String chat)` returns the calling chat. For a sub-agent or a helper it
  returns the parent, which keeps today's rule that those work for their parent's request.
- With no calling chat (an agent outside Buddy's chats), or one not found, it returns null, and `reviewContext` falls
  back to `latestRunning()` as today.

`ReviewChatTest` checks that B's review is B's own even when A has the newest event, that a sub-agent maps to its parent,
and that an outside call returns null.

F4: accepted - The phone check no longer uses long foreground sleeps. A question card (`ask`) holds the busy chat in
working while the user leaves it unanswered. Cases:
- (a) another chat with an open card while B's message comes due, which also covers removing the card wait;
- (b) an agent's own message while its own chat waits on a card;
- (c) a chat's Schedule while its own chat waits on a card, the recurring case from F1.
