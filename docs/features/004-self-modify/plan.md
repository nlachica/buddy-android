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
- `name` only: that setting in full (the rule's or schedule's JSON, the skill's SKILL.md, the app's mode and why, an
  MCP server's on/off, `key: set|none` and tool names; never its address).
- `name` + `value`: validate → snapshot → **card** → re-check → apply → reply `Changed <name>: <old> → <new>` (+ when
  it takes effect).
  `value: ""` deletes an item (rule, schedule, skill, MCP server) or resets an app to Buddy's suggestion.

The description says: this is how to change Buddy's settings (not by tapping its screens); Buddy shows the user an
approval card itself, so don't call `confirm` first; keys and tokens are never shown or changed here. That keeps
agents from refusing it under the system prompt's "never tap Buddy's screens" (upstream issue #33 lesson, research).
`linux/system-prompt.md` is left alone: the F-Droid build shares it, and "never tap Buddy's screens" stays true.

**Where.** New `SettingsTool.java` next to `PhoneTools.java`, in the same pattern: `static void list(JSONArray t)` for
the schema and `String call(JSONObject a)` for the work. `Tools.java` wires it in (list + a `case` in `call0`), only
when `!Flavor.FDROID`, like `update_buddy` (`Tools.java:298`). `call0` also refuses it when `Flavor.FDROID`, so an
unlisted call can't reach it.

**The gate (one code path for every change).** `change(setting, value, asker, main)`:
1. Refused before anything else: a notification rule's run (`ruleRun()`, the same reply text as installs,
   `Tools.java:539`); a helper (`HELPER_NO` gets `buddy_settings`; `askConfirm` also denies helpers, `BuddyService:880`);
   unknown name; read-only setting; invalid value.
2. **Snapshot**, on the main thread: `old = setting.get()`, the target's whole current value as a string (a scalar,
   the whole rule or schedule JSON, the whole SKILL.md, the server's stored entry; `null` when the item doesn't
   exist). The snapshot is only compared, never shown: the card and replies use the setting's `describe` (for an MCP
   server, no address). A value that would change nothing gets "Already <value>" and no card. New values are parsed
   into fresh objects (`Rule.of`, `Plan.of`, `new McpServers.S`), and cached objects are never touched before the
   commit.
3. `svc.askConfirm(card, false)` (`BuddyService.java:877`) on the tool thread, so the main thread isn't blocked while
   the card waits: `canAuto=false` means no rule countdown and no "Don't ask". It **never** consults
   `Guard.autonomous`, `Guard.runRule` or `Guard.takeApproval`, so a trusted app or an earlier `confirm` can't stand
   in for this card (research: `Tools.java:479-484`, `Guard.java:340-360`).
4. Goes on only when `answer.startsWith("APPROVED") && !BuddyService.selfApproved(answer)` (`BuddyService.java:840`).
   That covers the user's approval and nothing else: countdown and rule approvals are rejected even though they
   can't occur with `canAuto=false`. Otherwise it returns `"Not changed. " + answer` (same as `phone_shell`, `Tools.java:641`).
   Steps 3-4 are one static helper, `approval(asker, card)` (null when the user approved, else the answer), which
   `update_buddy` reuses (B).
