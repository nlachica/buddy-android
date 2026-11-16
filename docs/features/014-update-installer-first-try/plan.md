# Plan: 014-update-installer-first-try

## Goal
`update_buddy install=true` must show Android's installer on the first call, also when another app is in front. If
the installer can't be shown, the tool must say so and tell the user what to tap, instead of claiming "The installer
is open". Done when: (1) on the device, with another app in front, the installer opens on the first call (verified on
the release after the one carrying this fix); (2) a launch that doesn't show leads to a result that says so and names
what to tap; (3) CI is green.

Non-goals: silent installs; the approval card's wording; the release flow; `install_apk` (its `wait_for_install`
already reports a missing installer within 12 s); the "allow installs" settings-page launch (`NEEDS_PERMISSION`,
granted once on this phone); the in-app update sheet (it runs with Buddy's activity in front); the PackageInstaller
session API; full-screen-intent notifications.

## Design
**The cause.** The brief and research guess at Android's background-activity-launch (BAL) block. AOSP source doesn't
support that guess. In android14-, 15- and 16-release, `AccessibilityServiceConnection.bindLocked` binds every
accessibility service with `Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` and calls `setAllowAppSwitches`, and
`BackgroundLaunchProcessController.areBackgroundActivityStartsAllowed` allows that process by token (`BAL_ALLOW_TOKEN`)
before any visibility check. A `NEW_TASK` start with that verdict also passes the ASM check
(`BackgroundActivityStartController`). That is why `open_app` works from the same `BuddyService` with another app in
front. Samsung may differ, and no logcat exists from a failing run, so the cause stays unconfirmed. The one firm fact
is "the second try works". It fits a first-attempt state such as the installer process starting cold, a leftover
installer task from the previous update, or a Play Protect first look at a new APK. It also fits BAL if Samsung changed
the rules. The design therefore doesn't depend on the cause. The cause itself is found on the device: the update to the
fix release still runs the old code, so it reproduces the bug, and the orchestrator captures logcat and dumpsys around
it (Test plan, Cause).

**The fix: check, retry once, then hand off by notification.** This all happens in `Updates.install`, the agent path
only:
1. After the download and checks, start the installer exactly as today.
2. Watch the real screen for up to 8 s, polling every 250 ms. Only Android's installer itself counts: a window whose
   app package contains `packageinstaller` (Google's and Samsung's installer; the same rule as
   `BuddyService.installerInFront`). Play Store, Play Protect, the "Open with" chooser and other system windows don't
   count, so they can't produce a false "open". `wait_for_install`'s looser predicate stays as it is. The APK is about
   7 MB, so it stages in under a second, and the installer normally appears in 1-3 s. If the installer shows →
   `OPENED`, with the same text as today.
3. If not, start it once more with the same intent and watch for up to 8 s again. If it shows → `OPENED_RETRY`. The
   agent gets today's text plus "(it came up on Buddy's second try)". That shows a retry helped, not why the first
   start failed (see Test plan, Cause).
4. If it still doesn't show, post a notification on the existing `updates` channel, replacing the "update available"
   notification (same ID 3). Its title is "Buddy X is ready to install" and its content intent is the installer intent
   itself (a `PendingIntent.getActivity`, immutable, with the read-URI grant). A user's tap on a notification is
   always allowed to start an activity, whatever the cause. → `NOTIFIED`. The result says Buddy couldn't see the
   installer on screen and tells the agent to have the user tap that notification and then Update. If notifications
   are off or the channel is blocked → `NOT_SHOWN`. The result says so, asks the user to open the Buddy app and has the
   agent call again, plus `MANUAL`. Both texts add one clause: if a Play Protect or "Open with" screen is showing
   instead, the user finishes that first, then taps Update.

**A start that throws** (`ActivityNotFoundException`, `SecurityException`). On the first start the exception leaves
`openChecked`, `install` deletes the file as today, and `Tools.updateBuddy` catches it and returns that Android
couldn't open its installer, so nothing is on screen, plus `MANUAL` (buddy.apk on the release page, then Update). No
notification: a missing installer isn't fixed by wrapping the same intent in a PendingIntent. If only the retry
throws, the first start did dispatch, so `openChecked` swallows it and goes on as "not shown" (wait, then the
notification). The file stays.

The sequence is a Context-free static method that takes three lambdas (launch, on screen, fallback). That makes it
unit-testable, like `report`. The agent-facing texts move into a static `Updates.afterInstall(res, version)` for the
same reason. Tools supplies the on-screen check with `svc.getWindows()`, which reads the real (default) display: the
installer always lands there, even when the calling chat works on a background screen, where `Tools.windows()` would
read the wrong display. The package rule is a static `Updates.installerPkg(CharSequence)`, so its negative cases are
unit-tested. The sheet passes `null` for the check and behaves exactly as today. `update_buddy` also counts
in `installWaits` while it downloads and checks, as `BuddyService`'s comment on that field already says it should. A
user touching the screen while the installer is late then doesn't auto-pause the chat.

