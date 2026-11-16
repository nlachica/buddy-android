---
type: brief
feature: 016-audio-auto-priority
created: 2026-11-16
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 016-audio-auto-priority

## Ask (verbatim, never edit)
One thing I want you to work on is the auto switching of selected audio device. Earlier it was set to ls freecom even tho it was not connected or online anymore. The auto setting should be smart enough to connect to priority order kinda. Like obviously Bluetooth headset like my Samsung galaxy buds or my ls freecom would be priority, but if they weren't connected, then it would probably be my watch. And if my watch isn't connected then it'd probably be speaker. If that makes sense. I think most phones already do this kind of priority thing, so research and figure out how they do it and apply the same type of practice for the audio thing

## Goal
Buddy's audio device setting (001 selector, plus 012's Watch) should never stay stuck on a device that is gone. Auto picks the best connected output in a priority order like Android's own routing (research how Android/AudioManager, Samsung and iOS order outputs: most recently connected wired/BT headset first): connected Bluetooth or wired headset (Galaxy Buds, LS2 Freecom) > Galaxy Watch (012 path) > phone speaker. A specific device the user picked is used while it is connected; when it disconnects Buddy falls back by the same order and returns to it when it reconnects.

## Non-goals
- A user-editable priority list UI (unless research shows it is the simple standard); routing calls or other apps' audio.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] Auto with buds connected → buds; buds off → watch (when reachable); watch unreachable → speaker; at the moment of speaking, no stale device.
- [ ] Picked device disconnected → falls back by the order, no silence; reconnects → used again.
- [ ] The setting shows what Auto is using now. F-Droid: same minus Watch. CI green; unit tests for the order.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
