---
type: brief
feature: {{SLUG}}
created: {{DATE}}
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: {{SLUG}}

## Ask (verbatim, never edit)
{{ASK}}

## Goal
Talk with voice_input=claude put '/voice hold ⎿ Turned on voice mode (hold)...' into the chat instead of dictation. diagnosis.md: ClaudeDictation.promptText() scrapes the history echo. Smallest fix only: accept only the real input-box ❯ line (line above starts with ─), reject /voice echo text, and wait for 'Turned on voice mode' instead of a fixed 1500 ms sleep. Unit test with a screen seam. Upstream code: keep the change minimal so a future upstream merge is easy.

## Non-goals

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] promptText never returns the /voice echo; returns the real box text.
- [ ] ensureSession waits for voice mode on (bounded).
- [ ] Unit test; CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->

## Nicholas (important)
Talk with voice_input=claude worked before 2026-11-18's releases (fork.3: 026, fork.4: 027), and no upstream code came in. So something we shipped (or the Claude CLI moving to 2.1.328) is the real trigger; the echo scrape is only the symptom. First find the regression: diff v2.3.12-fork.2..v2.3.12-fork.4 for anything touching the mic, audio routing (026 AudioRoute/CodexLive/Gate), the dictation session, MicServer, ChatHub/Schedules (027) or prompts/sessions. Fix the cause; keep the promptText guard as the small safety net.
