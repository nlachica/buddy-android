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

## 2026-11-17 - Bypass's money floor is Ask's cards; the agent can't loosen them silently (plan review F1)
- Options: Bypass's own money check overrides Trust, never-ask buttons and the user's Safety switches at every action
  boundary; or Bypass shows every money/secret card Ask would show with the same settings, and `keepsCard` cards every
  setting that loosens one.
- Choice: the second. The brief keeps the money cards that exist ("still card"), and doesn't ask the no-cards mode to
  card more than Ask in apps the user trusts. `keepsCard` grows to: every `button/*/*=allow` (any label: "Send" in a
  bank app), `chat/<id>/allowed/<pkg>=on` (the run rule notes before any card), and every `chat/<id>/approvals`
  change (Routine's run rule skips the predicate, so leaving to Routine must card), plus Trust, `builtin_risky_words=off`
  and keys. `phone_commands_ask` is already read-only to agents; the on-screen switches are in protected App permissions.
  Supersedes the earlier "settings card for keys, Bypass, and switching money checks off" entry.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The predicate gains finishing words, key-file names and a bare digit code; the browser reads more context
- Options: keep the word list as planned; add what review F1/F2/F4/F5 showed Ask cards but the list missed.
- Choice: add confirm/submit and RISKY's translations (Ask always cards them; on a checkout they are the pay step),
  credential file names and key extensions (real shell reads name files, not "private key"), and a 4-8 digit text (a
  copied code). The browser adds `BrowserTools.money(o, url)` (payForm; URL, form, title, label, ids, submitLabel) and
  `Guard.moneyApp(svc, host)` (the app name test on the site). Still one predicate, `Bypass.secretOrMoney(String...)`.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - A Bypass chat judges each risky browser dialog on its own full text (plan review F3)
- Options: carry approval provenance (user vs bypass) from `approve` to `pendingDialog`; ignore the inherited flag in
  a Bypass chat.
- Choice: ignore it in Bypass, via a pure `dialogAsks(approved, bypass, msg)`, and pass `!secretOrMoney(fullMessage)`
  (before the 300-char clip). One line, no new state; the cost is a second card on a money dialog after a user-approved
  Pay. Other modes unchanged.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The clipboard is read first in Bypass and cards only when it holds a secret (plan review F5)
- Options: keep the clipboard card in Bypass (a non-goal the brief didn't grant); read first, card on the clip's
  sensitive flag or the predicate.
- Choice: read first. `ClipDescription.EXTRA_IS_SENSITIVE` (password managers, Android 13+; read by its literal key,
  minSdk 30) or `secretOrMoney(text)` → card before the agent sees it; else skipped and noted. Supersedes the clipboard
  half of "the clipboard keeps its card".
- By: Planner (claude-opus-5-5)

## 2026-11-17 - phone_shell's secret check reads the command line only; Nicholas asked (Q1)
- Options: skip with the command-line check (brief done-when 2); keep phone_shell's card in Bypass.
- Choice: the check, as written, with its limit stated (scripts and encoded commands go through). Q1 in plan.md puts it
  to Nicholas, as review F4 asked; B is a one-line flip.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - No F-Droid gate for Bypass (plan review F7 rejected)
- Options: gate the sheet row and `Bypass.on` with `Flavor.FDROID`; leave it ungated.
- Choice: ungated. The brief makes F-Droid a non-goal; the fork ships the github flavor only and F-Droid builds
  upstream's repo; fork features (001, 016) already build ungated in the fork's F-Droid flavor.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Install bypass is verified with a fork.5 release, inside this feature (plan review F6)
- Options: leave the install line pending until a later release; publish a version-only fork.5 after fork.4.
- Choice: fork.5, installed from a Bypass chat; `verify.md` stays incomplete until it's seen. The settings decision
  test (T4) runs `change` through `Bypass.skips(!keepsCard(...))`, the static `askConfirm` calls, not a fixed answer.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The 019 revert keeps 019's decisions.md (plan review F8)
- Choice: `git checkout HEAD -- docs/features/019-settings-no-card-routine/decisions.md` after the revert, checked in
  the staged stat. 6883d32 had appended 17 lines there.
- By: Planner (claude-opus-5-5)
