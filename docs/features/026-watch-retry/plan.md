# Plan: 026-watch-retry
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal

When a phone live call's voice falls back from the watch to the phone (the watch app was killed or restarted, the
link dropped, the watch refused), the call should go back to the watch by itself. Today `LiveWatch.Call.fallback()`
keeps the voice on the phone for the rest of the call unless the audio device setting changes (feature 018's recorded
"no retry" default), so under Auto it never comes back (diagnosis.md §2, the W2 log). Fix: retry the watch up to 3
times per call, each at least 5 s after the drop (or after the previous failed retry), and only in a pause of ≥1 s in
the call's voice. Done when: (1) after a fallback the call is back on the watch within ~10 s of the watch being
reachable, with at most 3 tries per call and never mid-utterance; (2) an unreachable watch never silences the phone
voice; (3) unit tests cover the retry timing, the cap and the pause gating, and 018's decisions record the change;
(4) CI is green.

Non-goals:
- The cause of the W2 drop itself (the phone agent ran `adb install -r` of the watch app mid-call). That is a process
  note in diagnosis.md, not code.
- Following headset or other route changes during a call (018's decision stays: start plus setting changes, plus
  these retries).
- Watch-side changes. The installed watch app already answers every case the retry needs.
- Retrying calls started from the watch (007's Remote). They never reach `LiveWatch`.
- Phone read-aloud (012). It is off during a live call anyway (`ChatHub.java:1269`, `Bubble.java:2511`).

## Design

All the logic goes into `LiveWatch.Call`, which is pure and unit-tested through the `Out` fake. The only Android-side
change is one read-only accessor in `CodexLive`.

**The pause signal.** `Out` gets `boolean voiceQuiet()`, "the call's voice has had no audible block for
`QUIET_MS` (1 s)". `Phone` computes it as `SystemClock.elapsedRealtime() - CodexLive.lastAudible() > QUIET_MS`.
`CodexLive` gains `static long lastAudible() { return lastAudible; }`. The field already exists: the page's 70 ms
timer feeds it through `Buddy.level(…, voice)` (`CodexLive.java:733-736`, page `voice = v > 0.02`). The page measures
the remote voice stream whether it plays on the phone or is muted for the watch. The field is written and read on
the main thread: `level()` posts to `main`, and `poll()` and `attached()` run on the main looper. `LiveWatch`'s own
`pcm()` can't serve as the signal, because the page's tap forwards only while `fwd` (`CodexLive.java:910`), so it gets
nothing once the voice is back on the phone.
Alternatives: a `voiceQuiet()` in CodexLive with the 1 s inside it (diagnosis §3.6). That would put LiveWatch policy
into an upstream file, so the constant stays in LiveWatch next to `RETRY_MS`. Making the tap always forward would edit
the page and add bridge traffic for phone-only calls.

**New state in `Call`.** Constants `RETRY_MS = 5_000`, `RETRIES = 3`, `QUIET_MS = 1_000`. Fields:
- `int retries`: retries started in this call. `begin()` resets it to 0. Nothing else resets it.
- `long retryAt`: when the next retry is due, 0 when none is armed.
- `Object retryTurn`: the turn a retry created, so its "none found" and its attach can be recognised. Stale turns fail
  the `turn == t` checks as today.
- `Object pending`: a retry turn whose channel is open while the voice waits for a pause before moving.
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
2. `pending != null && pending == turn && out.voiceQuiet()`: `move()` (below).
3. `retryAt != 0 && turn == null && drain == null && out.now() >= retryAt && out.voiceQuiet() && out.watchWins()`:
   `retryAt = 0; retries++; out.log("retry " + retries); attempt(true)`. `watchWins()` comes last: it is the
   expensive check, and it is reached only in the armed window. While the watch doesn't win (Auto with a headset now),
   the retry stays armed and nothing is used up. It fires when the watch wins again.

`attempt(boolean retry)` sets `turn` and then, if `retry`, `retryTurn = turn`, before `out.find(...)`. The fake's find
answers synchronously, and `found` must already see the retry turn. `decide()` calls `attempt(false)`.

**Moving only in a pause.** `attached(t)` keeps its stale checks (`turn != t || watch`). If `t == retryTurn &&
!out.voiceQuiet()`, it sets `pending = t`, logs `waiting for a pause` and returns. Otherwise it calls `move()`.
`move()` is today's body plus `pending = null` (`gate.reset(); watch = took = true; log "playing there"`). While
pending, `watch` is false, so `pcm()` ignores the voice, `toWatch()` is 0, the phone keeps playing and its mic is
open. The channel just idles, as 018's readings opened ahead already do between turns. A refusal or break of the
pending reading goes through the existing `cur` branch of `answered()` → `fallback()` → `arm()`. The call is gated
twice, at the retry's start and at the switch, because find plus open can take up to 3 s + 10 s
(`WatchVoiceLink.FIND_MS`, `OPEN_MS`), and the voice can resume in between. Only the switch check makes the done-when
"never mid-utterance" hold. Call-start and setting-change attaches stay immediate, as 018 tests expect
(`aChangeOfTheSettingMovesTheVoice`).

**Why it can't silence the phone.** The phone is muted only by `watch = true`, set only in `move()` after the channel
is attached and the voice is quiet. "None found" never opens a channel. An open that doesn't attach ends as
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

## Steps

1. **The pause seam, no behaviour change.**
   - `CodexLive.java`: add `static long lastAudible() { return lastAudible; }` next to the field (~line 391), with a
     one-line javadoc ("when the voice was last audible, elapsedRealtime; 026").
   - `LiveWatch.java`: `Out.voiceQuiet()` with its javadoc, `QUIET_MS = 1_000`, and `Phone.voiceQuiet()`.
   - `LiveWatchTest.java`: `Fake` gets `boolean quiet = true;` and `voiceQuiet()`. All existing tests still pass.
2. **Retry after a fallback** (`LiveWatch.java`, `LiveWatchTest.java`).
   - Add `RETRY_MS`, `RETRIES`, the fields `retries`, `retryAt` and `retryTurn`, and `arm()`. Reset them in
     `begin()`/`clear()` as in Design. Call `arm()` from `fallback()` and from `found()`'s null branch when
     `t == retryTurn`. Add `attempt(boolean)`. In `poll()`, clear `retryAt` on a setting change and add the retry
     branch (Design, firing 3).
   - Fix `fallback()`'s javadoc: "the phone's own route; retried up to RETRIES times per call in a pause (026), and on
     a setting change".
   - Tests: update `refusedAfterAttachFallsBackAndNothingIsFoundAgainWithTheSameSetting` (rename it to
     `…AndNothingIsFoundAgainBeforeTheRetry`, `advance(RETRY_MS - 1)`, `finds == 1`) and `brokenAfterAttachFallsBackToo`
     (`advance(RETRY_MS - 1)`). Change the comment "no retry in this call" in
     `aLocalBrokenWithNoAnswerKeepsTheMicClosedForTheFailTail` to "no retry yet". Add T1, T3, T4, T5 and T6 (Test
     plan).
3. **Move only in a pause** (`LiveWatch.java`, `LiveWatchTest.java`). Add the `pending` field, `move()`, the gate in
   `attached()`, and the pending branch in `poll()` (Design, firing 2). Add T2.
4. **Docs.**
   - `docs/features/018-live-voice-watch/decisions.md`: append the entry "2026-11-18 - A fallback retries the watch up
     to 3 times per call, in a pause (replaces the 'no retry otherwise' part of the 2026-11-17 'LiveWatch decides at
     call start…' entry; feature 026)". It cites the W2 log (026 diagnosis.md) and gives the rules: 5 s after the drop
     or the failed retry, a ≥1 s pause at the start and at the switch, at most 3 per call, `stop()` never arms, and a
     setting change cancels a pending retry. The file is append-only: earlier entries stay untouched.
   - `README.md:102` (Live voice on the watch): after "…never the earpiece.", add one sentence: "When the watch can play
     again, the voice goes back to it at the next pause (up to 3 tries per call)."
5. **Run the tests.** Run LiveWatchTest and the full github unit-test set with the local javac/JUnit harness (as 018
   and 023 did; it is not Gradle). Then push the branch, and CI's `./gradlew testGithubDebugUnitTest …` decides.

## Test plan

New tests in `LiveWatchTest`. Each starts from `onWatch()`. "A drop" is `utterance(); r.events.broken(true)` on the
draining reading at `now = 0`, which is the W2 case ("stopped by the watch"). `RETRY_MS`, `QUIET_MS` and `POLL_MS`
come from LiveWatch.

| # | Test | Proves |
|---|---|---|
| T1 | `aDropIsRetriedFiveSecondsLaterInAPauseAndTheVoiceGoesBackToTheWatch`: a drop, then `advance(RETRY_MS - 1)` → `finds == 1`, `opened == 1`. `advance(1)` → `finds == 2`, the log has `retry 1`, a new reading is open. Run its `attached` → `toWatch() == 1`, and `pcm(LOUD)` lands in the new reading. | Done-when 1 (timing, return), 3 (timing) |
| T2 | `aRetryWaitsForAPauseAndMovesTheVoiceOnlyInOne`: `quiet = false`, a drop, `advance(10_000)` → `finds == 1`, no `retry` line. `quiet = true; advance(POLL_MS)` → `retry 1`. `quiet = false`, run `attached` → `toWatch() == 0`, the log has `waiting for a pause`, and `pcm(LOUD)` → the new reading got nothing. `quiet = true; advance(POLL_MS)` → `toWatch() == 1`, the log has a second `playing there`. | Done-when 1 (never mid-utterance), 3 (pause gating) |
| T3 | `aWatchThatComesBackLaterIsFoundByTheNextRetry`: `node = null`, a drop, `advance(RETRY_MS)` → `retry 1`, `none found: phone`. `advance(1000); node = "n1"`. `advance(RETRY_MS - 1001)` → `finds == 2`. `advance(1)` → `finds == 3`, `retry 2`, attach → `toWatch() == 1` (4 s after it was reachable). | Done-when 1 (~10 s once reachable), 3 (spacing after a failed retry) |
| T4 | `atMostThreeRetriesPerCallWhateverTheirOutcome`: a drop. Retry 1 finds none (`node = null`). Retry 2 opens and its reading is `refused()` before attach. Retry 3 attaches (voice on the watch), then `broken(true)` on it. `advance(60_000)` → `finds == 4`, no `retry 4` line, `toWatch() == 0`. Each retry is ≥ `RETRY_MS` after the failure before it (assert `finds` at `-1` and `0`). Then `end(); begin()`, attach, a drop, `advance(RETRY_MS)` → `retry 1` again (the count is per call). | Done-when 1 (cap), 3 (cap) |
| T5 | `anUnreachableWatchNeverSilencesThePhone`: `node = null`, a drop, `advance(TAIL)`. Then 60 × `advance(POLL_MS)`, each followed by `assertEquals(0, c.toWatch())` (3 retries happen in that time). A second call: a drop, the retry opens a reading that never attaches → `toWatch() == 0`, `pcm(LOUD)` → that reading got nothing. `refused()` (the open timeout) → `toWatch() == 0`. | Done-when 2 |
| T6 | `aRetryWaitsWhileTheWatchDoesNotWinAndASettingChangeCancelsIt`: the call starts on Auto (`saved = ""`). (a) A drop, then `wins = false` with `saved` unchanged (a headset came), `advance(10_000)` → `finds == 1`, no `retry` line. `wins = true; advance(POLL_MS)` → `retry 1`. (b) Attach, then a drop. `saved = "2:Speaker", wins = false; advance(POLL_MS)`. `saved = "", wins = true, quiet = false; advance(POLL_MS)` → a find with no `retry 2` line, and its `attached` → `toWatch() == 1` (setting-change attaches aren't gated). Then `advance(10_000)` → still no `retry 2`. | Done-when 3 (gating), 018 rules kept |

Changed 018 tests: the two "nothing found again" cases now assert no find before `RETRY_MS` (the behaviour this
feature overrules on purpose). All other existing cases pass unchanged with `quiet = true`. I traced each:
`staleAnswersAreIgnored` cancels the armed retry through its setting change, and the remaining cases either don't fall
back or stop advancing the clock before 5 s.

Checks by done-when item:
1. Back within ~10 s, ≤3 tries, never mid-utterance: T1-T4 (CI). On the device after release (the playbook's
   after-switch row: phone code reaches the phone only through `update_buddy`): a phone live call with Auto and the
   voice on the watch, then `adb -s <watch> install -r buddy-watch.apk` mid-reply (the W2 trigger). The second check is
   watch Bluetooth and Wi-Fi off for ~5 s, then back on. The CodexLive log must show `watch: fallback: …`, then
   `watch: retry 1` (or 2) ≥5 s later, then `watch: playing there`, within ~10 s of the watch being back. The last
   `page voice audible`/`quiet` line before `playing there` must be `quiet`, ≥1 s earlier. Then speech is heard from the
   watch.
2. Never silences the phone: T5 (CI). Device: watch off after a drop, the reply is heard on the phone throughout, the
   log shows 3 `retry` lines each followed by `none found: phone`, and no `page mic closed` from them.
3. Tests and 018 decisions: T1-T6 in CI, plus the decisions.md entry from step 4.
4. CI green: the PR's `build.yml` run (helper tests, `testGithubDebugUnitTest`, assembles, wear tests).

## Risks

1. **Two loudness thresholds.** The pause signal is the page's meter (peak > 0.02, sampled every 70 ms), while the
   utterance gate uses 0.01. A soft but audible tail between 0.01 and 0.02 that lasts over 1 s could count as a
   pause. Speech rarely stays in that band for a second, and the worst case is a switch on a very soft word end.
2. **A meter that reads nothing.** If the page's audio element stalls again (020's bug), `lastAudible` stops moving
   and every moment looks quiet, so the switch could land mid-word. The phone voice wasn't audible in that state
   either, and 020 keeps the element playing.
3. **Long outages exhaust the tries.** Three tries cover roughly 15-45 s after the drop, depending on open timeouts.
   A watch that is gone longer (a reboot, out of range) stays on the phone until the setting changes, as today.
4. **A watch talk mid-call.** The watch's own mic use hushes the reading (code 0), and retries during the talk are
   refused (code 1). They can use up the 3 tries, so after a long watch talk the voice stays on the phone. The retry
   never interrupts the talk, because the watch refuses while busy.
5. **Setup sample during a call** (018 Risk 9). The sample replaces the call's reading. A retry 5 s later, in a pause
   of the call's voice, replaces the sample in turn (newest reading wins). This is rare and bounded.
6. **Upstream friction in `CodexLive.java`.** One added accessor line.
7. **Device behaviour after a reinstall is unverified.** Whether the restarted watch app accepts a channel right after
   `install -r` (stopped-state rules, the capability coming back) is only known on the device. If it doesn't, each
   try ends refused or broken and the voice stays on the phone. The phone is never silenced.

## Open questions

None blocking. Defaults recorded in decisions.md that Nicholas may overrule after trying:
- the count is per call and isn't reset by a successful return (a 4th drop in one call stays on the phone);
- a refusal at call start (busy or old watch app) also arms retries.
