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
