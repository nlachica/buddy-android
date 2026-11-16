# Decisions: 016-audio-auto-priority
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-16 - Auto's headset step is Android's own routing; Buddy only detects that a headset is connected
- Options: apply the top-ranked headset as the communication device (mic + speaker, call mode, SCO); leave Auto to
  Android for headsets and only decide "headset connected → Android, else watch, else speaker".
- Choice: the second. Android's media routing already prefers a connected headset and takes the most recently connected
  one (the brief's rule). Apps see no connection times, so Buddy can't rank headsets better. It also keeps A2DP quality
  and 001's "Auto behaves as before" for headset users. Cost: under Auto, voice input stays on Android's default mic
  (plan Risk 4).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The order lives in one pure function, `AudioRoute.pick`, with `headset(int type)`
- Options: order logic spread over `Voice.speak`, the UIs and `reconcile`; one pure function used by speaking, display
  and the settings read.
- Choice: one function, `pick(saved, savedHere, headset, watch)` → saved key / "watch" / "" (Android). The UIs and
  `Voice.speak` call it through `target()`/`autoNow()`, and it is unit-tested without Android. Headset types: wired
  headset/headphones, USB headset, BT SCO/A2DP, hearing aid, BLE headset/speaker.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Headset detection by `getDevices(OUTPUTS)`; label from `getAudioDevicesForAttributes` on API 33+
- Options: `getAvailableCommunicationDevices()` (misses A2DP-only outputs, API 31+); `getDevices(GET_DEVICES_OUTPUTS)`
  (API 23); `getAudioDevicesForAttributes` (API 33, Android's exact pick).
- Choice: API 33+ asks `getAudioDevicesForAttributes(USAGE_ASSISTANT)` first, so the label names the headset Android
  really uses when several are connected. The `getDevices` scan is the fallback and the only path on API 30-32. Both
  are public, need no permission, and were checked in the Android reference on 2026-11-16.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Auto's watch step goes through ReadQueue as a quiet item
- Options: a cached watch reachability checked synchronously in `Voice.speak` (stale at speak time); deciding in
  `Voice` after an async find (replies reorder); ReadQueue's per-reading find, with a quiet flag.
- Choice: ReadQueue, quiet. One find per reading is the fresh "at the moment of speaking" check, and the queue keeps
  order. Quiet means no "watch unavailable" notice (Watch wasn't picked), and the fallback goes to Voice's normal phone
  path (`Out.here`: ChatGPT's voice when chosen), not waited for. ChatGPT's phone voice has no done callback, and using
  the phone TTS instead would change the voice for Auto users with no watch around. Quiet fallbacks don't use up
  012's notice.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - A picked device that is gone follows the same order; the saved pick is kept
- Options: clear the pick when its device disconnects; keep it and treat it as Auto while it is away.
- Choice: keep it. `pick` gives an away pick the same result as Auto, and routing re-applies the pick on reconnect
  (001), so "reconnects → used again" holds. A picked Watch keeps 012's behaviour (notice, then the phone). The phone
  side is Android's routing, so it already goes headset, then speaker.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Showing what Auto uses: Setup note, voice bar chip, settings read; the watch answer is memoized 5 s for display
- Options: Setup note only; all three surfaces from one helper; a live reachability listener in the watch link.
- Choice: one helper `AudioRoute.autoNow` for Setup ("Auto is using X now.") and the chip ("Auto: X", max width 120 dp).
  `buddy_settings` read states the step synchronously ("watch, or X when no watch answers"), and `get()` stays the
  card's snapshot. Display reuses a watch answer for 5 s, so the chip's 1-s refresh doesn't run a lookup every second.
  Speaking never uses the memo. No listener: it would be new link API for display only.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - No user-editable priority list
- Options: an ordered list in Settings; the fixed order headset > watch > speaker.
- Choice: fixed. It's a brief non-goal, and research found no such list on stock Android (the platform orders by device
  class and then recency).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - A headset means a media output, and Android's API 33 answer wins (replaces the detection entry above)
- Options: any connected headset type, SCO included, with the `getDevices` list allowed to override Android's answer
  (round 0); Android's `getAudioDevicesForAttributes(USAGE_ASSISTANT)` answer final on API 33+, `getDevices` only
  without it, SCO not a headset.
- Choice: the second (plan review round 1 F2). A call-only (HFP/SCO) link gets no media in normal mode, which is where
  Auto stays. The Galaxy Watch's own call link is SCO and would have blocked the watch step. Android's answer names the
  output it really uses. Without an answer (API 30-32), the label is the generic "Headset". Pure
  `headsetIn(routed, connected)` holds the rule and is unit-tested. A call-only headset can still be picked (001).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Each reading decides watch-or-phone when it starts and after its find; quiet phone fallbacks keep the voice and are waited when they can be (replaces the "quiet item" entry above)
- Options: decide once in `Voice.speak` and end quiet fallbacks at once (round 0); re-check in ReadQueue at start and
  after find, line new readings up behind a busy queue, keep a quiet reading on the phone while the phone speaks, wait
  for phone-voice fallbacks.
- Choice: the second (round 1 F1, F3). Submission-time answers go stale while a reading waits or a find runs. Lining
  up keeps order across a switch. `phoneBusy` stops two voices at once when a watch appears while the phone still
  speaks (a picked Watch ignores it). Phone-voice fallbacks use the waitable `Voice.routed`, so a cold TTS can't drop
  one. ChatGPT-voice fallbacks go to CodexSpeech's own queue (no done callback; it keeps order and joins fallbacks), so
  the voice stays ChatGPT's. Two new `Out` methods (`watchWins`, `phoneBusy`) keep the rule inside the tested
  ReadQueue.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Auto's status shows in Setup (always, refreshed every 5 s while on screen) and the settings read; not on the voice bar (replaces the "showing what Auto uses" entry above)
- Options: Setup + voice bar chip + read with a 5-s display memo (round 0); Setup + read only, Setup re-asking every 5 s
  while visible; a Wear capability listener in the link.
- Choice: Setup + read, with a 5-s re-ask (round 1 F4-F6). The watch isn't an Android device, so no callback re-renders
  Setup when it comes or goes. A 5-s re-ask while the page is resumed is the smallest fix, and a listener would be new
  link API for display only. The voice bar is dictation and live voice, where Auto's watch step never applies, so the
  chip stays as it is and the memo goes. The label and note show even when Auto is the only choice (F-Droid API 30);
  the segments still need two or more.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - In the github build every reading goes through ReadQueue; a quiet reading waits while the phone speaks, then decides (replaces the `phoneBusy` part of the entry above)
- Options: a quiet reading follows on the phone while the phone is busy, and headset readings skip ReadQueue (round 1);
  a route check at CodexSpeech's dequeue that hands a reading back to ReadQueue; every reading through ReadQueue, and a
  quiet one waits (re-asked every 300 ms with `out.after`) until the phone is quiet, then decides by the order.
- Choice: the last (plan review round 2 F1). A reading queued in CodexSpeech's or the TTS queue keeps a route that can
  be gone by its turn, for example buds taken out while the reply before it plays. When nothing reaches the phone while
  it is busy, there is no stale queue there. It needs no new CodexSpeech API, and all the rules stay in the unit-tested
  ReadQueue. Phone-voice readings are now always waited for. F-Droid (no link) is unchanged: there, Android's routing
  when the voice starts is already the order. Cost: overlapping ChatGPT readings each open their own call (plan
  Risk 9). The dequeue check is the follow-up if that pause is noticeable.
- By: Planner (claude-opus-5-5)
