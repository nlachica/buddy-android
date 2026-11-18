# Watch-started live call: no response (diagnosis, read-only, nothing installed or changed)

Times are phone/watch local (MST). Sources: ~/.buddy/live.log + live.log.1, ~/.buddy/a11y.log, watch logcat (adb 192.168.1.88:5555, read-only dump).

## What happened
Two watch-started calls in the window. The phone received both, and both started.

| Call | Watch (logcat) | Phone (live.log) |
|---|---|---|
| A, 21:01:14 | `wear.Call` FGS and `CallActivity` start. The `buddy:call` wake lock is held from 21:01:15. The call screen is pushed behind another activity at 21:02:54. | **No log.** The log keeps only the current and one previous call (`live.log`, `live.log.1`), so this call's log was overwritten. |
| B, 21:06:37 to 21:10:35 | `Call` FGS and `CallActivity` start at 21:06:37. The mic opens at 21:06:38.2 (AudioRecord source 7 = VOICE_COMMUNICATION, input 4510, not silenced). The call lasts 237 s. | `live.log.1`: "start: chat 480311123d60 (watch call)", thread 01a1ecd5-…, `call: start -> result {}`, WebRTC connected at +1.8 s, data channel open, `session.started` at +2.0 s. At +237 s "end: End", the user hanging up. |

- The phone got the request and the call started. The 021 path (watch → `WatchCall.open` → `CodexLive.start`) worked, and no error reached the watch.
- Audio went watch mic → channel → `WatchCall.read` (WatchCall.java:~76-95) → `CodexLive.push` (CodexLive.java:866) → `pull`/`ownTrack` in the page → WebRTC → the realtime backend.
- Transcripts: **none**. `live.log.1` has no `turn.created`, `turn.done`, `transcript/done` or `handoff` line in the whole call, and nothing was sent to the chat. The backend never heard speech.
- Evidence that no speech reached the page:
  - `mic peak` reads 0.000–0.003 in every 10 s report except one blip of 0.102 at +220 s.
  - The page never sent speech: no `turn.created` for the user.
  - The voice never spoke: `voice peak 0.000`, and received RTP was only about 1500 B per 10 s, which is Opus silence.
- Why the watch showed "Working on it…" the whole time:
  - `status()` (CodexLive.java:~401-414) shows "Working on it…" whenever `ses.running` is true.
  - The status is pushed to the watch by `r.status(t)` (CodexLive.java:~417-420).
  - The status log shows "Working on it…" at +1.74 s and it never changed to "Listening…" or "Speaking…".
  - So the chat run was busy (inference: the same chat was running the agent turn). The status says nothing about whether the call hears the user, which is why it looks like a hang.
- Nothing was sent to the chat, so he couldn't tell: there is no "heard you" feedback on the watch, and no transcript is shown.

## Where the audio is lost (not proven)
The phone side is healthy (ICE connected, audio context running, data channel open, no console errors). Two things on the watch could produce silent input, and neither is logged:
1. `Call.java:246-247`: the half-duplex gate `if (n == 0 || now < quietUntil) continue;` drops mic frames while `sp.audibleNow()` is true or within 300 ms after it. Nothing says how many frames were actually written to the channel.
2. `Call.java:239-247`: the VOICE_COMMUNICATION recorder (AEC/NS) may return near-silence on this watch. The mic did open and was not silenced, so that is at least possible.

I can't tell which from the logs. Nothing counts frames on either end, so "watch sent silence", "watch sent nothing" and "user didn't speak until +220 s" look the same.

## Separate problem seen in the 21:10 call (phone-started, voice on the watch)
`live.log` +61.6 s: "watch: broken 7 (+6373 ms after its end)" → "watch: fallback: broken" → "page voice on the phone". The watch speaker stream broke after 6 plays, and the voice fell back to the phone. This is the `LiveWatch` path (feature 017), not the watch-started call, but it is the same "no sound on the watch" symptom.

## Smallest fix
1. **Make it observable (about 6 lines):**
   - `Call.mic` (Call.java:~242-250): count frames written and frames dropped by the gate, with the peak amplitude. Log once every 5 s with `Log.i(TAG, …)`, and put it in the status JSON.
   - `CodexLive.push` (CodexLive.java:866): count frames and the peak level. Add one line to the page's 10 s report: `watch in N frames, peak X`.
   - Both logs together settle whether the watch sends silence or the page drops frames.
2. **Fix the misleading status:** in `status()` (CodexLive.java:~401), when `remote != null` and the chat is running but the call is idle, show "Listening…" or "Chat busy, I'm listening" instead of "Working on it…". At least append whether audio was heard in the last N seconds.
3. **Keep the logs:** `live.log.1` is overwritten by the next call, so call A's log is lost. Keep 3 to 5 rotated logs (or timestamp them) so a watch call can be reviewed after a second and third call.

I did not change code or install anything on the watch.
