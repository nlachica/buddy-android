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
