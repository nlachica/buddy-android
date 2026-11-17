# Plan review: {{SLUG}}
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Root-cause proof is marked complete without observing the offending scroll - `plan.md:7-8,115` treats the single focused-view snapshot in `research.md:41-48` as satisfying done-when 1. It proves that a reply holds focus, not that focus caused the reported jump. There is new contrary evidence to the stated explanation: AOSP `ScrollView.onSizeChanged` only reveals a focused descendant when `isWithinDeltaOfScreen(currentFocused, 0, oldh)` holds ([android14-release ScrollView.java, lines 1794-1808](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/widget/ScrollView.java#1794)); an old reply wholly above the viewport is not pulled back merely because the height changed. F2 also identifies a separate shrink path omitted from the diagnosis. -> Keep focus as a supported hypothesis, but leave this acceptance item pending. Restore the research's bounded diagnostic step: capture an actual before/after scrollY transition with viewport/content heights, focused-view bounds and the initiating path, or reproduce a controlled jump on the old build and show the same sequence fixed. Do not substitute absence of a focused reply for evidence that the jump is fixed.

F2 [major] Both live tool logs automatically shrink, so the focus-only fix still permits jumps - `plan.md:15` excludes ToolLog/StepLog changes, relying on the research's claim that ChatActivity's log stays collapsed unless the user opens it. In fact `ChatActivity.java:2563-2564` calls `setLive(ses.running)`, and `:2784-2799` opens the rows while live and hides them on finish unless user-toggled. Bubble does the same at `Bubble.java:3296-3297,3542-3557`. A user can scroll back into a tall automatically opened log; completion then removes the text being read and reduces the scroll range, potentially clamping scrollY to zero. Blocking focus does not prevent this. The proposed append-on-resume path also still reaches `log.finish()` through `show()` / `refresh()` (`ChatActivity.java:2567-2569,2069-2072`). -> Include this existing automatic height-change path in the fix and verification. The smallest direction is to retain/defer automatic collapse while the user is reading back, preserving explicit user collapse and follow-at-end behavior. Test a long live log finishing in both screens, including completion while the full chat is paused, and verify the visible reading position survives.

F3 [major] Same-chat bubble rebuilds still violate done-when 2 - `plan.md:70-72` declares the end correct for every `renderCurrent()` caller because they are user actions. Changing a model or closing its picker is not a request to jump to the latest message: `Bubble.java:1861-1864,1900-1903` calls `closePicker()`, which rebuilds the same session at `:1841-1845`; `renderCurrent()` removes every message view and unconditionally scrolls to the end at `:2100,2119`. This explicitly leaves a same-chat rebuild losing the reader's position, despite the brief requiring rebuild preservation in both surfaces. -> Distinguish a different-chat navigation from returning to the same chat. For the picker, prefer reusing the existing message views and restoring list/chat visibility instead of rebuilding them; Bubble already continues receiving messages through `onMessage` (`:2198-2199`). Preserve position/end state for any remaining same-session rebuilds. Add a device case: read midway, open and close/change the model picker, return to the same visible text; retain end-on-navigation for a different chat.

F4 [major] Trim compensation double-counts removals before the next layout - `plan.md:73-77,100-102` calculates every cut from the survivor's absolute pre-layout `getTop()` and immediately subtracts it from scrollY. With two arrivals before layout, those tops still include all previously removed children. Example: two 100px leading rows are removed by successive calls; the planned cuts are 100 then 200, moving scrollY from 1000 to 700 although the correct offset is 800. The old child positions are not recomputed by `removeViewAt`; the existing mutation happens synchronously in `Bubble.java:3381-3383`, with callbacks delivered by `ChatHub.java:1061` and no intervening-layout guarantee. Thus the proposed fix introduces an upward jump during bursts. It also cannot retain text if the row being read is itself evicted. -> Prefer deferring trimming while reading back and catching up when following resumes, or use one stable surviving-view anchor and restore its offset once after layout, accounting for all removals together. Add a behavioral check for two trims before layout, variable-height rows, and a viewport intersecting the oldest row; the proposed source-token guard does not exercise this arithmetic.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted in part. Done-when 1 is no longer marked done. It stays pending until a controlled repro has run on the
phone, which is your second option, so no debug-logging build (the orchestrator ruled the cause proven; logging stays
the fallback). Your `onSizeChanged` point is right and is in the plan: it only reveals a focused view that was in the old
viewport. Checking it turned up the path that does pull from far away, read in AOSP android14 (plan.md Design, steps
1-4). Every full-chat reply carries a cursor at 0 (`ArrowKeyMovementMethod.initialize`, ArrowKeyMovementMethod.java:363).
`fullScroll` gives focus to a reply nobody touched: `findFocusableViewInBounds` compares parent-relative `getTop()`, and
replies sit in a wrapper, so the ScrollView falls back to FocusFinder (ScrollView.java:1129-1132, 1704-1728). The child
height spec carries the ScrollView's height (:1478-1480), so a new chat-area height re-measures the replies near the
top. `TextView.onMeasure` registers pre-draw (TextView.java:11018-11021), and `onPreDraw` → `bringPointIntoView` →
`requestRectangleOnScreen` when focused (:8474, :11591) → `smoothScrollBy` to that reply's line (ScrollView.java:1603-1614,
1732-1739). This also matches the snapshot: an *older* reply focused. The repro in the Test plan uses this chain on the
installed fork.8: tap the first reply, go to the end, show the voice bar → expected jump; the same on fork.9 → no move.
It also changed the full-chat fix. The old plan's hint plus `onSizeChanged` left `requestChildRectangleOnScreen` open
(its Risk 1, which is this jump). It is now one override of `computeScrollDeltaToGetChildRectOnScreen`, the method all
three reveal paths call. The bubble's replies can't take focus in touch mode (LinkMovementMethod gives `FOCUSABLE` only,
TextView.java:2763-2766), so for the panel the plan says plainly that its jump has no confirmed mechanism (Risks 2).

F2: accepted - `ToolLog.finish()` and `StepLog.finish()` fold only if the user is at the end or the log was never laid out:
`!(view.isLaidOut() && !atEnd())`. Rebuilt logs fold as today. A live log the reader is in or above stays open. This
covers every `finish()` caller, including the full chat's resume append and `refresh()` after a run that ended while
paused. Device rows added for both screens and the paused case.

F3: accepted - new `backToChat()`. For the same chat (`renderedFor == sessions.current`) it keeps the message views (the
picker and list only hide `scroll`, and `onMessage` keeps appending) and scrolls to the end only if the user was at the
end. A different chat goes through `renderCurrent()` at its end, as today. Used by `closePicker()`, the pill's same-chat
branch (:988) and the chat-list row (:2014-2018). Device rows (f)-(h) added.

F4: accepted - your first option: the trim runs only when following (`kind == USER || atEnd()`), together with
`scrollToEnd()`. While reading back, nothing is removed above the reader, and the next message at the end trims the
excess at once. The compensation arithmetic is gone, so the case it got wrong (two trims before one layout, an evicted
row) can't happen. Device row (e) checks it.
