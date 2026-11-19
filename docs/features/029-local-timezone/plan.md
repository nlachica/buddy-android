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
2. `static boolean copyZone(File tzdata, File zoneinfo, String id) throws IOException`: plain Java
   (`RandomAccessFile`): checks the `tzdata` magic, reads the header and the whole index in one read, finds `id`,
   writes that entry's bytes to `<zoneinfo>/<id>.tmp` (parent dirs made) and renames it over `<zoneinfo>/<id>`.
   Returns false, writing nothing, when `id` isn't in the index. Only names found in Android's index become paths.
3. `command()`: `TimeZone tz = TimeZone.getDefault(); zone(root(c), tz.getID()); a.addAll(env(tz));` where
   `private static synchronized void zone(File root, String id)` returns at once when `id` equals the zone copied
   earlier in this app process and the file exists; otherwise it calls `copyZone` and remembers `id`; an
   `IOException` is logged and the launch goes on (TZ is still passed: node and Claude Code are right, musl falls
   back to UTC as today).
   So the file is there before the first process starts (no network, no background task), a new zone after travel
   is copied on the next launch, and each app start rewrites it once (Android tz updates; an empty or partial file;
   a reinstalled rootfs). The work is one ~31 KB index read and a 1-3 KB write, once per app process; terminals
   already copy the helper files on the UI thread there (`TermSession.launch` → `Helper.sync`).

Alternatives considered:
- POSIX string from `ZoneRules` (brief option 1): rejected, Claude Code (bun) ignores it (probe above).
- POSIX string + dangling `/etc/localtime` symlink naming the id, for ICU: rejected, node still ignored it (probe).
- POSIX string when tzdata is missing, id when present: rejected, it breaks Claude Code exactly when tzdata is missing.
- Java writes a TZif file generated from `ZoneRules`: rejected, ~60 lines; Android already ships the compiled file.
- Alpine's `tzdata` via `apk add` (round 0 of this plan): rejected after review: it needs the network, so the first
  command after an upgrade (or offline) is still UTC, and its apk transaction races the browser installer's (F1,
  F2). Fixing that takes synchronous installs, retries and lock ordering: more code than copying one local file.

## Steps
1. `app/src/main/java/app/buddy/assistant/Linux.java`: add `static List<String> env(TimeZone zone)` returning
   `HOME=/root, LANG=C.UTF-8, TZ=<zone.getID()>, TERM=xterm-256color, COLORTERM=truecolor, PATH=...` (today's
   entries, same order, `TZ` added); `command()` does `a.add("/usr/bin/env"); a.add("-i"); a.addAll(env(TimeZone.getDefault()))`.
   One comment line: an IANA id, not a POSIX rule, because Claude Code's runtime only takes ids.
   Test in the same commit: `app/src/test/java/app/buddy/assistant/LinuxTimeZoneTest.java`, the `env` cases.
2. `Linux.java`: add `copyZone(File, File, String)`, the `zone(File, String)` guard with its one static field, and
   the call in `command()` before `env`; the `tzdata` path as a constant with a one-line comment (Android's tz
   database, one TZif file per zone, read by musl from `/usr/share/zoneinfo`). About 30 lines.
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
  - `env(TimeZone.getTimeZone("America/Dawson_Creek"))` contains exactly one `TZ=` entry, `TZ=America/Dawson_Creek`.
  - DST zones: `Europe/Berlin` and `America/Los_Angeles` give `TZ=Europe/Berlin` / `TZ=America/Los_Angeles`
    (the id, never an offset like `GMT-08:00`, so DST stays with the zone).
  - The other entries are unchanged (`HOME=/root`, `LANG=C.UTF-8`, the `PATH=` line), guarding the refactor.
