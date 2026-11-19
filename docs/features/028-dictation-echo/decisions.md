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
