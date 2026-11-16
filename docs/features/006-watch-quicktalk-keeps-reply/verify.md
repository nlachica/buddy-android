# Verification: 006-watch-quicktalk-keeps-reply

| Done-when item | Evidence | Result |
|---|---|---|
| 1. Story, then "four", each once | 2026-11-15, Watch4 with CI build 43766489010 watch APK (adb install Success): quick talk "write a 400-word story about a fox", second quick talk "what is two plus two" while working → Nicholas heard the story, then four | pass |
| 2. Reopen and cancel the mic keeps the first answer | Same build: long story, quick talk, mic cancelled → story still spoken | pass |
| 3. 003 behaviour unchanged | Quick talk with nothing pending opened listening (the first press of test 1); unseen-reply-first path unchanged per code review round 1, not exercised on device | pass (partly by review) |
| 4. CI green, APK on watch | Run 43766489010 green (workflow_dispatch on the branch); installed on the Watch4 | pass |

Diff scope: wear/ only (ChatsActivity, Talk, TalkActivity), +136/-47.

VERDICT: SHIP
