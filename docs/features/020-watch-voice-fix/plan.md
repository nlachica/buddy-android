# Plan: 020-watch-voice-fix

Research stage: `diagnosis.md` stands in for it (`research.md` is empty). Line numbers below are at `d23cfe2`, which
has the same code as the diagnosis's `dbf187a` (only docs changed since).

## Goal
Live voice calls must be heard again. (1) A phone live call with Watch picked plays the voice on the watch, and with
phone or speaker picked plays it on the phone (`level` > 0 in the `rtp 10 s` lines). (2) A call started from the watch,
including from the watch's New chat, is heard both ways. (3) `live.log` shows the page's audio element and
AudioContext state and every `audio_device` change (old -> new), and the previous call's log is kept as `live.log.1`.
(4) CI is green. Root cause (`diagnosis.md`): the page's `<audio id=out>` paused at 3.95 s and nothing resumed it
(`CodexLive.java:971`). Chromium feeds a remote WebRTC track into Web Audio only through a playing media element
(`CodexSpeech.java:384-387`), so the phone's speaker, the tap for the watch and the level meter all got silence.
In a watch call a suspended AudioContext would also stop both directions (the `ownTrack` ScriptProcessor and the tap).

Non-goals:
- Proving *why* the element paused. We resume it whatever paused it (diagnosis A, question 2).
- Logging which writer changed `audio_device` (the chip, Setup or the tool). The brief asks only for old -> new.
- The ~3 min idle break of the reading LiveWatch opens ahead of the voice (diagnosis: "secondary"), and its
  "fallback until the setting changes" (`LiveWatch.java:237-249`).
- The watch app, `CodexSpeech` (read-aloud works on the watch), and the shared `TAP_JS`.

## Design

Everything except the setting log is in `CodexLive.java`: two places in the `PAGE` JS and `logStartNew`. One change to
the page fixes A and B, because both calls use the same page.

**1. Keep `<audio id=out>` playing while the call is up (fixes A, and cause 1 of B).** A page function
`keepPlaying()` calls `out.play()` when four things hold: the element has a `srcObject`, it is `paused`, no resume is
already in flight, and `pc` exists and is not `'closed'`. It is called from two places:
- `out.onpause`, which is event-driven, so the resume is immediate.
- The existing 70 ms timer (`:977`), as a backstop: it catches a `play()` that was rejected, and a pause that came
  without an event. If the offscreen WebView counts as a hidden page, Chromium may slow this timer to about once a
  second. That is why `onpause` is the main path and the timer only backs it up.

The in-flight flag stops `play()` promises piling up every 70 ms. When a resume fails, its error is logged, but only
when it differs from the last one logged. `hangUp()` closes `pc` before it clears `srcObject` (`:1007-1008`), so ending
the call never resumes the element. A muted element that is playing is fine for the tap: that is today's state while
the voice is on the watch.

**2. Resume a suspended AudioContext (cause 2 of B).** Set `ctx.onstatechange = () => L('audio context ' + ctx.state)`
after the first `audio context …` line (`:942`). In the 70 ms timer, add: if `ctx.state` is neither `'running'` nor
`'closed'` and no resume is in flight, call `ctx.resume()` (errors ignored, one call in flight). Checking "not
running" also covers the newer `'interrupted'` state. The first line also gets `, page ' + document.visibilityState`.
That one word tests the diagnosis's untested idea that the never-attached WebView (`:668`) is a hidden page.

**3. The `rtp 10 s` line (`:988`) gets the states.** Append:
- `voice peak <max>`: the highest `said()` value over the last 10 s. The 70 ms timer keeps `vMax`, and the stats
  timer resets it.
- `audio playing|paused, <n> pauses`.
- `ctx <state>`.

`said()` (`meter(s)`, `:973`) reads the remote stream through Web Audio, which is the same path the tap uses. So a
silent tap shows up as `voice peak 0.000` in one line, without the watch's logcat, and without changing the shared
`TAP_JS`. Pause logging is capped: `audio element paused` and `audio element playing` are logged for the first
5 pauses of a call, and after that only the count in the `rtp` line grows. If something keeps pausing the element,
`live.log` can't flood.

