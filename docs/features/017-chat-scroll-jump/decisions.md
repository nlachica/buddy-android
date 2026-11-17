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

## 2026-11-17 - Full chat: one override, `computeScrollDeltaToGetChildRectOnScreen` returns 0 (supersedes option (c) above)
- Options: (c) `setRevealOnFocusHint(false)` + `onSizeChanged` override; (c) + `requestChildRectangleOnScreen` override;
  override the one method all three reveal paths call; `FOCUS_BLOCK_DESCENDANTS`.
- Choice: the one override, plus no `fullScroll`. Plan review round 1 led to the far-jump path in AOSP: a focused
  reply's cursor → `onPreDraw` → `bringPointIntoView` → `requestRectangleOnScreen` on every new chat-area height. (c)
  leaves that open. Blocking focus still breaks selection and link taps. Nothing inside `messages` needs a reveal:
  approval, helpers and input sit outside the ScrollView. Cost: no "show on screen" for TalkBack or arrow keys inside
  the messages (plan Risk 1).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Done-when 1 proof: AOSP chain + controlled repro on the phone, no logging build
- Options: debug-logging build (research.md plan); controlled repro on the installed fork.8 and again on fork.9.
- Choice: the repro (tap a top reply, go to the end, change the chat area's height). The chain predicts it exactly, and
  it needs no extra release. Logging runs only if fork.8 won't reproduce, or the panel still jumps on fork.9. The
  bubble's own jump has no confirmed mechanism (its replies can't take focus in touch mode), and the plan says so.
- By: Planner (claude-opus-5-5), plan review F1

## 2026-11-17 - Bubble: back to the same chat keeps its views (supersedes "renderCurrent unchanged")
- Options: always `renderCurrent` at the end (today); anchored rebuild; reuse the views.
- Choice: `backToChat()` reuses the views for the same chat (picker, chat list, pill) and goes to the end only if the user
  was there. The picker and list only hide `scroll`, and `onMessage` keeps appending. A different chat still opens at its end.
- By: Planner (claude-opus-5-5), plan review F3

## 2026-11-17 - Bubble page trim deferred while reading back (supersedes "page trim compensated")
- Options: `scrollBy(-cut)` compensation; one surviving-view anchor restored after layout; trim only when following.
- Choice: trim only when following, because it needs no arithmetic. The compensation was wrong for two trims before one
  layout and for an evicted row. The panel may hold more than PAGE views until the user is back at the end.
- By: Planner (claude-opus-5-5), plan review F4

## 2026-11-17 - Tool logs don't fold under a reader
- Options: leave the automatic fold; compensate the scroll; fold only at the end.
- Choice: `finish()` folds only if the user is at the end or the log was never laid out (rebuilds fold as before). It is
  one condition per screen, and a fold under a reader moves the text, against the brief's goal. The kept-open log stays
  open until tapped or redrawn.
- By: Planner (claude-opus-5-5), plan review F2

## 2026-11-17 - Source guard test tokens updated
- Choice: `ChatScrollTest` asserts no `fullScroll(` in either file, `FOCUS_BLOCK_DESCENDANTS` in Bubble, and
  `computeScrollDeltaToGetChildRectOnScreen` in ChatActivity (replaces the hint/`onSizeChanged` tokens).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Full chat: close only the automatic reveals (supersedes "one override, computeScrollDelta… returns 0")
- Options: the blanket `computeScrollDeltaToGetChildRectOnScreen` override; non-focusable replies or
  `FOCUS_BLOCK_DESCENDANTS` (the orchestrator's direction); two narrow overrides.
- Choice: two overrides on the full chat's ScrollView. `requestChildRectangleOnScreen` passes only `immediate` requests
  or those made while the focused reply has a selection. `onSizeChanged` skips super while a descendant holds focus
  (View's is empty, so only ScrollView's reveal goes). Together with no `fullScroll`, nothing focuses a reply on its
  own, and a touched reply's cursor or a resize never scrolls the chat. The blanket override also dropped TalkBack's
  show-on-screen, arrow-key reveal and selection-handle drags (plan review round 2 F3). Blocking focus breaks selection
  and link taps, which the orchestrator asked to keep. `requestChildFocus` (tap or key focus) and `arrowScroll` stay.
- By: Planner (claude-opus-5-5), plan review F3

## 2026-11-17 - Tool logs: one guarded fold for every automatic close (amends "Tool logs don't fold under a reader")
- Options: guard only `finish()`; guard `setLive(false)` and `finish()` through one `fold()`.
- Choice: `fold()` in both log classes, called by `setLive` when going idle and by `finish()`. `setLive(false)` folded
  before `finish()`'s guard ran, and Bubble's `updateHeader()` calls `setLive` directly (plan review round 2 F2).
- By: Planner (claude-opus-5-5), plan review F2

## 2026-11-17 - A panel jump left on fork.9 fails 017
- Choice: no speculative panel fix now (no confirmed mechanism), but a reproduced panel jump after the fix fails
  verification. The logging plan runs on the panel, and the evidence goes back into this feature's pipeline. The
  brief names both screens.
- By: Planner (claude-opus-5-5), plan review F1

## 2026-11-17 - backToChat repaints the header
- Choice: `backToChat()` calls `updateHeader()` after restoring chat mode, replacing what `renderCurrent()` →
  `refreshStatus()` did. `closePicker()`'s own extra `updateHeader()` goes.
- By: Planner (claude-opus-5-5), plan review F4

## 2026-11-17 - Guard test token for the full chat
- Choice: `ChatScrollTest` checks `requestChildRectangleOnScreen` in ChatActivity (replaces
  `computeScrollDeltaToGetChildRectOnScreen`). `onSizeChanged` already occurs elsewhere in ChatActivity (:808), so
  it is no use as a token.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Coder: small points the plan left open
- `backToChat()` for a different chat just calls `renderCurrent()` (its `refreshStatus()` paints the header); `updateHeader()` runs only on the same-chat path. `closePicker()` stays as a one-line caller so its two call sites don't change.
- Full chat `onResume`: a Search `found` view still wins over everything (as today); otherwise it scrolls to the end only if at the end or `lastShown` is gone.
- `ToolLog`/`StepLog.setLive(on)`: when `on` it opens the rows as before; when not, it goes through `fold()`.
- By: Coder (claude-sonnet-5-5)
