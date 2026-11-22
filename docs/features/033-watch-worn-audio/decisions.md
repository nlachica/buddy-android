# Decisions: 033-watch-worn-audio
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-21 - The watch answers when asked; no permanent sensor owner
- Options: (a) a foreground service on the watch that listens to the off-body sensor and pushes changes; (b) the phone
  asks `/buddy/worn` when Auto is about to use the watch, the watch registers the sensor, takes its first event,
  replies and unregisters.
- Choice: (b) because nothing on the watch runs between asks (no notification, boot receiver, foreground-service type
  or battery cost) and the phone needs no heartbeat or disconnect tracking to tell a silent watch from a worn one.
  The brief's "on change / at start / reconnect" is met by asking at every decision; the watch never pushes.
- By: Planner (claude-fable-5-2), allowed by the orchestrator's run instruction

## 2026-11-21 - The worn gate sits in Auto's find step, not in pick()'s watch boolean
- Options: (a) `pick(..., watch && worn)` from a cached state; (b) Auto's async "does a watch answer" step
  (`Link.find`) also asks whether it is worn; (c) both.
- Choice: (b) because the boolean is read before anything can be asked, so with an on-demand query it would be stale
  at the first reading and send it to the speaker on the wrist. `pick`, `ReadQueue` and `LiveWatch.Call` stay
  untouched, with all their tests. A hand-picked Watch keeps the plain find (`WatchVoice.find` chooses).
- By: Planner (deviates from the run instruction's wording, keeps its purpose: pick's signature and tests stay)

## 2026-11-21 - An answer is trusted for 30 s; unknown, stale or another watch's = not worn
- Options: 10 s, 30 s, 60 s; cache "no answer" or not.
- Choice: 30 s, and a missing answer is cached too, because a burst of readings, a call's 5 s retries and the settings
  note's 5 s poll then share one ask, a watch app that never answers costs its 4 s timeout once per 30 s, and a watch
  taken off is noticed by the next reply. Timestamp: the phone's elapsedRealtime when the answer arrives.
- By: Planner

## 2026-11-21 - A call does not follow the wrist mid-call
- Options: (a) check worn only where a call already places the voice (start, setting change, 026's retries);
  (b) watch for changes during the call and move the voice.
- Choice: (a) because (b) needs a listener on the watch for the length of the call and a new move in a tested state
  machine; a headset that connects mid-call isn't followed today either.
- By: Planner

## 2026-11-21 - No sensor permission flow now
- Options: (a) register without a permission; no sensor, a refused or failed registration or no event = "can't tell"
  = not worn; (b) add BODY_SENSORS (and its Wear OS 6 successor) with a prompt in the watch app.
- Choice: (a) pending Nicholas (plan Q1), because Android's pages list no permission for this sensor and no watch was
  on adb to check Samsung's. A manual release gate (worn watch must show "Watch" in the Auto note) catches the bad case.
- By: Planner

## 2026-11-21 - An answer is reused for 3 s, not 30; asks carry an id; the logic lives in the Google-free `Worn`
- Options: (a) 30 s reuse (round 0); (b) no reuse, ask at every placement; (c) reuse for the sensor's own lag.
- Choice: (c), `FRESH_MS` = 3 s, because Android's bound for noticing a removal is 3 s, so a 3 s old answer is as
  current as a new sample, while 30 s let a watch just taken off (or reconnected) keep a reading (plan review F1).
  (b) gives the same result with more messages and leaves the brief's "state with a timestamp" without a reader.
  Callers during an ask join it. Each ask has an id the watch echoes; only the pending node and id are accepted (F6).
  Ask, timeout and matching sit in `Worn` (src/main) so they are tested without Google's client; the link only sends.
  Supersedes "An answer is trusted for 30 s" and the waiters in `WatchVoiceLink`.
- By: Planner (claude-fable-5-2), answering plan review round 1

## 2026-11-21 - The watch waits 5 s for the sensor, the phone 8 s for the watch
- Options: 2 s / 4 s (round 0); 5 s / 8 s.
- Choice: 5 s / 8 s, because Android allows the initial event 5 s, and the phone adds delivery both ways and the
  service start (F4). The watch's wake lock is 8 s. The settings note keeps one ask in flight instead of replacing
  it every 5 s. A "can't tell" reply carries a reason (`no sensor`, `refused`, `no event`) so the sign-off gate can be
  diagnosed from the phone.
- By: Planner

## 2026-11-21 - A call checks worn after each utterance and on a setting change; once off, it stays on the phone
- Options: (a) only at placement (round 0); (b) also on a setting change while on the watch and after each played
  utterance, "not worn" runs the existing `stop()`; (c) a listener on the watch for the length of the call that pushes.
- Choice: (b), because (a) let an Auto call keep playing on a watch on the table, also after Watch was switched to
  Auto off the wrist (F3), and (c) needs watch code tied to playback. Cost: one `default` method on `LiveWatch.Out`
  and a few lines in `Call`; existing tests untouched. Putting the watch on mid-call is not followed, and no retry is
  armed: one rule, the call stays on the phone until it ends or the setting changes. Supersedes "A call does not
  follow the wrist mid-call".
- By: Planner (the run instruction: decide simply and record it)

## 2026-11-21 - Auto never uses the earpiece in a Live call; 030's "no speaker ends the call" stands
- Options: (a) no change: speaker before earpiece, earpiece-only ends the call; (b) `liveDevice` falls to the earpiece
  when Android lists no speaker.
- Choice: (a) pending Nicholas (plan Q2), because the brief keeps 030, requires the 030/031 tests to pass (031 pins
  earpiece-only to `NONE`) and excludes redesigning AudioRoute, and his phone always has a speaker. The brief's
  "earpiece only when no speaker route is possible" is read as "never while a speaker route exists" (F2).
- By: Planner

## 2026-11-21 - The permission check blocks sign-off instead of gating the release
- Options: (a) ship without a grant flow, manual release gate (round 0); (b) build the grant flow now; (c) no flow
  now, a probe on the watch (step 0 or the Verifier's first check) and a sign-off gate; a permission found sends the
  feature back to the Planner and the flow joins it before any release.
- Choice: (c) pending Nicholas (plan Q1), because the run instruction says to report unknown and ask rather than build
  the flow unless trivial (it is a runtime grant, renamed on Wear OS 6), and (a) could ship an Auto that never uses the
  watch (F5). Supersedes "No sensor permission flow now" where it says release gate.
- By: Planner
