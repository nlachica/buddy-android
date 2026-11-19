# Plan: 028-dictation-echo

Sources: `brief.md`, `diagnosis.md` (it stands in for research; `research.md` is empty), and
`app/src/main/java/app/buddy/assistant/ClaudeDictation.java` (upstream code, last touched in 2.2.19).

## Goal
With `voice_input=claude`, the chat sometimes gets `/voice hold ⎿ Turned on voice mode (hold). Hold space to record.`
instead of the dictated words. `ClaudeDictation.promptText()` takes the last `❯` line on the hidden session's screen.
When the input box shows no `❯` line, that is the `/voice hold` history echo. `ensureSession` also sleeps a fixed
1500 ms, but `/voice hold` takes 3-4 s. The smallest fix: `promptText` reads only the real input box (the `❯` line whose
line above starts with `─`), it rejects `/voice` echo text, and `ensureSession` waits (bounded) for
`Turned on voice mode` instead of sleeping. A unit test drives `promptText` through a screen-text seam. The change to
upstream code stays minimal so future upstream merges stay easy.
Done when: (1) `promptText` never returns the `/voice` echo and returns the real box text; (2) `ensureSession` waits for
voice mode on, bounded; (3) unit test, CI green.

Non-goals:
- No change to holding, `finish`, `readResult`, `busy()`, end-of-speech or MicServer timing.
- No error or session restart when voice mode doesn't come on. On timeout it carries on as today (see decisions).
- No shared wait helper and no refactor of upstream's existing prompt-wait loop (lines 196-200).
- No hunt for the exact phone-side trigger (diagnosis: not reproduced). The guards make every non-box screen read as "".
- CodexDictation, user-facing strings, settings and docs stay untouched.

## Design
One production file, about 15 changed lines, plus one new test file. No method renamed or moved.

1. **Seam.** `static String promptText()` becomes a one-line wrapper:
   `return session == null ? "" : promptText(session.emulator.screenText());`. The body moves unchanged into a new
   package-private, pure `static String promptText(String screen)`. Callers (`readResult`, `startHolding`) stay as they are.
2. **Only the input box.** The bottom-up scan accepts a line only if it contains `❯` **and** the line above it,
   trimmed, starts with `─` (the box's top rule). The loop runs `i > 0`. Nothing found means `""`. The rest (append
   lines until a blank or `─` line) is unchanged. The `─` (U+2500) rule is what upstream already uses for the
   box's bottom edge, and diagnosis confirmed this layout on CLI 2.1.327 and 2.1.328.
