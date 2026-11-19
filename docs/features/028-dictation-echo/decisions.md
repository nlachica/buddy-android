# Decisions: {{SLUG}}
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-19 - How promptText recognises the input box
- Options: (a) scan bottom-up for the last `❯` line whose line above starts with `─`; (b) take the last `❯` line and
  return "" unless the line above starts with `─`; (c) text guard only.
- Choice: (a), plus the `/voice` / `Turned on voice mode` text guard the brief asks for. (a) is as small as (b), and it
  also skips a `❯` menu line below the box. (c) leaves other non-box `❯` lines (history, errors) readable.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - Seam for the unit test
- Options: (a) a pure `promptText(String screen)` overload, with `promptText()` as a one-line wrapper; (b) feed bytes
  through a real `TermEmulator`/`TermSession`; (c) an injectable screen-supplier field.
- Choice: (a). It is additive and keeps callers and names unchanged, so the upstream merge stays small. `TermSession`'s
  constructor is private. (c) is a new abstraction with only one use.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - What ensureSession does if voice mode doesn't come on
- Options: (a) poll up to 15 s for voice mode on, then carry on as today (`clearPrompt()`); (b) on timeout,
  finish the session and throw `dictation_claude_start_failed`; (c) poll with no bound.
- Choice: (a). The brief asks for a bounded wait instead of the sleep, and the diagnosis fix says "then clearPrompt()".
  With the box rule, a session where voice mode is off can no longer leak the echo, only "no speech". (b) is a new
  failure path with a session reset, more than the brief's "smallest fix". Nicholas can ask for (b) later.
  15 s: `/voice hold` takes 3-4 s on the phone (diagnosis), with headroom for a cold start under proot.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - Wait loop shape
- Options: (a) inline loop shaped like upstream's prompt wait, reusing `until`; (b) a shared `waitFor(text, ms)` helper
  for both waits; (c) a named constant for the voice-on string.
- Choice: (a), with the string inline. (b) rewrites upstream lines and (c) adds a hunk, both against the brief's
  "keep the change minimal". The file already repeats inline literals.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - Which text means "voice mode on" (wait and echo guard)
