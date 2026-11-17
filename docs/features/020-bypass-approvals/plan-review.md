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

## Round 2 - Plan Reviewer (gpt-6-astra)

F1 [major] The required policy clarification is still unanswered - Round 1 F4 required a brief clarification before approving a narrower secret guarantee. `plan.md:398-402` now asks Q1, but neither the brief nor decisions records Nicholas's answer; a Planner-authored decision headed "Nicholas asked" is not that answer. Moreover, `plan.md:372-379` admits that an opaque shell command, a bank's Send, and an unflagged copied password can bypass cards that Ask would show, contradicting the stated invariant at `plan.md:26-27,382-383` and done-when 3 (`brief.md:27`). The clipboard currently asks before exposing any content (`app/src/main/java/app/buddy/assistant/PhoneTools.java:140-147`), so that limitation is also a real reduction in protection. -> Keep this acceptance question open for the orchestrator: obtain and record Nicholas's agreement to the concrete heuristic limits (including clipboard and unrecognized banking apps, not just shell scripts), or revise those paths to retain the required cards. Do not describe the Ask-equivalence invariant or done-when 3 as satisfied while expressly allowing these counterexamples. This does not reopen the decision to preserve existing user-set Trust/never-ask choices.

F2 [major] Native taps discard the money evidence that made them risky - The proposed phone opt-in reads only `frontPkg()` and the rendered summary (`plan.md:130`). But `Tools.guard` builds that summary from `Guard.buttonLabel(target)` while deciding to ask from `Guard.riskyLabel(target)` **or the hit node** (`app/src/main/java/app/buddy/assistant/Tools.java:1752-1772`). Those labels differ: `Guard.shortLabel` prefers visible text, whereas `Guard.risky` checks content description, tooltip and resource ID too (`app/src/main/java/app/buddy/assistant/Guard.java:260-265,707-718`). A shopping button with text "Continue" and content description "Pay now", in an app without a money-name match, cards in Ask but would bypass: its card says only "Tap Continue in Shop". A money label on the actual hit node can be lost the same way. -> Carry the already-detected risky labels from both target and hit into the bypass decision before formatting; retain the summary for presentation. Add a regression for visible Continue / accessible Pay now and for money evidence present only on the hit node. This is the native equivalent of the browser context loss fixed after round 1.

F3 [major] Browser secret classification still runs after draft truncation - `plan.md:134,154-157` adds structured button/form context, but `approve` still decides the secret exception from `summary`. That summary clips the draft to 300 characters (`app/src/main/java/app/buddy/assistant/BrowserTools.java:463-465`), and the click path already clips it in JavaScript (`app/src/main/assets/browser.js:319-322`); `BrowserTools.money` does not inspect the draft. A Send/Enter of 301 harmless characters followed by "password: ..." therefore cards in Ask but bypasses in this plan, despite containing a recognized secret word. -> Classify the complete outgoing draft before display clipping, including the JavaScript extraction on clicks; pass the resulting sensitivity into `approve`. Add click and Enter cases with the only secret marker beyond character 300, checking that denial prevents submission. Keep display limits unchanged.

F4 [major] Browser dialogs lose the money-site context used for taps - The revised dialog rule consults only `fullMessage` (`plan.md:135,149-153`), unlike the click/Enter gate which also checks URL and host. On `https://bank.example/checkout`, an ordinary non-risky opener followed by a JavaScript confirm "Send now?" has `approved=false` and matches `RISKY_DIALOG`, so Ask shows a card (`app/src/main/java/app/buddy/assistant/BrowserTools.java:28-29,503-508`). In Bypass, the proposed dialog predicate finds no money word in "Send now?" and silently accepts it, even though the host and URL explicitly identify money. -> Include the current site's money context in the dialog opt-in as well as its full message. Test this unapproved opener followed by a neutral-worded dialog on a money site, with denial answering Cancel; retain the existing harmless-dialog bypass test on a non-money site.

