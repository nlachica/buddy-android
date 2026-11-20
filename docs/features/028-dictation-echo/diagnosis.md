# voice_input=claude: chat shows "/voice hold ⎿ Turned on voice mode (hold)..." instead of dictation

Phone: Buddy 2.3.12-fork.4. Claude CLI 2.1.328 (2.1.327 is also installed). Read-only diagnosis; I changed no repo code.

## Short answer
- **Where:** upstream Buddy code, `ClaudeDictation.promptText()` at `app/src/main/java/app/buddy/assistant/ClaudeDictation.java:240-260`.
- **Not the fork:** `ClaudeDictation.java` has two commits, `87abd8e` (2.2.8) and `91cce1b` (2.2.19). Neither fork features 016-027 nor the 2.3.12 merge touched it.
- **Not the CLI:** the `/voice` code is identical in 2.1.327 and 2.1.328. I diffed the `[voice]` strings and `startRecording`, and ran both binaries through the same pty script. They behave the same.
- **The text is Buddy's own screen scrape.** Buddy did not send a message. Buddy keeps a hidden Claude Code session (`/root/dictation`, started by `ensureSession`, line 179-205). It types `/voice hold` there (line 202). It then holds space and reads the prompt text back off the terminal screen as the dictated words.

## Mechanism (proven by exact string match)
`promptText()` takes the **last** screen line containing `❯`. It appends the following lines until it meets a blank line or a line starting with `─`. In the hidden session, `/voice hold` leaves this on screen:

```
❯ /voice hold
  ⎿  Turned on voice mode (hold). Hold space to record.
<blank/status>
─────────────
❯                     <- the real input box
─────────────
```

If the real input box is not recognised as the last `❯` line, the scrape picks the history echo. It joins the two lines into `/voice hold ⎿ Turned on voice mode (hold). Hold space to record.`, which is exactly what you saw. `readResult()` then treats it as settled non-empty text (lines 139-145) and calls `l.onFinal(t)`, so the chat gets that text.

The only guard is line 258 (`ALSA lib` or `keep holding`). It does not cover this case. `clearPrompt()` (ctrl+u) only clears the live input box, never the history echo.

## What I could and could not reproduce
- Confirmed in a pty with a real screen emulator, on both 2.1.327 and 2.1.328:
  - `/voice hold` takes 3-4 s (a "Manifesting…" spinner with "esc to interrupt"). The first-ever real session in `~/.claude/projects/-root-dictation` shows `/voice` at 08:11:50 and its output at 08:11:54.
  - `ensureSession` waits only 1500 ms (line 203) before `clearPrompt()`. `listen()` then starts holding space while the command is still running.
  - With the box idle, the last `❯` is the empty box, so `promptText()` returns `""`. The bad text did not reproduce in that state.
- **Not reproduced:** the exact trigger on the phone. My shell has no running MicServer, so Claude never started recording. The likely trigger is a state where the input box shows no `❯`, for example the recording or transcribing indicator replacing the prompt, or a failed voice_stream. The scrape then falls back to the echo. This is inferred, not observed.
- `/tmp/dictation.err` holds only ALSA noise and no real error. `~/.buddy` logs have no voice entries, so I can't see what the phone's hidden screen showed at the moment of the press.

## Smallest fix (not applied)
1. In `promptText()`, only accept a `❯` line that is the input box. Require the line above it to start with `─`. Otherwise return `""`. About 4 lines, and it kills this whole class of echo and history reads.
2. Belt and braces: if `t` contains `Turned on voice mode` or starts with `/voice`, return `""`.
3. Optional, to remove the race: in `ensureSession` (line 202-204), replace `Thread.sleep(1500)` with a poll of the screen for `Turned on voice mode` (up to about 15 s), then `clearPrompt()`.
4. Add a unit test that feeds `promptText()` the screen above. It needs a small seam to inject the screen text, since the method reads `session.emulator` directly.

Should go through the feature pipeline and the release check (`tools/release-check.sh`) before a bump or tag.

## Workaround now
- Set voice input to the phone's recognizer: `buddy_settings voice_input=phone` (one approval card), or Settings → Voice → Voice input → Phone.
- To keep Claude dictation, reset the hidden session so it restarts clean: force-stop and reopen Buddy, or turn Linux off and on. A stale session in the wrong state keeps returning the echo. This may only help if the cause was stale state.
- `voice_input=chatgpt` is the other engine, if Codex is signed in.

## Housekeeping
My repro runs added about 4 small session files under `/root/.claude/projects/-root-dictation/` (15:37-15:39 today). They have no effect on Buddy. My scratch files are in `/tmp/vd-scripts` (the 160 MB of `strings` output in `/tmp/vd` is already deleted). `/tmp/vd-pyte` holds a pip-installed `pyte`, used only for the repro.
