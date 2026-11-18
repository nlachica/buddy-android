# Plan: 025-a11y-rebind

## Goal
Sometimes Buddy's accessibility service is switched on but Android doesn't rebind it after Buddy's process restarts.
Buddy then can't reach the phone, and nothing says so until a chat fails (upstream's `hub_accessibility_dead`,
`ChatHub.java:309`). The service can also be really off, for example after a force-stop. The goal is that Buddy notices
either state by itself:
- soon after its process starts, and when the service is lost while the process lives;
- it posts one notification whose tap opens Settings → Accessibility → Buddy;
- the notification goes away once the service connects;
- connect, unbind and destroy events, with times and the last process exit reason, are logged to a file agents can read.

Done when:
1. Enabled-but-unbound is detected within about 1 min of process start and shows one notification, and tapping it
   opens Buddy's accessibility settings.
2. Truly disabled shows the same notification with the "off" text.
3. It clears itself on connect and doesn't repeat while it's pending.
4. Events with timestamps and the last exit reason are in an agent-readable file.
5. CI is green.

Non-goals:
- Re-enabling the service by itself (`settings put secure …`): research-notes §4.4. It's a security tripwire upstream
  blocks on purpose, and it would hide the cause.
- Any periodic polling while the process lives.
- Changing upstream's send-time message in `ChatHub`.
- Translating the new strings.
- Root-causing why Android doesn't rebind. The log is there to gather the evidence for that.
- The battery and sleeping-apps advice in research-notes §4.1 (no code).

Sources: `research.md` is empty for this feature. The facts come from `research-notes.md` and from reading the code
(paths below).

## Design
**One fork-owned class, `A11yWatch`**, a `JobService` with static entry points. Upstream files get one-line hooks only.

**Triggers.**
- **Process start.** `BuddyApp.onCreate`, the one place that runs on every start of Buddy's (single) process, calls
  `A11yWatch.processStarted`.
- **Service lost.** `BuddyService.onUnbind` (a new override) and `onDestroy` call `A11yWatch.lost`.
- Both log the event and make sure a check is pending.

**The check runs as a one-shot JobScheduler job.** `setMinimumLatency(40 s)` and `setOverrideDeadline(60 s)`, no other
constraint. Android treats both as best effort: Doze or an OEM's deep sleep can still delay the job (Risks), so the
Test plan measures the timing on a device instead of assuming it.
- *Why a job and not `Handler.postDelayed`:* once the accessibility binding is gone, nothing keeps Buddy's process up
  unless its UI is in front. A cached process is frozen or killed (Android's freezer, Samsung's freecess), so a
  posted Runnable would wait until something thaws the process. That happens exactly on the "service lost" path,
  where the user is in Settings.
- JobScheduler binds the `JobService`, which thaws or restarts the process, and the job survives an lmkd kill.
- *No self-restart loop:* `ensureCheck` schedules only when `getPendingJob(JOB_ID) == null`. A job that is starting
  or running is still pending. So the process start that the job itself causes doesn't schedule another job, and two
  triggers close together share one check.

**The check** (`check`, on the main thread from `onStartJob`) decides with a pure function `decide(bound, enabled)`:
- `bound`: `BuddyService.get() != null`.
- `enabled`: `SetupState.accessibilityEnabled`, upstream's own predicate.
- No setup or reply-history gate (round 1 F2). An outage before the first reply is still an outage. A fresh install
  on API 33+ posts nothing until notifications are allowed.

Results:
- `CLEAR` when bound: cancel the notification.
- `DEAD` when not bound and enabled.
- `OFF` when not bound and not enabled.

`DEAD` and `OFF` post the notice and log one line with the state and whether it was posted (or notifications are off).

**Reuse of upstream's message.** It is the same predicate and the same strings as `ChatHub.send`:
- `hub_accessibility_dead` for DEAD, `hub_accessibility_off` for OFF.
- They are already translated into 17 languages, and they tell the user what to do: switch Buddy off and on, or
  turn it on.
- `ChatHub` itself isn't touched. A shared helper would save one ternary and add a conflict point in upstream's
  busiest file.

**The notice.**
- New channel `a11y` ("Accessibility service", `IMPORTANCE_DEFAULT`) and a fixed ID 12 (1, 3, 7, 8, 9 and 11 are
  taken).
