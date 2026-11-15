# Plan: 004-self-modify
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal

In the fork, an agent in a Buddy chat can (1) read Buddy's own settings and change them through one direct tool,
including the ones upstream keeps user-only (per-app trust, notification rules, scheduled tasks, skills, MCP servers,
background mode, Linux tools per chat) and some ordinary ones. Every change goes through one approval card that names
the setting and its old and new value. Denying leaves the setting as it was, and secrets are never shown. (2) The agent
can change Buddy's code in the fork, have the fork's CI build and sign it with Nicholas's key, and install it as an
update of itself after one approval card, with chats and settings intact.

Done when (brief): the tool lists settings (secrets masked) and changes one after a card naming setting/old/new, and a
denial leaves it unchanged; it covers the named user-only settings plus ordinary ones; self-update from a chat works
and Buddy comes back up intact; tests show the agent still can't answer approval cards; the code fits Buddy's style
and YAGNI, and the F-Droid build is unaffected or the difference is recorded.

Non-goals (brief and orchestrator): the agent answering, skipping or auto-approving cards, or changing how cards work
(the tool's own card and the install card have no setting that turns them off); reading or writing API keys, tokens
and MCP keys; tapping Buddy's guarded screens (`Ui.protect` and `Guard` stay as they are); anything new in the F-Droid
flavor; a plugin system; silent installs (one tap less, see Risks); every one of Settings' ~100 controls (the
table below is the set; others can be added the same way later).

## Design

### A. `buddy_settings`: one tool, github flavor only

**Shape.** One MCP tool, `buddy_settings`, with two optional string arguments, `name` and `value`:
- no arguments: every setting below, one line each: `name = current value  (allowed values)`. Families list their
  items: chats (newest 30, top-level only, the caller's marked "this chat"), apps with a user choice, rules, skills,
  MCP servers. Long values are summarised (a rule's app and instruction head, a skill's description).
- `name` only: that setting in full (the rule's or schedule's JSON, the skill's SKILL.md, the app's mode and why).
- `name` + `value`: validate → **card** → apply → reply `Changed <name>: <old> → <new>` (+ when it takes effect).
  `value: ""` deletes an item (rule, schedule, skill, MCP server) or resets an app to Buddy's suggestion.

The description says: this is how to change Buddy's settings (not by tapping its screens); Buddy shows the user an
approval card itself, so don't call `confirm` first; keys and tokens are never shown or changed here. That keeps
agents from refusing it under the system prompt's "never tap Buddy's screens" (upstream issue #33 lesson, research).
`linux/system-prompt.md` is left alone: the F-Droid build shares it, and "never tap Buddy's screens" stays true.

**Where.** New `SettingsTool.java` next to `PhoneTools.java`, in the same pattern: `static void list(JSONArray t)` for
the schema and `String call(JSONObject a)` for the work. `Tools.java` wires it in (list + a `case` in `call0`), only
when `!Flavor.FDROID`, like `update_buddy` (`Tools.java:298`). `call0` also refuses it when `Flavor.FDROID`, so an
unlisted call can't reach it.

**The gate (one code path for every change).** `change(setting, value, asker)`:
1. Refused before anything else: a notification rule's run (`ruleRun()`, the same reply text as installs,
   `Tools.java:539`); a helper (`HELPER_NO` gets `buddy_settings`; `askConfirm` also denies helpers, `BuddyService:880`);
   unknown name; read-only setting; invalid value; value equal to the current one ("Already <value>", no card).
2. `svc.askConfirm(card, false)` (`BuddyService.java:877`): `canAuto=false` means no rule countdown and no "Don't ask".
   It **never** consults `Guard.autonomous`, `Guard.runRule` or `Guard.takeApproval`, so a trusted app or an earlier
   `confirm` can't stand in for this card (research: `Tools.java:479-484`, `Guard.java:340-360`).
3. Applies only when `answer.startsWith("APPROVED") && !BuddyService.selfApproved(answer)` (`BuddyService.java:840`).
   That covers the user's approval and nothing else: countdown and rule approvals are rejected even though they
   can't occur with `canAuto=false`. Otherwise it returns `"Not changed. " + answer` (same as `phone_shell`, `Tools.java:641`).

