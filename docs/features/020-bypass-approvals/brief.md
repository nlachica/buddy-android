---
type: brief
feature: 020-bypass-approvals
created: 2026-11-17
skipped_stages: []   # e.g. [research, plan-review] for small tasks; never code-review or verify
---
# Brief: 020-bypass-approvals

## Ask (verbatim, never edit)
Keep routine allowed what it is right now, but add another setting... called like bypass... I can set a chat to that and that should make it so there are no approval cards and everything is just going to be done when I say it to be done. And no restrictions like, buddy should be able to do anything when I like the modify buddy settings. Uh, buddy should be able to do whatever. The only things that buddy shouldn't be able to do is handle secrets and like use my money basically. Like that stuff will need approval still. But everything else should be fair game

## Goal
Add a fourth per-chat approvals mode, "Bypass", next to upstream's Ask / Routine allowed / Always ask (Session.approvals,
chat options UI, buddy_settings chat/<id>/approvals from 010). In a Bypass chat no approval card is shown for anything:
phone taps on Send/Delete/etc., confirm calls, the Smart reviewer, phone_shell, buddy_settings (all settings,
including permissions, safety, Linux tools, MCP, skills, schedules), update installs. Still carded in Bypass: secrets
(setting/reading keys, passwords, codes, the 011 key routes) and money (paying, buying, checkout, payment and banking
apps, money transfers). Also revert 019's behaviour so Routine allowed is exactly upstream again (019 shipped in
2.3.11-fork.3 tying no-card settings to Routine allowed; Nicholas said keep Routine allowed as it is).

## Non-goals
- The agent answering cards; changing Ask/Always ask/Routine allowed; F-Droid flavor (github flavor only, recorded).

## Done when
- [ ] Chat options shows Bypass as a 4th mode; buddy_settings can set it (setting a chat to Bypass itself carded once).
- [ ] In Bypass: no cards for settings, phone actions, confirm, reviewer, phone_shell, installs (tests + phone check).
- [ ] In Bypass: secrets and money still card (tests).
- [ ] Routine allowed behaves as upstream again (019 split removed); Ask/Always ask unchanged.
- [ ] CI green; Codex review approves.

## Clarifications
- 2026-11-17 Nicholas: Routine allowed stays as it was; Bypass is the new mode; only secrets and money need approval.