- Title `a11y_notice_title` "Buddy can't reach the phone". The body is the upstream string in `BigTextStyle`.
- `setOnlyAlertOnce(true)`, `setAutoCancel(false)`, not ongoing. It stays as a reminder until the service connects,
  and the user can swipe it away.
- `nm.notify` is wrapped against `SecurityException`, and nothing is posted when notifications are off.
- The content intent is `PendingIntent.getActivity(…, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT)` with `NEW_TASK`, opening
  `Settings.ACTION_ACCESSIBILITY_SETTINGS`, the page upstream's setup opens too. It carries the string extra
  `":settings:fragment_args_key"` = Buddy's flattened `BuddyService` component.
  - AOSP's `SettingsPreferenceFragment` reads that extra from the intent and highlights the matching row. A service
    row's key is `componentName.flattenToString()`.
  - Other Settings apps ignore the extra. Either way it is one tap to the list, and one more tap opens Buddy's page.
- Why not the details page: `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` is `@SystemApi`. Settings guards it with
  `OPEN_ACCESSIBILITY_DETAILS_SETTINGS` (`signature|installer`), so a tap from Buddy would fail with a
  SecurityException even though `resolveActivity` finds the activity (round 1 F3).
- A notification tap counts as the user's own launch, so the background-start limits don't apply.

**"No repeats while pending".**
- Checks only run after a trigger, never in a loop. A re-post while the notice is showing updates it silently: same ID,
  `onlyAlertOnce`.
- A dismissed notice stays dismissed until the next trigger (process start or service lost). Each trigger is a new
  detection.
- Connect cancels it at once (`connected` → `nm.cancel(ID)`). The pending job may still run after that, finds the
  service bound, and returns `CLEAR`.

**The breadcrumb** is `~/.buddy/a11y.log` in Buddy's Linux: `Linux.root(c)/root/.buddy/a11y.log`, the same place and
pattern as `CodexLive`'s `live.log`, so agents read it as `/root/.buddy/a11y.log`.
- One line per event, starting with local wall time `yyyy-MM-dd HH:mm:ss Z`:
  - `start: last exit <time> <REASON>(<code>) status=<n> importance=<n> "<description>"`: the newest
    `ApplicationExitInfo` from `getHistoricalProcessExitReasons(pkg, 0, 1)` (API 30, minSdk 30). That is the exit of
    the previous process, so it is logged once per start. This includes the starts where the service never connects,
    which are the case that matters. Connect lines don't repeat it. A toggle off and on within one process has no
    exit to report.
  - `connected (<s> s after process start)`, from `Process.getStartElapsedRealtime()`. This also measures how fast
    Android binds, which is evidence for tuning the 40 s.
  - `unbind`, `destroy`.
  - `check: enabled, not bound -> notified` / `check: off -> notified` / `… -> notifications off`.
- The reason names are a `String[]` for codes 0-16 (UNKNOWN … PACKAGE_UPDATED). An unknown code prints as its number.
- Writes go through a single-thread executor, as in `CodexLive`. The exit-info binder call runs there too.
- The file is skipped when Buddy's Linux isn't installed: no agent can read it then, and `mkdirs` would leave a
  half-made rootfs.
- At 64 KB the file is renamed to `a11y.log.1` before the next append. That is about 400 events per file.

**Alternatives considered:**
- `Handler.postDelayed` only: simplest, but frozen or killed with the process on exactly the paths that need it.
- AlarmManager: needs its own dedupe (a prefs timestamp) to stop an alarm-started process re-arming itself, and
  inexact alarms drift up to about 80 s. The job gives dedupe for free.
- A periodic job (15 min): catches the case where nothing ever starts the process, but that is beyond the brief's
  "after process start". A force-stop cancels jobs anyway.
- Refactoring `ChatHub`'s message into a shared helper: one line saved, at the cost of upstream merge surface.
- A gate on `Prefs.hadReply` or a "bound once" pref: either hides real outages (round 1 F2).
- The accessibility details page: needs a `signature|installer` permission (round 1 F3).

## Steps
1. **Pure core and tests.** New `app/src/main/java/app/buddy/assistant/A11yWatch.java`, holding only
   `public final class A11yWatch extends JobService` and its pure statics, with no Android calls:
   - `enum State {CLEAR, DEAD, OFF}` and `static State decide(boolean bound, boolean enabled)`.
   - `static String reasonName(int code)`.
   - `static String exitText(int reason, int status, int importance, String desc)`. Newlines in `desc` become spaces;
     a null `desc` prints as `""`.
   - `static void append(File f, String line, long max) throws IOException`. If `f` is at least `max` bytes, rename it
     to `<name>.1`, replacing any old one. Then append the line.
   - Tests: new `app/src/test/java/app/buddy/assistant/A11yWatchTest.java` (see Test plan). Keep static fields free of
     Android objects: create the `Handler`, if any, and the `NotificationManager` lazily. JVM tests run with
     `isReturnDefaultValues`.
