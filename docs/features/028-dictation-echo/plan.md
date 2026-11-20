# Plan: 028-dictation-echo

**Status: BLOCKED on research for Part B (the cause).** Part A (the safety net) is final; it was revised for plan-review
rounds 1 (F2-F5) and 2 (F2-F4). Nicholas's clarification in `brief.md` says to find the regression first, so the
Researcher answers Q1-Q4 below. The Planner then adds Part B, which must meet `## Part B acceptance`. Coding starts on
both parts together.

Sources: `brief.md` (with Nicholas's clarification), `diagnosis.md` (stands in for research; `research.md` is empty),
`app/src/main/java/app/buddy/assistant/ClaudeDictation.java`, and the facts below.

## Goal
With `voice_input=claude`, the chat gets `/voice hold ⎿ Turned on voice mode (hold). Hold space to record.` instead of
the dictated words. Talk worked before 2026-11-18. Something that changed then is the real trigger: fork.3 (026), fork.4
(027), or Claude CLI 2.1.327/328. The echo scrape in `ClaudeDictation.promptText()` is only the symptom.
- **Part B, the cause:** find the regression and fix it (blocked: Open questions).
- **Part A, the safety net:** the smallest change to upstream code. `promptText` reads only the real input box (the
  `❯` line under a `─` rule) and rejects the `/voice hold` echo. `ensureSession` waits, bounded, for voice mode on
  instead of a fixed 1500 ms sleep, and a session counts as ready only after voice mode is on. A unit test drives the
  parser through a screen-text seam.

Done when: (1) `promptText` never returns the `/voice` echo and returns the real box text; (2) `ensureSession` waits for
voice mode on, bounded; (3) unit test, CI green; and, from the clarification, (4) the regression's cause is found and fixed.

Non-goals: no change to holding, `finish`, `readResult`, `busy()`, end-of-speech or MicServer timing beyond what Part B
proves necessary. No new error string and no retry loop when voice mode doesn't come on: the session is not marked
ready and the existing start-failed path reports it (A4). No refactor of upstream's prompt-wait loop. CodexDictation,
user-facing strings and settings stay untouched.

## Facts found while planning (for the Researcher)
- **The CLI rewords `/voice hold`'s output every release.** From `/root/.claude/projects/-root-dictation/*.jsonl`
  (`local-command-stdout`): 2.1.325 `Voice mode enabled (hold). Hold space to record.`; 2.1.326 `Voice mode is on
  (hold). …`; 2.1.327 and 2.1.328 `Turned on voice mode (hold). …`. Only `Hold space to record.` is constant.
- **"is on" became "Turned on" at 2.1.327.** Under 326, voice mode may already have been on at start (Buddy writes
  `"voiceEnabled": true` to `/root/dictation/.claude/settings.json`). From 327 it is off until `/voice hold` finishes.
  This is inferred from the wording, not verified.
- **`/voice hold` took ~3.5 s under 2.1.326 too** (04:32:02.5 to 04:32:06.1 on 2026-11-17). So upstream's 1500 ms
  sleep was already short. It may only have become harmful once voice mode stopped being on beforehand (inference).
- **`prepare` stops MicServer before `/voice hold` finishes.** A chat opening calls `prepare`, which runs
  `ensureSession` and then `MicServer.stop()` in its `finally` (`ClaudeDictation.java:77-81`): about 1.5 s after
  `/voice hold`, while the command still runs for 3-4 s. Line 60 says Claude Code tests the microphone when voice mode
  turns on. If 2.1.327 moved that test from startup to `/voice hold`, it now runs with no MicServer (inference).
- **Timeline (UTC, 2026-11-18):** CLI 2.1.327 installed at 00:46 (`~/.local/share/claude/versions` mtime); fork.2
  tagged 02:15; **first 2.1.327 dictation session 03:33**; fork.3 tagged 05:00; fork.4 08:06. CLI 2.1.328 followed on
  11-19 at 01:34. The 2.1.326 binary is no longer on disk. The diagnosis compared 327 against 328 only.
- **fork.2..fork.4 touch no dictation file.** `git diff --stat v2.3.12-fork.2 v2.3.12-fork.4`: no change to
  ClaudeDictation, Voice, MicServer or `linux/`. Changed: AudioRoute, ChatHub, CodexLive, LiveWatch, Prefs, Schedules,
  Tools.
- **026's AudioRoute change is on the dictation path, but gated.** `Voice.listen` calls `AudioRoute.use`, and its poll
  runs `reconcile()` while `Voice.listening()`. The new `liveRule` dropped the `!other` gate. It is true only when
  `LiveWatch.noEarpiece(...)` is true, i.e. `Call.on`. Only `CodexLive.start` sets it, for a phone Live call
  (`CodexLive.java:253`), and only `CodexLive.end` clears it (`:268`).

## Design, Part A (safety net; final)
One production file, about 18 changed lines, plus one new test file. No method renamed or moved, and `end()` and
upstream's prompt-wait (lines 196-200) are unchanged.
1. **Seam.** `static String promptText()` becomes
   `return session == null ? "" : promptText(session.emulator.screenText());`. The body moves into a new
   package-private, pure `static String promptText(String screen)`. Callers stay unchanged.
2. **Only the input box.** The bottom-up scan accepts a line only if it contains `❯` **and** the line above it, trimmed,
   starts with `─` (the box's top rule). The loop runs `i > 0`, and nothing found means `""`. The rest is unchanged.
   Upstream already uses `─` (U+2500) for the box's bottom edge. The diagnosis confirmed the layout on 2.1.327 and 2.1.328.
3. **Echo text guard.** Normalize first (`replaceAll("\\s+", " ").trim()`), then return `""` if the text contains
   `ALSA lib` or `keep holding` (upstream's guard) **or** `(t + " ").startsWith("/voice hold ")`. That matches only
   Buddy's own command: typed (`/voice hold`) or echoed with its `⎿` output (`/voice hold ⎿ …`). Box text such as
   `/voicemail` or `/voice holder` passes (round 2 F4). So do dictated words such as "Hold space to record a memo"
   (round 1 F2), because the guard ignores the output's wording.
4. **Wait for voice mode; ready only once it is on.** In `ensureSession`:
   - line 182: `session = null;` becomes `session = null; sessionLang = null;`.
   - line 194: `sessionLang = lang;` is removed.
   - line 203: `Thread.sleep(1500)` becomes a loop shaped like upstream's prompt wait. It reuses `until` = now
     + 15 000 ms and loops while not timed out, not `session.exited`, and the screen lacks `Hold space to record`,
     sleeping 200 ms each time. If the screen still lacks it, `throw new Exception(c.getString(R.string.dictation_claude_code_no_start))`.
     Otherwise `sessionLang = lang;` then `clearPrompt()` as before.

   So `ready()` (and line 180's reuse) holds only for a session whose voice mode came on. Any throw before that leaves
   `sessionLang` null: either timeout, PREP, or an interrupt. The next `ensureSession` (a tap or a chat opening) then
   finishes the half-started session at the existing line 181 and starts afresh, `/voice hold` included (round 1 F3,
   round 2 F2). The throw goes through the existing paths: `listen` shows `dictation_claude_start_failed` and `prepare`
   ignores it. The wait looks for `Hold space to record`, the only part of the output that is the same across 325-328.
   The brief's `Turned on voice mode` is one CLI release old (see Facts). If the inferences in Facts hold, this wait is
   also the cause fix.
5. **A stale attempt's error stays out of the next tap.** Line 65: `main.post(() -> end(l, …))` becomes
   `main.post(() -> { if (listener == l) end(l, …); })`. A cancelled attempt already got `onEnd(null)` from `cancel()`.
   Before this change its late start error (now possible after A4's 15 s wait) stopped the shared microphone and hid
   the voice bar of the tap that replaced it (round 2 F3).

Alternatives, one line each:
- Take the last `❯` and return `""` unless it is boxed: same size, but a `❯` menu line below the box would blank real text.
- Wait for `Turned on voice mode` (the brief's literal): it already fails for 2.1.326's wording, and each miss now fails the tap.
- Also reject `Hold space to record` in the parser: it blanks real dictation of those words (F2), and `/voice` already covers the echo.
- Wait for any `⎿` output line under `❯ /voice hold`: independent of the wording, but depends on two glyphs and the echo layout.
- Feed real bytes through `TermEmulator` in the test: `TermSession`'s constructor is private and a string seam is enough.
- On timeout, carry on with the session (the old plan): a session with voice mode off is reused forever (F3).
- On each timeout, `session.finish(); session = null;` (the round 1 answer, and round 2 F2's suggestion): it touches
  both throws, and misses other throws. Setting `sessionLang` last covers every exit with fewer lines.
- Guard on `end()` itself (`if (listener != l) return;`): same size, but it changes a shared method's meaning.
- Guard on `startsWith("/voice")`: it blanks `/voicemail` (round 2 F4).
- One `waitFor(text)` helper for both waits: it rewrites upstream's existing loop, which makes the merge diff bigger.

## Design, Part B (the cause; blocked)
To be designed from the Researcher's answers. The candidates, most likely first:
- (a) **CLI 2.1.327** turns voice mode on only through `/voice` (it ignores or moved `voiceEnabled`). Holding space
  1.5 s after `/voice hold` then lands before voice is on, or `prepare` has already stopped MicServer while the
  command's microphone test runs. Fix: Part A's wait (MicServer stays up until voice mode is on), plus whatever turns
  voice on at start if that matters.
- (b) **CLI 2.1.327 changed the screen while recording or transcribing** (the box `❯` is hidden). Fix: Part A, plus
  maybe `busy()`/`readResult` timing.
- (c) **026's `liveRule`** routes audio during phone dictation, if some teardown path skips `LiveWatch.end()` (Q3).
- (d) **027** (ChatHub/Schedules/Tools) reaches the hidden session or MicServer. No sign of it in the diff.

## Part B acceptance (from plan-review round 1 F1)
Part B is coded only once research gives it all three:
1. **A named cause**, from a working/failing pair that differs in one thing only (Q1): the CLI version with Buddy at
   fork.4, on the phone through the real microphone and routing; or, if both CLIs fail, Buddy's call/route state
   (Q3). A longer wait that helps on 2.1.328 does not count as a cause on its own (round 2 F1).
2. **The smallest fix** for that cause, as Steps here. If it is Part A's wait itself, this section says so and Part B
   adds no code.
3. **A repro check that repeats the failing setup.** Before the fix, that setup gives the echo or "no speech"; after
   it, the spoken words. For a CLI cause the setup is Buddy's flow in a pty on the CLI that broke, with a WAV through a
   stand-in MicServer, plus the device check on that CLI. For a routing cause it is the same phone state, after the
   release.

## Steps
0. Researcher answers Q1-Q4. Planner adds Part B's steps here and checks them against `## Part B acceptance`.
1. **Seam (pure refactor).** `ClaudeDictation.java`: split `promptText()` (Design A1). Commit
   `refactor(028): promptText reads a screen string`.
2. **Test first.** Add `app/src/test/java/app/buddy/assistant/ClaudeDictationTest.java` (JUnit 4, same package, style
   of `LeftoverTest`; real `❯` U+276F, `─` U+2500, `⎿` U+23BF). Run it against step 1's code and note the red cases
   (expected: T1, T5, T6, T9).
3. **Fix `promptText(String)`** (Design A2-A3). All pass, T10 and T11 included. Commit the test and the fix together:
   `fix(028): dictation reads only Claude Code's input box`, naming step 2's red cases.
4. **Wait for voice mode** (Design A4), with a one-line comment on the wait and one on why `sessionLang` is set last.
   Commit `fix(028): wait for voice mode before dictating`.
4b. **Stale error guard** (Design A5), line 65 only. Commit `fix(028): a cancelled start's error leaves the next tap alone`.
5. **Part B steps** (from step 0).
6. **Run the tests.** The `buddy-fork-026` harness checkout is gone. Use a copy of the harness pointed at this
   worktree, with its own output and `gen` directories (not Gradle; the known local `BypassTest…isLoggable` failure
   is not ours):
   ```sh
   mkdir -p /tmp/buddy-build-028/gen
   sed -e 's|/root/.buddy/work/buddy-fork-026|/root/.buddy/work/buddy-fork-028|' \
       -e 's|O=\$B/out-\$F|O=/tmp/buddy-build-028/out-$F|' -e 's|\$B/gen|/tmp/buddy-build-028/gen|g' \
       /tmp/buddy-build-026/check.sh > /tmp/buddy-build-028/check.sh
   sh /tmp/buddy-build-028/check.sh github app.buddy.assistant.ClaudeDictationTest   # red at step 2, green at step 3
   sh /tmp/buddy-build-028/check.sh github                                           # the whole github set
   ```
   Push. CI decides (`./gradlew testGithubDebugUnitTest …`).
7. **Feature docs.** Coder notes deviations in `decisions.md`.

## Test plan
`ClaudeDictationTest`, all on `promptText(String)`:
| # | Screen | Expect | Old code |
|---|---|---|---|
| T1 | banner, `❯ /voice hold`, `  ⎿  Turned on voice mode (hold). Hold space to record.`, blank, rule, `  ● Recording…` (no `❯`), rule | `""` | the echo (the bug) |
| T2 | same echo, then rule, `❯ hello from the phone`, rule, `  ? for shortcuts` | `hello from the phone` | same |
| T3 | same echo, then an empty box | `""` | same |
| T4 | box text wrapped over two lines | both lines joined by one space | same |
| T5 | box holding `❯ /voice hold` (typed, not run) | `""` | `/voice hold` |
| T6 | rule directly above the echo `❯ /voice hold` + `⎿` line, no box below (box rule passes, `/voice hold` guard rejects) | `""` | the echo |
| T7 | box holding `ALSA lib …` / `keep holding` | `""` | same |
| T8 | `""` screen; a `❯` on line 0 | `""` | same |
| T9 | T1 with 2.1.326's wording `Voice mode is on (hold). Hold space to record.` | `""` | the echo |
| T10 | same echo, then a box holding `❯ Hold space to record a memo` | `Hold space to record a memo` | same |
| T11 | same echo, then a box holding `❯ /voicemail`; again with `❯ /voice holder` | `/voicemail`; `/voice holder` | same |

Done-when proof:
1. **Echo never returned; box text returned:** T1, T5, T6, T9, then T2, T4, T10 and T11. Gate: `ClaudeDictationTest` by
   name in the local harness, then CI `testGithubDebugUnitTest`.
2. **Bounded wait; ready only once voice is on:** in the diff, the poll for `Hold space to record` is bounded at 15 s
   and stops on `session.exited`. On timeout it throws `dictation_claude_code_no_start`; otherwise it sets
   `sessionLang = lang` and calls `clearPrompt()`. `sessionLang` is cleared next to `session = null` and assigned
   nowhere else, and `listen`'s catch calls `end` only `if (listener == l)`. No unit test covers these branches:
   `listen`/`ensureSession` need `Linux.exec`, a pty and a Looper (no Robolectric here), and a seam would grow upstream's
   diff.
   **Device check** after merge + tag + `update_buddy` (fork code can't reach the phone sooner):
   `voice_input=claude`, force-stop Buddy, open a chat with an **empty draft**, tap the mic within 2 s, say a sentence.
   The **sent user message** is that sentence, not the echo (Talk sends on `onFinal`, `ChatActivity.java:1936-1940`).
   Do it 3 times, once right after Linux off/on. A silent tap shows the "no speech" toast and **adds no message**.
   **Fault checks** (same release): a one-shot stand-in for `claude`. Point `/root/.local/bin/claude` at a script that
   execs the real `versions/<ver>` binary, unless a counter file is above 0. In that case it decrements the counter,
   prints a bare box (`─` rule, `❯ `, `─` rule) and `exec sleep 120`, so voice mode never comes on. Restore the symlink
   afterwards. Unarmed, it passes through, so other `claude` runs are unaffected.
   - **D1, failed warm-up then a same-language tap** (round 2 F2, round 1 F3): arm 1 start, force-stop Buddy, open a
     chat (its warm-up gets the stand-in and fails silently at ~15 s), wait 20 s, tap and say a sentence. The sentence
     is sent.
   - **D2, cancel and immediate retry** (round 2 F3): arm 1 start, kill the hidden session (the process whose cwd is
     `/root/dictation`), tap, cancel within 10 s, tap again at once and say a sentence. The sentence is sent, no
     "couldn't start" toast appears, and the voice bar stays up until it is sent.
   These rows stay `pending` (`VERDICT: PARTIAL`) until the release.
3. **CI green:** the local harness run, then every PR check, the `apk` job included.
4. **Cause fixed:** Part B acceptance item 3 (the repro check, before and after), plus the device check on the CLI
   version that broke.

## Risks
- **Claude Code changes its input box** (no `─` top rule): every dictation ends in "no speech". That is visible, not
  wrong text. Upstream's bottom-rule check already depends on `─`. The device check catches it.
- **The CLI drops `Hold space to record`**: every fresh session fails after 15 s with "Claude voice couldn't start:
  Claude Code didn't start", and the next tap retries from scratch. It is loud and fixed by changing one string. This
  is the cost of "ready only once voice is on", which is better than reusing a session whose voice mode is off.
- **A session that failed to start keeps running** until the next tap or chat opening finishes it (line 181). This
  already happens after upstream's prompt timeout. It is one idle process.
- **A slow cold start (>15 s for `/voice hold`)** fails the first tap. The next tap starts afresh with warm caches. The
  diagnosis measured 3-4 s.
- **The lock is held longer**: `ensureSession` is `synchronized`, so `listen` waits for `prepare`'s ~3-4 s. That is
  correct (it must not hold space before voice mode is on).
- **The local harness is not Gradle**: CI is the real compile and test.

## Open questions (blocking Part B; for the Researcher)
- **Q1. A working/failing pair that differs in one thing only** (round 2 F1).
  (a) **On the phone, Buddy fixed at fork.4, only the CLI changes.** Get 2.1.326 (`claude install 2.1.326`, or npm
  `@anthropic-ai/claude-code@2.1.326`). Point `/root/.local/bin/claude` at it, force-stop Buddy, and Talk 3 times as in
  the device check. Then do the same with 2.1.328, and restore the symlink (until then, every `claude` start on the phone uses it). Confirm which CLI ran from the newest
  `/root/.claude/projects/-root-dictation/*.jsonl` (`version` field). This runs through the real microphone and
  routing. Nicholas taps and speaks.
  (b) **If 326 works and 328 fails (CLI cause):** drive Buddy's flow in a pty on both: `settings.json` with
  `voiceEnabled: true`, `/voice hold`, space held from 1.5 s after it, audio from a stand-in for MicServer on
  `127.0.0.1:8766` (`MicServer.PORT`; `linux/arecord` connects to it with a token) serving a WAV of a known sentence.
  Record the screen and the text `promptText` would read: before voice is on, while recording, while transcribing,
  after. Is voice mode on before `/voice hold` in 326 and off in 328? Does `prepare` stopping MicServer ~1.5 s after
  `/voice hold` matter? Which state loses the box `❯`? This gives the cause, the fix, and the pty repro for
  `## Part B acceptance`.
  (c) **If both fail (Buddy-side cause):** on 2.1.328, compare Talk right after a force-stop (no Live call since)
  with Talk right after a phone Live call has ended (Q3). Check Q4.
  (d) **If both work:** the trigger is a state not yet reproduced. Bring Q2 and Q3 to the orchestrator.
- **Q2. Ask Nicholas:** did dictation fail between 2026-11-18 03:33 (first 2.1.327 session, phone still on fork.2) and
  the fork.3 install (~05:00)? And was a watch call, a phone Live call or a Schedule involved when it failed?
- **Q3. 026, teardown:** `LiveWatch.Call.on` (what makes `noEarpiece`, and so `liveRule`, true) is set only by
  `LiveWatch.begin` from `CodexLive.start` for a phone Live call, and cleared only by `LiveWatch.end` from `CodexLive.end`,
  which returns early when `ses == null`. Does any path end a phone Live call, or fail after `begin`, without reaching
  `LiveWatch.end()`, leaving `on` true during a later Talk? If so, does `live()`'s route change the input of MicServer's
  `VOICE_RECOGNITION` `AudioRecord`?
- **Q4. 027:** does any changed path (ChatHub `send`/`helperGone`, Schedules `keep`, Tools) start, write to or stop the
  hidden dictation session or MicServer?
- **Q5. Nicholas, only if Q1 finds no failing setup:** ship Part A alone (its wait is the likely fix), with the cause
  recorded as unproven, or keep 028 open?
