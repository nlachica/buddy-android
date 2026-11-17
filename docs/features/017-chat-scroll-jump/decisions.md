# Decisions: {{SLUG}}
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-17 - Full chat: replies keep focusability; the ScrollView stops scrolling for focus
- Options: (a) FOCUS_BLOCK_DESCENDANTS on `messages`; (b) `setFocusable(false)` on reply text; (c) no fullScroll +
  `setRevealOnFocusHint(false)` on replies + ScrollView subclass skipping `onSizeChanged`'s focus reveal; (d) clear
  focus on scroll / selection end.
- Choice: (c) because AOSP needs focus for both selection (`Editor.checkField` → `requestFocus`, Editor.java:2554)
  and link taps in selectable text (`touchIsFinished ... && isFocused()`, TextView.java:12757), which (a)/(b) would
  break; (d) only moves focus to another reply or the input via `rootViewRequestFocus`. Deviates from the
  orchestrator's "message views never take focus" for the full chat only, with this evidence.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Bubble panel: FOCUS_BLOCK_DESCENDANTS on messages
- Options: same mechanism as the full chat; block focus in `messages`.
- Choice: block, because nothing there needs focus (no selectable text; LinkMovementMethod taps and long-press copy
  work without it) and it closes every focus path, including non-touch mode in the overlay window.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - scrollToEnd = post(smoothScrollTo(0, messages.getBottom())) in both screens
- Options: instant `scrollTo`; `smoothScrollTo`.
- Choice: `smoothScrollTo`, because fullScroll already scrolled through `doScrollY` → `smoothScrollBy`; same motion and
  same positions for 012's 48dp gate, minus the focus move.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Full chat onResume: end only if at the end; reading back appends missed messages without a rebuild
- Options: always end (today); anchored rebuild on the first shown message; append like `onMessage`.
- Choice: at the end (or `lastShown` gone): as today. Reading back: append after `lastShown`, no scroll. Nothing above
  the place re-lays out (async thumbnails, tool-log open state), unlike an anchored rebuild. A message sent meanwhile
  from the bubble/watch does not pull to the end while reading back (Nicholas may overrule).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Bubble renderCurrent unchanged; page trim compensated
- Options: keep the place on every renderCurrent; leave all as is; fix only the trim.
- Choice: renderCurrent callers are chat switches, user actions or run while collapsed, so the end is right. The trim
  (`removeViewAt(0)` past PAGE) moves the text under a reading user on every reply, against the brief's goal, so it gets
  `scrollBy(0, -cut)` when not following.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Source guard test against fullScroll returning
- Options: no test (view behaviour isn't JVM-testable here); Robolectric; a plain source check.
- Choice: plain JUnit source check (`ChatScrollTest`), because upstream syncs keep "features and their tests", and this
  is the cheapest thing that fails if a merge brings `fullScroll` or drops the focus guards. No new test dependency.
- By: Planner (claude-opus-5-5)
