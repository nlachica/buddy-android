# Verification: 002-runner-hang

Verifier: orchestrator (Opus 5.5), 2026-11-15.

| Done-when item | Evidence | Result |
|---|---|---|
| 1. Reproduced first | research.md "Reproduction": exit 3 / kill -9 gave done without error; orphan holding stdout hung with no done; TERM of the wrapper orphaned the run and sent no done (sandbox copy, no real chat) | pass |
| 2. Helper always error+done, every backend, traps | `bash linux/test/run.sh`: 28 fixtures and cases, 0 failed (on the phone, busybox awk) incl. new `run/` cases: claude killed/orphan/producer/runkill/term/agentterm/exit3/error/busy, codex/grok/opencode/agy killed. CI `helper-tests` ✓ (Ubuntu, mawk) on run 43684191514 | pass |
| 3. App ends runs whose helper is gone / silent with no live pid; sub-agent end releases parent | ChatHub/Helper/Helpers changes reviewed by Codex (2 rounds, round-1 mawk relay bug fixed); Java compiles (CI assemble ✓). Not exercised on device (needs the fork installed) | code + CI |
| 4. Hard ceiling per sub-agent run | `hub_helper_ceiling`, 30 min, armed in `startRun` for chats with a parent (covers note-started runs, plan review F3) | code + CI |
| 5. Tests + CI | helper-tests ✓, assembleGithubRelease/assembleFdroidRelease ✓; only the F-Droid reproducibility check fails, because the fork has no signing secrets (same on 001) | pass (env limit noted) |

HELPER_VERSION 30 → 31, so the app re-installs the helper.

VERDICT: SHIP (app-side behaviour to re-check on device after switching to the fork)
