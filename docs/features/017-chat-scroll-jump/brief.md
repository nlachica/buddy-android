---
type: brief
feature: 017-chat-scroll-jump
created: 2026-11-17
---
# Brief: 017-chat-scroll-jump

## Ask (verbatim, never edit)
"there was a bug ... where it scrolls all the way up in this chat, and it's still bugged, and right now I'm scrolling
all the way down and then it just shoots back up, and it's really, really annoying." Follow-up: happens in both the
bubble panel and the full chat screen, jumps to the very top, random, while the agent works and also when idle.

## Goal
A chat never moves the user's scroll position on its own, except to follow new output while they are at the end.

## What is already known (curator's read of the code, not a diagnosis)
- 012-chat-scroll (fork.7) only gated "follow new output" to within 48dp of the end (Bubble.followEnd, ChatActivity.onMessage). It does not explain a jump to the TOP.
- Suspects, unverified: (1) ChatActivity.showAll() and Bubble.renderCurrent() call messages.removeAllViews() then rebuild; the ScrollView's scrollY clamps to 0 while it is empty, so any rebuild (onResume when lastMsg != lastShown at ChatActivity ~531, restoreEarlier, session change) lands at the top unless scrollToEnd or a saved scrollY follows. (2) ToolLog groups collapsing/finishing (log.finish()) shrink content while at the end. (3) helpersBox ticks (ChatActivity helperTick 300 to 1000 ms) relayout above the ScrollView. (4) focus: ScrollView scrolls to a newly focused child.
- Idle jumps point to a rebuild path or an onResume/refresh path rather than streaming output.

## Done when
- [ ] Root cause found with evidence (reproduce or log scrollY around rebuilds), not a guess.
- [ ] Rebuilds keep the user's position (or the end if they were at the end) in both Bubble and ChatActivity.
- [ ] CI green; Codex reviewer approves; checked on the phone by him.
