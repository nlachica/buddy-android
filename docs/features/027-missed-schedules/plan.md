# Plan: 027-missed-schedules

## Goal
When another chat is busy for over an hour, scheduled messages are dropped or run hours late. On 2026-11-17 the 07:30
brief went out at 10:31, and the curator's 08:00 run didn't happen at all (`audit-excerpt.md` §4). The fix: a due
scheduled message runs at its time in its own chat, whatever other chats are doing. If its own chat is working, it runs
as soon as that chat is free. It is never dropped because a chat is busy. Use the smallest native change.

**Done when:**
1. A scheduled message that comes due while another chat is busy still runs. It runs on time if its own chat is idle,
   and it is never dropped.
2. A test covers it, and CI is green.

"Idle" means the chat has no agent running (`!s.running`). Both kinds of scheduled message are covered: a chat's
Schedule, and the agent's own `schedule_message`.

**Non-goals:**
- Notification rules' runs. `Triggers.pump` also waits for other chats (`Triggers.java:366`), but these are not
  scheduled messages.
- Two times of the same chat that both wait becoming one run (`Schedules.java:350-352`). This is upstream's design and
  GUIDE.md documents it. One run of the same message starts after both times, so nothing is dropped.
- Sending into a chat whose turn is over but whose process lives on (023 `ChatHub.liveInput`). The task waits until
  the chat is free.
- A screen lock between chats.
- The one-hour limit for a pause, Stop or no internet. It stays, but now counts from when the task's own chat is free.
- Any string changes.

## Design
**The cause.** `Schedules.pump()` (`Schedules.java:401-438`) holds a due task while `busy()` is true
(`Schedules.java:444-447`). `busy()` starts with `hub.runningCount() > 0`, which counts every Buddy chat that is
running (`Sessions.java:256`). Since 023, a chat stays `running` for as long as its agent process lives, background
jobs included (`ChatHub.liveInput`, `ChatHub.java:1019`). So an orchestrator chat waiting on `pipe` jobs holds every
schedule for hours, and after `WAIT_MS` (1 h) `pump()` skips the task with `schedule_skip_busy`. Separately, a
recurring task whose own chat is working is skipped at once (`Schedules.java:419`, `schedule_skip_running`).

**The change.** All of the start, wait and give-up decision for one waiting task moves into one pure static that
`pump()` calls with the session it found from the key's chat id:

```java
/** fork 027: what one look at a due task does (see due). */
enum Due { START, WAIT, GIVE_UP }

/**
 * fork 027: one look at a due task (key in waiting, s its chat). If its own chat is at work, the task waits for it,
 * however long, and its hour starts again, so it is never dropped for that. A pause, Stop's minute or no internet
 * make it wait, an hour at most, and then it is given up. Otherwise it starts. Other chats at work and cards waiting
 * for the user don't hold it up: they did, and on pipeline days the morning's tasks were dropped. START and GIVE_UP
 * take the key out of waiting.
 */
static Due due(Map<String, Long> waiting, String key, Sessions.Session s, boolean paused, boolean stopped,
               boolean online, long now) {
    if (s.running) {
        waiting.put(key, now + WAIT_MS);
        return Due.WAIT;
    }
    boolean held = paused || stopped || !online;
    Long until = waiting.get(key);
    if (held && until != null && now < until) return Due.WAIT;
    waiting.remove(key);
    return held ? Due.GIVE_UP : Due.START;
}
```

The key-to-chat lookup at the top of `pump()`'s loop (`Schedules.java:409-410`) moves into a pure static, so the
tests run the same selection `pump()` does (plan-review round 2, F2):

```java
/** fork 027: the chat a waiting key is for: "chat" (its Schedule) or "chat/message" (the agent's own message). */
static Sessions.Session chat(List<Sessions.Session> all, String key) {
    int slash = key.indexOf('/');
    String id = slash < 0 ? key : key.substring(0, slash);
    for (Sessions.Session s : all) if (s.id.equals(id)) return s;
    return null;
}
```

