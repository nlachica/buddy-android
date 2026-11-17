# Plan: 017-chat-scroll-jump
<!-- Planner (claude-opus-5-5), 2026-11-17, revised for plan review round 2. Sources: brief.md, research.md (incl.
     Device evidence), plan-review.md rounds 1-2, code at d1efceb, AOSP android14-release ScrollView.java, TextView.java,
     View.java, Editor.java, ArrowKeyMovementMethod.java (fetched 2026-11-17; line numbers below are from those files). -->

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
user's own action); "Load earlier" (`restoreEarlier`) and switching to a different chat still land at the end; search
and the 48dp rule. Also out of scope: a tap that first gives a full-chat reply focus may still scroll it into view. That
is Android's reveal on focus (`requestChildFocus`), today's behaviour, started by the user's touch. It stays so that
keyboard focus moves still reveal.

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
   TextView with a movement method (TextView.java:11018-11022). `onPreDraw` calls `bringPointIntoView(selectionEnd)`
   (:8474), and if the view is focused (:11591) it calls `requestRectangleOnScreen(rect)`, which is *not* immediate
   (View.java:8079-8080). That reaches `ScrollView.requestChildRectangleOnScreen` → `scrollToChildRect` →
   `smoothScrollBy` to that line (:1732-1739, :1603-1614), however far up it is. This is the jump to the very top. It
   happens only when a new height value appears (the measure cache absorbs repeats), so it looks random, while running
   and while idle.
5. **Smaller jumps** come from the same focused reply through `onSizeChanged`, which reveals it if it was in the old
   viewport (:1794-1808; the reviewer's point: this path alone can't pull from far away).

