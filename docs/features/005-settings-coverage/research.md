---
type: research
feature: 005-settings-coverage
created: 2026-11-15
---
# Research: 005-settings-coverage

Paths are under `app/src/main/java/app/buddy/assistant/` unless noted. Status: **cov** = covered by 004 (`SettingsTool`), **add** = to add (005 final status: **cov(005)** = added by 005; no row says add any more), **excl(N)** = excluded by non-goal N (1 secrets, 2 user-only flow, 3 approval gate, 4 new settings).

## Summary
- 004's `SettingsTool` has: 13 Prefs scalars (`SettingsTool.java:121-174`), `app/<pkg>` modes, `chat/<id>/{linux_tools,background,schedule}`, `rule/*`, `skill/*`, `mcp/*` (`SettingsTool.java:702-730`). Everything else on Settings is a gap.
- Extension points are cheap: `Scalar(name, get, set, note, values...)` and `onOff(...)` (`:98-119`) cover any Prefs switch/choice; `Setting` subclasses cover families; `find()` (`:702`) routes names; listing is built near `:737-760` (also must list new entries).
- Settings pages (`SetupActivity.pages()` `:253-263`): agents, bubble, voice, safety, background, tools, appearance, about. Render map `:211-250`: voice = voiceSection+readAloudSection; background = automationSection+browserSection; tools = connectionsSection+phoneToolsSection+remoteSection.
- Biggest to-add families: appearance (accent, surface, wallpaper accent), voice (engines, ChatGPT voice, audio device, languages, assist mode, live agent, live continue, pause media), default agent + per-agent default models, reviewer + reviewer models + built-in words switch, per-app button lists (ask/allow), browser switch, download mirror, per-service API-key on/off, rules master switch already cov.
- Secrets/flows to exclude: API keys/tokens (`Prefs.token`, Providers keys, MCP addresses/keys), sign-ins, pairing/`Background.setUp`, remote session / Codex remote / SSH (start network services, need sign-in), backup, installs, wipe browser data, face picture (needs file picker).
- Per-app button lists (AppPermissionsActivity) and chat options sheet are `protect()`ed screens (`AppPermissionsActivity.java:73`, `ChatActivity.java:1244`), i.e. agents cannot tap them: the tool is the only agent route, so these are the high-value gaps.
- Several setters have no validation: callers must validate (e.g. `setBackend`, `setReviewer`, `setVoiceEngine` store any string, `Prefs.java:134-140,717-723,681-686`). The UI greys out unusable choices (not signed in); the tool must replicate those checks.
- Tests: `app/src/test/.../ApprovalGateTest.java`, `SettingsFamiliesTest.java`; CI command `./gradlew --no-daemon testGithubDebugUnitTest ...` (`.github/workflows/build.yml:59`; 004 verify fixed release→debug).

## Repo map
- Gate/`change()`, `Scalar`, `onOff`, `scalars()`, `app()`, `chatSwitch()`, `schedule()`, `rule()`, `skill()`, `mcp()`, `find()`: `SettingsTool.java`. Wiring in `Tools.java` (github flavor only, 004 plan A).
- 004 conventions to keep (plan.md A, decisions.md): compare-and-write on main thread, card `Change Buddy's setting %1$s / Now / New`, values refused >20,000 chars, `Already <v>` when no change, no secrets, `phone_commands_ask` read-only, helper/rule-run refused.

## Inventory (every control)

### Settings menu top: setup card (`SetupActivity.setupSection :315-421`)
| control | store | status |
|---|---|---|
| Accessibility step (open system settings) | Android system | excl(2) system flow |
| Install agent / Sign in agent / use-instead / switch default (`useInstead :1630`, `switchDefaultSheet :1642`) | installs, sign-in; `Prefs.setBackend` | excl(2); the default-agent choice itself is on Agents page |
| Battery-optimisation step (`:408`) | Android system | excl(2) |

