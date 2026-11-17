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

## 2026-11-17 - Bypass's limits go to Nicholas as a blocking Q1; no Ask-equivalence claim (plan review round 2, F1)
- Options: keep claiming "every secret and money card Ask would show" while listing counterexamples; state what Bypass
  recognises and put each limit to Nicholas before coding.
- Choice: the second. Q1 asks four things, each with a recommended answer and a one-line alternative: (a) phone_shell's
  command-line check, (b) money steps with no money word or name, (c) the clipboard, (d) Safety settings carding although
  the brief's goal lists "safety" as no-card. The orchestrator records his answers in the brief's Clarifications, and
  done-when 3 is judged against them. Supersedes the framing of "phone_shell's secret check reads the command line
  only; Nicholas asked (Q1)".
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The clipboard keeps its card in Bypass (plan review round 2, F1)
- Options: read first and card on the sensitive flag or the predicate (round 1); keep the card, as in Ask.
- Choice: keep the card (no opt-in, no code; the PhoneToolActivity change is gone). An unflagged copied password would
  reach the agent before anything could judge it. Both options need Nicholas (Q1c), and this one keeps Ask's
  protection. Supersedes "The clipboard is read first in Bypass and cards only when it holds a secret".
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Each Bypass check reads its site's own risk inputs, unclipped (plan review round 2, F2-F4)
- Options: judge the card's summary text; pass each site's structured evidence in.
- Choice: pass it in, computed where the risk check runs. Native taps: `Bypass.tapMoney(moneyApp(pkg), target, hit)`,
  which runs `Guard.riskyLabel` on target and hit (text, description, tooltip, id), into a new `guardAction` `money`
  argument. Browser: `approve` checks the full draft (`browser.js` stops clipping it), and `pendingDialog` gets the
  opener's `money` plus `moneySite(key)` (host name test and URL) for the page the dialog is on. Native Enter on a web
  page or in a browser app keeps its card, upstream's own reason for Routine allowed (`Tools.java:1965-1966`).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The user's always-ask buttons keep their card in Bypass
- Options: skip them like any card the predicate passes; keep them.
- Choice: keep them (`userAsks` in `guard`, the ask-list check in the browser tap, ored into `money`). They are the
  user's own rule, like Trust and never-ask, which round 1 kept as set. They are also how Nicholas marks a money step
  Buddy can't recognise ("Send" in a bank app without a money name: Q1b).
- By: Planner (claude-opus-5-5)

## 2026-11-17 - In Bypass, settings card by name: keys, approvals, Safety settings, allowed apps (plan review round 2, F5, F6)
- Options: card by value (Trust, allow, on, off, plus round 2's Hard/Default and rule removals), which needs the
  setters' normalisation; card every change of those names, whatever the value.
- Choice: by name, read from the resolved `s.name`: `app/*`, `button/*`, `builtin_risky_words`, `chat/*/allowed/*`,
  `chat/*/approvals`, and the key routes. There is no value to normalise, and no table of which transitions loosen.
  Tightening changes card too, which is rare and the safe side. `reviewer` needs no entry (same, claude or codex: it
  can't be turned off). Supersedes the value rules in "Bypass's money floor is Ask's cards; the agent can't loosen them
  silently".
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Q1 answered: all four recommendations accepted
- Options: per Q1 in plan.md, each with a one-line alternative.
- Choice: (a) phone_shell skips its card unless the command line names a secret, a key file or money; (b) a money step
  with no money word or name goes through (an always-ask rule marks one); (c) clipboard_read keeps its card; (d) per-app
  modes, button rules, built-in words and allowed apps keep their card. Recorded in the brief's Clarifications.
  Done-when 3 is judged against these.
- By: Nicholas (recorded by Planner, claude-opus-5-5)

## 2026-11-17 - `tapMoney` reads every short label in `riskyLabel`'s bounded walk (plan review round 3 F2, round 4 F1)
- Options: `secretOrMoney(Guard.riskyLabel(target), riskyLabel(hit))`, which stops at the first risky field ("Send"
  before a "Pay now" description); every text of any length (a long "orders over $25" text would card a plain Send);
  every short label (text, description, tooltip at 30 chars and 4 words or fewer, and the view id) on the same nodes
  riskyLabel visits.
- Choice: the last. A ~15-line copy of the walk in `Bypass.java`, since `Guard.risky` is private and Guard is upstream's.
  It only decides whether a card already showing may be skipped, so it adds no card to a tap Ask lets through.
  Supersedes the `tapMoney` definition in "Each Bypass check reads its site's own risk inputs, unclipped".
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The full outgoing draft goes into the caller's `money`, before the reviewer (round 4 F3, F4)
- Options: check the draft from the card text (loses it in the no-reviewer fallback, and Enter's card shows only the
  appended text); show the full draft on the card (changes Ask's card); or each caller ors `secretOrMoney(full draft)`
  into `money`.
- Choice: `money`. Native tap: the focused field's text; native Enter: `value` (the field's whole text after an
  append); browser tap: the target's `draft`, unclipped; browser Enter: `__buddy.draft()` read after typing. `review`
  passes `money` to both `guardAction` calls, the no-reviewer fallback included. Card text unchanged in every mode.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - `__buddy.focus` returns the form facts `__buddy.target` already computes (round 4 F2)
- Options: a second `__buddy.target` call before Enter; return `payForm` and `form` from `B.focus` too.
- Choice: return them, from two small functions `payForm(form)` and `formName(form)` moved out of `B.target` (now two
  users). `B.draft(not)` is moved out the same way, with no clip. T11 checks the producer's source for those keys, as
  there is no JS test harness; the phone check covers a real checkout form.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Coder: small choices the plan left open
- `guardAction`'s 5- and 6-argument overloads became one 7-argument method (`otherRisk`, `money`): the only callers are
  `guard`, `review` (twice) and Enter, all updated, and two overloads differing only by trailing booleans would be ambiguous.
- `pendingDialog` tests the clipped 300-character message outside Bypass (Ask/Routine/Always ask unchanged) and the whole
  message in a Bypass chat (`bypass ? full : msg` into `dialogAsks`), so a risky word past character 300 is judged there.
- Browser `click` computes `money` before the ask branch (it also feeds `pendingDialog`); `type` reads `__buddy.draft()` once,
  after the `submit` early return, so a type without submit costs no extra eval.
- `approve` and `guardAction` judge `secretOrMoney(summary)` (card line plus quoted draft/reviewer reason) as well as the
  caller's `money`, a superset of the plan's `card` / reviewer-reason inputs.
- T4/T5/T6 live in `ChatApprovalsSettingTest` (they need its `row`/`edit` helpers); T1-T3, T7-T11 in `BypassTest`.
- Local runs of `BypassTest` need an `android.util.Log` shim (the real `AccessibilityNodeInfo` static init calls
  `Log.isLoggable`); Gradle's mockable android.jar returns defaults, so CI needs none. Added to `/tmp/buddy-build-020` only.

## 2026-11-17 - Coder: round 1 review fixes
- Checkout evidence for a form or an address reuses `BrowserTools.CHECKOUT` (the pattern `riskyTap` uses), so Bypass never skips the card of a checkout that Ask cards. The address check is the small static `moneyUrl` so it can be tested without a service.
- The money words in `Bypass.MONEY` are checked against all of `Guard.RISKY`; a missing money term is added there, with a table test for the money subset. Send/delete terms stay out.
