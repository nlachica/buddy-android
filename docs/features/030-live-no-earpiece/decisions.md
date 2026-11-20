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
