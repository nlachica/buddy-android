# Decisions: 031-headset-route
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-20 - Live: a headset Android hasn't reported yet gets the 30 s setup window, whatever Android reports
- Options: 0 ms while the earpiece is reported (030 round 4, the bug); 2 s (030 round 3); a shorter earpiece-only bound
  such as 5 s; `SETUP_MS` (30 s) for any device not seen yet.
- Choice: `SETUP_MS`, and `grace(applied, seen)` drops its `actual` parameter. Android reports the earpiece for as long
  as a SCO link connects (live-ls2.log `0.26`/`0.56`, `7.59`/`8.44`), so any earpiece-specific bound is a guess at
  headset setup time, and that guess broke 031. The brief asks for "the time Android needs to connect" and a fall to the
  speaker after "its setup time". It is also one branch fewer. Cost: a listed headset whose link never comes can leave a
  reply on the earpiece for up to 30 s, then the speaker (plan Risk 1).
- By: Planner (claude-opus-5-5); Nicholas may overrule with a shorter bound

## 2026-11-20 - No un-refusal of a device Android reports
- Options: also clear a refusal when Android reports that device (research's second fault, the `2.99` speaker fight);
  leave refusals as they are.
- Choice: leave them. The fight only followed LS2's wrong refusal at `0.56`, which the grace change removes. YAGNI.
- By: Planner (claude-opus-5-5)

## 2026-11-20 - Auto while dictating takes a connected headset's call device (reverses 016's "input stays on the default mic")
- Options: keep 016 (Auto = Android's routing, phone mic); the headset's SCO for the whole voice session; the SCO only
  while `Voice.listening()`.
- Choice: only while listening, through `want()`, so the existing pick path (call mode, `setCommunicationDevice`,
  `drop()` after) does the work. The brief requires the headset mic for dictation under Auto. Over a whole session, read
  aloud would lose A2DP quality, and on API 33+ `target()` would see the SCO and send readings to the watch. Watch counts
  as Auto for routing, so a Watch pick also dictates into a connected headset.
- By: Planner (claude-opus-5-5)

## 2026-11-20 - "A headset's call device" for dictation is liveDevice's rule, reused
- Options: a new SCO-only predicate; `liveDevice("", ...)` with no refusals, ignoring a speaker answer.
- Choice: reuse. The rule is the one Live already uses and tests: a SCO named like a connected media headset (so not the
  watch's SCO), or a wired/USB/BLE/hearing-aid headset. Wired headsets get call mode while dictating (same mic) instead
  of a second predicate.
- By: Planner (claude-opus-5-5)
