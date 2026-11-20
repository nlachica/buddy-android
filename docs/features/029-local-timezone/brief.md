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
Agents get dates wrong after 17:00 local because Buddy's Linux runs on UTC (Linux.java:89 env -i drops TZ; Alpine has no tzdata). See diagnosis.md. Fix generically so it follows the phone's zone (travel, DST): Buddy passes TZ from the phone's TimeZone.getDefault() to every Linux process; make it work without tzdata (compute a POSIX TZ string like MST7 / PST8PDT,M3.2.0,M11.1.0 from the Java zone) or install tzdata in rootfs setup, whichever is smaller and robust. Keep it small.

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] In Buddy's Linux, date, python and node show the phone's local time and date.
- [ ] Follows the phone's zone, incl. DST zones; unit test for the TZ string.
- [ ] CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->

Lesson (2026-11-19): TZ=MST7 crashed a node script that passes process.env.TZ to Intl timeZone (RangeError). Prefer an IANA TZ id (install tzdata if needed so date/python honor it); don't ship a POSIX-only TZ.
