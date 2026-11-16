# Plan review: 015-subagents-box-close
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Guard the in-place close-button update when no button exists - Design 4 resets `helpersClose` to null on every rebuild, creates no button for a closed box, and then specifies an unconditional `helpersClose.setVisibility(...)` on every refresh (`docs/features/015-subagents-box-close/plan.md:38-45`). The closed-path return only protects the rebuild: on the next refresh the signature matches, so execution reaches the in-place block. The existing helper-chat and empty-list returns are likewise inside the signature-change block (`app/src/main/java/app/buddy/assistant/ChatActivity.java:828-856`); a chat with no helpers even starts with the same empty signature. Following the stated steps therefore crashes on ordinary refreshes after closing, in helper chats, or in chats without helpers. -> Specify `if (helpersClose != null)` around the visibility update (or equivalent control flow covering every render). Add verification of a second refresh after closing, repeated refreshes in a helper chat, and opening a chat without helpers; the pure `boxClosed` tests do not exercise these paths.

F2 [major] Closing does not restore the parent's screen panel immediately - Design 5 claims the usual background panel reappears with "No new code", but the tap only saves and calls `renderHelpers()` (`docs/features/015-subagents-box-close/plan.md:47-52`). The panel was explicitly set GONE while the own card existed (`app/src/main/java/app/buddy/assistant/ChatActivity.java:870`); dropping that card does not undo its visibility. Restoration happens in the full `refresh()` through `backgroundPanel.setShown(resumed)` (`ChatActivity.java:2016-2017`, `app/src/main/java/app/buddy/assistant/BackgroundPanel.java:89-100`). `Sessions.save(ses)` only writes storage (`app/src/main/java/app/buddy/assistant/Sessions.java:289-291`), and the panel's picture tick exits while GONE (`BackgroundPanel.java:103-105`). With an idle parent, closing can therefore leave both its card and its normal screen controls inaccessible until another event refreshes the chat. -> Call the existing full `refresh()` after saving the close state instead of only `renderHelpers()`, or explicitly refresh the background panel on the close transition. Verify the parent screen and "Show on my screen" return immediately after closing with the parent and helpers idle, without navigation or another chat event.

VERDICT: REVISE

### Planner response (round 1)
F1: accepted - Design 4's in-place line is now `if (helpersClose != null) helpersClose.setVisibility(...)`, and it
says this covers a closed box, a helper chat and a chat without sub-agents. New test-plan row "no button, no crash":
a code walk (the only use outside the rebuild is guarded) plus device checks of refreshes after ×, a working
sub-agent's chat, and a chat that never had sub-agents. No new unit test: the null guard is view code, and
`ChatActivity` doesn't run on the JVM here.
F2: accepted - the tap now calls the full `refresh()` (as the speaker button already does) instead of
`renderHelpers()`. `refresh()` runs `backgroundPanel.setShown(resumed)` and then `renderHelpers()`. Design 5 no longer
says "no new code" and explains why the panel needs that call. The edge row now checks that the panel comes back right
after × with the parent and sub-agents idle, without navigation or another chat event, plus a code walk.

## Round 2 - Plan Reviewer (gpt-6-astra)

No new findings. Both Round 1 major findings are resolved in the revised plan:

- F1: Design 4 now guards the in-place update with `helpersClose != null` (`plan.md:45-47`), and the verification row covers subsequent refreshes after closing, helper chats, and chats without helpers (`plan.md:98`). This handles the existing signature-cache fall-through in `app/src/main/java/app/buddy/assistant/ChatActivity.java:828-856`.
- F2: The tap now calls full `refresh()` (`plan.md:49-57`), which restores the background panel before rendering the helpers (`app/src/main/java/app/buddy/assistant/ChatActivity.java:2016-2035`). The verification row explicitly checks immediate restoration with idle chats and no further event (`plan.md:97`).

The plan covers every done-when item without changing the delegate or bubble behavior. Checked the round's stable slot ordering and replacement ids (`app/src/main/java/app/buddy/assistant/Helpers.java:38-44,89-95`), session save/load integration (`app/src/main/java/app/buddy/assistant/Sessions.java:289-291,360-412`), card cleanup (`app/src/main/java/app/buddy/assistant/ChatActivity.java:1018-1022`), and independent report access through the bubble and Search (`app/src/main/java/app/buddy/assistant/Bubble.java:3050-3078`; `app/src/main/java/app/buddy/assistant/SearchActivity.java:121-139`). No blocker or major finding remains open. This is a plan/code inspection; implementation tests, CI, and the specified device checks remain to be performed.

VERDICT: APPROVE
