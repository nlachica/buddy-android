# Decisions: 030-live-no-earpiece
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-19 - Every phone live call takes live()'s route: `noEarpiece()` = the call is on
- Options: keep 018's condition (Watch, or Auto after the watch took the voice) and add "the pick is away"; make `drop()`
  call `live()` during a call; `LiveWatch.noEarpiece()` = `Call.on`.
- Choice: `Call.on`. In a call, every path that isn't `live()` ends in `drop()`, Android's routing, and in communication
  mode that is the earpiece. The 2026-11-19 23:28 live.log shows it: `none found: phone`, no `route:` line, and the call
  played on the earpiece. `took` goes with it. This replaces 018's "Auto without the watch stays `""` as today", from
  the 2026-11-17 entry "During a phone live call whose voice belongs to the watch…", and the `took` gate in
  `Call.noEarpiece`.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - A connected pick is applied by live(), first in liveDevice
- Options: the brief's split (the device branch when the pick is connected, `live()` otherwise); `live()` tries the
  pick first.
- Choice: `live()`. In a call, the device branch can still reach the earpiece three ways:
  - `other` makes it `drop()` (026's W2).
  - A refused device leaves no device set.
  - Chromium's later earpiece pick goes unnoticed (`want.equals(applied)` returns early).
  `live()` already covers all three (no `other` gate, `liveRefused`, `slipped`). The order is: the pick (connected, not
  refused, not the earpiece), then a headset's call device, then the speaker, then `NONE`, which ends the call. A pick
  that is here beats a headset, as in 016's `pick()`.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - The earpiece ("Phone") as the pick doesn't apply in a phone live call
- Options: honour it (done-when 3 read literally, and 001's C1, where the voice bar chip set to Phone mid-call gives the
  earpiece); skip it like any other device that isn't safe.
- Choice: skip it. The brief's goal says the call "must never play on the earpiece". The audio device is one setting,
  shared with read aloud and dictation, so a Phone pick made for those would put the next live call on the earpiece,
  which is exactly the reported failure. Done-when 3 ("that device") holds for every device except the earpiece. The
  cost is that 001's C1 mid-call switch to the earpiece ends. Nicholas may overrule this (plan Open questions); the flip
  is one condition in `liveDevice`.
- By: Planner (claude-opus-5-5)

## 2026-11-19 - A live refusal lasts only while the device is listed (plan review round 1 F1)
- Options: keep 018's "refused until `use()`"; clear every refusal when the device list changes; each `liveAttempt`
  drops the refused keys that aren't among the communication devices now.
- Choice: the third, `refused.retainAll(listed keys)`. When the pick (LS2) is switched off, it slips (Android reports
  another device) and `live()` refuses it. LS2 isn't listed any more, so the same tick removes the refusal, and LS2 is
  tried again when it comes back, without a `use()`. A device that refuses or slips while it stays listed stays refused
  until it leaves, so the fallback is still bounded. Clearing on any list change would retry a device that really
  refuses whenever something else connects. This refines 018's "kept until use() so each is tried once" to "once per
  connection".
- By: Planner (claude-opus-5-5)

## 2026-11-19 - The phone's voice is muted until Android reports the device live() applied (plan review round 1 F2)
- Options: accept the windows (up to ~2.3 s inside the slip grace, and a SCO link's setup after a switch) in which the
  voice can play on the earpiece; confirm the speaker with no grace (covers only the speaker); mute the page's audio
  element while the route isn't confirmed; buffer the voice while it is held.
- Choice: mute. Each `live()` tick sets a volatile `AudioRoute.held = !confirmed(applied, key(getCommunicationDevice()))`.
  `reconcile()` clears it outside the live rule, and so does `finish()`. The bridge's `toWatch` adds bit 4, and the page
  mutes `out` while `fwd || held`, the lever 017 uses for the watch. The 2 s grace keeps deciding when to re-apply or
  refuse, but no longer whether the voice plays. Buffering is far more page code for gaps under ~0.4 s. Cost: words
  spoken while the voice is held are lost (plan Risk 6). The brief's goal ("must never play on the earpiece") outweighs
  a short pause.
- By: Planner (claude-opus-5-5)

## 2026-11-20 - The voice is held for the whole phone live call unless a tick confirmed the route (plan review round 2 F1, F2)
- Options: hold only when `live()` sees a mismatch (the round 1 design: excluded modes and a failed read release the
  voice); hold by default for the whole call and release only on a confirming `live()` tick; a test seam around
  `reconcile()` for the mode cases.
- Choice: hold by default. `reconcile()` sets `held = LiveWatch.noEarpiece()` before any read, and only `held =
  !live(am)` clears it. Ringing, phone-call modes and a read that throws keep the active call muted, and the other call's
  routing is left as today. The page starts held too. No test seam: the existing `liveRule` cases and code review prove
  a one-line rule.
- By: Planner (claude-opus-5-5)

## 2026-11-20 - A switch Android makes before Buddy sees it is accepted, and stated (plan review round 2 F1)
- Options: promise "never" anyway; listen for Android's communication-device change (API 31) to cut the window to ~0.1 s;
  accept the ≤~0.4 s window (poll plus page tick) and say so.
- Choice: accept and state it (README, plan Risk 7), and ask Nicholas (plan Open question 2). The page can't pin its
  output, and Android moves the audio before any app hears of it, so no design reaches zero. The listener is a few more
  lines for ~0.3 s and stays an option for Nicholas. Switches Buddy makes, and the start of each reply, are held.
- By: Planner (claude-opus-5-5); Nicholas may overrule

## 2026-11-20 - The speaker gets one re-apply per confirmation, then it is refused (plan review round 2 F4)
- Options: keep the speaker exempt from refusal (with the hold, a speaker that never confirms leaves the call silent
  forever); refuse it on its first slip (Chromium's startup pick would end calls); cap how long the voice may be held.
- Choice: `refuseSlip(applied, speakerSlipped)`. The speaker's first slip since the last confirmed tick is applied again,
  and the second in a row refuses it. With no headset, the existing "no safe device" ends the call with its reason. One
  flag, cleared by a confirmed tick and with `liveRefused`. A held-time cap would need the same state plus a timer.
- By: Planner (claude-opus-5-5)

## 2026-11-20 - An explicit pick that comes back takes the voice from the watch (plan review round 2 F3)
- Options: leave LiveWatch alone (with LS2 picked but away, the watch keeps the voice when LS2 returns, failing done-when
  3); decide again on every `watchWins()` change, Auto included; decide again only while the watch has the voice, the
  pick is an explicit device and `watchWins()` has turned false.
- Choice: the narrow one, one branch in `Call.poll()` that calls the existing `decide()` / `stop()` handoff. Auto keeps
  018/026's "only a change of the setting decides again", because LiveWatch's Auto decision is a non-goal. The voice
  doesn't go back to the watch on its own if the pick leaves again (plan Risk 8).
- By: Planner (claude-opus-5-5)

## 2026-11-20 - Orchestrator: plan accepted after round 3, simplified
- The plan grew a mute/hold mechanism to guard millisecond races. Nicholas wants simple: drop the hold/mute machinery (F1, F3 go away with it). Accepted risk: up to a fraction of a word on the earpiece in the instant a headset disconnects; the steady-state route must never be the earpiece.
- Core change only: during a phone live call, noEarpiece is true regardless of watch outcome or saved key, so reconcile() always uses live() (connected saved device > headset > watch > speaker).
- F2 kept: a watch handoff stops only when the confirmed route equals the explicit saved device key, not any headset. Test LS2 absent + other buds, then LS2 arrives.

## 2026-11-20 - Coder: built the simplified plan; how F2 knows the pick is back
- Options: `plan.md` still describes the hold/mute design (its Step 3, Design 4); the orchestrator entry above drops it.
  For F2, "the confirmed route equals the explicit saved key": a new `Out.pickApplied(saved)`, or reading Android's
  communication device from LiveWatch.
- Choice: followed the orchestrator entry. No `held`, `confirmed`, `refuseSlip`, `speakerSlipped`, no bridge bit 4 and
  no page change; `live()` keeps its speaker exemption from a slip refusal. Steps 1, 2 and 4 are built; Step 3 is not.
  F2: `Out.pickApplied(saved)` is `AudioRoute.applied(saved)`, the key `live()` applied after Android accepted it (a
  slip clears it). `Call.poll()`'s branch is `watch && out.pickApplied(saved)`, with no `watchWins()` test: other buds
  can't match, and Auto (`""`) and Watch never equal a device key. README states the earpiece instant after a headset
  drops, as Nicholas accepted it.
- By: Coder (claude-sonnet-5-5)

## 2026-11-20 - Coder: code review round 1 (F1-F3), smallest fixes
- F1: `AudioRoute.applied(key)` (the `pickApplied` seam) also reads Android's communication device; the pure
  `pickOn(pick, applied, actual)` needs both to match. The watch lets go only when Android reports the pick.
- F2: the speaker's first slip is applied again; `refuseSlip(applied, speakerSlipped)` refuses the second in a row.
  `speakerSlipped` is cleared when Android reports the applied device, and where `liveRefused` is cleared. With no
  headset, `liveAttempt` then gives `NONE` and the existing "no safe device" ends the call. Not the plan's old
  Design 4 hold: no page change.
- F3: `seen` is set when Android first reports the applied device and cleared on each apply. `grace(applied, seen)` is
  2 s for the speaker and for a route seen once, `SETUP_MS` (8 s) for a device not yet seen. `slipped` takes the grace.
  Any device, not only Bluetooth: a wired or USB apply is reported at once, so the longer wait only costs a failed one.
- F4: the plan's D1/D4 checklist and expected log no longer require `page voice held/released`.

## 2026-11-20 - Coder: code review round 2 (F1 rejected, F2 fixed)
- F1 (hold phone playback while a headset is pending): not built; it is the hold/mute the orchestrator dropped. The
  earpiece exposure while a headset connects mid-speech is the accepted risk, now as long as the setup window.
- F2: `SETUP_MS` 8 s -> 30 s (Android's documented window for a Bluetooth call device). A refused headset after 30 s
  with no link still falls to the speaker.
- By: Coder (claude-sonnet-5-5)

## 2026-11-20 - Orchestrator: round-3 F1 is in scope; one more code loop
- F1 is valid and small: while a headset is still connecting (30 s grace), if Android reports the earpiece, switch to the speaker at once (existing fallback); keep waiting only while the reported output is safe. Add the regression test named. Nothing else changes.

## 2026-11-20 - Coder: code review round 3 (F1), earpiece during headset setup
- Options: slip at once when the earpiece is reported; use the normal 2 s wait.
- Choice: `grace(applied, seen, actual)` returns `CONFIRM_MS` when `actual` is the earpiece, so the existing slip,
  refusal and speaker fallback apply after 2 s; the 30 s setup window stays for a safe reported output. Zero would
  refuse a healthy headset in the moment Android hasn't reacted to the apply yet.
- By: Coder (claude-sonnet-5-5)

## 2026-11-20 - Coder: code review round 4 (F1), no grace while the earpiece is reported
- Options: keep the 2 s wait of round 3; zero grace for an unseen headset when Android reports the earpiece.
- Choice: zero, as the reviewer and orchestrator asked. The slip check runs before the next request, so Android still
  has until the next poll after the apply. Trade-off as in round 3: a headset that connects slowly while the earpiece is
  reported is refused for the rest of its listing.
- By: Coder (claude-sonnet-5-5)
