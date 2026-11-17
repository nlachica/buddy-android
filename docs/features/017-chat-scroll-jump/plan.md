# Plan: 017-chat-scroll-jump
<!-- Planner (claude-opus-5-5), 2026-11-17, revised for plan review round 1. Sources: brief.md, research.md (incl.
     Device evidence), plan-review.md round 1, code at b63bfff, AOSP android14-release ScrollView.java, TextView.java,
     ArrowKeyMovementMethod.java (fetched 2026-11-17; line numbers below are from those files). -->

## Goal
A chat (bubble panel and full chat screen) never moves the user's scroll position on its own, except to follow new
output while they are at the end (012's 48dp rule, kept as is). Done when: (1) the root cause is shown with evidence,
not a guess. Here that means the AOSP chain under Design, the device snapshot in research.md, and a controlled
before/after repro on the phone (Test plan). It stays pending until that repro has run. (2) Rebuilds keep the user's
place, or the end if they were at the end, in both screens. (3) CI is green, Codex approves, and Nicholas checks it on
the phone.

Non-goals: keeping the end in view when the chat area shrinks while at the end (012 behaviour, not a move); keeping the
place across activity re-creation or process death (no saved scroll today); the bubble's existing pulls to the end when
the panel opens, the keyboard shows or hides, or the panel is re-placed (`Bubble.java:823, 965, 1025`, all from the
user's own action); "Load earlier" (`restoreEarlier`) and switching to a different chat still land at the end;
hardware-keyboard and TalkBack navigation inside the messages (see Risks); search and the 48dp rule.

## Design

### Root cause (the full chat), every step read in AOSP
1. **Every reply has a cursor.** A reply is selectable (`ChatActivity.java:2655`), so its movement method is
   `ArrowKeyMovementMethod`. Each `setText` calls `mMovement.initialize` (TextView.java:7166-7167), and that sets the
   selection to 0 (ArrowKeyMovementMethod.java:362-364). A tap moves the cursor to the tapped point.
2. **`fullScroll` hands focus to a reply nobody touched.** `scrollAndFocus` looks for a focusable in the last screen
   with `findFocusableViewInBounds` (ScrollView.java:1259-1279). That compares each view's `getTop()` relative to its
   *own parent* with content coordinates (:1129-1132). Every reply sits inside a wrapper (`whole`, `:2616`), so in a long
   chat nothing matches. The ScrollView then requests focus itself, and `onRequestFocusInDescendants` gives it to the
   first focusable FocusFinder finds from the viewport top (:1704-1728). In a short chat, the in-bounds test matches early
   replies directly. Either way, a reply nobody touched ends up focused. This is research.md's snapshot (an older reply,
   "Feature 017 has started…", `{focused}`).
3. **A height change re-measures the replies near the top.** The ScrollView measures its child with an UNSPECIFIED spec
   that carries its own height as the size (ScrollView.java:1478-1480). The LinearLayout passes that size, minus the
   height used above, down to each child, so the replies in the first screen of content get a new spec whenever the
   chat area's height takes a new value: running strip, helper cards, approval card, voice bar, keyboard.
4. **The focused reply then pulls the chat to its cursor.** `TextView.onMeasure` registers a pre-draw pass for any
   TextView with a movement method (TextView.java:11018-11021). `onPreDraw` calls `bringPointIntoView(selectionEnd)`
   (:8474), and if the view is focused (:11591) it calls `requestRectangleOnScreen`. That reaches
   `ScrollView.requestChildRectangleOnScreen` → `scrollToChildRect` → `smoothScrollBy` to that line (:1732-1739,
   :1603-1614), however far up it is. This is the jump to the very top. It happens only when a new height value appears
   (the measure cache absorbs repeats), so it looks random, while running and while idle.
5. **Smaller jumps** come from the same focused reply through `onSizeChanged`, which reveals it if it was in the old
   viewport (:1794-1808; the reviewer's point: this path alone can't pull from far away), and through
   `requestChildFocus` → `scrollToDescendant` whenever focus moves (:1683-1693).

All three reveal paths go through one protected method, `computeScrollDeltaToGetChildRectOnScreen`
(`scrollToDescendant` :1585, `onSizeChanged` :1804+, `scrollToChildRect` :1604).

**The bubble panel is different.** Its replies get links through `LinkMovementMethod`, which makes them `FOCUSABLE`
but not focusable in touch mode (TextView.java:2763-2766), and it has no selectable text. In touch mode no message view
there can take focus, so this chain does not run in the panel. The plan closes the focus paths there anyway (they apply
outside touch mode) and fixes the two moves the code does show (page trim, same-chat return). The panel's reported jump
to the top has no confirmed mechanism yet (see Risks and the Test plan).

### Fix
**Full chat: replies stay focusable, and the ScrollView never scrolls for focus.** Selection needs focus
(`Editor.checkField` → `requestFocus`), and so do link taps in selectable text (`touchIsFinished … && isFocused()`,
TextView.java:12757-12769). So in the full chat, "message views never take focus" can only mean never *programmatically*.
1. `scrollToEnd` without `fullScroll`. This removes step 2: no reply gets focus unless the user touches it.
2. `scroll` becomes an anonymous `ScrollView` subclass (the bubble's already is, `Bubble.java:421-427`) overriding
   `computeScrollDeltaToGetChildRectOnScreen(Rect)` to return 0. This closes steps 4 and 5 for a reply the user did touch
   (a link tap or selection leaves it focused). Nothing in `messages` needs those reveals: approval, helpers, strip and
   input are all outside the ScrollView (`ChatActivity.java:149-250`), so the keyboard never has a field in there to
   reveal. `scrollTo`, `smoothScrollTo`, fling and touch scrolling don't use this method.

**Bubble: block focus in the messages.** `messages.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS)`, and `scrollToEnd`
without `fullScroll`. Nothing in the panel's messages needs focus: there is no selectable text, link taps go through
`LinkMovementMethod.onTouchEvent` without focus, and long-press copy and buttons work in touch mode.

**scrollToEnd (both screens):** `scroll.post(() -> scroll.smoothScrollTo(0, messages.getBottom()))`. `fullScroll`
scrolled through `doScrollY` → `smoothScrollBy`, and this is the same call without the focus step. It reaches the same
end (clamped to the content) at the same speed, so 012's gate sees the same positions.

**`atEnd()` in both screens:** the existing 48dp check (`ChatActivity.onMessage` :636-638, `Bubble.followEnd`
:3563-3566) moved into a helper, measured from the last layout. `onMessage`, `followEnd` and the items below use it.

### Rebuilds and other moves (done-when 2)
- **Full chat, `onResume`.** Today it always ends at the end (`:533-534`), so coming back from a tapped link, another app
  or the lock screen while reading back lands at the end. New: take `atEnd()` first (true before the first layout, so a
  newly opened chat still opens at the end).
  - At the end, or `lastShown` no longer in `ses.messages` (chat cleared or replaced): as today, `showAll()` if needed,
    then `scrollToEnd()`.
  - Reading back: no rebuild. `show(m)` each message after `lastShown` (what `onMessage` would have done while visible),
    set `lastShown`, and don't scroll. Nothing above the place is laid out again (async pictures, log open state), so
    nothing can shift it.
  - "Show earlier" (`:421-427`) is already anchored. Unchanged.
- **Bubble, back to the same chat (F3).** Opening the model picker or the chat list only hides `scroll` (`showListMode`,
  `:1812-1819`). The message views stay, and `onMessage` keeps appending to them (`:2198-2199`). New helper
  `backToChat()`: if `renderedFor != sessions.current`, then `renderCurrent()` (a different chat opens at its end, as
  today). Otherwise read `atEnd()` (a hidden view keeps its last layout, so this is the place as the list found it), then
  `picking = pickingModel = false`, `showListMode()`, `updateViewing()`, and `scrollToEnd()` only if it was at the end
  (to catch up on output that came meanwhile). Used by `closePicker()` (`:1841-1846`), the pill's same-chat branch
  (`:988`) and the chat-list row (`:2014-2018`). Other `renderCurrent` callers are unchanged: they draw a different or
  new chat, restyle, or run while collapsed, and `expand()` goes to the end anyway.
- **Bubble page trim (F4).** `while (count > PAGE) removeViewAt(0)` (`:3382`) runs only when following:
  `if (kind == USER || atEnd()) { trim; scrollToEnd(); }`. While reading back, the oldest views stay. The next message
  that arrives at the end trims all the excess at once, below a user who is following. No offset arithmetic.
- **Tool logs folding while reading back (F2).** `ToolLog.finish()` (`ChatActivity.java:2792`) and `StepLog.finish()`
  (`Bubble.java:3550`) fold the rows only if the user is at the end or the log was never on screen:
  `if (!userToggled && !(view.isLaidOut() && !atEnd())) setOpen(false)`. A log built during a rebuild (`showAll`,
  `renderCurrent`) isn't laid out yet, so it folds as today. A live log the reader is in or above stays open, so the text
  under them doesn't move. The user can fold it with a tap. This covers every `finish()` caller (a reply after steps,
  `refresh()` when the run ends, a picture, the resume append).

Alternatives considered:
- `FOCUS_BLOCK_DESCENDANTS` or non-focusable replies in the full chat: breaks selection and link taps (above).
- The previous plan's `setRevealOnFocusHint(false)` plus an `onSizeChanged` override: leaves step 4 open
  (`requestChildRectangleOnScreen` from a tapped reply's cursor), which is the jump to the very top.
- Overriding `requestChildRectangleOnScreen` alone: leaves `requestChildFocus` and `onSizeChanged`. The one override
  under all three is smaller.
- `clearFocus()` on touch or scroll: a tapped reply stays focused until the next touch (returning from a link it opened
  is exactly when heights change).
- Debug-logging build first (research.md plan): the AOSP chain plus a controlled repro on the installed build gives the
  same proof without an extra release. Logging stays the fallback.
- Bubble trim compensated with `scrollBy(-cut)` (previous plan): wrong when two trims come before one layout, and it
  can't keep a row that is itself evicted (F4). Deferring needs no arithmetic.
- Bubble: anchored rebuild on return: re-layout above the place (logs folded, pictures) can still shift it, and reusing
  the views is less code.

## Steps
1. **Bubble: no focus in messages, end without fullScroll, `atEnd()`.** `Bubble.java`: after `messages` is created
   (~:428), `messages.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS)` with a one-line why.
   `scrollToEnd()` (:3568-3570) becomes `smoothScrollTo(0, messages.getBottom())` in the post. `followEnd()` is split
   into `atEnd()` + `if (atEnd()) scrollToEnd()`.
2. **Full chat: the ScrollView ignores focus, end without fullScroll, `atEnd()`.** `ChatActivity.java`: `scroll` (:195)
   becomes an anonymous `ScrollView` overriding `computeScrollDeltaToGetChildRectOnScreen` to return 0, with a short
   comment naming the 017 chain. `scrollToEnd()` (:2679-2681) becomes `smoothScrollTo(0, messages.getBottom())`. Add
   `atEnd()` from `onMessage` (:636-638), which then uses it.
3. **Full chat: resume keeps the place.** `onResume` (:530-534): the at-end and reading-back logic from Design, about 8
   lines.
4. **Bubble: trim only when following; back to the same chat keeps the place.** `showMessage(Kind, String)`
   (:3381-3383) as in Design. Add `backToChat()`, used at :988, :1841-1846 and :2014-2018.
5. **Logs stay open while reading back.** `ToolLog.finish()` (`ChatActivity.java:2792-2795`) and `StepLog.finish()`
   (`Bubble.java:3550-3553`), one condition each, with a comment.
6. **Guard test.** `app/src/test/java/app/buddy/assistant/ChatScrollTest.java`: plain JUnit that reads
   `src/main/java/app/buddy/assistant/{Bubble,ChatActivity}.java` (Gradle's unit-test workdir is the module dir). It
   asserts that neither file contains `fullScroll(`, that Bubble contains `FOCUS_BLOCK_DESCENDANTS`, and that
   ChatActivity contains `computeScrollDeltaToGetChildRectOnScreen`. Upstream syncs keep "features and their tests", so
   this fails if a merge brings the old calls back.

Release (orchestrator, after merge, per AGENTS.md "Updating Buddy on the phone"): `2.3.10-fork.9`, code 220.

## Test plan
| Done-when | Proof |
|---|---|
| 1 Root cause | Design's chain (AOSP lines) plus research.md's snapshot, plus a **controlled repro**, run by the orchestrator with phone tools. **On fork.8 now:** open the full chat of a chat that starts within its first page and is 3+ screens long (freshly opened, so measure caches are empty). Scroll to the top. Tap once on the text (not a link) of the first reply; `read_screen` shows it `{focused}`. Drag to the end. Then change the chat area's height without touching the chat: tap the mic so the voice bar replaces the composer, then cancel (or an approval card, or the run strip from a run started outside the screen). Expected: the chat jumps back up to that reply. Record `read_screen` before and after. **On fork.9:** same steps, no move. Also on fork.9, through a run at the end with nothing tapped: no `{focused}` message view in either screen. If fork.8 doesn't jump after 3 tries, record that in verify.md and keep this row pending. Bubble: no focus path exists in touch mode, so its proof is the in-use row below. If the panel still jumps on fork.9, research.md's logging plan runs on the panel. |
| 2 Rebuilds keep the place | Full chat: (a) read back mid-chat while the agent runs, open another app or a link, come back: same text on screen, new messages below; (b) same at the end: at the end with the new messages; (c) open a chat from the list or a notification: at the end; (d) "Show earlier": stays on the message that was at the top. Bubble: (e) a chat over 150 items, read back while replies arrive: the text doesn't move; back at the end, the next reply follows; (f) read midway, open the model chip, pick a model (and once just close it): same text; (g) same through the chat list back to the same chat; at the end before opening: at the end, with what came meanwhile; (h) a different chat from the list: opens at its end. |
| Goal: logs (F2) | Both screens: a run with many tool steps; scroll up into the open log and read; let the reply arrive: the log stays open and the text doesn't move. The same at the end: the log folds and the reply is followed (as today). Full chat: leave for another app while reading the open log, let the run finish, come back: same text, log open. |
| Goal: no jump | Both screens: long chat, at the end, idle and while running (helper cards and strip coming and going, voice bar, keyboard): stays put, new output followed (012). Full chat: tap a reply near the top, scroll to the end, start a run elsewhere: no move. Scroll up while running: never moves. |
| Kept behaviour | Full chat: long-press selects reply text and Copy works; a link in a reply opens; long-press on a user message copies; the copy icon copies the reply. Bubble: a link in a reply opens; long-press copies; tapping the conversation still hides the keyboard. |
| 3 CI / review | `./gradlew testGithubDebugUnitTest` incl. `ChatScrollTest`; PR CI (`build.yml`) green; Codex code review APPROVE. |
| 3 Phone | Nicholas runs the device rows on the installed fork. verify.md rows stay `pending` until he has. |

## Risks
1. **The override also turns off "show on screen" inside the full chat's messages.** That covers TalkBack's
   `ACTION_SHOW_ON_SCREEN` and arrow-key reveal. Scrolling with TalkBack's scroll actions still works. If Nicholas uses
   TalkBack, the narrower fix is to let only `immediate` rectangle requests through (TalkBack's are, a TextView's
   cursor request isn't).
2. **The bubble's reported jump may have another cause.** Its message views can't take focus in touch mode (Design). The
   trim and same-chat fixes stand on their own. If the panel still jumps on fork.9, research.md's logging plan runs on
   the panel. It's a follow-up, not a reason to hold this.
3. **Vendor ScrollView changes** (One UI): the override is at the one method AOSP's three reveal paths share. A vendor
   path that scrolls without it would show up in the controlled repro on fork.9.
4. **A log kept open while reading back stays open** until the user folds it or the chat is redrawn.
5. **The bubble can hold more than PAGE views** while the user reads back through a long run. It catches up at the next
   message at the end, and `renderCurrent` draws one page.
6. **Resize while at the end** still hides the last lines and can turn 012's following off. This is today's behaviour
   and a non-goal.

## Open questions
None blocking. For Nicholas to overrule (in decisions.md): coming back to the full chat while reading back, a message he
sent meanwhile from the bubble or watch is appended without pulling him to the end. Also, whether he uses TalkBack
(Risk 1).
