# Plan: 029-local-timezone

## Goal
Agents get the date wrong every evening because Buddy's Linux runs on UTC: `Linux.java` starts every process with
`env -i` and no `TZ`, and Alpine has no tzdata (diagnosis.md). Fix it generically: Buddy passes the phone's own zone
(`TimeZone.getDefault()`) to every Linux process, so it follows travel and DST. Keep it small.
Done when: (1) in Buddy's Linux, `date`, python and node show the phone's local time and date; (2) it follows the
phone's zone, DST zones included, with a unit test for the TZ string; (3) CI is green.

Non-goals: changing processes that are already running (they keep their env until restarted); updating notes
outside this repo that say "the Linux clock is UTC" (vault `_meta/L1.md`, `architecture/buddy-phone.md`, wa-sync
`WATCH.md`); handling Android's custom `GMT+hh:mm` zone ids; a TZ for programs that clear their own env; zones
other than the phone's own in `/usr/share/zoneinfo` (an agent that needs them can `apk add tzdata`).

## Design
**`TZ=<the phone's IANA zone id>` on every Linux process, plus that zone's file copied from Android's own tz
database into the rootfs, so C programs can read it too.** No apk, no network.

Why the id, not a POSIX rule (planner probes in this phone's Linux, 2026-11-19, ~16:10 UTC):

| TZ value | `date`, python (musl) | node | Claude Code's injected date (bun) |
|---|---|---|---|
| POSIX `<+14>-14` | right | fixed offset only | **UTC: 2026-11-19** (should be 11-20) |
| POSIX `<+08>-8`, `<+07>-7<+08>,M10.1.0,M4.1.0/3`, `AEST-10AEDT,...` | right, DST too | DST ignored unless ICU knows the abbreviation (PST/PDT, CET/CEST); `AEST` gave +09 | **UTC** for all |
| IANA `Pacific/Kiritimati` | UTC without a zone file | right | **right: 2026-11-20** |
| IANA `America/Los_Angeles` | UTC without a zone file | right, DST too | not probed (same id path as Kiritimati) |

(Claude Code was run with `--settings '{"env":{"TZ":...}}'`, because `~/.claude/settings.json` sets `TZ=MST7`.)
Claude Code's injected "today" is the actual bug, and bun takes only ids its ICU knows. So the id wins, and musl
needs the zone's file at `/usr/share/zoneinfo/<id>`.

Where the zone file comes from: Android's tz database, `/apex/com.android.tzdata/etc/tz/tzdata` (world-readable;
there on every Android 10+, and Buddy's minSdk is 30; every app's libc reads it). Checked on this phone
(2026-11-19, `phone_shell`, `tzdata2026e`, 411,893 bytes). It is bionic's packed format:
- header, 24 bytes: 12-byte version (`tzdata2026e\0`), then big-endian int32 `index_offset` (24), `data_offset`
  (31068), `zonetab_offset`;
- index entries, 52 bytes each from `index_offset` to `data_offset`: 40-byte NUL-padded name, int32 `start`
  (relative to `data_offset`), int32 `length`, int32 unused;
- each entry is a complete TZif2 file with its POSIX footer (`America/Dawson_Creek`: 683 bytes, ends `\nMST7\n`;
  `Europe/Berlin`: 2568 bytes, ends `\nCET-1CEST,M3.5.0,M10.5.0/3\n`).