In `pump()`:
- `Sessions.Session s = chat(hub.sessions.all, key);` replaces `hub.sessions.find(...)`, which runs the same loop. The
  `Later` lookup below it stays.
- The skip chain keeps only Buddy's service being off and the agent not being signed in. The
  `s.running && l == null ? schedule_skip_running` branch goes.
- Then `due(waiting, key, s, svc.paused(), svc.controlRevoked() != null, online(), now)` decides: `WAIT` continues the
  loop, `GIVE_UP` sets `skip = schedule_skip_busy`, and `START` falls through to `start(s, l)` as today.
- `busy()` is deleted.

What changes:
- **Other chats at work** (`runningCount() > 0`) no longer hold a task up. This is the bug.
- **A card waiting for the user** (`bubble().confirming()`) no longer holds a task up. At this point the card can only
  be another chat's or session's: the task's own chat would be running. Taps are already refused while a card shows
  (`Tools.java:1784`, `2133`), and cards from several chats queue (`Bubble.java:103`).
- **The task's own chat at work** now makes both kinds wait for it, with no deadline, instead of skipping a recurring
  task at once or giving up an agent's own message after an hour. Each look resets its hour. Without that, a Stop that
  ends a long run would make the task give up at once (Stop's minute, past an old deadline).
- **Unchanged:** pause, Stop's minute and no internet (an hour at most), the service and sign-in skips, `fire` (two times
  becoming one run), `fireLater`, `start`, and `sendScheduled`'s own check.

**A waiting Schedule survives a restart** (plan-review round 2, F1). `waiting` is only in memory. Today a restart
during a wait drops the run, and with waits for the task's own chat that can be hours. An agent's own message is safe
already: it stays in `s.later` until it leaves `waiting`, and `serviceStarted` → `sync` → `setLater` arms its past
alarm, which fires at once. A chat's Schedule isn't: `sync` sets only its next alarm. So the Schedule keys of `waiting`
are mirrored to Buddy's prefs:
- `static Set<String> held(Map<String, Long> waiting)`, pure: the keys without `/`.
- `Prefs.heldSchedules(c)` / `Prefs.setHeldSchedules(c, set)`: string set `schedules_held`, in the style of
  `busyChats` / `setBusy` (`Prefs.java:599-607`), `commit()`.
- A private `keep(Context c)` writes `held(waiting)` when it differs from what's stored. It runs at the end of `pump()`
  and in `cancel()` after `waiting.remove(id)`. Those cover every change to a Schedule key: `fire` and `runNow` call
  `pump()` right after their put.
- `static void restore(Map<String, Long> waiting, Set<String> held, long now)`, pure: `putIfAbsent(id, now + WAIT_MS)`
  for each id.
- `serviceStarted(c)` becomes `listen(c); restore(waiting, Prefs.heldSchedules(c), SystemClock.elapsedRealtime());
  sync(c); pump();`. `BuddyService.instance` is set before this call (`BuddyService.java:81`, `:129`), so `pump()`
  doesn't take it for "no service".

The existing rules keep working. There's one key per chat, so two times still become one run. A pause, a removed
schedule or a deleted chat goes through `cancel()`, and `sync` runs after the restore, so a paused schedule's restored
key goes again. `pump()` drops a key whose chat or schedule is gone. A dispatch, give-up or skip clears the stored copy
on the same pass, because the stored set is derived from the map.

**Element ids belong to the chat that read them** (plan-review round 2, F3). `BuddyService` has one `Tools`, so all
chats share one `lastNodes` list. `node()` resolves an id against whichever chat called `read_screen` last
(`Tools.java:1110-1113`, `1307-1319`), and a node on another display can still pass `refresh()`. The fix:
- `readScreen()` sets `readKey = screenKey()` and `readDisplay = display()` under `lock` when it clears `lastNodes`.
- `node()` first checks the pure `static boolean sameRead(String readKey, int readDisplay, String key, int display)`
  (`Objects.equals` on the keys, `==` on the displays) against `screenKey()` and `display()`. On a mismatch it throws
  `IllegalArgumentException("Element ids are from another chat's read_screen. Call read_screen again.")`, like the
  unknown and stale element errors next to it.
