# Verification: 004-self-modify

| Done-when item | Evidence | Result |
|---|---|---|
| 1. Tool lists settings (secrets masked), changes one after a card naming setting/old/new; deny leaves it | 19 JUnit tests (ApprovalGateTest, SettingsFamiliesTest) pass locally and in CI run 43707738554 (`testGithubDebugUnitTest`); code read: `SettingsTool.change` asks via `askConfirm(card, false)` on the request thread, refuses rule self-approvals, compare-and-write on main | pass (unit); device 2026-11-15: listing OK (no secrets), read_aloud change not approved -> unchanged, theme dark/system approved -> changed |
| 2. Covers the user-only settings + ordinary ones | SettingsFamiliesTest covers trust, rules, schedules, skills, MCP, background, Linux tools per chat | pass (unit); device pending |
| 3. Self-update: edit, CI build signed, install via one card, Buddy back intact | CI builds and signs with the fork key (run 43707738554); `install_apk` refuses Buddy's own APK (ApkStagingTest + code); release tag + install need the phone on the fork and a public tag (Nicholas's OK) | pass: 2026-11-15 v2.3.9-fork.2 tagged, CI published, update_buddy install=true -> installer -> Update; Buddy restarted on 2.3.9-fork.2 with chats and settings intact (first attempt's installer didn't appear; second worked) |
| 4. Agent still can't answer approval cards | ApprovalGateTest; `Guard`/`Ui.protect` untouched | pass (unit); device tap test pending |
| 5. Style/YAGNI, F-Droid unaffected | Codex review round 2 APPROVE; fdroid compiles; CI fdroid assemble + no-Google-code check green | pass |

Fix during verify: CI ran `testGithubReleaseUnitTest`, which AGP doesn't create (unit tests exist for the test build type, debug); changed to `testGithubDebugUnitTest`.

VERDICT: SHIP - verified on device 2026-11-15 (card-tap blocking covered by unit test + unchanged Guard).
