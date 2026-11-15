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
