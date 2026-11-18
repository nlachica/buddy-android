# Decisions: 025-a11y-rebind
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-18 - The delayed check runs as a one-shot JobScheduler job
- Options: Handler.postDelayed in the process; AlarmManager to a receiver; a one-shot JobScheduler job (40 s minimum
  latency); a periodic 15-min job.
- Choice: one-shot job, scheduled only when `getPendingJob(JOB_ID) == null`. Once the accessibility binding is gone,
  Buddy's process is cached and gets frozen or killed, so a posted Runnable waits for a thaw on exactly the paths
  that need it. A job thaws or restarts the process. Its pending state also stops the process start that the job
  itself causes from arming another job, with no extra state. AlarmManager would need a prefs timestamp for that and
  drifts up to about 80 s. A periodic job goes beyond the brief's "after process start".
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Triggers: process start and service lost, no polling
- Options: hook every entry point; `BuddyApp.onCreate` plus `BuddyService.onUnbind`/`onDestroy`; also poll while
  running.
- Choice: `BuddyApp.onCreate` (it runs on every start of the single process) plus `onUnbind`/`onDestroy`. These are
  the brief's two triggers. Without polling nothing can repeat, so "no repeats while pending" holds by construction.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Notify only once Buddy has been used (`Prefs.hadReply`)
- Options: always; only after the service was bound once (a new pref); only after `Prefs.hadReply`.
- Choice: `hadReply`. A reply needs the service, so it shows the service worked before. A fresh install in onboarding
  isn't nagged, and every phone that has used Buddy already has it set. A new pref would miss the first outage right
  after updating to this release.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Reuse upstream's predicate and strings; leave ChatHub alone
- Options: refactor `ChatHub.send`'s dead/off choice into a shared helper; repeat its one ternary in `A11yWatch`.
- Choice: repeat the ternary, using `BuddyService.get()`, `SetupState.accessibilityEnabled` and
  `hub_accessibility_dead`/`hub_accessibility_off` as the body. These are translated, and the brief says to reuse
  upstream's mechanism. Refactoring would add merge surface in upstream's busiest file to save one line.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - The tap opens the accessibility details page, falling back to the list
- Options: `ACTION_ACCESSIBILITY_SETTINGS` (the list); `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` with Buddy's component.
- Choice: the details page when `SDK_INT >= 31` and it resolves, otherwise the list. A `<queries>` entry for the
  details action makes the resolve check truthful even if the handler has no launcher entry. This gives the brief's
  "Settings > Accessibility > Buddy in one tap"; the fallback costs one extra tap.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Breadcrumb at ~/.buddy/a11y.log; exit reason logged per process start
- Options: the app's `files/a11y.log` (research-notes §4.2); `~/.buddy/a11y.log` in Buddy's Linux, like `live.log`.
  Log the exit reason on connect, or on process start.
- Choice: `~/.buddy/a11y.log`, which agents read without any extra tool. It rotates to `.1` at 64 KB and is skipped
  when Linux isn't installed. The newest `ApplicationExitInfo` is logged on each process start, not on connect,
  because the starts where the service never connects are the case being investigated.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - The notice is quiet on update, not auto-cancelled, and fixed-ID
- Options: auto-cancel on tap; ongoing; keep until connected but swipeable.
- Choice: fixed ID 12, `setOnlyAlertOnce(true)`, not auto-cancel, not ongoing. It stays as a reminder until the service
  connects (connect cancels it), and a dismissal holds until the next trigger. The new title and channel name are
  English only (the fork's convention).
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Supersedes "Notify only once Buddy has been used": no `hadReply` gate
- Options: keep `Prefs.hadReply` as the gate; a new "bound once" pref; no gate.
- Choice: no gate; the check is `decide(bound, enabled)`. Plan review round 1 F2: the gate hid real outages that
  happen before a first reply, and the brief has no such restriction. On API 33+ a fresh install posts nothing until
  notifications are allowed, and an off service really does stop Buddy working, so the notice is accurate.
- By: Planner (claude-opus-5-5)

## 2026-11-18 - Supersedes "The tap opens the accessibility details page": the list, with Buddy's row highlighted
- Options: `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` (literal string); `ACTION_ACCESSIBILITY_SETTINGS`;
  the same with `:settings:fragment_args_key`.
- Choice: `ACTION_ACCESSIBILITY_SETTINGS` with `:settings:fragment_args_key` = Buddy's flattened `BuddyService`
  component. The details action is `@SystemApi`, and Settings guards its activity with
  `OPEN_ACCESSIBILITY_DETAILS_SETTINGS` (`signature|installer`). `resolveActivity` would still find it, and the tap
  would then fail. AOSP's `SettingsPreferenceFragment` highlights the row whose key matches the extra, and a service
  row's key is its flattened component. Other Settings apps ignore the extra. There is no `<queries>` entry.
- By: Planner (claude-opus-5-5), plan review round 1 F3

## 2026-11-18 - The job gets a 60 s override deadline; timing is measured, not assumed
- Options: minimum latency only; add `setOverrideDeadline(60_000)`; another mechanism.
- Choice: 40 s minimum plus a 60 s deadline. Both are best effort, so a probe check before release (including a
  process killed while the job is pending) and Buddy's OFF run after release time it from `a11y.log`. A miss fails
  the row and reopens the mechanism.
- By: Planner (claude-opus-5-5), plan review round 1 F1
