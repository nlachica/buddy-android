# Research: 010-settings-chat-approvals

## Summary
- Storage: `Sessions.Session.approvals`, a `volatile String`: `""` = Ask, `"routine"` = Routine allowed, `"strict"` = Always ask; saved in the session JSON (`Sessions.java:126`, `:365`, `:401`).
- The one write path is `ChatHub.setApprovals(ses, choice)` (`ChatHub.java:573`). It calls `Guard.forget(ses.id)` if the choice changed, sets the field, saves, and calls `changed()`. Do not set the field directly: that skips the forget and the cache refresh (this is the brief's "cached approvals refresh").
- The sheet only offers the control when `ChatHub.approvalsEditable(ses)` holds: top-level chat (`parent.isEmpty()`) and not a notification-rule chat (`!Triggers.ruleChat(app, id)`) (`ChatHub.java:569`, `ChatActivity.java:1059`, `:2030`).
- `SettingsTool.find()` already gives `chat/<id>/{linux_tools,background,schedule}` for top-level chats only (`SettingsTool.java:1221-1233`). Nothing in it mentions approvals. The 4-part `chat/<id>/allowed/<pkg>` branch is the other chat family (`:1235`).
- The `Scalar` + `chatSwitch` pattern fits. It needs a `Scalar` with named choices that map to the stored `""`. 005 settled this: `""` is read as "delete" by `change()`, so the name `ask` (not `""`) must be the choice. Precedent: `default`/`auto` (`docs/features/005-settings-coverage/decisions.md`; `change()` at `SettingsTool.java:1091-1096`).
- `Scalar.check` matches ignoring case and returns the choice's own spelling (`:121-129`), so choices `ask`, `routine`, `strict` would work, with the setter mapping `ask` to `""`.
- The listing prints one line per chat (`chatLines`, `SettingsTool.java:1298-1313`) and a header sentence (`:1344`) naming the per-chat names. Both need the new item. The tool description (`:1152`) also lists "a chat's allowed apps" and should mention it.
- Existing tests drive `SettingsTool.change(...)` with `Runnable::run` and a fake Asker (`SettingsCoverageTest.java:21-35`), and construct family rows from factories with in-memory get/set (`allowedApp` tests, `:460-510`). A factory such as `approvalsRow(chat, get, set)` gives JVM tests with no phone.
- The approvals mode loosens or tightens the gate. `routine` makes `Guard.runRule` auto-approve routine steps in every allowable app; `strict` makes it return false even for the chat's allowed apps and makes Trust-mode apps ask (`Guard.java:475-487`, `Tools.java:852`, `:1850`). The setting is already behind the approval card, since `change()` always asks (`SettingsTool.java:1098`).

## Repo map
- Docs: `docs/features/010-settings-chat-approvals/brief.md` (goal; done-when: lists the mode per chat; sets it after an approved card; denied changes nothing; unit tests; CI green). There is no `docs/decisions/` dir.
- Upstream feature: strings `chat_approvals*` at `app/src/main/res/values/strings.xml:506-515` (labels Ask / Routine allowed / Always ask; `approvalsName(c, choice)` at `Ui.java:366`). Reuse the choice-to-label mapping in the card note if wanted, but `SettingsTool` is in a JVM-testable class and uses plain strings (no `R` strings in rows).
- `ChatActivity.approvalsSheet()` `:1058-1090` is the UI doing the same thing: `hub.setApprovals(ses, ch[0])` at `:1081` for `{"", "routine", "strict"}`.
- `SettingsTool.java`: `chat(svc,id)` helper `:481` (`ChatHub.get(svc).sessions.find(id)`); `chatSwitch` `:672`; `allowedApp` `:694`; `find` `:1184` (chat switch branch `:1221`); `chatLines` `:1299`; `listing` `:1315`; `list()` description `:1145`.
- `Guard.forget(chat)` `Guard.java:388` clears a pending approval or plan for that chat.
- `Guard.runRule` `Guard.java:480`; `Tools.java:852` and `:1850` consume `approvalsFor`.
- Tests: `app/src/test/java/app/buddy/assistant/SettingsCoverageTest.java` (005; pattern for a new test class or added tests), 004's `ApprovalGateTest` and `SettingsFamiliesTest` (`docs/features/004-self-modify/verify.md:5`).
- Build/test: CI runs `./gradlew --no-daemon testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease` (`.github/workflows/build.yml:63`); `helper-tests` job runs `bash linux/test/run.sh` and `bash tools/upstream-sync-test.sh` (`:15-22`). Whether the Gradle build works on the phone is (unverified); 005's verify cites CI only.

## Prior art
- 004/005 shipped the same shape for `chat/<id>/allowed/<pkg>` and `linux_tools`/`background` (commits `4477718`, `08d0b12`; `docs/features/005-settings-coverage/decisions.md`). 005 deliberately excluded a chat's model, effort, keep-awake and incognito (its scope decision); approvals did not exist then (arrived with upstream 2.3.10, merged in PR #7 `d6aede8`).
- 005's review lesson: put anything that changes the card's meaning into the snapshot so a change made while the card is open makes the write stale (`allowedApp` puts `+scheduled` into the snapshot, `:696`). Approvals need no extra scope: `get` is just the field.
- Pipeline rule for sync review finding F1: from the brief (Codex sync review of PR 7).

## Constraints and risks
- Threading: `Setting` methods run on the main thread via `onMain`; `change()` does compare-and-write under `s.lock()` (`:1110-1118`). `ChatHub.setApprovals` saves the session and calls `changed()`, which the existing `setDev`/`setBackground` setters do on main too (as the `chatSwitch` usage shows).
- `""` stored value vs. the gate: `change()` treats a checked `""` as delete, and "Already" needs `old == null` (`:1096`). So the visible value must be a name (`ask`), and `get()` must map the stored `""` to `ask`. A missing chat must return `null` (as `chatSwitch` does, `:675`).
- Applicability: only top-level, non-rule chats. `find()`'s chat branch already requires `parent.isEmpty()` (`:1221`) but does not exclude rule chats. `approvalsEditable` also excludes `Triggers.ruleChat`. The row should refuse a rule chat the way the UI hides the line (`Triggers.ruleChat` needs a `Context`, so JVM tests need a `ready`/lambda seam). `chatLines` likewise should not claim a mode for a rule chat, or the listing should show it read-only. Decision for the Planner.
- Safety: an agent can ask to set its own chat to `routine`; the user's card is the only gate (same as `allowed/<pkg>`). The card text should say what each choice does (as `allowedApp.card` does at `:703-707`). Whether to add a rule against loosening mid-run is the Planner's call; brief non-goal says only that agents cannot change it without the card.
- Switching mode calls `Guard.forget(ses.id)` (`ChatHub.java:575`): going through the hub keeps this. A direct field write would leave an old approval live.
- `Session.approvals` is `volatile` but read from tool threads (`approvalsFor` uses a copy of the list, `ChatHub.java:547-557`); the tool's `chat()` uses `sessions.find` on the main thread. Fine.
- Out of scope per brief: any other settings; 005's exclusions stay.

## Answers to open questions
None (the Planner has not left any).
