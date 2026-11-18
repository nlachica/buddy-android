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

## Round 2 - Plan Reviewer (gpt-6.2-astra)

F1 [major] A recurring run waiting for its own chat can disappear on restart - If the 08:00 alarm fires while its chat is working, `Schedules.java:349-352` arms the next occurrence and records today's run only in the static `waiting` map (`Schedules.java:223`). If Android restarts Buddy during that wait, `sync()` at `Schedules.java:250-254` restores one-shot messages but schedules only a future alarm for a recurring task. The chat is now free and the 08:00 run never starts. The risk acknowledged at `plan.md:188-190` therefore affects the “never dropped” case in `brief.md:19`. -> Persist one pending occurrence for a recurring schedule held by its chat and restore it on service start. Keep the existing coalescing and cancellation rules. Test a restart between the busy and idle passes, including removal of the pending state after dispatch.

F2 [major] The two-chat CI test still bypasses destination selection - At `plan.md:128-135`, the test constructs running chat A but passes only B to `due()`. Changing A's state cannot affect any assertion. If `pump()` passed A for B's key, both this test and the source checks at `plan.md:140-142` would stay green while B waited for A again. -> Move the existing key-to-destination lookup at `Schedules.java:409-411` into the production path exercised by the JVM test. Give it both sessions and each form of B's key. Assert that it selects B on the first pass when B is idle, and selects B once after B becomes idle while A keeps running.

F3 [major] Overlapping screen reads can redirect an element action to another chat's display - The background-display mitigation at `plan.md:175-182` assumes each chat's element IDs refer to its own screen. `BuddyService.java:98` creates one `Tools` instance. Each `readScreen()` replaces its shared `lastNodes` list (`Tools.java:1110-1113`), and `node()` resolves an ID against whichever list was written last (`Tools.java:1307-1319`). A valid node from another display can pass `refresh()`. Correcting the Smart review request leaves this target-selection error in place. -> Record the caller's existing screen key and display with the cached list. Reject a mismatched element lookup with the existing instruction to read again. Test interleaved reads from two chats. This requires no lock across tool calls and lets both scheduled runs start on time.

F4 [minor] The phone check can mistake a late Android alarm for a scheduler delay - `plan.md:172` measures dispatch against the requested time with a one-minute tolerance. `Schedules.java:271-280` uses an inexact alarm when exact alarms are unavailable, and `Schedules.java:677` already reports that condition to the tester. -> Confirm exact-alarm access before the timed phone check, or measure the delay from alarm delivery. Record that A was still working when B started.

VERDICT: REVISE

### Planner response (round 2)

F1: accepted, in its smallest form. This design makes a recurring run wait in memory, possibly for hours, where it
used to be skipped. A restart during that wait would drop it without a word. An agent's own message is already safe:
it stays in `s.later`, and `serviceStarted` → `sync` arms its past alarm again, which fires at once. So only a chat's
Schedule needs this:
- A pure `Schedules.held(waiting)` returns the waiting keys without a `/` (chats' Schedules). It is mirrored into a
  `schedules_held` string set in Buddy's prefs (`Prefs.heldSchedules` / `setHeldSchedules`, beside the existing
  `busy_chats`), and written only when it changed. `pump()` writes it at the end, and `cancel()` writes it after its
  `waiting.remove`.
- `serviceStarted` calls the pure `Schedules.restore(waiting, Prefs.heldSchedules(c), now)` before `sync(c)`. That puts
  each id back with a fresh hour (`putIfAbsent`). Then `pump()` runs.
- The existing rules still apply. One key per chat, so two times still become one run. A pause, a removed schedule or a
  deleted chat goes through `cancel()`, and `sync` runs after the restore. `pump()` drops a key whose chat or schedule
  is gone.
- The state is the waiting map itself, so a dispatch, give-up or skip clears the persisted copy on the same pass,
  without a separate path that could be missed.

