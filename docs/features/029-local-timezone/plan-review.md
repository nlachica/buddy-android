# Plan review: {{SLUG}}
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6.2-astra)

F1 [major] The first Linux command can still return tomorrow’s date - The plan queues tzdata installation and ignores its result (plan.md:61–64). A chat starts its process immediately after sync (Helper.java:81–83), as does a terminal (TermSession.java:85–97). Even online, either can run `date` before the zone file arrives. If the download fails, the early return at Helper.java:175 can skip another attempt for the rest of the app process. -> Include tzdata in the existing checked setup transaction (Account.java:356–365). For upgrades, specify how the first launch gets usable zone data and how a failed migration recovers when connectivity returns. Keep network waits off the terminal UI thread (TerminalActivity.java:177). Check the first command on an upgraded rootfs with tzdata initially absent.

F2 [major] The background install can break Buddy’s browser setup - Browser.install calls Helper.sync and immediately runs the browser installer (Browser.java:183–185). That script runs its own `apk update` and `apk add` and reports installation failure on a lock error (linux/buddy-browser:26–35). It never takes the proposed Account.class lock. Opening the browser can therefore start both transactions by itself. -> Sequence any upgrade transaction before that installer, or supply zone data without a competing apk operation. Exercise the browser-first upgrade path with both packages initially absent.

F3 [major] Runtime verification comes after release - The plan defers the only three-runtime check to the installed release (plan.md:66,80–89). The JVM test can pass with tzdata provisioning entirely broken, and manually setting `TZ=Europe/Berlin` bypasses the launcher. -> Add a pre-merge check using the candidate build on a disposable device or an equivalent Alpine integration fixture that exercises the planned provisioning and launch environment together. Compare numeric offsets and calendar dates for the same instant in date, Python and Node, including an instant when UTC and local dates differ. Keep the installed-phone check as a release smoke test.

F4 [minor] A directory can exist without the zone data - Both guards at plan.md:62–63 accept an empty or partial `/usr/share/zoneinfo` as success. Such an upgraded rootfs skips installation on every restart. -> Check for the required readable zone file or use apk’s installed-package check. Include a pre-existing empty directory in the migration check.

F5 [minor] The workaround removal can print credentials - The standalone `jq 'del(.env.TZ)'` filter at plan.md:67–69 writes the resulting settings to stdout and leaves the file unchanged. Account.java:643 stores the Claude OAuth token in that same JSON. -> For the approved phone smoke test, follow Account.java’s temporary-file-and-rename pattern, remove only the expected `MST7` override, and verify its absence with a boolean check that prints no settings.

VERDICT: REVISE