### Agents and models (`agentSection :477`)
| control | store / values | status |
|---|---|---|
| Default agent segmented (`:485-497`) | `Prefs.backend`/`setBackend`; claude, codex, opencode, agy, grok; UI greys `notReady()` agents (install/sign-in needed) | cov(005) `default_agent` |
| Agent rows (install / sign-in) `agentRow :1670` | Account state | excl(2) |
| API key service: "Use this service" switch (`ApiKeysActivity.java:213`) | `Providers.setOff(c,p,off)` (`Providers.java:151`); non-secret | cov(005) `service/<id>` |
| API key service: name | `p.name`, `Providers.save(…, null)` | cov(005) `service/<id>/name` |
| API key service: API key | `Secrets` | excl(1) |
| API key service: Anthropic-style and OpenAI-style addresses | `p.anthropic`, `p.openai` | excl(1) |
| API key service: Check now | refreshes the model list | not a setting (an action) |
| API key service: shown models checklist (`:386-438`) | `p.shown`, `Providers.save(…, null)` | cov(005) `service/<id>/models` |
| API key service: add | needs a key | excl(1) |
| API key service: remove | deletes the saved key | excl(1) |
| Chats-on-Default per agent (`defaultsSheet Ui.java:487`, `pickDefault :508`, ApiKeysActivity `defaultRow :113`) | `Prefs.setAgentDefault(agent, ref)`; ref "" or `@service/model` (`Prefs.java:578-584`); side effect `ChatHub.get(c).changed()`; sheet only lists signed-in agents; options from `Models.options(agent,true)` plus free-typed model | cov(005) `default_model/<agent>` |
| GitHub row (sign-in) | `GitHub` | excl(2) |
| Download server (`mirrorSheet :1328`, `customMirror :1374`) | `Prefs.setMirror(id)` then `Mirrors.apply` on a thread (`chooseMirror :1321`); ids from `Mirrors.ALL` ("", ustc, tuna, aliyun, yandex, mobinhost) + custom | cov(005) `download_server`: presets, and `custom` once saved; the custom address is excl(1) |

### Bubble and chat (`bubbleSection :855`)
| control | store | status |
|---|---|---|
| Chat bubble switch | `setBubbleEnabled` | cov `chat_bubble` |
| Tap action | `setTapAction` | cov `tap_action` |
| Panel size | `setPanelSize` | cov `panel_size` |

### Voice (`voiceSection :578`, `readAloudSection :889`)
| control | store / values | status |
|---|---|---|
| Voice input engine (`:590`) | `setVoiceEngine`: phone, claude, chatgpt; claude needs `SetupState.planSignedIn(c,"claude")`, chatgpt needs codex (`:581-584`) | cov(005) `voice_input` |
| Voice output engine (`:600`) | `setSpeechEngine`: phone, chatgpt (same sign-in rule) | cov(005) `voice_output` |
| ChatGPT voice (`:607`, only shown when output=chatgpt) | `setSpeechVoice`; `CodexSpeech.VOICES` juniper, maple, ember, cove (`CodexSpeech.java:20`); UI plays a sample | cov(005) `chatgpt_voice` |
| Audio device (`:620`, Android 12+, >1 device) | `setAudioDevice(key)`; "" = Auto else `type:name` from `AudioRoute.choices(c)` (`AudioRoute.java:34`) | cov(005) `audio_device` |
| Languages (chips, `:640-667`) | `setVoiceLangs(csv)` of `Voice.LANGUAGES` tags (12, `Voice.java:80`); at least one; long-press = default → `setVoiceLang` | cov(005) `voice_languages` (one setting; the first tag is the default, so `voice_language_default` is not separate) |
| Assistant gesture (`:680`) | `setAssistMode`: talk, live | cov(005) `assist_mode` |
| Live voice agent (`:689`) | `setLiveAgent`: "" or agent; `Account.known`; UI greys not-ready | cov(005) `live_agent` |
| Live voice continue latest chat | `setLiveContinue` | cov(005) `live_continue` |
| Read replies aloud | `setSpeakReplies` | cov `read_aloud` |
| Pause media while reading | `setPauseMediaForSpeech` (`:897`); on also needs notification access else UI opens CheckActivity | cov(005) `pause_media` |
| Voice cards | `setVoiceCards` | cov `voice_cards` |

