# Plan: 019-settings-no-card-routine

Research was skipped (brief), so the facts below come from the code, with `path:line` on each.

## Goal
In a chat whose approvals mode is "Routine allowed" (`Session.approvals == "routine"`), an ordinary `buddy_settings`
change (audio_device, read_aloud, voice, appearance) goes through without an approval card, and the chat notes it the
way Routine allowed notes other steps. Sensitive changes still card: keys, permissions and trust, safety and approvals
(the approvals modes themselves included), Linux tools, MCP servers, and update installs. Ask and Always ask chats
are unchanged: every change cards. Done when: (1) an ordinary setting changes with no card and a note in a Routine
allowed chat; (2) the sensitive ones still card there; (3) Ask and Always ask still card everything; (4) tests for
the split, CI green, Codex review approves.

Non-goals (brief): changing upstream's Routine allowed rules for phone actions; letting the agent answer cards; the
F-Droid flavor (`buddy_settings` is github-only, `Tools.java:325`). Also not in scope: upstream's Approvals sheet
text (`chat_approvals_routine_info`, 18 locales), new settings, and any change to `update_buddy`.

## Design

**Where the skip happens.** Today every change goes through `SettingsTool.change` (`SettingsTool.java:1436`), which
asks `approval(asker, card)` (`:1426`) and writes only on the user's own APPROVED. `change` gets one more argument,
`Consumer<String> routine`: null means "card every change" (what the 5-argument form passes, so existing callers and
tests stay as they are). When `routine` is non-null and `!sensitive(s.name)`, the card step is skipped. Everything
else stays: the check, the snapshot, `prepare`, and the compare-and-write on the main thread. Only after a write that
succeeded, `change` calls `routine.accept(card)`, where `card` is the exact text the card would have shown. The agent
gets the usual `Changed <name>: <old> → <new>…` line plus ` (Routine allowed: no card, noted in the chat)`. `approval()`
does not change, so an answer that isn't the user's (AUTO_APPROVED, RULE_APPROVED, NOT_NOW) still writes nothing on
the card path, and `update_buddy` keeps its card: it calls `approval()` directly (`Tools.java:2355`), not `change`.

**Which chat counts as Routine allowed.** The calling chat (`here`, read from `McpServer.CALLER_CHAT` in `call`,
`SettingsTool.java:1514`) is routine when `routineChat(ChatHub.get(svc).approvalsFor(here), Triggers.ruleRun(here))`
holds, with `static boolean routineChat(String approvals, boolean ruleRun)` = `"routine".equals(approvals) && !ruleRun`.
That is the test upstream's Routine allowed uses for phone steps (`Guard.runRule`, `Guard.java:567-574`).
`approvalsFor` already gives `""` for a helper or sub-agent, for an unknown session, and for no chat at all (Remote
Control or a terminal), all of which card (`ChatHub.java:641-644`). Both calls are safe from any thread
(`Triggers.java:197,793`). Always ask (`"strict"`) and Ask (`""`) give null, so nothing changes for them.