Alternatives, one line each:
- Always notify, never start directly: an extra tap on every update, even when the direct start works.
- Download first, then start the installer from the card tap: changes the flow, wastes a download on "no", and doesn't
  help if the cause isn't BAL.
- Full-screen intent: needs `USE_FULL_SCREEN_INTENT`, which Android 14+ revokes by default for apps that aren't calls
  or alarms, and it only matters on the lock screen.
- PackageInstaller session API: a bigger change, and its confirm activity has to be started the same way.
- `NEW_TASK|CLEAR_TASK` or an explicit launch display: speculative without logcat. The retry and the notification
  already cover these causes.

## Steps
1. **`Tools.java`**: new `private boolean installerShown()`: true if any window in `svc.getWindows()` has a root
   whose package passes `Updates.installerPkg`. `installerOnScreen()` and `waitForInstall` are not touched.
2. **`Updates.java`**: Context-free core, plus a test.
   - New constants `OPENED_RETRY`, `NOTIFIED`, `NOT_SHOWN` next to `OPENED`/`NEEDS_PERMISSION`, documented.
   - `static boolean installerPkg(CharSequence pkg)`: non-null and contains `packageinstaller`.
   - `static boolean appears(BooleanSupplier shown, long waitMs)`: polls every 250 ms. On interrupt it restores the
     flag and returns the current value, so there's no checked exception and the sheet's thread needs no change.
   - `static String openChecked(Runnable launch, BooleanSupplier shown, BooleanSupplier fallback, long waitMs)`: runs
     the four outcomes above. With `shown == null` it launches once and returns `OPENED` without waiting. The first
     launch's exception propagates; the retry's `RuntimeException` is caught and treated as not shown.
   - `static String afterInstall(String res, String version)`: the agent texts for `OPENED` (today's text, word for
     word), `OPENED_RETRY`, `NOTIFIED`, `NOT_SHOWN` and `NEEDS_PERMISSION` (today's text). Any other value is an error:
     `res + " " + MANUAL`, as today.
   - New `app/src/test/java/app/buddy/assistant/InstallCheckTest.java` (see Test plan).
3. **`Updates.java` + `res/values/strings.xml`**: wire it up.
   - `private static Intent installer(File apk)`: today's `ACTION_VIEW` intent.
   - `private static boolean notifyInstall(Context c, String version, File apk)`.
     - Content: `CHANNEL`, `ID`, `PendingIntent.getActivity(c, 32, installer(apk), FLAG_IMMUTABLE |
       FLAG_UPDATE_CURRENT)`, `setAutoCancel(true)`.
     - Returns false if `!nm.areNotificationsEnabled()`, if the channel's importance is `IMPORTANCE_NONE`, or on a
       `SecurityException`.
     - Channel creation is shared with `notify` through a small helper.
   - `install(Context, Release, BooleanSupplier shown)` replaces `install(Context, Release)` and calls the private
     `install(c, r, false, shown)`, which gains the `shown` parameter. After the `canRequestPackageInstalls` check it
     calls `openChecked(() -> c.startActivity(installer(out)), shown, () -> notifyInstall(c, r.version, out), 8000)`.
     - Keep today's delete-on-throw of `out` (the `opened` flag and `finally`), now around the `openChecked` call: only
       a throwing first start reaches it.
     - Call `cancel(c)` only for `OPENED`/`OPENED_RETRY`, never after `NOTIFIED`.
     - Never delete `out` once a start was dispatched: the installer or the notification may still open it, and
       `newApk` removes day-old files.
   - The sheet calls `install(a, r, true, null)`.
   - Strings, English only (the fork's convention since 001/003/005/008): `update_ready_title` "Buddy %1$s is ready
     to install" and `update_ready_text` "Tap to open Android's installer, then tap Update. Your chats stay."
4. **`Tools.updateBuddy`**: around `Updates.install`, do `svc.installWaits++` / `--` in a try/finally. Pass
   `this::installerShown`. Catch `RuntimeException` there and set `res` to "Couldn't open Android's installer (<message>),
   so it isn't on screen. The user can tap buddy.apk on the release page below, then Update." Return
   `Updates.afterInstall(res, r.version)`, which appends `MANUAL` (the page). The tool definition text stays as it is.
5. **Docs**:
   - Append to `decisions.md` anything the Coder decides differently.
   - Nothing in README/GUIDE: the user-facing flow is unchanged.
   - The version bump and release follow AGENTS.md "Updating Buddy on the phone", done by the orchestrator after the
     merge. This step is not the Coder's.

## Test plan
- **Unit tests, CI `testGithubDebugUnitTest`** (the task `build.yml` runs; also runnable on the phone with javac +
  JUnit, as in 004). Each case uses lambdas with a launch counter and `waitMs` = 50.
  `InstallCheckTest` for `openChecked`:
  - shown at once → `OPENED`, 1 launch.
  - shown only after the 2nd launch → `OPENED_RETRY`, 2 launches.
  - never shown, fallback true → `NOTIFIED`, 2 launches, fallback called once.
  - never shown, fallback false → `NOT_SHOWN`.
  - `shown == null` → `OPENED`, 1 launch, no fallback.
  - first launch throws → the exception propagates, 1 launch, fallback not called.
  - only the retry throws, never shown, fallback true → `NOTIFIED`, 2 launches, fallback called once.

  `InstallCheckTest` for `installerPkg`: true for `com.google.android.packageinstaller` and
  `com.samsung.android.packageinstaller`; false for `com.android.vending`, `android`, `com.android.chrome` and `null`.

  `InstallCheckTest` for `afterInstall`:
  - `OPENED` equals today's exact text.
  - `OPENED_RETRY` contains it.
  - `NOTIFIED` and `NOT_SHOWN` contain the version and never contain "installer is open". `NOTIFIED` names the
    notification and Update. `NOT_SHOWN` says notifications are off, says to open Buddy and contains `MANUAL`.
  - any other string (e.g. the "Couldn't open Android's installer" text) → that string + " " + `MANUAL`.
- **Cause, required (orchestrator, at ship time of the fix release; needs Phone commands).** Updating to the release
  that carries the fix still runs the old code, so that update is the reproduction of the original bug.
  1. Before: `phone_shell 'dumpsys activity activities | grep -iE -B1 -A3 packageinstaller'` (a leftover installer
     task?). Put Settings or Chrome in front.
  2. From a Linux-tools chat, call `update_buddy install=true` and approve the card. Right after the result:
     read_screen (is the installer showing?), then `phone_shell 'logcat -d -v time -t 4000 | grep -iE "background
     activity|android-bal|BalVerdict|abort background|packageinstaller|vending" | tail -60'` and the dumpsys again.
  3. Record in `verify.md`, row "Cause", the raw lines and which hypothesis they support: BAL (a blocked/abort line
     for Buddy), a cold or late installer (a start for the installer, displayed late or not at all), a leftover task
     (dumpsys shows an installer task before the call), or Play Protect (a vending window or log).
  4. If the first call works this time (no reproduction) or Phone commands are off, write "cause unresolved" and why.
     The result code (`OPENED_RETRY`) alone never counts as the cause.
- **DW1, device, on the next release (the first one installed by the fixed code).** Same captures as Cause, steps 1-2.
  Pass only if the installer is on screen after the first call (read_screen or a screenshot) and the result is `OPENED`
  or `OPENED_RETRY`; both happen inside the first call. `NOTIFIED` or `NOT_SHOWN` means DW1 fails and stays open (the
  fallback meets DW2 only). Note which result came, with the logcat lines, in `verify.md`.
- **DW2.**
  - Unit tests above.
  - Code read: `notifyInstall` returns false when notifications are off; `Tools.updateBuddy` catches a throwing start.
  - If the fallback happens on the device, screenshot the notification and tap it: the installer must open.
- **DW3**: `gh pr checks --watch` on the PR, every check green, including `apk` and its reproducibility step. Record the
  `testGithubDebugUnitTest` result in `verify.md`.

## Risks
- **Cause unconfirmed.** If neither start ever shows (for example Samsung blocks the start), every update takes one
  tap on the notification. That still meets DW2 but not DW1. The Cause capture then points to the follow-up.
- **Screens before the installer don't count.** If an "Open with" chooser or a Play Protect dialog shows first, the
  check doesn't see the installer: Buddy retries (a second such screen may stack) and then posts the notification. The
  result stays truthful ("couldn't see the installer", with the Play Protect / "Open with" clause). No such screen was
  reported on this phone: the second call always showed the Update dialog directly.
- **Late first installer.** If it appears after 8 s, the retry stacks a second installer. Tapping Update on the top
  one installs; the other may show an error afterwards. Acceptable.
- **Slower failure result.** The worst case adds about 16 s before a failure result. The happy path adds about 1-3 s.
- **Expired notification file.** A notification tapped more than a day later may point at a file `newApk` already
  removed. The installer then shows a parse error, and calling again fixes it.
- **Delayed device proof.** DW1 can only be verified on the release after the fix (see Open questions).

## Open questions
None blocking. One timing choice for Nicholas, for the orchestrator to raise at ship time: verify DW1 on whatever
release comes next, or cut a version-bump-only release right after this one to verify now.
