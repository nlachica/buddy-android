# Why Buddy agents get the date wrong (diagnosis, nothing changed)

## Root cause
Buddy's Linux (proot/Alpine) runs on UTC, so every agent sees UTC dates. Nicholas is on America/Dawson_Creek
(UTC-7, no DST). From 17:00 local to midnight local, the Linux date is already tomorrow. Agents trust it.

Evidence
1. `Linux.java:89` in the fork (`app/src/main/java/app/buddy/assistant/Linux.java`) starts every Linux process
   with `/usr/bin/env -i HOME=/root LANG=C.UTF-8 TERM=... PATH=...`. `-i` wipes the environment and no `TZ` is
   put back. `linux/buddy-run` and `linux/profile.sh` don't set TZ either. It is also unset here (`TZ=`).
2. Alpine has no tzdata: `/etc/localtime`, `/etc/timezone` and `/usr/share/zoneinfo` don't exist. `date`
   prints `Thu Nov 19 15:52:56 UTC 2026` when the phone says 08:52 Thursday. Python also reports UTC.
3. Claude Code's "Today's date is ..." comes from that process clock. In the transcripts the injected `date`
   attachment flips to the next day at exactly 17:00 local:
   - `2026-11-19T00:00:26Z` (Wed 17:00 local): `{"type":"date","date":"2026-11-19"}`
   - `2026-11-19T02:02:04Z` (Wed 19:02 local): `date: 2026-11-19` again.
4. The bad answer: at 2026-11-19T02:02Z (Wed 18 Nov, 19:02 local) an agent offered openings for "tomorrow, Fri Nov 20".
   It believed today was Thu 19 Nov (the injected UTC date), so it called Fri 20 "tomorrow". Real
   tomorrow was Thu 19. This repeats every evening after 17:00 local, which matches "happened several times".
5. The date the app gives agents is correct, but only if they ask. `PhoneTools.java:299` (device_status) prints
   `Local time: EEEE yyyy-MM-dd HH:mm (<Android tz id>)` from the phone's own zone. Agents don't call it before
   scheduling, and Claude Code's injected date is the one they read first.

Not the cause
- wa-sync: `WATCH.md` already warns "Linux clock is UTC: convert". `check.sh` uses `TZ=MST7`, `recent.mjs` uses
  `process.env.TZ || 'America/Dawson_Creek'`, and `bin/volley-today` uses a fixed UTC-7. Those paths are fine.
  The watcher agent can still get "today" wrong if it skips the conversion.
- Composio calendar calls: no timezone bug found. They take explicit datetimes; the wrong word was the agent's.
- Vault: `architecture/buddy-phone.md` (2026-11-12) and `_meta/L1.md` already say the clock can be a day ahead.
  This is a per-agent warning, not a fix, and it only reaches agents that read the vault or L1.
  Plain chats don't act on it.

## Smallest fix: set TZ at the source
In `Linux.java:89`, add `"TZ=MST7"` to the `env -i` list:

    "/usr/bin/env", "-i", "HOME=/root", "LANG=C.UTF-8", "TZ=MST7", "TERM=xterm-256color", ...

`TZ=MST7` is a POSIX rule that needs no tzdata and no DST part. That matches Dawson Creek, which has been on
fixed MST (UTC-7) since 2020. I checked it on this phone with TZ set only in the command:

| TZ value | `date` | python | node |
|---|---|---|---|
| unset | UTC, Thu 15:54 | UTC | GMT+0, "Etc/Unknown" |
| `MST7` | MST, Thu 08:54 | MST | GMT-0700 |
| `America/Dawson_Creek` | still UTC (no tzdata) | still UTC | correct |

So `MST7` is right for `date`, python and node (Claude Code, Codex, wa-sync). An IANA id alone would fix only
node. With `MST7`, Claude Code's injected date becomes the local date, and the evening flip moves to local midnight.

Because `env -i` is in the one shared launcher, the fix covers chats, background agents, Codex, OpenCode, the
browser helper and `Linux.exec`.

Better, if you want it generic (a bit more work): `apk add tzdata` in the rootfs setup and pass
`"TZ=" + java.util.TimeZone.getDefault().getID()`, so travel and DST follow the phone. This takes an Alpine
package install plus a Linux.java change.

## Without a rebuild (interim)
- Chat-level: put `export TZ=MST7` early in `linux/buddy-run` (installed copy `~/bin/buddy-run`, overwritten on
  app update; it is also a fork source file, so edit it in `linux/buddy-run` and bump `HELPER_VERSION`, now 33).
  It's inherited by the claude/codex processes it launches.
- A wrapper around `~/.local/bin/claude` that exports TZ is lost on `claude update`, so not recommended.
- Prompt-level (weak): add one line to `linux/system-prompt.md`: "The Linux clock is UTC; the user's date is in
  the 'Local time' line of device_status." This is a hint only; the injected date still conflicts with it.

## Checks after the fix
- In a new chat run `date` (should say MST and the local date) and ask "what's tomorrow's date and weekday?" after 17:00 local.
- In the next transcript, the `date` attachment (`"type":"date"`) should stay on the local date until 00:00 local (07:00Z).
- Existing sessions keep the old env until restarted.
