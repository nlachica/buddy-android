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

## 2026-11-20 - No playback hold while a headset connects (plan review round 1 F1)
- Options: hold or mute the phone's voice while Android reports the earpiece during a headset's setup; accept the
  earpiece until the headset is reported or its 30 s window ends.
- Choice: accept. The brief's DW1 asks for the setup time "while Android still reports the earpiece". The hold/mute was
  dropped in 030 because Nicholas wants it simple. DW2's "never the earpiece" is about where a failed headset ends up
  (the speaker), which stays.
- By: Planner (claude-opus-5-5); Nicholas may overrule with a shorter earpiece bound

## 2026-11-20 - Dictation keeps its capture order (plan review round 1 F2)
- Options: start capture (recognizer, MicServer) only after the headset is confirmed, with a timeout and cancellation;
  keep today's order (route request posted, capture starts, SCO up 0.3-2 s later).
- Choice: keep it. Saved = LS2 has always run in this order and worked before 030. Gating would mean new code in four
  classes. Cost: the first moment of an utterance may come from the phone mic (plan Risk 3).
- By: Planner (claude-opus-5-5)

## 2026-11-20 - Auto's dictation override is a Bluetooth headset's SCO only, and Auto only (plan review round 1 F4; replaces the two Fix 2 entries above where they differ)
- Options: `liveDevice("")` (also wired, USB, BLE, the speaker) for Auto and Watch; only a `BLUETOOTH_SCO` named like a
  connected media output (`mediaHeadset`), for Auto only.
- Choice: the second. DW3 names Auto and saved LS2. A wired headset's mic is already Android's default input. Watch,
  wired, USB and BLE keep their routing, and nothing new goes into call mode for them. It is one condition with an
  existing helper.
- By: Planner (claude-opus-5-5)

## 2026-11-20 - Auto's dictation SCO is released on the first idle poll (plan review round 1 F3)
- Options: wait for `finish()` after `QUIET_MS` (a reading in that time sees the SCO and can go to the watch); release
  it in the poll's idle branch when `applied != null`, `want()` is "" and no live call was seen; release it from Voice
  when listening ends.
- Choice: the idle branch, a single new branch in AudioRoute. It releases the SCO within 300 ms of listening ending, and
  ReadQueue's re-check when the watch find returns (`ReadQueue.java:187`) covers a reading that starts inside those
  300 ms. Releasing from Voice would add a hook from Voice into AudioRoute that the poll already makes unneeded.
- By: Planner (claude-opus-5-5)

## 2026-11-20 - The earpiece wait for a headset not seen yet is 5 s (plan review round 2 F1; replaces the first entry's 30 s for that case)
- Options: 0 (030 round 4, the bug); 2 s (030 round 3, under LS2's ~2.7 s); 30 s whatever Android reports (the
  previous plan, up to 30 s of a reply on the earpiece for a listed headset that never connects); a hold/mute while the
  earpiece is reported (dropped by Nicholas in 030); a bound of 5 s.
- Choice: 5 s (`EARPIECE_MS`) while Android reports the earpiece. 30 s (`SETUP_MS`) stays while it reports a safe
  output. `grace` keeps its 3-arg signature, and on main only the 0 changes. LS2's link came within about 2.7 s
  (`0.26` applied, `2.99` reported), so 5 s covers it with about 2x margin and bounds the earpiece exposure to 5 s
  after the latest request.
- By: Planner (claude-opus-5-5); Nicholas may pick another bound or ask for the hold back

## 2026-11-20 - A seen headset's loss counts from the last poll that saw it (plan review round 2 F2)
- Options: from the apply (main and pre-030: a single earpiece report more than 2 s after the apply refuses a working
  headset); from the first sighting (the same failure later in the call); from the last sighting, for headsets only;
  also for the speaker.
- Choice: the last sighting, headsets only, through a pure `lossFrom` (the file's pattern, like `grace` and
  `refuseSlip`), so the rule has a unit test. The speaker keeps 030's clock from its apply, so another app's pick over
  it is still undone at the next poll after its first 2 s. A disconnect doesn't use the clock: `liveAttempt` moves on
  at the next poll. Cost: a link drop while the headset stays listed can play up to about 2 s on the earpiece.
- By: Planner (claude-opus-5-5)

## 2026-11-20 - Dictation doesn't track whether the SCO is pending or active (plan review round 2 F3)
- Options: track Android's reported device on the non-live path, with a timeout and fallback; leave the path as it is.
- Choice: leave it. While the link is pending, input is the phone mic. The only fallback, `drop()`, gives the same phone
  mic, and waiting would be capture gating (round 1 F2). The state ends with the dictation (Auto: the first idle poll;
  a saved pick: session end). DW3's proof is the headset-only phrase on the phone, for Auto and saved = LS2.
- By: Planner (claude-opus-5-5)