F5 [major] Two allowed settings changes still silently remove money checks - The plan promises the agent cannot loosen checks without a card, but explicitly permits `app/<pkg>=hard/default` and button-rule removal (`plan.md:185-198,313`). A Smart shop app's Continue can be carded because the reviewer recognizes checkout; silently setting that app to Hard eliminates the reviewer (`app/src/main/java/app/buddy/assistant/Tools.java:1774-1776,1800-1808`). Likewise, removing an existing always-ask rule for Continue in a Hard shop app removes the sole reason that action asks (`Tools.java:1755,1760-1774`; `app/src/main/java/app/buddy/assistant/SettingsTool.java:569-588`). These are new agent-made relaxations, not the preserved existing user choices discussed in round 1. -> Cover transitions that remove a protection: Smart to Hard (including Default when it resolves to Hard), and removal of an existing always-ask button rule. The simpler conservative option is to card Hard/Default app writes and button-rule removals in Bypass, rather than add a context-dependent policy. Test both change-then-action sequences and denial preserving the old protection.

F6 [major] Settings classification must use the same value normalization as the write - The proposed asker captures raw `a.optString("value")` (`plan.md:200`) and defines value-dependent exceptions for `trust`, `allow`, `on` and `off`, without specifying normalization. Actual settings accept trimmed, case-insensitive values: `Scalar.check`, `app.check` and `button.check` canonicalize before writing (`app/src/main/java/app/buddy/assistant/SettingsTool.java:134-139,475-485,581-588`), and `change` uses that checked value (`:1472`). Implementing the stated comparisons literally would let `app/<pkg>=" TRUST "`, `button/*/Pay=" ALLOW "` or `builtin_risky_words=" OFF "` bypass the exception and still write the unsafe value. T5 only varies capitalization for approvals, whose exception is name-only. -> Specify matching trim/case normalization inside `keepsCard` (or feed it the canonical checked value) and test mixed-case/whitespace variants for every value-dependent exception through the settings gate, including denial yielding zero writes.

VERDICT: REVISE

### Planner response (round 2)

F1: accepted.
- The plan no longer claims Bypass shows every card Ask would. Goal now says what Bypass promises: the secret-and-money
  check reads every input of the step's own risk check, unclipped, and the agent can't change approvals modes, Safety
  settings or allowed apps without a card. It also says what Bypass can't promise.
- Q1 is now blocking: the plan's first lines say so. It asks Nicholas four things, each with a recommendation and a
  one-line alternative:
  - (a) phone_shell's command-line check;
  - (b) money steps with no money word or name ("Send" in "Chase");
  - (c) the clipboard;
  - (d) Safety settings carding although the brief's goal lists "safety" as no-card.
- The orchestrator records his answers in the brief's Clarifications. Done-when 3 is judged against them (test plan,
  done-when 3).
- Clipboard: back to keeping its card in Bypass. Not opted in, so no code: the read-first path and the
  PhoneToolActivity change are gone. Round 1 F5 allowed this branch once Nicholas confirms it (Q1c). It keeps Ask's
  protection, where read-first let an unflagged password through.
- Narrowing (b): the user's always-ask buttons now keep their card in Bypass (`userAsks`, `Tools.java:1760`; the
  browser tap's ask-list check, `BrowserTools.java:248`). So Nicholas can mark a money step Buddy can't recognise. The
  limit stays in Q1b and Risks.
- The earlier user-set Trust and never-ask choices are unchanged.

F2: accepted.
- `guard` computes `Bypass.tapMoney(Guard.moneyApp(svc, pkg), target, hit) || userAsks` once, before formatting.
  `tapMoney` is `moneyApp || secretOrMoney(Guard.riskyLabel(target), Guard.riskyLabel(hit))`: the labels the ask
  decision read (`Tools.java:1761-1762`), from text, description, tooltip and id.
