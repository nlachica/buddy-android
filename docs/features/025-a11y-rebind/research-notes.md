# Buddy accessibility service switching off: investigation (2026-11-18, read-only)

## 1. Already fixed? No. Never root-caused.
- **Transcripts.** He reported it on 2026-11-14 at 17:44Z (a35d19e5). The agent's checks were blocked or interrupted, and the logcat buffer only reached back a few minutes. Its guess was a force-stop (Samsung sleeping apps, auto-optimization or an update). It told him to add Buddy to Never sleeping apps and to set Battery to Unrestricted. He said "I'll do that later", and nothing confirms he did. A 2026-11-16 check found Buddy "probably Unrestricted already". The 2026-11-18 audit (8330aeae, 583b86bd) lists it as "not started".
- **A known force-stop.** On 2026-11-12 at 23:26Z (5e15e431) he force-stopped Buddy himself (App info, then Force stop) to clear a stuck "Stopping". On AOSP, a force-stop removes the service from `enabled_accessibility_services`, so that day at least one switch-off had a known cause. The 11-14 one (around 10:40 local) has no recorded cause.
- **Fork.** `git log --all -i --grep=accessib` finds no fork fix. No `docs/features/*` covers it (001 to 024 are about audio, the watch, settings and so on).
- **Upstream.** Two releases touch the service, and neither stops it being switched off:
  - 2.3.8 (#41) only changes how setup *detects* that the service is on.
  - 2.3.12 (#54, the fork runs v2.3.12-fork.1) only *explains* it: sending a message now says "accessibility service isn't running, although it's switched on: Android stopped Buddy and didn't start the service again" (`hub_accessibility_dead`, ChatHub.java:309). Upstream itself confirms an "on but dead" state.
- **Vault.** `devices/phone.md:15` says "switches off by itself (cause unknown; a force-stop is one known trigger)". `~/.buddy/tips` has nothing.
- **Source.** BuddyService has no breadcrumb for when the service is lost:
  - `onDestroy` (BuddyService.java:303) cleans up silently, and `onServiceConnected` records nothing.
  - There is no `onUnbind` and no reading of `getHistoricalProcessExitReasons`.
  - Nothing in the app calls `disableSelf()`. The only self-kill is `Backup.restart` (restore only).
  - Re-requesting the service happens only through the setup check (`SetupState.accessibilityEnabled`) and the send-time message that opens Settings → Accessibility.

## 2. Likely causes, ranked
1. **Two different states that both look "off".** Which one he hits decides the fix:
   - (a) *Truly disabled*: Buddy's entry is removed from the enabled list. On AOSP this happens on a **force-stop**, by him or by Samsung (MARs / "deep sleeping" / abnormal-battery auto-stop / auto optimization).
   - (b) *Enabled but dead*: Buddy's process gets killed (lmkd under memory pressure, a WebView update, or a package update). Android normally rebinds. After repeated deaths it gives up (crashed state), and Settings shows it as not working.
   Memory evidence makes (b) likely. On 11-17 he had 1.5 GB available and 5 GB in swap, lmkd was busy, and pipelines run several agents. Exit-info also showed Buddy stopped for a WebView update ("stop com.google.android.webview due to installPackageLI").
2. **Samsung battery policy force-stopping Buddy.** Sleeping or deep sleeping apps, MARs or auto optimization. This is the top cause if the entry is removed *without* him force-stopping it.
3. **His own force-stops.** App info → Force stop, done to clear stuck chats (as on 11-12). It's easy to forget this turns accessibility off.
4. **Low: Buddy updates.** A normal update keeps the service enabled on AOSP, but restarts it (covered by 1b).
5. **Very low: Android 16 Advanced Protection or Samsung Auto Blocker Maximum.** These turn off accessibility services not marked `isAccessibilityTool`. If it were active, he couldn't turn the service back on, which he can. Rule it out with one look.

## 3. phone_shell commands (read-only)
Note: phone_shell blocks any command containing `app.buddy.assistant`, `enabled_accessibility_services` or `accessibility_enabled` (Tools.java:665 `SHELL_NO`). The commands below use `buddy` patterns instead, so no evasion is needed. Run them **as soon as possible after it next turns off** (logcat only holds minutes), then again after he turns it back on.

```sh
# A. Which state? Is Buddy listed as enabled, and is it bound or crashed?
dumpsys accessibility | grep -iE "enabled services|bound services|crashed|binding services|buddy" | cut -c1-400
#  Missing from Enabled               -> truly disabled (force-stop path, cause 1a/2/3)
#  In Enabled, not Bound, or Crashed  -> enabled but dead (cause 1b)

# B. Why did Buddy's process die? (exit reasons, newest first; survives logcat rotation)
dumpsys activity exit-info | grep -iE -B1 -A1 "process=[a-z.]*buddy[a-z.]* reason" | cut -c1-260 | head -40
#  reason=10 USER_REQUESTED, description "stop ... from pid N"  -> force-stop; then:  ps -A -o PID,NAME | grep -E "^ *N "
#  reason=3 LOW_MEMORY / 13 OTHER KILLS / lmkd                   -> memory (1b)
#  reason=16 PACKAGE_UPDATED                                     -> update or WebView update restart
#  reason=4/5 CRASH or CRASH_NATIVE                              -> a Buddy bug

# C. Who force-stopped it? (Samsung MARs / Device care vs. the Settings app)
logcat -d -b main,system,events | grep -iE "force.?stop|Force stopping|MARs|freecess|DeviceCare|sleeping" | grep -i buddy | cut -c1-240 | tail -20

# D. The accessibility system's own log line at unbind time
logcat -d -b all | grep -iE "AccessibilityManagerService|AccessibilityServiceConnection|UiAutomation" | grep -iE "buddy|died|crash|unbind|disabl" | cut -c1-240 | tail -20

# E. Battery and sleep status (is it exempt?)
dumpsys deviceidle whitelist | grep -i buddy
cmd appops get $(pm list packages | grep -i 'app\.bud' | cut -d: -f2) RUN_ANY_IN_BACKGROUND
am get-standby-bucket $(pm list packages | grep -i 'app\.bud' | cut -d: -f2)
dumpsys package $(pm list packages | grep -i 'app\.bud' | cut -d: -f2) | grep -iE "stopped=|firstInstallTime|lastUpdateTime|versionName"
#  stopped=true right after it goes off -> it was force-stopped

# F. Samsung sleeping lists and auto optimization (key names vary by One UI; read-only)
settings list global | grep -iE "sleep|auto_optim|auto_restart|deep" | head -20
settings list secure | grep -iE "sleep|deep|auto_blocker|advanced_protection" | head -20
dumpsys activity service com.samsung.android.sm 2>/dev/null | grep -i buddy | head   # Device care (may print nothing)

# G. Memory pressure when it happened (cause 1b)
logcat -d -b events | grep -E "am_kill|am_proc_died" | grep -i buddy | tail -10
cat /proc/meminfo | head -3
```
Fastest test: A plus B, right after the next switch-off, settle it on the spot. If they can't be run in time, B alone still works hours later (exit-info keeps history).

## 4. Smallest fix options
1. **Settings he does by hand (no code).** In Battery → Background usage limits: add Buddy to **Never sleeping apps**, and turn **Put unused apps to sleep** off. Set Buddy's app battery to **Unrestricted**. Turn Device care → Auto optimization's auto-restart and cleanup off. Stop using Force stop to clear stuck chats: close and reopen the chat instead (2.3.12 also fixed Stopping, #53). This fixes causes 2 and 3.
2. **Small fork change (about 20 lines in BuddyService):** a breadcrumb for the next occurrence.
   - In `onServiceConnected`, append the time plus `ActivityManager.getHistoricalProcessExitReasons(getPackageName(), 0, 3)` (reason and description) to `files/a11y.log`. In `onDestroy`/`onUnbind`, append the time.
   - The app can read its own exit reasons without a shell, so the next agent learns "force-stopped by pid X / lmkd / update" even days later.
   - Run it through the pipeline, and batch it with the next release.
3. **If it's 1b (memory):** cut memory pressure. Fewer pipeline agents at once (already partly done) and smaller swap churn. Optionally, the fork could show a notification when the service is enabled but not bound for more than 1 minute, with a one-tap shortcut to Settings → Accessibility, so he notices before a chat fails.
4. **Not recommended:** having Buddy or a script re-enable itself through the background helper (`settings put secure ...`). This is a security tripwire upstream deliberately blocks, and it would hide the cause.
