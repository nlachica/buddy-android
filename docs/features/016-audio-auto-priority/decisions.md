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
