# Decisions: 019-settings-no-card-routine
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-17 - The skip lives in `SettingsTool.change`, behind a nullable `routine` consumer; `approval()` is untouched
- Options: an Asker that answers "approved by routine" through `approval()`; going through `confirm`/`Guard.runRule`;
  a sixth `change` argument that skips the card for non-sensitive settings and receives the card text after the write.
- Choice: the sixth argument (`null` = card every change; the 5-argument form passes null). `approval()` keeps refusing
  every answer that isn't the user's, so 004's gate test stands and `update_buddy` (which calls `approval()` directly)
  keeps its card. `Guard.runRule`/`confirm` are tied to the front app and `routineText`, which a setting doesn't have.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - "Routine allowed" = `"routine".equals(approvalsFor(here)) && !Triggers.ruleRun(here)`
- Options: read `Session.approvals` directly; also require `approvalsEditable`; mirror upstream's `Guard.runRule`.
- Choice: mirror `Guard.runRule` (`Guard.java:567-574`), as a pure `routineChat(approvals, ruleRun)` for the test.
  `approvalsFor` already gives "" for helpers/sub-agents, unknown sessions and no chat, so those card.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - One predicate `sensitive(name) = !ORDINARY.contains(name)`: an allow-list of 17 exact names
- Options: a deny-list of sensitive names/prefixes; a flag on each `Setting`; an allow-list of ordinary names.
- Choice: the allow-list, so a setting added later (upstream sync, a fork feature) cards until someone lists it, and
  every family (`app/`, `chat/`, `mcp/`, `service/`, `button/`, `rule/`, `skill/`, `default_model/`, `reviewer_model/`)
  is carded with no prefix rule. ORDINARY: chat_bubble, tap_action, panel_size, theme, accent, surface, face,
  read_aloud, voice_input, voice_output, chatgpt_voice, audio_device, voice_languages, assist_mode, live_continue,
  pause_media, update_notices.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Carded beyond the brief's list: agents/models, rules, schedules, background, browser, download server, skills, voice_cards
- Options: treat them as ordinary (fewer cards); keep them carded.
- Choice: keep them carded, by one test: does it change what agents may do, what runs without the user or out of
  sight, how cards are answered, or what gets billed? `voice_cards` answers approval cards by voice; `default_agent`,
  `live_agent`, `default_model/*` can point chats at a pay-per-use API key service; rules, schedules and background run
  unattended or out of sight; skills steer every chat's agent; `background_browser` adds a tool and a 300 MB download;
  `download_server` is where Buddy's Linux gets software. All card today, so nothing is taken away; moving one is a
  one-word change. Shown to Nicholas in the PR.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - The note reuses `run_rule_approved` with the card text, after a successful write only
- Options: a new English string; the one-line result; upstream's `run_rule_approved` with the card text.
- Choice: `run_rule_approved` ("Approved by your run rule: %1$s"), the line upstream writes for a Routine allowed phone
  step (`Tools.java:1836`), already translated; the card text mirrors the card path's "✓ Approved: <card>" note.
  Posted on the main thread into the calling chat; nothing noted for Already / Not changed / stale / a failed set.
- By: Planner (claude-opus-5-5)

## 2026-11-17 - Tell the user where it's fork-owned; leave upstream's Approvals sheet text alone
- Options: edit `chat_approvals_routine_info` (18 locales); edit only fork-owned text.
- Choice: fork-owned text only: the tool description, the `chat/<id>/approvals` card's routine effect, `AGENTS.md:25`
  and the fork parenthetical in `docs/GUIDE.md:205`. Editing upstream's string is a sync conflict every release.
- By: Planner (claude-opus-5-5)