- `node()` is the only lookup by element id. There's no lock across calls. Two chats driving screens at once may need
  an extra `read_screen`, but a tap never lands on the other chat's element.

**Smart review reads the calling chat** (plan-review round 1, F3). Once a scheduled run works beside another chat, Smart review
must not read the other chat's request. Today `Tools.review` asks `ChatHub.reviewContext()`, which takes
`latestRunning()`, the chat with the newest event (`ChatHub.java:961`). The calling chat is already known on the tool's
thread (`McpServer.CALLER_CHAT`, also read at `Tools.java:671`). The fix:
- `Tools.review` reads `McpServer.CALLER_CHAT.get()` before posting to the main thread and calls
  `reviewContext(chat)`.
- `reviewContext(String chat)` starts from `reviewed(sessions.all, chat)`. When that is null, it uses
  `latestRunning()` as today.
- A new pure `static Sessions.Session reviewed(List<Sessions.Session> all, String chat)` returns:
  - the session with that id;
  - for a sub-agent or a helper (`!parent.isEmpty()`), its parent, which is today's rule ("those work for their
    parent");
  - null for a null or unknown id (an agent outside Buddy's chats keeps today's behaviour).

**Why running beside other chats is safe enough.** Buddy already runs chats side by side, with no lock on the screen:
the user's own chats, sub-agents, and outside sessions over MCP. `busy()` only kept the scheduler from adding one more.
Most scheduled tasks use Linux tools or the background browser, and background-mode chats have their own display. A
pause holds every screen tool (`Tools.call0` → `awaitResume`). Outward-facing actions still need approval cards. With
the changes above, Smart review judges each chat against its own request, and element ids only work for the chat that
read them.

**Alternatives considered:**
- Skip the wait only for background-mode chats: Linux-only tasks (the brief, the curator) in normal chats would still
  be dropped.
- Wait only while the control chip shows (`svc.controlActive()`): once any phone tool has been used, the chip stays on
  while any chat runs, so it's true for hours on pipeline days.
- Keep the wait but start anyway after the hour: still hours late.
- Run scheduled tasks one at a time among themselves: a long scheduled run would again delay or drop the next.
- Keep an own-chat deadline, such as giving up after an hour: the brief says never dropped.
- Send into a 023 live chat whose turn is over: that's a new delivery path, and the brief only asks for as soon as
  possible.
- Robolectric to test `pump()` end to end: a new test framework for one test. `chat()` and `due()` hold the whole
  selection and decision.
- A restart: store the held occurrence in `Plan`'s JSON. That touches upstream's `toJson`, `of` and `copy`, and every
  path that ends a wait would have to clear it. A set derived from `waiting` can't drift from it.
- Element ids: one cache per chat. That needs cleanup when a chat ends, and the reviewer's owner check is enough.