- Options: (a) `Turned on voice mode` (the brief's words); (b) `Hold space to record`; (c) any `⎿` line under
  `❯ /voice hold`; (d) a list of every wording seen.
- Choice: (b). The hidden session's logs show the CLI rewords the message each release: 2.1.325 "Voice mode enabled",
  2.1.326 "Voice mode is on", 2.1.327 and 2.1.328 "Turned on voice mode". Only "(hold). Hold space to record." stayed the
  same. (a) already misses 2.1.326, and each miss costs the full 15 s. (c) relies on two glyphs and the echo layout. (d)
  breaks on the next wording. The guard also rejects text starting with `/voice`.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - Research before coding (Nicholas's clarification)
- Options: (a) code Part A (safety net) now and research the cause in parallel; (b) research first, then plan Part B
  and code both under one review.
- Choice: (b). The clarification says "First find the regression". The facts found (CLI 2.1.327's first dictation
  session came before fork.3, and the `/voice` wording changed from "is on" to "Turned on") point at the CLI. If that
  holds, Part A's wait may be the cause fix itself, and one review should see both.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - Echo guard: `/voice` prefix only (replaces the `Hold space to record` part of the entry above)
- Options: (a) keep rejecting any box text that contains `Hold space to record`; (b) reject only text that starts
  with `/voice`; (c) also reject text that contains `⎿`.
- Choice: (b). Plan review round 1 F2: (a) blanks real dictation such as "Hold space to record a memo". The `/voice
  hold` command, typed or echoed with its output, always starts with `/voice`, and the box rule already skips other
  history. (c) adds nothing that (b) and the box rule miss. `Hold space to record` stays as the readiness wait's signal only.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - If voice mode doesn't come on: drop the session and use the existing error (replaces choice (a) above)
- Options: (a) carry on after 15 s (the old choice); (b) `session.finish(); session = null;` and throw
  `dictation_claude_code_no_start`; (c) carry on, but mark the session not reusable (`sessionLang = null`).
- Choice: (b). Plan review round 1 F3: under (a), `ready()` stays true for a session whose voice mode never came on,
  so every later tap reuses it and loses the speech. (b) uses existing strings and paths: `listen` reports
  start-failed, `prepare` ignores it, and the next tap starts fresh. `session = null` is needed because `finish()` only
  hangs up. (c) hides the failure as "no speech" and still restarts on every tap. Cost: if the CLI drops `Hold space to
  record`, every tap fails loudly until a one-string fix. No unit test for this branch: `ensureSession` needs
  `Linux.exec` and a pty, so a seam would grow upstream's diff. Proof is a diff check and the device check.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - Gate for Part B (the cause)
- Options: (a) ship Part A as the fix, with the cause inferred; (b) code Part B only after a controlled comparison names
  the cause and gives a repro that fails before the fix and returns the words after it.
- Choice: (b). The brief's clarification says to fix the cause, and plan review round 1 F1 showed that the 3-4 s delay
  alone doesn't explain it. Q1 changes one variable at a time: only the CLI, or, with the CLI fixed, only Buddy's
  timing (`prepare` stops MicServer about 1.5 s after `/voice hold`). If that proves Part A's wait is the cause fix,
  Part B adds no code.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - A session is ready only once voice mode is on (replaces "drop the session" above)
- Options: (a) `session.finish(); session = null;` at the voice-wait timeout only (round 1); (b) the same at both
  startup timeouts (plan review round 2 F2); (c) clear `sessionLang` with `session = null`, drop its assignment at line
  194, and set it only after the `Hold space to record` wait succeeds.
- Choice: (c). Round 2 F2: under (a), a prompt timeout at line 200 left a live session with `sessionLang` set, so the
  next same-language tap skipped `/voice hold`. (c) covers every throw (both timeouts, PREP, interrupt) with fewer
  lines than (b), and leaves upstream's line 200 unchanged. The existing line 181 finishes the half-started session on
  the next call. Cost: that session keeps running until then, as it already does after upstream's prompt timeout.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - A cancelled attempt's start error is dropped
- Options: (a) in `listen`'s catch, `if (listener == l) end(l, …)`; (b) `if (listener != l) return;` at the top of
  `end()`; (c) leave it.
- Choice: (a). Round 2 F3: A4's 15 s wait makes it likely that a cancelled tap's error arrives during the next tap,
  and `end()` then stops the shared microphone and hides the bar. `cancel()` already sent that listener `onEnd(null)`.
  (a) changes one line on the only path concerned, whereas (b) changes a shared method's meaning and (c) keeps the bug.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - Echo guard matches Buddy's command only (replaces the `/voice` prefix entry above)
- Options: (a) `startsWith("/voice")`; (b) `(t + " ").startsWith("/voice hold ")` on the normalized text.
- Choice: (b). Round 2 F4: (a) blanks real box text like `/voicemail`. Buddy only ever sends `/voice hold`, and its
  typed and echoed forms both match (b). Positive fixture T11.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - Proof for the startup-failure fixes: device fault checks, no unit test
- Options: (a) a seam for `ensureSession`/`listen` so JUnit can drive them; (b) a diff check plus device fault checks
  with a one-shot stand-in `claude` (the symlink points at a script that fakes a bare box N times, then execs the real
  binary).
- Choice: (b). The repo has no Robolectric, and these methods need `Linux.exec`, a pty and a Looper. A seam would grow
  upstream's diff, against the brief. (b) runs the real code on the phone after release: D1 covers a failed warm-up
  followed by a tap, and D2 covers cancel plus immediate retry.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - The cause test is a working/failing pair on the phone (refines "Gate for Part B")
- Options: (a) pty only, with a WAV at MicServer's socket, comparing Buddy's timings on 2.1.328; (b) on the phone with
  Buddy at fork.4 and only the CLI swapped (2.1.326 against 2.1.328), with the pty used only to record screens.
- Choice: (b). Round 2 F1: (a) bypasses the microphone and routing (026), and a longer wait helping on 328 doesn't
  explain why 326 worked. (b) holds Buddy fixed and uses the real input path. If both CLIs fail, the next comparison
  is the phone's Live-call state (Q3). If no failing setup is found, Nicholas decides (Q5).
- By: Planner (claude-opus-5-5)
