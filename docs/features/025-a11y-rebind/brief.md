---
type: brief
feature: 025-a11y-rebind
created: 2026-11-18
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 025-a11y-rebind

## Ask (verbatim, never edit)
a11y

## Goal
Buddy's accessibility service ends up 'enabled but not bound': on 2026-11-18 dumpsys accessibility listed app.buddy.assistant/.BuddyService under Enabled services but not Bound (only Tasker bound), Crashed empty; battery unrestricted, standby bucket 5, RUN_ANY allow. So Android didn't rebind after Buddy's process restarted (updates, WebView update, lmkd). Upstream 2.3.12 only warns at send time (hub_accessibility_dead, ChatHub.java:309). Wanted: Buddy notices this itself soon after its process starts (and when the service dies), posts one notification that opens Settings > Accessibility > Buddy in one tap, and clears it once bound; plus a log breadcrumb (onServiceConnected/onUnbind/onDestroy with time and exit reason) so future cases leave evidence. Research notes: research-notes.md. Must not duplicate upstream's message mechanism; reuse it where possible.

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] Enabled-but-unbound is detected within ~1 min of process start and shows one notification; tapping it opens Settings > Accessibility (Buddy one more tap).
- [ ] Truly disabled (not in Enabled) shows the same notification with the right text.
- [ ] The notification clears itself once the service connects; no repeats while it's pending.
- [ ] Connect/unbind/destroy events with timestamps (and last exit reason) are logged to a file agents can read.
- [ ] CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->

2026-11-18 (orchestrator, on Nicholas's standing "decide the small things"): Android doesn't let an app open its own accessibility detail page, so the notification opens Settings > Accessibility (installed apps list) and Buddy is one more tap. Accepted.