## Steps
1. **`Schedules.java`, plus the new `app/src/test/java/app/buddy/assistant/SchedulesWaitTest.java`.** Commit:
   `fix(027): only a task's own chat holds it up, and it is never dropped for that`.
   - Add `enum Due`, `due(...)` and `chat(...)` as above, package-private, with `fork 027` javadocs.
   - In `pump()` (lines 409-423):
     - `chat(hub.sessions.all, key)` in place of `hub.sessions.find(...)` at line 410.
     - Drop the `schedule_skip_running` branch from the skip chain.
     - Call `due(...)` as described, and replace the comment at line 420 with `// fork 027: see due()`. Keep the
       `waiting.remove(key)` at line 424 for the service and sign-in skips.
   - Delete `busy()` and its javadoc (lines 440-447).
   - Class javadoc, lines 38-40, becomes: "An agent that isn't signed in or Buddy's service being off skips the task,
     with a notification. A pause, Stop or no internet make it wait, for an hour at most. Its own chat still working
     makes it wait until the chat is done (fork 027: other chats at work don't hold it up)."
   - `SchedulesWaitTest` has the class comment "027: a due scheduled task waits only for its own chat (however long), a
     pause, Stop or no internet". Sessions are built as in TurnTrackTest: `a` is the pipeline and `b` the task's chat,
     in `all = [a, b]` (A first, as the most recent). Each look is `Schedules.due(waiting, key, Schedules.chat(all,
     key), ...)`, the selection and decision `pump()` makes, over the test's own `LinkedHashMap`. Each case runs for
     key `b` (a Schedule) and `b/x` (the agent's own message), and asserts `assertSame(b, Schedules.chat(all, key))`:
     - `anotherChatAtWorkDoesNotHoldATaskUp`: `a.running`, `b` idle, the waiting key due now. The first look gives
       `START`, and the key is gone from the map. A comment says the old `busy()` held this task, because
       `runningCount()` was 1.
     - `ownChatAtWorkWaitsPastTheHourThenStartsOnce`: `a` and `b` running, deadline `t0 + 1 h`. Looks at `t0` and at
       `t0 + 3 h` give `WAIT`, and the key is still there. Then `b.running = false` while `a` keeps running: the look
       at `t0 + 3 h + 1 s` gives `START`, and the key is gone.
     - `theHourCountsFromWhenItsChatIsFree`: `b` running at `t0 + 3 h`, then idle and stopped at `t0 + 3 h + 1 s` gives
       `WAIT`, not `GIVE_UP`.
     - `pauseStopOrOfflineWaitAnHourAtMost`: `b` idle, and each of paused, stopped and offline alone. Before the
       deadline: `WAIT`. At or after it: `GIVE_UP`, and the key is gone.
     - `oldWaitsDoNotComeBack`: read `src/main/java/app/buddy/assistant/Schedules.java` as ChatScrollTest does, and
       assert it contains no `runningCount(`, no `confirming(` and no `schedule_skip_running`. The message says an
       upstream sync brought back a wait or skip that feature 027 removed, and points to the feature folder.
2. **`Schedules.java`, `Prefs.java`, plus tests in `SchedulesWaitTest`.** Commit:
   `fix(027): a waiting Schedule survives a restart`.
   - `Prefs`: `heldSchedules(c)` and `setHeldSchedules(c, set)` beside `busyChats` (string set `schedules_held`,
     `commit()`), with a `fork 027` javadoc.
   - `Schedules`: `held(...)`, `restore(...)` and the private `keep(c)` as above. `keep(app)` goes at the end of
     `pump()`'s `try`, and `keep(c)` in `cancel()` after `waiting.remove(id)`. `serviceStarted` restores before `sync`,
     then calls `pump()`. The `waiting` field's comment gains "(fork 027: its chats' Schedules are kept in Prefs too,
     see keep)".
   - Tests:
     - `aWaitingScheduleSurvivesARestart`: `a` and `b` running, key `b` waits (`WAIT`), and `held(waiting)` is `{b}`.
       The restart is a new map, `restore(fresh, held, t1)`, and `b.running = false`. The first look there gives
       `START`, and `held(fresh)` is empty.
     - `agentMessagesAreNotHeldInPrefs`: with `b` and `b/x` waiting, `held` is `{b}`. `b/x` comes back through
       `s.later` and `sync`.
3. **`ChatHub.java`, `Tools.java`, plus the new `app/src/test/java/app/buddy/assistant/ReviewChatTest.java`.** Commit:
   `fix(027): Smart review reads the calling chat's request`.
   - `ChatHub`: add `reviewed(...)` as above, with a `fork 027` javadoc. `reviewContext()` becomes
     `reviewContext(String chat)`: `Sessions.Session latest = reviewed(sessions.all, chat); if (latest == null) latest
     = latestRunning();`. The rest is unchanged. Its javadoc names the calling chat.
   - `Tools.review` (line 1831): `String chat = McpServer.CALLER_CHAT.get();` before `svc.runOnMain`, and
     `reviewContext(chat)` inside it. The only caller is line 1837.
   - `ReviewChatTest` (class comment "027: Smart review reads the calling chat's request, not the newest chat's"):
     - `a` and `b` are running, and `a.lastEventAt` is newer. `reviewed(all, "b")` is `b`.
     - A sub-agent `c` with `parent = "b"`: `reviewed(all, "c")` is `b`.
     - `reviewed(all, null)` and `reviewed(all, "gone")` are null, so the caller falls back to `latestRunning()`,
       today's outside-session behaviour.
4. **`Tools.java`, plus the new `app/src/test/java/app/buddy/assistant/ElementIdsTest.java`.** Commit:
   `fix(027): element ids only work for the chat that read them`.
   - Fields `readKey` (String) and `readDisplay` (int), set in `readScreen()` under `lock` right after
     `lastNodes.clear()` (line 1112). Add `sameRead(...)` as above, and its check at the top of `node()`'s
     `synchronized` block (line 1310). Each gets a `fork 027` comment.
   - `ElementIdsTest` (class comment "027: element ids from one chat's read_screen don't work for another chat"). It
     replays interleaved reads through `Tools.sameRead`:
     - A reads on the real screen (`"a"`, 0), and A's lookup passes.
     - B reads on its own display (`"b"`, 7). Now A's lookup is refused and B's passes.
     - A reads again, and it flips.
     - The same chat after its display changed (`"a"`, 7 read, `"a"`, 0 lookup) is refused.
     - An outside session with no key (`null`, 0) matches only a read with no key.
5. **`docs/GUIDE.md`** line 58 (Scheduled tasks), as its own `docs(027)` commit. Add the fork's sentence after
   upstream's text: "(In the nlachica fork, a run whose chat is still working waits for it to finish instead of being
   skipped, also across a restart of Buddy, and other chats at work don't hold a scheduled task up: it starts at its
   time beside them. Only a pause, Stop or no internet make it wait, for an hour at most.)" GUIDE.md also ships to
   agents as `/root/.buddy/GUIDE.md`.

