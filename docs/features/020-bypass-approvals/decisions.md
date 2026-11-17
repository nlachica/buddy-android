# Decisions: 020-bypass-approvals
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-17 - Bypass is skipped in `BuddyService.askConfirm`, opt-in per call site (fail-safe)
- Options: skip every card in askConfirm by scanning its text (fail-open); a Bypass check with its own note at each of
  ~9 sites; a new `askConfirm(summary, canAuto, bypass)` that skips and notes only when the caller opted in.
- Choice: the opt-in overload; the 2-argument form passes false, so any card not opted in (the clipboard, a card a
  future upstream sync adds) still shows in Bypass. askConfirm already holds the chat-level card policy (helpers,
  a rule's "Don't ask" → RULE_APPROVED + note), so one note and one chat test live there. Fail-open loses the money app,
  the checkout URL and scans release notes.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - `BYPASSED` is not `selfApproved`, so `SettingsTool.approval()` accepts it
- Options: add it to selfApproved and keep a separate skip in change() (019's path) and in update_buddy; leave it out.
- Choice: leave it out: in a Bypass chat the user's standing choice is the yes, and buddy_settings/update_buddy need no
  skip code of their own. The cards that must stay (keys, setting a chat to Bypass, money switches) pass bypass=false
  and never get BYPASSED. `confirm` doesn't store BYPASSED with Guard.approved, or a vague skipped confirm would cover
  the next "Place order" tap (takeApproval).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - One predicate `Bypass.secretOrMoney(text)` in a new fork file; sites add only structured money facts
- Options: put it in Guard (upstream) or split Guard.RISKY; a new fork file.
- Choice: `Bypass.java` (MODE, applies, on, secretOrMoney): word-start matching like Guard.routineText over English
  secret words (no bare "code"/"key") and money words in English plus RISKY/CHECKOUT's pay/buy/order/cart/transfer words
  in Buddy's languages, currency signs and `$` + digit. Sites add what text can't show: `Guard.moneyApp(frontPkg())` for
  taps/Enter/confirm, `payForm` and the URL for the background browser. Guard.java and RISKY untouched (sync surface).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - In Bypass, settings card for keys, for setting a chat to Bypass, and for switching money checks off
- Options: card only the 011 key routes and `approvals=bypass`; also card the three settings that disarm money cards.
- Choice: also card `app/<pkg>=trust` (Trust skips Pay/Buy in that app), `button/*/<money label>=allow`, and
  `builtin_risky_words=off`; otherwise one silent change turns money cards off for every chat. Everything else (permissions
  other than trust, safety, Linux tools, MCP, skills, schedules, agents and models) goes through, per the brief.
  `default_agent`/`default_model/*` don't card (not paying or buying in the brief's sense); shown to Nicholas in the PR.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The Smart reviewer keeps running in Bypass; Trust stays Trust; the clipboard keeps its card
- Options: skip the reviewer in Bypass (saves ~3 s a tap); keep it and let the predicate read its reason.
- Choice: keep it: its ask/block reason is on the card text, so a checkout behind "Continue" still cards while every
  other reviewer card is skipped. Trust apps were never carded in Ask, so Bypass adds nothing there. The clipboard is
  where copied passwords and codes sit, so reading it is "reading secrets": not opted in (one-line flip; in the PR).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Revert 019 with `git revert` of its three code commits
- Options: hand-remove 019's code; `git revert --no-commit 6883d32 19b0017 5efdc2d`.
- Choice: the revert: it restores SettingsTool, AGENTS.md and GUIDE.md exactly and deletes SettingsRoutineTest, so
  "Routine allowed behaves as upstream" is a diff against 2e6624d. BuildInfo and 019's docs stay.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Fork strings are English-only; Bypass stands out with the existing accent
- Options: translate the three new strings into 17 locales; English-only like the fork's other strings; a warning colour.
- Choice: English-only (precedent: tools_card_setting/update/shell exist only in values/), and paintApprovals' accent,
  which already marks every mode other than Ask.
- By: Planner (claude-opus-5-5)
