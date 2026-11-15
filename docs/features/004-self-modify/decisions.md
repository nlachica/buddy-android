# Decisions: 004-self-modify
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-15 - One `buddy_settings` tool (list / read / change) in a new `SettingsTool.java`
- Options: one tool with optional `name`/`value`; separate get/set tools; one tool per kind of setting; a raw
  SharedPreferences editor (getAll / put by key).
- Choice: one tool, in a new class shaped like `PhoneTools` (static `list`, `call`), wired into `Tools.list`/`call0`,
  because the orchestrator asked for one tool, and a curated table can call the same setters (and side effects) as
  the UI. A raw editor would expose internal state keys, skip side effects and caches (`McpServers.cache`), and
  show unreadable JSON on cards.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The gate: `askConfirm(card, false)` per change; only the user's APPROVED applies it
- Options: reuse `confirm` (with `Guard.autonomous`/`takeApproval`); `askConfirm` with `canAuto=false`; add a user
  on/off switch like `phone_shell`'s.
- Choice: `svc.askConfirm(card, false)` on every change, applied only when the answer starts with APPROVED and
  `!BuddyService.selfApproved(answer)`. It never consults trust, run rules or an earlier confirm, and is refused
  in rule runs and for helpers (`HELPER_NO`). No extra switch, because the brief asks for a card per change, not a
  switch, and `confirm` can auto-approve in trusted apps or be satisfied by an earlier confirm (research). A
  one-method `asker` interface exists only so the unit test can prove the gate (its second use).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - "The approval gate itself is not changeable" = no switch for the tool's/install card, `phone_commands_ask` read-only, card answering untouched
- Options: also lock per-app trust and rules' approval modes; lock nothing beyond the card code.
- Choice: no setting controls the settings tool's card or the install card (they're in code). `phone_commands_ask`
  is listed read-only (the one switch that removes a Buddy-level card from an arbitrary shell). `Ui.protect`/`Guard`
  stay as they are. Per-app trust and rule approval modes stay changeable, each on a card showing old → new,
  because the brief's done-when names "per-app trust levels" and "notification rules"; the brief wins.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Settings covered: the brief's user-only list + a short table of plain ordinary ones
- Options: every Settings control (~100, incl. sign-ins, keys, remote/SSH, backup); the brief's named user-only set
  plus plain Prefs switches and choices.
- Choice: the table in plan.md (13 scalars incl. one read-only, plus the `app/`, `chat/<id>/…`, `rule/`, `skill/`
  and `mcp/` families). Anything needing secrets, a sign-in, pairing, a download or a user-only flow (backup) is
  out. Each later addition is one row. Reason: YAGNI. The done-when needs the named user-only set "plus the
  ordinary ones", and these are the ordinary ones a direct tool can apply safely, with the UI's own side effects.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Value formats: on/off and choices for scalars; JSON for rule/schedule/MCP; SKILL.md text for skills; "" deletes
- Options: per-field setting names (`rule/<id>/approval`); whole-list JSON documents; per-item JSON.
- Choice: one name per item, its value the item's existing JSON shape (`Rules.Rule.json`, `Schedules.Plan.toJson`,
  `{url,on}` for MCP) so parsing reuses `Rule.of`/`Plan.of`. The card lists only the fields that differ, so it
  stays readable and still names old and new. Values are shown whole on the (scrolling) card; values over 20,000
  characters are refused.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - MCP servers: add keyless, switch on/off, delete; never change an existing server's URL; never write keys
