# Decisions: 022-watch-chat-view
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-17 - How the watch gets a chat's messages and their updates
- Options: (a) new `/buddy/history` request, phone pushes a fresh snapshot after each new message while the screen is registered; (b) the watch polls every few seconds; (c) reuse `tracked`/`ses.watch`.
- Choice: (a), because it is the only one that shows phone-started replies at once without repeated 60 KB transfers; (c) would silence the phone's read-aloud and end notification (`ChatHub.java:1134,1153-1155`).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Registration lifecycle on the phone
- Options: stop message only; time lease only; stop with a per-request `view` number plus a 30-min lease, one registration per watch.
- Choice: the last, because a relaunch starts the new screen before the old one stops (a plain stop would cancel the new one), and a stop lost out of range must not push forever. The watch registers again on every `onStart`.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - One source of truth on the watch screen
- Options: snapshot plus Talk lines newer than it (a `seq` on `Talk.Line`); snapshot plus only watch-side lines (a flag in Talk); match lines by text.
- Choice: snapshot plus lines with `seq > mark`, with the phone posting each push a turn later so a line always comes before the snapshot that holds it, because a lost push still leaves the reply visible and Talk only gains a counter.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Bounds and content of a snapshot
- Options: count only; count, per-message cut and byte budget; text shortened to 1000 chars.
- Choice: last 30 USER/BUDDY/ERROR messages, each cut to `SPOKEN_MAX` (3000, so a live reply isn't shortened when the snapshot replaces it), 60 KB serialized, newest kept; Buddy and error text through `Voice.spoken` like live replies, the user's as typed, attachments as their `📎` label; STEP and NOTE left out.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Scrolling on the talk screen
- Options: always scroll to the end (today); never; follow only at the end.
- Choice: to the end on the first snapshot, after the user's own words, or when within 48dp of the end (012's rule); otherwise keep the place. `scroll.requestFocus()` for the bezel, as the chat list does.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Tests
- Options: add a unit-test setup to the wear module; test the pure phone-side `history()` in the existing JVM tests and check the watch by hand.
- Choice: the latter, because the bounds and filtering live in `history()` and the watch screen is view code that needs the device anyway (research "Build and test").
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Snapshots and Talk's live lines (replaces "One source of truth on the watch screen")
- Options: receive-time watermark `seq > mark` for all lines (round 1); phone message ids on lines and rows; snapshots authoritative, used in phone order (`n`), live lines from the phone (`mirror`) not drawn beside one, plus a catch-up request.
- Choice: the last, because MessageClient promises queueing, not order (plan review round 2 F1), so the watermark can erase or double a reply; `Msg` has no id, and ids would change Talk and both relay paths. `mark` stays only for the watch's own lines, which are never in a snapshot. A mirror line with no snapshot within 8 s triggers one new request.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Unit tests in the wear module (replaces "Tests")
- Options: device checks only; a minimal JUnit setup in wear for pure statics.
- Choice: the latter, because the reconciliation and the reading-place rules depend on message order and window rolls a device can't be made to produce on demand; JUnit only, test-only, `:wear:testDebugUnitTest` in CI. The view code stays device-checked.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Keeping the reading place when a snapshot replaces the window
- Options: keep `scrollY` (round 1); keep the distance from the end; anchor on the top-most visible row, found again by kind and text at or before its old index, else the top.
- Choice: the anchor, because a full window loses rows at the top as rows arrive at the bottom, so neither pixel rule keeps the text in view; there are no row ids, and a wrong match between equal messages is a small jump.
- By: Planner (claude-opus-5-5)

## Orchestrator decision after plan round 3 (no APPROVE)
Round 3 F1 accepted as stated, overriding plan.md where they conflict: the phone keeps the latest `view` per node in the existing registration state; it ignores any start or stop older than that, and remembers a stopped view so a delayed start can't revive it; a newer start always wins. Tests: start 2 → start 1 → stop 1 (view 2 still gets updates); stop 2 → delayed start 2 (stays stopped); newer start after a stop works. Why: small, contained, and no new transport, so another planning round isn't worth it.

## 2026-11-17 - Coder: details the plan left open
- Registration rule (round 3 F1): `WatchLink.register(map, node, chat, view, stop, now)` keeps one entry per node, a stopped one too (`chat == null`). Older `view` ignored; a start of an already stopped `view` ignored; a newer start or stop always wins. Entries, stopped ones included, lapse after the same 30 min as a registration, so a watch whose clock went back is not ignored for ever. Four JVM tests (the three the orchestrator named, plus the lapse).
- The snapshot rule is a pure `TalkActivity.takes(...)`, next to `below()` and `anchor()`, so it is tested too. These three went into step 4 with their tests (tests that call them would not compile otherwise); step 5 is the view code only.
- `mirror` is set on the last line right after `lines.add`/`say(...)` at `/buddy/heard` and `/buddy/reply` (one line each in `Talk.java`), instead of a new constructor argument or a `say` parameter. `Line.count` is package-private so the screen can read the newest `seq` for `mark`.
- Under a snapshot the screen asks again (`catchUp`) when a mirror line is newer than `mark`; `Talk.fresh` clearing calls `request()` from `drawLive()`, the one place that sees the first YOU line.
