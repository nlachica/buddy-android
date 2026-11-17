# Plan review: 020-bypass-approvals
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] The money exception is downstream of paths that never ask - The brief requires money and banking/payment apps to remain carded, but the plan explicitly preserves Trust and checks money only when an existing card reaches `askConfirm` (`plan.md:91-112`). `Tools.java:502-505` approves every confirm in a trusted app before reading its summary; `Tools.java:1751-1755` returns before checking a trusted app or an allowed button; `Tools.java:1761-1774` does not require a card just because the app handles money. This also creates a concrete silent settings escape: `button/<bank-package>/Send=allow` passes the proposed `keepsCard` because “Send” is not a money word, then the bank's Send bypasses `guardAction` entirely. Existing `builtin_risky_words=off` and `phone_commands_ask=off` similarly avoid the proposed gates (`BrowserTools.java:248`, `Tools.java:667`). Carding changes to three settings does not protect against existing values or the unprotected Send rule. -> Make the Bypass secret/money requirement take precedence over Trust, button allow lists and disabled asking at the relevant action boundaries, while preserving other modes. Test existing permissive settings and the bank-Send setting sequence. Resolve the admitted Hard-mode/website money gaps (`plan.md:261-265`) against the brief rather than treating them as fulfilled acceptance criteria.

F2 [major] Browser Enter drops financial context it already knows - The planned Enter classifier uses only the URL and the rendered summary (`plan.md:95`). `BrowserTools.type` knows `submitLabel`, uses it to decide that the action is risky, then builds a card containing only “Press Enter”, the host and the typed text (`BrowserTools.java:338-342`). On `https://shop.example/session`, a non-sensitive delivery-note field whose submit button says “Pay” therefore cards today but would bypass with the proposed logic: neither the URL nor the card contains “Pay”. The tap path also derives risk from a button title (`BrowserTools.java:300-301`) which may be absent from its displayed label. -> Pass the actual submit label and the available form/payment context into classification before presentation or clipping; preserve risky title context for taps too. Add an Enter-on-Pay test with a neutral URL and neutral input, plus a title-only payment button case.

F3 [major] A bypassed browser step can approve a later money dialog - Opting in the dialog's `askConfirm` call (`plan.md:96`) does not cover the preceding shortcut. After `approve` returns null, `click` sets `approved=true` (`BrowserTools.java:250-260`); `type` passes `risky` as the approval flag (`:347`). `pendingDialog` then accepts any confirm dialog when that flag is true, before inspecting its risk (`:503-506`). Thus a bypassed “Send” or user-listed “Continue” can open “Pay $20?” and receive OK with no money card. This is the same approval-provenance problem the plan correctly fixes for `Guard.approved`, in a second location. -> Preserve whether approval was a user decision or a bypass, and ensure a secret/money dialog cannot inherit a bypassed step's approval. Classify the full dialog message before its 300-character display truncation (`:479`). Test a bypassed click followed by a money dialog, and denial preventing acceptance.

F4 [major] The shell secret test adds words that the real command need not contain - The brief explicitly retains approval for reading secrets, but `phone_shell` skips based solely on the proposed vocabulary (`plan.md:67-70,93`). T1 tests `cat ~/.ssh/id_rsa private key` (`plan.md:213`), where the added “private key” supplies the match. An actual `cat /sdcard/id_rsa` or `cat /sdcard/.env` contains none of the listed secret terms; `Tools.java:659-679` would run it and return its contents after the proposed skip. -> Cover recognizable credential files and secret-reading commands using the actual command, without explanatory words in fixtures. State how opaque commands/scripts are handled; do not claim arbitrary secret reads are protected by a word-list test. If the intended guarantee must be narrower, send that discrepancy to the orchestrator for a brief clarification before approval.

F5 [major] The plan adds an unconditional clipboard exception to “only secrets and money” - `plan.md:29,99,278-281` excludes every clipboard read and postpones that choice to the PR, although the brief's goal says no cards except secrets and money. `PhoneTools.java:140-147` cards before reading and has no content-sensitive distinction, so an empty clipboard or an ordinary copied URL still cards. The safety rationale explains the choice but does not establish authorization to narrow the goal; the new UI text also omits this exception. -> Either provide a way to withhold sensitive clipboard contents until approval while allowing ordinary content, or obtain an explicit brief clarification through the orchestrator and make the UI and acceptance tests match it. Do not silently add this non-goal.