## Test plan
There is no local Android SDK on the phone, so CI runs the unit tests. Phone-app code only reaches the device after
merge, tag and `update_buddy`: `install_apk` refuses Buddy's own APK (solutions/android/buddy-fork-verify §4). So each
row is CI or after-release, and the after-release rows stay `pending` with `VERDICT: PARTIAL` until the release is
installed. `Schedules` loads in a JVM test: with `isReturnDefaultValues`, `Looper.getMainLooper()` returns null, and
the stubbed `Handler` constructor doesn't throw.

The phone checks hold a chat working with a question card (`ask` left unanswered), not with long foreground commands,
which the no-long-foreground hook blocks. **Precondition for the timed checks:** exact alarms. The `schedule_message`
reply must not contain "Android allows Buddy no exact alarms" (`Schedules.java:677`). If it does, turn on Alarms &
reminders for Buddy first, so a late inexact alarm isn't read as a scheduler delay. A restart while a Schedule waits is
covered in CI only: on the phone, the only clean restart of Buddy is installing a release.

| Done-when | Proof |
|---|---|
| 1 Due while another chat is busy: still runs, on time if its chat is idle, never dropped | **CI:** `SchedulesWaitTest`. Through `chat(all, key)` and `due()`, the same selection and decision `pump()` makes, with both chats in `all`, it covers: an idle chat starting on the first look beside a running one; an own chat at work holding both kinds past the old hour, then starting once; the hour counting from when the chat is free; and the waits that remain. It also covers a waiting Schedule surviving a restart and being cleared once it starts. `oldWaitsDoNotComeBack` guards against an upstream sync bringing the old waits back. Once runs overlap, `ReviewChatTest` covers Smart review reading the right chat, and `ElementIdsTest` covers element ids staying with the chat that read them. **After release, on the phone** (exact alarms on, see above): (a) Chat A's agent calls `ask` and the card stays open, so A shows working. In idle chat B, the agent calls `schedule_message in_minutes=2` "say the time from `date`" and its turn ends. Expect B's Scheduled message to start within about 1 min of its `at` while A's card is still open, with no "Scheduled task skipped" notification. Record that A was still working: a `read_screen` of the chat list when B's run starts shows A working, and B's first event comes before the time A's card is answered. Then answer A's card. Before the fix, B waited for A. (b) In chat C, the agent calls `schedule_message in_minutes=1` to itself, then `ask`. Leave the card for about 3 min, then answer. Expect nothing to start in C while the card is open, then the message to start within about 30 s of C's run ending, not dropped. (c) Chat D's options → Schedule, at a time 2 min ahead. D's agent calls `ask`, and the card stays past that time, then is answered. Expect the scheduled run to start right after D's run ends, with no "skipped: still working" notification. Before the fix it was skipped at once. **When it recurs:** the next morning with pipeline chats running, the 07:30 brief and the 08:00 curator run at their times. Evidence: the `wa-sync/data/brief-date` mtime, and the first prompt time in the curator's transcript. The day's run count is close to 34 of 34. |
| 2 Test covers it; CI green | The PR's `build.yml` run: `testGithubDebugUnitTest` (includes `SchedulesWaitTest`, `ReviewChatTest` and `ElementIdsTest`), the github and fdroid release builds, the wear build and tests, `helper-tests`, and the F-Droid/reproducibility checks. |

