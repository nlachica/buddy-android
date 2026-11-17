# Plan: 020-bypass-approvals

Research is empty for this run. The facts below come from the code, with `path:line` on each (lines as of `9c7a20b`).

## Goal
Add a fourth per-chat approvals mode, **Bypass** (stored `"bypass"` in `Session.approvals`), next to upstream's Ask
(`""`), Routine allowed (`"routine"`) and Always ask (`"strict"`). In a Bypass chat Buddy shows no approval card: not
for phone taps and Enter (Hard rules and the Smart reviewer), `confirm`, `phone_shell`, `buddy_settings`, `update_buddy`,
the background browser, or a location request. Each step that would have carded is noted in the chat instead. Secrets
and money still card: anything that names passwords, codes, keys or tokens, or paying, buying, orders, checkout,
transfers or money; everything in banking and payment apps; `buddy_settings` key routes (011); and the settings that
would switch those checks off. Also revert 019, so Routine allowed is upstream's again.

Done when:
1. Chat options shows Bypass as a fourth mode, and `buddy_settings chat/<id>/approvals=bypass` sets it after one card.
2. In Bypass there are no cards for settings, phone actions, confirm, reviewer, phone_shell or installs (tests and a phone check).
3. In Bypass, secrets and money still card (tests).
4. Routine allowed behaves as upstream again (019's split is gone); Ask and Always ask are unchanged.
5. CI is green and Codex approves.

Non-goals:
- From the brief: the agent answering cards; changing Ask, Always ask or Routine allowed; the F-Droid flavor.
- Also out:
  - Translations of the three new strings. Fork strings are English-only (`tools_card_setting` and the others at
    `values/strings.xml:2126-2130`; no locale has them).
  - Changing upstream's hard blocks. These are not cards, and they stay in Bypass: Buddy's own screens are off-limits
    (`Guard.offLimits`), password fields are never typed (`Tools.java:1914`), `SHELL_NO`, and sub-agents (helpers)
    are refused.
  - The clipboard card (see Design).
  - Sending Android's own installer screen: the user still taps Update there.

## Design

### Where the card is skipped: `BuddyService.askConfirm`, opt-in per call site
Almost every approval card goes through `BuddyService.askConfirm(summary, canAuto)` (`BuddyService.java:896`). That
method already holds the chat-level card policy:
- a helper gets `DENIED` with no card (`:899`);
- a rule set to "Don't ask" gets `RULE_APPROVED` with no card, noted in the chat (`noteRuleApproved`, `:864`, `:913`).

Bypass sits in the same place and works the same way:
- New overload `askConfirm(String summary, boolean canAuto, boolean bypass)`. Here `bypass` means "in a Bypass chat
  this card may be skipped; the caller has checked that it isn't about secrets or money".
- The existing two-argument form passes `false`. Every call site that doesn't opt in keeps its card in Bypass,
  including any card a future upstream sync adds. That is fail-safe, the same stance as 019's allow-list.
- Right after the helper check: if `bypass && Bypass.on(this, chat)`, Buddy notes
  `card_note_bypassed` ("✓ Approved by Bypass, without asking: %1$s") in the chat, like `noteRuleApproved`, and
  returns `BYPASSED`.
- `BYPASSED` is a new constant: `"APPROVED: this chat is set to Bypass, so Buddy went ahead without asking the user
  (the chat notes it). Go ahead with this, and only this. Paying, buying, money transfers and anything with
  passwords, codes or keys still ask them."`
- `BYPASSED` is **not** in `selfApproved()`. So `SettingsTool.approval()` (`SettingsTool.java:1428`) accepts it, and
  `buddy_settings` and `update_buddy` need no card-skipping code of their own. This is a deliberate change to 004's
  "only the user's yes" rule: in a Bypass chat the user's standing choice is the yes. The cards that must stay
  (keys, setting a chat to Bypass) never get `BYPASSED`, because their caller passes `bypass=false` (below).

`Bypass.on(c, chat)` = `chat != null && applies(ChatHub.get(c).approvalsFor(chat), Triggers.ruleRun(chat))`, with a
pure `applies(approvals, ruleRun)` = `"bypass".equals(approvals) && !ruleRun`. This mirrors upstream's
`Guard.runRule` (`Guard.java:567-574`). `approvalsFor` already gives `""` for sub-agents, helpers, unknown sessions
and no chat (Remote Control). Both calls are safe from any thread.

### The one predicate: `Bypass.secretOrMoney(String text)`
This is a pure function, JVM-tested. It returns true when any word in the text starts a secret or money term. Words
are checked at each word start, as `Guard.routineText` does (`Guard.java:592-605`): a letter not preceded by a letter
(CJK at any position), followed by a non-letter. Underscores, slashes and hyphens count as word breaks, so `api_key`
and `/checkout/` match, while `keyboard`, `codex`, `payload`, `display` and `spinner` don't.

- **Secrets** (English, the agent's and Nicholas's language): password(s), passcode, passphrase, PIN (upper case, or
  "pin code"), OTP, 2FA/MFA, one-time / verification / security / login code, token(s), secret(s), credential(s),
  API / private / secret / access / SSH key, key file, seed / recovery phrase. Bare "code" is not on the list ("push
  the code" is everyday dev work). Bare "key" is not on it either.
- **Money**:
  - English: pay, pays, paying, paid, payment(s), payout, PayPal, Venmo, Zelle, Cash App, buy, buying, bought,
    purchase(s/d), checkout / check out, order(s), place order, add to cart/basket/bag, cart, subscribe,
    subscription(s), donate, donation, transfer, wire, refund, invoice, bank, banking, IBAN, credit card, debit
    card, card number, CVV/CVC, money, wallet, crypto, bitcoin.
  - Currency: the signs `€ £ ¥ ₹`, and `$` followed by a digit.
  - Other languages: the pay / buy / order / cart / checkout / subscribe / transfer / donate words of `Guard.RISKY`
    and `BrowserTools.CHECKOUT` (bezahlen, kaufen, bestellen, Warenkorb, Kasse, Zahlung, payer, acheter, commander,
    panier, virement, pagar, comprar, carrito, 注文, 購入, 결제, 支付, 购买, thanh toán and the rest).
  - `Guard.RISKY` itself is not split or edited: it is upstream's, and it mixes send and delete words in.

False positives give a card, the safe side. Known ones: a message draft that mentions paying, "transfer the files",
"sort order", and `$1` in a shell command. One test (T2) proves that no locale's card frame (`Tap “%1$s” in %2$s`
and the like) trips the predicate by itself, so the words that count come from the label, app, draft, reviewer
reason, command or URL.

### Each card, in Bypass

| Site | Opt-in (`bypass` argument) | Still cards when |
|---|---|---|
| Tap / Enter guard: `Tools.guardAction` (`Tools.java:1843`), reached from Hard rules (`guard`), Enter (`typeText`) and the Smart reviewer's ask/block (`review`) | `!Guard.moneyApp(svc, frontPkg()) && !Bypass.secretOrMoney(summary)` | banking/payment app (or app unknown: `moneyApp(null)` is true); the card's label, app name, draft or reviewer reason names a secret or money |
| `confirm` (`Tools.java:518`) | `!Guard.moneyApp(svc, front) && !Bypass.secretOrMoney(summary)` | the same |
| `phone_shell` (`Tools.java:668`) | `!Bypass.secretOrMoney(cmd)` | the command names a secret or money |
| `update_buddy` (`Tools.java:2355`) | `true` | never (the release notes aren't scanned: they talk about keys and money as features) |
| Background browser tap / Enter (`BrowserTools.approve`, `:461`) | `!money && !Bypass.secretOrMoney(summary)`. `approve` gets `boolean money`: tap = `t.optBoolean("payForm") \|\| Bypass.secretOrMoney(url + " " + form)`; Enter = `Bypass.secretOrMoney(url)` | a payment form, a checkout/cart/payment URL, or a money/secret word on the card |
| Browser "Are you sure?" dialog (`BrowserTools.java:505`) | `!Bypass.secretOrMoney(msg)` | the dialog names money or a secret |
| Location (`PhoneTools.java:528`) | `true` | never |
| `buddy_settings` (`SettingsTool.call`, `:1584`) | `!SettingsTool.keepsCard(name, value, file != null)` | see the next section |
| Clipboard read (`PhoneTools.java:141`) | not opted in | always: the clipboard is where copied passwords and one-time codes sit |
| Rule runs (`open_url` at `Tools.java:485`, `askRuleReply`) | n/a | `Bypass.on` is false in a rule's run |

Two more changes go with the table:
- **`confirm` doesn't keep a `BYPASSED` answer as an approval.** `Tools.java:520` becomes `if
  (answer.startsWith("APPROVED") && !BuddyService.BYPASSED.equals(answer)) Guard.approved(...)`. Otherwise a vague
  confirm ("Go ahead with step 3") would be skipped, kept by `Guard.approved` as a full approval, and then spent on
  the next "Place order" tap through `takeApproval` (`Guard.java:453`), with no card.
- **The Smart reviewer still runs in Bypass.** It takes its usual ~3 s. Its "ask" or "block" reason goes into the
  card text, and the predicate reads it. So a reviewer that spots a checkout behind a plain "Continue" still gets the
  user's card, and every other reviewer card is skipped. Taps the reviewer allows were never carded and aren't noted.

Unchanged in Bypass: apps on Trust stay on Trust. Taps the rules never card stay silent; only steps that would have
carded are noted. `Guard.runRule` returns false for `"bypass"` unless the app is on the chat's own list.
`Tools.firstUse` already skips any chat whose mode isn't Ask (`Tools.java:915`).

### `buddy_settings` in Bypass: `SettingsTool.keepsCard(name, value, keyFile)`
`keepsCard` is a static, pure, JVM-tested function. It is true (card) for:
- **Secrets:** `keyFile` (a new `mcp/<id>` with `key_file`), or a name ending in `/key` (`mcp/<id>/key`,
  `service/<id>/key`). These are the 011 key routes.
- **Bypass itself:** `chat/<id>/approvals` set to `bypass` (trimmed, any case). Leaving Bypass, or setting another
  mode, needs no card.
- **Money checks switched off:**
  - `app/<pkg>` set to `trust`: Trust skips every check in that app, Pay and Buy included (`Tools.java:1751`).
  - `button/<scope>/<label>` set to `allow` when `Bypass.secretOrMoney(label)`: a never-ask Pay is tapped with no
    card (`Tools.java:1755`).
  - `builtin_risky_words` set to `off`: the built-in Pay and Buy words stop asking.

  Without these three, one silent setting change would switch money cards off for every chat.

Everything else goes through without a card: permissions, `phone_commands`, `agents_tap_buddy`, reviewer, Linux
tools, MCP servers, skills, schedules, rules, agents and models, look and sound. `default_agent` and
`default_model/*` can point a chat at a pay-per-use API key service. That isn't paying or buying in the brief's
sense, so they don't card (a decision, listed for Nicholas).

`call` passes `card -> svc.askConfirm(card, false, !keepsCard(name, a.optString("value"), file != null))`.
`change()` and `approval()` stay as 004 and 010 left them (019's six-argument `change` goes away with the revert).

### The mode itself (010's row and upstream's UI)
- `SettingsTool.approvals` factory (`:756`): the values become `"ask", "routine", "strict", "bypass"`. `allowed()`
  adds `bypass (Bypass)`, and `label` adds "Bypass". `effect("bypass")` gets: "no approval cards: the agent's steps,
  Buddy settings, phone commands and installs go ahead and each is noted in the chat; paying, buying, money transfers,
  banking and payment apps, and passwords, codes and keys still ask". The tool description (`:1537`) and the listing
  header name Bypass. `chatLines` prints `approvals bypass` with no change (`approvalsChoice` passes it through).
- `Ui.approvalsName` (`Ui.java:380`) maps `bypass` to the new string `chat_approvals_bypass` ("Bypass").
  `paintApprovals` already shows any mode other than Ask in the accent colour.
- `ChatActivity.approvalsSheet` (`:1206`) gets a fourth choice, `{"bypass", getString(R.string.chat_approvals_bypass_info)}`:
  "No approval cards: the agent does what you ask, including changing Buddy's settings, running phone commands and
  installing. Each step is noted in the chat. Paying, buying and money transfers, banking and payment apps, and
  passwords, codes and keys still ask you."
- New strings, English only, with translator comments: `chat_approvals_bypass`, `chat_approvals_bypass_info`,
  `card_note_bypassed`.

### Reverting 019
`git revert --no-commit 6883d32 19b0017 5efdc2d` (019's code commits) undoes all of it:
- in `SettingsTool.java`: `ORDINARY`, `sensitive`, `routineChat`, `ROUTINE_NOTE`, the six-argument `change`, the
  routine consumer in `call`, the routine effect sentence, and the class-comment and tool-description sentences;
- `SettingsRoutineTest.java`;
- 019's sentences in `AGENTS.md:25-26` and `docs/GUIDE.md:205`.

`BuildInfo` and 019's docs stay. Routine allowed then goes back to upstream's: no settings card is skipped, and the
card text has no extra sentence.

### Placement: a new fork file `Bypass.java`
`Bypass.java` holds `MODE`, `applies`, `on` and `secretOrMoney`. The predicate is used from four upstream files
(BuddyService, Tools, BrowserTools, SettingsTool). Putting it in `Guard.java` (upstream) would add a merge-conflict
surface on every upstream sync (AGENTS.md "Bringing in official Buddy"). This is a home for fork code, not a new
abstraction.

Alternatives considered:
- Skip in `askConfirm` for every caller, scanning the card text (fail-open): rejected. It loses the money app and the
  checkout URL, scans release notes, skips the clipboard, and skips any card upstream adds later.
- A Bypass check at each of the ~9 sites with its own note: rejected. Nine copies of the note and the chat test,
  where `askConfirm` already holds the chat policy and `RULE_APPROVED` is the precedent.
- Add `BYPASSED` to `selfApproved()` and keep 019's skip in `change()`: rejected. That keeps 019's six-argument path
  and adds a separate skip for `update_buddy`, where the opt-in flag already covers both.
- Treat Bypass as Trust for every app (`Tools.mode`): rejected. Trust drops Pay and Buy cards and banking-app cards too.
- Skip the Smart reviewer in Bypass: rejected. It saves ~3 s a tap but loses the one check that sees a checkout
  behind a plain label.
- Edit `Guard.RISKY` into money and non-money parts: rejected. It is upstream's pattern, and the edit means a sync
  conflict every release.

## Steps
1. **Revert 019.** `git revert --no-commit 6883d32 19b0017 5efdc2d`, then check that `BuildInfo.java` and
   `docs/features/019-*` are untouched. Commit "020 step 1: revert 019 (Routine allowed is upstream's again)".
   `ApprovalGateTest`, `ChatApprovalsSettingTest` and `SettingsFamiliesTest` pass.
2. **Predicate and mode test.** New `app/src/main/java/app/buddy/assistant/Bypass.java` (`MODE`, `applies`, `on`,
   `secretOrMoney`). New `app/src/test/java/app/buddy/assistant/BypassTest.java` with T1, T2 and T3.
3. **The skip.** In `BuddyService.java`: `BYPASSED`, the three-argument `askConfirm` (the two-argument form passes
   `false`), and `noteBypassed`. In `values/strings.xml`: `card_note_bypassed`. Opt the sites in:
   - `Tools.java`: `confirm` (and the `Guard.approved` guard), `guardAction`, `phoneShell`, `updateBuddy`;
   - `PhoneTools.java`: `location`;
   - `BrowserTools.java`: `approve` gains `boolean money` (both callers pass it), and the dialog.

   Add T4 to `ApprovalGateTest`.
4. **Settings.** In `SettingsTool.java`: `keepsCard`, the `call` asker, the `approvals` factory (value, `allowed`,
   `label`, `effect`), and the tool description and listing header. Update the two `allowed()` strings in
   `ChatApprovalsSettingTest.java:43,61`. Add T5 and T6 (in `ChatApprovalsSettingTest` and `BypassTest`).
5. **UI.** In `Ui.approvalsName`, `ChatActivity.approvalsSheet`, and `values/strings.xml`: `chat_approvals_bypass`
   and `chat_approvals_bypass_info`.
6. **Docs.**
   - `AGENTS.md`, the `buddy_settings` sentence: "(also one approval card per change, except in a chat set to
     Bypass, where only keys, setting a chat to Bypass, and switching off money checks still card)".
   - `docs/GUIDE.md`: a fork bullet under "Approvals, per chat" (after `:211`): "**Bypass** (nlachica fork): no
     approval cards; each step is noted in the chat. Still asks: paying, buying, money transfers, banking and payment
     apps, passwords, codes and keys, and the settings that would turn those checks off."
7. **Version** (the orchestrator's release step): `BuildInfo.java` → `2.3.11-fork.4`, `VERSION_CODE` 224.

## Test plan
These are JVM tests. Locally they run with the javac + JUnit script of 017/019 (`/tmp/buddy-build-019/check.sh`,
pointed at this worktree, run from `app/`). CI is the authority.

| # | Test | Proves |
|---|---|---|
| T1 | `secretOrMoney` is **true** for: "Pay $20 to Anna", "Buy now", "Place order", "Checkout", "Add to cart", "Subscribe to Premium", "Transfer money to savings", "Send €5", "Type the password", "Enter the one-time code", "Read the API key", "gh auth token", "cat ~/.ssh/id_rsa private key", "credit card number", "https://www.amazon.com/gp/buy/spc/handlers", "https://shop.example/checkout/", "Jetzt kaufen", "Bezahlen", "注文する", "결제하기", "thanh toán". It is **false** for: "Send the message to Anna", "Delete the old screenshots", "Install the app", "Turn on Bluetooth", "Push the code to GitHub", "Open VS Code", "Tap “Next” in Display settings", "pm list packages", "echo $HOME", "keyboard", "codex", "payload.json", "Pin this chat", "", null | the predicate, both ways (done-when 2 and 3) |
| T2 | For each `values*/strings.xml`, the frames `tools_card_tap`, `_tap_in`, `_tap_this`, `_tap_this_in`, `_enter`, `_enter_in`, `_detail`, `_quote`, `_no_reviewer`, `_reviewer_block`, `_reviewer_ask`, `browser_card_tap`, `_enter`, `_dialog`, with every `%n$s` blanked, are not `secretOrMoney` (read from `src/main/res` like `ChatScrollTest`) | the framing words in any of the 18 languages never force a card |
| T3 | `Bypass.applies("bypass", false)` is true. `applies("bypass", true)`, `applies("routine", false)`, `applies("strict", false)` and `applies("", false)` are false | only a Bypass chat skips, never a rule's run (done-when 2 and 4) |
| T4 | `BYPASSED` starts with `APPROVED`. `selfApproved(BYPASSED)` is false. `SettingsTool.change` with an asker that returns `BYPASSED` writes once. The existing `onlyTheUsersApprovalWritesTheChange` cases (DENIED, CHANGES, NOT_NOW, AUTO, RULE) still write nothing | settings and update go through on a skipped card; other non-user answers still don't |
| T5 | `keepsCard` is **true** for: `mcp/notes/key`, `service/deepseek/key`, any name with `keyFile`, `chat/c1/approvals`=`bypass` / `Bypass ` / `BYPASS`, `app/com.amazon.mShop.android.shopping`=`trust`, `button/*/Pay`=`allow`, `button/com.x/Place order`=`allow`, `builtin_risky_words`=`off`. It is **false** for: `audio_device`=`auto`, `reviewer`=`off`, `phone_commands`=`on`, `linux_tools_new_chats`=`on`, `mcp/notes`=`off`, `chat/c1/approvals`=`ask`/`routine`/`strict`, `app/com.x`=`hard`/`smart`/`default`, `button/*/Delete`=`allow`, `button/*/Pay`=`ask`, `builtin_risky_words`=`on`, `skill/x`=`…`, `chat/c1/schedule`=`…`, `default_agent`=`codex` | the settings split: keys, Bypass itself and money switches card (done-when 1 and 3); the rest don't (done-when 2) |
| T6 | 010's factory: `bypass` is accepted (and `BYPASS` too). `read()` lists `bypass (Bypass)`. The card for `ask` → `bypass` shows `bypass (Bypass): no approval cards` and `still ask`. `chatLines` prints `approvals bypass`. The `routine` card no longer contains "Ordinary Buddy settings" | done-when 1 (setting it); done-when 4 (019's sentence is gone) |

Done-when item by item:
1. **Fourth mode, settable with one card:**
   - T6 and T5 (`chat/c1/approvals`=`bypass` keeps the card even inside a Bypass chat).
   - The UI row is checked by code review and on the phone.
2. **No cards in Bypass:**
   - The predicate and gating: T1, T3, T4 and T5 (negatives).
   - The `askConfirm` skip and each site's opt-in are on Android classes that aren't JVM-constructible. Code review
     checks every opt-in against the table above, and the phone check confirms.
3. **Secrets and money still card:**
   - T1 (positives), T5 (positives), and T2 (no frame hides or forces it).
   - Code review checks that `moneyApp` sits in `guardAction` and `confirm`, that `payForm` and the URL sit in the
     browser, that the clipboard is not opted in, and that `Guard.approved` skips `BYPASSED`.
4. **Routine allowed is upstream's again:**
   - Step 1's revert: the diff of `SettingsTool.java` against `2e6624d` (before 019) shows only 020's lines.
   - T6's negative and T3 (`routine` doesn't apply).
   - `ApprovalGateTest` and `ChatApprovalsSettingTest` pass. Ask and Always ask code paths are untouched (only
     opt-in flags are added).
5. **CI and Codex:** the PR's `build.yml` run (`testGithubDebugUnitTest`, both release builds, wear,
   `helper-tests`), watched with `gh pr checks --watch`. Then the Codex code review.

**Phone check** (after merge, tag and `update_buddy`; until then `pending` in `verify.md`):
1. Chat options → Approvals shows four rows. Pick Bypass: the line reads "Approvals · Bypass" in the accent colour.
2. In that chat:
   - `buddy_settings audio_device=auto`: no card, and the chat notes "✓ Approved by Bypass…".
   - `confirm "Delete the test screenshot"`: no card, noted.
   - A Send in a messaging app on Hard (a message to yourself): no card, noted.
   - `phone_shell pm list packages` (if phone commands are on): no card.
3. Still in that chat, these card, and you deny each one:
   - `confirm "Buy the cheapest one"`;
   - `buddy_settings service/<id>/key` with a key_file;
   - `buddy_settings chat/<other chat>/approvals=bypass`;
   - `buddy_settings app/<some shop app>=trust`.
4. An update install without a card can only be seen at the next release (fork.5). Until then that line in
   `verify.md` stays pending.
5. Switch the chat to Routine allowed and set `read_aloud`: a card shows (019's skip is gone).

## Risks
- **Prompt injection in a Bypass chat.** A message or page that tricks the agent now meets no card for
  non-money, non-secret steps: deleting, sending, installing, phone commands, MCP servers, skills, schedules. That is
  the trade the brief asks for, and the mode's info text says so. What still stands: money and secret cards, the hard
  blocks (Buddy's screens, password fields, `SHELL_NO`), and each step noted in the chat.
- **The word lists are heuristic.**
  - A money step whose card names no money word, in an app that isn't a money app, goes through. For example, a
    plain "Continue" in a shop app on Hard: Smart's reviewer would catch it, Hard has no reviewer.
  - Bank websites in the background browser count only by URL or button words, and "Send" in a bank's web page
    isn't a money word.
  - False positives (a draft mentioning pay, "transfer files", `$1` in awk) show a card, which is safe.
  - Secret words are English only. Password fields are refused in every language anyway.
- **`approval()` now accepts one answer that isn't a card click (`BYPASSED`).** It is returned only from
  `askConfirm`, and only in a Bypass chat, for a caller that passed `bypass=true`. T4 and code review pin that down.
- **Scheduled runs of a Bypass chat bypass too.** That matches upstream, where Routine allowed applies to the chat's
  scheduled runs. Notification-rule runs never bypass.
- **Downgrade.** An older Buddy that reads `"bypass"` behaves as Ask (except that `firstUse` stays quiet) and labels
  the line Ask. Nothing breaks.
- **Upstream sync.** Small opt-in edits in BuddyService, Tools, BrowserTools, PhoneTools, ChatActivity and Ui, plus
  fork files. If upstream adds a fourth mode or renames `askConfirm`, the sync's Review step checks 020's done-when.

## Open questions
None blocking. To show Nicholas in the PR (each is a one-line flip):
- the clipboard still cards in Bypass;
- `default_agent` and `default_model/*` don't card, though they can point chats at a pay-per-use API service;
- `app/<pkg>=trust`, a never-ask money button and `builtin_risky_words=off` card, as switches for money checks.
