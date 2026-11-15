# Decisions: 006-watch-quicktalk-keeps-reply
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-15 - A relaunch is told from a leave by a finishing `onStop` plus a relaunch counter
- Options: (a) a "reopening" flag set in `ChatsActivity.onNewIntent`, checked in `TalkActivity.onDestroy`; (b) delay
  `Talk.left()` by a grace period, cancelled when a new talk screen appears; (c) never end the wait when the screen
  closes; (d) launch-mode / separate-task changes so the relaunch doesn't clear the talk screen; (e) end the wait only
  from a finishing `onStop` of a screen created after the last relaunch (`ChatsActivity.relaunches`, bumped first in
  `onNewIntent`).
- Choice: (e). It doesn't depend on the order of the old screen's destroy and the new one's create (research R1): out of
  sight, the old screen gets no finishing `onStop` (and can't have been swiped); showing, it is stopped only after
  `ChatsActivity` has its new intent. (a) fails from the watch face (old screen destroyed before `onNewIntent`), (b) is
  a timing guess, (c) drops 003's swipe-away rule, (d) breaks Back-to-list and is a bigger change.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - New words for the same chat join its conversation while it is live (waiting or still speaking)
- Options: (a) join only while `Talk.waiting`; (b) join while waiting or while the service still says replies
  (`instance != null`); (c) join only for the quick-talk path (an extra into `begin`).
- Choice: (b), keyed on `Talk`'s state, whatever opened the screen (so a mic tap while waiting is fixed too). The story's
  reply and `done` arrive together, so while the user is speaking the second message the wait may be over with the
  story mid-speech: (a) would hush it and clear its lines, failing done-when 1. (c) adds plumbing for no gain. A join
  keeps lines, speech queue and (if waiting) the listener, and resets only the latest message's state; anything else
  (other chat, nothing live) replaces the conversation as in 003, hush included.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - While the latest words are unconfirmed, only the `noHeard` deadline ends the wait
- Options: (a) as 003: any phone message cancels `noHeard`, `done` and `status running:false` end the wait; (b) only
  `heard` cancels `noHeard`; `done` and `status running:false` are ignored while `!heard`.
- Choice: (b). During a join the earlier run keeps sending steps, replies and its `done`; these say nothing about the new
  words. (b) covers the phone's `done` crossing the watch's send (research R4): the phone then starts a run for the new
  words and sends their own `heard`/replies/`done`. A lost send is still caught by `noHeard`. Cost: a lost `heard` in a
  fresh conversation now ends it with "didn't answer" (plan R3).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Quick talk while waiting opens the mic; other opens unchanged
- Options: (a) keep 003 (waiting → only show the lines); (b) `news = unseen && !waiting` for every open; (c) a `"speak"`
  extra from `ChatsActivity.talkToLast()`: with it, waiting doesn't block listening; after the wait an unseen reply is
  still shown first.
- Choice: (c). Done-when 1 and 2 need the mic on a reopen while waiting; (b) would also pop the mic when the user taps
  the "Waiting for Buddy" notification to look. Chat-list taps and the notification behave as in 003.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Speech is not held while the mic listens
- Options: (a) hold new replies (and leave speech in progress) while the recognizer is up, release on its result;
  (b) leave speech as it is.
- Choice: (b). (a) needs new state that must be released if the screen dies mid-listen, and the brief rules out redesign.
  Overlap already existed in 003 for a reply arriving during a mic tap. Plan R1; the device run shows whether a
  follow-up is needed.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Joined words are sent one at a time, each after the words before them are settled
- Options: (a) a join bumps `run` and sends at once, taking `waiting` to mean the listener is registered (round-0
  plan); (b) send at once and count unacknowledged words; (c) queue the words and send one set at a time, each after
  the one before is heard, refused, failed or given up by `noHeard`, with every send waiting on the stored registration
  `Task`; (d) refuse or replace words said while earlier words are in flight.
- Choice: (c) (plan-review F1). (a) can drop the first words (their callbacks die with the bump) or send before
  registration, and an earlier `heard` can confirm later words. (b) keeps no order between two sends in flight and
  still needs per-send ids for late failures. (d) loses words, or the first answer, in a 1-10 s window. (c) keeps one
  `heard` flag (now "nothing in flight"), keeps order, and needs only a list. `run` now goes up on a new conversation,
  an ended wait and words given up, not on a join.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - A failure of some words doesn't end a run the phone has accepted
- Options: (a) as 003 and round 0: a failed send, refusal or `noHeard` ends the whole wait; (b) these settle only the
  words in flight (`settle`: error shown and said, next queued words sent), and the wait goes on while `accepted` (a
  `heard` with no `done` since); conversation-wide failures (registration, ceiling, lost contact, `status
  running:false` with nothing in flight, screen closed) still end it; (c) also retry failed words.
- Choice: (b) (plan-review F2). (a) loses the story whenever the added question fails. (c) risks duplicates on the
  phone and isn't asked for. A `done` that comes while words are in flight sets `accepted` false and is settled by those
  words: their `heard` brings their own `done`, and their failure ends the wait. This replaces the earlier entry "only
  the `noHeard` deadline ends the wait" while words are unconfirmed; its `done` and `status` rules stay.
- By: Planner (claude-opus-5-5)
