# W2: live call voice left the watch at 63.68 s and never came back

Buddy 2.3.12-fork.2, phone live call, audio_device=auto, no headset. Read-only diagnosis; no code changed.

## Timeline (call start 20:47:19 MST = 03:47:19 UTC)

| call t | UTC | what |
|---|---|---|
| 0.52 | 03:47:19.5 | `watch: playing there`: reading 1 attached |
| 45.69 | 03:48:04.7 | phone agent: "Starting the watch install in the background" |
| ~47.9 | 03:48:06.97 | agent runs `gh release download … buddy-watch.apk -O /tmp/bw.apk && watch-connect && adb -s 192.168.1.88:5555 install -r /tmp/bw.apk` (session 583b86bd transcript) |
| 51.3 | 03:48:10.28 | `/tmp/bw.apk` written (mtime) |
| 54.8 | 03:48:13.8 | adb transport to the watch up (`/tmp/adb.0.log`) |
| 59.20 | 03:48:18.2 | `watch: played 1 (+1854 ms after its end)`: reading 1 answered code 2; `openNext` opens reading 2 at once (LiveWatch.java:235) |
| 60.94–63.32 | | voice audible: reading 2 is `cur` and is fed (`fed = true`) |
| **63.68** | **03:48:22.7** | **`watch: fallback: stopped by the watch`** |
| 63.74 | | `page voice on the phone` |
| ≤ 70.9 | ≤ 03:48:29.9 | install output read back: `Success`, `versionName=2.3.12-fork.2` |
| 75.32 | | agent: "The watch is updated to 2.3.12-fork.2" |
| …170.98 | | user: "audio isn't coming out of the watch" |

## 1. Why the watch closed the second reading

**What the phone saw.** The fallback line has no reading number, so it came from the second branch of
`answered()` (LiveWatch.java:237-238): the event was for `cur`, the open reading 2, not for a draining one. Its text
"stopped by the watch" means `broken(true)` (LiveWatch.java:224-225), which ReadStream emits for a remote close with
**code 0 on a link that was not disconnected** (ReadStream.java:92-96; WatchVoiceLink.java:94-98 passes
`reason == CLOSE_REASON_DISCONNECTED` as `lost`).

**The candidates, one by one:**

- **Busy?** No. Busy (`Talk.busy() || Call.on || TalkActivity.dictating()`) closes with `NOT_PLAYED` = 1
  (wear ReadAloud.java:51-53, 92-93), which the phone logs as `refused`, not "stopped by the watch".
- **Replaced / a second reading opened while the first was still playing?** No. Replacement (wear
  ReadAloud.java:55-62) needs a reading channel still open on the watch when a new one arrives. Reading 1 had already
  closed with `PLAYED` (its answer is the `played 1` line, and `finish()` nulls `channel` before closing,
  ReadAloud.java:130-136). On the phone, `openNext` only runs after that answer (LiveWatch.java:235); `cur` and `drain`
  are never both set (LiveWatch.java:90). And a replace would close the *old* reading, not the new one.
- **Code 0 from the watch's own code paths?** Possible in principle: `Speaker` ended by `"closed"` after sound
  (ReadAloud.java:126), or `silence()` when the watch mic is about to be used (ReadAloud.java:149-156). Nothing points
  to either: the user did not touch the watch, and the phone was still streaming normally.
- **The watch app was killed under it. This is the cause.** The phone agent ran `adb install -r` of the watch APK
  *during the call*. adb reached the watch at 03:48:13.8, the install had succeeded by 03:48:29.9, and the fallback
  falls between them at 03:48:22.7 (±1 s; the log header has 1 s resolution). `install -r` force-stops the old
  package. That kills the watch's `ReadAloud` service and its `Speaker`, and Wear OS closes the dead app's open
  channel with no app code, i.e. 0. The node link itself stays up, so the reason is not DISCONNECTED. ReadStream then
  classifies it exactly as a watch-side hush: `STOPPED` → "stopped by the watch".

**Confidence: high on timing, unconfirmed from the watch side.** `/tmp/watch-w2.log` is empty because the capture
failed, not because there were no Buddy lines: at 03:50:43 adb reported the watch `offline` (auth not finished) and
the connection reset at 03:50:55 (`/tmp/adb.0.log`). The logcat ring buffer survives the reinstall, so this confirms
it:

```
adb -s 192.168.1.88:5555 logcat -d -v time | grep -E 'BuddyTalk|Force stopping app.buddy|pkg removed|app.buddy.assistant.*(died|replace)' | grep -E '20:48:(1|2)'
```

