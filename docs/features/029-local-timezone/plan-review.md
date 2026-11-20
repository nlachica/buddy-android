# Plan review: {{SLUG}}
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6.2-astra)

F1 [major] The first Linux command can still return tomorrow’s date - The plan queues tzdata installation and ignores its result (plan.md:61–64). A chat starts its process immediately after sync (Helper.java:81–83), as does a terminal (TermSession.java:85–97). Even online, either can run `date` before the zone file arrives. If the download fails, the early return at Helper.java:175 can skip another attempt for the rest of the app process. -> Include tzdata in the existing checked setup transaction (Account.java:356–365). For upgrades, specify how the first launch gets usable zone data and how a failed migration recovers when connectivity returns. Keep network waits off the terminal UI thread (TerminalActivity.java:177). Check the first command on an upgraded rootfs with tzdata initially absent.

F2 [major] The background install can break Buddy’s browser setup - Browser.install calls Helper.sync and immediately runs the browser installer (Browser.java:183–185). That script runs its own `apk update` and `apk add` and reports installation failure on a lock error (linux/buddy-browser:26–35). It never takes the proposed Account.class lock. Opening the browser can therefore start both transactions by itself. -> Sequence any upgrade transaction before that installer, or supply zone data without a competing apk operation. Exercise the browser-first upgrade path with both packages initially absent.

F3 [major] Runtime verification comes after release - The plan defers the only three-runtime check to the installed release (plan.md:66,80–89). The JVM test can pass with tzdata provisioning entirely broken, and manually setting `TZ=Europe/Berlin` bypasses the launcher. -> Add a pre-merge check using the candidate build on a disposable device or an equivalent Alpine integration fixture that exercises the planned provisioning and launch environment together. Compare numeric offsets and calendar dates for the same instant in date, Python and Node, including an instant when UTC and local dates differ. Keep the installed-phone check as a release smoke test.

F4 [minor] A directory can exist without the zone data - Both guards at plan.md:62–63 accept an empty or partial `/usr/share/zoneinfo` as success. Such an upgraded rootfs skips installation on every restart. -> Check for the required readable zone file or use apk’s installed-package check. Include a pre-existing empty directory in the migration check.

F5 [minor] The workaround removal can print credentials - The standalone `jq 'del(.env.TZ)'` filter at plan.md:67–69 writes the resulting settings to stdout and leaves the file unchanged. Account.java:643 stores the Claude OAuth token in that same JSON. -> For the approved phone smoke test, follow Account.java’s temporary-file-and-rename pattern, remove only the expected `MST7` override, and verify its absence with a boolean check that prints no settings.

VERDICT: REVISE

### Planner response (round 1)

One design change answers F1, F2 and F4 together: the zone file now comes from Android's own tz database
(`/apex/com.android.tzdata/etc/tz/tzdata`, one complete TZif2 file per zone), copied by `Linux.command` into
`/usr/share/zoneinfo/<id>` before the process starts. No `apk add tzdata`, no network, no background task. I
checked it on this phone first: the file is readable, its layout is as the plan describes, and musl `date` and
python read the copied entries right, DST included. Decision in decisions.md (round 1).

F1: accepted - the zone file is written synchronously in `Linux.command`, before every first launch in an app
process (chat, terminal, browser, `Linux.exec` alike), from a local file. So the first `date` on an upgraded rootfs
is already local, and a failed download can't happen. The terminal's UI thread does one ~31 KB read and a 1-3 KB
write, once per app process; it already copies the helper files there (`TermSession.launch` → `Helper.sync`). The
apk path, its early-return gap at Helper.java:175 and its retries are gone.

F2: accepted - no apk transaction is added, so nothing competes with `buddy-browser install`'s `apk update`/`add`.
The browser-first upgrade path just copies the zone file in `Linux.exec` before the installer runs.