- Options: full edit including URL and key; on/off only.
- Choice: add a keyless server (`addressOk`, `free`, then `discover(url, null)` and `save(..., null)` after the
  card), switch on/off, and delete. Changing the URL of an existing server is refused, because its saved key would
  be sent to the new address. Writing keys stays out (brief: only if trivial and safe; this isn't).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Self-update: point `Updates` at the fork's releases and put a card before the installer
- Options: repoint `Updates.REPO/API` and use the tag-triggered CI release; `gh run download` the artifact +
  `install_apk`; on-device `build.sh`; a silent `PackageInstaller` update with `USER_ACTION_NOT_REQUIRED`.
- Choice: repoint, reusing `update_buddy`/`Updates` (version and package checks, the daily notice) and the existing
  `v*`-tag release job, which already signs with the fork's `BUDDY_KEYSTORE_*` secrets. `update_buddy install=true`
  gets `askConfirm(card, false)` before the download. No CI change. The other options need gh sign-in and Linux
  tools and skip the version check (artifact), need the key on the phone (`build.sh`), or need more code and
  unverified installer-of-record rules (silent).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Two taps per install (Buddy's card, then Android's Update) for now
- Options: Buddy card + Android installer; Android installer only (today's `update_buddy`); silent one-tap update.
- Choice: Buddy card + Android installer. The brief asks for an approval card naming what's installed, and Android's
  confirm can't be skipped without the silent path. Nicholas can ask for one-tap as a follow-up.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Fork version rule: `<upstream>-fork.<n>`, VERSION_CODE = max(fork, upstream) + 1
- Options: suffixed version + manual code bump; a fourth number; CI-derived versionCode.
- Choice: `2.3.7-fork.1` style with a manual `VERSION_CODE` bump per release, written down in `AGENTS.md`.
  `Updates.compare` already orders it correctly, it shows plainly as the fork in About, and Gradle and F-Droid
  reproducibility stay as upstream has them.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Flavors: both parts github-only through `Flavor.FDROID`; F-Droid APK bytes differ, behaviour doesn't
- Options: runtime `Flavor.FDROID` gate (as `update_buddy`); `SettingsTool` in `app/src/github` with an fdroid
  stub; ship the settings tool in both flavors.
- Choice: runtime gate in `Tools.list` and `call0`. Upstream would reject a settings tool, so the F-Droid flavor
  doesn't get it (brief non-goal). The fdroid APK then holds the gated-off class and the changed `Updates.REPO`
  string (`Updates` is unreachable in that flavor). That byte difference is deliberate. The reproducibility check
  compares two fdroid builds of one source, so it is unaffected.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Tests: JUnit 4 JVM tests in CI + device checks after the phone switch
- Options: JUnit 4 local unit tests; Robolectric; device-only manual checks.
- Choice: `testImplementation("junit:junit:4.13.2")` (the app's first dependency, test-only, never in the APK)
  and `testGithubReleaseUnitTest` in CI, for the gate, the setting names and `SHELL_NO`. Device checks follow once
  the phone runs a fork build. The brief requires "(tested)", the repo has no app tests, and JUnit is the least
  infra that runs in CI. Robolectric is heavy, and device-only can't run until the switch.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Strings English only; system prompt unchanged
- Options: English + 16 machine translations (002's way); English only (001, 003, CONTRIBUTING "until it's
  translated, other languages show it in English"). For agent guidance: edit `linux/system-prompt.md`, or put it in
  the tool descriptions.
- Choice: English only for `tools_card_setting`, `tools_card_update`, `hub_tool_settings`. Agent guidance goes in the
  `buddy_settings`/`update_buddy` descriptions, which are flavor-gated with the tools. The shared system prompt
  stays as upstream has it.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Gate binds the card to the state it showed: snapshot, then re-check and commit on the main thread
- Options: validate → card → apply (round-1 plan); lock the store for the card's whole wait; snapshot + compare at
  commit.
- Choice: snapshot the whole target on the main thread, show the card, and after the user's APPROVED re-read,
  compare and write in one main-thread task. If it changed or is gone, nothing is written and the agent asks again.
  The UI writes on the main thread, so the compare-and-write is serialized with it and with other commits, and the
  main thread never waits on a card. Holding a lock for up to 5 minutes would freeze the screens. (Plan review F3.)
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Self-install side doors: `install_apk` refuses Buddy's own package; the sheet's Update is protected
- Options: a second card in `install_apk` for Buddy's package; refuse that package there and point to
  `update_buddy`; leave both as upstream has them.
- Choice: refuse it in `install_apk` (github flavor), and an unreadable APK too. Protect the update sheet's Update
  button (`Ui.protect`). `Updates.install` and `installApk` write `install.apk` and open the installer under one lock.
  One carded route meets the brief, and a fork-signed APK comes only from the fork's CI as a release. A second card
  would add a second card text and a file-swap window during the card's wait. `phone_shell`'s `pm install` stays
  behind its own per-command card, whose switch is read-only in the tool. (Plan review F1.)
- By: Planner (claude-opus-5-5)

## 2026-11-15 - MCP servers: no stored address in any output; values are `on`/`off`/`""`, or a URL for a new id
- Options: `{url,on}` JSON (round-1 plan, supersedes that part of "Value formats"); show the host only; show no part
  of the address.
- Choice: no part of a stored URL appears in replies, cards or errors. A server shows as id, on/off,
  `key: set|none` and its tools. Existing servers take `on`, `off` or `""`. A URL is accepted only to add a keyless
  server under a free id. A URL can carry credentials anywhere, a host included (opaque subdomains), and agents
  never get addresses today (`McpServers.store`). (Plan review F2.)
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Rules: the setting name's id is authoritative; checks mirror the rule editor
- Options: trust the JSON's `id` (round-1 plan); path id wins and a conflicting `id` is refused.
- Choice: `rule/<id>` must exist, and the value's `id` must be absent or equal. `rule/new` refuses an `id` and gets
  a fresh unused one. Validation is `RulesActivity.save` plus the editor's choice sets (approval, seconds, limit,
  agents that run rules). Otherwise `Rules.put` could overwrite a rule other than the one on the card.
  (Plan review F4.)
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Skills: one confinement check; linked skills are listed by name only
- Options: slug check only (round-1 plan); follow links but check the canonical target; refuse links.
- Choice: refuse a symlinked skill folder or `SKILL.md`, check canonical containment in the skills folder, and do
  this on every access and again right before the write. This is the simplest rule that keeps reads (which could
  reach Buddy's private files, e.g. the Prefs token) and writes inside the skills folder. Linked skills stay usable
  by the agents and in the Skills screen. (Plan review F5.)
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Tests: add the real `org.json` as a test-only dependency (amends "Tests")
- Options: keep the tested code to plain strings; `testImplementation("org.json:json:…")`; Robolectric.
- Choice: `testImplementation("org.json:json:20240303")` (the Coder may take the current release). The rule-id and
  JSON-diff tests need real JSON parsing, and android.jar only has stubs. It is test-only and never in the APK.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Each APK install attempt stages its own file and URI; the round-1 lock goes (amends "Self-install side doors")
- Options: keep the shared `install.apk` under a lock (round-1 plan); a per-attempt file and URI; also hold the file
  until `wait_for_install` sees the install.
- Choice: `FilesProvider` stages each attempt as a new file in `cache/apk/` with its own `content://…/apk/<name>`.
  Each route checks and launches only its own file, and deletes only that file on every path that doesn't open the
  installer. Staging deletes staged APKs older than a day. This is one code path for both flavors: F-Droid's
  `install_apk` behaves the same and only the cache file name differs. That byte difference is deliberate. The
  installer opens the URI after `startActivity` returns, so a lock around the launch can't cover its read. With
  nothing shared, no lock is needed. Tying cleanup to `wait_for_install` would miss attempts the agent never waits
  for. (Plan review round 2, F1.)
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Skills folder: one `Skills.LOCK` for every Buddy write; other stores stay main-thread
- Options: move the importer's writes onto the main thread; a shared lock for skill writes; leave it (round-1 plan).
- Choice: `Skills.LOCK` is held by `create`, `delete`, the write part of `importUri` (after the URI read and zip
  scan) and the tool's skill snapshot and re-check-and-write/delete. It is never held while a card waits or while
  anything waits for the main thread. A skill's snapshot adds SKILL.md's last-modified time, because every Buddy write
  to an existing skill rewrites it. The import's parse-and-write part becomes a Context-free `importBytes` so a JVM
  test can race it. A lock is fewer changes than moving the importer onto the main thread, which would need a
  prepared-import object handed between threads. (Plan review round 2, F2.)
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Gate signature: `change(setting, value, cardFormat, asker, main)`; the asker takes the finished card text
- Options: `Asker.ask(name, old, new)` building the card inside the production lambda; `Asker.ask(card)` plus the card
  format passed in (chosen); a hard-coded English format in `SettingsTool`.
- Choice: `change` gets `getString(R.string.tools_card_setting)` as `cardFormat`, formats it, and hands the finished
  card to the one-method `Asker`, as the plan's `approval(asker, card)` has it. The card text stays in `strings.xml`
  like every other card, and the JVM tests pass the same literal format.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - `Setting` is a small abstract class with `get/view/check/prepare/lock/set/card/read`
- Options: a record of lambdas; an abstract class (chosen).
- Choice: the families need different snapshot, display, validation and commit rules (an MCP snapshot holds the
  address and must never be shown; a skill's snapshot adds its mtime and its commit holds `Skills.LOCK`), so each
  family is one small anonymous subclass. `prepare` is the hook for the MCP `discover` that follows the approval, off
  the main thread (plan gate step 5). `check` returns the normalised value, so "Already" compares like with like.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - Antigravity's pre-approved tool list gets `buddy_settings`; OpenCode's naming hint does not
- Options: leave `linux/buddy-run` alone (plan said nothing); add the name to `AGY_PHONE_TOOLS` (chosen); also add
  `phone_buddy_settings` to OpenCode's hint.
- Choice: `AGY_PHONE_TOOLS` is the list Antigravity pre-approves; without the name its own permission prompt (which
  nobody can answer in a Buddy chat) would stop the tool. The script is shared with the F-Droid flavor, but a listed
  name for a tool that isn't offered there does nothing. Claude Code and Codex allow the whole `phone` server, and
  OpenCode's hint is a generic "phone_ prefix" note, so they need no change. `mcp-bridge/tools.json` (the public
  registry listing of upstream's tools) is left alone.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - `buildRule` takes the list of agents that can run rules; `install_apk` gives an unreadable APK its own message
- Options: `buildRule` reads `Account.ALL` itself; the caller passes the list (chosen). One refusal text for Buddy's own
  package and an unreadable APK (plan wording); two texts (chosen).
- Choice: `Account`'s static initialiser uses Android classes, so reading it inside `buildRule` would make the rule
  checks untestable in the JVM; `rule()` passes `ruleAgents()` (the editor's own set). For `install_apk` an unreadable
  file gets "Android can't read that file as an app" rather than "That's Buddy itself", which would mislead; both
  still refuse and delete that attempt's file.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - A new MCP server is saved with key `""`, not `null`
- Options: `McpServers.save(c, s, null)` (plan wording: "keeps the saved one"); `save(c, s, "")` (removes any).
- Choice: `""`. `remove` already deletes a server's key, so there is none to keep; passing `""` guarantees that a stale
  secret under the same name can never follow the new address, which is the point of "keyless".
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - Unit tests were run with `javac` + JUnitCore here, not Gradle
- Options: install Gradle and the Android Gradle plugin on the phone; compile `app/src/test` against `android.jar` and
  run JUnit directly (chosen, `/tmp/buddy-build-004/check.sh`).
- Choice: no Gradle is installed and the on-device toolchain is `aapt2` + `javac` + `d8`. The tests only touch
  Context-free code, and the real `org.json` jar is put before `android.jar` as Gradle's `testImplementation` does. CI
  (`testGithubReleaseUnitTest`) is the first Gradle run of these tests and has not happened yet.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - Known issue: first update_buddy install didn't open the installer
- On device the first `update_buddy install=true` reported "installer is open" but nothing appeared; the second call
  worked. Likely Android's background-activity-launch limit (Buddy wasn't in front). Not fixed yet: watch for a
  repeat; a fix would launch the installer from a notification/the bubble. By: Orchestrator