The expected result: a `BuddyTalk read <id>: received …` line for reading 2 without `ended by eof`, or no end line
at all, next to `ActivityManager: Force stopping app.buddy.assistant … (replace|installPackageLI)` at about 20:48:22
(watch clock).

Side effect: the audio of reading 2 that the watch had buffered but not yet played when it died (part of 60.94–63.68)
was lost. The rest of the reply played on the phone from 63.74.

## 2. Why it never went back to the watch

This is by design. `fallback()` sets `turn = null; watch = false` (LiveWatch.java:243-255, javadoc: "the phone's own
route, until the setting changes"). After that, `decide()` → `attempt()` runs only from:

- `begin()` (LiveWatch.java:103), at call start;
- `poll()` when the **saved setting string changes** (LiveWatch.java:131-135). With Auto, it stays `""` the whole
  call;
- `answered()` when `e.t != turn` (LiveWatch.java:234), or `giveUp()` without `watch` (LiveWatch.java:284). Neither
  applies after a fallback.

This was recorded: feature 018 decisions.md:31-37 ("A refusal or drop falls back for the rest of the call unless the
setting changes, so the voice doesn't flap"), plan.md:189, plan.md:285, and plan.md:440 lists it under Open questions
as a default "Nicholas may overrule after trying". This run is the case for overruling it: a transient watch-side
death (here a reinstall; equally an OOM kill or a crash) costs the rest of the call. The user could have got the
watch back only by changing the audio device setting away from Auto and back.

## 3. Smallest fix

**Code:** a bounded retry of the watch after a fallback, at a quiet moment so the voice doesn't jump mid-word. All
of it is in LiveWatch.java, plus a one-line accessor in CodexLive.

1. **LiveWatch.java:20.** Add `RETRY_MS = 5_000` and `MAX_RETRIES = 3`.
2. **LiveWatch.java:87-92 (fields).** Add `private int retries; private long retryAt;`. Set `retries = 0` in
   `begin()` (LiveWatch.java:98-105), and `retryAt = 0` in `clear()` (LiveWatch.java:113-121).
3. **LiveWatch.java:243-255, `fallback()`.** Before the log line:
   `retryAt = retries < MAX_RETRIES ? out.now() + RETRY_MS : 0;`
4. **LiveWatch.java:128-137, `poll()`.** After the setting-change `if`:
   ```java
   } else if (retryAt != 0 && turn == null && drain == null && out.now() >= retryAt && out.voiceQuiet()) {
       retryAt = 0;
       retries++;
       out.log("retry " + retries);
       decide(); // watchWins() is checked again; a failed retry falls back and arms the next one
   }
   ```
5. **LiveWatch.java:28-46, `Out`.** Add `boolean voiceQuiet();`. In `Phone` (LiveWatch.java:335-377):
   `return CodexLive.voiceQuiet();`.
6. **CodexLive.java:391.** Next to `lastAudible`, add
   `static boolean voiceQuiet() { return SystemClock.elapsedRealtime() - lastAudible > 1000; }`. `lastAudible` is
   already fed every 70 ms by the page's `Buddy.level(…, voice)` (CodexLive.java:733-736), including while the voice
   is on the phone. LiveWatch's own `pcm()` is not usable for this: the page's tap only forwards while `fwd`
   (CodexLive.java:910), so it gets nothing after a fallback.

Why this is safe with the existing machinery:

- `attached()` (LiveWatch.java:180-185) only mutes the phone once the new channel is open, and `gate.reset()` there
  starts the next reading at the next audible block. An unreachable watch never silences the phone: the retry logs
  `none found: phone` or falls back again.
- Flapping is bounded at 3 retries per call, each at least 5 s after the drop and only in a ≥1 s gap.
- `turn == null && drain == null` keeps it off while a reading is still draining.
- `stop()` (setting moved away) does not call `fallback()`, so it doesn't arm a retry.
- `poll()` → `decide()` only attempts while `watchWins()`.

**Tests (LiveWatchTest):** the fake `Out` gets `voiceQuiet()`. Cases: after `broken(true)` on `cur`, the polls before
5 s don't call `find`; at 5 s with voice audible, still no `find`; once quiet, `find` runs once and the log has
`retry 1`; after 3 failed retries, nothing more. Update the existing "a poll with the same setting doesn't find again"
expectation (plan.md:365, case 3), which this changes on purpose.

**Docs:** a decisions.md entry in 018 that overrules the 2026-11-17 "no retry otherwise" default, citing this log.

**Process (no code):** don't `adb install` the watch app while a phone live call's voice is on the watch. The phone
agent did that here. The code fix makes it recoverable, but the voice still drops for that utterance.