5. **Re-check and commit**, in one main-thread task: `if (!Objects.equals(setting.get(), old))` → `Not changed:
   <name> changed while the card was open (now: …). Ask again.` and no write; else `setting.set(value)`. The
   settings screens write on the main thread, so this compare-and-write can't interleave with them or with another
   `buddy_settings` commit. Network work that has to follow the approval (an MCP server's `discover`) runs on the
   tool thread before this task.

`asker` is a one-method nested interface (`String ask(String card) throws InterruptedException`), and `main` is a
`java.util.concurrent.Executor`; the gate wraps steps 2 and 5 in a `FutureTask` it runs on `main` and waits for
(copied from `Schedules.agentTool`, `Schedules.java:630-646`). Production passes `t -> svc.askConfirm(t, false)` and
a main-thread `Handler`'s `post`. The unit test passes a fake asker and `Runnable::run`. Their uses are this method
and the tests that prove the gate (done-when 1 and 4).

Card: new string `tools_card_setting` = `Change Buddy's setting %1$s\nNow: %2$s\nNew: %3$s`. Values are shown whole
(the card scrolls, `Bubble.cardScroll`). Nothing is cut, so nothing can hide past a limit. The tool refuses values over
20,000 characters. For JSON items (rule, schedule), Now/New list only the fields that differ
(`approval: ask` → `approval: now`); a new item lists all fields, a deletion shows `(deleted)`. The re-check in step 5
compares the whole snapshot, not only the fields on the card.

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
| `rule/<id>`, `rule/new` | `Rules.Rule` JSON, or "" | `Rules.put` / `Rules.remove`, then `Triggers.get(c).rulesChanged()` (`RulesActivity:444-445`). Ids and checks: see "Rules" below |
| `skill/<name>` | full SKILL.md text, or "" | write `Skills.dir(c)/<name>/SKILL.md` (`Linux.write`), front matter `name` must equal `<name>` (a `Skills.slug`); "" → `Skills.delete` (card says the folder goes). Links: see "Skills" below |
| `mcp/<id>` | existing: `on`, `off` or ""; new (free id): its URL | existing: `setOn`, "" → `McpServers.remove`, nothing connects to the server. New: `McpServers.addressOk` + `free`, then after the card `discover(url, null)` and in the commit `free` again + `save(c, s, null)` (keyless). A URL for an existing id is refused (its key would follow it). Addresses: see "MCP servers" below |

**Rules.** For `rule/<id>` the path's id wins: the rule must exist (else "No rule <id>; use rule/new"), an `id` in
the value must be absent or equal to `<id>` (else refused), and the tool sets `id = <id>` before `Rule.of`, so an
edit can't reach another rule. `rule/new` refuses a value that carries an `id`; the commit gives it a fresh one (the
`Rule` default, base-36 time, plus one while another rule already has it). Checks follow the editor (`RulesActivity.save`
`:431-445` and its choices): `pkg` non-empty; `instruction` non-empty after trimming; `instruction`, `from`, `words`
trimmed; `approval` ∈ ask/countdown/now; `seconds` ∈ `Rules.SECONDS`; `limit` ∈ `Rules.LIMITS`; `backend` one the
editor offers (`Account.ALL` with `runsRules()`, `RulesActivity:290`).

**Skills.** Every skill access (list, read, create, edit, delete) goes through one check, `skillFile(root, name)`:
`name` is a `Skills.slug`; neither `<root>/<name>` nor its `SKILL.md` is a symlink (`Files.isSymbolicLink`, the test
`Skills.isLink` already uses; it becomes package-private); and the file's canonical path is inside the canonical
`root` (`Skills.dir`). A linked skill is listed by name only, `(linked: not read or changed here)`; reading or
changing it is refused. The commit runs the check again right before `Linux.write` / `Skills.delete`. `Skills.all`
is not used for the tool's listing, since it reads linked SKILL.md files.

**MCP servers.** A stored server address, or any part of it (host included), never goes into a reply, a card or an
error: a URL can carry credentials in its user-info, query or path (`McpServers.java:94-95`), and Buddy keeps it from
the agents today (`McpServers.java:135-137`). A server shows as `mcp/<id> = on|off  (key: set|none, N tools)`; reading
it adds its tool names, which agents already get through the relay. On/off and delete never connect to the server,
so no error can carry its address. The only URL that appears is one the agent itself gives to add a server, on that
card.

Every `get` and `set` runs on the main thread (gate steps 2 and 5; a listing or read is one main-thread task too), which also covers what must be there anyway
(sessions, `ChatHub`). Secrets: the table holds no token or key. MCP servers show `key: set|none` and no address, and
`Prefs.token`, `bg_token`, `Secrets` and `signedAgents` are never read.

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
The carded `update_buddy` is the only way an agent gets Buddy's installer opened on Buddy's own package. Changes:
1. `Updates.REPO` / `API` → `https://github.com/nlachica/buddy-android` (`Updates.java:41,45`). Today they point at
   upstream, whose APKs can't install over a fork-signed Buddy (research). The daily "update available" check then
   follows the fork's releases too, which is the right behaviour for a fork install.
2. `Tools.updateBuddy(install=true)`: before `Updates.install`, `SettingsTool.approval(asker,
   getString(R.string.tools_card_update, r.version, BuildInfo.VERSION, r.notes))`, the gate's own "user APPROVED
   only" helper. A denial returns `"Not installed. " + answer`.