- It reaches `guardAction` through a new `money` argument, directly and through `review`. The summary is still used
  for the card.
- Native Enter is the same kind of gap: its risk check knows `web` and `Guard.browser`. It passes `moneyApp(pkg) ||
  web || browser`, upstream's own reason for keeping Routine allowed off that Enter (`:1965-1966`).
- T9 (fake `AccessibilityNodeInfo`; `isReturnDefaultValues` is on):
  - visible "Continue" with description "Pay now": `tapMoney` is true, while the card text "Tap “Continue” in Shop"
    is not money;
  - a plain target over a hit described "Pay": true;
  - plain targets: false.

F3: accepted.
- `browser.js:321` stops clipping `draft`. `approve` still clips it to 300 for the card (`BrowserTools.java:465`).
- `approve`'s opt-in reads `secretOrMoney(card, draft)` on the full draft, not the summary. Enter passes the typed
  `text`, which was never clipped.
- T10: a secret or a "Pay $20" past character 300 is caught, and clipped it isn't.
- Denial stopping the click or Enter is upstream's unchanged `if (blocked != null) return` (`:250-252`, `:341-342`).
  BrowserTools has no JVM harness, so this is a code-review item in the test plan.

F4: accepted.
- `pendingDialog(key, approved, money)` cards in Bypass when any of these holds:
  - the opener's `money`;
  - `moneySite(key)`, read on the page the dialog is on: `Guard.moneyApp(svc, host)` or `secretOrMoney(url)`;
  - the full message names a secret or money.
- The open and back callers (`:128`, `:172`) pass `false` and still get the site test.
- T8 adds `bank.example/checkout` with "Send now?" and an unapproved opener: it cards. The harmless dialog on a
  non-money site stays skipped. Cancel on denial is the unchanged `:507`.

F5: accepted, simpler than suggested.
- `keepsCard` goes by name only: every change of these cards in Bypass, whatever the value:
  - `app/*`, `button/*` and `builtin_risky_words`;
  - `chat/*/allowed/*` and `chat/*/approvals`;
  - the key routes.
- That covers Smart → Hard or Default and rule removals, with no table of which transitions loosen. Tightening
  changes card too: rare in Bypass, and the safe side.
- `reviewer` needs no entry: it takes only `same`, `claude` or `codex` (`SettingsTool.java:312`), so it can't turn
  the reviewer off.
- T4 runs both sequences: removing an existing ask rule, and `builtin_risky_words=off`. Each gets one card, and
  denial gives zero writes, so the old protection stays.
- The brief's goal names "safety" as no-card, so this goes to Nicholas as Q1d.

F6: accepted; mostly moot after F5.
- `keepsCard` reads no value. It takes `s.name`, the canonical name of the setting `find` resolved, not the raw
  argument.
- T4 still runs mixed-case and padded values through the real gate: `button/*/Pay=" ALLOW "`,
  `builtin_risky_words=" OFF "` and `chat/c1/approvals=" Bypass "`. Each gives one card, and denial gives zero writes.

## Round 3 - Plan Reviewer (gpt-6-astra)

F1 [major] Q1 remains an unresolved acceptance gate - The revised plan correctly makes Q1 blocking (`plan.md:4-5,447-464`), but neither `brief.md:31-32` nor the latest decisions records Nicholas's answers. The proposed shell and unnamed-money exceptions still narrow the secrets/money requirement, while unconditional clipboard and Safety-setting cards narrow the no-card requirement (`brief.md:14-18,26-27`). Explicitly identifying the disagreement does not resolve Round 2 F1 or authorize those changes. -> The orchestrator must obtain and record Nicholas's answers and align the plan, UI promises and acceptance tests with them before coding. Keep this finding open until that happens; this is the existing acceptance dispute, not a request to reopen Trust, never-ask or F-Droid decisions.

