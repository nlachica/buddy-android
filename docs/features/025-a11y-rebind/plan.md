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

**The check runs as a one-shot JobScheduler job.** `setMinimumLatency(40 s)` and no other constraint, so it runs about
40-60 s later.
- *Why a job and not `Handler.postDelayed`:* once the accessibility binding is gone, nothing keeps Buddy's process up
  unless its UI is in front. A cached process is frozen or killed (Android's freezer, Samsung's freecess), so a
  posted Runnable would wait until something thaws the process. That happens exactly on the "service lost" path,
  where the user is in Settings.
- JobScheduler binds the `JobService`, which thaws or restarts the process, and the job survives an lmkd kill.
- *No self-restart loop:* `ensureCheck` schedules only when `getPendingJob(JOB_ID) == null`. A job that is starting
  or running is still pending. So the process start that the job itself causes doesn't schedule another job, and two
  triggers close together share one check.

**The check** (`check`, on the main thread from `onStartJob`) decides with a pure function
`decide(bound, setUp, enabled)`:
- `bound`: `BuddyService.get() != null`.
- `setUp`: `Prefs.hadReply`. Chats can't run without the service, so a reply means the service has worked before.
  This keeps a fresh install in onboarding from being nagged; setup's own UI covers that case.
- `enabled`: `SetupState.accessibilityEnabled`, upstream's own predicate.

Results:
- `CLEAR` when bound: cancel the notification.
- `NONE` when not bound and not set up: do nothing.
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
- The content intent is `PendingIntent.getActivity(…, FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT)` with `NEW_TASK`, opening:
  - `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` (API 31+) with `Intent.EXTRA_COMPONENT_NAME` = Buddy's flattened
    `BuddyService` component, when `SDK_INT >= 31` and it resolves. AOSP's details page reads the extra as a string
    and falls back to the general page on a bad one.
  - Otherwise `Settings.ACTION_ACCESSIBILITY_SETTINGS`, which upstream's setup uses too.
- A `<queries>` entry for the details action makes `resolveActivity` truthful. The handler may be a package with no
  launcher entry, such as Samsung's accessibility app, which the existing LAUNCHER query wouldn't make visible.
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
- A new pref "service was bound once" as the gate: misses the first outage right after updating to this release.
  `hadReply` is already true on every phone that has used Buddy.

## Steps
1. **Pure core and tests.** New `app/src/main/java/app/buddy/assistant/A11yWatch.java`, holding only
   `public final class A11yWatch extends JobService` and its pure statics, with no Android calls:
   - `enum State {NONE, CLEAR, DEAD, OFF}` and `static State decide(boolean bound, boolean setUp, boolean enabled)`.
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
   - `JOB_ID` is a fixed constant, for example 2501. There are no other jobs in the app. `CHECK_MS = 40_000`.
   - `ensureCheck` catches `RuntimeException` from `schedule`; scheduling must never crash `Application.onCreate`.
   - Manifest:
     - `<service android:name=".A11yWatch" android:permission="android.permission.BIND_JOB_SERVICE"
       android:exported="false" />` next to `NotificationWatcher`.
     - In `<queries>`, `<intent><action android:name="android.settings.ACCESSIBILITY_DETAILS_SETTINGS" /></intent>`.
   - Strings, in English only (the fork's convention, see the comment at `strings.xml:243`), under a `<!-- 025
     (fork) … -->` comment: `a11y_channel_name` "Accessibility service" and `a11y_notice_title` "Buddy can't reach the
     phone".
   - Check the `EXTRA_COMPONENT_NAME` type against AOSP Settings' `AccessibilityDetailsSettingsFragment`, which reads
     it with `getStringExtra`. If it differs, follow AOSP and note it in `decisions.md`.
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
(solutions/android/buddy-fork-verify §4). So each item names CI, after-release, or "when it recurs".

| Done-when | Proof |
|---|---|
| 1 Enabled-but-unbound is notified within about 1 min; the tap opens Buddy's page | **CI:** `decide(false, true, true) == DEAD`. **After release:** (a) the update restart itself must not give a false positive. Within 2 min there is no notice, and `a11y.log` shows `start: last exit … PACKAGE_UPDATED(16)` then `connected (N s …)`. (b) The OFF run in row 2 proves the same job, timing, notice and tap target; only the text differs. **When it recurs:** `a11y.log` has `check: enabled, not bound -> notified`, and Nicholas confirms the notice and the tap. Until then the DEAD half of row 1 stays `pending`, giving `VERDICT: PARTIAL`. Optional earlier proof is a probe app (solutions §3) with its own accessibility service, which Nicholas switches on once. The agent `pm disable`s the probe's service component, so it stays enabled but is unbound, then starts the probe's activity and expects the DEAD notice in 40-60 s. It then `pm enable`s the component and expects the notice to clear. |
| 2 Truly disabled gives the "off" text | **CI:** `decide(false, true, false) == OFF`, `decide(false, false, *) == NONE`. **After release:** Nicholas switches Buddy off in Settings → Accessibility and stays in Settings, so the process isn't in front. Within about 1 min one notice appears with the `hub_accessibility_off` text. Tapping it opens Buddy's own accessibility page, or the general page if the phone lacks the details page; record which. `a11y.log` has `unbind`, `destroy` and `check: off -> notified`. |
| 3 Clears on connect, no repeats | **CI:** `decide(true, *, *) == CLEAR`. **After release:** continuing row 2, Nicholas switches Buddy back on from that page. The notice is gone within seconds, and `a11y.log` has `connected`. Over the whole run only one notice sounds. |
| 4 Agent-readable log with times and exit reason | **CI:** `reasonName` (3 → `LOW_MEMORY`, 10 → `USER_REQUESTED`, 16 → `PACKAGE_UPDATED`, 99 → `99`), `exitText` (newline and null description), and `append` rotation in a temp dir (past `max`: `.1` holds the old content, the new file has only the new line; under `max`: it appends). **After release:** an agent `cat`s `/root/.buddy/a11y.log` and sees timestamped `start: last exit …`, `connected`, `unbind` and `destroy` lines from rows 1-3. |
| 5 CI green | The PR's `build.yml` run: `testGithubDebugUnitTest` (includes `A11yWatchTest`), both release builds, `helper-tests`, F-Droid checks. |

## Risks
- **Job latency.** Doze or an OEM's deep sleep can delay the job past 1 min. Nicholas's Buddy is unrestricted and in
  the exempt standby bucket (5), so it runs on time there. On other phones the notice comes late, never wrong.
- **No process start, no check.** If nothing starts Buddy (no UI, alarm, notification listener or boot), nothing is
  detected. This is accepted: the brief anchors detection on process start, and the first app open catches it within
  a minute.
- **Slow binding at boot** can give a brief false notice. It clears itself on connect (row 3), and the `connected (N s
  …)` lines show whether 40 s needs raising.
- **A deliberately disabled service** gets one notice per process start or service loss, and each can be swiped away.
  Buddy can't run chats without the service, so this nag points at a real problem.
- **Details page missing or ignoring the extra.** The tap falls back to the general accessibility page: two taps
  instead of one. Row 2 records which one Nicholas's phone does.
- **Upstream merge surface:** 1 line in `BuddyApp`, 3 hooks in `BuddyService`, 2 manifest entries and 2 strings.
  `ChatHub` is untouched.
- **Language.** On a non-English phone the title is English and the body is translated (fork convention).

## Open questions
None blocking. Every branch has a recommended answer, recorded in `decisions.md`.
