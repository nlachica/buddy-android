# Plan review: 022-watch-chat-view
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)
F1 [blocker] Implementation and verification plan is missing - `docs/features/022-watch-chat-view/plan.md:1-2` contains only the title and template comment, so none of the five done-when items in `docs/features/022-watch-chat-view/brief.md:19-23` has a proposed implementation or verification method. These are not already satisfied by the code: `wear/src/main/java/app/buddy/assistant/wear/TalkActivity.java:239-251` renders only the active `Talk.lines`, `:262` unconditionally scrolls down on redraw, `app/src/watchlink/java/app/buddy/assistant/WatchLink.java:73-86` has no history request handler, and `:156-158` relays messages only for tracked watch-started runs. -> Have the Planner fill in the design, concrete steps and tests before another review: bounded history transport and rendering; initial positioning and touch/rotary scrolling with reachable controls; updates for the viewed chat, including phone-started replies; reconciliation with live watch messages; request lifecycle/error handling; and same-release phone/watch plus CI verification. Use the research to choose a minimal approach rather than leaving these choices to the Coder.

VERDICT: REVISE