F2 [major] The native label fix still discards explicit money evidence - `tapMoney` uses only the first `Guard.riskyLabel` result (`plan.md:165-170`). `Guard.risky` returns immediately on the first matching text/description/tooltip (`app/src/main/java/app/buddy/assistant/Guard.java:707-718`), and `riskyLabel` likewise stops at its first match (`:666-680`). For a Hard-mode shop button with text "Send" and description "Pay now", the result is "Send", the displayed label is "Send", and the proposed bypass check finds no money. Ask cards this tap (`Tools.java:1761-1772`). The plan acknowledges this at `plan.md:424-426`, but puts it under absent money words even though the node explicitly contains "Pay"; Q1b only describes labels with no money word. The rejection at `plan.md:299-300` does not justify losing this short, explicit button description: classification can remain inside the existing card path, so it need not add cards to plain taps Ask allows. -> Check the relevant label fields for money/secret evidence without stopping at the first non-money risky label, retaining the existing bounded node scope and approval entry points. Add a Send-text/Pay-description regression (and equivalent tooltip/ID coverage), not just Continue/Pay.

F3 [major] Browser Enter's proposed form check reads fields its producer never returns - The plan passes Enter's focus JSON to `BrowserTools.money`, expecting `payForm`, `form`, `title` and `ids` as well as labels (`plan.md:143,178-181`). Actual `__buddy.focus` returns only `ok`, `label`, `multiline`, `search` and `submitLabel` (`app/src/main/assets/browser.js:392-404`); the richer payment/form data exists only in `__buddy.target` (`:303-315`). On a neutral page URL, a normal multiline field in a form whose action is `/checkout`, with a "Send" submit button, therefore cards in Ask (`BrowserTools.java:338-342`) but bypasses the planned check: the form evidence is absent and every supplied string is neutral. T7's hand-built JSON does not expose this producer/consumer mismatch. -> Return the required form/payment metadata from the focus path too, or obtain it before the Enter decision using the existing target extraction. Test the actual focus-result shape for a neutral-URL form with checkout/payment context and a neutral submit label; denial must prevent Enter.

F4 [major] Enter checks the appended fragment rather than the full outgoing draft - The plan explicitly calls Enter's `text` the complete draft (`plan.md:184-186`), but both native and browser typing support append. Native `typeText` constructs the combined `value` at `app/src/main/java/app/buddy/assistant/Tools.java:1920-1922`, then passes only `text` to the Hard/Smart approval paths (`:1970-1972`). The browser also passes only the newly supplied `text` after appending (`BrowserTools.java:315-316,332-342`; `browser.js:380-388`). With an ordinary message field already containing "password: example", `append=true`, `text="!"`, `submit=true` sends the whole secret-bearing draft, but the proposed bypass predicate sees only "!". This occurs in a native non-browser app on Hard or a background-browser message field, with no Trust rule or unknown vocabulary involved. -> Classify the full resulting draft before Enter in both paths, including append with empty text. Keep display clipping separate. Add append/submit regressions that retain the card and verify denial prevents submission.

F5 [major] The no-reviewer fallback drops the draft before the new secret check - In the normal Smart path, `review` includes the draft in the detail passed to `guardAction` (`app/src/main/java/app/buddy/assistant/Tools.java:1806-1809`). When no reviewer harness is available, however, it passes only `tools_card_no_reviewer` (`:1795-1796`), whose English text is "No reviewer is signed in, so Buddy asks you." (`app/src/main/res/values/strings.xml:1699`). The proposed `money` flag for native Enter contains only app/web/browser facts, and `guardAction` classifies the rendered summary (`plan.md:139`); merely forwarding that flag through `review` does not preserve the draft. Thus submitting "password: example" from a non-search native field in a non-money Smart app can skip Ask's fallback card whenever no reviewer is signed in. -> Preserve draft sensitivity before the reviewer availability branch and carry it into every fallback, or include the full draft in that fallback's classification input. Add a no-harness regression with sensitive and harmless drafts, checking that denial stops the sensitive submission.