Test (`SchedulesWaitTest`): B's Schedule waits while B runs, and `held` is `{b}` (and leaves out `b/x`). After a
restart (a new map, `restore`, B idle), the first look starts B, and `held` is then empty.

F2: accepted - The lookup at `Schedules.java:409-410` becomes a pure `Schedules.chat(List<Session> all, String key)`:
the session whose id is the key's part before `/`. `pump()` calls `chat(hub.sessions.all, key)` in place of
`hub.sessions.find(...)`, which is the same loop. The two-chat tests now pass `all = [a, b]` (A first, as the most
recent) and call `due(waiting, key, chat(all, key), ...)` for `b` and `b/x`. Each also asserts `assertSame(b, chat(all,
key))`. A wrong pick would now hand A's running state to B's key and fail:
- B idle beside a running A: START on the first pass;
- both running: WAIT past the old hour;
- B idle, A still running: START once, then the key is gone.

F3: accepted, as suggested - `readScreen()` records `readKey = screenKey()` and `readDisplay = display()` under `lock`
when it refills `lastNodes`. `node()` checks the pure `Tools.sameRead(readKey, readDisplay, screenKey(), display())`
first. On a mismatch it throws "Element ids are from another chat's read_screen. Call read_screen again." That's the
same kind of error and instruction as the existing unknown or stale element errors. `node()` is the only lookup of
`lastNodes` ids. There's no lock across calls. Two chats driving screens at once may need an extra `read_screen` each,
but a tap never lands on another chat's element. `ElementIdsTest` covers interleaved reads: A reads, then B reads on
its own display; A's lookup is refused and B's passes; A reads again, and it flips. A chat whose display changed (it
was brought to the real screen) is refused too. `Tools` already loads in JVM tests (ApprovalGateTest).

F4: accepted - The phone checks start with a precondition: the `schedule_message` reply has no "Android allows Buddy
no exact alarms" sentence (`Schedules.java:677`). If it does, turn on Alarms & reminders for Buddy first. In (a), A's
card is answered only after B's run has started. The evidence is B's first event time before the time of A's answer,
plus a `read_screen` of the chat list that shows A working when B starts. The restart from F1 is CI only: on the phone,
the only clean restart is installing a release.

## Round 3 - Plan Reviewer (gpt-6.2-astra)

F1 [major] Boot can erase a held run before the service restores it - Suppose B's 08:00 run is saved in `schedules_held` and an unrelated schedule A is paused. On a cold start, `Schedules.Boot.onReceive` can call `sync()` before the accessibility service connects. Syncing A calls `cancel(A)` (`Schedules.java:240-265`). The proposed `keep(c)` then replaces the entire saved set with the still-empty in-memory map. When `serviceStarted` restores it, B is gone. The restart test at `plan.md:207-209` starts directly at restore and misses this ordering. -> Update the persisted set per affected chat ID, so cancelling A or processing its alarm before service startup preserves B. Test a cold process with B saved, a boot sync that cancels A, then service startup. B must start once and its saved entry must clear.

F2 [major] Editing a waiting Schedule can run the replacement message before its first due time - If B's 08:00 message waits for B and the user edits the Schedule to a different message for 17:00, the editor confirms the new next time (`ChatActivity.java:1815-1816`). Saving replaces `ses.schedule` and arms the new alarm without clearing B's waiting key (`ChatHub.java:540-545`, `Schedules.java:262-269`). When B finishes at 09:00, `start()` reads the replacement text from `s.schedule.text` (`Schedules.java:449-450`). The plan keeps only the chat ID across the wait and explicitly leaves `start` unchanged (`plan.md:94-103`), so the 17:00 instruction runs at 09:00 and can run again at 17:00. -> Treat an explicit replacement of the message or its timing as cancellation of the superseded pending run. Clear that chat's pending entry in memory and prefs before arming the replacement. Test that freeing B after the edit cannot dispatch the replacement until its own alarm fires.

VERDICT: REVISE
