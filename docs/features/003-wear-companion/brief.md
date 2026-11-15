---
type: brief
feature: 003-wear-companion
created: 2026-11-15
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 003-wear-companion

## Ask (verbatim, never edit)
Can't you find a way to make the watch work? What if u made the buddy fork we have a version for the Samsung galaxy watch 4? And sync it somehow

## Goal
A Buddy app for Nicholas's Galaxy Watch4 (Wear OS) that uses the watch's own mic and speaker to talk to Buddy on
the phone, synced over Google's Wear Data Layer. Phase 1 (this feature): on the watch, pick a chat, tap, speak; the
message goes to that chat on the phone; Buddy's reply is spoken on the watch (and shown). Phase 2 (later, separate
feature): live voice through the watch. Background: 001 showed Android doesn't expose the Watch4 as an audio
device to phone apps (Samsung call relay), so routing can't reach it; a watch-side app can.

## Non-goals
- Live voice on the watch (phase 2, own feature), but don't block it: the link should be able to carry it later.
- The F-Droid build: the watch link uses Google Play services, GitHub flavor / separate wear module only.
- Approvals, cards, settings or other Buddy screens on the watch (unless the plan shows one is needed).

## Done when
1. A Wear OS app (wear module in the fork) builds in CI and installs on the Galaxy Watch4.
2. On the watch: a list of Buddy chats (from the phone) to pick from; tap to speak; the transcribed text is sent to
   that chat on the phone and runs like a typed message.
3. Buddy's reply text comes back to the watch, is shown, and is spoken by the watch's own speaker.
4. Works with the phone in a pocket/locked (Buddy running as today); clear message on the watch when the phone or
   Buddy isn't reachable.
5. The phone app's F-Droid build stays free of Google libraries and still builds; GitHub build carries the link.
6. Looks like Buddy (same colours/tone) and follows the repo's style; YAGNI.

## Clarifications
- 2026-11-15: Both talk and live voice, talk first, live voice later.
- 2026-11-15: Pick a chat on the watch.
- 2026-11-15: Use the Wear Data Layer (Google Play services), GitHub build only.