**4. The setting change, `LiveWatch.poll` (`LiveWatch.java:131`).** Before `saved = s`, log
`out.log("setting " + show(saved) + " -> " + show(s))`, where an empty value shows as `auto`. In `live.log` that reads
`watch: setting 2:Speaker -> watch`. LiveWatch runs in every phone call (`CodexLive.java:243`), so every change during
a phone call is logged. A watch call skips LiveWatch and the route (`CodexLive.java:215, 234-243`).

**5. Keep the previous call's log.** In `logStartNew`'s logger task (`CodexLive.java:178-190`), after `mkdirs()` (`:183`):
`f.renameTo(new File(f.getParentFile(), "live.log.1"))`, with the result ignored. A failed rename must not cost the new
call its log. On Android, `File.renameTo` is `rename(2)`, which replaces an older `live.log.1`. The logger is a
single-thread executor, so the rename runs before the new call's first line. Lines that the previous page logs late
still land in the new file, as they do today. The class comment (`:41`) and the section comment (`:156`) get
"(the previous call's: live.log.1)".

Alternatives, one line each:
- `onpause` only: a rejected `play()` would never be retried.
- Timer only: no event-driven resume, so it is slow if a hidden page throttles timers.
- Play the remote track through Web Audio with no element: Chromium doesn't feed it (`CodexSpeech.java:386`).
- Attach the WebView to a window so it isn't "hidden": needs an overlay window and a permission. That is feature-sized
  and only worth it if the resume fails (Risks 1).
- Add a peak getter to `TAP_JS` (diagnosis A-2): it changes code shared with read-aloud, which works, and `said()`
  measures the same path.
- `live.prev.log` (diagnosis): the brief says `live.log.1`.
- Also log the setting when the call starts: not asked for. The first change's "old" already shows the starting value.

## Steps
1. **Page: keep the voice playing, and log it** (`CodexLive.java`, `PAGE` only):
   - Page-level state: `pauses = 0`, `resuming = false`, `waking = false`, `resumeErr = ''`.
   - `keepPlaying()` as in Design 1.
   - In `start()`: `ctx.onstatechange`, and `page <visibilityState>` on the first context line.
   - In `ontrack`: `onpause` counts, logs while the count is 5 or less, then calls `keepPlaying()`; `onplaying` logs
     while the count is 5 or less.
   - In the 70 ms timer: `keepPlaying()`, the context resume, and `vMax = Math.max(vMax, v)`, with `vMax` declared
     beside `said`.
   - The `rtp 10 s` line additions, then `vMax = 0`.
   - Run the page syntax check from the Test plan.
2. **Setting change log** (`LiveWatch.java` `poll`, `LiveWatchTest.java`). In `aChangeOfTheSettingMovesTheVoice`,
   assert `f.log` holds `setting 2:Speaker -> watch`, `setting watch -> 2:Speaker`, `setting watch -> auto` and
   `setting auto -> 2:Speaker`. Also assert that a poll with no change adds no `setting` line: the number of
   `setting` lines equals the number of changes.