F6 [major] The verification plan cannot demonstrate the required behavior - T1-T6 exercise vocabulary, mode predicates and a fake asker that already returns `BYPASSED`; none exercises the real card-versus-execute decisions (`plan.md:213-231`). This would miss F1-F3. The phone check omits Smart-reviewer behavior and leaves update-install bypass pending until a future fork.5 release (`plan.md:240-253`), although installs and reviewer behavior are explicit done-when requirements. -> Add focused executable coverage of the actual decision paths, including card denial preventing the action and regression cases from this review, using a small test seam or Android tests as appropriate. Arrange an update candidate/controlled update test and Smart-reviewer phone check within this feature's verification. Keep verification incomplete until the required evidence exists rather than making a later release part of the completion plan.

F7 [major] The proposed implementation also enables Bypass in F-Droid - The brief limits the feature to the GitHub flavor, but the new UI row, settings factory and `Bypass.on` are unconditional changes under `src/main` (`plan.md:56-59,137-150`). Both flavors compile those sources (`app/build.gradle.kts:42-47`); hiding `buddy_settings` in F-Droid (`Tools.java:325,561`) does not hide the chat row or stop the common approval gate. -> Gate exposure and runtime activation with the existing `Flavor.FDROID` mechanism. Verify that F-Droid has the original three modes and cannot activate Bypass even if a stored session contains `"bypass"`; retain GitHub coverage for all four modes.

F8 [minor] The revert command also removes 019's decision history - `plan.md:153-159,181-184` says the three whole-commit reverts leave 019's docs untouched. `git show --stat 6883d32` shows that commit also appended 17 lines to `docs/features/019-settings-no-card-routine/decisions.md`, so reverting it removes those decisions. -> Explicitly preserve/restore that document before committing the revert, or reverse only the implementation, tests and user-facing documentation. Check the staged diff excludes feature 019's history.

VERDICT: REVISE

### Planner response (round 1)

F1: partly accepted.
- Accepted: the agent can no longer silently loosen a check that money cards depend on. In a Bypass chat, `keepsCard`
  now cards every `button/<scope>/<label>=allow` (any label, so `button/<bank>/Send=allow` cards) and
  `chat/<id>/allowed/<pkg>=on` (the run-rule path notes a step before any card is considered, so it would get around
  the predicate). It also cards every `chat/<id>/approvals` change, not only one to `bypass`. These come on top of
  `app/<pkg>=trust`, `builtin_risky_words=off` and the key routes. `phone_commands_ask` is already read-only to agents
  (`SettingsTool.java:169-175`). The same switches on screen sit in App permissions, which agents can't tap
  (`AppPermissionsActivity.java:72`, `protect`). T4 and T5 test the bank-Send sequence.
