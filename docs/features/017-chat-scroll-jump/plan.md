# Plan: 017-chat-scroll-jump
<!-- Planner (claude-opus-5-5), 2026-11-17. Sources: brief.md, research.md (incl. Device evidence), code at 807bc07,
     AOSP android14-release Editor.java / TextView.java / ScrollView.java (fetched 2026-11-17, quoted under Design). -->

## Goal
A chat (bubble panel and full chat screen) never moves the user's scroll position on its own, except to follow new
output while they are at the end (012's 48dp rule, kept as is). Done when: (1) the root cause is shown with evidence
(done in research.md "Device evidence": a reply nobody touched is `{focused}`; ScrollView scrolls to its focused child);
(2) rebuilds keep the user's place, or the end if they were at the end, in both screens; (3) CI green, Codex approves,
Nicholas checks it on the phone.

Non-goals: keeping the end in view when the chat area shrinks (helpers card, strip, keyboard) while at the end, which
is 012 behaviour and not a move; keeping the place across an activity re-creation or process death (there is no saved
scroll today); "Load earlier" (`restoreEarlier`) and Bubble's chat switches still land at the end, since the user asked for them;
hardware-keyboard navigation in the bubble panel's messages; any change to the ToolLog/StepLog, search or the 48dp rule.

## Design

**Root cause (accepted, research.md).** Message views can hold focus, and ScrollView scrolls to its focused
descendant: `fullScroll(FOCUS_DOWN)` itself moves focus (`scrollAndFocus` → `requestFocus`), focus falls back to an
on-screen reply when a focused view is removed (`showAll`'s `removeAllViews`), and a focused reply is pulled back on
screen by `requestChildFocus` → `scrollToDescendant` (ScrollView.java:1683-1693, deferred through `mChildToScrollTo` to
`onLayout`, :1766) and by every height change, `onSizeChanged` → `doScrollY` (:1794-1810, not gated by any hint).

**Constraint found while planning: in the full chat, a reply must be able to take focus.** Its text is selectable
(`setTextIsSelectable(true)`, ChatActivity.java:2655), and in AOSP both selection and link taps need focus:
- `Editor.checkField()`: `if (!mTextView.canSelectText() || !mTextView.requestFocus()) { ... "Selection cancelled." }`
  (Editor.java:2554-2561). A reply that can't take focus can't be selected.
- `TextView.onTouchEvent`: `touchIsFinished = (action == ACTION_UP) && ... && isFocused()`, and link taps in
  selectable text open only `if (touchIsFinished && mLinksClickable && mAutoLinkMask != 0 && textIsSelectable)`
  (TextView.java:12757-12780). A reply that can't take focus has dead links.
So "FOCUS_BLOCK_DESCENDANTS on messages" or "non-focusable reply text" would break selection and links in the full
chat, which the orchestrator asked to keep. The two screens therefore get different mechanisms. The rule is the same in
both: **nothing moves focus programmatically, and the ScrollView never scrolls because of focus.**

**Bubble panel: block focus in the messages.** Nothing in Bubble's `messages` needs focus. There is no selectable text
(grep: no `setTextIsSelectable` in Bubble.java); links use `LinkMovementMethod` (`Ui.linkifyForService`, Ui.java:670), whose
`onTouchEvent` handles taps without focus (no `isFocused()` on that path, TextView.java:12760-12765); copy is
`setOnLongClickListener` and buttons, which work without focus in touch mode. The messages hold only text, logs, pictures
and copy buttons, no EditText (Bubble.java:2109, 3269, 3294, 3381). So
`messages.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS)` closes every focus path (fullScroll,
fallback focus, non-touch mode in the overlay window), and `scrollToEnd` stops using `fullScroll`.

**Full chat: replies may take focus from the user's touch, and the ScrollView ignores it.**
1. `scrollToEnd` without `fullScroll`. This removes the programmatic grab seen on the device.
2. `replyText`: `tv.setRevealOnFocusHint(false)` (API 26, minSdk 30). `requestChildFocus` reads exactly this hint
   (ScrollView.java:1684), so a tap on a tall reply, or focus falling back to a reply during a rebuild, no longer scrolls.
3. `scroll` becomes an anonymous `ScrollView` subclass (as Bubble's already is, Bubble.java:421-427) whose
   `onSizeChanged` calls `super` only when no descendant holds focus (`findFocus()` null or the ScrollView itself). Then
   the helpers cards, running strip, approval cards or keyboard changing the chat's height no longer pull it to a focused
   reply. With no focus inside, `super` does nothing focus-related, so nothing else changes.

**scrollToEnd (both screens):** `scroll.post(() -> scroll.smoothScrollTo(0, messages.getBottom()))`. `fullScroll` scrolled
through `doScrollY` → `smoothScrollBy`, and `smoothScrollTo` is that same call without the focus step. The motion stays
the same and 012's follow gate sees the same scroll positions. `smoothScrollBy` clamps to the content (no
`Integer.MAX_VALUE`: `scrollTo`'s clamp overflows on it).

**Rebuilds (done-when 2).**
- *Full chat, `onResume`.* Today it always ends with `scrollToEnd()` (ChatActivity.java:533-534), so coming back from a
  link, another app or the lock screen while reading back lands at the end. New: measure `atEnd()` first (the 012
  check, moved out of `onMessage` into a helper used by both). Before the first layout it is true, so a newly opened
  chat still opens at the end.
  - At the end, or when `lastShown` is no longer in the list (chat cleared or replaced): do as today, `showAll()` if
    needed, then `scrollToEnd()`.
  - Reading back: no rebuild. Append the messages after `lastShown` with `show(m)`, which is what `onMessage` would have
    done had the screen been visible, then set `lastShown` and don't scroll. Nothing above the user's place is laid out
    again, so async picture heights and opened tool logs can't shift it. `refresh()` still finishes the tool log
    (ChatActivity.java:2069-2072).
- *Full chat, "Show earlier".* Already anchored (`scrollTo(y + Δheight)`, :427), with no focus involved. Unchanged.
- *Bubble `renderCurrent`.* Every caller is a chat switch, opening the chat from the list, new or deleted chat, closing
  the picker (all user actions), or a restyle or face change while collapsed (Bubble.java:1054-1062, 3193). End is
  right. Unchanged.
- *Bubble page trim.* `while (count > PAGE) removeViewAt(0)` (Bubble.java:3382) drops the oldest view above a user who
  is reading back, so the text under them moves by that view's height whenever a reply arrives. That breaks the goal, so
  it gets fixed: before removing, note the first survivor's `getTop()`. After removing, if it was laid out, `cut = oldTop
  - paddingTop - its topMargin`. Then, when not following, `scroll.scrollBy(0, -cut)`. `followEnd()` returns whether it
  followed, so its pre-layout check is made before the shift.

Alternatives considered:
- `FOCUS_BLOCK_DESCENDANTS`, or `setFocusable(false)` on replies, in the full chat: breaks selection and links (above).
- `clearFocus()` on scroll or when a selection ends: focus falls back through `rootViewRequestFocus` to another reply
  on screen or to the input. That just moves the problem.
- Skipping `super.onSizeChanged` always: same effect, but it also drops vendor (Samsung) work when nothing is focused.
- Instant `scrollTo` for the end: changes 012's feel. During a burst, `smoothScrollBy` already scrolls instantly (250 ms gap).
- Rebuilding on resume anchored on the first shown message: re-layout above the place (async thumbnails, tool logs
  reset to closed) can still shift it, and appending can't.
- One shared ScrollView subclass for both screens: Bubble doesn't need the override once its messages are blocked.
  One use, so no abstraction.

## Steps
1. **Bubble: no focus in messages, end without fullScroll.** `Bubble.java`: after `messages` is created (~:427-430)
   `messages.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS)`, with a one-line why (017: a focused reply
   pulled the panel back to it). `scrollToEnd()` (:3568-3570) → `smoothScrollTo(0, messages.getBottom())` in the post.
2. **Full chat: the ScrollView ignores focus.** `ChatActivity.java`: `scroll` (:195) → anonymous subclass overriding
   `onSizeChanged` (super only if `findFocus()` is null or `this`), commented. `replyText()` (:2653-2662) adds
   `tv.setRevealOnFocusHint(false)`. `scrollToEnd()` (:2679-2681) → `smoothScrollTo(0, messages.getBottom())`.
3. **Full chat: resume keeps the place.** `ChatActivity.java`: `private boolean atEnd()` from `onMessage`'s check
   (:636-637), and `onMessage` uses it. `onResume` (:530-534) does the at-end / reading-back logic from Design, in
   about 8 lines.
4. **Bubble: the page trim keeps the place.** `Bubble.java`: `followEnd()` (:3563-3566) returns `boolean`; the trim in
   `showMessage(Kind, String)` (:3381-3383) computes `cut` as in Design and does `scroll.scrollBy(0, -cut)` when
   `kind != USER && !followEnd() && cut > 0`.
5. **Guard test.** `app/src/test/java/app/buddy/assistant/ChatScrollTest.java` (plain JUnit, reads
   `src/main/java/app/buddy/assistant/{Bubble,ChatActivity}.java` from the module dir, Gradle's default test workdir).
   It asserts that neither contains `fullScroll(`, that Bubble contains `FOCUS_BLOCK_DESCENDANTS`, and that
   ChatActivity contains `setRevealOnFocusHint(false)` and `onSizeChanged`. The fork merges upstream regularly, and
   AGENTS.md's sync keeps "features and their tests", so the test is what stops an upstream merge from bringing
   `fullScroll` back.

Release (orchestrator, after merge, per AGENTS.md "Updating Buddy on the phone"): `2.3.10-fork.9`, code 220.

## Test plan
| Done-when | Proof |
|---|---|
| 1 Root cause with evidence | Already in research.md "Device evidence" (`{focused}` reply in `read_screen`, AOSP paths). After install: the same scenario's `read_screen` shows no `{focused}` message view in the bubble panel at any time, and none in the full chat unless he tapped one. |
| 2 Rebuilds keep the place | Device, full chat: (a) read back mid-chat while the agent runs, leave for another app, come back: same text on screen, new messages below; (b) at the end, leave, come back: at the end with the new messages; (c) open a chat from the list or a notification: at the end; (d) "Show earlier": stays on the message that was at the top. Bubble: (e) a chat with more than 150 items, read back while replies arrive: the text doesn't move; (f) switch chats: the new one opens at the end. |
| 2 / goal: no jump (both screens) | Device: long chat, at the end, idle and while running (helpers cards and strip coming and going, keyboard open and closed): stays put, new output followed (012). Full chat: tap a reply, scroll so it's half visible, start a run so the strip appears: no move. Scroll up while running: never moves. |
| Kept behaviour | Full chat: long-press selects reply text and Copy works; a link in a reply opens; long-press on a user message copies; the copy icon copies the reply. Bubble: a link in a reply opens; long-press copies; tapping the conversation still hides the keyboard. |
| 3 CI / review | `./gradlew testGithubDebugUnitTest` incl. `ChatScrollTest`; PR CI (`build.yml`) green; Codex code review APPROVE. |
| 3 Phone | Nicholas runs the device rows above on the installed fork (after-switch checks; verify.md rows stay `pending` until he has). |

## Risks
1. **A tapped reply stays focused in the full chat.** Two of its reveal paths are closed (hint, `onSizeChanged`). One is
   left: `TextView.bringPointIntoView` → `requestChildRectangleOnScreen`, which runs only on a selection change (user
   driven) or a relayout of that reply (its text never changes after creation). If a jump is still seen after a tap,
   the next step is to override `requestChildRectangleOnScreen` in the same subclass.
2. **Vendor ScrollView** (Samsung One UI) work in `onSizeChanged` is skipped while a reply is focused. Nothing in AOSP
   is skipped (the `super` chain is View's empty method).
3. **Non-touch mode** (hardware keyboard): in the full chat, key navigation to links or buttons still scrolls, which the
   user drives. In the bubble panel, messages can't be reached by keys. TalkBack is unaffected (accessibility focus is
   separate from input focus).
4. **Trim compensation during a fling** jumps by the trimmed height, the same as today, so it is no regression (the
   scroller works in absolute positions).
5. **Resize while at the end** still hides the last lines and can switch 012's following off. This is today's behaviour
   and a non-goal (see Goal). If Nicholas wants "stick to the end", it is a separate feature.

## Open questions
None blocking. One design pick is recorded in decisions.md for Nicholas to overrule: when he comes back to the full chat
while reading back, a message he sent meanwhile from the bubble or the watch is appended without pulling him to the end.
