# Plan: 022-watch-chat-view
<!-- Planner (claude-opus-5-5), 2026-11-17. Written for plan review round 1 (the first planner run failed on an API
     error and left only the template), revised for round 2 (snapshot order, New chat registration, reading place).
     Sources: brief.md, research.md, code at a86d60a. -->

## Goal
Opening a chat on the Galaxy Watch shows that chat's recent conversation (the user's messages and Buddy's replies and
errors), newest at the bottom and opened there, scrollable by touch and the bezel, with the mic and call buttons still
on screen. A new message in that chat appears while the screen is open, whether the run was started on the watch or on
the phone. Done when: messages show on open at the newest; touch and bezel scroll and the buttons stay reachable; a new
reply appears while open; long chats stay fast (bounded count and size) with the phone app from the same release; CI
is green.

Non-goals: pictures and files (a message shows its `📎 name` label only); steps and notes (`STEP`, `NOTE`); "load
earlier"; showing on the watch that a phone-started run is working; editing, deleting or approvals on the watch;
caching history on the watch between screens; history for a New chat before the phone has made it; an older phone app
(it gets a "not answering" note, nothing breaks); message ids on the phone.

## Design

**Transport: one new path, request plus push while viewing.** The watch's talk screen asks `/buddy/history` for its
chat when it starts and says stop when it stops. The phone answers with a bounded snapshot and, while that screen is
registered, sends a fresh snapshot after each new user, Buddy or error message in that chat. That one mechanism covers
the open, phone-started replies (which the phone never relays today, `WatchLink.java:156-158`) and the user's own words
typed on the phone. Alternatives: the watch polls every few seconds (stateless phone, but repeated 60 KB transfers and
up to a poll's delay); reuse `tracked`/`ses.watch` (wrong: they silence the phone's read-aloud and its end notification,
`ChatHub.java:1134,1153-1155`, research "Constraints").

Protocol (UTF-8 JSON over MessageClient, like the table in `docs/features/003-wear-companion/plan.md:106-119`):

| Path | Way | Body | Meaning |
|---|---|---|---|
| `/buddy/history` | W→P | `{chat, view}` | send this chat's latest messages, and again on each new one while I show it |
| `/buddy/history` | W→P | `{chat, view, stop:true}` | I stopped showing it (ends only the registration with this `view`) |
| `/buddy/history` | P→W | `{chat, view, n, messages:[{kind, text}]}` | oldest first; `kind` is `you`, `buddy` or `error`; empty for an unknown chat; `n` goes up with every snapshot the phone sends |

