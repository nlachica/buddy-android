# Decisions: 015-subagents-box-close
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-16 - Close (×) that hides the whole box, not collapse
- Options: × hides the box until the next round; collapse to a one-line header that can reopen; both.
- Choice: × hides it, because the brief's goal and done-when say "tap hides the box ... until the next delegate round". A header line would still be a box.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - No control while a sub-agent runs
- Options: nothing while running; collapse-only while running (the brief allows either).
- Choice: nothing (the × is INVISIBLE, so nothing moves when it appears), because it is less code and fully meets done-when 2.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - A round is its helpers' ids; the parent stores the closed round's key
- Options: joined helper ids stored on the parent (`helpersClosed`); a boolean on the parent reset in `Helpers.delegate`; a flag on each helper session.
- Choice: ids key, because a new round always makes new sessions with new ids, so it shows the box by itself. The delegate path stays untouched (non-goal), and there is nothing to reset. Pure `Helpers.round` / `Helpers.boxClosed` make it unit-testable.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The closed state is saved with the chat
- Options: an activity field (lost on reopen or rotation); a runtime `Session` field (lost on restart, when the old box would come back); saved in the chat's JSON.
- Choice: saved (`helpers_closed`, written only when not empty, as separate lines for easy upstream merges), because "hides it until the next delegate round" should hold across reopen and restart, and it costs two lines.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - A closed round's sub-agent run again shows the box while it works
- Options: hidden iff the key matches and none runs (pure); clear the close when one runs again; keep it hidden even while running.
- Choice: pure rule. The box shows (no ×) while the user's re-run works, and hides again when it is done. Clearing would write state during render, and only if the parent chat happened to be open. Keeping it hidden would hide work in the user's chat. This is a rare path (the user must open the sub-agent's chat from the bubble or Search and write in it).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - No way to reopen a closed box before the next round (default; Nicholas may flip)
- Options: none (bubble panel rows and Search still open the sub-agents' chats); a "Show sub-agents" row in Chat options.
- Choice: none, because the brief says closed "until the next delegate round" and the extra row grows scope. The reports stay reachable: the sub-agents' chats are not deleted, the bubble panel lists them, and Search finds them.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The × string is English only
- Options: `values/strings.xml` only; add every locale.
- Choice: English only, as fork features 012 and 013 did (Android falls back to English, and lintVital doesn't fail on missing translations).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The × tap runs the full refresh()
- Options: `renderHelpers()` only, plus a separate `backgroundPanel.setShown(resumed)` on close; the full `refresh()`.
- Choice: `refresh()`, because it already restores the panel (which `renderHelpers` set GONE while the "This chat" card
  existed) and then rebuilds the box. It is one call, and the speaker button already uses the same path (plan-review F2).
- By: Planner (claude-opus-5-5)