3. `update_buddy`'s description: "this fork's GitHub release", plus one sentence on how an agent makes one (below).
4. **No side doors** (github flavor only, F-Droid's behaviour is unchanged):
   - `install_apk` reads the package of the APK it copied (`getPackageArchiveInfo`). If it's Buddy's own package, or
     Android can't read it, the copy is deleted and the tool refuses: "That's Buddy itself: use update_buddy
     install=true (the user approves it on a card)." Other apps install as before, with no Buddy card. This refuses
     rather than adds a second card: one carded route meets the brief, and a fork-signed APK only comes from the
     fork's CI, which publishes it as a release when tagged.
   - The update sheet's Update button (`Updates.java:231`) gets `Ui.protect`, so only the user can start it even
     with "Let agents tap Buddy's screens" on (`Guard.offLimits`, `Guard.java:485-494`). Opening the sheet (Settings
     → Check for updates, the notification) installs nothing, so it stays as it is.
   - `Updates.install` and `Tools.installApk` write `install.apk` and open the installer inside one lock
     (`synchronized (FilesProvider.class)`), so one route can't swap the file another has just checked.
   - `phone_shell` can run `pm install`, but each command shows its own card unless the user switched that off,
     and `phone_commands_ask` is read-only in `buddy_settings` (see Risks).

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
stays unchanged. The new `install_apk` check is behind `!Flavor.FDROID`, and its lock changes no behaviour. The
F-Droid APK's behaviour is unchanged; its bytes differ by the gated-off code (the class, the `install_apk` check), the
lock and one string constant. That difference is deliberate and recorded in decisions.md. CI's reproducibility check compares two
fdroid builds of the same source, so it is unaffected.

## Steps

1. **Test wiring + shell tripwire test.** `app/build.gradle.kts`: `dependencies { testImplementation("junit:junit:4.13.2");
   testImplementation("org.json:json:20240303") }` (the first dependencies; test-only, never in the APK; the real
   `org.json` replaces android.jar's stubs in JVM tests so `Rule.of` and the JSON diff can be tested), plus
   `testOptions { unitTests.isReturnDefaultValues = true }` if the test needs it. `Tools.SHELL_NO` goes from private to package-private. New
   `app/src/test/java/app/buddy/assistant/ApprovalGateTest.java`: `SHELL_NO` blocks `input tap 1 2`,
   `/system/bin/input swipe …`, `cmd input tap`, `sendevent`, `monkey`, `uiautomator`, `am start -n app.buddy.assistant/…`,
   `settings put secure enabled_accessibility_services …`, and lets `pm list packages` and `dumpsys battery` through.
   `.github/workflows/build.yml`: add `testGithubReleaseUnitTest` to the build step's gradle command.
2. **`SettingsTool` core + scalar settings + wiring.** New `SettingsTool.java` (setting record: name, allowed
   values/help, get, set, describe; `list`, `read`, `change` with the gate above: snapshot, `approval(asker, card)`,
   re-check and commit on the `main` executor; the scalar rows of the table).
   `Tools.java`: list it when `!Flavor.FDROID`, add `case "buddy_settings"` (refuses in rule runs and in the F-Droid
   flavor), add it to `HELPER_NO`. `BuddyService.onToolActivity`: add it to the not-operating-the-phone list (`:554`).
   `ChatHub.describeTool`: a `buddy_settings` case (`hub_tool_settings`: "Buddy settings: %1$s", the name or "list").
   `res/values/strings.xml`: `tools_card_setting`, `hub_tool_settings`, English only. Tests in `ApprovalGateTest`
   (see Test plan 1, 2, 4).
3. **Per-app trust and per-chat settings.** `SettingsTool.java`: `app/<package>`, `chat/<id>/linux_tools`,
   `chat/<id>/background`, `chat/<id>/schedule` (main-thread `FutureTask`), and their listing lines.
4. **Rules, skills, MCP servers.** `SettingsTool.java`: `rule/…` (path id wins, the editor's checks), `skill/…`
   (`skillFile(root, name)` on every access and again in the commit; `Skills.isLink` package-private), `mcp/…`
   (address-free lines, `on`/`off`/""/new URL), the JSON field diff for rule and schedule cards, and their listing
   lines. Tests: Test plan 1-2.
5. **Self-update through the fork.** `Updates.java`: `REPO`/`API` → fork, plus the class comment; `Ui.protect` on the
   sheet's Update button; `install` writes and opens inside `synchronized (FilesProvider.class)`. `Tools.java`:
   `update_buddy` description; `SettingsTool.approval` before `Updates.install` in `updateBuddy`; in `installApk`
   (when `!Flavor.FDROID`) the package check that refuses Buddy's own or an unreadable APK, inside the same lock.
   `strings.xml`: `tools_card_update` = `Install Buddy %1$s over %2$s?\n%3$s`.
6. **Docs.** `AGENTS.md` (repo): "Updating Buddy on the phone" (version rule, tag, CI, `update_buddy`).
   `docs/GUIDE.md:202`: one sentence saying that in this fork agents can change these settings with `buddy_settings`,
   each change on an approval card, and still can't answer cards.

## Test plan

| Done-when item | Proof |
|---|---|
| 1. List with secrets masked; change after a card naming setting/old/new; denial leaves it unchanged | **Unit** (`ApprovalGateTest`, fake setting + fake asker): `DENIED…`, `CHANGES REQUESTED…`, the 5-minute timeout text, `NOT_NOW`, `AUTO_APPROVED` and `RULE_APPROVED` leave the fake's value unchanged and its setter uncalled; `APPROVED by the user. Go ahead.` calls the setter once with the new value; the card text holds name, old and new; an unchanged value asks no card. **Unit, stale writes**: a fake asker that changes the fake's value, or deletes it (`get` → null), before answering `APPROVED…` → setter never called, reply says it changed while the card was open. **Unit, MCP**: the list line, read text and card for servers with URLs carrying credentials (`https://u:p@h.example/mcp`, `…?token=abc`, `…/sk-123/mcp`) and a saved key contain no part of the URL or key; a URL for an existing id is refused. **Device** (after the switch, phone MCP): list shows no `Prefs.token`/`bg_token` value, MCP servers without addresses and keys only as `set`/`none`; change `read_aloud` → deny → still the old value; approve → new value, visible in Settings; ask to change a rule's `on`, then edit and save its instruction in Settings before approving → "changed while the card was open", the new instruction kept. |
| 2. Covers the user-only settings + ordinary ones | **Unit**: the setting names/families include `app/`, `chat/<id>/linux_tools`, `chat/<id>/background`, `chat/<id>/schedule`, `rule/`, `skill/`, `mcp/`, `background_new_chats`, `notification_rules`, `linux_tools_new_chats` and at least the ordinary rows of the table. **Unit, rules**: `rule/A` with `"id":"B"` refused; `rule/A` without an id → the rule built has id A; `rule/new` with an id refused; empty instruction, unknown `approval`/`seconds`/`limit` refused. **Unit, skills** (temp folder, real symlinks): a skill folder linked outside the root and a `SKILL.md` linked to an outside file are refused by `skillFile` for read, write and delete, listed by name only, and the outside file's text never appears in a reply and is unchanged; a plain skill passes. **Device**: one approved change per family (trust for one app, a rule's `on`, a schedule's `paused`, a test skill created then deleted, a keyless MCP server switched off, this chat's Linux tools), each checked on its Settings screen. |
| 3. Code change → signed APK by fork CI → installed after one card; Buddy back up intact | **CI**: a `v…-fork.N` tag on the fork publishes `buddy.apk`; `apksigner verify --print-certs` shows the fork cert (`7cf74b84…`, as in 003's verify); `curl` of the fork's `releases/latest` API shows the asset Updates accepts. **Device** (after the switch): in a chat with Linux tools, the agent makes a trivial visible change, bumps the version, tags, waits for CI, calls `update_buddy install=true` → card → installer → Update; Buddy restarts on the new version (Settings › About); chat list, a test chat's messages and a changed setting are still there. **Device, side doors**: `update_buddy install=true` → deny → no installer; `install_apk` with the release's `buddy.apk` copied into Buddy's Linux → refused, pointing to `update_buddy`; `install_apk` with another app's APK → installer as before; with "Let agents tap Buddy's screens" on, `tap` on the update sheet's Update → BLOCKED. Tagging publishes a public release, so it needs the orchestrator's confirm with Nicholas first. |
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
- **Open settings screens go stale** while the tool changes a value underneath them. They show it when reopened, and
  an editor open on an old draft (a rule being edited) can overwrite the tool's change when the user saves it, as two
  screens could today. The tool itself never writes over a change it didn't show (gate step 5). Accepted.
- **Skill folder swapped for a link mid-commit.** An agent with Linux tools could swap a skill folder for a link
  between the commit's check and the write (milliseconds). `Linux.write` replaces a linked `SKILL.md` rather than
  following it, and that agent can already write files in Buddy's Linux with its own shell. Accepted.
- **`phone_shell` can `pm install`.** It is adb-level by design: each command shows its own card unless the user
  turned that off (`phone_commands_ask`, read-only in `buddy_settings`). Not a new route; left as upstream has it.
- **Upstream merges** conflict on `Updates.REPO`, `BuildInfo` and the `Tools.java` hunks. These are small and the
  version rule is written down.
- **`releases/latest`** picks the newest non-draft, non-prerelease release by its commit date. Tag the commit you
  mean (normally `main`), never mark it prerelease.
- **JVM tests and Android stubs**: `android.*` is stubbed in local unit tests (the real `org.json` is on the test
  classpath), so the tested code (gate, names, `SHELL_NO`, rule building, `skillFile`, MCP lines) takes plain values,
  `File`s and JSON, never a `Context`.

## Open questions

None blocking the build.

For Nicholas (each has a default, and he can override):
- When to switch the phone to the fork build. Device verification of items 1-4 needs it. Default: verify in CI and
  with unit tests now, and do the device rows after the switch, as with 003.
- One tap or two for installs. Default: Buddy's card + Android's Update (two taps). A silent one-tap update could
  follow as its own feature if he wants it.