VERDICT: REVISE

## Round 4 - Plan Reviewer (gpt-6-astra)

Round 3 F1 is resolved: `brief.md:33` records Nicholas accepting all four Q1 recommendations. No further approval of those choices is needed. The following are the still-open round 3 technical findings, rechecked against the code; they concern explicit evidence discarded by the proposed implementation, not the accepted unnamed-money or opaque-shell limits. No Planner response or corresponding plan revision addresses them. The pipeline's three-round debate limit has been reached; the orchestrator owns disposition of these findings.

F1 [major] Native taps still discard explicit money labels (round 3 F2) - `plan.md:165-170` still defines `tapMoney` using only `Guard.riskyLabel(target)` and `Guard.riskyLabel(hit)`. `app/src/main/java/app/buddy/assistant/Guard.java:708-718` returns the first risky field, and `:666-680` stops at the first matching node. A button with visible text "Send" and description "Pay now" therefore supplies only "Send" to the new predicate, although Ask cards it (`app/src/main/java/app/buddy/assistant/Tools.java:1761-1772`). Q1b accepts labels with no money word (`plan.md:455-458`), not this loss of an explicit Pay label. The rejection at `plan.md:299-300` remains unsound: classification can run only at existing card entry points, preserving plain taps that Ask allows. -> Inspect the relevant text, description, tooltip and ID within the existing bounded node scope without stopping on a non-money risky label. Add Send/Pay-description, tooltip and ID regressions to T9.

F2 [major] Browser Enter still consumes form metadata its producer omits (round 3 F3) - `plan.md:178-181` passes the focus result to `money`, which expects `payForm`, `form`, `title` and `ids`. `app/src/main/assets/browser.js:404` returns only `ok`, `label`, `multiline`, `search` and `submitLabel`; the richer fields belong to the target path at `:303-315`. Step 4 (`plan.md:318-322`) changes only draft clipping in JavaScript. A neutral-URL multiline field in a `/checkout` form with a Send button still cards in Ask (`app/src/main/java/app/buddy/assistant/BrowserTools.java:338-342`) but bypasses the proposed check because the form evidence never arrives. T7's hand-built JSON cannot catch this mismatch. -> Supply form/payment metadata from the focus path or retrieve it before Enter using the existing extraction. Test the actual producer output for the checkout-form case, including denial preventing Enter.

F3 [major] Append-and-submit still checks only the added fragment (round 3 F4) - `plan.md:184-186` calls the newly typed `text` the whole Enter draft. Native typing combines the existing content and `text` into `value` (`app/src/main/java/app/buddy/assistant/Tools.java:1920-1922`) but passes only `text` to approval (`:1970-1972`). Browser typing also appends and then passes only `text` (`app/src/main/java/app/buddy/assistant/BrowserTools.java:315-341`; `app/src/main/assets/browser.js:380-388`). Appending "!" to "password: example" and submitting therefore loses the recognized secret marker before the bypass check. -> Classify the full resulting outgoing draft before Enter in both paths, including append with empty text. Add regressions showing that the card remains and denial prevents submission; retain display clipping separately.

F4 [major] The no-reviewer fallback still loses draft sensitivity (round 3 F5) - The Enter `money` flag at `plan.md:139` contains app/web/browser facts only, and `review` merely forwards it. If `Reviewer.harness` returns null, `app/src/main/java/app/buddy/assistant/Tools.java:1795-1796` calls `guardAction` with only the no-reviewer message, discarding the supplied draft; the ordinary reviewer path retains it at `:1808-1809`. Submitting "password: example" from a native non-search field in a non-money Smart app can consequently bypass Ask's fallback card. -> Compute draft sensitivity before branching on reviewer availability and retain it in the fallback, or pass the full draft as a separate classification input. Add sensitive and harmless no-harness cases, including denial preventing the sensitive submission.

VERDICT: REVISE