**The note.** In `call`, the `routine` consumer posts to the main thread
`ChatHub.note(chat, Bubble.Kind.STEP, getString(R.string.run_rule_approved, card))` on the calling chat, if the chat
still exists. That is the line upstream writes for a step its run rule let through (`Tools.java:1836`, "Approved by
your run rule: …"), so it comes already translated. It also matches the card path, which notes "✓ Approved: <card
text>" (`Bubble.java:2684`). There is no new string. Nothing is noted when nothing was written: `Already …`, `Not
changed: …`, a stale snapshot, or a `set` that throws.

**The one predicate: `static boolean sensitive(String name)`.** It is `!ORDINARY.contains(name)`, where `ORDINARY` is
a package-visible set of exact names. It fails safe: a setting that upstream or a later fork feature adds cards until
someone adds it to the list. Because every family name (`app/…`, `chat/…`, `mcp/…`, `service/…`, `button/…`,
`rule/…`, `skill/…`, `default_model/…`, `reviewer_model/…`) holds an id, none of them can be on an exact-name list,
so every family stays carded with no prefix rules.

`ORDINARY` (17 names, all scalars from `scalars()`): how Buddy looks and sounds, nothing more.
- Look: `chat_bubble` (cards still show with it off, `Prefs.java:250`), `tap_action`, `panel_size`, `theme`,
  `accent`, `surface`, `face`.
- Voice: `read_aloud`, `voice_input`, `voice_output`, `chatgpt_voice`, `audio_device`, `voice_languages`,
  `assist_mode`, `live_continue`, `pause_media`.
- Notices: `update_notices` (only the notification; installing still goes through `update_buddy`'s card).

Everything else stays carded. Mapped to the brief:
- keys (011): `mcp/<id>/key`, `service/<id>/key`, and the rest of the API keys screen (`service/<id>[/name|/models]`).
- permissions and trust: `app/<pkg>`, `chat/<id>/allowed/<pkg>`, `phone_commands`, `agents_tap_buddy`, `ask_new_apps`.
- safety and approvals: `reviewer`, `reviewer_model/*`, `builtin_risky_words`, `button/*`, `chat/<id>/approvals`,
  `phone_commands_ask` (read-only anyway), and `voice_cards`, which answers approval cards by voice
  (`Prefs.java:327`).
- Linux tools: `linux_tools_new_chats`, `chat/<id>/linux_tools`, and `download_server` (where Buddy's Linux gets its
  software).
- MCP servers: all of `mcp/<id>`. Add, remove and on/off share one name, and telling them apart would mean reading
  the value.
- Carded beyond the brief's list, by the same test ("does it change what agents may do, what runs without the user
  or out of sight, or what gets billed?"):
  - `rule/*` and `notification_rules`: rules act on others' messages, with their own approval modes.
  - `chat/<id>/schedule`: unattended runs.
  - `chat/<id>/background` and `background_new_chats`: the agent works on its own display, out of sight.
  - `skill/*`: instructions every chat's agent follows.
  - `background_browser`: a new tool and a 300 MB download.
  - `default_agent`, `live_agent`, `default_model/*`: an agent or model can point new chats at a pay-per-use API key
    service.

  These are "still card", which is today's behaviour, so carding them takes nothing away. Moving one later is a
  one-word change in `ORDINARY`.

**Telling the user and the agent.**
- The tool description (`SettingsTool.java:1488-1507`) and the class comment (`:22-27`) say that in a chat set to
  Routine allowed, ordinary settings change without a card and are noted in the chat, while keys, permissions,
  safety and approvals, Linux tools, MCP servers and the other sensitive ones still ask.
- The `chat/<id>/approvals` card spells out each mode's effect (010's decision). The routine effect (`effect()`,
  `:788-793`) gains "ordinary Buddy settings (voice, audio, appearance) change without a card too", so a user who
  approves Routine allowed through the tool knows this.
- The fork's notes that say "one approval card per change" become false and get the exception: `AGENTS.md:25` and
  the fork parenthetical in `docs/GUIDE.md:205`.

Alternatives considered:
- An Asker that answers "approved by routine" through `approval()`: rejected. `approval()` would then accept an
  answer that isn't the user's, the exact thing 004's gate test forbids (`ApprovalGateTest.java:66-71`).
- Going through `confirm` / `Guard.runRule`: rejected. Those are tied to the front app and `routineText` words, and a
  setting isn't in any app.
- A list of sensitive names (deny-list): rejected. A new setting would skip the card by default.
- A `routine` flag on each `Setting` or `Scalar.ordinary()`: rejected. It spreads the classification over about 20
  constructors and needs `svc` to test. One name set is one place to read and test.
- A new note string: rejected. `run_rule_approved` is upstream's Routine allowed wording and is already translated.
- Changing upstream's `chat_approvals_routine_info`: rejected. It is in 18 locales, and changing it means a sync
  conflict each time. The fork's docs carry the exception instead.

## Steps
1. **Predicate.** In `SettingsTool.java`, add `static final java.util.Set<String> ORDINARY` (the 17 names),
   `static boolean sensitive(String name)` and `static boolean routineChat(String approvals, boolean ruleRun)`, next
   to "the gate" (`:1423`). Add `app/src/test/java/app/buddy/assistant/SettingsRoutineTest.java` with T1 and T2.
2. **Gate.** `change(Setting, String, String, Asker, Executor, Consumer<String> routine)`. The current 5-argument
   form delegates with `null`. Skip `approval()` when `routine != null && !sensitive(s.name)`. After a successful
   write (the `Changed …` result), call `routine.accept(card)` and add the suffix. Add T3 to T6. `ApprovalGateTest`
   and `ChatApprovalsSettingTest` pass unchanged.
3. **Wiring.** In `call` (`:1535-1538`), build `routine`: null unless
   `routineChat(ChatHub.get(svc).approvalsFor(here), Triggers.ruleRun(here))`. Otherwise
   `card -> svc.runOnMain(() -> { Sessions.Session c = chat(svc, here); if (c != null) ChatHub.get(svc).note(c,
   Bubble.Kind.STEP, svc.getString(R.string.run_rule_approved, card)); })`. Pass it to `change`.
4. **Texts.** Update the tool description, the class comment and `effect("routine")` in `SettingsTool.java` (T7),
   `AGENTS.md:25`, and the fork parenthetical in `docs/GUIDE.md:205`. Add one sentence each, no new sections.
5. **Version** (the orchestrator's release step, `AGENTS.md` "Updating Buddy"): `BuildInfo.java` →
   `2.3.11-fork.3`, `VERSION_CODE` = max(fork 222, upstream) + 1.

## Test plan
These are JVM tests in the new `SettingsRoutineTest`. Each uses a local `Fake` setting with a name it is given (a
field value and a write counter, like `ApprovalGateTest.Fake`), the card format `CARD` from `ApprovalGateTest`, and
`Runnable::run` as the main executor.

| # | Test | Proves |
|---|---|---|
| T1 | `sensitive()` is false for each of the 17 `ORDINARY` names. It is true for the brief's sensitive names (`linux_tools_new_chats`, `reviewer`, `builtin_risky_words`, `phone_commands_ask`, `voice_cards`, `agents_tap_buddy`, `ask_new_apps`, `phone_commands`, `download_server`) and for one family name of each kind (`app/com.whatsapp`, `chat/c1/approvals`, `chat/c1/allowed/com.whatsapp`, `chat/c1/linux_tools`, `chat/c1/background`, `chat/c1/schedule`, `mcp/notes`, `mcp/notes/key`, `service/deepseek`, `service/deepseek/key`, `button/*/Pay`, `rule/new`, `skill/notes`, `default_model/claude`, `reviewer_model/claude`), plus an unknown `some_future_setting` (fails safe). Every `ORDINARY` name is a name in `SettingsTool.scalars(null)`, so a typo can't hide | the split, done-when 1 and 2 at the predicate |
| T2 | `routineChat("routine", false)` is true. `routineChat("", false)`, `routineChat("strict", false)` and `routineChat("routine", true)` (a rule's run) are false | only Routine allowed skips; Ask, Always ask and rule runs don't (done-when 3) |
| T3 | `Fake("audio_device", "phone:2")`, change to `auto` with a non-null `routine` and an asker that throws `AssertionError("no card")`. One write, the value is `auto`, the result starts with `Changed audio_device: phone:2 → auto` and ends with the Routine allowed suffix, and `routine` received exactly `Change Buddy's setting audio_device\nNow: phone:2\nNew: auto`. The same for `read_aloud` off → on | done-when 1: no card, and the note carries the change |
| T4 | For `app/com.x`, `chat/c1/approvals`, `mcp/notes/key`, `linux_tools_new_chats`, `reviewer` and `voice_cards` with a non-null `routine`: the asker is called exactly once. Answers `DENIED: no`, `BuddyService.AUTO_APPROVED`, `BuddyService.RULE_APPROVED` and `BuddyService.NOT_NOW` each give zero writes, a result starting `Not changed. ` and no call to `routine`. `APPROVED by the user. Go ahead.` gives one write and still no call to `routine` | done-when 2: sensitive changes card in a Routine allowed chat, and only the user's yes writes |
| T5 | Ordinary `read_aloud` with `routine == null` (what `call` passes in Ask and Always ask chats): the asker is called once with the card. DENIED gives zero writes; APPROVED gives one | done-when 3 at the gate |
| T6 | In a Routine allowed chat, `routine` is never called and nothing is written when: the value is already set (`Already read_aloud = off`); the value is bad (`maybe`, `Not changed: …`); the value changes between snapshot and write (a `Fake` whose `prepare` edits it: `changed while the card was open`); or `set` throws (`Not changed: …`) | the note means "changed", nothing less |
| T7 | `SettingsTool.approvals(...)` card for `routine` contains `ordinary Buddy settings` and still `go ahead in every app` | the user approving Routine allowed sees the new effect |

Done-when item by item:
- **(1) No card, with a note:** T1, T2 and T3. The `call` wiring (step 3) is checked by code review. On the phone:
  see the phone check below.
- **(2) Sensitive still cards:** T1 and T4. Update installs: `update_buddy` calls `approval()` itself
  (`Tools.java:2355`). The diff must not touch it, which code review checks, and `ApprovalGateTest` keeps proving
  `approval()`.
- **(3) Ask and Always ask unchanged:** T2 and T5. `ApprovalGateTest` and `ChatApprovalsSettingTest` pass unchanged.
- **(4) Tests, CI, Codex:**
  - CI is the evidence: the PR's run of `.github/workflows/build.yml` (`testGithubDebugUnitTest`, both release
    builds, wear, `helper-tests`), watched with `gh pr checks --watch`.
  - Locally, the Coder may run the tests with javac and JUnitCore against the android-all jar, as in 004 and 005.
  - The Codex code review closes this item.
