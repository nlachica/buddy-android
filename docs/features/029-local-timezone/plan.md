# Plan: 029-local-timezone

## Goal
Agents get the date wrong every evening because Buddy's Linux runs on UTC: `Linux.java` starts every process with
`env -i` and no `TZ`, and Alpine has no tzdata (diagnosis.md). Fix it generically: Buddy passes the phone's own zone
(`TimeZone.getDefault()`) to every Linux process, so it follows travel and DST. Keep it small.
Done when: (1) in Buddy's Linux, `date`, python and node show the phone's local time and date; (2) it follows the
phone's zone, DST zones included, with a unit test for the TZ string; (3) CI is green.

Non-goals: changing processes that are already running (they keep their env until restarted); updating notes
outside this repo that say "the Linux clock is UTC" (vault `_meta/L1.md`, `architecture/buddy-phone.md`, wa-sync
`WATCH.md`); handling Android's custom `GMT+hh:mm` zone ids; a TZ for programs that clear their own env.

## Design
**`TZ=<the phone's IANA zone id>` on every Linux process, plus Alpine's `tzdata` package so C programs can read it.**

The brief offered two routes; the planner probed both in this phone's Linux (2026-11-19, ~16:10 UTC):

| TZ value | `date`, python (musl) | node | Claude Code's injected date (bun) |
|---|---|---|---|
| POSIX `<+14>-14` | right | fixed offset only | **UTC: 2026-11-19** (should be 11-20) |
| POSIX `<+08>-8`, `<+07>-7<+08>,M10.1.0,M4.1.0/3`, `AEST-10AEDT,...` | right, DST too | DST ignored unless ICU knows the abbreviation (PST/PDT, CET/CEST); `AEST` gave +09 | **UTC** for all |
| IANA `Pacific/Kiritimati` | UTC without tzdata | right | **right: 2026-11-20** |
| IANA `America/Los_Angeles` | UTC without tzdata | right, DST too | not probed (same id path as Kiritimati) |

(Claude Code was run with `--settings '{"env":{"TZ":...}}'`, because `~/.claude/settings.json` sets `TZ=MST7`.)

So a POSIX string, the brief's first option and the diagnosis's `MST7`, fixes `date` and python but **not Claude
Code**, whose injected "today" is the actual bug: bun accepts only zone ids its ICU knows. An IANA id fixes Claude
Code and node at once, and with tzdata installed musl (`date`, python, busybox, everything C) reads it too, DST
included. One variable can't carry both forms, so the id wins, and tzdata comes along.

Pieces:
1. `Linux.command` builds the `env -i` list through a new `static List<String> env(TimeZone zone)` that adds
   `"TZ=" + zone.getID()`; `command` passes `TimeZone.getDefault()`. Computed on each launch, so a new process
   after travel gets the new zone (Android resets the app's default zone when the phone's zone changes; the
   `device_status` tool already relies on that). This is the one launcher for chats, Codex, terminals, sshd
   (OpenSSH hands `TZ` on to its sessions), the browser helper and `Linux.exec`.
