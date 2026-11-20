# Plan review: {{SLUG}}
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6.2-astra)

F1 [major] A headset disconnect can exclude the saved device for the rest of the call - Once LS2 has been applied for two seconds, unplugging or switching it off can make Android report the speaker at the next poll. `AudioRoute.java:328-334` then adds LS2 to `liveRefused` before reading the available devices. When LS2 reconnects, the proposed first loop skips it (`plan.md:81-83`). D3 also accepts the speaker after a slip even when LS2 remains usable (`plan.md:129-137`), so it can pass while done-when 3 fails. -> Tie refusal to the current connection. Give a reappearing saved device a fresh attempt while retaining bounded fallback for a device that actually refuses. Exercise LS2 → speaker on disconnect → LS2 on reconnect in one established call, without another `use()` or settings change. Require audible LS2 for the healthy-device D3 case.

F2 [major] Playback can start while Android still reports the earpiece - `AudioRouteTest.java:143` explicitly ignores a speaker-to-earpiece mismatch at 1,999 ms. During that interval `AudioRoute.java:349-353` treats the cached speaker key as success, and `CodexLive.java:1023-1026` plays the phone track without checking the actual route. A later speaker reapply can therefore produce the expected D1 log even if the start of a reply played on the earpiece. -> Cover an actual earpiece route during startup and during a spoken reply. Specify how phone playback stays silent until a safe route is confirmed, including when Chromium overrides a successful request. Verify the first audible output and the transition, as well as the final route.

F3 [minor] The D2 setup and user-facing wording omit watch playback - With LS2 off and no headset, `AudioRoute.java:93-96` can select Watch. A reachable watch then takes the voice and mutes the phone (`LiveWatch.java:214-218`, `CodexLive.java:945`), even if the log shows the speaker route that D2 expects. The proposed README and settings text at `plan.md:103-108` likewise promises a phone speaker in that case. -> Run D2 with the watch unreachable and no other headset. Describe the new order as the route used when the phone plays the voice, preserving the existing watch behavior.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted. A refusal now lasts only while Android lists the device. `liveAttempt` first drops from `refused` every
key that isn't among the communication devices it was given (`refused.retainAll(listed)`). When LS2 is switched off,
the slip in `live()` still refuses it, but LS2 isn't listed any more, so the same tick removes the refusal. When LS2 is
back, the first loop tries it again, with no `use()` and no setting change. A device that refuses or slips while it stays
listed stays refused until it leaves or until `use()`, so the fallback is still bounded. This refines 018's "each is tried
once" to "once per connection". New tests: `AudioRouteTest.liveAttempt` keeps one `refused` set through LS2 applied, LS2
gone (the speaker, refusal dropped) and LS2 back (LS2 again), plus a refusing LS2 that stays listed and isn't tried
again. D3 now fails on any `route: slipped from 7:LS2 Freecom` or speaker while LS2 stays on, and the voice must be heard
in LS2. The new D4 covers LS2 off, then on, in one call, by ear and in live.log. (plan Design 3, Step 1, Test plan,
decisions.md)

F2: accepted, in its smallest form. The phone's voice is muted whenever Android's communication device isn't the one
`live()` applied. Every `live()` tick sets a volatile `AudioRoute.held = !confirmed(applied, key(cur))`, and the
bridge's `toWatch` adds bit 4 while it is set. The page then mutes `out` while `fwd || held`, the same lever 017 uses
when the watch takes the voice. This covers:
- startup: nothing applied yet, or Chromium's earpiece pick before `connected()`'s re-apply;
- the 1,999 ms case: the speaker applied but the earpiece reported, so the voice is muted, while `slipped` still waits
  the full 2 s before re-applying;
- a SCO link that is still setting up, during a switch.
The 2 s grace now decides only when to re-apply or refuse, never whether the voice plays. Cost: words spoken while the
voice is held are lost, because the voice is live, not buffered. That is at most ~370 ms after each apply (a 300 ms poll
plus the page's 70 ms tick), or longer while SCO sets up. Unit: `AudioRouteTest.confirmed`. Device: D1 and D3 check the
first words, and D4 checks each switch. No more than this: no buffering, and no faster speaker re-apply. 018's device log
(026 plan.md:63) applied `route: 2:SM-G990W` with no slip after it, so the speaker's key matches
`getCommunicationDevice()` on this phone, and a confirmed speaker is never held. (plan Design 4, Step 3, Test plan,
Risk 6, decisions.md)

F3: accepted. D2 and D4 now run with the watch unreachable (its Bluetooth off) and no other headset. The README and
`buddy_settings` text describe the order as the route used "when the phone plays the voice", and say that an away pick
is Auto, so a reachable watch can still take the voice as today. (plan Step 4, Test plan)
