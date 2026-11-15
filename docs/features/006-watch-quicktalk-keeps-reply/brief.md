---
type: brief
feature: 006-watch-quicktalk-keeps-reply
created: 2026-11-15
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 006-watch-quicktalk-keeps-reply

## Ask (verbatim, never edit)
Busy-chat fails: quick talk while the watch waits drops the first answer, so you only heard four. Fix via pipeline (006).

## Goal
On the watch, opening Buddy again (Home double-press quick talk, or the launcher) while a talk screen is still waiting
on a reply currently finishes that screen and its wait (003 decisions, singleTask cost), so the earlier answer is never
spoken. Make a reopen keep the pending run tracked: the first answer is still shown and spoken, then the new message
goes to the phone (as a note if the chat is busy) and its answer follows, matching 003 done-when 2's busy-chat case.

## Non-goals
- Changing what quick talk opens, the phone-side note handling, live voice, or anything outside the watch reopen path.
- Redesigning Talk/TalkActivity beyond what the fix needs.

## Done when
<!-- testable items; the Verifier checks each one -->
- [ ] Watch waiting on a long reply ("write a 400-word story about a fox"); a quick-talk reopen and "what is two plus two" → the story is spoken, then "four", each once; the phone chat holds both messages once.
- [ ] Reopening while waiting without speaking (cancel the mic) still lets the first answer be spoken.
- [ ] Existing 003 behaviour unchanged: quick talk with nothing pending listens; an unseen reply is shown first.
- [ ] CI green; watch APK installed on the Watch4 for the device check.

## Clarifications
<!-- Nicholas's later answers, dated, appended -->
