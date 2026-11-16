# Plan: 010-settings-chat-approvals
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal

Feature 005's `buddy_settings` tool should list each chat's per-chat approval mode and change it. The mode comes from upstream 2.3.10's `Session.approvals`, with three choices: Ask, Routine allowed and Always ask. A change goes through the existing approval card (`SettingsTool.change`) and is written with `ChatHub.setApprovals()`. That call clears the chat's pending approval (`Guard.forget`) and refreshes the chat screens (`changed()`). This closes finding F1 of the Codex sync review of PR 7.

Done when (brief):
1. `buddy_settings` lists the mode for each chat.
2. It sets the mode after an approved card, and a denied card changes nothing.
3. Unit tests exist and CI is green.

Non-goals:
- From the brief: agents changing the mode without the user's card; any other setting.
- Also out (this plan):
  - Any change to the gate (`change()`, `approval()`), to `ChatHub`, or to the chat screen.
  - Naming the chat on the other chat rows' cards (`linux_tools`, `background`, `allowed/…`).
  - The F-Droid flavor (the tool stays github-only).
  - A `docs/GUIDE.md` sentence. The fork note at `docs/GUIDE.md:204` already says agents change settings through `buddy_settings` behind a card, and another fork line inside upstream's Approvals paragraph (`:205-208`) would be one more place for a future sync to conflict.
  - The release bump (`BuildInfo`), which the orchestrator does after the merge per AGENTS.md "Updating Buddy on the phone".

## Design

### One new chat row, `chat/<id>/approvals`, built like 005's `allowedApp`

The new row is a `SettingsTool.Scalar` made by a package-private static factory:

```java
static Scalar approvals(String chat, String title, String refusal, Supplier<String> get, Consumer<String> set)
```

- `get` returns the stored value (`""`, `"routine"`, `"strict"`), or null when the chat is gone.
- `set` takes the stored value.
- The factory maps between stored and shown values, so the mapping is covered by JVM tests with in-memory state. This is 005's pattern for family rows.
- `find()` only wires the factory to the session and the hub. `change()` is untouched, so the card, the user-only approval, the stale-write re-check and the denial path are 004's.

The row's behaviour:

- **Name:** `chat/<id>/approvals`. It goes in `find()`'s existing three-part chat branch (`SettingsTool.java:1221`), which already admits only top-level chats (`parent.isEmpty()`), the same as `ChatHub.approvalsFor`.
- **Values:** `ask`, `routine`, `strict`. These are the stored names, with `ask` standing in for the stored `""`. 005 decided that `""` reads as "delete" in `change()`, so the row needs a named choice (precedent: `default`, `auto`). Matching ignores case, through `Scalar.check`. `""` and anything else are refused, and the refusal lists the choices.
- **Helper:** `approvalsChoice(String stored)` maps `""` to `ask` and passes any other value (or null) through. It has two users: the factory's getter and `chatLines`.
- **`allowed()`:** `ask (Ask), routine (Routine allowed), strict (Always ask)`. The agent can map the user's words to a value, and Scalar's refusal text ("takes: …") shows the same.
- **`view()` is not overridden.** It must return the plain choice (`ask`, not `Ask`), because `change()` decides "Already …" with `v.equals(s.view(old))` (`SettingsTool.java:1096`). Labels and effects go only in `card()`.
- **Setter:** maps `ask` to `""`. In `find()`, the setter looks the chat up again by id (throwing `IllegalStateException("that chat is gone")` when it's missing, as `chatSwitch` does) and calls `ChatHub.get(svc).setApprovals(c, stored)`. It never writes the field directly, which would skip `Guard.forget` and `changed()`.
- **Note** returned by `set`: `" It applies from the chat's next step."` `approvalsFor` is read on every tool call, so the change takes effect at once.

**Card** (format: `Change Buddy's setting %1$s\nNow: %2$s\nNew: %3$s`):

- **Now:** the old choice with its label, plus the chat's title, for example `ask (Ask) in chat “Plan the trip”`. The card is shown in the calling chat, so without the title a user approving a change to another chat sees only an opaque id. That matters for the one switch that loosens approvals in every app.
  - The title is made one line: control characters become spaces, it is trimmed and cut to 60 characters with `…`. A renamed title isn't length-capped and could otherwise forge card lines.
  - A blank title is left out.
- **New:** the new choice, its label and its effect, in English like every other row's card text. The effects follow upstream's `chat_approvals_*_info` strings and `docs/GUIDE.md:205-208`. Add a code comment pointing at those strings.
  - `ask (Ask): each app follows its App permissions setting, and the chat's Allowed without asking list applies`
  - `routine (Routine allowed): until it's changed, routine steps go ahead in every app without a card (sending a message, or a step the reviewer would ask about); paying, buying, deleting, installing, passwords and security settings still ask, and so does everything in banking and payment apps, Settings and app stores`
  - `strict (Always ask): also in apps set to Trust, Buddy asks before sending, posting, buying or deleting, and the chat's Allowed without asking list doesn't apply`
- **Snapshot:** the stored mode only. Nothing else changes what the card means (unlike `allowedApp`'s `+scheduled`). A mode changed on the chat's Approvals sheet while the card is open makes the write stale, through `change()`'s re-check.

**Notification-rule chats.** The UI hides the control for them (`ChatHub.approvalsEditable`: top-level and not `Triggers.ruleChat`, `ChatHub.java:569`).

- `find()` passes `refusal = hub.approvalsEditable(c) ? null : "chat/<id> is a notification rule's chat: its runs follow the rule, so its approvals can't be set here"`.
- The factory applies the refusal through Scalar's existing `ready` hook (Scalar's "what the screen greys out" check), so every value is refused before any card.
- `read()` shows the line plus the refusal when there is one.

**Listing.**

- `chatLines` adds `, approvals <ask|routine|strict>` after `schedule …` for each listed chat, or `, approvals n/a (notification rule's chat)` for a rule chat.
- It gets a fourth parameter, `Predicate<Sessions.Session> editable`. `listing()` passes `ChatHub.get(svc)::approvalsEditable` (main thread, as the chat header uses it). Tests pass a lambda, because `Triggers.ruleChat` needs a `Context`.
- The sub-agent filter stays as it is.
- The header sentence (`:1344`) gains `chat/<id>/approvals (ask, routine: Routine allowed, strict: Always ask)`.
- The tool description (`list()`, `:1152`) changes "and a chat's allowed apps" to "and a chat's approvals (Ask, Routine allowed, Always ask) and allowed apps".

**Safety.** An agent may ask to set its own chat to `routine`. The card is the gate, as for `allowed/<pkg>` and 005's other loosening settings (005 decisions, "Settings that lower protection stay changeable…"). Nothing lets the mode approve its own change:

- The settings card is `svc.askConfirm(card, false)` (`SettingsTool.java` `call()`), so it never auto-approves. `canAuto` is false, and `Guard.runRule` is only consulted by the agent's `confirm` tool (`Tools.java:495`).
- `approval()` refuses `AUTO_APPROVED` and `RULE_APPROVED` answers.

No extra rule is added against loosening during a run.

### Alternatives considered (one line each)

- Write `Session.approvals` directly: skips `Guard.forget` and `changed()`, so it breaks the brief's "cached approvals refresh".
- A dedicated `Setting` subclass: Scalar already does choices, matching, `ready` and the card override, so it would add nothing.
- Values named after the labels (`routine_allowed`, `always_ask`) or accepting label aliases: the stored names match the code and upstream, and the labels in `allowed()` and the refusal already guide the agent.
- `""` as the value for Ask: `change()` reads `""` as delete (005 decision).
- Treating a rule chat as an unknown setting: an agent would be told the name doesn't exist with no reason given. The refusal says why.
- Omitting the title from the card: the user can't tell which chat they are loosening.

## Steps

Each step builds and keeps the unit tests green. Tests go in a new class
`app/src/test/java/app/buddy/assistant/ChatApprovalsSettingTest.java`, using 005's helpers pattern (a `CARD` format, `YES`, `edit()` through `SettingsTool.change(..., Runnable::run)`).

1. **The row.** In `app/src/main/java/app/buddy/assistant/SettingsTool.java`, add `approvalsChoice(stored)` and the `approvals(chat, title, refusal, get, set)` factory next to `allowedApp`. Its parts are the overridden `allowed()`, the refusal through `ready`, the `card()` with the one-line title, the setter mapping `ask` to `""`, `read()` with the refusal, and the effect texts with a comment naming the `chat_approvals_*_info` strings. Add the factory's tests (T1-T7 below) in the new test class.
2. **Wiring.** In `SettingsTool.find()`'s three-part chat switch, add `case "approvals"`. It computes the refusal from `ChatHub.get(svc).approvalsEditable(c)`, passes `c.title`, a getter that re-finds the chat and returns `c.approvals` (null when gone), and a setter that re-finds it and calls `ChatHub.get(svc).setApprovals(c, v)`. No test: `ChatHub`/`BuddyService` aren't JVM-constructible (JUnit only, `app/build.gradle.kts:91-92`). The code review and the phone check cover it.
3. **Listing and description.** In `SettingsTool.java`: `chatLines(b, all, here, editable)` with the `approvals` item, `listing()` passing `ChatHub.get(svc)::approvalsEditable` and the header sentence, and `list()`'s description. Update the one existing call in `app/src/test/java/app/buddy/assistant/SettingsFamiliesTest.java:260` to pass `c -> true`. Add T8 to the new test class.

## Test plan

JVM tests in `ChatApprovalsSettingTest`. Each row drives the factory with an `AtomicReference<String>` holding the stored value and a counting setter.

| # | Test | Proves |
|---|---|---|
| T1 | stored `""`, `routine`, `strict` read as `chat/c1/approvals = ask / routine / strict` with `(ask (Ask), routine (Routine allowed), strict (Always ask))`; the name is `chat/c1/approvals` | listing a chat's mode |
| T2 | `check`: `STRICT` gives `strict`, `Ask` gives `ask`; `""`, `always ask` and `on` are refused, with the refusal listing the choices | values; `""` isn't delete |
| T3 | stored `""` → edit `routine` with an approving asker: setter got `routine`, result starts `Changed chat/c1/approvals`, the card contains `in chat “Trip”` and `go ahead in every app`. Then edit `ask`: setter got `""`. Edit `strict`: card mentions Trust and the allowed list | sets after an approved card; ask maps to `""` |
| T4 | for each of `DENIED: no`, `CHANGES REQUESTED: “x”`, `BuddyService.NOT_NOW`, `BuddyService.AUTO_APPROVED`, `BuddyService.RULE_APPROVED`: zero writes, the result starts `Not changed`, the stored value is unchanged | a denied card (or an answer not from the user) changes nothing |
| T5 | stored `""`, edit `ask`: returns `Already chat/c1/approvals = ask` and the asker is never called | no needless card (`view` stays the plain choice) |
| T6 | the asker changes the stored value (the sheet) or makes it null (chat deleted) while the card is open: result `changed while the card was open`, zero writes | stale write refused |
| T7 | a non-null refusal: `ask`, `routine` and `strict` are all refused; `edit` returns `Not changed: <refusal>` with no card; `read()` shows the refusal. A title with `\n` and `\r` and over 60 characters gives a card of exactly three lines, with the title cut and ending in `…` | rule chats; the card can't be forged by a title |
| T8 | `chatLines` over four top-level chats (approvals `""`, `routine`, `strict`, and one where `editable` returns false) and one sub-agent: the lines contain `approvals ask`, `approvals routine`, `approvals strict`, `approvals n/a (notification rule's chat)`; the sub-agent is absent | the listing shows the mode per chat |

How each done-when item is proven:
- **Lists the mode per chat:** T1 and T8. On the phone, the `buddy_settings` listing shows `approvals …` on each chat line.
- **Sets it after an approved card:** T3. The `find()` → `setApprovals` wiring is checked by code review, and on the phone (below) the chat's header line changes.
- **A denied card changes nothing:** T4 and T6. On the phone, one denial leaves the line as it was.
- **Unit tests; CI green:** the PR's `pull_request` run of `.github/workflows/build.yml` (`testGithubDebugUnitTest`, both release builds, the wear build, `helper-tests`), watched with `gh pr checks --watch`. Gradle on the phone is unverified (research), so CI is the evidence.
- **Phone check** (after the orchestrator's release and `update_buddy install=true`):
  1. `buddy_settings` with no arguments shows `approvals ask` on this chat's line.
  2. Set `chat/<this>/approvals` = `routine`. The card shows the chat's title and the effect. Approve, and the header line reads "Approvals · Routine allowed".
  3. Set it to `strict` and deny. The line stays on Routine allowed.
  4. Set it back to `ask` and approve.
  5. `buddy_settings name=chat/<a rule chat id>/approvals value=ask` is refused with no card (if a rule chat exists).

## Risks

- **The card's effect texts are hand-copied English.** If a later upstream sync changes what a mode does, the card can go stale. Mitigations: the code comment names the source strings, and AGENTS.md's sync Review step checks each fork feature's done-when, 010's included. The texts are not localized, like every other row's card text.
- **Upstream adds a fourth mode:** `check` refuses it, `get` and `view` show the raw stored value, and the card still works. A new value needs a one-line change here.
- **A chat becomes a rule chat after a mode was set:** upstream behaviour. The UI hides the line while `approvalsFor` still applies the stored value in the user's own runs. The tool refuses changes there like the UI does, and doesn't change that behaviour.
- **`chatLines`' signature changes** and its one test caller must follow (step 3). There are no other callers (`grep -n chatLines`).
- **Threading:** `find()`, the snapshot and the write run on the main thread (`call()` and `change()`'s `onMain`), where the Approvals sheet writes too. `setApprovals` saves and calls `changed()` on main, as `ChatActivity` does. `check()`, including `ready`, runs on the tool thread and reads only the captured refusal string.

## Open questions

None blocking. A possible follow-up, not in this brief: name the chat on the other chat rows' cards too (`linux_tools`, `background`, `allowed/<pkg>`).