**Who starts each reveal.** Automatic, and the bug: the cursor's request on re-measure (step 4) and the resize reveal
(step 5). Started by the user, and kept: a selection being made (a handle drag changes the selection, `spanChange` →
pre-draw → the same cursor request, TextView.java:12484-12491), TalkBack's "show on screen" (`requestRectangleOnScreen(r,
true)`, View.java:15256-15260), arrow keys (`arrowScroll`, ScrollView.java:1291-1305), and a focus change by tap or key
(`requestChildFocus`, :1683-1693).

**The bubble panel is different.** Its replies get links through `LinkMovementMethod`, which makes them `FOCUSABLE`
but not focusable in touch mode (TextView.java:2763-2766), and it has no selectable text. In touch mode no message view
there can take focus, so this chain does not run in the panel. The plan closes the focus paths there anyway (they apply
outside touch mode) and fixes the two moves the code does show (page trim, same-chat return). The panel's reported jump
to the top has no confirmed mechanism yet. If it is still there on fork.9, 017 is not done (Risks 2, Test plan).

### Fix
**Full chat: replies stay focusable; nothing focuses them on its own, and a focused reply never scrolls the chat by
itself.** Selection needs focus (`Editor.checkField` → `requestFocus`, Editor.java:2554-2555), and so do link taps in
selectable text (`touchIsFinished … && isFocused()`, TextView.java:12757-12769). So in the full chat, "message views
never take focus" can only mean never *programmatically*.
1. `scrollToEnd` without `fullScroll`. This removes step 2: no reply gets focus unless the user touches it.
2. `scroll` becomes an anonymous `ScrollView` subclass (the bubble's already is, `Bubble.java:421-427`) with two
   overrides that close only the automatic paths:
   - `requestChildRectangleOnScreen(child, r, immediate)`: if `immediate`, or `findFocus()` is a TextView with
     `hasSelection()`, return super; else return false. This drops step 4 and keeps TalkBack and handle drags.
     Returning false stops only this level. `View.requestRectangleOnScreen` keeps its own coordinates for the parents
     (View.java:8100-8128), and the window is `adjustResize`, so nothing above pans.
   - `onSizeChanged(w, h, ow, oh)`: call super only if `findFocus()` is null or the ScrollView itself.
     `View.onSizeChanged` is empty (View.java:17766), and FrameLayout and ViewGroup don't override it. So with a focused
     reply this skips just ScrollView's reveal (step 5).
   `arrowScroll` and `requestChildFocus` stay as they are.

**Bubble: block focus in the messages.** `messages.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS)`, and `scrollToEnd`
without `fullScroll`. Nothing in the panel's messages needs focus: there is no selectable text, link taps go through
`LinkMovementMethod.onTouchEvent` without focus, and long-press copy and buttons work in touch mode. TalkBack is
unaffected: its accessibility focus is not input focus, and "show on screen" is a rectangle request.

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
- **Bubble, back to the same chat.** Opening the model picker or the chat list only hides `scroll` (`showListMode`,
  `:1812-1819`). The message views stay, and `onMessage` keeps appending to them (`:2198-2199`). New helper
  `backToChat()`: if `renderedFor != sessions.current`, then `renderCurrent()` (a different chat opens at its end, as
  today). Otherwise read `atEnd()` (a hidden view keeps its last layout, so this is the place as the list found it), then
  `picking = pickingModel = false`, `showingList = false`, `showListMode()`, `updateViewing()`, `updateHeader()` (title,
  model chip, helpers, strip and log state in chat mode; `hub.select` may have painted list mode, `Bubble.java:2014-2017`),
  and `scrollToEnd()` only if it was at the end (to catch up on output that came meanwhile). Used by `closePicker()`
  (`:1841-1846`, its own `updateHeader()` then goes), the pill's same-chat branch (`:988`) and the chat-list row
  (`:2014-2018`). Other `renderCurrent` callers are unchanged: they draw a different or new chat, restyle, or run while
  collapsed, and `expand()` goes to the end anyway.
- **Bubble page trim.** `while (count > PAGE) removeViewAt(0)` (`:3382`) runs only when following:
  `if (kind == USER || atEnd()) { trim; scrollToEnd(); }`. While reading back, the oldest views stay. The next message
  that arrives at the end trims all the excess at once, below a user who is following. No offset arithmetic.
- **Tool logs folding while reading back.** Both log classes (`ChatActivity.ToolLog` :2784-2795, `Bubble.StepLog`
  :3542-3553) get one guarded fold: `fold()` = `if (!userToggled && !(view.isLaidOut() && !atEnd())) setOpen(false)`.
  `setLive(on)` repaints the marks as today, opens the rows when `on` (unchanged), and calls `fold()` when not `on`
  (today an unguarded `setOpen(false)`). `finish()` = `setLive(false); fold();`. So every automatic close goes through
  the guard: a reply after steps, `refresh()` when the run ends (also on resume after a run that ended while paused),
  and Bubble's `updateHeader()` → `log.setLive(ses.running)` (:2151) when a run ends with no reply. A log built during
  a rebuild (`showAll`, `renderCurrent`) isn't laid out yet, so it folds as today. A log the reader is in or above stays
  open, so the text under them doesn't move. A header tap still opens or folds it.

Alternatives considered:
- `FOCUS_BLOCK_DESCENDANTS` or non-focusable replies in the full chat (the orchestrator's first direction): breaks
  selection and link taps (above).
- One override, `computeScrollDeltaToGetChildRectOnScreen` returning 0 (round 1): also drops TalkBack's show-on-screen,
  arrow-key reveal and selection-handle drags (review round 2 F3).
- `setRevealOnFocusHint(false)` plus an `onSizeChanged` override (round 0): leaves step 4, the jump to the top.
- `clearFocus()` on touch or scroll: misses a tapped reply carried off screen by followed output, and a resize while
  it is still on screen.
- Debug-logging build first (research.md plan): the AOSP chain plus a controlled repro on the installed build gives the
  same proof without an extra release. Logging stays the fallback, and is required if the panel still jumps.
- Bubble trim compensated with `scrollBy(-cut)`: wrong when two trims come before one layout, and it can't keep a row
  that is itself evicted. Deferring needs no arithmetic.
- Bubble: anchored rebuild on return: re-layout above the place (logs folded, pictures) can still shift it, and reusing
  the views is less code.

## Steps
1. **Bubble: no focus in messages, end without fullScroll, `atEnd()`.** `Bubble.java`: after `messages` is created
   (~:428), `messages.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS)` with a one-line why.
   `scrollToEnd()` (:3568-3570) becomes `smoothScrollTo(0, messages.getBottom())` in the post. `followEnd()` is split
   into `atEnd()` + `if (atEnd()) scrollToEnd()`.
2. **Full chat: the ScrollView ignores automatic reveals, end without fullScroll, `atEnd()`.** `ChatActivity.java`:
   `scroll` (:195) becomes an anonymous `ScrollView` with the two overrides from Design (`requestChildRectangleOnScreen`,
   `onSizeChanged`), with a short comment naming the 017 chain and what stays. `scrollToEnd()` (:2679-2681) becomes
   `smoothScrollTo(0, messages.getBottom())`. Add `atEnd()` from `onMessage` (:636-638), which then uses it.
3. **Full chat: resume keeps the place.** `onResume` (:530-534): the at-end and reading-back logic from Design, about 8
   lines.
4. **Bubble: trim only when following; back to the same chat keeps the place.** `showMessage(Kind, String)`
   (:3381-3383) as in Design. Add `backToChat()` (with `updateHeader()`), used at :988, :1841-1846 and :2014-2018.
5. **Logs stay open while reading back.** `ToolLog` (`ChatActivity.java:2784-2795`) and `StepLog`
   (`Bubble.java:3542-3553`): add `fold()`, call it from `setLive` (when not live) and `finish()`, with a comment.
6. **Guard test.** `app/src/test/java/app/buddy/assistant/ChatScrollTest.java`: plain JUnit that reads
   `src/main/java/app/buddy/assistant/{Bubble,ChatActivity}.java` (Gradle's unit-test workdir is the module dir). It
   asserts that neither file contains `fullScroll(`, that Bubble contains `FOCUS_BLOCK_DESCENDANTS`, and that
   ChatActivity contains `requestChildRectangleOnScreen`. Upstream syncs keep "features and their tests", so this fails
   if a merge brings the old calls back.

Release (orchestrator, after merge, per AGENTS.md "Updating Buddy on the phone"): `2.3.10-fork.9`, code 220.

## Test plan
| Done-when | Proof |
|---|---|
| 1 Root cause | Design's chain (AOSP lines) plus research.md's snapshot, plus a **controlled repro**, run by the orchestrator with phone tools. **On fork.8 now:** open the full chat of a chat that starts within its first page and is 3+ screens long (freshly opened, so measure caches are empty). Scroll to the top. Tap once on the text (not a link) of the first reply; `read_screen` shows it `{focused}`. Drag to the end. Then change the chat area's height without touching the chat: tap the mic so the voice bar replaces the composer, then cancel (or an approval card, or the run strip from a run started outside the screen). Expected: the chat jumps back up to that reply. Record `read_screen` before and after. **On fork.9:** same steps, no move. Also on fork.9, through a run at the end with nothing tapped: no `{focused}` message view in either screen. If fork.8 doesn't jump after 3 tries, record that in verify.md and keep this row pending. |
| 2 Rebuilds keep the place | Full chat: (a) read back mid-chat while the agent runs, open another app or a link, come back: same text on screen, new messages below; (b) same at the end: at the end with the new messages; (c) open a chat from the list or a notification: at the end; (d) "Show earlier": stays on the message that was at the top. Bubble: (e) a chat over 150 items, read back while replies arrive: the text doesn't move; back at the end, the next reply follows; (f) read midway, open the model chip, pick a model (and once just close it): same text; (g) same through the chat list back to the same chat: same text, and the header shows the chat's title, the model chip, and the running strip if it runs; at the end before opening: at the end, with what came meanwhile; (h) a different chat from the list: opens at its end. |
| Goal: logs | Both screens, reading inside a tall open log while the run works: (i) the run ends with a reply: the log stays open and the text doesn't move; (j) the run ends with no reply (stop it, or a run that only uses tools), so only the status/header refreshes: same. The same at the end: the log folds and the reply is followed (as today). Full chat: leave for another app while reading the open log, let the run finish, come back: same text, log open. |
| Goal: no jump | Both screens: long chat, at the end, idle and while running (helper cards and strip coming and going, voice bar, keyboard): stays put, new output followed (012). Full chat: tap a reply near the top, scroll to the end, start a run elsewhere: no move. Tap a link in a reply on screen, come back, let the strip appear: no move. Scroll up while running: never moves. **Bubble: if the panel still jumps on fork.9, this row and 017 fail.** The orchestrator runs research.md's logging plan on the panel (a diagnostic build is fine) and takes the evidence back to the Researcher and Planner inside 017. |
| Kept behaviour | Full chat: long-press selects reply text and Copy works; dragging a selection handle to the bottom edge scrolls the chat with it; a link in a reply opens; long-press on a user message copies; the copy icon copies the reply. With TalkBack on (Nicholas, or the orchestrator if it can switch TalkBack), swipe through a long full chat past the screen's bottom: the chat scrolls to each message read. Arrow keys, only if a hardware keyboard is at hand: they move through the replies and scroll. Bubble: a link in a reply opens; long-press copies; tapping the conversation still hides the keyboard. |
| 3 CI / review | `./gradlew testGithubDebugUnitTest` incl. `ChatScrollTest`; PR CI (`build.yml`) green; Codex code review APPROVE. |
| 3 Phone | Nicholas runs the device rows on the installed fork. verify.md rows stay `pending` until he has. |

## Risks
1. **What still moves the full chat, on purpose.** A tap that first gives a long, partly visible reply focus scrolls it
   into view (`requestChildFocus`, today's behaviour, kept for keyboard focus moves). While a selection stays open in a
   reply, a re-measure keeps its end in view (Android's selection rule, kept so handle drags scroll). Both need the
   user's own touch on that reply.
2. **The bubble's reported jump may have another cause.** Its message views can't take focus in touch mode (Design). The
   trim and same-chat fixes stand on their own. If the panel still jumps on fork.9, 017 is not done. The logging plan
   runs on the panel, and the fix comes back through this feature's pipeline.
3. **Vendor ScrollView changes** (One UI): the overrides sit at the AOSP reveal paths. A vendor path that scrolls
   elsewhere would show in the controlled repro on fork.9. A vendor addition to `ScrollView.onSizeChanged` is skipped
   only while a reply holds focus (after the user taps one).
4. **A log kept open while reading back stays open** until the user folds it or the chat is redrawn.
5. **The bubble can hold more than PAGE views** while the user reads back through a long run. It catches up at the next
   message at the end, and `renderCurrent` draws one page.
6. **Resize while at the end** still hides the last lines and can turn 012's following off. This is today's behaviour
   and a non-goal.

## Open questions
None blocking. For Nicholas to overrule (in decisions.md): coming back to the full chat while reading back, a message he
sent meanwhile from the bubble or watch is appended without pulling him to the end.
