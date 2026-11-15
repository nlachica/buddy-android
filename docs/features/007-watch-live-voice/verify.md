# Verification: 007-watch-live-voice

| Done-when item | Evidence | Result |
|---|---|---|
| 1. Call from the watch | 2026-11-15, phone v2.3.9-fork.4 + watch APK from run 43775991469: Nicholas used the call button on the talk screen and spoke with Buddy: works. Locked phone and ending from the watch not separately reported | pass (core) |
| 2. Replies in the phone's voice, else watch TTS | ChatGPT voice on: "tell me a short joke" heard in ChatGPT's voice on the watch. Fallback cases (i-iv) not exercised on device; covered by code review rounds 1-3 | pass (core) |
| 3. Cost | Nicholas judged the added delay acceptable; switching back = phone Settings → Voice → phone voice | pass (subjective) |
| 4. CI | Branch run 43775991469 and release v2.3.9-fork.4 green, F-Droid check included | pass |

Not tried: fallback failures, half-duplex edge cases, locked-phone call. Diff +1859/-100 (17 files).

VERDICT: SHIP
