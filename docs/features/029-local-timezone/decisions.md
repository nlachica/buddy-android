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

## 2026-11-19 - The zone file is written into the guest tree without following links (round 2)
- Options: (a) keep the bytes in Buddy's own storage and bind them onto `/usr/share/zoneinfo/<id>` with proot `-b`;
  (b) write into the guest tree, checking each directory with `NOFOLLOW_LINKS`, creating the temp file with
  `CREATE_NEW` (O_EXCL) after `deleteIfExists`, and renaming over the target.
- Choice: (b). In (a), proot resolves a bound path only while its parent directories exist on disk (termux/proot
  52d65a4, `src/path/canon.c:136-185`). It creates missing parents as placeholders when it starts and removes them
  when it exits (`src/path/glue.c`, `build_glue`). The first of several concurrent proots (chat, terminal, sshd) to
  exit would put the others back on UTC, and making the directories from Buddy needs the same walk as (b), plus a
  bind. (b) is stdlib `java.nio.file`, about 15 more lines, and unit-testable with real symlinks. It does not cover
  a guest that deliberately races the check, since guest code already runs as Buddy's own uid.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - The pre-merge runtime check runs in the live rootfs from an absent zoneinfo, not a disposable rootfs
- Options: a disposable Alpine rootfs (review round 2, F1); the live rootfs, gated on `/usr/share/zoneinfo` not
  existing and removing it afterwards.
- Choice: the live rootfs. A second rootfs needs Buddy's proot, which `phone_shell` refuses to run (BLOCKED), and
  this Linux has no other proot. The gate and the exact cleanup still show that the branch's `copyZone` creates the
  file from Android's real database (brought in over localhost, sha256-checked). They change no file that existed
  before, and they leave the post-install check unable to pass without the launcher.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - Orchestrator: plan accepted after round 3, with one design change for F1
- F1 is real: Node's bundled ICU has stale rules for America/Vancouver (BC's 2026 permanent UTC-7), so an IANA region id gives Node the wrong hour/date. Change: pass a fixed-offset IANA id computed from the phone's CURRENT offset at process start, `Etc/GMT+7` for UTC-7 (sign inverted per IANA; half-hour offsets fall back to the region id). Node ICU, musl (copy that Etc file from Android's tzdata) and Python all agree, nothing depends on stale rule tables, and DST/travel still follow the phone because each new process re-reads the offset. Long-lived processes across a DST change are an accepted edge.
- F2: verification only creates and removes its own files; never deletes apk-owned or unexpected entries.
- Also remove the interim TZ=MST7 from ~/.claude/settings.json after install (orchestrator does this).

## 2026-11-20 - Coder: env and zoneId take a String / (TimeZone, now), and the Etc range is -12..+14
- The orchestrator's Etc/GMT design changes the plan's `env(TimeZone)` to `env(String zoneId)`, with `zoneId(TimeZone, long now)`
  computing the id (testable with a fixed instant, no clock in the test). Valid IANA ids are Etc/GMT+12 (UTC-12) to Etc/GMT-14
  (UTC+14); outside that, and for half-hour offsets, the region id is kept.
- If the id isn't in Android's index, `copyZone` returns false and `zone()` retries on the next launch (one 31 KB index read);
  not cached, because a missing file is the case worth retrying.
- By: Coder (claude-sonnet-5-5)

## 2026-11-20 - Orchestrator: code accepted after round 3
- F1 (private detail in an old branch commit): resolved by squash-merging (main never gets 29f174e) and deleting the branch right after. Remaining checks are the on-phone smoke test after install.