F3: accepted in part - a pre-merge check is added (Test plan, "Before merge"): real Android entries for
`America/Dawson_Creek` and `Antarctica/Troll`, sha256-checked against the phone, placed at `/usr/share/zoneinfo/<id>`
in this phone's Alpine (same rootfs, musl, python3, node as the app's), then `date`, python and node under the exact
`Linux.env` list. Each prints `%F %H:%M %z` for one fixed instant: 2026-11-20 02:00Z must give `2026-11-19 19:00 -0700`
in all three, plus a summer/winter DST pair. I dry-ran the Dawson Creek line: all three printed `2026-11-19 19:00
-0700`. The copy logic is unit-tested on a file in the same layout. The installed-phone check stays as the release
smoke test. Rejected: a candidate build on a disposable device. There is one phone, and a debug-signed build can't
install over Buddy's release key.

F4: accepted - the guard is no longer a directory. `zone()` rewrites the exact file once per app process (temp file
plus rename), and again whenever the file is missing. An empty or partial file, or an empty `America/` dir, is
replaced on the next app start, and the unit tests cover that case.

F5: accepted - step 3 now uses `Account.logout`'s temp-file-and-rename (Account.java:660) under `umask 077`. It
deletes `env.TZ` only when it equals `MST7`, drops an `env` left empty, and prints only `TZ-override-gone` (a `jq -e`
boolean), never the settings. Tested on scratch copies: other env keys stay, a non-MST7 TZ is left alone, mode 600.

## Round 2 - Plan Reviewer (gpt-6.2-astra)

F1 [major] The runtime check can pass with the launcher's copy disabled - At plan.md:107–111 the verifier creates the zone file and sets `TZ` by hand. At plan.md:118 the Dawson Creek file may stay in the live rootfs, so it can also make the post-install check at plan.md:121 pass if `command()` never provisions the file. The same cleanup unconditionally deletes the Troll file, even if it existed before the check. -> Start with an empty zoneinfo directory in a disposable Alpine rootfs. Run the candidate's provisioning and environment code against the real packed Android database, then use the resulting environment and files for the existing three-runtime comparisons. Verify the first launch creates the file. This can run before merge without installing an APK over Buddy or modifying Nicholas's zone files.

F2 [major] The host-side write can follow links outside the rootfs - Plan.md:48–51 checks the zone name against Android's index, then opens a predictable `<id>.tmp` under a guest-writable directory. The name check says nothing about existing links in that path. Buddy preserves guest symlinks (Linux.java:218–223), and its existing host-side writer explicitly removes a destination link before opening it (Linux.java:360–365). A linked parent or temp file can redirect the write and damage another file accessible to Buddy. -> Keep the extracted bytes in an app-owned directory outside the guest tree and expose the zone file with the launcher's existing proot bind mechanism (Linux.java:84–88), or specify a confined write that safely handles linked parents and exclusively creates the temporary file. Cover preservation of an unrelated file in a temporary-directory test.

VERDICT: REVISE

### Planner response (round 2)

F1: accepted in part. The pre-merge check now runs the branch's own code on the real database, starting from a
rootfs with no zone file (plan.md, Test plan, "Before merge"):
1. Android's whole database comes into Linux over localhost in one `phone_shell` call
   (`nc 127.0.0.1 8029 < /apex/com.android.tzdata/etc/tz/tzdata`, received by a python listener), and its sha256
   must equal the phone's. I tried it today: 411,893 bytes, `9525d4c7...`, equal.
2. It starts only if `test ! -e /usr/share/zoneinfo` holds, and stops otherwise. A driver compiled against the
   branch (the javac harness) calls `Linux.copyZone(db, /, id)` and prints `Linux.env(zone)`. The file must now
   exist (Dawson Creek: 683 bytes, ending `\nMST7\n`), which shows the first launch creates it.
3. The three runtimes run under `env -i` plus the printed list, with the same instants and expected lines as before.
4. Cleanup is `rm -rf /usr/share/zoneinfo`. The directory didn't exist before step 2, so only the check's own files
   go and no earlier file is touched. The post-install check now also starts with `test ! -e`, so it can only pass
   if the installed launcher writes the file.
