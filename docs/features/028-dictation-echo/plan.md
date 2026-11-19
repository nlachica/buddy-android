# Plan: 028-dictation-echo

**Status: BLOCKED on research for Part B (the cause).** Part A (the safety net) is final. Nicholas's clarification in
`brief.md` says to find the regression first, so the Researcher answers Q1-Q4 below. The Planner then adds Part B, and
coding starts on both together.

Sources: `brief.md` (with Nicholas's clarification), `diagnosis.md` (stands in for research; `research.md` is empty),
`app/src/main/java/app/buddy/assistant/ClaudeDictation.java`, and the facts below.

## Goal
With `voice_input=claude`, the chat gets `/voice hold ⎿ Turned on voice mode (hold). Hold space to record.` instead of
the dictated words. Talk worked before 2026-11-18. Something that changed then is the real trigger: fork.3 (026), fork.4
(027), or Claude CLI 2.1.327/328. The echo scrape in `ClaudeDictation.promptText()` is only the symptom.
- **Part B, the cause:** find the regression and fix it (blocked: Open questions).
- **Part A, the safety net:** the smallest change to upstream code. `promptText` reads only the real input box (the
  `❯` line under a `─` rule) and rejects `/voice` echo text. `ensureSession` waits, bounded, for voice mode on instead
  of a fixed 1500 ms sleep. A unit test drives it through a screen-text seam.

Done when: (1) `promptText` never returns the `/voice` echo and returns the real box text; (2) `ensureSession` waits for
voice mode on, bounded; (3) unit test, CI green; and, from the clarification, (4) the regression's cause is found and fixed.

Non-goals: no change to holding, `finish`, `readResult`, `busy()`, end-of-speech or MicServer timing beyond what Part B
proves necessary. No error or session restart when voice mode doesn't come on. No refactor of upstream's prompt-wait
loop. CodexDictation, user-facing strings and settings stay untouched.

## Facts found while planning (for the Researcher)
- **The CLI rewords `/voice hold`'s output every release.** From `/root/.claude/projects/-root-dictation/*.jsonl`
  (`local-command-stdout`): 2.1.325 `Voice mode enabled (hold). Hold space to record.`; 2.1.326 `Voice mode is on
  (hold). …`; 2.1.327 and 2.1.328 `Turned on voice mode (hold). …`. Only `Hold space to record.` is constant.
- **"is on" became "Turned on" at 2.1.327.** Under 326, voice mode may already have been on at start (Buddy writes
  `"voiceEnabled": true` to `/root/dictation/.claude/settings.json`). From 327 it is off until `/voice hold` finishes.
  This is inferred from the wording, not verified.
- **`/voice hold` took ~3.5 s under 2.1.326 too** (04:32:02.5 to 04:32:06.1 on 2026-11-17). So upstream's 1500 ms
  sleep was already short. It may only have become harmful once voice mode stopped being on beforehand (inference).
- **Timeline (UTC, 2026-11-18):** CLI 2.1.327 installed at 00:46 (`~/.local/share/claude/versions` mtime); fork.2
  tagged 02:15; **first 2.1.327 dictation session 03:33**; fork.3 tagged 05:00; fork.4 08:06. CLI 2.1.328 followed on
  11-19 at 01:34. The 2.1.326 binary is no longer on disk. The diagnosis compared 327 against 328 only.
- **fork.2..fork.4 touch no dictation file.** `git diff --stat v2.3.12-fork.2 v2.3.12-fork.4`: no change to
  ClaudeDictation, Voice, MicServer or `linux/`. Changed: AudioRoute, ChatHub, CodexLive, LiveWatch, Prefs, Schedules,
  Tools.
- **026's AudioRoute change is on the dictation path, but gated.** `Voice.listen` calls `AudioRoute.use`, and its poll
  runs `reconcile()` while `Voice.listening()`. The new `liveRule` dropped the `!other` gate. It is true only when
  `LiveWatch.noEarpiece(...)`, i.e. a watch live call object exists (`LiveWatch.call != null`).

## Design, Part A (safety net; final)
One production file, about 15 changed lines, plus one new test file. No method renamed or moved.
1. **Seam.** `static String promptText()` becomes
   `return session == null ? "" : promptText(session.emulator.screenText());`. The body moves into a new
   package-private, pure `static String promptText(String screen)`. Callers stay unchanged.
2. **Only the input box.** The bottom-up scan accepts a line only if it contains `❯` **and** the line above it, trimmed,
   starts with `─` (the box's top rule). The loop runs `i > 0`, and nothing found means `""`. The rest is unchanged.
   Upstream already uses `─` (U+2500) for the box's bottom edge. The diagnosis confirmed the layout on 2.1.327 and 2.1.328.
3. **Echo text guard.** Normalize first (`replaceAll("\\s+", " ").trim()`), then return `""` if the text contains
   `ALSA lib` or `keep holding` (upstream's guard) **or** starts with `/voice` **or** contains `Hold space to record`.
   The last one is the one part of the `/voice` output that stayed the same across 325-328, so it covers every wording.
4. **Wait for voice mode.** In `ensureSession` (line 203), replace `Thread.sleep(1500)` with a loop shaped like
   upstream's prompt wait. Reuse `until` = now + 15 000 ms, loop while not timed out, not `session.exited`, and the screen
   lacks `Hold space to record`, with `Thread.sleep(200)`. Then `clearPrompt()` as before. On timeout it carries on (today's
   behaviour). The brief names `Turned on voice mode`, but that wording is only one CLI release old (see Facts). The
   constant tail keeps the wait working when the CLI rewords it again. If the inference in Facts holds, this wait is
   also the cause fix.

Alternatives, one line each:
- Take the last `❯` and give `""` unless it is boxed: same size, but a `❯` menu line below the box would blank real text.
- Wait for `Turned on voice mode` (the brief's literal): it already fails for 2.1.326's wording, and each miss costs 15 s.
- Wait for any `⎿` output line under `❯ /voice hold`: independent of the wording, but depends on two glyphs and the echo layout.
- Feed real bytes through `TermEmulator` in the test: `TermSession`'s constructor is private and a string seam is enough.
- Throw and restart the session when voice mode doesn't come on: a new failure path nobody asked for.
- One `waitFor(text)` helper for both waits: it rewrites upstream's existing loop, which makes the merge diff bigger.

## Design, Part B (the cause; blocked)
To be designed from the Researcher's answers. The candidates, most likely first:
- (a) **CLI 2.1.327** turns voice mode on only through `/voice` (it ignores or moved `voiceEnabled`). Holding space
  1.5 s after `/voice hold` then lands before voice is on. Fix: Part A's wait, plus whatever makes voice on at start
  if that matters.
- (b) **CLI 2.1.327 changed the screen while recording or transcribing** (the box `❯` is hidden). Fix: Part A, plus
  maybe `busy()`/`readResult` timing.
- (c) **026's `liveRule`** routes or reopens audio during phone dictation, if `LiveWatch.call` outlives a watch call.
- (d) **027** (ChatHub/Schedules/Tools) reaches the hidden session or MicServer. No sign of it in the diff.

## Steps
0. Researcher answers Q1-Q4. Planner adds Part B's steps here.
1. **Seam (pure refactor).** `ClaudeDictation.java`: split `promptText()` (Design A1). Commit
   `refactor(028): promptText reads a screen string`.
2. **Test first.** Add `app/src/test/java/app/buddy/assistant/ClaudeDictationTest.java` (JUnit 4, same package, style
   of `LeftoverTest`; real `❯` U+276F, `─` U+2500, `⎿` U+23BF). Run it against step 1's code and note the red cases
   (expected: T1, T5, T6, T9).
3. **Fix `promptText(String)`** (Design A2-A3). All pass. Commit test plus fix:
   `fix(028): dictation reads only Claude Code's input box`, naming step 2's red cases.
4. **Wait for voice mode** (Design A4) with a one-line comment. Commit
   `fix(028): wait for voice mode before dictating`.
5. **Part B steps** (from step 0).
6. **Run the tests.** Run `ClaudeDictationTest` and the full github set with the local javac/JUnit harness
   (`/tmp/buddy-build-026/check.sh github`; not Gradle; the known local `BypassTest…isLoggable` failure is not ours).
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
| T6 | rule directly above the echo `❯ /voice hold` + `⎿` line, no box below | `""` | the echo |
| T7 | box holding `ALSA lib …` / `keep holding` | `""` | same |
| T8 | `""` screen; a `❯` on line 0 | `""` | same |
| T9 | T1 with 2.1.326's wording `Voice mode is on (hold). Hold space to record.` | `""` | the echo |

Done-when proof:
1. **Echo never returned; box text returned:** T1, T5, T6, T9, then T2 and T4. Gate: CI `testGithubDebugUnitTest`.
2. **Bounded wait:** in the diff, the poll for `Hold space to record` is bounded at 15 s, stops on `session.exited`,
   and is followed by `clearPrompt()`. Device check after merge + tag + `update_buddy` (fork code can't reach the
   phone sooner): `voice_input=claude`, force-stop Buddy, open a chat, tap the mic within 2 s, say a sentence. The box
   gets the sentence. Do it 3 times, once right after Linux off/on. A silent tap gives "no speech", not the echo. These
   rows stay `pending` (`VERDICT: PARTIAL`) until the release.
3. **CI green:** the local harness run, then every PR check, the `apk` job included.
4. **Cause fixed:** defined with Part B (at minimum the device check above, run on the CLI version that broke).

## Risks
- **Claude Code changes its input box** (no `─` top rule): every dictation ends in "no speech". That is visible, not
  wrong text. Upstream's bottom-rule check already depends on `─`. The device check catches it.
- **The CLI drops `Hold space to record`**: each fresh session waits 15 s once, then works as today. Audio is buffered meanwhile.
- **The lock is held longer**: `ensureSession` is `synchronized`, so `listen` waits for `prepare`'s ~3-4 s. That is
  correct (it must not hold space before voice mode is on).
- **The local harness is not Gradle**: CI is the real compile and test.

## Open questions (blocking Part B; for the Researcher)
- **Q1. CLI 2.1.326 vs 2.1.327/328.** Get 2.1.326 (native installer or npm) and drive each version in a pty through
  Buddy's exact flow: `settings.json` with `voiceEnabled: true`, `/voice hold`, space held from 1.5 s after it. Is voice
  mode on before `/voice hold` in 326 and off in 327? What does the screen show when space is held before voice is on,
  while recording, and while transcribing (stand-in recorder with real audio, e.g. a WAV)? Which state loses the box
  `❯`? Where does 327 read `voiceEnabled`?
- **Q2. Ask Nicholas:** did dictation fail between 2026-11-18 03:33 (first 2.1.327 session, phone still on fork.2) and
  the fork.3 install (~05:00)? And was a watch call or a Schedule involved when it failed?
- **Q3. 026:** can `LiveWatch.call` stay non-null after a watch call ends (retries, fallback), so that
  `AudioRoute.liveRule` acts during phone dictation? Does `live()` change the mic input of MicServer's
  `VOICE_RECOGNITION` `AudioRecord`?
- **Q4. 027:** does any changed path (ChatHub `send`/`helperGone`, Schedules `keep`, Tools) start, write to or stop the
  hidden dictation session or MicServer?
