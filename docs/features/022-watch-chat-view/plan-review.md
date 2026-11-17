# Plan review: 022-watch-chat-view
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)
F1 [blocker] Implementation and verification plan is missing - `docs/features/022-watch-chat-view/plan.md:1-2` contains only the title and template comment, so none of the five done-when items in `docs/features/022-watch-chat-view/brief.md:19-23` has a proposed implementation or verification method. These are not already satisfied by the code: `wear/src/main/java/app/buddy/assistant/wear/TalkActivity.java:239-251` renders only the active `Talk.lines`, `:262` unconditionally scrolls down on redraw, `app/src/watchlink/java/app/buddy/assistant/WatchLink.java:73-86` has no history request handler, and `:156-158` relays messages only for tracked watch-started runs. -> Have the Planner fill in the design, concrete steps and tests before another review: bounded history transport and rendering; initial positioning and touch/rotary scrolling with reachable controls; updates for the viewed chat, including phone-started replies; reconciliation with live watch messages; request lifecycle/error handling; and same-release phone/watch plus CI verification. Use the research to choose a minimal approach rather than leaving these choices to the Coder.

VERDICT: REVISE

### Planner response (round 1)
F1: accepted - the first planner run died on an API error and left the template; `plan.md` now has Goal, Design,
Steps, Test plan, Risks, and each point you listed is decided there: bounded transport (`/buddy/history`, pure tested
`history()`: 30 messages, 3000 chars each, 60 KB) and rendering (history container rebuilt only per snapshot); opened at
the newest, touch and bezel scroll (`requestFocus`, follow only within 48dp), buttons outside the ScrollView;
updates for the viewed chat including phone-started replies (phone pushes a snapshot after each new message while the
screen is registered); reconciliation with `Talk.lines` (`seq` on `Line`, draw only lines after the last snapshot, push
posted a turn later so lines precede it); request lifecycle (per-request `view`, stop on `onStop`, 30-min lease, 8 s
timeout note, `fresh` chats skip it); same-release and mixed-version behaviour and CI in the Test plan. Six decisions
appended to `decisions.md`.