`asker` is a one-method nested interface (`String ask(String card) throws InterruptedException`). Production passes
`t -> svc.askConfirm(t, false)`, and the unit test passes a fake. Its two uses are this method and the test that
proves the gate (done-when 4).

Card: new string `tools_card_setting` = `Change Buddy's setting %1$s\nNow: %2$s\nNew: %3$s`. Values are shown whole
(the card scrolls, `Bubble.cardScroll`). Nothing is cut, so nothing can hide past a limit. The tool refuses values over
20,000 characters. For JSON items (rule, schedule, MCP server), Now/New list only the fields that differ
(`approval: ask` → `approval: now`); a new item lists all fields, a deletion shows `(deleted)`.

**What "the approval gate itself not changeable" means here.** (1) No setting controls the settings tool's card or
the install card: their only source is code. (2) `phone_commands_ask` ("ask before each phone command") is listed
read-only: it is the one switch that removes a Buddy-level card from an arbitrary shell. (3) Who can answer a card is
untouched: the card and bubble are overlay windows behind `Ui.protect`/`Guard.offLimits`, and `agents_tap_buddy`
doesn't reach them (`Ui.java:377-392`). Per-app trust and a rule's `approval` mode stay changeable, each on its own
card showing old → new, because the brief's done-when names them.

**Settings covered** (applied the way the UI applies them, same side effects, file:line of the UI handler):

