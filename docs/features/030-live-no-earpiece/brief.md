---
type: brief
feature: {{SLUG}}
created: {{DATE}}
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: {{SLUG}}

## Ask (verbatim, never edit)
{{ASK}}

## Goal
A phone live voice call must never play on the earpiece; Nicholas has asked repeatedly. Today (2.3.14-fork.1) a call with no watch in range played on the earpiece. Cause: LiveWatch.Call.noEarpiece(saved) (LiveWatch.java:133) is true only when the setting is Watch, or Auto AND the watch took the call; AudioRoute.reconcile (AudioRoute.java:286) then drop()s to the earpiece. Also a saved specific device that isn't connected (his: '7:LS2 Freecom') falls to drop() = earpiece. Fix, smallest: while a phone live call is on, the route is always live()'s (connected headset, else speaker) unless the saved device is connected and chosen. Live log line 'watch: none found: phone' then speaker.

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] Live call, Auto, no watch, no headset: speaker.
- [ ] Live call, saved device not connected: speaker (or a connected headset), never earpiece.
- [ ] Saved device connected: that device. Tests; CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