2. `Helper.sync` (its once-per-app-process path) checks `usr/share/zoneinfo` in the rootfs with a Java `File`
   check; if missing it runs `apk add -U -q tzdata` on Helper's pool thread, inside `synchronized (Account.class)`
   (the existing "one apk at a time" lock, so it waits for a first-time setup's own `apk add` instead of racing it).
   One mechanism covers new installs (right after setup) and existing ones (first app start after the update).
   Offline: it tries again on the next app start; meanwhile `date`/python stay on UTC exactly as today, while
   Claude Code and node are already right.

Alternatives considered:
- POSIX string from `ZoneRules` (brief option 1): rejected, Claude Code (bun) ignores it (probe above).
- POSIX string + dangling `/etc/localtime` symlink naming the id, for ICU: rejected, node still ignored it (probe).
- POSIX string when tzdata is missing, id when present: rejected, it breaks Claude Code exactly when tzdata is missing.
- Java writes a TZif file with a POSIX footer into `/etc/zoneinfo/<id>` (offline): works in theory, ~60 lines of
  clever code against ~10; not worth it while `apk add tzdata` (441 KiB, 2026f in v3.24/main) is one line.
- `tzdata` in Account's setup `apk add` list: redundant with the `Helper.sync` check, which also covers old installs.

## Steps
1. `app/src/main/java/app/buddy/assistant/Linux.java`: add `static List<String> env(TimeZone zone)` returning
   `HOME=/root, LANG=C.UTF-8, TZ=<zone.getID()>, TERM=xterm-256color, COLORTERM=truecolor, PATH=...` (today's
   entries, same order, `TZ` added); `command()` does `a.add("/usr/bin/env"); a.add("-i"); a.addAll(env(TimeZone.getDefault()))`.
   One comment line: an IANA id, not a POSIX rule, because Claude Code's runtime only takes ids; C programs read it
   from tzdata (Helper.sync).
   Test in the same commit: `app/src/test/java/app/buddy/assistant/LinuxEnvTest.java` (see Test plan).
2. `app/src/main/java/app/buddy/assistant/Helper.java`: in `sync()`, after the files are copied (before
   `syncedFor = ...`), if `!new File(root, "usr/share/zoneinfo").isDirectory()`, `pool.execute` a task that takes
   `synchronized (Account.class)` and runs `Linux.exec(app, "[ -d /usr/share/zoneinfo ] || apk add -U -q tzdata", 300)`,
   ignoring failure (next app start retries). Use the application context. About 6 lines with a one-line comment.

After merge and `update_buddy install=true` (orchestrator, not the Coder):
3. With Nicholas's OK, remove the interim `"TZ": "MST7"` from `env` in Linux's `/root/.claude/settings.json`
   (`jq 'del(.env.TZ)'`). Claude Code copies settings env into its children, so it would pin every Bash tool to
   fixed MST and hide whether the fix works. Then open a new chat for the checks below.
4. Tell Nicholas the vault/wa-sync notes that say "the Linux clock is UTC: convert" are now wrong (an agent following
   them would subtract 7 h twice); updating them is outside this repo.

## Test plan
- **TZ string (done-when 2, unit):** `LinuxEnvTest` (JVM, plain JUnit):
  - `env(TimeZone.getTimeZone("America/Dawson_Creek"))` contains exactly one `TZ=` entry, `TZ=America/Dawson_Creek`.
  - DST zones: `Europe/Berlin` and `America/Los_Angeles` give `TZ=Europe/Berlin` / `TZ=America/Los_Angeles`
    (the id, never an offset like `GMT-08:00`, so DST stays with the zone).
  - The other entries are unchanged (`HOME=/root`, `LANG=C.UTF-8`, the `PATH=` line), guarding the refactor.
- **CI (done-when 3):** PR checks green; `testGithubDebugUnitTest` in `build.yml` runs the new test.
- **On the phone, after install and step 3 (done-when 1), in a new chat:**
  - `apk info -e tzdata` prints `tzdata`; `echo $TZ` prints `America/Dawson_Creek`.
  - `date`, `python3 -c 'import time;print(time.strftime("%F %T %Z"))'`, `node -e 'console.log(new Date().toString())'`
    all show the phone's local time and date (MST, same as the status bar).
  - Claude Code: the new transcript's `"type":"date"` attachment is the local date (after 17:00 local it must not
    jump to tomorrow).
- **DST (done-when 2, device):** `TZ=Europe/Berlin date -d @1783000000` shows CEST and `@1768000000` shows CET; same
  pair with python (`time.localtime`) and node. Proves the value Buddy passes works in a DST zone for all three.
  Optional, with Nicholas's OK: set the phone's zone by hand to a DST zone, open a new terminal, `echo $TZ; date`
  shows that zone; set it back.

## Risks
- **First start offline:** tzdata isn't there yet, so `date`/python show UTC (as today) until a later app start
  with network; Claude Code and node are right meanwhile. Visible with `apk info -e tzdata`.
- **apk lock:** the one-time background `apk add` could make an agent's own `apk add` at that same second fail with
  "unable to lock database". Once per install, seconds long; setup itself is protected by the `Account.class` lock.
- **Zone ids Alpine lacks:** an id newer than Alpine's tzdata, or Android's custom `GMT+05:30` form, gives musl UTC
  (or, for the custom form, a wrong-sign POSIX reading). Rare on a phone whose zone list is IANA; not handled.
- **Running processes** keep their old TZ until restarted (a chat's next run, a new terminal); DST changes need
  no restart, since the id carries the rules.
- **The settings.json override** (step 3): left in place, Bash tools under Claude Code stay on fixed MST7 and travel
  won't follow.

## Open questions
None blocking. Step 3 edits Nicholas's Claude Code settings in Linux, so the orchestrator asks him first.
