## Diagnosis: the Watch read-aloud path never ran. You were on a live voice call on the phone, and picking Watch mid-call released the speaker, so the call's voice dropped to the earpiece.

No code changed. I ran read-only `dumpsys audio` on the phone (I wrote a temp copy to `/sdcard/Download/a.txt` and deleted it) and read `~/.buddy/live.log`. I didn't need wireless debugging; `phone_shell` was enough.

### What the phone shows
- **`live.log`:** a phone live voice call started at 17:45:29, not a watch call. It's still on: its last line is at +1116 s. At +650 s you switched the device to Watch. At +675 s the line "This reply should come through your watch…" was spoken by the live voice itself. The reply you heard was the call talking, not a read-aloud.
- **`dumpsys`:**
  - At 17:56:19.239 (+650 s), Buddy called `clearCommunicationDevice`, and nothing set a device again after that.
  - Buddy has owned `MODE_IN_COMMUNICATION` since 17:45:30.
  - A `VOICE_COMMUNICATION` mic with echo cancelling is open (riid 30815), plus two `USAGE_VOICE_COMMUNICATION` AAudio players.
  - Before the switch, each time the call opened its mic the page picked the earpiece, and Buddy put the Speaker back 0.8 s later (17:45:50.881 earpiece, then 17:45:51.665 speaker).

### (1) Every reason the Watch path ends up on the phone
| # | Where | Reason |
|---|---|---|
| **0 (this one)** | `ChatHub.java:1057-1059` | No read-aloud at all while `CodexLive.on()`: the live voice speaks for itself on the phone. Watch only covers `Voice.speak`, and 012 lists calls as a non-goal. |
| 1 | `Voice.java:240` | The build has no watch link (F-Droid or `build.sh`). |
| 2 | `ReadQueue.java:310`, `WatchVoiceLink.java:44-55` | The search finds no reachable node with `buddy_watch_read` within 3 s (`:26`), or the search fails. A slow lookup over Bluetooth only would land here. |
| 3 | `ReadStream.java:93-98`, `WatchVoiceLink.java:75-83` | The channel or its output stream isn't open within 10 s. |
| 4 | `ReadStream.java:87-89` | The watch closes with code 1: busy (Talk, Call or dictation), its foreground service was refused, or its player failed before making sound. |
| 5 | `ReadStream.java:74-80` | The stream got no audio: an unsupported TTS format (`Voice.java:392-394`) or the TTS failed (`:419-420`). |

If ChatGPT's voice fails on the watch path, the phone's TTS goes to the watch, not the phone (`ReadQueue.java:327-331`). A picked-Watch fallback would have started with the "watch unavailable" notice in the phone's TTS voice (`ReadQueue.java:359-366`), which isn't what you heard.

### (2) Why the earpiece
- `AudioRoute.want()` (`AudioRoute.java:191-194`) treats `watch` as Auto, `""`.
- While the call is on, the poll runs `reconcile` every 300 ms (`:262-272`). It reaches `drop()` (`:209-211`), which calls `clearCommunicationDevice()` (`:249`) because your earlier Speaker pick was in effect. The settings change is picked up even though `SettingsTool` never calls `use()`.
- `dropMode()` correctly leaves the mode alone during live voice (`:256`), so the call stays in communication mode with no chosen device. Android then uses the earpiece, which is also what the page picks itself when it opens the mic.
- CodexSpeech isn't involved: only `CodexLive.java:929` opens a mic (getUserMedia).

### (3) Did 016 change the watch key?
No.
- `want()`, `use()` and `reconcile()` are unchanged between fork.6 and fork.8.
- 016 only added Auto's quiet items (`Voice.java:240`, ReadQueue). A picked Watch still runs with `quiet=false`, as before.
- `wear/` and `src/watchlink` didn't change, so the watch's fork.6 build matches.

The bug dates from 012's decision that Watch counts as Auto for routing.

### Smallest fixes
- **Earpiece (the real bug):** in `want()`, while live voice is on, treat `watch` as the phone speaker's key instead of `""`. For example, `return CodexLive.on() ? speakerKey(c) : "";`, where `speakerKey` finds the built-in speaker among the available communication devices. When the call ends, `want` goes back to `""` and `drop()` clears it.
  - Auto during live voice has the same earpiece default. Changing that goes against 001's rule that Auto behaves as before, so it's your call.
- **Live voice on the watch:** that's feature-sized (CodexLive's voice through a Remote to the watch). Meanwhile, Setup's Watch note could say it applies to read-aloud only. Right now, ending the call puts the phone back in normal mode.

### 10-line summary
1. The read-aloud and Watch path never ran: a phone live voice call was on, and `ChatHub.java:1057` skips read-aloud during calls.
2. The reply you heard was the live voice, in a call Buddy started at 17:45:29 that's still open now (`live.log`).
3. Picking Watch at 17:56:19 led to `want()` turning `watch` into Auto (`AudioRoute.java:191-194`).
4. The 300 ms poll then ran `reconcile()` → `drop()`, which cleared your Speaker pick (`:209-211`, `:249`).
5. The WebView keeps `MODE_IN_COMMUNICATION` for the call (`dumpsys`: mode owner is Buddy's pid, plus an open mic).
6. In communication mode with no chosen device, Android uses the earpiece, matching your "Active communication device: earpiece".
7. The Watch fallback reasons (no watch found in 3 s, channel not open in 10 s, watch said "not played", empty stream, no link) didn't apply.
8. 016 didn't touch watch-key routing, and the watch's fork.6 build hasn't changed since.
9. Fix 1 (small): during live voice, treat `watch` as the phone speaker in `AudioRoute.want()`.
10. Fix 2: live voice on the watch is a feature (007-style Remote); for now, the Watch note could say "read-aloud only".
