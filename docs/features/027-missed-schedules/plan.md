# Plan: 027-missed-schedules

## Goal
When another chat is busy for over an hour, scheduled messages are dropped or run hours late. On 2026-11-17 the 07:30
brief went out at 10:31, and the curator's 08:00 run didn't happen at all (`audit-excerpt.md` §4). The fix: a due
scheduled message runs at its time in its own chat, whatever other chats are doing, and is never dropped because of
them. Use the smallest native change.

**Done when:**
1. A scheduled message that comes due while another chat is busy still runs. It runs on time if its own chat is idle,
   and it is never dropped.
2. A test covers it, and CI is green.

"Idle" means the chat has no agent running (`!s.running`). This applies to both kinds of scheduled message: a chat's
Schedule, and the agent's own `schedule_message`.

**Non-goals:**
- Notification rules' runs. `Triggers.pump` also waits for other chats (`Triggers.java:366`), but these are not
  scheduled messages.
- The task's own chat being busy. That behaviour stays as it is:
  - A recurring task whose chat is still working is skipped, with a notification.
  - An agent's own message waits up to an hour for its chat's run to end. Since 023, that includes a chat whose turn is
    over but whose process lives on.

  It's a candidate follow-up (see decisions).
- A screen lock between chats.
- The one-hour limit for a pause, Stop or no internet.
- Any string changes.

## Design
**The cause.** `Schedules.pump()` (`Schedules.java:401-438`) holds a due task while `busy()` is true
(`Schedules.java:444-447`). `busy()` starts with `hub.runningCount() > 0`, which counts every Buddy chat that is
running (`Sessions.java:256`). Since 023, a chat stays `running` for as long as its agent process lives, background
jobs included (`ChatHub.liveInput`, `ChatHub.java:1019`). So an orchestrator chat waiting on `pipe` jobs holds every
schedule for hours. After `WAIT_MS` (1 h) the task is skipped with `schedule_skip_busy`.

**The change.** The wait reads only the due task's own chat and Buddy's own state:

```java
// fork 027: other chats at work, and their cards, no longer hold a due task up (see the feature's decisions)
static boolean waits(Sessions.Session s, boolean paused, boolean stopped, boolean online) {
    return s.running || paused || stopped || !online;
}
```

- `pump()` calls `waits(s, svc.paused(), svc.controlRevoked() != null, online())` where it called `busy(hub, svc)`, and
  `busy()` is deleted.
- `s.running` keeps the one wait the old global count gave the due chat itself. The comment at `Schedules.java:420`
  says it: an agent's own message waits for its chat to finish. A recurring task whose chat runs is still skipped
  before this line (`schedule_skip_running`).
- Two conditions leave the wait:
  - `hub.runningCount() > 0`, the bug.
  - `svc.bubble().confirming()`. A card can only be another chat's or session's at this point. Taps are already
    refused while a card shows (`Tools.java:1784`, `2133`), and cards from several chats queue (`Bubble.java:103`).
- Pause, Stop's minute and no internet still make a task wait, for an hour at most, as before.

**Why it is safe to run beside other chats.** Buddy already runs chats side by side with no lock on the screen:
- the user's own chats,
- sub-agents,
- outside sessions over MCP.

`busy()` only kept the scheduler from adding one more. Most scheduled tasks use Linux tools or the background browser;
background-mode chats have their own display. A pause holds every screen tool (`Tools.call0` → `awaitResume`), and
outward-facing actions still need approval cards.

**Alternatives considered:**
- Skip the wait only for background-mode chats: Linux-only tasks (the brief, the curator) in normal chats would still
  be dropped.
- Wait only while the control chip shows (`svc.controlActive()`): the chip stays on while any chat runs once any phone
  tool was used, so it's true for hours on pipeline days.
- Keep the wait but start anyway after the hour: still hours late.
- Run scheduled tasks one at a time among themselves: a long scheduled run would again delay or drop the next.
- Robolectric to test `pump()` end to end: a new test framework for one test.
- A prompt line telling the scheduled agent that another chat is working: the brief doesn't need it.

## Steps
1. **`app/src/main/java/app/buddy/assistant/Schedules.java`** (commit `fix(027): other chats don't hold scheduled tasks
   up`):
   - Replace `busy(ChatHub, BuddyService)` and its javadoc (lines 440-447) with the package-private static
     `waits(Sessions.Session s, boolean paused, boolean stopped, boolean online)` above. Its javadoc names the waits
     left and says why other chats and cards are gone, with a `fork 027` marker for upstream-sync reviewers.
   - In `pump()` (line 420), call `waits(s, svc.paused(), svc.controlRevoked() != null, online())`. Keep the comment
     about the agent's own message.
   - Class javadoc, lines 38-40. It currently says "another chat at work, a pause or no internet make it wait, for an
     hour at most". It becomes "a pause, Stop or no internet make it wait, for an hour at most; other chats at work
     don't".
   - Nothing else changes: the `WAIT_MS` cap, the skip texts, `fire`, `fireLater` and `start`.