- **Phone check (after merge, tag and `update_buddy`; until then `pending` in `verify.md`):**
  1. In a chat set to Routine allowed, set `audio_device` to `auto` and toggle `read_aloud`. No card appears, and the
     chat shows "Approved by your run rule: Change Buddy's setting …" for each.
  2. In the same chat, `app/<pkg>`, `chat/<this>/approvals` and `linux_tools_new_chats` each show a card. Deny them.
  3. Switch the chat to Ask and toggle `read_aloud`: a card. Do the same in Always ask: a card.

## Risks
- **The classification is a judgment call.** Nicholas may want `default_agent`, `live_agent`, `default_model/*`,
  schedules, background, `background_browser`, `download_server` or skills to go without a card. All of them card
  today, so this plan only leaves them as they are. Moving one is a one-word change in `ORDINARY` plus one line in
  T1.
- **A prompt-injected agent in a Routine allowed chat can flip ordinary settings without a card.** For example,
  `read_aloud` on in public, or the audio routed to the speaker. That is the trade the brief asks for. Each change is
  noted in the chat, and the reach is limited to look and sound.
- **`run_rule_approved` says "run rule".** The same wording appears for Routine allowed phone steps
  (`Tools.java:1836`), so it is consistent. The card text inside it is multi-line, as in the card path's "✓
  Approved:" note.
- **Upstream sync.** The changes are in fork files (`SettingsTool.java`, a new test, and fork lines in AGENTS and
  GUIDE). No upstream string or class changes, so there is little new conflict surface.

## Open questions
None blocking. One to show Nicholas in the PR: the carded-beyond-the-brief list above (agents and models, schedules,
background, browser, download server, skills, rules). They card now, and moving any of them is a one-word change.