3. **`live.log.1`** (`CodexLive.java`: `logStartNew` and the two comments).
4. **Version** (the orchestrator's release step, `AGENTS.md` "Updating Buddy"): set `BuildInfo.java` to
   `2.3.11-fork.4`, with `VERSION_CODE` = max(fork 223, upstream) + 1.

## Test plan
Each check is labelled CI, local (before the PR) or after-switch. After-switch means the fork's APK must be on the
phone: `install_apk` refuses Buddy's own APK, so the device checks run after merge, tag and `update_buddy install=true`.
Until then, `verify.md` keeps those rows `pending` with `VERDICT: PARTIAL` (playbook `buddy-fork-verify`, items 4-5).

| Done-when | How it is proven |
|---|---|
| 1. Watch picked: heard on the watch. Phone or speaker picked: heard on the phone | **Local:** the page syntax check below (a typo in `PAGE` would silently break every call). **After-switch:** (a) Set `audio_device` = watch and start a phone live call. Say something that gets a spoken answer. Pass if the watch plays it, `live.log` `rtp` lines show `voice peak` > 0.02 and `audio playing` while the model speaks, every `audio element paused` is followed by `audio element playing`, and the watch's `adb logcat -d -s BuddyTalk` shows `read … received` > 0 with `audible at`. (b) The same with Phone (earpiece) and with Speaker: heard on the phone, with `level` > 0 in the `rtp` lines. |
| 2. A call started from the watch, incl. New chat, heard both ways | **After-switch:** on the watch, open New chat and start a call. Say a request. Pass if the chat shows the user's words (watch to phone), the watch plays the answer (phone to watch), and `live.log` shows `(watch call)`, `audio context running`, `ctx running` and `voice peak` > 0 in the `rtp` lines. If it fails, keep `live.log` and the watch's `logcat -s BuddyCall` for a new diagnosis. Don't guess at a fix. |
| 3. `live.log` shows element and context state and setting changes; previous log kept as `live.log.1` | **CI:** `LiveWatchTest` (step 2) for the setting lines. **After-switch:** during check 1(a), move the live bar's device chip once. Pass if `live.log` has `watch: setting watch -> …`, and the `rtp` lines show `audio …, n pauses; ctx …`. After check 2, `head -1 ~/.buddy/live.log.1` is the header of the call before it. |
| 4. CI green | **CI:** the PR's `build.yml` run (`testGithubDebugUnitTest`, both APKs, wear, helper tests, reproducibility). All checks pass. |

Page syntax check (local, Coder and Verifier). Save the script outside the repo, for example as
`/tmp/pagecheck.js`, and run `node /tmp/pagecheck.js "$PWD"` from the repo root. It must print `syntax ok` for both
pages. It turns the Java string concatenations into JS strings and parses each page's script without running it. It
passes on the current code.

```js
const fs = require('fs');
const expr = (java, name) => { const m = java.match(new RegExp('\\b' + name + '\\s*=\\s*([\\s\\S]*?");\\n')); if (!m) throw new Error('no ' + name); return m[1]; };
const dir = process.argv[2] + '/app/src/main/java/app/buddy/assistant/';
const sp = fs.readFileSync(dir + 'CodexSpeech.java', 'utf8'), lv = fs.readFileSync(dir + 'CodexLive.java', 'utf8');
const TAP_JS = eval(expr(sp, 'TAP_JS'));
for (const [n, j] of [['CodexLive', lv], ['CodexSpeech', sp]]) {
  const page = eval(expr(j, 'PAGE').replace(/CodexSpeech\.TAP_JS|\bTAP_JS\b/g, 'TAP_JS'));
  new Function(page.match(/<script>([\s\S]*)<\/script>/)[1]); // throws SyntaxError on a typo
  console.log(n + ' PAGE: syntax ok');
}
```

## Risks
1. **The pause's cause is unproven.** If Chromium refuses `play()` for a hidden WebView, the resume fails. `live.log`
   then shows `audio element resume failed: <reason>` and `audio paused` in every `rtp` line. The next step would be
   feature-sized (attach the WebView to a window), and that goes to Nicholas, not into this fix.
2. **Something keeps pausing the element.** The voice would stutter. The logs are capped at 5 pauses, and the count in
   the `rtp` line shows the rate.
3. **B's cause is unproven** (no logs survived). The fix covers both likely causes. If B still fails, `live.log`,
   `live.log.1` and the new state fields hold the evidence.
4. **A quiet stretch of about 3 min before the voice, in a Watch call,** can still break LiveWatch's opened-ahead
   reading and move the voice to the phone (018 behaviour, out of scope). If verification hits it, record it and
   propose a separate fix.
5. **Only after-switch can prove items 1-3 on the device**, so a miss means a fix-forward release (playbook
   `buddy-fork-verify`).

## Open questions
None blocking.