- **Zone file copy (unit, same class, `TemporaryFolder`):** the test writes a small file in the bionic layout above
  (`tzdata2026e\0` header, two index entries, payloads at different `start`s):
  - `copyZone(f, dir, "America/Dawson_Creek")` returns true and `dir/America/Dawson_Creek` holds exactly the first
    payload; the second id gives exactly the second payload (proves `start` is relative to `data_offset`).
  - An id not in the index (`GMT+05:30`) returns false and creates nothing.
  - A target that already exists empty (and an empty `America/` dir) is replaced by the full payload; no `.tmp`
    is left (F4).
  - A file without the `tzdata` magic returns false.
- **Before merge, in this phone's Alpine (Verifier; same rootfs, musl, python3 and node as the app's):**
  1. Take the real `America/Dawson_Creek` and `Antarctica/Troll` entries from Android's database with `phone_shell`,
     using the same layout `copyZone` parses (header int32s at 12 and 16, 52-byte entries, `start` relative to
     `data_offset`): print `sha256sum` and line-wrapped `base64` of each, decode into `/usr/share/zoneinfo/<id>`
     here, and check the sha256 matches (redo on a mismatch: hand-copied base64 can break).
  2. Under the exact list from `Linux.env` (`env -i HOME=/root LANG=C.UTF-8 TZ=<id> TERM=... PATH=...`), print
     `%F %H:%M %z` for the same instant from `date -d @t`, `python3` (`time.localtime(t)`) and `node` (local
     getters plus `getTimezoneOffset`):
     - `TZ=America/Dawson_Creek`, t=1795140000 (2026-11-20 02:00Z, the UTC date is already tomorrow): all three
       `2026-11-19 19:00 -0700`.
     - DST, `TZ=Antarctica/Troll`: t=1783000000 all three `2026-07-02 15:46 +0200`; t=1768000000 all three
       `2026-01-09 23:06 +0000`.
  3. Delete the Troll file; the Dawson Creek one may stay (the app writes the same bytes).
- **CI (done-when 3):** PR checks green; `testGithubDebugUnitTest` in `build.yml` runs the new tests.
- **On the phone, after install and step 3 (done-when 1; release smoke test), in a new chat:**
  - `ls -l /usr/share/zoneinfo/America/Dawson_Creek` shows 683 bytes; `echo $TZ` prints `America/Dawson_Creek`.
  - `date`, `python3 -c 'import time;print(time.strftime("%F %T %Z"))'`, `node -e 'console.log(new Date().toString())'`
    all show the phone's local time and date (MST, same as the status bar).
  - Claude Code: the new transcript's `"type":"date"` attachment is the local date (after 17:00 local it must not
    jump to tomorrow).
  - Optional, with Nicholas's OK: set the phone's zone by hand to a DST zone (e.g. Europe/Berlin), open a new
    terminal: `echo $TZ; date` shows that zone and the file `/usr/share/zoneinfo/Europe/Berlin` exists; set it back.

## Risks
- **Android's tz database unreadable or in another format** (a ROM oddity): `copyZone` throws or finds nothing,
  the launch goes on, `date`/python show UTC as today, node and Claude Code are still right; logged. The packed
  format has been bionic's since Android 4.x and is the one on this phone.
- **Only the phone's zone is installed:** `TZ=Europe/Paris date` in an agent's shell stays UTC unless it runs
  `apk add tzdata`. If it does, Buddy still rewrites the phone's zone file once per app start (same or newer data);
  harmless, though `apk audit` would list it.
- **Zone ids Android lacks:** Android's custom `GMT+05:30` form isn't in the index, so no file, and musl reads that
  TZ as a POSIX rule with the wrong sign. Rare on a phone whose zone list is IANA; a non-goal.
- **Running processes** keep their old TZ until restarted (a chat's next run, a new terminal); DST changes need
  no restart, since the file carries the rules.
- **The settings.json override** (step 3): left in place, Bash tools under Claude Code stay on fixed MST7 and travel
  won't follow.

## Open questions
None blocking. Step 3 edits Nicholas's Claude Code settings in Linux, so the orchestrator asks him first.
