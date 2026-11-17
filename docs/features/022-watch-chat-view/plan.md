# Plan: 022-watch-chat-view
<!-- Planner (claude-opus-5-5), 2026-11-17, written for plan review round 1 (the first planner run failed on an API
     error and left only the template). Sources: brief.md, research.md, code at 4e50840. -->

## Goal
Opening a chat on the Galaxy Watch shows that chat's recent conversation (the user's messages and Buddy's replies and
errors), newest at the bottom and opened there, scrollable by touch and the bezel, with the mic and call buttons still
on screen. A new message in that chat appears while the screen is open, whether the run was started on the watch or on
the phone. Done when: messages show on open at the newest; touch and bezel scroll and the buttons stay reachable; a new
reply appears while open; long chats stay fast (bounded count and size) with the phone app from the same release; CI
is green.

Non-goals: pictures and files (a message shows its `📎 name` label only); steps and notes (`STEP`, `NOTE`); "load
earlier"; showing on the watch that a phone-started run is working; editing, deleting or approvals on the watch;
caching history on the watch between screens; history for a New chat the phone hasn't heard yet; an older phone app
(it gets a "not answering" note, nothing breaks); unit-test setup for the wear module.

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
| `/buddy/history` | P→W | `{chat, view, messages:[{kind, text}]}` | oldest first; `kind` is `you`, `buddy` or `error`; empty for an unknown chat |