2. **Android side**, in `A11yWatch.java`, `AndroidManifest.xml` and `res/values/strings.xml`:
   - `log(Context, String)`, `processStarted(Context)`, `connected(Context)`, `lost(Context, String event)`,
     `ensureCheck(Context)`, `check(Context)`, `notice(Context, State)`, `onStartJob` (`check(this); return false;`)
     and `onStopJob` (`false`).
   - Hold the application context only. `lost` is called from a service that is being destroyed.
   - `JOB_ID` is a fixed constant, for example 2501. There are no other jobs in the app. `CHECK_MS = 40_000`,
     `DEADLINE_MS = 60_000`.
   - `ensureCheck` wraps both `build()` and `schedule()` in a catch of `RuntimeException`; scheduling must never crash
     `Application.onCreate`. If `build()` rejects the deadline on some API, the Coder drops it and records that in
     `decisions.md`.
   - `notice`'s content intent: `Settings.ACTION_ACCESSIBILITY_SETTINGS` with the string extra
     `":settings:fragment_args_key"` = `new ComponentName(c, BuddyService.class).flattenToString()` (Design).
   - Manifest:
     - `<service android:name=".A11yWatch" android:permission="android.permission.BIND_JOB_SERVICE"
       android:exported="false" />` next to `NotificationWatcher`.
   - Strings, in English only (the fork's convention, see the comment at `strings.xml:243`), under a `<!-- 025
     (fork) … -->` comment: `a11y_channel_name` "Accessibility service" and `a11y_notice_title` "Buddy can't reach the
     phone".
3. **Hooks in upstream files** (one line each, so syncs stay easy):
   - `BuddyApp.onCreate`: `A11yWatch.processStarted(this);` after `ctx = this`.
   - `BuddyService.onServiceConnected`: `A11yWatch.connected(this);` right after `instance = this`.
   - `BuddyService`: a new `@Override public boolean onUnbind(Intent intent) { A11yWatch.lost(this, "unbind"); return
     super.onUnbind(intent); }`.
   - `BuddyService.onDestroy`: `A11yWatch.lost(this, "destroy");` right after `instance = null`.
4. **Docs.**
   - The Coder records any deviation in `decisions.md`.
   - No README or GUIDE change: the notice explains itself.
   - After the release the orchestrator, not the Coder, adds one line to the vault's `devices/phone.md`: "accessibility
     breadcrumb: `~/.buddy/a11y.log`; Buddy notifies when the service is off or unbound." The version bump and release
     follow AGENTS.md "Updating Buddy on the phone".

## Test plan
Phone-app code reaches the device only after merge, tag and `update_buddy`. `install_apk` refuses Buddy's own APK
(solutions/android/buddy-fork-verify §4). So each item names CI, probe (before release), after-release, or "when it
recurs". Timing is always read from `a11y.log` timestamps: the trigger line (`start:`, `unbind` or `destroy`) to the
`check:` line must be at most about 65 s (60 s deadline plus slack). A miss fails the row. The fix is then a different
scheduling mechanism or a timing change agreed with Nicholas, not a waiver.

**Probe check (before release, required).** It proves the DEAD half of row 1, the timing, and a process killed while
the job is pending.
- Build the probe per solutions §3:
  - `A11yWatch.java` copied unchanged except for the package.
  - Stubs for `BuddyService.get()`, `SetupState.accessibilityEnabled` and `Linux.root`, which points at the probe's
    files dir. The log is read with `run-as`; the probe is a debug build.
  - An accessibility service with the three hooks, and an `Application` that calls `processStarted`.
- Nicholas switches the probe's service on once and allows its notifications.
- Then, via `phone_shell`:
  1. `pm disable <probe>/<service>`. It stays in Enabled but is no longer Bound; `dumpsys accessibility` shows this.
     This also kills the probe, so the hooks may not run.
  2. `am start` the probe, press Home, and record `dumpsys jobscheduler <probe>`, which shows the pending job with its
     latency and deadline.
  3. `am kill <probe>` at once.
- Expect: the job restarts the probe, which is a second `start:` line, and one DEAD notice. The delay from the first
  `start:` line to `check: enabled, not bound -> notified` must be at most about 65 s.
- Then `pm enable` the component. Expect `connected` and the notice gone.
- Finally, uninstall the probe (with a confirm).

| Done-when | Proof |
|---|---|
| 1 Enabled-but-unbound is notified within about 1 min; the tap opens Buddy's page | **CI:** `decide(false, true) == DEAD`. **Probe:** the check above (DEAD, timing, kill-restart). **After release:** (a) the update restart must not give a false positive: within 2 min there is no notice, and `a11y.log` shows `start: last exit … PACKAGE_UPDATED(16)` and then `connected (N s …)`. (b) The tap target is proven in row 2. It is the Accessibility list with Buddy's row highlighted, or without the highlight on a non-AOSP Settings. Record which, and see Open questions. **When it recurs:** `a11y.log` has `check: enabled, not bound -> notified`, and Nicholas confirms the notice. |
| 2 Truly disabled gives the "off" text | **CI:** `decide(false, false) == OFF`, with no reply history involved. **After release, timed:** Nicholas switches Buddy off in Settings → Accessibility and stays in Settings, so Buddy isn't in front. One notice appears with the `hub_accessibility_off` text. `a11y.log` has `unbind`/`destroy` and then `check: off -> notified` at most about 65 s later. Nicholas taps it: record the page that opens and whether Buddy's row is highlighted. |
| 3 Clears on connect, no repeats | **CI:** `decide(true, true) == CLEAR`, `decide(true, false) == CLEAR`. **After release:** continuing row 2, Nicholas switches Buddy back on. The notice is gone within seconds, and `a11y.log` has `connected`. Over the whole run only one notice sounds (the probe run too). |
| 4 Agent-readable log with times and exit reason | **CI:** `reasonName` (3 → `LOW_MEMORY`, 10 → `USER_REQUESTED`, 16 → `PACKAGE_UPDATED`, 99 → `99`), `exitText` (newline and null description), and `append` rotation in a temp dir (past `max`: `.1` holds the old content, and the new file has only the new line; under `max`: it appends). **After release:** an agent `cat`s `/root/.buddy/a11y.log` and sees timestamped `start: last exit …`, `connected`, `unbind` and `destroy` lines from rows 1-3. |
| 5 CI green | The PR's `build.yml` run: `testGithubDebugUnitTest` (includes `A11yWatchTest`), both release builds, `helper-tests`, F-Droid checks. |

## Risks
- **Job latency.** The minimum latency and the deadline are both best effort. Doze, an OEM's deep sleep or
  system-health throttling can delay the job past 1 min. Nicholas's Buddy is unrestricted and in the exempt standby
  bucket (5), which should help, but only the timed checks above count as evidence. On other phones the notice can
  come late, never wrong.
- **No process start, no check.** If nothing starts Buddy (no UI, alarm, notification listener or boot), nothing is
  detected. This is accepted: the brief anchors detection on process start, and the first app open catches it within
  a minute.
- **Slow binding at boot** can give a brief false notice. It clears itself on connect (row 3), and the `connected (N s
  …)` lines show whether 40 s needs raising.
- **A deliberately disabled service, or a fresh install with notifications allowed before setup finishes**, gets one
  notice per process start or service loss, and each can be swiped away. Buddy can't run chats without the service,
  so the notice points at a real problem.
- **Tap target.** A third-party app can't open Buddy's accessibility details page. The tap opens the list, and the
  highlight depends on Settings honoring `:settings:fragment_args_key` (AOSP does; Samsung is unverified). At worst
  it is the plain list, as in upstream's setup.
- **Upstream merge surface:** 1 line in `BuddyApp`, 3 hooks in `BuddyService`, 1 manifest entry and 2 strings.
  `ChatHub` is untouched.
- **Language.** On a non-English phone the title is English and the body is translated (fork convention).

## Open questions
- **For Nicholas (doesn't block coding; needed before verify):** Android lets only system or installer apps open an
  accessibility service's own page (`OPEN_ACCESSIBILITY_DETAILS_SETTINGS`, `signature|installer`). Done-when 1's
  "tapping it opens Buddy's accessibility settings" can at best be "opens Settings → Accessibility with Buddy's row
  highlighted, one more tap to Buddy". Recommended: accept that as meeting the item. The Verifier records what the
  phone shows.