musl reads these entries as they are: `America/Dawson_Creek` gave `date` and python `MST -0700`; `Antarctica/Troll`
(1878 bytes, sha256 equal to the phone's) gave `+0200` in July 2026, `+0000` in January 2026 and `+0200` in July
2100 (the footer rule), for `date` and python alike. At 2026-11-20 02:00Z, `date`, python and node under an
`env -i` list with the zone all printed `2026-11-19 19:00 -0700`.

Pieces, all in `Linux.java` (the one launcher for chats, Codex, terminals, sshd, the browser helper, `Linux.exec`):
1. `static List<String> env(TimeZone zone)`: today's `env -i` entries plus `"TZ=" + zone.getID()`.
2. `static boolean copyZone(File tzdata, File root, String id) throws IOException`. It is plain Java
   (`RandomAccessFile`, plus `java.nio.file.Files`, which `Backup` and `Mirrors` already use). It checks the
   `tzdata` magic, reads the header and the whole index in one read, and finds `id`. If `id` isn't in the index, or
   the magic is wrong, it returns false and writes nothing, so only names from Android's index become paths.
   It then writes the entry to `<root>/usr/share/zoneinfo/<id>` and never follows a link in the guest tree. Buddy
   keeps the guest's symlinks (`untar`), and agents can add more:
   - Every directory from `root` down (`usr`, `share`, `zoneinfo`, then the id's own, e.g. `America`) must be a
     real directory (`Files.isDirectory(p, NOFOLLOW_LINKS)`). If it is missing, it is made with
     `Files.createDirectory`, which runs mkdir and fails instead of following a link. If a link or a file is there,
     it throws `IOException` and writes nothing.
   - The bytes go to `<id>.tmp`. Any existing one is removed first (`Files.deleteIfExists` unlinks a link, never
     its target), then the file is opened with `CREATE_NEW` (O_EXCL, which fails instead of following a link).
   - `Files.move(tmp, target, ATOMIC_MOVE)` runs rename(2). It replaces whatever is at the target, a link
     included, without following it.
   Out of scope: a guest that deliberately swaps a checked directory for a link between the check and the write.
   Guest code already runs as Buddy's own uid.
3. `command()`: `TimeZone tz = TimeZone.getDefault(); zone(root(c), tz.getID()); a.addAll(env(tz));`. Here
   `private static synchronized void zone(File root, String id)` returns at once when `id` equals the zone copied
   earlier in this app process and the target is still a regular file (`NOFOLLOW_LINKS`). Otherwise it calls
   `copyZone` and remembers `id`. An `IOException` is logged and the launch goes on (TZ is still passed: node and
   Claude Code are right, musl falls back to UTC as today).
   So the file is there before the first process starts (no network, no background task), a new zone after travel
   is copied on the next launch, and each app start rewrites it once (Android tz updates; an empty or partial file;
   a reinstalled rootfs). The work is one ~31 KB index read and a 1-3 KB write, once per app process; terminals
   already copy the helper files on the UI thread there (`TermSession.launch` → `Helper.sync`).

Alternatives considered:
- POSIX string from `ZoneRules` (brief option 1): rejected, Claude Code (bun) ignores it (probe above).
- POSIX string + dangling `/etc/localtime` symlink naming the id, for ICU: rejected, node still ignored it (probe).
- POSIX string when tzdata is missing, id when present: rejected, it breaks Claude Code exactly when tzdata is missing.
- Java writes a TZif file generated from `ZoneRules`: rejected, ~60 lines; Android already ships the compiled file.
- Alpine's `tzdata` via `apk add` (round 0 of this plan): rejected after review. It needs the network, so the first
  command after an upgrade (or offline) is still UTC, and its apk transaction races the browser installer's (F1,
  F2). Fixing that takes synchronous installs, retries and lock ordering: more code than copying one local file.
- Keep the file in Buddy's own storage and bind it onto `/usr/share/zoneinfo/<id>` with proot `-b` (review round 2):
  rejected. proot resolves a bound path only while its parent directories exist on disk (termux/proot
  `src/path/canon.c:136-185`). It creates missing parents as placeholders when it starts and removes them when it
  exits (`src/path/glue.c`, `build_glue`). So the first chat to end would put every other running process back on
  UTC.

## Steps
1. `app/src/main/java/app/buddy/assistant/Linux.java`: add `static List<String> env(TimeZone zone)` returning
   `HOME=/root, LANG=C.UTF-8, TZ=<zone.getID()>, TERM=xterm-256color, COLORTERM=truecolor, PATH=...` (today's
   entries, same order, `TZ` added); `command()` does `a.add("/usr/bin/env"); a.add("-i"); a.addAll(env(TimeZone.getDefault()))`.
   One comment line: an IANA id, not a POSIX rule, because Claude Code's runtime only takes ids.
   Test in the same commit: `app/src/test/java/app/buddy/assistant/LinuxTimeZoneTest.java`, the `env` cases.
2. `Linux.java`: add `copyZone(File tzdata, File root, String id)` with the link-safe write, the
   `zone(File, String)` guard with its one static field, and the call in `command()` before `env`. Add the `tzdata`
   path as a constant with a one-line comment (Android's tz database, one TZif file per zone, read by musl from
   `/usr/share/zoneinfo`). About 45 lines.
   Tests in the same commit: the `copyZone` cases in `LinuxTimeZoneTest` (see Test plan).

After merge and `update_buddy install=true` (orchestrator, not the Coder):
3. With Nicholas's OK, remove the interim `"TZ": "MST7"` from `env` in Linux's `/root/.claude/settings.json`.
   Claude Code copies settings env into its children, so it would pin every Bash tool to fixed MST and hide whether
   the fix works. Same temp-file-and-rename as `Account.logout` (Account.java:660), only when the value is `MST7`,
   and nothing printed but a yes/no:
   `f=/root/.claude/settings.json; umask 077; jq -e '.env.TZ == "MST7"' $f >/dev/null && jq 'del(.env.TZ) | if (.env // {}) == {} then del(.env) else . end' $f > $f.new && mv $f.new $f; jq -e '(.env // {}) | has("TZ") | not' $f >/dev/null && echo TZ-override-gone`
   Then open a new chat for the checks below.
4. Tell Nicholas the vault/wa-sync notes that say "the Linux clock is UTC: convert" are now wrong (an agent following
   them would subtract 7 h twice); updating them is outside this repo.

## Test plan
- **TZ string (done-when 2, unit):** `LinuxTimeZoneTest` (JVM, plain JUnit):
  - `env("Etc/GMT+7")` contains exactly one `TZ=` entry, `TZ=Etc/GMT+7`.
  - `zoneId(zone, instantMillis)` gives the fixed-offset id for the offset at that instant (orchestrator's design
    change, decisions.md): Dawson_Creek `Etc/GMT+7`, Berlin in winter `Etc/GMT-1`, Los_Angeles `Etc/GMT+8` in
    November and `Etc/GMT+7` in July (DST follows the instant), Kiritimati `Etc/GMT-14`, UTC `Etc/GMT`; a
    half-hour zone (`Asia/Kolkata`) keeps its region id.
  - The other entries are unchanged (`HOME=/root`, `LANG=C.UTF-8`, the `PATH=` line), guarding the refactor.
- **Zone file copy (unit, same class, `TemporaryFolder`):** the test writes a small file in the bionic layout above
  (`tzdata2026e\0` header, two index entries, payloads at different `start`s):
  - On an empty `root`, `copyZone(f, root, "America/Dawson_Creek")` returns true, creates
    `root/usr/share/zoneinfo/America/`, and `.../America/Dawson_Creek` holds exactly the first payload (first
    launch). The second id gives exactly the second payload, which proves `start` is relative to `data_offset`.
  - An id not in the index (`GMT+05:30`) returns false and creates nothing, not even directories.
  - A target that already exists empty is replaced by the full payload, and no `.tmp` is left (F4).
  - A file without the `tzdata` magic returns false.
  - Links (review round 2, F2), with an unrelated file `outside/x` of known bytes:
    - `root/usr/share/zoneinfo/America` is a symlink to `outside/`: `IOException`, and `outside/` holds only `x`,
      unchanged.
    - The target and a stale `Dawson_Creek.tmp` are both symlinks to `outside/x`: the copy returns true, `x` is
      unchanged, the target is now a regular file (`NOFOLLOW_LINKS`) with the payload, and no `.tmp` is left.
- **Before merge, in this phone's Alpine (Verifier; same rootfs, musl, python3 and node as the app's), running the
  branch's own `copyZone` and `env` on Android's real database:**
  1. Real database: start a python listener on `127.0.0.1:8029` in Linux that writes what it receives to a scratch
     file and stops after the size `wc -c` reports (411,893 today). Then make one `phone_shell` call:
     `T=/apex/com.android.tzdata/etc/tz/tzdata; wc -c < $T; sha256sum $T; nc 127.0.0.1 8029 < $T`. The copy's
     sha256 must equal the phone's. The planner tried this on 2026-11-19 and the sha256 matched (`9525d4c7...`).
  2. Precondition `test ! -e /usr/share/zoneinfo`: if it exists, stop and report, and touch nothing. Build the
     branch's classes with the javac + JUnit harness as in 026 (`/tmp/buddy-build-026/check.sh` with `R` set to this
     worktree, or its javac lines). Use Java 17 (`/usr/lib/jvm/java-17-openjdk`), because the default `java` 21
     doesn't start here. Then compile and run a scratch driver in package `app.buddy.assistant`, outside the repo.
     For `America/Dawson_Creek` and `America/Vancouver` it calls `id = Linux.zoneId(TimeZone.getTimeZone(zone), t * 1000L)`
     for each test instant `t` of step 3 (the launcher's own choice; `t` is epoch seconds, `zoneId` takes milliseconds), then `Linux.copyZone(db, new File("/"), id)`, prints true,
     and prints `String.join(" ", Linux.env(id))` for that same `id`. `Antarctica/Troll` is the same, with its two
     instants (ids `Etc/GMT-2` and `Etc/GMT`). `/` here is the same tree Buddy passes as
     `root(c)`. The driver records each path it created (the zone file and every directory it made, found by
     checking existence before the copy). Then each `id`'s file must exist as a regular file whose bytes equal that
     id's payload in the phone's database: the first launch created them.
  3. Every `t` below is epoch seconds: pass `t` to `date -d @t` and python, and `t * 1000` to node's `new Date(...)`.
     Under `env -i` plus the exact list the driver printed for that `id`, print `%F %H:%M %z` for the same instant from
     `date -d @t`, `python3` (`time.localtime(t)`) and `node` (local getters plus `getTimezoneOffset`):
     - `America/Dawson_Creek` (id `Etc/GMT+7`), t=1795140000 (2026-11-20 02:00Z, when the UTC date is already
       tomorrow): all three print `2026-11-19 19:00 -0700`.
     - `America/Vancouver` (id `Etc/GMT+7`, BC's permanent UTC-7, which Node's own region rules lack), t=1795158000
       (2026-11-20 07:00Z, local midnight; stale PST rules would print the 19th at 23:00): all three print
       `2026-11-20 00:00 -0700`.
     - DST, `Antarctica/Troll`: at t=1783000000 all three print `2026-07-02 15:46 +0200`; at t=1768000000 all three
       print `2026-01-09 23:06 +0000`.
  4. Cleanup, only what the check created: for each path the driver recorded, unlink the file only if its bytes still
     equal the payload the check wrote and `apk info -W <path>` says no package owns it, then `rmdir` the directories
     the check created, deepest first, and only if empty. Anything else (a file another session installed during the
     check, an `apk`-owned file, a non-empty directory) is left intact and reported. Delete the scratch database copy.
     Never `rm -rf` under `/usr/share/zoneinfo`.
- **CI (done-when 3):** PR checks green; `testGithubDebugUnitTest` in `build.yml` runs the new tests.
- **On the phone, after install and step 3 (done-when 1; release smoke test), in a new chat:**
  - Before installing, `test ! -e /usr/share/zoneinfo` holds, so whatever is there afterwards came from the launcher.
  - `ls -l /usr/share/zoneinfo/Etc/GMT+7` shows a regular file (the launcher selects the fixed-offset id for Dawson
    Creek's UTC-7), and `echo $TZ` prints `Etc/GMT+7`.
  - `date`, `python3 -c 'import time;print(time.strftime("%F %T %Z"))'`, `node -e 'console.log(new Date().toString())'`
    all show the phone's local time and date (MST, same as the status bar).
  - Claude Code: the new transcript's `"type":"date"` attachment is the local date (after 17:00 local it must not
    jump to tomorrow).
  - Optional, with Nicholas's OK: set the phone's zone by hand to a DST zone (e.g. Europe/Berlin), open a new
    terminal: `echo $TZ` prints `Etc/GMT-1` in winter (`Etc/GMT-2` in summer), `date` shows Berlin's local time,
    and `/usr/share/zoneinfo/$TZ` exists; set it back.

## Risks
- **Android's tz database unreadable or in another format** (a ROM oddity): `copyZone` throws or finds nothing,
  the launch goes on, `date`/python show UTC as today, node and Claude Code are still right; logged. The packed
  format has been bionic's since Android 4.x and is the one on this phone.
- **Only the phone's zone is installed:** `TZ=Europe/Paris date` in an agent's shell stays UTC unless it runs
  `apk add tzdata`. If it does, Buddy still rewrites the phone's zone file once per app start (same or newer data);
  harmless, though `apk audit` would list it.
- **A link or a file in the zoneinfo path** (an agent's `ln -s /usr/share/zoneinfo ...`): Buddy writes nothing and
  logs it. `date` and python show UTC as today, while node and Claude Code are still right.
- **Zone ids Android lacks:** Android's custom `GMT+05:30` form isn't in the index, so no file, and musl reads that
  TZ as a POSIX rule with the wrong sign. Rare on a phone whose zone list is IANA; a non-goal.
- **Running processes** keep their old TZ until restarted (a chat's next run, a new terminal); a DST change
  takes effect only in processes started after it, since each launch fixes the offset (accepted, decisions.md).
- **The settings.json override** (step 3): left in place, Bash tools under Claude Code stay on fixed MST7 and travel
  won't follow.

## Open questions
None blocking. Step 3 edits Nicholas's Claude Code settings in Linux, so the orchestrator asks him first.
