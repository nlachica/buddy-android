# Plan: 026-watch-retry
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal

When a phone live call's voice falls back from the watch to the phone (the watch app was killed or restarted, the
link dropped, the watch refused), the call should go back to the watch by itself. Today `LiveWatch.Call.fallback()`
keeps the voice on the phone for the rest of the call unless the audio device setting changes (feature 018's recorded
"no retry" default), so under Auto it never comes back (diagnosis.md §2, the W2 log). Fix: retry the watch up to 3
times per call. Each try comes at least 5 s after the drop (or after the previous failed retry), and only in a pause of
≥1 s in the call's voice. Added mid-run: after a watch drop under Auto, the phone side must be the loudspeaker (or a
connected headset), not the earpiece it used in W2.

Done when:
1. After a fallback, the call is back on the watch within ~10 s of the watch being reachable, with at most 3 tries per
   call and never mid-utterance.
2. An unreachable watch never silences the phone voice.
3. Unit tests cover the retry timing, the cap and the pause gating, and 018's decisions record the change.
4. CI is green.
5. After a watch drop with Auto and no headset, the voice plays on the speakerphone, not the earpiece (a connected
   headset when there is one), and a test covers it.

Added mid-run, a call started from the watch hears nothing (diagnosis-watchcall.md, call B):
6. Counters at both ends, logged. The watch's `Call.mic` logs its source once, then every 5 s: the frames it read,
   sent and gated, their peak, and whether Android silenced the recorder. The phone logs every 10 s what
   `CodexLive.push` got from the watch: frames, peak, and frames dropped because the page didn't pull them.
7. A call started from the watch hears the user. Speaking on the watch gives a user turn and a spoken reply (device
   check D4b). This is the acceptance gate for the silence fix, and it stays open until it passes (see stage 2).
8. The watch's call status is the phone's status string, from the one shared status code, with no new status logic.
9. live.log keeps the current call plus 5 rotated logs (live.log.1 to live.log.5).

Non-goals:
- The cause of the W2 drop itself (the phone agent ran `adb install -r` of the watch app mid-call). That is a process
  note in diagnosis.md, not code.
- Following headset or other route changes during a call (018's decision stays: start plus setting changes, plus
  these retries).
- Auto calls where the watch never took the voice. They keep Android's routing, as 018 decided. The brief's addition is
  about the fallback after a drop.
- Watch changes beyond `Call.mic`: its counters (stage 1), and the source swap if the counters point there (stage 2).
  The installed watch app already answers every case the retry needs.
- Watch-specific status logic, and any change to the shared status order (Nicholas: "no new status logic").
- The page's `rtp 10 s` report. Its `mic peak` is a snapshot (Design). For watch calls, the new phone line is the
  measure.
