---
type: diagnosis
feature: 019-watch-voice-fix
created: 2026-11-17
---
# Diagnosis: watch voice in a phone live call (A) and a call started from the watch (B)

Read-only. No code changed. Evidence used:
- The phone's `live.log`, copied to `/tmp/live-018.log`. The call started 23:14:31 MST and its header says Buddy
  2.3.11-fork.3, not fork.1.
- Watch logcat for Buddy's process (`/tmp/watch-018.log`), plus `adb logcat -d` on the watch.
- The phone's `dumpsys audio` (via `phone_shell`).
- The code at `dbf187a`.
- Clocks: the watch is about 0.9 s behind the phone. I measured it with `adb shell date` against the phone's own
  clock (the same kernel).

## Bug A: phone live call with audio_device = watch, and nothing heard anywhere

### What happened, in order (live.log seconds)
| t | live.log | meaning |
|---|---|---|
| 0.05 / 0.84 | `watch: looking` / `playing there` | LiveWatch opened reading #1 to the watch (`LiveWatch.java:144-180`) |
| 2.26 | `watch: stop: the setting moved` | `poll()` saw a different `audio_device` value (`LiveWatch.java:128-141`). Reading #1 had no audio yet, so it was cancelled (`:262`). The watch logged `read …281: received 0 … ended by eof` at 23:14:33.27 watch time, which is 23:14:34.2 phone time |
| 2.95 | `page audio element playing` | the remote track is attached to `<audio id=out>` |
| **3.95** | **`page audio element paused`** | **the `<audio>` element stopped. Nothing ever logged `playing` again for the rest of the call** |
| 4.29 / 4.95 | `looking` / `playing there` / `page voice on the watch` | reading #2 opened. The page muted `<audio>` and started the tap (`CodexLive.java:902-906`) |
| 11.8 → 271.8 | `rtp 10 s: … got 28–70 kB, level 0.000` | audio packets keep arriving, and the inbound level is 0.000 every time, even at 161–171 s while the model was talking (70 kB) |
| ~184 | (watch) `read …282: received 0 … ended by eof` at 23:17:33.29 watch time, ~23:17:34.2 phone time | reading #2 ran about 3 min with **0 bytes**, then the phone closed it |
| 187.90 | `watch: fallback: broken` / `page voice on the phone` | 3.7 s later, which fits `ReadStream.CLOSE_GRACE_MS` = 3000 (`ReadStream.java:15,37,108`). The page unmuted `<audio>`, but it was still paused, so the phone stayed silent too. `level 0.000` continued |

The user confirmed during the call: "nothing's playing at all". That matches every line above: from 3.95 s on, the
received audio was never played out, on the watch or on the phone.

### Answers to the three questions
1. **Is the tapped audio empty?** Yes. LiveWatch only writes to the watch when a block passes the gate (peak > 0.01,
   `LiveWatch.java:283-298, 312-323`). Over about 180 s with the model speaking several times, nothing passed. The
   watch received 0 bytes because the tap only delivered silence (or nothing), not because the link failed. The link
   works: two read-alouds at 23:28 and 23:29 played on the watch (`received 368640 / 1019904, audible at …`).
2. **Does `level 0.000` mean the page's remote audio is silent?** Yes, in practice. `inbound-rtp.audioLevel`
   (`CodexLive.java:987`) is measured on the audio Chromium plays out. It stayed 0.000 even while 70 kB/10 s of
   speech arrived and after the voice went back to the phone. So the decoded voice reached no player at all. The tap
   (`CodexSpeech.java:389-404`) and the page's `said()` meter read that same stream through Web Audio. The project
   already records the Chromium rule (`CodexSpeech.java:386-387`): *"Chromium only feeds a remote WebRTC track into
   Web Audio through a media element"*. That element was **paused** from 3.95 s, so Web Audio got silence.
   **Root cause: `<audio id=out>` paused at 3.95 s and nothing ever resumed it.** The page only logs the pause
   (`CodexLive.java:971`) and never calls `play()` again.
   - Why it paused is not proven. `dumpsys audio` shows no other app requesting audio focus in that window, and
     Buddy never requests it. What does line up: the WebView's mic reopened and it set the earpiece at 23:14:33.347,
     then Buddy put the speaker back at 23:14:34.052 (live.log 3.80 `route: … applied`), 150 ms before the pause.
     The WebView is also never attached to a window (`CodexLive.java:668`), so Chromium may treat the page's player
     as hidden. Either way, the fix is to resume it, whatever paused it.
3. **Does the route or setting change stop it?** Not what silenced the call.
   - The 2.26 s `stop` cancelled reading #1 before the voice existed. Reading #2 then ran from 4.95 s to ~184 s with
     no setting change.
   - The route changes (speaker re-applied, the WebView's earpiece pick) only precede the pause above.
   - The 2.26 s stop itself needs `audio_device` to have changed (`LiveWatch.java:131`, with `watchWins()` false). The
     only writers are:
     - the live bar's device chip, where one tap moves to the next device (`VoiceBar.java:81-85`)
     - Setup (`SetupActivity.java:643`)
     - `buddy_settings` (`SettingsTool.java:344-345`)

     Nothing logs which one fired. With Auto, `livePick` drops the watch if any media headset output is connected
     (`AudioRoute.java:129-131, 92-96`). It recovered by 4.29 s, so this is noise, not the bug. It should be logged.
   - The ~184 s close is secondary. The phone's `PcmOut` failed on a channel idle for 3 min (a write failure or a
     close; the watch saw a clean eof), and `ReadStream` reported `broken` after its 3 s grace.