### Safety and approvals (`safetySection :925`)
| control | store | status |
|---|---|---|
| Notification rules row → RulesActivity | rules | cov `rule/*` |
| Notification rules master switch (`RulesActivity.java:143`) | `setRulesOn` | cov `notification_rules` |
| Rule editor fields (app, instruction, from, words, backend, approval, seconds, limit, on) | `Rules.put` | cov via `rule/<id>` JSON |
| Rules: notification-access prompt, "Clear activity" (`:~170`) | Android permission; `Triggers.clearLog` | excl(2) permission; clear-log = no setting (YAGNI) |
| App permissions row → AppPermissionsActivity | | see next |
| Per-app mode hard/smart/trust + "all apps" + "reset to suggested" (`AppPermissionsActivity:171-218`) | `setAppSafety` | cov `app/<pkg>` (bulk = N calls; fine) |
| Smart reviewer: same, claude, codex (`:92`) | `Prefs.reviewer/setReviewer`; claude/codex greyed unless `SetupState.signedIn` | cov(005) `reviewer` |
| Reviewer model per harness claude/codex (`:105-116`, only if signed in) | `setReviewerModel(h, model)`; "" = quick default (Haiku / small Codex) (`Prefs.java:726-732`) | cov(005) `reviewer_model/<claude or codex>` |
| Button rules page (`rulesPage`): built-in risky words switch (`:269`) | `setGuardBuiltin` (default on) | cov(005) `builtin_risky_words` |
| Always-ask list / Never-ask list: add/remove a button label, optionally for one app (`:272-340`) | `Guard.rules/saveRules(list,...)`, `Prefs.guardList` JSON of {label,pkg,app}; lists "ask","allow"; dedupe `Guard.addRule` by normalised label+pkg | cov(005) `button/<package or *>/<label>` |
| "Seen" buttons list (`Guard.rules(c,"seen")`) | suggestions only | excl(4) not a setting |
| Linux tools for new chats | `setDevMode` | cov `linux_tools_new_chats` |
| Let agents tap Buddy's screens | `setAgentsTapBuddy` | cov `agents_tap_buddy` |

### Background and automation (`automationSection :974`, `browserSection :1455`)
| control | store | status |
|---|---|---|
| Background mode row → BackgroundActivity: connect/pair/start, Remove | `Background.*` | excl(2) |
| Background for new chats (`BackgroundActivity:213`) | `setBackgroundDefault` | cov `background_new_chats` |
| Phone commands switch (`:221`) | `setPhoneShell` | cov `phone_commands` |
| Ask before each phone command (`:228`) | `setPhoneShellAsk` | cov read-only `phone_commands_ask` (excl(3)) |
| Scheduled tasks row → SchedulesActivity | per-chat plans | cov `chat/<id>/schedule` (SchedulesActivity has no own prefs; unverified beyond grep, no `Prefs.set`/`switchRow` hits) |
| Background browser switch (`:1461`) | `Prefs.setBrowser`; on triggers `Browser.install` (download), off stops it on a thread | cov(005) `background_browser` |
| Open browser / Wipe browser data / sign-in help | `Browser.wipe`, BrowserActivity | excl(2) (sign-in sessions; destructive wipe) |