| name | values | store / side effect |
|---|---|---|
| `linux_tools_new_chats` | on, off | `Prefs.setDevMode` (Settings, protected, `SetupActivity:853`) |
| `agents_tap_buddy` | on, off | `Prefs.setAgentsTapBuddy` (`SetupActivity:859`) |
| `background_new_chats` | on, off | `Prefs.setBackgroundDefault` (`BackgroundActivity:214`) |
| `phone_commands` | on, off | `Prefs.setPhoneShell` (`:223`); `on` refused until `Background.setUp` (pairing stays the user's) |
| `phone_commands_ask` | read-only | `Prefs.phoneShellAsk`: the approval gate, see above |
| `notification_rules` | on, off | `Prefs.setRulesOn` (`RulesActivity:144`) |
| `chat_bubble` | on, off | `setBubbleEnabled`; on → `HiddenNotice.cancel`; `svc.applyBubbleSetting()` (`SetupActivity:721`) |
| `read_aloud` | on, off | `setSpeakReplies`; off → `Voice.stopSpeaking()` (`:770`) |
| `voice_cards` | on, off | `setVoiceCards` (`:762`) |
| `tap_action` | panel, talk, app | `setTapAction` (`:734`) |
| `panel_size` | full, compact | `setPanelSize` (`:742`) |
| `theme` | `Theme.modes` values | `setThemeMode` (`:315`); screens pick it up when they next open |
| `update_notices` | on, off | `setUpdateNotices`; off → `Updates.cancel`, on → `setUpdateNotified("")` (`:782`) |
| `app/<package>` | hard, smart, trust, default | `Prefs.setAppSafety` (`AppPermissionsActivity:218`); `default` = null (Buddy's suggestion, `Guard.modeWhy`) |
| `chat/<id>/linux_tools` | on, off | `ChatHub.setDev` on the main thread (`ChatHub:368`); reply: from the chat's next message (`devArg` is read at run start, `ChatHub:783`) |
| `chat/<id>/background` | on, off | `ChatHub.setBackground` (`:470`); `on` refused until `Background.setUp` |
| `chat/<id>/schedule` | `Schedules.Plan` JSON, or "" | `ChatHub.setSchedule(ses, Plan.of(json))` (`:378`); `Plan.of` null → refused with the expected shape |
| `rule/<id>`, `rule/new` | `Rules.Rule` JSON, or "" | `Rules.put` / `Rules.remove`, then `Triggers.get(c).rulesChanged()` (`RulesActivity:444-445`); `pkg` required, `approval` ∈ ask/countdown/now |
| `skill/<name>` | full SKILL.md text, or "" | write `Skills.dir(c)/<name>/SKILL.md` (`Linux.write`), front matter `name` must equal `<name>` (a `Skills.slug`); "" → `Skills.delete` (card says the folder goes) |
| `mcp/<id>` | `{"url":…, "on":…}`, or "" | new: `McpServers.addressOk` + `free`, then after the card `discover(url, null)` + `save(c, s, null)` (keyless); existing: only `on` changes (`setOn`); a new `url` is refused (the server's key would follow it); "" → `McpServers.remove` |

Main-thread work (sessions, `ChatHub`) goes through a `FutureTask` posted to the main thread, copied from
`Schedules.agentTool` (`Schedules.java:630-646`). Secrets: the table holds no token or key. MCP servers list
`key: set|none` (masked), and `Prefs.token`, `bg_token`, `Secrets` and `signedAgents` are never read.

Deliberately not covered (YAGNI, or a flow the user does): sign-ins, API keys, models, remote session/SSH/Codex
remote (pairing, network services), backup/restore, background-mode pairing, App permissions' button lists, reviewer
and built-in list, a chat's "Allowed without asking", voice engines/language (need sign-in checks), accent/surface,
background browser (download). Each can join the table later with one row.

Research's unverified point is settled: the MCP servers, Notification rules, Scheduled and Skills screens are all
`protect()`ed (`McpServersActivity:57`, `RulesActivity:86`, `SchedulesActivity:49`, `SkillsActivity:51`), as GUIDE says.

Alternatives (one line each): two tools (get/set): the orchestrator asked for one. Raw `SharedPreferences` editor
(`getAll`/put by key): exposes internal state keys, skips side effects and caches (`McpServers.cache`), and its
cards would show unreadable JSON blobs. Reusing `confirm`: auto-approves in trusted apps. A user on/off switch like
`phone_shell`'s: not asked for, since the card per change is the gate. Code in `app/src/github` with an fdroid stub: two
files for what one `Flavor.FDROID` check already does.

### B. Self-update: repoint the existing updater at the fork and put a card before the installer

The smallest path reuses `update_buddy` and `Updates` as they are: download `buddy.apk` from the latest GitHub
release, check that the package matches and `versionCode` is higher (`Updates.java:96-100`), open Android's installer.
Three changes:
1. `Updates.REPO` / `API` → `https://github.com/nlachica/buddy-android` (`Updates.java:41,45`). Today they point at
   upstream, whose APKs can't install over a fork-signed Buddy (research). The daily "update available" check then
   follows the fork's releases too, which is the right behaviour for a fork install.
2. `Tools.updateBuddy(install=true)`: before `Updates.install`, `svc.askConfirm(getString(R.string.tools_card_update,
   r.version, BuildInfo.VERSION, r.notes), false)` with the same "user APPROVED only" check. A denial returns
   `"Not installed. " + answer`. The user-tapped sheet from the notification gets no card, because there the user
   started it.
3. `update_buddy`'s description: "this fork's GitHub release", plus one sentence on how an agent makes one (below).

**How the agent ships a change** (documented in the repo's `AGENTS.md`, new section "Updating Buddy on the phone"):
change the code in `/root/.buddy/work/buddy-fork` (or a fresh `gh repo clone`), normally through the feature
pipeline and a merged PR. Then raise `BuildInfo.VERSION` to `<upstream>-fork.<n>` and `VERSION_CODE` to
max(fork, upstream) + 1, commit, push, `git tag v<VERSION>` and `git push origin v<VERSION>`. The existing workflow
builds on `v*` tags, signs with `BUDDY_KEYSTORE_*` (secrets set on the fork) and publishes `buddy.apk` to the release
(`build.yml` "Publish GitHub release"). Then `gh run watch`, and `update_buddy install=true` shows the card, the
installer opens and the user taps Update. Buddy restarts: Android rebinds the accessibility service, chats and Prefs
survive an update (research: `ChatHub.java:70-80`), and alarms are re-armed on `MY_PACKAGE_REPLACED`.
`Updates.compare` already orders `2.3.7-fork.2` after `2.3.7-fork.1` and after `2.3.7`.

No CI change is needed for B. Alternatives: `gh run download` the branch artifact + `install_apk` (needs gh sign-in
and Linux tools in the chat, no version check, the artifact is not a release); on-device `build.sh` (the key lives
only in CI secrets); a silent one-tap `PackageInstaller` update with `USER_ACTION_NOT_REQUIRED` (more code, and
installer-of-record rules unverified on this phone); a CI-derived versionCode (touches Gradle and drifts from upstream).

### C. Flavors

Both A and B are reachable only in the github flavor. A is gated by `Flavor.FDROID`. B: `Updates` is only used
behind `!Flavor.FDROID` (`BuddyService.java:72`, `Tools.java:298-302`), and `fdroidUpdates()` with its upstream link
stays unchanged. The F-Droid APK's behaviour is unchanged; its bytes differ by the gated-off class and one string
constant. That difference is deliberate and recorded in decisions.md. CI's reproducibility check compares two
fdroid builds of the same source, so it is unaffected.

## Steps

1. **Test wiring + shell tripwire test.** `app/build.gradle.kts`: `dependencies { testImplementation("junit:junit:4.13.2") }`
   (the first dependency; test-only, never in the APK), plus `testOptions { unitTests.isReturnDefaultValues = true }`
   if the test needs it. `Tools.SHELL_NO` goes from private to package-private. New
   `app/src/test/java/app/buddy/assistant/ApprovalGateTest.java`: `SHELL_NO` blocks `input tap 1 2`,
   `/system/bin/input swipe …`, `cmd input tap`, `sendevent`, `monkey`, `uiautomator`, `am start -n app.buddy.assistant/…`,
   `settings put secure enabled_accessibility_services …`, and lets `pm list packages` and `dumpsys battery` through.
   `.github/workflows/build.yml`: add `testGithubReleaseUnitTest` to the build step's gradle command.
2. **`SettingsTool` core + scalar settings + wiring.** New `SettingsTool.java` (setting record: name, allowed
   values/help, get, set, describe; `list`, `read`, `change` with the gate above; the scalar rows of the table).
   `Tools.java`: list it when `!Flavor.FDROID`, add `case "buddy_settings"` (refuses in rule runs and in the F-Droid
   flavor), add it to `HELPER_NO`. `BuddyService.onToolActivity`: add it to the not-operating-the-phone list (`:554`).
   `ChatHub.describeTool`: a `buddy_settings` case (`hub_tool_settings`: "Buddy settings: %1$s", the name or "list").
   `res/values/strings.xml`: `tools_card_setting`, `hub_tool_settings`, English only. Tests in `ApprovalGateTest`
   (see Test plan 1, 2, 4).
3. **Per-app trust and per-chat settings.** `SettingsTool.java`: `app/<package>`, `chat/<id>/linux_tools`,
   `chat/<id>/background`, `chat/<id>/schedule` (main-thread `FutureTask`), and their listing lines.
4. **Rules, skills, MCP servers.** `SettingsTool.java`: `rule/…`, `skill/…`, `mcp/…`, the JSON field diff for
   their cards, and their listing lines.
5. **Self-update through the fork.** `Updates.java`: `REPO`/`API` → fork, plus the class comment. `Tools.java`:
   `update_buddy` description, and the card before `Updates.install` in `updateBuddy`. `strings.xml`: `tools_card_update`
   = `Install Buddy %1$s over %2$s?\n%3$s`.
6. **Docs.** `AGENTS.md` (repo): "Updating Buddy on the phone" (version rule, tag, CI, `update_buddy`).
   `docs/GUIDE.md:202`: one sentence saying that in this fork agents can change these settings with `buddy_settings`,
   each change on an approval card, and still can't answer cards.

## Test plan

| Done-when item | Proof |
|---|---|
| 1. List with secrets masked; change after a card naming setting/old/new; denial leaves it unchanged | **Unit** (`ApprovalGateTest`, fake setting + fake asker): `DENIED…`, `CHANGES REQUESTED…`, the 5-minute timeout text, `NOT_NOW`, `AUTO_APPROVED` and `RULE_APPROVED` leave the fake's value unchanged and its setter uncalled; `APPROVED by the user. Go ahead.` calls the setter once with the new value; the card text holds name, old and new; an unchanged value asks no card. **Device** (after the switch, phone MCP): list shows no `Prefs.token`/`bg_token` value and MCP keys only as `set`/`none`; change `read_aloud` → deny → still the old value; approve → new value, visible in Settings. |
| 2. Covers the user-only settings + ordinary ones | **Unit**: the setting names/families include `app/`, `chat/<id>/linux_tools`, `chat/<id>/background`, `chat/<id>/schedule`, `rule/`, `skill/`, `mcp/`, `background_new_chats`, `notification_rules`, `linux_tools_new_chats` and at least the ordinary rows of the table. **Device**: one approved change per family (trust for one app, a rule's `on`, a schedule's `paused`, a test skill created then deleted, a keyless MCP server switched off, this chat's Linux tools), each checked on its Settings screen. |
| 3. Code change → signed APK by fork CI → installed after one card; Buddy back up intact | **CI**: a `v…-fork.N` tag on the fork publishes `buddy.apk`; `apksigner verify --print-certs` shows the fork cert (`7cf74b84…`, as in 003's verify); `curl` of the fork's `releases/latest` API shows the asset Updates accepts. **Device** (after the switch): in a chat with Linux tools, the agent makes a trivial visible change, bumps the version, tags, waits for CI, calls `update_buddy install=true` → card → installer → Update; Buddy restarts on the new version (Settings › About); chat list, a test chat's messages and a changed setting are still there. Tagging publishes a public release, so it needs the orchestrator's confirm with Nicholas first. |
| 4. Approval cards still can't be answered by the agent (tested) | **Unit**: (a) the gate test above (only the user's APPROVED applies a change; self-approval strings don't); (b) `phone_commands_ask` and an unknown/secret name (`token`) are refused without calling the asker; (c) `SHELL_NO` cases (step 1). **Device**: `tap` on an open card's button → BLOCKED (existing `Guard.offLimits`), `phone_shell "input tap …"` → BLOCKED, `buddy_settings name=phone_commands_ask value=off` → refused. |
| 5. Style, YAGNI, F-Droid | Code review against this plan and the repo's style (no new dependency in the APK, English strings, same patterns as `PhoneTools`/`Schedules.agentTool`). CI: both flavors build, unit tests and helper tests green, "F-Droid APK is reproducible" green. Grep: `buddy_settings` is only listed/handled behind `!Flavor.FDROID`. decisions.md records the F-Droid byte difference. |

## Risks

- **Prompt injection → privilege escalation.** A poisoned page or message could talk the agent into loosening trust,
  turning on phone commands or adding an MCP server. Mitigations: a card per change showing exact old → new values;
  rule runs and helpers refused. What's left is the user approving without reading. The card text stays plain and
  whole.
- **Self-modified code can remove gates.** A build could drop the cards altogether. The install card shows the version
  and the release notes (written by CI from merged PRs, or by the agent). The real review is the pipeline/PR before
  the tag. This is by design (Nicholas approves the install), but it is not something a test can prevent.
- **Two taps per install** (Buddy's card, then Android's Update). A one-tap silent update is possible on Android 12+
  in principle, but its conditions are unverified here and it needs more code (see decisions).
- **The phone runs the official Buddy.** A fork APK can't install over it until the 003 switch, which Nicholas hasn't
  done ("not yet", 003 verify). All device rows wait for it, as in 003 (the verdict may be PARTIAL). The switch
  wipes Buddy's Linux, including this checkout and the gh sign-in. After it, the agent works in a fresh clone,
  hence "(or a clone)".
- **Open settings screens go stale** while the tool changes a value underneath them. They show it when reopened.
  Accepted.
- **Upstream merges** conflict on `Updates.REPO`, `BuildInfo` and the `Tools.java` hunks. These are small and the
  version rule is written down.
- **`releases/latest`** picks the newest non-draft, non-prerelease release by its commit date. Tag the commit you
  mean (normally `main`), never mark it prerelease.
- **JVM tests and Android stubs**: `org.json` and `android.*` are stubs in local unit tests, so the tested core (gate,
  names, `SHELL_NO`) is kept to plain strings.

## Open questions

None blocking the build.

For Nicholas (each has a default, and he can override):
- When to switch the phone to the fork build. Device verification of items 1-4 needs it. Default: verify in CI and
  with unit tests now, and do the device rows after the switch, as with 003.
- One tap or two for installs. Default: Buddy's card + Android's Update (two taps). A silent one-tap update could
  follow as its own feature if he wants it.