- Retrying calls started from the watch (007's Remote). They never reach `LiveWatch`.
- Phone read-aloud (012). It is off during a live call anyway (`ChatHub.java:1269`, `Bubble.java:2511`).

## Design

Three parts. The route fix is in `AudioRoute` and is independent of the retry. The retry logic all goes into
`LiveWatch.Call`, which is pure and unit-tested through the `Out` fake. The page feeds it the voice's loudness through
the fork's existing `toWatch` bridge call. The watch-started call (last section) adds counters, keeps more logs, and
leaves the shared status alone. Its silence fix is picked from the counters in a second stage.

### Auto's fallback route (done-when 5)

**Diagnosis (plan-review F1).** In W2 (Auto, no headset), the voice was on the watch from 0.52 s, and after the drop at
63.68 s it played from the earpiece. The call's log has no `route:` line at all. `AudioRoute.live()` logs every device
it applies or refuses, every slip and "no safe device". At call start `applied` is null, because the first
`reconcile()` runs `drop()` while `noEarpiece("")` is still false. So any `live()` after the attach would have logged:
`live()` never ran. `reconcile()` (AudioRoute.java:277-280) takes the live branch only when
`!other && mode is NORMAL or IN_COMMUNICATION && LiveWatch.noEarpiece(saved)`. The three conditions:
- `noEarpiece("")` was true: `took` is kept after a fallback (LiveWatchTest:148-152).
- The mode passes in a Buddy call on this phone. 018's device log (2.3.11-fork.3) applied `route: 2:SM-G990W` 0.3 s
  after `playing there`, and again after `connected`.
- That leaves `other`. It is a latch, set by the first `use()` of an AudioRoute session when the phone isn't in NORMAL
  mode, and cleared only by `finish()` (AudioRoute.java:238-241, 420). A session that began while something else held
  the mode (for example a previous call's WebView still letting go) keeps the live rule off for the whole call.
  Auto's key is `""`, so it falls through to `drop()`. In communication mode that is Android's routing: the earpiece.

The log doesn't record `other`, so device check D1 confirms the cause.

**Fix.** Make the live branch independent of `other`. A new pure
`static boolean liveRule(int mode, boolean noEarpiece)` returns `noEarpiece && (mode == MODE_NORMAL || mode ==
MODE_IN_COMMUNICATION)`, and `reconcile()` uses it in place of today's condition. This is safe for three reasons.
`noEarpiece` is only true during Buddy's own phone live call, whose WebView holds the mode. Ringing and a phone call are
still excluded by the mode check. `other` keeps guarding the device branch, its original job. `live()`'s log line gets
` (session began in another mode)` when `other` is set, so D1 shows whether the latch was the cause. Nothing else
changes. `liveDevice` already picks a headset's call device, else the speaker, never the earpiece. While the voice is
on the watch, the route already is that device (018), so a drop needs no route change.
Alternatives: re-read `other` at each call's start (`use()` can't tell a call start from dictation, and a WebView still
letting go would set it again); end the latch in `afterLive` (it only runs after a call, not before one).

### The retry

**The pause signal (plan-review F2).** The page's 70 ms timer already computes `v`, the voice's peak this tick, and
already calls `Buddy.toWatch()` each tick in a phone call (CodexLive.java:995-998, 017's fork lines). It now passes the
peak: `Buddy.toWatch(v)`.
- The bridge becomes `toWatch(float voicePeak)`, and calls `LiveWatch.toWatch(voicePeak)`. That calls
  `k.voicePeak(p); return k.toWatch();`.
- `Call.voicePeak(float p)` (synchronized, bridge thread) stamps `loudAt = out.now()` when `p > Gate.AUDIBLE`.
- `Gate.AUDIBLE = 0.01` is a new constant that `Gate.loud()` now uses as well (`> AUDIBLE * 32768`). One constant
  therefore decides both what starts an utterance and what breaks a pause.
- `quiet()` is `out.now() - loudAt >= QUIET_MS` (1 s). `loudAt` is never reset: an old stamp only reads as quiet, and
  the page stamps every tick.

Why not the page's own `voice` flag (`v > 0.02`) through `lastAudible`? Its threshold is upstream's, and it also drives
Speaking… and the `voice audible/quiet` log lines. Speech between 0.01 and 0.02 would read as a pause. A JS comparison
also can't be unit-tested. This way the whole calculation runs in Call, on the Fake's clock, `Out` needs no new method,
and CodexLive gets no accessor.
`LiveWatch.pcm()` can't serve as the signal: the page's tap forwards only while `fwd` (CodexLive.java:910), so it gets
nothing once the voice is back on the phone.

**New state in `Call`.** Constants `RETRY_MS = 5_000`, `RETRIES = 3`, `QUIET_MS = 1_000`. Fields:
- `int retries`: retries started in this call. `begin()` resets it to 0. Nothing else resets it.
- `long retryAt`: when the next retry is due, 0 when none is armed.
- `Object retryTurn`: the turn a retry created, so its "none found" and its attach can be recognised. Stale turns fail
  the `turn == t` checks as today.
- `Object pending`: a retry turn whose channel is open while the voice waits for a pause before moving.
- `long loudAt`: as above.

`clear()` sets `retryAt = 0` and `retryTurn = pending = null`.

**Arming.** `arm()` is `retryAt = retries < RETRIES ? out.now() + RETRY_MS : 0`. It is called:
- at the end of `fallback()`. Every fallback arms: refused, broken, stopped by the watch, or the 30-s give-up (through
  `giveUp` → `fallback`). This includes a refusal before attach, since the voice was meant for the watch then too;
- in `found()` when `n == null && t == retryTurn`: a retry that found no watch arms the next one, so the 3 tries are
  real tries and a watch that comes back between tries is caught.

It is not called by `stop()` (the setting moved away), or by a "none found" at call start or after a setting change.
Auto with no watch around therefore stays as today: one find, then the phone.

**Firing (in `poll()`, after the setting-change branch, as `else if`s).**
1. A setting change sets `retryAt = 0` before its `decide()`. The change is itself the fresh decision (018's rule), and
   its attempt is not a retry, isn't counted and isn't pause-gated.
2. `pending != null && pending == turn && quiet()`: `move()` (below).
3. `retryAt != 0 && turn == null && drain == null && out.now() >= retryAt && quiet() && out.watchWins()`:
   `retryAt = 0; retries++; out.log("retry " + retries + " (quiet " + (now - loudAt) + " ms)"); attempt(true)`.
   `watchWins()` comes last: it is the expensive check, and it is reached only in the armed window. While the watch
   doesn't win (Auto with a headset now), the retry stays armed and nothing is used up. It fires when the watch wins
   again.

`attempt(boolean retry)` sets `turn` and then, if `retry`, `retryTurn = turn`, before `out.find(...)`. The fake's find
answers synchronously, and `found` must already see the retry turn. `decide()` calls `attempt(false)`.

**Moving only in a pause.** `attached(t)` keeps its stale checks (`turn != t || watch`). If `t == retryTurn &&
!quiet()`, it sets `pending = t`, logs `waiting for a pause` and returns. Otherwise it calls `move()`.
`move()` is today's body plus `pending = null`: `gate.reset(); watch = took = true`, then a log line. A retry's move
logs `playing there (quiet Y ms)`, any other move `playing there` as today.

While pending, `watch` is false. So `pcm()` ignores the voice, `toWatch()` is 0, the phone keeps playing and its mic is
open. The channel just idles, as 018's readings opened ahead already do between turns. A refusal or break of the
pending reading goes through the existing `cur` branch of `answered()` → `fallback()` → `arm()`.

The pause is checked twice, at the retry's start and at the switch, because find plus open can take up to 3 s + 10 s
(`WatchVoiceLink.FIND_MS`, `OPEN_MS`), and the voice can resume in between. Only the check at the switch makes "never
mid-utterance" hold. Call-start and setting-change attaches stay immediate, as 018's tests expect
(`aChangeOfTheSettingMovesTheVoice`).

**Why it can't silence the phone.** The phone is muted only by `watch = true`, which only `move()` sets, after the
channel is attached and the voice is quiet. "None found" never opens a channel. An open that doesn't attach ends as
`refused` (the 10-s open timeout, `ReadStream.openTimedOut`) → `fallback` with `fed == false`, so the mic isn't held
either.

**Timing against the done-when.** The poll runs every 500 ms. A retry fires 5.0-5.5 s after the drop or the failed
retry, plus the wait for a pause. With fixed 5 s spacing, a watch that becomes reachable while tries remain is caught
by the next retry within ~5.5 s, plus find/open (usually under 1 s). This meets "~10 s once reachable".

Alternatives considered:
- Exponential backoff (5, 10, 20 s): it covers longer outages but breaks "~10 s once reachable" for the third try.
- Resetting the count after a successful return: the brief says "at most 3 tries per call".
- Retrying whenever the watch wins and the voice is on the phone (also at call start): it adds finds to every Auto
  call without a watch. The brief scopes the retry to after a fallback.
- Gating only the retry's start (diagnosis' version): it leaves the find/open window mid-utterance.
- Changing the page's 0.02 to 0.01 (review F2's suggestion): it changes upstream's Speaking… and log lines, and it
  can't be tested. The peak through `toWatch` can.

### The watch-started call (done-when 6-9)

**What the code says about call B (plan-review round 2).** It rules out less than the diagnosis assumed:
- *The watch side hasn't changed since it worked.* `Call`, `Speaker` and `WatchCall` are unchanged since v2.3.9-fork.4,
  and with that build Nicholas made a watch call and spoke with Buddy (007 verify.md, 2026-11-15). That build also
  opened VOICE_COMMUNICATION first. So the source alone doesn't explain call B. Since then, the phone page changed (017's
  tap and route; 020 replaced the awaited `ctx.resume()` with `wake()`, which doesn't wait), upstream 2.3.12 was
  merged, and the watch's firmware may have updated.
- *The half-duplex gate is unlikely.* `Speaker.audibleUntil` only moves when a block above 0.01 reaches the track.
  Call B's `voice peak` (a 10 s maximum, `vMax`) read 0.000 in every report, so the voice never played and the gate
  never closed.
- *"mic peak ~0" doesn't prove silence.* The page's `mic peak` is `heard()` at report time: one 21 ms window every
  10 s. Readings of 0.001-0.003 look like a noise floor (frames arriving), not the digital zeros `ownTrack` outputs when
  nothing arrives. The 0.102 at +220 s shows sound did arrive at least once.

So the code can't say where the speech is lost. It could be the capture, the link, the page not pulling (a suspended
context stalls `ownTrack`'s processor), the page-to-WebRTC leg, or Nicholas not speaking at those moments. The brief's
item (1), the counters, is what settles it. A blind fix now could change nothing, and it would drop the source a working
call used.

**Stage 1: counters, shipped with the retry and route work.**
- *Watch, `Call.mic`.* Once: `call <id> mic: VOICE_COMMUNICATION` (or `MIC`), the source that opened. Then every 5 s:
  `call <id> mic 5 s: read R, sent S, gated G, peak P, silenced yes|no`.
  - R, S and G count 40 ms reads with n > 0. A read is either gated or sent.
  - P is the loudest sample read, as a fraction of full scale with 3 decimals (the page's scale).
  - silenced comes from `r.getActiveRecordingConfiguration().isClientSilenced()` (API 29; the watch's floor is 30).
    The diagnosis only saw "not silenced" at open, and silencing can start later.
  - `Speaker.peak` becomes package-private and is reused.
- *Phone, `CodexLive.push`.* Under the `chunks` lock it counts frames, the peak (`LiveWatch.Gate.peak`, new; `loud()`
  uses it), and the frames its 5 s cap throws away because the page didn't pull them. The counters reset with the queue
  when a watch call starts (CodexLive.java:217). Every 10 s while `remote != null`, the watchdog (every 250 ms, main
  thread) logs `watch in 10 s: N frames, peak P, D dropped` and resets them. The watchdog rather than `push()` itself,
  so that "nothing arrived" is an explicit `0 frames` line, not a missing one.
- Together, the two lines show the loss end to end. The watch reads (peak, silenced), gates or sends. The phone
  receives about 2 × the watch's 5 s `sent` per line, and the page pulls (`dropped` 0). The page's report adds `ctx`
  state, and the `page dc` lines show whether a user turn came.

**Stage 2: the silence fix, chosen from device check D4a's counters.** This table is decided now. Each row reads the
lines from the seconds Nicholas spoke:

| Watch `mic 5 s` | Phone `watch in 10 s` | Lost at | Stage 2 |
|---|---|---|---|
| peak < 0.01, silenced no | (anything) | the capture: the source's processing | `Call.mic` tries `MIC` before `VOICE_COMMUNICATION`, a one-line swap. The half-duplex gate is the echo guard (007 chose it because the Watch4's AEC was unknown), so the voice-call processing adds nothing. Append a 007 decisions entry. |
| silenced yes | (anything) | another app holds the mic | back to diagnosis: which app (`dumpsys audio` recording configs on the watch) |
| gated ≈ read while the voice is quiet | (anything) | the gate stuck closed | back to diagnosis: `Speaker.audibleUntil` |
| peak ≥ 0.05, sent ≈ read | frames ≪ 2 × sent | the link | back to diagnosis: `WatchCall.read`, the channel |
| (fine) | frames fine, dropped > 0 | the page isn't pulling | back to diagnosis: the context's state, 020's unawaited resume |
| (fine) | frames fine, peak ≥ 0.05, dropped 0, no user turn | page to WebRTC | back to diagnosis: `ownTrack`, `sendTrack` |
| (fine) | (fine), a user turn and a reply | nowhere: it works | none. D4a counts as D4b |

Only the first row is a planned code change. The other rows go back to the Researcher with both logs (the
orchestrator runs it), and done-when 7 stays open. Stage 1 alone does not complete the brief.

Alternatives: swap to `MIC` now, unproven (above); set MODE_IN_COMMUNICATION on the watch for the call (it reroutes
the watch's audio and volume stream, more side effects than a source); drop the gate (it is 007's echo guard, and
unlikely here); counters inside the watch's status JSON (the logcat line is enough to diagnose).

**The status: no change (brief item 3, Nicholas's clarifications).** `CodexLive.status()` computes one string `t`, and
that same `t` goes to the phone's live bar (`u.onStatus(t)`) and to the watch: `r.status(t)` → `WatchCall.status` →
wear `Call.onPhone` sets `Call.status` → `CallActivity`. Nothing in that path is watch-specific. The order is:
Connecting… > Speaking… > Listening… (words heard, not sent yet) > Working on it… (the chat runs) > Thinking… >
Listening…. So the phone shows "Listening…" while the chat works exactly when it is hearing the user. In call B no
words arrived, and the chat's run (busy from before the call) kept "Working on it…" on both ends. The phone, had it
been on screen, showed the same string. Nicholas's latest, firm, clarification ("reuse the phone's existing
call-status code … send that same string to the watch; no new status logic") describes today's code. So the code
doesn't change. Once the audio arrives (done-when 7), the watch says "Listening…" whenever the phone does. Taken
literally (user silent, chat busy, "Listening…"), the brief's item (3) would need the shared order changed for both
UIs. That contradicts "no new status logic", so it is left to Nicholas as a non-blocking question (Open questions).

**Five rotated logs.** `logStartNew()` renames `live.log` to `live.log.1` (020). It now shifts first: `live.log.4` →
`.5`, …, `.1` → `.2`, then `live.log` → `.1`, through a pure `static void rotate(File log, int keep)` with
`LOGS_KEPT = 5`. On Android, `renameTo` replaces the target, so `.5` drops the oldest. A failed rename is ignored, as
today ("a failed rename must not cost this call its log"). Alternative: timestamped names (they need pruning and are
harder to name in a check).

## Steps

1. **Auto's fallback route** (`AudioRoute.java`, `AudioRouteTest.java`, `LiveWatchTest.java`).
   - Add `liveRule(int mode, boolean noEarpiece)` with a javadoc (017's rule while Buddy's call holds the mode, not
     gated on the session latch `other`, 026). Use it in `reconcile()` in place of the current condition.
   - In `live()`'s apply lambda, append `(other ? " (session began in another mode)" : "")` to the
     `route: … applied/refused` line.
   - Tests: `AudioRouteTest.liveRule` and T7 (Test plan).
2. **The pause seam, no behaviour change** (`LiveWatch.java`, `CodexLive.java`).
   - `Gate.AUDIBLE = 0.01`, used by `loud()`.
   - `QUIET_MS`, the field `loudAt`, `Call.voicePeak(float)` and `Call.quiet()`.
   - `static int toWatch(float voicePeak)` replaces the no-arg static. `Call.toWatch()` stays as it is; the tests use
     it.
   - `CodexLive`: the bridge becomes `public int toWatch(float voicePeak)`, and its javadoc gains "voicePeak: the
     voice's peak this tick (LiveWatch's pause, 026)". The page line 998 becomes `Buddy.toWatch(v)`.
   - All existing tests still pass.
3. **Retry after a fallback** (`LiveWatch.java`, `LiveWatchTest.java`).
   - Add `RETRY_MS`, `RETRIES`, the fields `retries`, `retryAt` and `retryTurn`, and `arm()`. Reset them in
     `begin()`/`clear()` as in Design. Call `arm()` from `fallback()` and from `found()`'s null branch when
     `t == retryTurn`. Add `attempt(boolean)`. In `poll()`, clear `retryAt` on a setting change and add the retry
     branch (Design, firing 3).
   - Fix `fallback()`'s javadoc: "the phone's own route; retried up to RETRIES times per call in a pause (026), and on
     a setting change".
   - Tests: update `refusedAfterAttachFallsBackAndNothingIsFoundAgainWithTheSameSetting`. Rename it to
     `…AndNothingIsFoundAgainBeforeTheRetry`, with `advance(RETRY_MS - 1)` and `finds == 1`. Update
     `brokenAfterAttachFallsBackToo` (`advance(RETRY_MS - 1)`). Change the comment "no retry in this call" in
     `aLocalBrokenWithNoAnswerKeepsTheMicClosedForTheFailTail` to "no retry yet". Add T1, T3, T4, T5 and T6 (Test
     plan).
4. **Move only in a pause** (`LiveWatch.java`, `LiveWatchTest.java`). Add the `pending` field, `move()`, the gate in
   `attached()`, and the pending branch in `poll()` (Design, firing 2). Add T2.
5. **Docs.**
   - `docs/features/018-live-voice-watch/decisions.md`: append two entries.
     - "2026-11-18 - A fallback retries the watch up to 3 times per call, in a pause (replaces the 'no retry
       otherwise' part of the 2026-11-17 'LiveWatch decides at call start…' entry; feature 026)". It cites the W2 log
       (026 diagnosis.md) and gives the rules: 5 s after the drop or the failed retry; a ≥1 s pause (no voice peak
       above 0.01) at the start and at the switch; at most 3 per call; `stop()` never arms; a setting change cancels
       a pending retry.
     - "2026-11-18 - The live route rule doesn't depend on the voice session's `other` latch (feature 026)". It cites
       W2's missing `route:` lines.
     The file is append-only: earlier entries stay untouched.
   - `README.md:102` (Live voice on the watch): after "…never the earpiece.", add one sentence: "When the watch can play
     again, the voice goes back to it at the next pause (up to 3 tries per call)." The earpiece sentence already states
     done-when 5; the code now keeps it.
6. **Five rotated logs** (`CodexLive.java`, new `CodexLiveTest.java`).
   - Add `LOGS_KEPT = 5` and `static void rotate(File log, int keep)` (Design). `logStartNew()` calls it in place of
     its single rename.
   - Update the class javadoc (line 42) and the log section's comment (line 156) to "the five before it: live.log.1
     (newest) to live.log.5".
   - Test T8.
7. **The phone's watch-in counter** (`LiveWatch.java`, `CodexLive.java`, `LiveWatchTest.java`).
   - `Gate.peak(byte[])` returns the loudest sample's magnitude. `loud()` becomes `peak(p) > AUDIBLE * 32768`, using
     step 2's constant, so it behaves as before.
   - `push()`: the frame count, the peak (computed before the lock) and the dropped count, as in Design. They reset with
     the queue at a watch call's start.
   - The watchdog logs `watch in 10 s: …` every 10 s while `remote != null`. That is the one line added to upstream's
     code in this feature; `push()` is 007's.
   - Test T9.
8. **The watch's mic counters** (`wear/src/main/java/app/buddy/assistant/wear/Call.java`, `Speaker.java`).
   - `Speaker.peak` becomes package-private.
   - `mic()` keeps the source that opened and logs it once, then counts and logs the 5 s line (Design) with
     `Log.i(TAG, …)`.
   - No behaviour change: the source order and the gate stay. D4a reads the lines.
9. **Run the tests.** Run AudioRouteTest, LiveWatchTest, CodexLiveTest and the full github unit-test set with the local
   javac/JUnit harness (as 018 and 023 did; it is not Gradle). Then push the branch, and CI decides:
   `./gradlew testGithubDebugUnitTest … :wear:testDebugUnitTest`, with the wear module compiled by `:wear:assembleRelease`.
10. **Stage 2, the silence fix** (after stage 1's release and device check D4a; a follow-up commit, review and release in
    this feature folder).
    - Read D4a's two logs against the Design table and record the row in decisions.md.
    - Row 1: swap the two entries of `Call.mic`'s source array (`MIC` first), and append a 007 decisions entry. Then
      run D4b.
    - Any other row: stop. Hand the logs to the orchestrator for the Researcher. Done-when 7 stays open.

## Test plan

New `LiveWatchTest` cases start from `onWatch()`. "A drop" is `utterance(); r.events.broken(true)` on the draining
reading at `now = 0`, which is the W2 case ("stopped by the watch"). `RETRY_MS`, `QUIET_MS` and `POLL_MS` come from
LiveWatch. "Soft voice" means `c.voicePeak(0.015f); f.advance(70)` repeated: the peak sits between the gate's 0.01 and
the page's 0.02. No test calls `voicePeak` unless it says so, so `loudAt` stays 0 and the voice counts as quiet from
1 s.

| # | Test | Proves |
|---|---|---|
| T1 | `aDropIsRetriedFiveSecondsLaterInAPauseAndTheVoiceGoesBackToTheWatch`: a drop, then `advance(RETRY_MS - 1)` → `finds == 1`, `opened == 1`. `advance(1)` → `finds == 2`, the log has `retry 1 (quiet`, and a new reading is open. Run its `attached` → `toWatch() == 1`, the log has `playing there (quiet`, and `pcm(LOUD)` lands in the new reading. | Done-when 1 (timing, return), 3 (timing) |
| T2 | `aRetryWaitsForAPauseAndMovesTheVoiceOnlyInOne`: a drop, then soft voice until `now = 10_000` → `finds == 1`, no `retry` line. Then `c.voicePeak(0.005f)` ticks (below 0.01) for `QUIET_MS + POLL_MS` → `retry 1`, and its quiet number is ≥ 1000. Soft voice again, then run `attached` → `toWatch() == 0`, the log has `waiting for a pause`, and `pcm(LOUD)` → the new reading got nothing. Soft voice for 2 s → still `toWatch() == 0`. Then `advance(QUIET_MS + POLL_MS)` with no peak → `toWatch() == 1`, and the log has `playing there (quiet` with a number ≥ 1000. | Done-when 1 (never mid-utterance), 3 (pause gating), review F2 |
| T3 | `aWatchThatComesBackLaterIsFoundByTheNextRetry`: `node = null`, a drop, `advance(RETRY_MS)` → `retry 1`, `none found: phone`. `advance(1000); node = "n1"`. `advance(RETRY_MS - 1001)` → `finds == 2`. `advance(1)` → `finds == 3`, `retry 2`, attach → `toWatch() == 1` (4 s after it was reachable). | Done-when 1 (~10 s once reachable), 3 (spacing after a failed retry) |
| T4 | `atMostThreeRetriesPerCallWhateverTheirOutcome`: a drop. Retry 1 finds none (`node = null`). Retry 2 opens, and its reading is `refused()` before attach. Retry 3 attaches (voice on the watch), then `broken(true)` on it. `advance(60_000)` → `finds == 4`, no `retry 4` line, `toWatch() == 0`. Each retry is ≥ `RETRY_MS` after the failure before it (assert `finds` at `-1` and `0`). Then `end(); begin()`, attach, a drop, `advance(RETRY_MS)` → `retry 1` again (the count is per call). | Done-when 1 (cap), 3 (cap) |
| T5 | `anUnreachableWatchNeverSilencesThePhone`: `node = null`, a drop, `advance(TAIL)`. Then 60 × `advance(POLL_MS)`, each followed by `assertEquals(0, c.toWatch())` (3 retries happen in that time). A second call: a drop, and the retry opens a reading that never attaches → `toWatch() == 0`, and `pcm(LOUD)` → that reading got nothing. `refused()` (the open timeout) → `toWatch() == 0`. | Done-when 2 |
| T6 | `aRetryWaitsWhileTheWatchDoesNotWinAndASettingChangeCancelsIt`: the call starts on Auto (`saved = ""`). (a) A drop, then `wins = false` with `saved` unchanged (a headset came), `advance(10_000)` → `finds == 1`, no `retry` line. `wins = true; advance(POLL_MS)` → `retry 1`. (b) Attach, then a drop. `saved = "2:Speaker", wins = false; advance(POLL_MS)`. `saved = "", wins = true`, then `c.voicePeak(0.5f)`, then `advance(POLL_MS)` → a find with no `retry 2` line, and its `attached` → `toWatch() == 1` (setting-change attaches aren't gated). Then `advance(10_000)` → still no `retry 2`. | Done-when 3 (gating), 018 rules kept |
| T7 | `anAutoCallThatLosesTheWatchUsesTheSpeakerNotTheEarpiece`: a drop. Then `toWatch() & 1 == 0` and `AudioRoute.liveRule(MODE_IN_COMMUNICATION, c.noEarpiece(""))`. `AudioRoute.liveDevice({EARPIECE, SPEAKER, SCO}, {"Earpiece", "Speaker", "Watch4"}, {SPEAKER}, {"Speaker"}, ∅) == 1`: the speaker; the watch's SCO isn't a headset. With buds connected, `liveDevice({EARPIECE, SPEAKER, SCO, SCO}, {…, "Watch4", "Buds2"}, {SPEAKER, A2DP}, {"Speaker", "Buds2"}, ∅) == 3`: the buds' SCO. | Done-when 5 (headset preference kept) |
| T8 | `CodexLiveTest.liveLogsRotateKeepingFive`: in a temp dir, `rotate(log, LOGS_KEPT)` with no files throws nothing. Then 7 times: `rotate`, then write "call k" to `live.log`. After that, `live.log` holds call 7, `live.log.1` call 6, …, `live.log.5` call 2. There is no `live.log.6`, and call 1 is gone. | Done-when 9 (retention past five calls) |
| T9 | `LiveWatchTest.gatePeakIsTheLoudestSample`: the peak of samples {0, 500, -1200, 30} is 1200, of {-32768} it is 32768, of an empty array 0. `loud` is still true for a 500 sample (the existing case) and false for 300. | Done-when 6 (the phone's number), the gate unchanged |

`AudioRouteTest.liveRule`: `(MODE_NORMAL, true)` and `(MODE_IN_COMMUNICATION, true)` are true. `(MODE_IN_CALL, true)`,
`(MODE_RINGTONE, true)` and `(MODE_IN_COMMUNICATION, false)` are false. The rule takes no session state, so a session
that began in another mode can't switch it off (done-when 5, the W2 cause).

Changed 018 tests: the two "nothing found again" cases now assert no find before `RETRY_MS`, the behaviour this
feature overrules on purpose. All other existing cases pass unchanged. I traced each: `staleAnswersAreIgnored`
cancels the armed retry through its setting change, and the remaining cases either don't fall back or stop advancing
the clock before 5 s.

Device checks run after release (the playbook's after-switch row: phone code reaches the phone only through
`update_buddy`). Logs are `~/.buddy/live.log`. The route is read with `dumpsys audio`, looking for the communication
device line, through `phone_shell`.
- **D1 (done-when 5):** Auto, no headset, the watch on the wrist.
  1. Start a phone live call a few seconds after ending a watch call (the suspected latch trigger).
  2. While the voice is on the watch, live.log must have `route: 2:<phone> applied` after `watch: playing there`. The
     `(session began in another mode)` suffix confirms the diagnosis.
  3. Turn the watch's Bluetooth off mid-reply: after `watch: fallback: …` the voice is heard from the loudspeaker,
     and `dumpsys audio` shows the speaker, never the earpiece.
  4. Variant: connect the buds mid-call while the voice is on the watch, then the drop → the voice in the buds, and
     `route: 7:<buds> applied`.
- **D2 (done-when 1):** a phone live call with Auto and the voice on the watch. Trigger a drop with
  `adb -s <watch> install -r buddy-watch.apk` mid-reply (the W2 trigger), and separately with the watch's Bluetooth
  and Wi-Fi off for ~5 s, then back on. live.log must show:
  1. `watch: fallback: …`;
  2. then `watch: retry n (quiet X ms)` ≥ 5 s later, with X ≥ 1000;
  3. then `watch: playing there (quiet Y ms)` with Y ≥ 1000, within ~10 s of the watch being back.

  Speech is then heard from the watch. X and Y are LiveWatch's own measure: the time since the last 70 ms tick with a
  voice peak above 0.01. The `voice audible`/`voice quiet` lines (upstream's 0.02 status meter) are not this check.
- **D3 (done-when 2):** the watch off after a drop. The reply is heard on the phone throughout, the log shows 3
  `retry` lines each followed by `none found: phone`, and no `page mic closed` from them.

D4 and D5 need the watch app from the same release (`adb -s <watch> install -r buddy-watch.apk`, never during a call).
The watch's lines come from `adb -s <watch> logcat -d -s BuddyCall`. The phone's come from the watch call's live.log (or
`live.log.n`).
- **D4a (done-when 6; stage 1).** Start a call from the watch's talk screen in a chat that isn't running. At
  "Listening…", ask a short question. Wait 15 s, ask again, then End. Note the times he spoke.
  1. Watch: one `call <id> mic: <source>` line, then a `mic 5 s` line every 5 s, with read ≈ 125. While he spoke:
     peak, gated, sent, silenced.
  2. Phone: a `watch in 10 s` line every 10 s, with frames ≈ 2 × the watch's `sent` for the same seconds, plus
     `dropped`, its peak, and the page report's `ctx`.
  3. Whether a user turn came (`page dc … user: …`) and the voice answered.

  D4a passes as a counter check when both ends' lines are there and their frame counts agree within one line's worth.
  Its numbers select the stage 2 row (Design table).
- **D4b (done-when 7; the acceptance gate).** After stage 2, or D4a itself if D4a already had a user turn and a reply.
  A call started from the watch: what he says shows as a user turn with his words, and the voice answers on the watch,
  for two exchanges in one call. The `mic 5 s` lines show `gated` rising only while the voice plays (half-duplex as
  designed). Until D4b passes, done-when 7 is open and the feature isn't closed.
- **D5 (done-when 8, 9).** During D4b, the phone unlocked with the call's chat on screen (the live bar) beside the watch:
  1. Idle, chat not running: both show "Listening…".
  2. Ask something that makes the chat work (e.g. "what's on my calendar today"). While it works and he is silent,
     both show the same string (today "Working on it…"). While he speaks during the run, both show "Listening…". While
     the voice answers, both show "Speaking…".
  3. Every `status:` line in live.log appears on the watch within ~1 s. Pass: the two never differ beyond that delay.
  4. After at least 6 calls since the release (D1-D5 together), `ls ~/.buddy/live.log*` lists `live.log` and
     `live.log.1` to `live.log.5`, and the oldest call's file is gone.

Checks by done-when item:
1. T1-T4 in CI, D2 on the device.
2. T5 in CI, D3 on the device.
3. T1-T6 in CI, plus the 018 decisions.md entries from step 5.
4. CI green: the PR's `build.yml` run (helper tests, `testGithubDebugUnitTest`, assembles, wear tests).
5. T7 and `AudioRouteTest.liveRule` in CI, D1 on the device.
6. T9 in CI, D4a on the device (both ends' lines).
7. D4b on the device, after stage 2. It stays open until then.
8. D5 on the device. No code changes, so code review only confirms that the one `t` reaches both.
9. T8 in CI, D5 step 4 on the device.

## Risks

1. **The meter's sampling.** The peak is read once per 70 ms tick, over the analyser's last 1024 samples (~21 ms at
   48 kHz). A pause can therefore look up to one tick longer than it is, and an isolated click between ticks goes
   unseen. Real speech peaks above 0.01 in nearly every 21 ms window, and 1 s holds ~14 ticks, so a word can't hide in
   the gaps. The threshold is now the gate's.
2. **A meter that reads nothing.** If the page's audio element stalls again (020's bug), the peak reads 0 and every
   moment looks quiet, so the switch could land mid-word. The phone voice wasn't audible in that state either, and 020
   keeps the element playing.
3. **Long outages exhaust the tries.** Three tries cover roughly 15-45 s after the drop, depending on open timeouts.
   A watch that is gone longer (a reboot, out of range) stays on the phone until the setting changes, as today.
4. **A watch talk mid-call.** The watch's own mic use hushes the reading (code 0), and retries during the talk are
   refused (code 1). They can use up the 3 tries, so after a long watch talk the voice stays on the phone. The retry
   never interrupts the talk, because the watch refuses while busy.
5. **Setup sample during a call** (018 Risk 9). The sample replaces the call's reading. A retry 5 s later, in a pause
   of the call's voice, replaces the sample in turn (newest reading wins). This is rare and bounded.
6. **The `other` diagnosis is inferred.** If D1 shows no `route:` line after `playing there` even with the fix, the
   live branch is skipped for another reason, for example an exception swallowed by `reconcile()`'s callers. D1 then
   fails done-when 5, and the feature goes back to diagnosis.
7. **Another app's call under Buddy's.** If another app's VoIP call (communication mode) was already on when Buddy's
   phone live call started, the live rule now sets Buddy's speaker or headset as the communication device while
   `noEarpiece` holds. `other` used to leave that alone. Buddy's own call already takes the mic and the mode then, so
   this is accepted.
8. **Device behaviour after a reinstall is unverified.** Whether the restarted watch app accepts a channel right after
   `install -r` (stopped-state rules, the capability coming back) is only known on the device. If it doesn't, each
   try ends refused or broken and the voice stays on the phone. The phone is never silenced.
9. **Two releases for the watch call.** The silence fix waits for the counters on the device. Stage 1's release
   carries the retry, the route, the counters and the logs; stage 2's carries the fix. Done-when 7 stays open between
   them, and the feature is not closed on stage 1.
10. **The cause may not be in a planned row.** Only the source swap is planned. The other rows go back to diagnosis,
    but the counters at least say which end lost the audio.
11. **`MIC` has no echo canceller (if stage 2 takes row 1).** The half-duplex gate is already the echo guard: the mic
    isn't sent while the voice plays, nor for 300 ms after. A longer room tail could still reach the backend and
    interrupt the voice. The counters would show it as sent frames with a peak right after the voice. `AFTER_MS` is the
    knob; it doesn't change now.
12. **Log volume.** One watch line per 5 s and one phone line per 10 s add ~540 lines to a 30-minute call. That is
    small next to the page's own lines.

Upstream lines: one added line in the watchdog (step 7). `toWatch`, its page call, `push()`, the log rotation,
`AudioRoute`, `LiveWatch` and the wear `Call` are fork code (007/017/018/020).

## Open questions

**None blocking.** Plan-review round 2's two questions are answered from the code (Design, "The watch-started call"):
- *Why the watch mic sends silence.* The code can't tell; the counters will. Stage 1 ships them, D4a reads them, and
  stage 2 applies the table's row.
- *Status.* One shared string already reaches both UIs, and Nicholas's latest clarification describes that code. No
  change.

**For Nicholas, non-blocking (the orchestrator relays).** In any call where he is silent and the chat is busy, both
UIs show "Working on it…", as the phone always has. The phone shows "Listening…" during the chat's work only while it
hears him. If he wants "Listening…" in the silent case too, it is a reorder of the shared `status()`, and the phone
changes the same way. Default: unchanged, per "no new status logic".

**Not blocking.** Defaults recorded in decisions.md that Nicholas may overrule after trying:
- the count is per call and isn't reset by a successful return (a 4th drop in one call stays on the phone);
- a refusal at call start (busy or old watch app) also arms retries;
- an Auto call where the watch never took the voice keeps Android's routing (018), so it can still use the earpiece.
  Only the fallback after a drop is fixed here.
