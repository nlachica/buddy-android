# Decisions: {{SLUG}}
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-19 - TZ carries the phone's IANA zone id, and tzdata is installed (not a POSIX rule string)
- Options: (a) POSIX string computed from the Java zone's rules (brief option 1, diagnosis's `MST7`); (b) IANA id +
  Alpine `tzdata` (brief option 2); (c) POSIX string + dangling `/etc/localtime` symlink naming the id for ICU;
  (d) POSIX when tzdata is missing, id when present; (e) IANA id + a Java-written TZif file with a POSIX footer in
  `/etc/zoneinfo/<id>`.
- Choice: (b) because probes in this Linux showed Claude Code's runtime (bun) ignores every POSIX TZ string
  (`<+14>-14` still gave the UTC date 2026-11-19 at 16:10Z) but honors an id (`Pacific/Kiritimati` gave 11-20);
  node drops POSIX DST rules unless ICU knows the abbreviation; (c) did not change node's zone; (d) breaks Claude
  Code exactly when tzdata is missing; (e) works offline but is ~60 lines of TZif/POSIX code against ~10. Without
  tzdata, (b) leaves `date`/python on UTC as today while Claude Code and node are already right.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - tzdata arrives through one check in Helper.sync, not in the setup apk list
- Options: add `tzdata` to Account's setup `apk add` list (new installs only) plus a migration for old installs;
  or one check in `Helper.sync` (once per app process: if `/usr/share/zoneinfo` is missing, `apk add -U -q tzdata`
  in the background under the `Account.class` apk lock).
- Choice: the single Helper.sync check, because it covers new installs (it waits for setup's lock, then installs)
  and existing ones (first app start after the update), and retries on the next start when offline.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - The TZ string is unit-tested through Linux.env(TimeZone)
- Options: test `Linux.command` (needs a Context, which JVM tests don't have); extract the `env -i` list into
  `static List<String> env(TimeZone)`.
- Choice: extract `env(TimeZone)`, so the test checks the exact list that survives `env -i`, for fixed and DST zones.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - The zone file is copied from Android's tz database, not installed with Alpine's tzdata (round 1)
- Supersedes "tzdata arrives through one check in Helper.sync". `TZ` still carries the IANA id.
- Options: keep `apk add tzdata` and fix the review's findings (in the setup list, plus a synchronous install on
  upgrade with retries, the browser installer sequenced after it, a per-file guard); or copy the phone's zone
  entry (a complete TZif2 file) from `/apex/com.android.tzdata/etc/tz/tzdata` into `/usr/share/zoneinfo/<id>` in
  `Linux.command`, before each launch, once per app process and zone.
- Choice: the copy, because it needs no network or apk, so the first command after an upgrade already has the
  zone (F1), nothing competes with the browser installer's apk (F2), and the guard is the exact file (F4); about
  30 lines against more than that for the apk fixes. Checked on this phone: the database is readable, the layout is
  as planned, and musl `date`/python read the entries right, DST included (plan.md, Design).
- By: Planner (claude-opus-5-5)