## Round 2 - Plan Reviewer (gpt-6-astra)
F1 [major] A receive-time watermark cannot reconcile snapshots with live messages - `plan.md:46-48,60-67,120-124` assumes posting the snapshot one main-loop turn later guarantees that its corresponding live lines have arrived, then hides every line received before that snapshot. `app/src/watchlink/java/app/buddy/assistant/WatchLink.java:286-287` starts independent asynchronous sends without waiting; the [MessageClient contract](https://developers.google.com/android/reference/com/google/android/gms/wearable/MessageClient) documents successful queueing, not an ordering guarantee across these sends and listeners. A delayed snapshot containing A can arrive after live reply B, set `mark` past B, and erase B from the screen even though the snapshot never contained it. If B's snapshot is lost, B stays missing until another update/reopen; this contradicts the claimed fallback, and is more than the duplicate-only risk described in the plan. Conversely, a late live line duplicates an already rendered snapshot row. `view` distinguishes registrations, not successive snapshots within one registration. -> Choose reconciliation that does not infer snapshot contents from watch arrival time: either carry a shared phone-side identity/revision for overlap and stale-snapshot rejection, or simplify to authoritative snapshots for phone-origin display once history is available, with an explicit refresh/recovery rule. Add deterministic checks for snapshot-before-line, line-before-older-snapshot, and a delayed older snapshot after a newer one; establish that no accepted newer reply disappears or renders twice.

F2 [major] A New chat never starts history updates after it becomes a real chat - `plan.md:70-75` registers only in `onStart` and skips `fresh`; `:80-81` only clears the flag while scanning Talk lines. The phone accepts the first words asynchronously (`wear/src/main/java/app/buddy/assistant/wear/Talk.java:330-335`), and `TalkActivity.draw()` currently just clears `fresh` on that YOU line (`wear/src/main/java/app/buddy/assistant/wear/TalkActivity.java:245-246`). There need not be another `onStart`. After the first watch run finishes, `WatchLink.endCheck()` removes tracking (`app/src/watchlink/java/app/buddy/assistant/WatchLink.java:242-248`), so a subsequent phone-started reply in that still-open chat has neither a relay nor a history subscription. The fresh-chat exception is justified only while the chat does not exist, not for the screen's remaining lifetime. -> Start the normal history request/subscription once when a started screen transitions from fresh to accepted, using the same lifecycle guard and stop cleanup. Test creating a chat on the watch, receiving its first reply, then sending on the phone while that watch screen remains open; the later user message and reply must appear without reopening.

F3 [minor] Keeping the pixel offset does not keep the reading position when the history window rolls - `plan.md:54,76-87` replaces the last-30/60-KB window on every snapshot and says to keep the position when scrolled up, but does not account for rows removed from its beginning. At the cap, a new reply removes old content above the viewport; preserving `scrollY` then moves the reader to different text, despite the hold-position check in `plan.md:112`. -> Define preservation relative to a retained visible row and its offset (or adjust for the removed rows' measured height), with a simple fallback if that row has left the bounded window. Extend the scroll check to a full window with unequal-height messages and byte-budget eviction.

VERDICT: REVISE

### Planner response (round 2)
F1: accepted - snapshots are now the only source of the chat's messages once one is used, and they are used in phone
order: each carries `n` (a phone counter that only goes up), and the watch drops one whose `n` isn't above the last it
used. `Talk.Line` gets `mirror` (true for `/buddy/heard` and `/buddy/reply` lines, which always stand for a message the
phone added and then pushes). A mirror line is never drawn beside a snapshot, so a reply shows once (from the snapshot)
whether its line or its snapshot comes first, and a late older snapshot is refused instead of erasing it. `seq > mark`
remains only for the watch's own lines (no answer, refusal, stopped, no voice), which are never in a snapshot, so it
infers nothing about a snapshot's contents. Recovery: a mirror line newer than the used snapshot with no snapshot
within 8 s makes the screen ask again (new `view`), once per used snapshot; a lost push of a phone-started message, or a
reordered-then-lost one, waits for the next message or a reopen (Risks). Deterministic checks: a minimal JUnit setup in
the wear module (test-only, `:wear:testDebugUnitTest` in CI) testing the pure `below()` and the snapshot rule with
snapshot-before-line, line-before-snapshot, line-before-older-snapshot, a delayed older snapshot after a newer one,
another `view`, watch-own errors and no snapshot. The phone's posted push stays, now only to coalesce.
F2: accepted - one `request()`: `onStart` calls it unless `fresh`, and `draw()` calls it at the moment it clears
`fresh` while the screen is started (same `view`, timeout, catch-up and stop cleanup). Manual check (d) added: New chat
on the watch, first reply, then type on the phone with the screen still open; the words and reply appear without
reopening.
F3: accepted - the place is kept by a row: before a history rebuild that doesn't follow the end, the screen notes the
top-most visible row (index and offset); after layout the pure `anchor(old, i, now)` finds it (same kind and text at an
index <= i, nearest first, since rows only leave from the top) and restores the offset; if it left the window, the
screen goes to the top. JVM tests for `anchor()` (not full, rolled by one and by three, duplicate text, gone) and the
device check now uses a full window of unequal lengths where the 60 KB budget evicts.

## Round 3 - Plan Reviewer (gpt-6-astra)
F1 [major] A delayed registration can permanently displace the open screen's subscription - `docs/features/022-watch-chat-view/plan.md:37-45` guards a late stop by equality but says every new request replaces the node's registration; it never rejects an older start. For example, request `view=1` is already sent, the watch reopens or refreshes with `view=2`, and the phone receives start 2 followed by delayed start 1. It now pushes only view 1, which the current screen rejects under `plan.md:69`; a subsequent stop 1 can also remove the registration entirely. Phone-started replies have no mirror line to trigger catch-up (`plan.md:77-79`), so all subsequent replies can remain invisible until another reopen, even on a connected same-release pair. `wear/src/main/java/app/buddy/assistant/wear/Phone.java:52-62` checks validity before sending, which cannot cancel an already-sent request, and the [MessageClient contract](https://developers.google.com/android/reference/com/google/android/gms/wearable/MessageClient) promises queueing rather than ordered delivery, the same constraint already recognized at `plan.md:40`. -> Apply the existing increasing `view` to phone-side subscription transitions too: retain the latest view per node, reject older starts and stops, and retain a stopped marker long enough that a delayed start cannot resurrect a stopped view or replace a newer one. Keep this in the existing registration state; no new transport is needed. Add deterministic tests for start 2 → start 1 → stop 1 (view 2 must continue receiving updates), stop 2 → delayed start 2 (must remain stopped), and an ordinary newer start after a stop. The previous round's snapshot reconciliation and New chat registration findings are otherwise addressed.

VERDICT: REVISE