- Accepted: closing the gaps where Ask cards but Bypass wouldn't. The predicate gains the words that finish a checkout
  (confirm, submit, and `Guard.RISKY`'s translations of them). The background browser also checks the site's host
  against the money-name test (`Guard.moneyApp`) and the button's title, ids and submit label (F2).
- Rejected: Bypass overriding Trust, the never-ask list or switches the user already set. The brief says money "still
  card[s]": the money cards Ask shows must stay. It doesn't ask for cards in apps the user trusts. That would make the
  no-cards mode card more than Ask, against "no restrictions". In Ask today, a confirm in a Trust app is approved
  (`Tools.java:502`), and Trust and never-ask taps never card (`:1751-1755`). That is the user's App permissions
  choice, and it applies in every mode. The invariant is now stated in Design: Bypass shows every secret and money card
  that Ask would show with the same settings, and the agent can't change those settings without a card.
- The "admitted gaps", restated:
  - A plain "Continue" in a shop app on Hard doesn't card in Ask either. It isn't a risky label, and only Smart
    reviews (`Tools.java:1761-1776`). So it isn't a Bypass gap, and it's gone from Risks.
  - What remains, in Risks for Nicholas: a step Ask cards, whose card names no money word, in an app or site whose
    name isn't a money name ("Send" in a bank app called "Chase"). Upstream's Routine allowed has the same limit
    (`Guard.sends`, `moneyApp`).

F2: accepted. A new pure `BrowserTools.money(JSONObject o, String url)` returns true for `payForm`, or when the
predicate matches the URL or the item's `form`, `title`, `label`, `ids` or `submitLabel`. The tap passes its target
JSON and Enter passes its focus JSON, both before any clipping, plus `Guard.moneyApp(svc, host)`. T7 covers Enter on a
"Pay" submit with a neutral URL and neutral text, and a button whose only payment word is in its title.

F3: accepted. In a Bypass chat, `pendingDialog` ignores the inherited `approved` flag for a risky dialog. The dialog is
judged on its own full message (before the 300-character clip), through
`askConfirm(card, false, !Bypass.secretOrMoney(fullMessage))`. The decision is a pure
`dialogAsks(approved, bypass, msg)`, tested in T8: a bypassed "Send" followed by "Pay $20?" cards. A denial still
answers Cancel (`BrowserTools.java:507`, unchanged). One cost: after a Pay the user approved on a card in a Bypass
chat, a money dialog cards a second time. That is the safe side.

F4: accepted, with the limit stated.
- The predicate gains credential file names: `id_rsa`, `id_dsa`, `id_ecdsa`, `id_ed25519`, `.ssh/`, `.env`, `.netrc`,
  `.pgpass`, `.git-credentials`, `.npmrc`, `.pypirc`, and the extensions `.pem`, `.key`, `.p12`, `.pfx`, `.jks`,
  `.keystore`, `.kdbx`, `.gpg`.
- T1's fixtures are now the bare commands (`cat /sdcard/id_rsa`, `cat /sdcard/Download/.env`, …), with no explaining
  words added.
- The plan no longer claims that a word list protects shell reads of secrets. `phone_shell`'s check reads the command
  line only, so a script or an encoded command goes through in Bypass (Design, Risks).
- Sent to the orchestrator as Q1 for Nicholas: skip phone_shell's card with this check (done-when 2; recommended), or
  keep its card in Bypass (a one-line flip).

F5: accepted, the first option.
- In a Bypass chat, `clipboard_read` reads the clipboard first. It then cards, before the agent sees anything, when:
  - the copying app marked the clip sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`, which password managers set on
    Android 13+); or
  - the text matches the predicate, which now also counts a bare 4-8 digit code.
- Otherwise the card is skipped and the step noted. Other modes are unchanged (card first, then read).
- Removed from the non-goals and the open questions. The phone check covers both cases.

F6: accepted, within the JVM-only test setup.
- (a) T4 now drives the real settings decision. It runs `SettingsTool.change` with an asker built from
  `Bypass.skips(!keepsCard(…), mode, ruleRun)`, the same static `askConfirm` calls. It covers Bypass, Routine, Ask,
  Always ask and a rule's run, and it checks:
  - zero cards and one write for `audio_device` in Bypass;
  - one card and no write on denial for a key, `approvals=bypass`, and `button/<bank>/Send=allow`;
  - a card for every change in the other modes.
- (b) The browser decisions are pure statics (T7, T8).
- (c) The tap, confirm, shell and clipboard opt-ins are one expression each inside Android classes (JUnit only, no
  Robolectric: `app/build.gradle.kts:83-92`). Code review checks them against the table, and the phone check confirms.
- The phone check gains Smart-reviewer steps, the clipboard, and the bank-Send sequence.
- The update install is now part of this feature's verification: after fork.4, publish `2.3.11-fork.5` (version
  only) and install it from a Bypass chat. `verify.md` stays incomplete until then.

F7: rejected.
- The brief lists the F-Droid flavor as a non-goal, meaning no F-Droid work. The fork ships only the github flavor
  (AGENTS.md "Updating Buddy on the phone": "github flavor only"), and F-Droid builds Buddy from upstream's repo,
  which never gets this code.
- Fork features already sit ungated in the fork's F-Droid build: 001's audio device, and 016 ("F-Droid: same minus
  Watch").
- A flavor gate would be F-Droid work with no user. Recorded in decisions.

F8: accepted. Step 1 restores 019's `decisions.md` after the revert
(`git checkout HEAD -- docs/features/019-settings-no-card-routine/decisions.md`). It then checks that
`git diff --cached --stat` lists no `docs/features/019-*` path.