### Smallest fixes (A)
- **A-1 (the fix, `CodexLive.java:971` PAGE):** keep the element playing while the call is up.
  - In `ontrack`, replace the pause handler with
    `out.onpause = () => { L('audio element paused'); if (pc && pc.connectionState !== 'closed') out.play().then(() => L('audio element resumed')).catch(e => L('resume failed: ' + e)); };`
  - In the 70 ms timer (`:978`), as a backstop: `if (out.srcObject && out.paused) out.play().catch(() => {});`.
  - Muted playing is fine for the tap: it already is muted when the voice goes to the watch.
- **A-2 (diagnostics, `CodexLive.java:986-988`):** add `paused ' + out.paused + ', ctx ' + ctx.state` and the tap's
  last peak to the `rtp 10 s` line. A silent tap then shows up in one line instead of needing the watch's logcat.
- **A-3 (`LiveWatch.java:131-133`):** log the change, `out.log("setting " + saved + " -> " + s)`, so the next
  "setting moved" says which value it moved to.
- Verify on the device: a phone call with Watch picked. `live.log` must show `level` > 0 while the model speaks and
  no `paused` left unresumed. The watch's `read …` line must show `received` > 0 and `audible at …`.

## Bug B: watch call (007) in a new chat said "on the call, listening", but nothing was heard either way

### Evidence
- The watch shows QuickTalk at 23:00:08 and `CallActivity` visible from 23:04:26 to 23:05:26 (system log).
- The app's own log lines (`BuddyCall`) for that call are gone: the watch's main log buffer now starts at 23:18:29.
- The phone's `live.log` is rewritten at each call start, so the 23:14 phone call wiped the 23:04 watch call's log.
- So B is diagnosed from code. "Listening…" only means the phone's call started and its data channel opened:
  `WatchCall` relays `CodexLive.status()` to the watch (`WatchCall.java:159-160`). It says nothing about audio.

### Likely causes, most likely first
1. **The same silent tap as A.** A watch call's voice reaches the watch only through the same tap on the remote track
   (`CodexLive.java:976`, `if (rem) tap(ctx, s, () => true)`). The tap depends on `<audio id=out>` playing, muted. If
   the element pauses, which A shows happens and is never undone, the watch's Speaker gets silence. Fixed by A-1.
2. **The page's AudioContext stays suspended in a watch call.**
   - A watch call opens no mic (`rem`), so nothing un-suspends the context: no `getUserMedia`, no user gesture.
   - The WebView is offscreen (`CodexLive.java:668`), and the phone is often locked when calling from the watch.
     `await ctx.resume().catch(() => {})` (`:942`) can stay `suspended`.
   - Then neither the ScriptProcessor in `ownTrack` (`:917-930`) nor the tap runs. The model hears silence from the
     watch mic, so "listening" with nothing happening, and the watch hears nothing back. Both symptoms match.
   - The phone call in A logged `audio context running` (1.36 s) because it opened the mic. A watch call has no such
     line to compare yet.
   - Fix: `ctx.onstatechange = () => L('audio context ' + ctx.state)`, and in the 70 ms timer
     `if (ctx.state === 'suspended') ctx.resume();`.
3. **Not the cause:**
   - `audio_device = watch`: a watch call has a Remote, so `AudioRoute.use` and `LiveWatch` are skipped
     (`CodexLive.java:215, 236-244`) and `toWatch()` returns 0 (`:726`).
   - 013's "New chat": it opens the talk screen. The call in that chat is the normal 007 path, and `WatchCall` finds
     the chat by id (`WatchCall.java:118-122`). A missing chat would have ended the call with "chat gone", not
     "listening".
   - The watch's mic loop (`wear/.../Call.java:216-247`) only skips blocks while its own speaker is audible.

### Smallest fixes (B)
- A-1 and the AudioContext resume above. Both are in the same page code, so one change covers A and B.
- **Keep the evidence:** in `CodexLive.logStartNew`, rename the old `live.log` to `live.prev.log` before starting a new
  one, so a call placed right after another doesn't erase it.
- Next repro: right after the watch call, copy `~/.buddy/live.log` and run
  `adb -s 192.168.1.88:5555 logcat -d -s BuddyCall BuddyTalk`. Look for `audio context`, `voice tapped for the watch`,
  `rtp … level`, and the watch's `call <id>: received …`.

## 10-line summary
1. A: the watch got 0 bytes because the page's tapped voice was silent the whole call, not because the link failed.
2. `<audio id=out>` paused at 3.95 s (`page audio element paused`) and nothing ever resumed it (`CodexLive.java:971`).
3. Chromium feeds a remote track into Web Audio only through a playing media element (`CodexSpeech.java:386`), so the tap got silence.
4. `level 0.000` for 270 s, even with 70 kB/10 s of speech and after the fallback to the phone, confirms nothing was played out anywhere.
5. LiveWatch's gate never opened (`LiveWatch.java:283-298`). The reading idled ~180 s, failed, and reported `broken` after the 3 s grace.
6. The 2.26 s "setting moved" stop needed an `audio_device` change (chip `VoiceBar.java:81-85`, Setup or the tool). It is unproven and harmless: it recovered by 4.29 s.
7. The watch link itself works: read-aloud played on the watch at 23:28 and 23:29.
8. B: no logs survive from the 23:04 watch call. Most likely causes are the same paused element and a suspended AudioContext in the offscreen WebView.
9. Fix: resume `<audio>` on pause and in the 70 ms timer, resume a suspended AudioContext, and log both in the `rtp` line.
10. Also log the old and new setting in `LiveWatch.poll`, and keep the previous call's `live.log`, so the next repro has evidence.
