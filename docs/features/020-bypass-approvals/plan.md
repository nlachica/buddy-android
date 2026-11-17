# Plan: 020-bypass-approvals

Research is empty for this run. The facts below come from the code, with `path:line` on each (lines as of `9c7a20b`).
Revised after plan review round 1 (`plan-review.md`).

## Goal
Add a fourth per-chat approvals mode, **Bypass** (stored `"bypass"` in `Session.approvals`), next to upstream's Ask
(`""`), Routine allowed (`"routine"`) and Always ask (`"strict"`).

In a Bypass chat Buddy shows no approval card for:
- phone taps and Enter (Hard rules and the Smart reviewer);
- `confirm`, `phone_shell`, `buddy_settings` and `update_buddy`;
- the background browser, location, and the clipboard.

Each step that would have carded is noted in the chat instead.

Secrets and money still card:
- anything recognisably about passwords, codes, keys, tokens or key files, or about paying, buying, orders, checkout,
  transfers or money;
- everything in banking and payment apps and on money sites;
- a clipboard holding a password or code;
- `buddy_settings` key routes (011), and the settings that would loosen these checks.

"Recognisably" is the limit: Buddy judges a step by its words, its app, its site and its file names (see Risks).

The invariant: Bypass shows every secret and money card that Ask would show with the same settings, and the agent
can't change those settings without a card.

Also revert 019, so Routine allowed is upstream's again.