### Tools and connections (`connectionsSection :989`, `phoneToolsSection :523`, `remoteSection :704`)
| control | store | status |
|---|---|---|
| MCP servers row → McpServersActivity: add/remove/on-off, keys | `McpServers` | cov `mcp/*` (keys/addresses excl(1)); "copy exported" at `:82` shows addresses: excl(1) |
| Skills row → SkillsActivity (create/edit/delete/import zip/GitHub?) | `Skills` | cov `skill/*`; zip import = user file flow, excl(2) |
| Phone-tools command (agent picker + copy command containing `Prefs.token`) | `mcpAgent` field, token | excl(1) (token) |
| Remote Control session switch (`:709`) | `setRemoteSession`; needs Claude plan sign-in; starts `RemoteSession` | excl(2) network/pairing service |
| Codex session for ChatGPT app + pair code (`:757`) | `setCodexRemote`; `CodexRemote` | excl(2) |
| SSH server + one-time code (`:786`) | `setSshServer`; `SshServer.newCode` | excl(2) (opens a login to Buddy's Linux; also credential) |
| Old Termux chats remove | deletes chats | excl(4)/destructive, no setting |

### Appearance (`appearanceSection :423`)
| control | store | status |
|---|---|---|
| Theme mode | `setThemeMode` | cov `theme` |
| Accent presets (`Theme.accents`, 6, `:1214`) | `setAccent(color)` (clears wallpaper flag, `Prefs.java:379`) | cov(005) `accent` |
| Accent from wallpaper (Android 12+) | `setAccentFromWallpaper` (flag only true; no off other than setAccent) | cov(005) `accent` = wallpaper |
| Surface: warm, neutral, black (`Theme.surfaces`) | `setSurface` | cov(005) `surface` |
| Buddy's look: buddy / picture (`:445-462`) | `Prefs.setFace`; `picture` needs an image already saved (`Face.picture`) else opens file picker | cov(005) `face` (buddy always; picture only when one is saved); picking a picture is excl(2) |
Side effects: UI calls `themeChanged()`/`faceChanged()` (`SetupActivity:190-203`); the tool from the service has no activity, so check how 004's `theme` note ("screens pick it up when next open") applies; accent same.

### About (`aboutSection :1002`)
| control | store | status |
|---|---|---|
| Backup & restore | BackupActivity | excl(2) |
| Update notices switch | `setUpdateNotices` | cov `update_notices` |
| Check for updates / Help / Setup check | open screens | excl(4) no setting |

### Adjacent controls named in the brief but not on Settings pages (chat screen; both `protect()`ed)
| control | store | status |
|---|---|---|
| Chat model & effort (`ChatActivity:665-730`) | `ChatHub.setModel/setEffort` (`ChatHub.java:385,393`) | excl(4): on the chat screen, not on a Settings page (plan open question) |
| Keep awake (long task) | `ChatHub.setAwake` (`:510`) | excl(4): chat screen (plan open question) |
| Incognito | `ChatHub.setIncognito` (`:420`) | excl(4): chat screen (plan open question) |
| "Allowed without asking" apps | `ChatHub.setAllowed(ses,pkg,on)` (`:520`), `allowedFor` (`:528`); eligible only if `Guard.allowable(c,pkg)` (`Guard.java:407`) | cov(005) `chat/<id>/allowed/<package>` |
| Chat Linux tools / background / schedule | | cov |

## Prior art
- 004 plan "Deliberately not covered" (`docs/features/004-self-modify/plan.md`, end of section A) lists exactly these; says "each can join the table later with one row".
- No upstream equivalent (upstream keeps these user-only; 004 research).

## Constraints and risks
- Availability checks must mirror the UI: signed-in/ready agents (`SetupState.signedIn`, `planSignedIn`, `notReady()`); Android 12+ for audio device and wallpaper accent; `NotificationWatcher.enabled` for pause_media.
- Dynamic allowed-values (audio devices, installed apps, service ids, models): `Setting.allowed` is a static string; listing must be computed per call.
- Most Prefs setters are unvalidated strings (see Summary), so a bad value from the tool would persist and e.g. `liveAgent` silently resets via `Account.known` (`Prefs.java:270`) but `backend` does not: validate in `check()`.
- Voice/engine and browser changes have side effects beyond the pref: mirror `Mirrors.apply` (thread), browser `Browser.install`/`stop`, `Voice.stopSpeaking`, `ChatHub.changed()` after default model. Reuse the same call sequence as the UI handlers (line refs above) or the state drifts.
- Lowering-protection settings (button "never ask" list, "Allowed without asking", reviewer, built-in words off) are on the same card gate; none removes the card itself. Confirm with Planner that none is treated as excl(3). `phone_commands_ask` precedent is the only excl(3) in 004.
- Mirror custom address and Providers hosts may contain credentials (unverified): keep custom out.
- Card shows values whole (20,000 cap); csv/list values fine.
- Wear/F-Droid: tool is github-flavor only; no change.
- Strings: new card text reuses `tools_card_setting`; any new notes are code strings in `SettingsTool` (check how 004 handled i18n, unverified).

## Answers to open questions
None (no plan.md yet for 005).