2. **`app/src/test/java/app/buddy/assistant/SchedulesWaitTest.java`** (commit `test(027): scheduled task wait`). The
   class comment says "027: a due scheduled task waits only for its own chat, a pause, Stop or no internet".
   - `otherChatAtWorkDoesNotHoldATaskUp`:
     - Build `Sessions.Session pipeline` with `running = true`, and an idle `Sessions.Session brief`.
     - A comment says that `hub.runningCount()` over these two is 1, which made the old `busy()` hold `brief`. It isn't
       asserted, because `Sessions` needs a Context.
     - Assert `assertFalse(Schedules.waits(brief, false, false, true))`.
   - `ownChatAtWorkWaits`: with `brief.running = true`, assert `waits(...)` is true. This is an agent's own message in
     a chat still working, and it must not regress.
   - `pauseStopOrOfflineWait`: each of `paused`, `stopped` and `!online` alone makes `waits(...)` true.
   - `oldWaitsDoNotComeBack`: read `src/main/java/app/buddy/assistant/Schedules.java`, as ChatScrollTest does. Assert
     it contains no `runningCount(` and no `confirming(`. The message says an upstream sync brought back the wait for
     other chats, and points to feature 027.
3. **`docs/GUIDE.md`** line 58 (Scheduled tasks), in the same commit as step 1 or its own `docs(027)` commit. Add the
   fork's sentence after upstream's text: "(In the nlachica fork, other chats at work don't hold a scheduled task up:
   it starts at its time beside them; only a pause, Stop or no internet make it wait.)" GUIDE.md also ships to agents
   as `/root/.buddy/GUIDE.md`.

## Test plan
There is no local Android SDK on the phone, so CI runs the unit tests. Phone-app code only reaches the device after
merge, tag and `update_buddy`: `install_apk` refuses Buddy's own APK (solutions/android/buddy-fork-verify §4). A probe
app isn't practical, because `pump()` is tied to ChatHub, BuddyService and the alarms. So each row is CI or
after-release, and the after-release rows stay `pending` with `VERDICT: PARTIAL` until the release is installed.

| Done-when | Proof |
|---|---|
| 1 Due while another chat is busy: still runs, on time if its chat is idle, never dropped | **CI:** `SchedulesWaitTest`. `otherChatAtWorkDoesNotHoldATaskUp` covers an idle chat beside a running one: it doesn't wait, so `pump()` starts it on the same pass. `oldWaitsDoNotComeBack` fails if `runningCount(` or `confirming(` returns to `Schedules.java`. `ownChatAtWorkWaits` and `pauseStopOrOfflineWait` show the waits that remain. **After release, on the phone:** (a) Chat A, with Linux tools on, runs a foreground `sleep 600`, so A shows working. (b) In idle chat B, the agent calls `schedule_message in_minutes=2` "say the time from `date`". Expect B's Scheduled message to start within about 1 min of its `at` (exact alarms allowed) while A still shows working, and no "Scheduled task skipped" notification. Before the fix it waited for A. (c) No regression: in chat C the agent calls `schedule_message in_minutes=1` to itself, then runs a foreground `sleep 180`. Expect the message to start within about 30 s after C's run ends, not during it, and not dropped. **When it recurs:** the next morning with pipeline chats running, the 07:30 brief and the 08:00 curator run at their times. Evidence: `wa-sync/data/brief-date` mtime, and the first prompt time in the curator's transcript. The day's run count is close to 34 of 34. |
| 2 Test covers it; CI green | The PR's `build.yml` run: `testGithubDebugUnitTest` (includes `SchedulesWaitTest`), the github and fdroid release builds, the wear build and tests, `helper-tests`, and the F-Droid/reproducibility checks. |

## Risks
- **Two agents on the real screen at once.** A scheduled run that uses the screen while another chat drives it can
  tap into the other's app. This is the same as two chats the user started, which Buddy already allows. It's limited
  by these:
  - taps are refused while a card shows;
  - outward-facing actions need approval cards;
  - background mode avoids it.

  Accepted: the brief asks for "regardless of other chats".
- **More processes at once.** A schedule now starts beside pipeline sessions, so Android's phantom-process killer
  (exit 137, issue #50) gets more likely. A killed run shows as failed with its reason, which is visible, unlike
  today's silent skip. Two schedules due at the same minute now run together too.
- **Upstream sync.** `Schedules.java` is upstream's file, so an upstream change to `busy()` will conflict, or could
  bring the global wait back. `oldWaitsDoNotComeBack` turns CI red, and the `fork 027` marker tells the sync
  resolver to keep `waits()`.
- **Notification wording.** `schedule_skip_busy` still says "another task, a pause, or no internet". "Another task"
  now only means the chat's own previous run. Left as is on purpose (18 translations).

## Open questions
None blocking.

For Nicholas, later: a recurring task whose own chat is still working is still skipped. That includes a chat kept
alive by background jobs (023). Say if you want that case to wait and run too. It would be a follow-up, not this
feature.
