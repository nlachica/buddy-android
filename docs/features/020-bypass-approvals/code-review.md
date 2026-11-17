# Code review: 020-bypass-approvals
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

Reviewed `main...03a2138`, the touched files and their approval callers against the brief, final plan and decisions. The accepted opaque-shell, unnamed-money, Trust/never-ask and clipboard limits are not findings.

F1 [major] Recognized checkout forms lose their approval card - `app/src/main/java/app/buddy/assistant/BrowserTools.java:482-490`: `money` and `moneySite` use only the new word-boundary predicate for form/URL evidence, whereas `riskyTap` at lines 311-312 uses the existing, broader `CHECKOUT` pattern. On `https://shop.example/session`, a generic `Continue` submit button with `payForm=false` and form metadata `/billing` or `checkoutForm` is recognized as a checkout by `riskyTap`, so Ask cards it. Both new money checks and the harmless card summary return false, so Bypass skips that very checkout card. The same loss occurs with checkout evidence in the URL. A temporary JVM probe against the compiled production classes confirmed `riskyTap=Continue`, `money=false` for both forms; `/checkout` was the positive control. This is explicit checkout evidence, not Q1b's accepted unnamed-money case. -> Reuse `CHECKOUT` for form and URL classification alongside the new secret/money predicate, including the dialog's site check. Add regressions for `/billing` and `checkoutForm` (and a checkout URL with a suffix), showing the bypass opt-in stays false; retain a harmless `/note` control.

F2 [major] The money predicate omits existing money labels and IDs - `app/src/main/java/app/buddy/assistant/Bypass.java:50-72`: the copied vocabulary does not cover all of the promised money terms already recognized by `Guard`. For example, `Pedir` (order), `Dodaj do koszyka` (add to cart), and the ID `addtocart` produce an Ask card but return false from `secretOrMoney`. With a neutral app/site, no draft and no other money marker, the new tap path therefore bypasses their card. The compiled-code probe confirmed `Guard.riskyText` recognizes both labels, and `BrowserTools.riskyTap` recognizes an unlabeled button with `ids=addtocart`, while `BrowserTools.money` and the corresponding card summary classify it as harmless. Native ID scanning also passes `addtocart` unchanged into the predicate. -> Add the missing explicit money labels/ID spellings from the existing risk rules, including the Polish inflection, and table-test the money subset against Bypass so these omissions cannot recur. Keep send/delete terms excluded; no new abstraction is needed.

F3 [minor] T4 omits the planned always-ask removal case - `app/src/test/java/app/buddy/assistant/ChatApprovalsSettingTest.java:198-205`: the settings decision test covers adding `allow` rules, but not `button/*/Continue=""` when an `ask` rule exists, which the plan explicitly requires. Removing that rule is how an otherwise unrecognized money action loses its protection. The name-based implementation appears correct, but the promised denial/no-write regression is absent. -> Add a factory-built button with an existing `ask` value, request the empty value in Bypass, deny the card, and assert one card, zero writes and the original rule retained.

Validation: the local `/tmp/buddy-build-020/check.sh github` compiled production and test sources and passed all 187 JVM tests. `node --check app/src/main/assets/browser.js` passed. Extra classification probes were written only under `/tmp`; no code was changed. The diff against pre-019 commit `2e6624d` confirms the Routine settings skip was removed. The fourth UI choice, protected settings names, helper/rule exclusion, full-draft propagation, denial returns, clipboard non-opt-in and exclusion of `BYPASSED` from stored confirm approvals were inspected. No additional YAGNI issue found.

Not checked: full Gradle/APK builds, CI, or phone/release smoke checks; those remain the orchestrator's verification work. The local javac harness uses Android/watch stubs and its documented API-35 workaround, so it does not replace CI.

VERDICT: REVISE

### Coder response (round 1)

- F1: fixed in acda943. `BrowserTools.money` ors `CHECKOUT` on the form name, and `moneySite` goes through a new static `moneyUrl` (`CHECKOUT` or `Bypass.secretOrMoney` on the address). Tests in `BypassTest`: `/billing` and `checkoutForm` forms, a suffixed checkout URL and `/billing/address` are money; `/note` stays false.
- F2: fixed in acda943. `Bypass.MONEY` gains `pedir`, `addtocart`, `inscrever-se`, and `koszyk` / `кошик` with endings (`Dodaj do koszyka`, `додати до кошика`). I ran every term of `Guard.RISKY` through `Bypass.secretOrMoney`: what is still false is only send/post/delete/block/report/call/log-out words. Table test `everyMoneyLabelOfTheAskRulesIsMoney` covers the money subset.
- F3: fixed in acda943. `ChatApprovalsSettingTest.removingAnAlwaysAskRuleKeepsItsCardAndItsRuleOnDenial`: `button/*/Continue` with an `ask` rule, `""` requested in Bypass, denied: one card, zero writes, rule still `ask`.

Local run (`/tmp/buddy-build-020/check.sh github`, from `app/`): 189 JVM tests pass. CI and the phone check are not run here.

## Round 2 - Code Reviewer (gpt-6-astra)

Reviewed `main...1711498`, the touched approval paths and tests against the brief, final plan, decisions and round 1. No new findings; no blocker or major remains open.

- Round 1 F1 resolved: `BrowserTools.money` now retains `CHECKOUT` evidence from form metadata, and `moneySite` uses `moneyUrl`, which retains the same evidence from the address. Both action approval and dialog approval use these checks. Regressions cover `/billing`, `checkoutForm`, checkout/billing addresses and harmless `/note` controls.
- Round 1 F2 resolved: the omitted money labels and ID spelling are included, with a table test covering `Pedir`, Polish and Ukrainian cart inflections, `addtocart` and the other added labels. Send/delete controls remain outside the money predicate.
- Round 1 F3 resolved: the factory-built `button/*/Continue` removal test denies the card and asserts one card, zero writes and the original `ask` rule retained.

Coverage and fit: the fourth UI choice and settings value are wired; settings use the resolved name for the protected-name check; helpers and notification-rule runs cannot bypass; the two-argument approval method remains non-opted-in. Native and browser action paths pass money context and the full draft before card presentation, including append and the no-reviewer fallback. Browser dialogs in Bypass recheck their full text and site. Denial returns still stop execution, clipboard reads remain carded, and bypassed confirms cannot seed a later stored approval. The diff against pre-019 `2e6624d` confirms the Routine settings skip was removed. The accepted opaque-shell, unnamed-money, Trust/never-ask and clipboard limits are not findings. No additional security, resource-lifetime or YAGNI issue found for the intended single-user phone usage.

Validation: ran `/tmp/buddy-build-020/check.sh github` from `app/`; production and test sources compiled and all **189 JVM tests passed**. `node --check app/src/main/assets/browser.js` passed. `git diff --check main...HEAD` reported only the existing trailing space on the unfinished `verify.md` verdict placeholder; no code whitespace errors. No code was changed.

Not checked: full Gradle/APK builds, CI, or phone/release smoke checks. The local javac harness uses Android/watch stubs and an API-35 workaround; it does not replace CI. Done-when items requiring live UI, actual cards/notes and the controlled update install remain the orchestrator's verification work; this verdict approves the code, not completion of those checks.

VERDICT: APPROVE