`view` is a number the watch makes per request (a static counter seeded from the clock, like `WatchLink.voiceSeq`). It
lets the phone ignore a late stop from a screen that a newer one already replaced (a relaunch starts the new screen
before the old one's `onStop`, `TalkActivity.java:156-159`), and lets the watch ignore an answer to an older request.

**Phone (`WatchLink.java`, github flavor only).**
- `viewers`: node id → `{chat, view, until}`, one per watch (a new request replaces it), main thread. A stop removes it
  only if `view` matches. `until` = request time + 30 min: a stop that never arrived (watch out of range at that moment)
  can't make the phone push forever; the watch registers again on every `onStart`, and a watch screen does not stay on
  that long untouched (Talk keeps it on for at most 2 min after the phone's last message, `TalkActivity.java:36`).
- The existing `relay` listener gets, first thing in `onMessage`: for `USER`, `BUDDY`, `ERROR` in a viewed chat,
  `main.post` one push per node (a pending flag coalesces several messages in one turn). Posting matters: the push then
  goes out *after* the `/buddy/heard` that `deliver` sends right after `hub.send` (`WatchLink.java:126,133`) and after
  the relay's own `/buddy/reply` (`:166-167`), so the watch always gets a line before the snapshot that contains it.
  The listener is registered by a small `listen()` taken out of `deliver` (`:136-139`), now also called on a history
  request.
- `history(List<Msg> all, Function<Msg,String> text)`: a pure static function, unit-tested. Walks from the newest
  message back, keeps `USER`/`BUDDY`/`ERROR` with non-empty text, cuts each to `SPOKEN_MAX` (3000, the live reply's cap,
  so a reply read live isn't shortened when the snapshot replaces it) without splitting a surrogate pair, stops at
  `HISTORY_MAX = 30` messages or when the serialized rows pass `HISTORY_MAX_BYTES = 60_000` (measured like `chats()`,
  `:102-103`; MessageClient's limit is 100 KB), and returns them oldest first. The caller passes
  `m -> m.kind == USER ? m.label() : Voice.spoken(app, m.label())`: replies read like the live ones (`:162`), the user's
  words as typed, attachment-only messages as their `📎` label (`Sessions.java:52-57`). An unknown chat gives `[]`.
  Reading `ses.messages` happens on the main thread, as the switch already runs (`:71`).

**Watch: one source of truth.** The phone's snapshot is the truth for everything it holds. `Talk.lines` (this
process's live conversation) can overlap it, so each `Talk.Line` gets a `seq` from a process-wide counter (it survives
`lines.clear()`, `Talk.java:166`). When a snapshot arrives the screen remembers `mark` = the newest line's seq, and
draws: the snapshot, then only the Talk lines of this chat with `seq > mark` (a reply or watch-side error that came
since), then the status line. The next snapshot (pushed right after each phone message) absorbs them. With no snapshot
yet (`mark = 0`) or none at all (old phone, phone unreachable) the screen draws all of `Talk.lines` as today. Watch-side
errors before the latest snapshot are old news and drop out. Alternatives: hide all phone-origin lines once a snapshot
exists (needs a flag in Talk, and a lost push would hide a reply); match lines by text (fragile).

**Watch: `TalkActivity.java`.**
- Request lifecycle, copied from `ChatsActivity.load()` (`:126-148`): in `onStart`, unless `fresh` (no chat on the
  phone yet, `:57-58`), take a new `view`, arm an 8 s timeout, add a MessageClient listener, then `Phone.send(HISTORY,
  {chat, view})` with `valid` = still started and still this `view`. The listener `main.post`s (as Talk does,
  `Talk.java:135`) so a reply and the snapshot after it are handled in arrival order. An answer is used only if its
  `chat` and `view` match. Timeout: a muted note `chats_no_answer` under the conversation; a send error: its own text
  (both strings exist). `onStop`: remove the listener, cancel the timeout, send the stop (fire and forget).
- Rendering: `body` keeps the face, then a `history` container (rebuilt only when a snapshot arrives), then a `live`
  container (Talk lines after `mark`, status, problems; rebuilt by `draw()` as today). A run's frequent step updates
  then don't re-lay-out 30 messages. Messages look like today's lines (`You: …` in `TEXT2`, Buddy in `TEXT`, errors in
  `ERR`, 15sp, 8dp apart, `Look.sidePad` sides) through one small `line(kind, text)` helper used by both containers.
  TextViews stay plain (not selectable: 017's cursor-reveal jump needs a movement method). The `fresh` check keeps
  scanning all of this chat's Talk lines, not only those after `mark`.
- Scrolling: the ScrollView already sits between the pill and the buttons (`:94-132`), so the buttons stay on screen.
  `scroll.requestFocus()` once in `onCreate`, as the chat list does (`ChatsActivity.java:122`), so the bezel (rotary
  events go to the focused view) scrolls it; today the talk screen never asks for focus. The unconditional
  `fullScroll` at the end of `draw()` (`:262`) becomes: go to the end if the user was within 48dp of it before the
  rebuild (012's rule), on the first snapshot of this screen (opened at the newest), or right after the user's own
  words were sent (`onActivityResult`); otherwise keep the position. A screen coming back from sleep keeps its place.

**Mixed versions.** An old phone ignores the path (`default: break`, `WatchLink.java:84`): the watch shows the note
after 8 s and works as today. An old watch never asks: the phone does nothing new. Talk's own listener sees
`/buddy/history` too and drops it (`Talk.java:316,323,366`): no side effect.

## Steps
1. Phone, tests first: `WatchLink.history(...)` plus `HISTORY_MAX`, `HISTORY_MAX_BYTES`, and its JVM tests in
   `app/src/testGithub/java/app/buddy/assistant/WatchLinkTest.java` (see Test plan).
2. Phone: `/buddy/history` in `WatchLink.java`: `HISTORY` constant, `case HISTORY` in the switch, `viewers` with the
   stop rule and the 30-min `until`, `listen()` taken out of `deliver`, the posted and coalesced push at the top of
   `relay.onMessage`, `sendHistory(node)`. The class comment points at this plan for the new path.
3. Watch: `Phone.HISTORY` in `wear/.../Phone.java`; `seq` on `Talk.Line` from a static counter in `wear/.../Talk.java`
   (nothing else in Talk changes).
4. Watch: `wear/.../TalkActivity.java`: request lifecycle, `history`/`live` containers and `line()`, `mark`, the
   scroll rules and `requestFocus()`.
5. Docs: one bullet in README's Galaxy Watch section (the talk screen shows the chat's latest messages, up to 30, newest
   at the bottom, touch or bezel to scroll, new ones appear while it's open, also those from the phone); PRIVACY.md:48
   adds that the latest messages of a chat opened on the watch pass over the same link.

## Test plan
| Done-when | Proof |
|---|---|
| Recent messages, both sides, readable, opened at the newest | JVM tests of `history()`: keeps USER/BUDDY/ERROR oldest first and drops STEP/NOTE/empty; last 30 of 50; a 3000+ char text cut with `…` and no lone surrogate; 30 long multi-byte messages stay under `HISTORY_MAX_BYTES` and keep the newest; `[]` for no messages. On the watch: open a long chat from the list, it lands on the latest message, `You:` lines and replies readable inside the round edge. |
| Touch and bezel scroll; buttons reachable | On the watch: swipe and turn the bezel up to the oldest shown message and back; mic and call stay visible and work from a scrolled position. |
| A new reply appears while open | On the watch, keep a chat open and (a) type in that chat on the phone: the words and Buddy's reply appear; (b) speak on the watch: words and reply appear once each (no doubles after the snapshot); (c) scroll up while a reply arrives: the position holds. |
| Long chats stay fast; same-release phone | Bounds are in the tested `history()` (30 messages, 3000 chars each, 60 KB); history views are rebuilt only on a snapshot, so steps don't re-lay-out them; on the watch a chat with hundreds of messages opens without a visible stall. Phone and watch APKs of one release (both read `BuildInfo.java`, `wear/build.gradle.kts:5-8`). |
| CI green | `build.yml` on the PR: `testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease` (the fdroid build doesn't compile `src/watchlink`). |

The watch checks need a release with both APKs installed (AGENTS.md, "Updating Buddy on the phone"); the Verifier
records them with Nicholas, the same way 007/013/020 were checked.

## Risks
- Message order: no duplicate needs the phone's line message to arrive before the snapshot that holds it. The phone
  sends them in that order and the watch handles them in order; existing watch code already relies on MessageClient
  keeping order (heard → reply → done). If it ever reorders, a line shows twice until the next snapshot (cosmetic).
- A lost push leaves a phone-started reply off the screen until the next message or reopening. Watch-started replies
  still show through Talk.
- Bezel: the talk screen hasn't had focus before; same mechanism as the chat list, but only a device check proves it.
- A lost stop: the phone pushes that chat's messages for up to 30 min to a watch that drops them (the wear app has no
  message service, `wear/src/main/AndroidManifest.xml`, so nothing wakes).
- Privacy: messages typed on the phone now reach the watch too, over the same encrypted link as replies today; nothing
  is logged (PRIVACY.md updated).
- `Talk.java` is delicate (007/020); the only change is a counter on `Line`.

## Open questions
None blocking. The 30-message count is a default Nicholas can change later (one constant).
