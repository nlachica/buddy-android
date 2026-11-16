# Decisions: 010-settings-chat-approvals
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-16 - One `Scalar` row `chat/<id>/approvals` from a JVM-testable factory, written only through `ChatHub.setApprovals`
- Options: write `Session.approvals` directly in a `chatSwitch`-style row; a new `Setting` subclass; a static
  `approvals(chat, title, refusal, get, set)` factory like 005's `allowedApp`, wired in `find()`.
- Choice: the factory, with the stored↔shown mapping inside it so JVM tests cover it with in-memory state; `find()`
  passes `c.approvals` and `hub.setApprovals(c, v)`. A direct write skips `Guard.forget` and `changed()` (the brief's
  "cached approvals refresh"). `change()`, `ChatHub` and the chat screen are untouched.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Values `ask`, `routine`, `strict` (the stored names; `ask` for the stored `""`); labels only in `allowed()` and the card
- Options: the UI labels as values (`routine_allowed`, `always_ask`); also accept label aliases; `""` for Ask.
- Choice: the stored names, as 004/005 rows use their stored values (`tap_action`, `app/<pkg>`). `""` reads as delete
  in `change()` (005 decision), so Ask needs a name. `view()` stays the plain choice because `change()` decides
  "Already" with `v.equals(view(old))`. `allowed()` and the refusal show "ask (Ask), routine (Routine allowed),
  strict (Always ask)", so the agent maps the user's words without an alias table.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - A notification rule's chat: listed as n/a and refused before any card, as the UI hides its line
- Options: let it be set (it would apply in that chat's user runs, which the UI never allows); treat it as an unknown
  setting; refuse with the reason.
- Choice: refuse through Scalar's `ready` hook with a refusal computed in `find()` from `ChatHub.approvalsEditable`
  (the UI's own test); `chatLines` gets an `editable` predicate and prints `approvals n/a (notification rule's chat)`.
  An unknown-setting answer would hide why.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - The card names the chat by its title (one line, ≤ 60 chars) and spells out the new mode's effect
- Options: the bare `chat/<id>/approvals` name as the other chat rows have; add the title.
- Choice: add it on the "Now" side; control characters become spaces and it's cut at 60 with "…". The card shows in
  the calling chat, so a change to another chat would otherwise show only an opaque id, on the one switch that loosens
  approvals in every app. Effects follow upstream's `chat_approvals_*_info` strings (English, like every row's card).
  The other chat rows are left as they are (out of the brief).
- By: Planner (claude-opus-5-5)

## 2026-11-16 - No extra rule against an agent loosening its own chat; the card is the gate
- Options: refuse `routine` for the caller's own chat or during a run; allow it on the card.
- Choice: allow it on the card, 005's stance for settings that lower protection; the brief's non-goal only rules out
  changes without the card. The settings card is `askConfirm(card, false)` (never auto-approves; `Guard.runRule` isn't
  consulted) and `approval()` refuses AUTO/RULE_APPROVED, so "Routine allowed" can't approve its own change.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - No GUIDE.md sentence
- Options: add a fork sentence to upstream's "Approvals, per chat" paragraph; rely on the existing fork note.
- Choice: rely on `docs/GUIDE.md:204`'s fork note (agents change settings through `buddy_settings` behind a card).
  Another fork line inside upstream's paragraph is one more future sync conflict for little gain.
- By: Planner (claude-opus-5-5)

## 2026-11-16 - Listing keeps the plan's `approvals n/a` for a rule chat; plan-review F1 (minor) not adopted
- Options: `approvals n/a (notification rule's chat)` (plan.md); `approvals <mode> (read-only: …)` (plan-review F1, a
  minor suggestion on an APPROVE, with no Planner response and no plan change).
- Choice: the plan as approved, per "implement the plan, don't redesign". The individual read still shows the stored
  mode with the refusal. Switching is one line in `chatLines` plus T8 if the orchestrator wants it.
- By: Coder (claude-sonnet-5-5)

## 2026-11-16 - The row's snapshot is the plain choice (`ask|routine|strict`), not the stored value
- Options: snapshot the stored value and map in `view()`; snapshot the choice.
- Choice: the factory's getter maps `""` to `ask` (`approvalsChoice`), so `view()` stays default, `change()`'s
  "Already" check compares `v.equals(old)`, and `card()` needs no mapping. The setter maps `ask` back to `""`.
- By: Coder (claude-sonnet-5-5)