Done when:
1. Chat options shows Bypass as a fourth mode, and `buddy_settings chat/<id>/approvals=bypass` sets it after one card.
2. In Bypass there are no cards for settings, phone actions, confirm, reviewer, phone_shell or installs (tests and a phone check).
3. In Bypass, secrets and money still card (tests).
4. Routine allowed behaves as upstream again (019's split is gone); Ask and Always ask are unchanged.
5. CI is green and Codex approves.

Non-goals:
- From the brief: the agent answering cards; changing Ask, Always ask or Routine allowed; the F-Droid flavor. The F-Droid
  flavor gets no gate: the fork ships the github flavor only, and fork features (001, 016) already build ungated in it.
- Also out:
  - Translations of the three new strings. Fork strings are English-only (`tools_card_setting` and the others at
    `values/strings.xml:2126-2130`; no locale has them).
  - Making Bypass card more than Ask. Apps on Trust, buttons on the never-ask list, and Safety switches the user
    already set stay as they are: they are the user's App permissions choices, in every mode.
  - Changing upstream's hard blocks. These are not cards, and they stay in Bypass:
    - Buddy's own screens are off-limits (`Guard.offLimits`);
    - password fields are never typed (`Tools.java:1914`);
    - `SHELL_NO`;
    - sub-agents (helpers) are refused.
  - Skipping Android's own installer screen: the user still taps Update there.

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
  including any card a future upstream sync adds. That is fail-safe.
- Right after the helper check: if `Bypass.skips(bypass, hub.approvalsFor(chat), Triggers.ruleRun(chat))`, Buddy notes
  `card_note_bypassed` ("✓ Approved by Bypass, without asking: %1$s") in the chat, like `noteRuleApproved`, and
  returns `BYPASSED`.
- `BYPASSED` is a new constant: `"APPROVED: this chat is set to Bypass, so Buddy went ahead without asking the user
  (the chat notes it). Go ahead with this, and only this. Paying, buying, money transfers and anything with
  passwords, codes or keys still ask them."`
- `BYPASSED` is **not** in `selfApproved()`. So `SettingsTool.approval()` (`SettingsTool.java:1428`) accepts it, and
  `buddy_settings` and `update_buddy` need no card-skipping code of their own. This is a deliberate change to 004's
  "only the user's yes" rule: in a Bypass chat, the user's standing choice is the yes. The cards that must stay never
  get `BYPASSED`, because their caller passes `bypass=false`.

`Bypass.skips(optIn, approvals, ruleRun)` is pure and JVM-tested: `optIn && "bypass".equals(approvals) && !ruleRun`.
`Bypass.on(c, chat)` is `chat != null && skips(true, ChatHub.get(c).approvalsFor(chat), Triggers.ruleRun(chat))`, and it
is used where a site needs the mode itself (the browser dialog, the clipboard). This mirrors upstream's
`Guard.runRule` (`Guard.java:567-574`). `approvalsFor` already gives `""` for sub-agents, helpers, unknown sessions
and no chat (Remote Control).

### The one predicate: `Bypass.secretOrMoney(String... parts)`
A pure function, JVM-tested, true when any part names a secret or money.

How it matches:
- Words are checked at each word start, as `Guard.routineText` does (`Guard.java:592-605`): a letter not preceded by
  a letter (CJK at any position), followed by a non-letter.
- Underscores, slashes, dots and hyphens count as word breaks. So `api_key` and `/checkout/` match, while `keyboard`,
  `codex`, `payload`, `display`, `paylaş` and `spinner` don't.

What it matches:
- **Secrets** (English, the agent's and Nicholas's language):
  - Words: password(s), passcode, passphrase, PIN (upper case, or "pin code"), OTP, 2FA/MFA, one-time /
    verification / security / login code, token(s), secret(s), credential(s), API / private / secret / access / SSH
    key, key file, seed / recovery phrase. Bare "code" and bare "key" are not on the list ("push the code", "Claude
    Code").
  - Credential files, matched in the raw text: `id_rsa`, `id_dsa`, `id_ecdsa`, `id_ed25519`, `.ssh/`, `.env`,
    `.netrc`, `.pgpass`, `.git-credentials`, `.npmrc`, `.pypirc`, and names ending `.pem`, `.key`, `.p12`, `.pfx`,
    `.jks`, `.keystore`, `.kdbx`, `.gpg`.
  - A text that is only a 4-8 digit number: a copied one-time code.
- **Money**:
  - English: pay, pays, paying, paid, payment(s), payout, PayPal, Venmo, Zelle, Cash App, buy, buying, bought,
    purchase(s/d), checkout / check out, order(s), place order, add to cart/basket/bag, cart, subscribe,
    subscription(s), donate, donation, transfer, wire, refund, invoice, bank, banking, IBAN, credit card, debit
    card, card number, CVV/CVC, money, wallet, crypto, bitcoin.
  - The words that finish a checkout: confirm and submit, and `Guard.RISKY`'s translations of them (valider,
    confirmer, bestätigen, conferma, confirmar, bevestigen, potwierdź, подтвердить, підтвердити, onayla, पुष्टि करें,
    konfirmasi, 確定, 确认, تأكيد, xác nhận). In Ask such a button always cards, and on a checkout screen it is the
    pay step.
  - Currency: the signs `€ £ ¥ ₹`, and `$` followed by a digit.
  - Other languages: the pay, buy, order, cart, checkout, subscribe, transfer and donate words of `Guard.RISKY` and
    `BrowserTools.CHECKOUT` (bezahlen, kaufen, bestellen, Warenkorb, Kasse, Zahlung, payer, acheter, commander,
    panier, virement, pagar, comprar, carrito, 注文, 購入, 결제, 支付, 购买, thanh toán and the rest).
  - `Guard.RISKY` itself is not split or edited: it is upstream's, and it mixes in send and delete words.

False positives give a card, which is the safe side. Known ones:
- a message draft that mentions paying;
- "transfer the files", "sort order";
- `$1` in a shell command, `process.env`;
- a "Confirm" that only confirms a delete.

T2 proves that no locale's card frame (`Tap “%1$s” in %2$s` and the like) trips the predicate by itself. So the words
that count come from the label, app, draft, reviewer reason, command, URL or clipboard.

### Each card, in Bypass

| Site | Opt-in (`bypass` argument) | Still cards when |
|---|---|---|
| Tap / Enter guard: `Tools.guardAction` (`Tools.java:1843`), reached from Hard rules (`guard`), Enter (`typeText`) and the Smart reviewer's ask/block (`review`) | `!Guard.moneyApp(svc, frontPkg()) && !Bypass.secretOrMoney(summary)` | banking/payment app (or app unknown: `moneyApp(null)` is true); the label, app name, draft or reviewer reason names a secret or money |
| `confirm` (`Tools.java:518`) | `!Guard.moneyApp(svc, front) && !Bypass.secretOrMoney(summary)` | the same |
| `phone_shell` (`Tools.java:668`) | `!Bypass.secretOrMoney(cmd)` | the command line names a secret, a key file or money (the command line only: see Risks) |
| `update_buddy` (`Tools.java:2355`) | `true` | never (the release notes aren't scanned: they talk about keys and money as features) |
| Background browser tap / Enter (`BrowserTools.approve`, `:461`) | `!money && !Bypass.secretOrMoney(summary)`. `approve` gets `boolean money`: `BrowserTools.money(o, url) \|\| Guard.moneyApp(svc, host(key))`, where `o` is the tap's target JSON or Enter's focus JSON | a payment form; a money word in the URL or the item's form, title, label, ids or submit label; a host with a money name (bank, pay, wallet, finance, credit, crypto, trading, broker) |
| Browser "Are you sure?" dialog (`BrowserTools.pendingDialog`, `:479-507`) | `!Bypass.secretOrMoney(fullMessage)` | the dialog's full message names money or a secret, even after a bypassed tap (below) |
| Location (`PhoneTools.java:528`) | `true` | never |
| Clipboard read (`PhoneTools.java:140`) | after reading: `!sensitive && !Bypass.secretOrMoney(text)` | the copying app marked the clip sensitive, or the text names a secret or money, or is a bare 4-8 digit code |
| `buddy_settings` (`SettingsTool.call`, `:1584`) | `!SettingsTool.keepsCard(name, value, file != null)` | see the next section |
| Rule runs (`open_url` at `Tools.java:485`, `askRuleReply`) | n/a | `Bypass.on` is false in a rule's run |

What goes with the table:
- **`confirm` doesn't keep a `BYPASSED` answer as an approval.** `Tools.java:520` becomes `if
  (answer.startsWith("APPROVED") && !BuddyService.BYPASSED.equals(answer)) Guard.approved(...)`. Otherwise a vague
  confirm ("Go ahead with step 3") would be skipped, kept by `Guard.approved` as a full approval, and then spent on
  the next "Place order" tap through `takeApproval` (`Guard.java:453`), with no card.
- **A browser dialog doesn't inherit a bypassed tap's approval.** Today `click` passes `approved=true` after `approve`
  lets a tap through (`BrowserTools.java:250-260`), and `type` passes `risky` (`:347`). `pendingDialog` then accepts any
  dialog when that flag is set (`:503-506`), so "Send" could open "Pay $20?" and get OK.
  - New pure `static boolean dialogAsks(boolean approved, boolean bypass, String msg)` =
    `RISKY_DIALOG.matcher(msg).find() && (!approved || bypass)`, with `bypass = Bypass.on(svc, chat)`.
  - When it asks: `askConfirm(dialogCard, false, !Bypass.secretOrMoney(fullMessage))`, where `fullMessage` is
    `d.optString("message")` before the 300-character clip.
  - So in a Bypass chat every risky dialog is judged on its own text. Other modes are unchanged.
- **The browser's money context is read before presentation.** New pure `static boolean money(JSONObject o, String
  url)` = `o.optBoolean("payForm") || Bypass.secretOrMoney(url, o.optString("form"), o.optString("title"),
  o.optString("label"), o.optString("ids"), o.optString("submitLabel"))`. The tap passes its target `t`, and Enter
  passes its focus result `f` (which holds `submitLabel`, `:338`).
- **The clipboard is read first in Bypass.**
  - In a Bypass chat `clipboardRead` skips the up-front card, reads the clip, then calls
    `askConfirm(tools_card_clipboard, false, !sensitive && !Bypass.secretOrMoney(text))`. It returns the text only
    on an `APPROVED…` answer.
  - `PhoneToolActivity`'s clipboard job (`:79-91`) also reports whether the clip is marked sensitive:
    `clip.getDescription().getExtras()` holds `"android.content.extra.IS_SENSITIVE"` (`ClipDescription.EXTRA_IS_SENSITIVE`,
    API 33+). The literal is used because minSdk is 30; below 33 the flag is just absent.
  - In other modes the card still comes first and nothing else changes.
- **The Smart reviewer still runs in Bypass.** It takes its usual ~3 s. Its "ask" or "block" reason goes into the card
  text, and the predicate reads it. So a reviewer that spots a checkout behind a plain "Continue" still gets the
  user's card, and every other reviewer card is skipped. Taps the reviewer allows were never carded and aren't noted.

Unchanged in Bypass, the same as in Ask:
- Apps on Trust and buttons on the never-ask list never card (`Tools.java:502`, `:1751-1755`).
- Taps the rules never card stay silent; only steps that would have carded are noted.
- A chat's own "Allowed without asking" apps keep their run rule (`Guard.runRule` gives `"bypass"` only the chat's
  list).
- `Tools.firstUse` already skips any chat whose mode isn't Ask (`Tools.java:915`).

### `buddy_settings` in Bypass: `SettingsTool.keepsCard(name, value, keyFile)`
`keepsCard` is a static, pure, JVM-tested function. It is true (card) for:
- **Secrets:** `keyFile` (a new `mcp/<id>` with `key_file`), or a name ending in `/key` (`mcp/<id>/key`,
  `service/<id>/key`). These are the 011 key routes.
- **The approvals modes themselves:** any `chat/<id>/approvals` change. Setting a chat to Bypass cards (the brief), and
  so does leaving it. Routine's run rule lets a reviewer "ask" through without the predicate, so leaving to Routine
  must card too. Leaving to Ask or Always ask is rare, and one card there keeps the rule to one line.
- **Switches that loosen a check money cards depend on:**
  - `app/<pkg>` set to `trust`: Trust skips every check in that app, Pay and Buy included (`Tools.java:1751`).
  - `button/<scope>/<label>` set to `allow`, for any label: a never-ask button is tapped with no card
    (`Tools.java:1755`), and Buddy can't tell whether "Send" or "Continue" moves money in a given app.
  - `chat/<id>/allowed/<pkg>` set to `on`: the run rule notes a step before any card is considered (`guardAction`'s
    `byRule`), so it would get around the predicate.
  - `builtin_risky_words` set to `off`: the built-in Pay and Buy words stop asking.

  `phone_commands_ask` needs no entry: it is read-only to agents (`SettingsTool.java:169-175`). On screen, these
  switches live in App permissions, which agents can't tap (`AppPermissionsActivity.java:72`).

Everything else goes through without a card: permissions other than Trust, `phone_commands`, `agents_tap_buddy`,
reviewer, Linux tools, MCP servers, skills, schedules, rules, agents and models, look and sound. `default_agent` and
`default_model/*` can point a chat at a pay-per-use API key service. That isn't paying or buying in the brief's
sense, so they don't card (a decision, listed for Nicholas).

`call` passes `card -> svc.askConfirm(card, false, !keepsCard(name, a.optString("value"), file != null))`.
`change()` and `approval()` stay as 004 and 010 left them (019's six-argument `change` goes away with the revert).

### The mode itself (010's row and upstream's UI)
- `SettingsTool.approvals` factory (`:756`):
  - The values become `"ask", "routine", "strict", "bypass"`. `allowed()` adds `bypass (Bypass)`, and `label` adds
    "Bypass".
  - `effect("bypass")`: "no approval cards: the agent's steps, Buddy settings, phone commands and installs go ahead
    and each is noted in the chat; paying, buying, money transfers, banking and payment apps, and passwords, codes and
    keys still ask, and so do settings that would turn those checks off".
  - The tool description (`:1537`) and the listing header name Bypass.
  - `chatLines` prints `approvals bypass` with no change (`approvalsChoice` passes it through).
- `Ui.approvalsName` (`Ui.java:380`) maps `bypass` to the new string `chat_approvals_bypass` ("Bypass").
  `paintApprovals` already shows any mode other than Ask in the accent colour.
- `ChatActivity.approvalsSheet` (`:1206`) gets a fourth choice, `{"bypass", getString(R.string.chat_approvals_bypass_info)}`:
  "No approval cards: the agent does what you ask, including changing Buddy's settings, running phone commands and
  installing. Each step is noted in the chat. Still asks: paying, buying, money transfers, banking and payment apps,
  passwords, codes and keys, and settings that would turn those checks off. Apps you set to Trust and buttons you set
  to never ask stay as you set them."
- New strings, English only, with translator comments: `chat_approvals_bypass`, `chat_approvals_bypass_info`,
  `card_note_bypassed`.

### Reverting 019
`git revert --no-commit 6883d32 19b0017 5efdc2d` (019's code commits) undoes all of it:
- in `SettingsTool.java`: `ORDINARY`, `sensitive`, `routineChat`, `ROUTINE_NOTE`, the six-argument `change`, the
  routine consumer in `call`, the routine effect sentence, and the class-comment and tool-description sentences;
- `SettingsRoutineTest.java`;
- 019's sentences in `AGENTS.md:25-26` and `docs/GUIDE.md:205`.

`6883d32` also appended 019's decisions to `docs/features/019-settings-no-card-routine/decisions.md`. Step 1 restores
that file before committing, because 019's history stays. `BuildInfo` stays too. Routine allowed then goes back to
upstream's: no settings card is skipped, and the card text has no extra sentence.

### Placement: a new fork file `Bypass.java`
`Bypass.java` holds `MODE`, `skips`, `on` and `secretOrMoney`. The predicate is used from five upstream files
(BuddyService, Tools, BrowserTools, PhoneTools, SettingsTool). Putting it in `Guard.java` (upstream) would add a
merge-conflict surface on every upstream sync (AGENTS.md "Bringing in official Buddy"). This is a home for fork code,
not a new abstraction. `BrowserTools.money` and `dialogAsks` stay in BrowserTools, next to the private patterns they
read.

Alternatives considered:
- Skip in `askConfirm` for every caller, scanning the card text (fail-open): rejected. It loses the money app and the
  checkout URL, scans release notes, and skips any card upstream adds later.
- A Bypass check at each of the ~10 sites, each with its own note: rejected. That means ten copies of the note and the
  chat test, where `askConfirm` already holds the chat policy and `RULE_APPROVED` is the precedent.
- Bypass overriding Trust, never-ask buttons and the user's Safety switches with its own money check (review F1):
  rejected. The no-cards mode would card more than Ask, in apps the user chose to trust. Carding the agent's changes to
  those switches closes the escape instead.
- Add `BYPASSED` to `selfApproved()` and keep 019's skip in `change()`: rejected. That keeps 019's six-argument path
  and needs a separate skip for `update_buddy`, where the opt-in flag already covers both.
- Treat Bypass as Trust for every app (`Tools.mode`): rejected. Trust drops Pay and Buy cards and banking-app cards too.
- Skip the Smart reviewer in Bypass: rejected. It saves ~3 s a tap, but it loses the one check that sees a checkout
  behind a plain label.
- Track per-approval provenance through the browser (review F3's wording): rejected for the simpler rule that a Bypass
  chat judges each risky dialog on its own text.
- Edit `Guard.RISKY` into money and non-money parts: rejected. It is upstream's pattern, and the edit would mean a
  sync conflict every release.
- Gate Bypass out of the F-Droid flavor (review F7): rejected. The brief makes F-Droid a non-goal, and the fork ships
  the github flavor only.

## Steps
1. **Revert 019.**
   - Run `git revert --no-commit 6883d32 19b0017 5efdc2d`, then
     `git checkout HEAD -- docs/features/019-settings-no-card-routine/decisions.md`.
   - Check that `git diff --cached --stat` lists no `docs/features/019-*` path and no `BuildInfo.java`.
   - Commit "020 step 1: revert 019 (Routine allowed is upstream's again)". `ApprovalGateTest`,
     `ChatApprovalsSettingTest` and `SettingsFamiliesTest` pass.
2. **Predicate and mode test.** Add `app/src/main/java/app/buddy/assistant/Bypass.java` (`MODE`, `skips`, `on`,
   `secretOrMoney`) and `app/src/test/java/app/buddy/assistant/BypassTest.java` with T1, T2 and T3.
3. **The skip, phone side.**
   - In `BuddyService.java`: `BYPASSED`, the three-argument `askConfirm` (the two-argument form passes `false`), and
     `noteBypassed`.
   - In `values/strings.xml`: `card_note_bypassed`.
   - Opt in, in `Tools.java`: `confirm` (and the `Guard.approved` guard), `guardAction`, `phoneShell` and
     `updateBuddy`. In `PhoneTools.java`: `location`.
4. **The skip, browser.** In `BrowserTools.java`:
   - `money(JSONObject, String)` and `dialogAsks(boolean, boolean, String)`;
   - `approve` gains `boolean money`, and `click` and `type` pass it;
   - `pendingDialog` uses `dialogAsks` and the full message.

   Add T7 and T8 (in `BypassTest`).
5. **Clipboard.** `PhoneToolActivity`'s clipboard job reports the sensitive flag along with the text (its only caller
   is `clipboardRead`). `PhoneTools.clipboardRead` reads first in a Bypass chat, as in Design.
6. **Settings.** In `SettingsTool.java`:
   - `keepsCard` and the `call` asker;
   - the `approvals` factory (value, `allowed`, `label`, `effect`);
   - the tool description and the listing header.

   Update the two `allowed()` strings in `ChatApprovalsSettingTest.java:43,61`. Add T4, T5 and T6 (in
   `ChatApprovalsSettingTest` and `BypassTest`).
7. **UI.** `Ui.approvalsName`, `ChatActivity.approvalsSheet`, and in `values/strings.xml`: `chat_approvals_bypass` and
   `chat_approvals_bypass_info`.
8. **Docs.**
   - `AGENTS.md`, the `buddy_settings` sentence: "(also one approval card per change, except in a chat set to
     Bypass, where only keys, approvals modes, and switches that loosen money checks still card)".
   - `docs/GUIDE.md`: a fork bullet under "Approvals, per chat" (after `:211`): "**Bypass** (nlachica fork): no
     approval cards; each step is noted in the chat. Still asks: paying, buying, money transfers, banking and payment
     apps and money sites, passwords, codes and keys (including a clipboard holding one), and the settings that would
     turn those checks off. Apps on Trust and never-ask buttons stay as you set them."
9. **Version** (the orchestrator's release step): `BuildInfo.java` → `2.3.11-fork.4`, `VERSION_CODE` 224. After
   fork.4 is installed, cut `2.3.11-fork.5` (version bump only, via "Updating Buddy on the phone") as the controlled
   update for the phone check.

## Test plan
These are JVM tests (JUnit only, `app/build.gradle.kts:83-92`). Locally they run with the javac + JUnit script of
017/019 (`/tmp/buddy-build-019/check.sh`, pointed at this worktree, run from `app/`). CI is the authority.

| # | Test | Proves |
|---|---|---|
| T1 | `secretOrMoney` is **true** for: "Pay $20 to Anna", "Buy now", "Place order", "Checkout", "Add to cart", "Subscribe to Premium", "Transfer money to savings", "Send €5", "Tap “Confirm” in Shop", "Submit", "Type the password", "Enter the one-time code", "Read the API key", "gh auth token", "cat /sdcard/id_rsa", "cat /sdcard/Download/.env", "ls /sdcard/.ssh/", "cp /sdcard/release.jks /sdcard/x", "cat ~/.netrc", "482913", "credit card number", "https://www.amazon.com/gp/buy/spc/handlers", "https://shop.example/checkout/", "Jetzt kaufen", "Bezahlen", "Bestätigen", "注文する", "결제하기", "thanh toán". It is **false** for: "Send the message to Anna", "Delete the old screenshots", "Install the app", "Turn on Bluetooth", "Push the code to GitHub", "Open VS Code", "Tap “Next” in Display settings", "pm list packages", "echo $HOME", "keyboard", "codex", "payload.json", "confirmation sent", "Pin this chat", "https://example.com/article", "", null | the predicate, both ways, with real commands as fixtures (done-when 2 and 3) |
| T2 | For each `values*/strings.xml`, the frames `tools_card_tap`, `_tap_in`, `_tap_this`, `_tap_this_in`, `_enter`, `_enter_in`, `_detail`, `_quote`, `_no_reviewer`, `_reviewer_block`, `_reviewer_ask`, `_clipboard`, `_location`, `_shell`, `browser_card_tap`, `_enter` and `_dialog`, with every `%n$s` blanked, are not `secretOrMoney` (read from `src/main/res` like `ChatScrollTest`) | the framing words in any of the 18 languages never force a card |
| T3 | `Bypass.skips(true, "bypass", false)` is true. `skips(false, "bypass", false)`, `skips(true, "bypass", true)`, and `skips(true, x, false)` for x = `routine`, `strict`, `""` are false | only an opted-in card in a Bypass chat skips, never in a rule's run or another mode (done-when 2 and 4) |
| T4 | The settings decision path: `SettingsTool.change` with the asker `c -> Bypass.skips(!keepsCard(name, value, keyFile), mode, ruleRun) ? BYPASSED : user.ask(c)`, where `user` counts cards and answers yes or no, run on factory-built settings (a Scalar `audio_device`, `button(...)`, `approvals(...)`). In mode `bypass`: `audio_device=auto` gives 0 cards and 1 write. `button/com.bank.app/Send=allow`, `button/*/Pay=allow` and `chat/c1/approvals=bypass` give 1 card, and on denial 0 writes. In `routine`, `""`, `strict`, and `bypass` with `ruleRun`: `audio_device=auto` gives 1 card. Also: `BYPASSED` starts with `APPROVED`, `selfApproved(BYPASSED)` is false, and the existing `onlyTheUsersApprovalWritesTheChange` answers (DENIED, CHANGES, NOT_NOW, AUTO, RULE) still write nothing | settings go through in Bypass, keep their card where they must, the bank-Send sequence cards, denial writes nothing, and Routine skips nothing (done-when 1-4) |
| T5 | `keepsCard` is **true** for: `mcp/notes/key`, `service/deepseek/key`, any name with `keyFile`, `chat/c1/approvals`=`bypass` / `Bypass ` / `ask` / `routine` / `strict`, `app/com.amazon.mShop.android.shopping`=`trust`, `button/*/Pay`=`allow`, `button/com.bank.app/Send`=`allow`, `button/*/Continue`=`allow`, `chat/c1/allowed/com.whatsapp`=`on`, `builtin_risky_words`=`off`. It is **false** for: `audio_device`=`auto`, `reviewer`=`codex`, `phone_commands`=`on`, `linux_tools_new_chats`=`on`, `agents_tap_buddy`=`on`, `mcp/notes`=`off`, `app/com.x`=`hard`/`smart`/`default`, `button/*/Pay`=`ask`, `button/*/Delete`=`""`, `chat/c1/allowed/com.whatsapp`=`off`, `builtin_risky_words`=`on`, `skill/x`=`…`, `chat/c1/schedule`=`…`, `default_agent`=`codex` | the settings split: keys, approvals modes and loosening switches card (done-when 1 and 3); the rest don't (done-when 2) |
| T6 | 010's factory: `bypass` is accepted (and `BYPASS` too). `read()` lists `bypass (Bypass)`. The card for `ask` → `bypass` shows `bypass (Bypass): no approval cards` and `still ask`. `chatLines` prints `approvals bypass`. The `routine` card no longer contains "Ordinary Buddy settings" | done-when 1 (setting it); done-when 4 (019's sentence is gone) |
| T7 | `BrowserTools.money` is **true** for: focus `{submitLabel:"Pay"}` with URL `https://shop.example/session` (and neutral typed text); target `{label:"→", title:"Pay now"}`; `{label:"", ids:"add-to-cart-button"}`; `{label:"Continue", payForm:true}`; `{label:"Continue", form:"/checkout/step2"}`. It is **false** for: `{label:"Send"}` on `https://mail.example/compose`; focus `{submitLabel:"Search"}`; `{label:"Delete", title:"Delete draft"}` | F2: the browser's money context counts before presentation (done-when 3) |
| T8 | `dialogAsks(true, true, "Pay $20?")` is true, and `secretOrMoney("Pay $20?")` is true, so it cards. `dialogAsks(true, true, "Delete this item?")` is true, but the predicate is false, so it is skipped and noted. `dialogAsks(true, false, "Pay $20?")` is false (upstream unchanged). `dialogAsks(false, false, "Hello")` is false | F3: a bypassed tap can't approve a money dialog (done-when 3) |

Done-when item by item:
1. **Fourth mode, settable with one card:**
   - T6, T5, and T4 (`chat/c1/approvals=bypass` cards inside a Bypass chat).
   - The UI row is checked by code review and on the phone.
2. **No cards in Bypass:**
   - The predicate and gating: T1 (negatives), T3, T4 and T5 (negatives).
   - The tap, confirm, shell, location, clipboard and update opt-ins are one expression each, inside Android classes
     that aren't JVM-constructible. Code review checks every opt-in against the table above, and the phone check
     confirms.
3. **Secrets and money still card:**
   - T1 (positives), T4, T5 (positives), T7, T8, and T2 (no frame hides or forces it).
   - Code review checks:
     - that `moneyApp` sits in `guardAction` and `confirm`, and that `money(...)` and the host test sit in the browser;
     - that the clipboard reads first only in Bypass and checks the flag;
     - that `Guard.approved` skips `BYPASSED`.
4. **Routine allowed is upstream's again:**
   - Step 1's revert: the diff of `SettingsTool.java` against `2e6624d` (before 019) shows only 020's lines.
   - T6's negative, T3 and T4 (`routine` doesn't skip).
   - `ApprovalGateTest` and `ChatApprovalsSettingTest` pass. The Ask and Always ask code paths are untouched (only
     opt-in flags are added).
5. **CI and Codex:** the PR's `build.yml` run (`testGithubDebugUnitTest`, both release builds, wear,
   `helper-tests`), watched with `gh pr checks --watch`. Then the Codex code review.

**Phone check.** It runs after merge, tag and `update_buddy` (fork.4). Each line stays `pending` in `verify.md` until
it is seen, and the feature isn't verified until all are.
1. Chat options → Approvals shows four rows. Pick Bypass: the line reads "Approvals · Bypass" in the accent colour.
2. In that chat, these go through with no card, each noted ("✓ Approved by Bypass…"):
   - `buddy_settings audio_device=auto`;
   - `confirm "Delete the test screenshot"`;
   - a Send in a messaging app on Hard (a message to yourself);
   - `phone_shell pm list packages` (if phone commands are on);
   - `clipboard_read` after copying a web link.
3. Smart reviewer: put an app on Smart and ask for a step the rules don't catch (for example "Mark all as read" in
   Gmail). If the reviewer asks, there is no card and the step is noted; if it allows, nothing shows. Then, in a shop
   app on Smart, tap "Continue" or "Proceed" on the cart page: if the reviewer's reason names the order or the payment,
   a card shows (deny it). Record what the reviewer did either way.
4. Still in that chat, these card, and you deny each one:
   - `confirm "Buy the cheapest one"`;
   - `buddy_settings service/<id>/key` with a key_file;
   - `buddy_settings chat/<other chat>/approvals=bypass`;
   - `buddy_settings app/<some shop app>=trust`;
   - `buddy_settings button/<a bank app>/Send=allow`;
   - `clipboard_read` after copying a password from the password manager.
5. Install without a card: the orchestrator publishes `2.3.11-fork.5` (version only). In the Bypass chat,
   `update_buddy install=true` shows no Buddy card and notes the step, and Android's installer asks (tap Update).
   Buddy restarts on fork.5.
6. Switch the chat to Routine allowed and set `read_aloud`: a card shows (019's skip is gone).

## Risks
- **Prompt injection in a Bypass chat.** A message or page that tricks the agent now meets no card for non-money,
  non-secret steps: deleting, sending, installing, phone commands, MCP servers, skills, schedules. That is the trade
  the brief asks for, and the mode's info text says so. What still stands: the money and secret cards, the hard
  blocks (Buddy's screens, password fields, `SHELL_NO`), the cards on loosening settings, and each step noted in the
  chat.
- **"Recognisably" is a heuristic.** These go through in Bypass:
  - **phone_shell scripts and encoded commands.** The check reads the command line only. `sh /sdcard/x.sh` or
    `base64 -d … | sh` can read a key file on shared storage with no card. Q1 asks Nicholas.
  - **A money step that Ask cards, but whose card names no money or finishing word, in an app or site without a
    money name.** Example: "Send" in a bank app called "Chase" (`moneyApp` tests names only). Upstream's Routine
    allowed has the same limit (`Guard.sends` excludes only `moneyApp` apps).
  - **A copied password without the sensitive flag** (an older password manager, or a password typed into a note and
    copied). Password fields themselves are never typed in any mode.
  - **Secret words are English only.** Key-file names and the sensitive flag don't depend on language.
- **Not gaps in Bypass:** a money step that Ask doesn't card either, such as a plain "Continue" on Hard (not a risky
  label, and Hard has no reviewer: `Tools.java:1761-1776`), or anything in a Trust app. Bypass never shows fewer
  money cards than Ask.
- **False positives** (a draft mentioning pay, "transfer files", `$1` in awk, a "Confirm" for a delete) show a card,
  which is safe. Every `chat/<id>/approvals` change and every never-ask button cards in Bypass, even harmless ones.
- **`approval()` now accepts one answer that isn't a card click (`BYPASSED`).** It is returned only from
  `askConfirm`, only in a Bypass chat, and only for a caller that passed `bypass=true`. T3, T4 and code review pin
  that down.
- **Scheduled runs of a Bypass chat bypass too.** That matches upstream, where Routine allowed applies to the chat's
  scheduled runs. Notification-rule runs never bypass.
- **Downgrade.** An older Buddy that reads `"bypass"` behaves as Ask (except that `firstUse` stays quiet) and labels
  the line Ask. Nothing breaks.
- **Upstream sync.** The change is small opt-in edits in BuddyService, Tools, BrowserTools, PhoneTools,
  PhoneToolActivity, ChatActivity and Ui, plus fork files. If upstream adds a fourth mode or renames `askConfirm`,
  the sync's Review step checks 020's done-when.

## Open questions
- **Q1 (for Nicholas, through the orchestrator, as review F4 asked).** In Bypass, `phone_shell` skips its card unless
  the command line names a secret, a key file or money. A script or an encoded command can still read a key file
  with no card.
  - A (recommended, as written): keep this. The brief lists phone_shell among the no-card steps.
  - B: keep phone_shell's card in Bypass. This is a one-line change: don't opt in.
- To show Nicholas in the PR (each is a one-line flip):
  - `default_agent` and `default_model/*` don't card, though they can point chats at a pay-per-use API service;
  - every approvals-mode change, never-ask button, chat allowed app, Trust and `builtin_risky_words=off` cards in
    Bypass;
  - "Confirm" and "Submit" count as money words.
