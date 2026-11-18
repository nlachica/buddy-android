# Code review: 025-a11y-rebind
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

F1 [minor] The startup breadcrumb timestamps the completed lookup, not the startup trigger - `app/src/main/java/app/buddy/assistant/A11yWatch.java:103` queues the exit-history lookup, then line 121 calls `log`, which captures the current time and queues a second task. If the worker or binder lookup is delayed, the `start:` timestamp is late; if `connected` is queued during the lookup, its line is written before `start:`. The notification still runs independently, but the plan's `start:`-to-`check:` timing measurement can understate the real delay, and the lifecycle log can show connection before startup. -> Capture the startup timestamp synchronously in `processStarted`, and append the completed startup line directly from its existing logger task instead of re-enqueuing it through `log`. Keep the exit lookup and file I/O off the main thread. When verifying timing, also retain an independent trigger time until this is corrected.

Reviewed `git diff main...HEAD`, the touched application files and tests, and the connected `BuddyService`, `BuddyApp`, `SetupState`, `ChatHub`, `Linux`, notification, and logging code. Feature 024's release-check changes are inherited through merge `dbe4037`; feature 025 itself adds the planned watch, lifecycle hooks, manifest service, strings, and tests. No blocker or major correctness, security, or YAGNI finding is open. The clarified Accessibility-list destination is recorded in the brief and orchestrator decisions; the protected details action is not reintroduced.

Coverage and validation:

- All planned JVM cases are present in `A11yWatchTest`: the four bound/enabled combinations, reason names including an unknown code, own-process selection including null/no-match cases, exit description formatting, and append/rotation behavior.
- [CI run 44219737982](https://github.com/nlachica/buddy-android/actions/runs/44219737982) passed for reviewed HEAD `d0b1d5ad50a8cd570d96aa27cfa2696ea7590ad0`. Its logs show `:app:testGithubDebugUnitTest` and `:wear:testDebugUnitTest` executed successfully; both release builds, helper tests, upstream-sync tests, the no-Google-code check, and F-Droid reproducibility also passed.
- Local Java cannot initialize (`Failed to mark memory page as executable`), so local JVM tests were not rerun. `git diff --check main...HEAD` reports only trailing whitespace in the existing verification-template `VERDICT:` line; no style finding is raised.
- Device evidence remains for the Verifier: the required pre-release DEAD/kill-restart probe and measured delay, then the planned Buddy OFF/tap/reconnect/no-repeat and agent-readable-log checks after release. `verify.md` has no results yet. Code review approval does not establish those done-when items or authorize skipping the probe. Android documents that [override deadlines can still be delayed by Doze and system health](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setOverrideDeadline(long)); green JVM tests do not establish the approximately one-minute behavior.

VERDICT: APPROVE
