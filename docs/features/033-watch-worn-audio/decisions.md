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