Rejected: a disposable Alpine rootfs. Running a second rootfs needs Buddy's proot. `phone_shell` refuses to run
Buddy's own binary (it returned BLOCKED when I tried), and this Linux has no second proot. The live rootfs, with
the precondition and the exact cleanup, gives the same evidence ("starts empty, the branch's code creates the file,
the runtimes read it under the branch's env") and changes no file that was there before.

F2: accepted, as a write that never follows a link. I rejected the proot bind after reading proot's source
(termux/proot at the submodule's commit 52d65a4):
- proot resolves a bound path only while its parent directories exist on disk. In `src/path/canon.c:136-185`, a
  missing non-final component gives ENOENT.
- For a bind whose parents are missing, proot creates them in the rootfs as placeholders when it starts and removes
  them when that proot exits (`src/path/glue.c`, `build_glue` and `remove_placeholder`).
- Chats, terminals and sshd each run their own proot. The first one to exit would delete
  `/usr/share/zoneinfo/America` and put every other running process back on UTC.
- Having Buddy make those directories itself would bring back the same host-side walk, plus a bind.

So `copyZone(tzdata, root, id)` now writes with `java.nio.file` (stdlib, already used by `Backup` and `Mirrors`)
and never follows a link:
- Each directory from `root` down must be a real directory (`isDirectory(NOFOLLOW_LINKS)`), or is created with
  `createDirectory`, which runs mkdir and fails instead of following a link. If a link or a file is there, it throws
  `IOException` and writes nothing.
- The temp file is first removed with `deleteIfExists` (which unlinks a link, never its target), then created
  with `CREATE_NEW` (O_EXCL).
- `Files.move(..., ATOMIC_MOVE)` runs rename(2), which replaces a linked target without following it.

New unit tests use an unrelated `outside/x`:
- `America` is a link to `outside/`: `IOException`, and `outside/` is unchanged.
- The target and a stale `.tmp` are both links to `outside/x`: `x` is unchanged, the target is now a regular file
  holding the payload, and no `.tmp` is left.

Out of scope: a guest that deliberately swaps a checked directory for a link between the check and the write. Guest
code already runs as Buddy's own uid.

## Round 3 - Plan Reviewer (gpt-6.2-astra)

F1 [major] Node still returns yesterday in Vancouver - The plan copies Android’s TZif file for musl and assumes the IANA id suffices for Node (plan.md:29–34, 64–68). In Buddy’s Alpine, with the proposed environment and `TZ=America/Vancouver`, Node v24.22.3 reports tzdata `2025b`. At `2026-11-20T07:30:00Z`, both `Date.toString()` and `Intl.DateTimeFormat` with `timeZone: process.env.TZ` return November 19 at 23:30. Vancouver’s local time is November 20 at 00:30 under the permanent UTC−07 rule included in the phone’s reported `2026e` data. ICU reads its own time-zone resources, so copying the TZif file leaves this discrepancy in place. -> Keep the IANA id, add this instant to the three-runtime check, and specify a verified way to supply current ICU time-zone data to existing Node installs. [IANA 2026b release](https://www.iana.org/time-zones/releases/2026b) · [ICU time-zone data updates](https://unicode-org.github.io/icu/userguide/datetime/timezone/#updating-the-time-zone-data)

F2 [major] Verification cleanup can delete unrelated live zone files - The absence check at plan.md:135 establishes the initial state only. If another Buddy session installs tzdata during the check, the recursive deletion at plan.md:149 removes its newly installed files and leaves apk recording the package as installed. The plan itself allows this install (plan.md:13). -> Keep the live-rootfs check. Record the two files it creates. If apk now owns either file or the tree contains unexpected entries, leave it intact and report the changed precondition. Otherwise unlink only the unchanged test copies and use `rmdir` on the directories the check created.

VERDICT: REVISE