## Risks
- **Two agents on the real screen at once.** A scheduled run that uses the screen while another chat drives it can
  tap into the other's app. This is the same as two chats the user started, which Buddy already allows. It's limited
  by these:
  - taps are refused while a card shows;
  - outward-facing actions need approval cards;
  - Smart review now judges each chat against its own request;
  - element ids only work for the chat that read them;
  - background mode avoids it.

  Accepted: the brief asks for "regardless of other chats". Two chats that drive screens at once may each need an
  extra `read_screen` call when the other read in between.
- **A long-lived own chat holds its task that long.** A chat kept running by background jobs (023) holds its own
  scheduled task until the process ends, possibly hours. The brief asks for "as soon as possible", which this is.
  A waiting task costs a look on the main thread every 30 s, and no wake lock is held meanwhile.
- **A restored run can be old.** If Buddy was off for days, for example with the phone switched off, a Schedule that
  was waiting runs once when the service starts again. An agent's own message already does the same today. A backup
  carries Buddy's prefs, `schedules_held` included, so restoring one made while a run waited starts that run once.
  Both are rare, and one run is what "never dropped" asks for.
- **More processes at once.** A schedule now starts beside pipeline sessions, so Android's phantom-process killer
  (exit 137, issue #50) gets more likely. A killed run shows as failed with its reason, which is visible, unlike
  today's silent skip.
- **Upstream sync.** `Schedules.java`, `Prefs.java`, `ChatHub.java` and `Tools.java` are upstream's files.
  `oldWaitsDoNotComeBack` turns CI red if the old waits come back. The `fork 027` markers tell the sync resolver to
  keep `due()`, `chat()`, `held()`/`restore()`/`keep()`, `reviewed()` and `sameRead()`.
- **Notification wording.** `schedule_skip_busy` still says "another task, a pause, or no internet". It is now only
  shown after an hour of pause, Stop or no internet. Left as is on purpose (18 translations).

## Open questions
None blocking.