`view` is a number the watch makes per request (a static counter seeded from the clock, like `WatchLink.voiceSeq`). It
lets the phone ignore a late stop from a screen that a newer one already replaced (a relaunch starts the new screen
before the old one's `onStop`, `TalkActivity.java:156-159`), and lets the watch ignore an answer to an older request.
`n` is the phone's order of sending: MessageClient promises queueing, not order, so the watch uses a snapshot only if
its `n` is above the last one it used.

**Phone (`WatchLink.java`, github flavor only).**
- `viewers`: node id → `{chat, view, until}`, one per watch (a new request replaces it), main thread. A stop removes it
  only if `view` matches. `until` = request time + 30 min: a stop that never arrived (watch out of range at that moment)
  can't make the phone push forever; the watch registers again on every `onStart`, and a watch screen does not stay on
  that long untouched (Talk keeps it on for at most 2 min after the phone's last message, `TalkActivity.java:36`).
- The existing `relay` listener gets, first thing in `onMessage`: for `USER`, `BUDDY`, `ERROR` in a viewed chat,
  `main.post` one push per node (a pending flag coalesces several messages in one turn into one snapshot). Posting
  also sends it, in the usual case, after the `/buddy/heard` or `/buddy/reply` of the same turn (`WatchLink.java:126,
  133,166-167`); nothing on the watch depends on that order. The listener is registered by a small `listen()` taken out
  of `deliver` (`:136-139`), now also called on a history request.
- `sendHistory(node)` stamps `n = ++pushes`, a static counter seeded from the clock (like `voiceSeq`), so it only goes
  up, also across phone restarts.
- `history(List<Msg> all, Function<Msg,String> text)`: a pure static function, unit-tested. Walks from the newest
  message back, keeps `USER`/`BUDDY`/`ERROR` with non-empty text, cuts each to `SPOKEN_MAX` (3000, the live reply's cap)
  without splitting a surrogate pair, stops at `HISTORY_MAX = 30` messages or when the serialized rows pass
  `HISTORY_MAX_BYTES = 60_000` (measured like `chats()`, `:102-103`; MessageClient's limit is 100 KB), and returns them
  oldest first. The caller passes `m -> m.kind == USER ? m.label() : Voice.spoken(app, m.label())`: replies read like
  the live ones (`:162`), the user's words as typed, attachment-only messages as their `📎` label (`Sessions.java:52-57`).
  An unknown chat gives `[]`. Reading `ses.messages` happens on the main thread, as the switch already runs (`:71`).

**Watch: the snapshot is the truth for the chat's messages.** Each `Talk.Line` gets `seq` (a process-wide counter on
`Line`, so it survives `lines.clear()`, `Talk.java:166`) and `mirror`: true for the lines that are messages of the
phone's chat as well (added at `/buddy/heard` and `/buddy/reply`, `Talk.java:335,344`), false for the watch's own (no
answer, lost contact, a refusal from `/buddy/error`, stopped, no voice). Every `/buddy/heard` and `/buddy/reply` stands
for a USER, BUDDY or ERROR message the phone added (a note to a running chat is a USER message too,
`ChatHub.java:952`), and while the screen is registered the phone sends a snapshot after each one. So:
- The screen uses a snapshot only if its `chat` and `view` are its own and its `n` is above the last used one; then
  `rows` = its messages and `mark` = the newest `seq` so far. Rows only ever come from snapshots taken in phone order,
  so a reply in a used snapshot stays until it rolls out of the 30.
- `below(lines, snapshot, mark)` (pure static, tested) picks the Talk lines drawn under the rows: with no snapshot yet
  (or an old phone), all of them, as today; with one, only the watch's own lines with `seq > mark` (things that went
  wrong since; older ones drop out). Mirror lines are never drawn next to a snapshot: whether a reply's line or its
  snapshot comes first, it shows once, from the snapshot. `mark` only places the watch's own lines, which are never in
  a snapshot; it infers nothing about a snapshot's contents.
- Recovery: if Talk has a mirror line of this chat with `seq > mark` (its snapshot hasn't come) and no snapshot is used
  within 8 s, the screen asks again (`request()`, a new `view`), at most once per used snapshot. A phone-started message
  has no line on the watch: if its push is lost, it shows with the next message or on reopening (Risks).
- Alternatives: phone message ids on lines and rows (`Msg` has none; Talk and both relay paths would change); the
  round-1 receive-time watermark (a reordered snapshot erases or doubles a reply, plan review round 2 F1); match by text
  (fragile).

**Watch: `TalkActivity.java`.**
- Request lifecycle, in one `request()` copied from `ChatsActivity.load()` (`:126-148`): take a new `view`, arm an 8 s
  timeout, add a MessageClient listener, then `Phone.send(HISTORY, {chat, view})` with `valid` = still started and still
  this `view`. The listener `main.post`s (as Talk does, `Talk.java:135`). Timeout: a muted note `chats_no_answer` under
  the conversation; a send error: its own text (both strings exist). `onStart` calls `request()` before its first draw,
  unless `fresh` (no chat on the phone yet, `:57-58`); `draw()` calls it at the moment it clears `fresh` (the YOU line:
  the phone has just made the chat, `:246`) while the screen is started, so a New chat registers without reopening and
  keeps getting phone-side messages after its first run ends. `onStop`: remove the listener, cancel the timeout and the
  catch-up, send the stop (fire and forget).
- Rendering: `body` keeps the face, then a `history` container (rebuilt only when a snapshot is used), then a `live`
  container (`below(...)`, status, problems; rebuilt by `draw()` as today). A run's frequent step updates then don't
  re-lay-out 30 messages. Snapshot rows are parsed into `Talk.Line`s and drawn like today's lines (`You: …` in `TEXT2`,
  Buddy in `TEXT`, errors in `ERR`, 15sp, 8dp apart, `Look.sidePad` sides) through one small `line(kind, text)` helper
  used by both containers. TextViews stay plain (not selectable: 017's cursor-reveal jump needs a movement method). The
  `fresh` check keeps scanning all of this chat's Talk lines.
- Scrolling: the ScrollView already sits between the pill and the buttons (`:94-132`), so the buttons stay on screen.
  `scroll.requestFocus()` once in `onCreate`, as the chat list does (`ChatsActivity.java:122`), so the bezel (rotary
  events go to the focused view) scrolls it; today the talk screen never asks for focus. The unconditional
  `fullScroll` at the end of `draw()` (`:262`) becomes: go to the end if the user was within 48dp of it before the
  rebuild (012's rule), on this screen's first used snapshot (opened at the newest), or right after the user's own
  words were sent (`onActivityResult`). Otherwise the reading place is kept by a row, not by pixels, because a full
  window loses rows at the top when one arrives at the bottom: before a history rebuild the screen notes the top-most
  row still in view (its index `i` and its top minus `scrollY`); after layout (`scroll.post`) it finds the row with
  `anchor(old, i, now)` (pure static, tested: the same kind and text at an index `<= i`, nearest first, since rows only
  leave from the top) and scrolls to keep the offset; if the row left the window, to the top (the oldest kept message is
  the nearest to where the user was). A `draw()` rebuilds only `live`, below the rows, so it keeps `scrollY`. A screen
  coming back from sleep keeps its place the same way.

**Mixed versions.** An old phone ignores the path (`default: break`, `WatchLink.java:84`): the watch shows the note
after 8 s and works as today (no snapshot: `below` draws all Talk lines, no catch-up). An old watch never asks: the
phone does nothing new. Talk's own listener sees `/buddy/history` too and drops it (`Talk.java:316,323,366`).

## Steps
1. Phone, tests first: `WatchLink.history(...)` plus `HISTORY_MAX`, `HISTORY_MAX_BYTES`, and its JVM tests in
   `app/src/testGithub/java/app/buddy/assistant/WatchLinkTest.java` (see Test plan).
2. Phone: `/buddy/history` in `WatchLink.java`: `HISTORY` constant, `case HISTORY` in the switch, `viewers` with the
   stop rule and the 30-min `until`, `listen()` taken out of `deliver`, the posted and coalesced push at the top of
   `relay.onMessage`, `sendHistory(node)` with `n`. The class comment points at this plan for the new path.
3. Watch: `Phone.HISTORY` in `wear/.../Phone.java`; `seq` (static counter on `Line`) and `mirror` (true only at
   `HEARD` and `REPLY`) on `Talk.Line` in `wear/.../Talk.java` (nothing else in Talk changes).
4. Watch, tests first: JUnit in `wear/build.gradle.kts` (`testImplementation("junit:junit:4.13.2")`, test-only);
   `wear/src/test/java/app/buddy/assistant/wear/TalkActivityTest.java` for `below()`, the snapshot rule and `anchor()`
   (see Test plan); `:wear:testDebugUnitTest` added to the Gradle line in `.github/workflows/build.yml:63`.
5. Watch: `wear/.../TalkActivity.java`: `request()` and its two callers, the snapshot rule (`view`, `n`, `rows`,
   `mark`), the catch-up, `history`/`live` containers and `line()`, the pure statics `below()` and `anchor()`, the
   scroll rules and `requestFocus()`. The pure statics use only `java.util` and `Talk.Line`.
6. Docs: one bullet in README's Galaxy Watch section (the talk screen shows the chat's latest messages, up to 30, newest
   at the bottom, touch or bezel to scroll, new ones appear while it's open, also those from the phone); PRIVACY.md:48
   adds that the latest messages of a chat opened on the watch pass over the same link.

## Test plan
| Done-when | Proof |
|---|---|
| Recent messages, both sides, readable, opened at the newest | JVM tests of `history()`: keeps USER/BUDDY/ERROR oldest first and drops STEP/NOTE/empty; last 30 of 50; a 3000+ char text cut with `…` and no lone surrogate; 30 long multi-byte messages stay under `HISTORY_MAX_BYTES` and keep the newest; `[]` for no messages. On the watch: open a long chat from the list, it lands on the latest message, `You:` lines and replies readable inside the round edge. |
| Touch and bezel scroll; buttons reachable | JVM tests of `anchor()`: window not full (same index); rolled by one and by three with new rows at the end; a duplicate text picks the nearest at or before the old index; a row that left gives -1. On the watch: swipe and turn the bezel up to the oldest shown message and back; mic and call stay visible and work from a scrolled position. With a full window of unequal lengths where the 60 KB budget, not the count, decides (ask Buddy several times for a 2,500-character text, plus short messages): scroll to a middle message, type twice in that chat on the phone: the same message stays in place; scrolled to the oldest shown, a new message: the screen shows the new oldest. |
| A new reply appears while open | JVM tests of the watch rules (rows of the used snapshot plus `below()`; the snapshot rule): snapshot [A,B] then line B: B shown once; line B then snapshot [A,B]: once; line B, older snapshot [A] used, then [A,B]: B absent between, then once; [A,B] (n=2) used then a late [A] (n=1): refused, [A,B] stays; another `view`: refused; the watch's own error after the used snapshot shows, one before it doesn't; no snapshot: all Talk lines. On the watch, keep a chat open and (a) type in that chat on the phone: the words and Buddy's reply appear; (b) speak on the watch: words and reply appear once each; (c) scroll up while a reply arrives: the place holds; (d) New chat on the watch, speak, get the first reply, then type in that chat on the phone with the watch screen still open: the words and reply appear without reopening. |
| Long chats stay fast; same-release phone | Bounds are in the tested `history()` (30 messages, 3000 chars each, 60 KB); history views are rebuilt only on a used snapshot, so steps don't re-lay-out them; on the watch a chat with hundreds of messages opens without a visible stall. Phone and watch APKs of one release (both read `BuildInfo.java`, `wear/build.gradle.kts:5-8`). |
| CI green | `build.yml` on the PR: `testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease :wear:testDebugUnitTest` (the fdroid build doesn't compile `src/watchlink`). |

The watch checks need a release with both APKs installed (AGENTS.md, "Updating Buddy on the phone"); the Verifier
records them with Nicholas, the same way 007/013/020 were checked.

## Risks
- Order: no rule depends on MessageClient's order (snapshots used in phone order by `n`; no mirror line beside a
  snapshot). The cost: while a snapshot is shown, a live reply's text appears when its snapshot lands (sent right
  behind the line on the same link, up to 60 KB), not with the line; Talk starts saying it at the line.
- A lost snapshot: after a watch-side line, the catch-up asks again after 8 s. A phone-started message whose push is
  lost, or a line that came before an older snapshot and whose own snapshot is then lost, shows with the next message
  or on reopening.
- `anchor()` matches by kind and text: two equal messages in the window can anchor to the wrong one (a jump of a few
  rows), cosmetic.
- Bezel: the talk screen hasn't had focus before; same mechanism as the chat list, but only a device check proves it.
- A lost stop: the phone pushes that chat's messages for up to 30 min to a watch that drops them (the wear app has no
  message service, `wear/src/main/AndroidManifest.xml`, so nothing wakes).
- Privacy: messages typed on the phone now reach the watch too, over the same encrypted link as replies today; nothing
  is logged (PRIVACY.md updated).
- `Talk.java` is delicate (007/020); the only change is two fields on `Line`, set where lines are added.
- The wear module gets its first unit tests: a test-only JUnit dependency, nothing in the APK; `:wear:testDebugUnitTest`
  adds a debug compile to CI.

## Open questions
None blocking. The 30-message count is a default Nicholas can change later (one constant).