3. **Echo text guard.** Normalize first (`replaceAll("\\s+", " ").trim()`), then return `""` if the text contains
   `ALSA lib` or `keep holding` (upstream's existing guard) **or** starts with `/voice` **or** contains
   `Turned on voice mode`. This also covers `/voice hold` still sitting unsent in the box.
4. **Wait for voice mode.** In `ensureSession` (line 203), replace `Thread.sleep(1500)` with a loop in the same shape as
   upstream's prompt wait. It reuses `until`, sets it to now + 15 000 ms, and loops while not timed out, not
   `session.exited` and the screen lacks `Turned on voice mode`, with `Thread.sleep(200)`. Then `clearPrompt()` runs
   as before. On timeout it carries on unchanged (today's behaviour). The string is in the installed CLI
   (`grep -a "Turned on voice mode ("` on 2.1.328 matches). A side effect, inferred and not observed: `prepare()`
   stops MicServer as soon as `ensureSession` returns. Today that is ~1.9 s after `/voice hold`, while Claude Code may
   still be testing the microphone. Waiting removes that too.

Alternatives, one line each:
- Take the last `❯` and return `""` unless it is boxed: same size, but a `❯` menu line below the box would blank real text.
- Text guard alone: misses other non-box `❯` lines (earlier history, error output). The brief asks for the box rule.
- Feed real bytes through `TermEmulator` in the test: `TermSession`'s constructor is private and a string seam is enough.
- Throw and restart the session when voice mode doesn't come on: a better error, but more code and a new failure path nobody asked for.
- One `waitFor(text)` helper for both waits: it rewrites upstream's existing loop, which makes the merge diff bigger.
- A named constant for `Turned on voice mode`: an extra hunk. The file already repeats inline literals (`❯`, `ALSA lib`).

## Steps
1. **Seam (pure refactor).** `ClaudeDictation.java`: split `promptText()` into the wrapper and `promptText(String screen)`
   as in Design 1. No behaviour change. Commit `refactor(028): promptText reads a screen string`.
2. **Test first.** Add `app/src/test/java/app/buddy/assistant/ClaudeDictationTest.java` (JUnit 4, same package, style of
   `LeftoverTest`). It holds the screens from the Test plan, built from the diagnosis layout with the real characters
   `❯` U+276F, `─` U+2500, `⎿` U+23BF. Run it against step 1's code and note which cases fail (expected: T1, T5, T6).
3. **Fix `promptText(String)`.** Apply the box rule and the echo guard (Design 2-3). All of `ClaudeDictationTest` passes.
   Commit test plus fix together: `fix(028): dictation reads only Claude Code's input box`, with the red cases from
   step 2 named in the message.
4. **Wait for voice mode.** `ClaudeDictation.ensureSession`: replace the 1500 ms sleep with the bounded poll (Design 4),
   plus a one-line comment ("/voice hold takes a few seconds; holding space before it is on reads its echo"). Commit
   `fix(028): wait for voice mode before dictating`.
5. **Run the tests.** Run `ClaudeDictationTest` and the full github unit-test set with the local javac/JUnit harness
   (`/tmp/buddy-build-026/check.sh github`, as 023 and 026 did; it is not Gradle; the known
   `BypassTest…isLoggable` local failure is not ours). Push the branch. CI decides
   (`./gradlew testGithubDebugUnitTest …` in `build.yml`).
6. **Feature docs.** Coder notes in `decisions.md` anything that deviated from this plan. No other docs change.

## Test plan
`ClaudeDictationTest`, all on `promptText(String)`:
| # | Screen | Expect | Old code |
|---|---|---|---|
| T1 | banner, `❯ /voice hold`, `  ⎿  Turned on voice mode (hold). Hold space to record.`, blank, rule, `  ● Recording…` (no `❯`), rule | `""` | the echo string (the bug) |
| T2 | same echo, then rule, `❯ hello from the phone`, rule, `  ? for shortcuts` | `hello from the phone` | same |
| T3 | same echo, then an empty box (`❯ ` between rules) | `""` | same |
| T4 | box text wrapped over two lines | both lines joined by one space | same |
| T5 | box holding `❯ /voice hold` between rules (sent text not yet run) | `""` | `/voice hold` |
| T6 | rule directly above the echo `❯ /voice hold` + `⎿` line, no box below | `""` (text guard) | the echo string |
| T7 | box holding `ALSA lib …` / `keep holding` | `""` | same (upstream guard kept) |
| T8 | `""` screen; and a `❯` on line 0 | `""` | same |

Done-when proof:
1. **`promptText` never returns the echo; returns the box text.** T1, T5, T6 (echo or `/voice` text gives `""`), T2 and T4
   (the real box text). Gate: CI `testGithubDebugUnitTest`.
2. **`ensureSession` waits for voice mode, bounded.** Code check: the diff shows the poll for
   `Turned on voice mode`, bounded at 15 s, stopping on `session.exited`, followed by `clearPrompt()`. String check:
   `grep -a -c "Turned on voice mode (" ~/.local/share/claude/versions/<installed>` is at least 1 (slow on the phone,
   ~1-2 min). Device check, after merge + tag + `update_buddy` (fork code can't reach the phone sooner): with
   `voice_input=claude`, force-stop Buddy, open a chat, tap the mic within 2 s and say a sentence. The box gets the
   sentence, never `/voice hold ⎿ …`. Do it 3 times, once right after Linux off/on. Also tap the mic and stay silent:
   the result is the "no speech" message, not the echo. These rows stay `pending` (`VERDICT: PARTIAL`) until the release.
3. **Unit test; CI green.** Local harness run (step 5), then every PR check green, the `apk` job included.

## Risks
- **Claude Code changes its input box** (no `─` top rule): `promptText` always gives `""` and every dictation ends in
  "no speech". This is visible, not wrong text. Upstream's bottom-rule check already depends on `─`. The device check catches it.
- **The CLI rewords `Turned on voice mode`**: each fresh session waits the full 15 s once, then works as today. Audio is
  buffered meanwhile (`MicServer.startCapture`), but `finish()`'s 45 s no-start bound loses that margin.
- **The phone trigger was never reproduced** (diagnosis). Whatever it is, the echo can no longer reach the chat. If the
  real cause is voice mode failing, the user now sees "no speech" instead of the echo. The device check confirms that
  dictation really works.
- **The lock is held longer**: `ensureSession` is `synchronized`, so `listen` waits for `prepare`'s ~3-4 s. That is correct
  (it must not hold space before voice mode is on).
- **The local harness is not Gradle**: CI is the real compile and test.

## Open questions
None blocking. Recorded choice for Nicholas to overrule: when voice mode doesn't come on within 15 s, dictation carries
on as today instead of failing with an error (decisions.md).
