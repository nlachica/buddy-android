---
type: brief
feature: 009-watch-open-screen
created: 2026-11-16
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 009-watch-open-screen

## Ask (verbatim, never edit)
Right now If I open the buddy app on the watch, it goes to the Google mic thing. Then I have to cancel by swiping left, then I see the options for the call or mic. Instead, I want it to just go straight to the mic or call screen, and also something that let's me choose between a chat (but default is still resume talking to the same chat, or the chat that I'm working on that just responded to me). But then the double tap home should continue to go straight to the talking thing, which is good.

## Goal
Opening Buddy on the watch from the app list (launcher tap) shows the talk screen with the mic and call buttons, without
starting the recognizer. That screen is for the default chat: the last chat talked to, or a chat that has just replied
to him (most recent wins), and it has a clear way to pick another chat (the chat list). Home double-press keeps today's
behaviour: straight into listening (003/006).

## Non-goals
- Changes to calls, voices, the phone side, or the list itself beyond what picking a chat needs.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] App-list tap → talk screen of the default chat, mic not started; one tap on mic or call starts it.
- [ ] Default chat = most recent of: last talked-to chat, chat whose reply most recently reached the watch.
- [ ] From that screen, one obvious control opens the chat list; picking a chat makes it the talk screen's chat.
- [ ] Home double-press still goes straight to listening; 006 reopen-while-waiting still keeps the reply.
- [ ] CI green.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
