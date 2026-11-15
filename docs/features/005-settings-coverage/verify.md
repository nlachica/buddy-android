# Verification: 005-settings-coverage

| Done-when item | Evidence | Result |
|---|---|---|
| Inventory of every control | research.md (checked inventory, covered/excluded with non-goal) | pass |
| Tool lists and changes every non-excluded control | 44 JUnit tests; device listing on 2.3.9-fork.2 shows voice, audio_device, languages, models, reviewer + reviewer_model, builtin_risky_words, button lists, chat allowed apps, background_browser, accent/surface/face, services (no keys/addresses) | pass |
| JVM tests + CI | CI run 43756642973 green; release run for v2.3.9-fork.2 green | pass |
| Phone: approve/deny per new family | Nicholas skipped the per-family card matrix (2026-11-15); the shared gate was checked on device in 004 (deny and approve) | skipped by Nicholas |

VERDICT: SHIP (per-family device matrix skipped at Nicholas's choice)
