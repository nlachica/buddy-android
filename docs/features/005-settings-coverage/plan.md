# Plan: 005-settings-coverage
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal

Extend 004's `buddy_settings` so that it lists and changes every setting on Buddy's Settings pages, all permissions
and all ordinary settings, plus the chat's "Allowed without asking" that the brief names. 004's gate stays as it
is: one card per change showing old → new, only the user's APPROVED writes, a value changed while the card was open is
not overwritten, a denial leaves the setting as it was, and secrets are never shown.

Done when (brief): (1) research.md holds a checked inventory of every control on every Settings page, each marked
covered or excluded with the non-goal that excludes it; (2) `buddy_settings` lists and changes every control that
isn't excluded, with checks that match the screen's own choices; (3) JVM tests cover the new families (validation,
card text, stale-write refusal) and CI is green; (4) on the phone the list shows the new entries, and for each new
family one change is approved and one is denied.

Non-goals (brief): secrets (API keys, tokens, passwords, MCP and service addresses and keys); flows the user does
(sign-ins, pairing, backup/restore, downloads/installs of agents); changing the approval gate or letting the agent
answer cards; settings the screens don't offer. Also out (this plan): a chat's model, effort, keep-awake and
incognito switches, which are on the chat screen, not on Settings (see Open questions); UI changes; any change to
004's gate or rows beyond `theme` (below); the F-Droid flavor (the tool stays github-only, as in 004).

## Design

### Approach: more rows in 004's table, every one a `Scalar`

Each new setting is a `SettingsTool.Scalar`: a getter, a setter, its choices, and, where the screen has an extra
rule, an override of `check` (as 004's `phone_commands` adds the pairing check) or of `card`. Families (a button rule, an
API key service, a chat's allowed app, an agent's default model) are Scalars that `find()` builds per item, like 004's
`chatSwitch`. `change()`, the gate, is untouched, so card, approval, re-check and write work exactly as in 004.

**One change to `Scalar`**, needed by two rows. Its choices may come from a `Supplier<String[]>` read when the setting is
checked or listed. `audio_device` needs the devices connected now, and `download_server` offers `custom` only once
the user has saved their own server. A value matches a choice ignoring case and is stored in the choice's own spelling,
because audio device keys are mixed case (`7:Galaxy Buds2`). 004's varargs constructor wraps its array, and all of
004's choices are lower case, so 004's rows behave as before. The listing and the "takes: …" refusal show the live
choices. Lazy choices also keep `scalars(null)`, which the JVM tests call, from loading Android-backed classes
(`CodexSpeech` creates a `Handler` when it loads).

**Values.** Rows use the screen's choices, `on`/`off` for switches, and comma-separated lists where the screen picks
several. Where the screen stores "nothing chosen" as `""`, the tool gives that choice a name (`default`, `auto`) and
the setter maps it back to `""`. That keeps "Already …" working (the gate reads a checked `""` as "delete"), and `""`
keeps its 004 meaning for items: delete, or take off a list.

**Checks run in this order.** First the shape: a listed choice, six hex digits, a known language tag. Then what the
screen greys out: an agent not signed in, Android older than 12, no saved picture, an app that can't be allowed. Shape
checks need no phone, so JVM tests reach them through `find(null, name)` or the pure helpers below. Availability
checks read Buddy's state and are tested on the phone. Checks run on the tool thread, as in 004, and only read
thread-safe state (Prefs, PackageManager, the synchronized `Providers` and `Models` stores).

**Side effects.** Each setter repeats the state changes that the screen's handler makes (file:line in the tables). It
doesn't repeat UI feedback: no toast, no sign-in screen, and no voice sample after a voice or device change. Instead
the setter calls `Voice.stopSpeaking()`, so the next speech uses the new choice. 004's `theme` row also gets
`Theme.changed(svc)`, the screen's own call (`SetupActivity:197`), so the bubble restyles at once, as it does for
`accent` and `surface`.

**Testable seams, without a new abstraction.** Family rows come from static factories that take the item's getter and
setter, e.g. `button(pkg, label, app, get, set)`, `allowedApp(chat, pkg, app, get, set)`, `serviceModels(id, ids, get,
set)`, `model(name, options, services, get, set, note)`. `find()` passes the Guard / ChatHub / Providers / Prefs calls
and the tests pass in-memory ones. That is the `Scalar` constructor's own shape, reused. There are also two pure
helpers: `accent(String)` and `languages(String)`, each returning the canonical value or throwing
IllegalArgumentException.

### New settings

Paths: `SetupActivity` unless named. "Signed in" = `SetupState.signedIn`, the screens' `notReady()`
(`SetupActivity:1611`); "plan" = `SetupState.planSignedIn`.

**Agents and models**

| name | values | check (as the screen) | applied (as the screen) |
|---|---|---|---|
| `default_agent` | claude, codex, opencode, agy, grok | signed in | `Prefs.setBackend` (`:488`). Note: new chats use it |
| `default_model/<agent>` | `default`, a model the picker lists (`Models.options(agent, true)`: the agent's own, and `@service/model`), or a typed model name | the agent is on one of the two "Chats on Default" screens: signed in (`Ui.defaultsSheet`, `Ui.java:495`) or with a usable API key service (`Providers.forAgent`, `ApiKeysActivity:84`). A typed name is one line, no spaces, at most 200 characters, and starts with `@` only if it is listed | `Prefs.setAgentDefault(svc, agent, "" for default)`, then `ChatHub.get(svc).changed()` (`Ui.pickDefault`, `Ui.java:520-526`). Reading the item lists the options with their names |
| `service/<id>` | on, off | the service exists (`Providers.find`) | `Providers.setOff(svc, p, !on)` (`ApiKeysActivity:214`) |
| `service/<id>/models` | `all`, or comma-separated model ids from the service's list | each id is in `p.models` (matched ignoring case, stored as listed, duplicates dropped) | `p.shown` replaced, then `Providers.save(svc, p, null)` (`null` keeps the saved key; `ApiKeysActivity:435-437`) |
| `download_server` | default, ustc, tuna, aliyun, yandex, mobinhost (`Mirrors.ALL` ids), plus `custom` once the user has saved a server of their own | none | `Prefs.setMirror(svc, "" for default)`, then `Mirrors.apply` on a new thread (`chooseMirror`, `:1321-1325`). Note: Buddy's Linux uses it from now on. The custom address is never shown or set |

**Voice**

| name | values | check | applied |
|---|---|---|---|
| `voice_input` | phone, claude, chatgpt | claude needs Claude Code's plan, chatgpt needs Codex's (`:583-584`) | `setVoiceEngine` (`:590`) |
| `voice_output` | phone, chatgpt | chatgpt needs Codex's plan | `setSpeechEngine` (`:600`) |
| `chatgpt_voice` | the `CodexSpeech.VOICES` ids | none. The listing says it is used while `voice_output` = chatgpt, the only time the screen shows it | `setSpeechVoice`, `Voice.stopSpeaking()` (`:607-609`) |
| `audio_device` | auto, or a key from `AudioRoute.choices(svc)` (Android 12+; earlier, auto only) | none | `setAudioDevice(svc, "" for auto)`, `Voice.stopSpeaking()` (`:620-622`). A saved device that is away still shows, and auto clears it, as on the screen |
| `voice_languages` | comma-separated tags from `Voice.LANGUAGES`; the first is the one Buddy listens for | at least one; each a listed tag (any case); duplicates dropped | `setVoiceLangs(csv)` and `setVoiceLang(first)` (chips and long-press, `:650-668`). The current value shows `Prefs.voiceLang` first, then the rest in stored order |
| `assist_mode` | talk, live | none | `setAssistMode` (`:680`) |
| `live_agent` | default, claude, codex, opencode, agy, grok | an agent (not default) must be signed in | `setLiveAgent(svc, "" for default)` (`:689`) |
| `live_continue` | on, off | none | `setLiveContinue` (`:698`) |
| `pause_media` | on, off | none (the screen saves it too) | `setPauseMediaForSpeech` (`:897`). The reply notes when it can't work yet: notification access is off (`NotificationWatcher.enabled`; the user grants it, as the screen sends them to do), or `read_aloud` is off |

**Safety and approvals** (`AppPermissionsActivity`)

| name | values | check | applied |
|---|---|---|---|
| `reviewer` | same, claude, codex | claude and codex must be signed in (`:90-91`) | `setReviewer` (`:94`) |
| `reviewer_model/<claude or codex>` | default (the quick one: Haiku, or a small Codex model), a model that `Models.options(h, false)` lists, or a typed name (no `@`: the reviewer's picker offers no services) | the harness is signed in (the row only shows then, `:101`) | `setReviewerModel(svc, h, "" for default)` (`:114`) |
| `builtin_risky_words` | on, off | none | `setGuardBuiltin` (`:270`) |
| `button/<package or *>/<label>` | ask (always ask), allow (never ask), `""` (on neither list) | the label isn't blank; a package must be an installed app; `*` = every app | ask/allow: `Guard.addRule(svc, list, new Guard.Rule(label, pkg or null, app label))` (`:319`, `:344`; it also takes the button off the other list). `""`: the matching rule (same `Guard.norm` label and package) comes off both lists through `Guard.saveRules` (`:280-282`). Snapshot: which list holds it |

**Background and automation**

| name | values | check | applied |
|---|---|---|---|
| `background_browser` | on, off | none | `setBrowser`; on → `Browser.install(svc)`, off → `Browser.stop` on a new thread (`:1461-1463`) |

**Appearance**

| name | values | check | applied |
|---|---|---|---|
| `accent` | coral, ocean, mint, violet, rose, amber (`Theme.ACCENTS` names), wallpaper (Android 12+), or `#RRGGBB` | six hex digits, as the custom sheet takes (`:1290-1295`); a hex equal to a preset becomes that preset's name | preset or hex → `setAccent(colour)`; wallpaper → `setAccentFromWallpaper()` (`swatches`, `:1212-1236`); then `Theme.changed(svc)`. Current value: wallpaper, a preset name, or the hex |
| `surface` | the `Theme.SURFACES` ids | none | `setSurface`, `Theme.changed(svc)` (`:435`) |
| `face` | buddy, picture | picture: a picture is saved (`Face.saved`); picking one is the user's file flow | `setFace`, `svc.applyFace()` (`:454-461`). If the saved picture can't be read, the setter puts back buddy and answers "Not changed", as the screen falls back. Current value: what the screen shows (`Face.picture != null`) |

**Chats** (`ChatActivity`)

| name | values | check | applied |
|---|---|---|---|
| `chat/<id>/allowed/<package>` | on, off | on: an installed app that `Guard.allowable` accepts (`:1642`: no money, Settings, installer or store apps) | `ChatHub.setAllowed` (`:1323`, `:1614`) |

**Cards** follow 004's text (`Change Buddy's setting <name> / Now / New`). Rows that lower protection or cost data
spell out what the value does (the same text goes in the reply):
- `button/…`: `allow: never ask; agents tap “<label>” without a card in <app | every app>`,
  `ask: always ask before an agent taps “<label>” in …`, `""`: `on neither list`.
- `builtin_risky_words` off: `off: Send, Pay, Delete… no longer always ask`.
- `chat/<id>/allowed/<pkg>` on: `on: routine approvals in <app> go ahead without asking until this chat's task ends`
  (plus "and in its scheduled runs" when the chat has a schedule, `ChatHub:980`).
- `background_browser` on, while Chromium isn't installed: `on: Buddy downloads Chromium (about 300 MB) now`.

**Listing.** 004's listing, plus these additions in Settings-page order: the new scalars; one `default_model/<agent>`
line per agent the screens show and one `reviewer_model/<h>` line per signed-in harness (value and "name=… lists
the models"); an "API key services" section (`service/<id> = on  (<name>; models shown: all of N | K of N)`, never
an address, host or key); a "Button lists" section (`button/*/Pay = ask`); and each chat line gains `allowed: none |
<packages>`. Reading an item adds what its screen offers: an agent's models with their names, a service's model ids.

### What is covered and what is excluded (the done-when inventory)

Covered: every **add** row of research.md's inventory, as above, and `service/<id>/models`. Research suggested
excluding that one, but it holds no secret and saves with the key untouched (`save(…, null)`), so no non-goal
excludes it. Research's `voice_languages` and `voice_language_default` become one setting.

Excluded, by the brief's non-goals (step 7 writes the final status into research.md's inventory):
1. **Secrets:** API keys, service addresses and adding/editing/removing a service (the editor holds the key); the custom
   download server's addresses (`Mirrors.address` allows a path, so a token can sit there; same rule as MCP addresses
   in 004); the phone-tools command (it holds `Prefs.token`); MCP keys and addresses (004); SSH's one-time code.
2. **User flows:** the accessibility and battery steps, agent install and sign-in, GitHub, background-mode pairing,
   Remote Control, the Codex session's pairing, the SSH server, backup/restore, importing a skill zip, picking a face
   picture, opening the browser and its sign-ins, wiping browser data, granting notification access.
3. **The approval gate:** `phone_commands_ask` stays read-only (004).
4. **Not offered by the Settings screens:** a chat's model, effort, keep-awake and incognito (chat screen; see Open
   questions).

Not settings (actions, navigation or screen state; nothing to change): Check for updates, Help, Setup check, the
rules' Clear activity, removing old Termux chats, the phone-tools agent picker, the "seen" buttons (suggestions for the
lists). The brief's "Experience" page doesn't exist in this build: the Settings menu has eight pages
(`SetupActivity.pages()`, `:253-263`), and research walked all of them.

### Settings that lower protection

The never-ask buttons, `builtin_risky_words` off, a chat's allowed apps and the reviewer choice reduce what asks the
user. They stay changeable on the same card, each card spelling out the effect, as 004 did for per-app trust and a
rule's approval mode. None of them reaches the gate itself: the settings card and the install card come only from code;
`Guard.offLimits` / `Ui.protect` are checked before any list (`Tools.java:1612`), so Buddy's own screens and cards stay
the user's; a never-ask rule is skipped in rule runs (`Tools.java:1623`) and `Guard.runRule` never applies in one;
`phone_commands_ask` stays read-only.

### Alternatives (one line each)

- A `Setting` subclass per row: more code for the same thing; `Scalar` with overrides covers every row.
- A generic Prefs key editor: skips side effects and exposes internal keys (004 rejected it).
- Whole-list JSON values for the button lists and allowed apps: harder to read on a card; per-item names match `app/<pkg>`.
- Two settings for languages: the screen has two gestures but one stored list, whose first entry already means
  "default" in Prefs.
- Refusing `pause_media` without notification access: the screen saves it and sends the user to grant access; same here.
- Covering the chat's model, effort, keep-awake and incognito now: not on Settings and not named in the brief; each is one
  row later.

## Steps

1. **Scalar choices.** `SettingsTool.java`: choices from a `Supplier<String[]>` (the varargs constructor wraps its
   array); match ignoring case and return the choice's spelling; listing and refusal text from the live choices.
   New `app/src/test/java/app/buddy/assistant/SettingsCoverageTest.java` with the Scalar cases (Test plan 2a).
   `ApprovalGateTest` must pass unchanged.
2. **Agents and models.** `SettingsTool.java`: `default_agent`, `download_server`, `default_model/<agent>`,
   `service/<id>`, `service/<id>/models` (factories `model(…)`, `serviceModels(…)`), their `find` routes, the
   default-model lines and the "API key services" section of the listing (never a host or key). Tests 2b, 3.
3. **Voice.** `SettingsTool.java`: `voice_input`, `voice_output`, `chatgpt_voice`, `audio_device`, `voice_languages`
   (`languages(…)`), `assist_mode`, `live_agent`, `live_continue`, `pause_media`. Tests 2b, 3.
4. **Safety and approvals.** `SettingsTool.java`: `reviewer`, `reviewer_model/<h>`, `builtin_risky_words`, the
   `button(…)` family (name parsing `button/<pkg or *>/<label>`, get = the list holding it, set via
   `Guard.addRule`/`saveRules`), its `find` route, the "Button lists" section, the cards above. Tests 2b, 3.
5. **Background and appearance.** `SettingsTool.java`: `background_browser` (its card), `accent` (`accent(…)`),
   `surface`, `face`; `Theme.changed(svc)` in `theme`, `accent` and `surface`. Tests 2b, 3.
6. **Chats.** `SettingsTool.java`: `chat/<id>/allowed/<package>` (`allowedApp(…)`), `find` for four-part chat names,
   `allowed:` on each chat line, the card. Tests 2b, 3.
7. **Description and inventory.** `SettingsTool.list`: `value ""` also takes a button off the lists, and the families
   are named in a few words. `docs/features/005-settings-coverage/research.md`: each inventory row gets its final
   status: covered (setting name), excluded (non-goal number), or not a setting, matching this plan. No other doc
   changes: `docs/GUIDE.md:204` and `AGENTS.md:24` already say "these settings, and others".

All code lives in `SettingsTool.java` and its tests. No upstream file changes, so upstream merges don't touch this
feature.

## Test plan

| Done-when item | Proof |
|---|---|
| 1. Checked inventory in research.md | Step 7. The Verifier checks that every row has a final status, that every "covered" name appears in the tool's listing on the phone (item 4), and that every "excluded" row names a non-goal. After step 7, no inventory row still says **add** (only the legend may). |
| 2. Lists and changes every non-excluded control, with the screen's checks | **Unit** (`SettingsCoverageTest`, JVM): (a) Scalar: a mixed-case value matches and is stored in the choice's spelling (`7:galaxy buds2` → `7:Galaxy Buds2`); a supplier is read at check time (it changes between two checks, so does the answer); 004 rows are unchanged (`ApprovalGateTest`). (b) `scalars(null)` holds every new scalar name, built without touching `svc` or Android-backed classes; shape refusals through `find(null, name).check(…)`, before any phone state is read: `surface` purple, `assist_mode` shout, `default_agent`/`live_agent` nobody, `voice_input` siri, `voice_output` claude, `reviewer` gpt, `builtin_risky_words`/`live_continue`/`pause_media`/`background_browser` maybe → refused; `Black`/`LIVE`/`ON` → `black`/`live`/`on`. `accent(…)`: `Ocean` → `ocean`, `#4f8bff` → `ocean`, `#123abc` → `#123ABC`; `#12345`, `blue`, `#GGGGGG` refused. `languages(…)`: `fr-fr, en-US, fr-FR` → `fr-FR,en-US`; `""` and `xx-XX` refused. `model(…)`: `default` kept; a listed `@svc/m` accepted, an unlisted `@svc/m` refused; `opus` accepted; `two words` and a 201-character name refused; with services off, every `@…` refused. `serviceModels(…)`: `all` accepted; `a, B` against ids `[a, b]` → `a,b`; `zzz` refused. `button` name parsing: `button/*/Log out` → every app, `Log out`; `button/com.x/a/b` → `com.x`, `a/b`; `button/*/ ` and `button/bad pkg/x` → not a setting. **Device**: availability refusals: `voice_input claude` / `reviewer codex` with that agent not signed in (if one isn't), `face picture` with no saved picture, `chat/<id>/allowed/<a bank app>`, `audio_device` with a key not connected. |
| 3. JVM tests: validation, card text, stale-write refusal | Validation: item 2. **Card text** (the factories with in-memory get/set, the real format string as in 004's tests): `button` allow for `*` says "never ask" and "every app", ask says "always ask", `""` says "on neither list"; `builtin_risky_words` off names "Send, Pay, Delete"; `allowedApp` on says "until this chat's task ends"; `voice_languages` Now/New show the default first; `service/<id>/models` shows both lists whole. **Stale write** (fake asker changes the in-memory state before `APPROVED…` → "changed while the card was open", setter never called): `button` moved from ask to allow, and removed; `allowedApp` turned on by the screen; `serviceModels` list edited; `voice_languages` default changed; `default_model` set elsewhere. **No secret** (`Providers.P` built in the test with `openai = https://u:p@h.example/v1?token=abc`, `anthropic = https://sk-123.example`): the service's list line and read text contain no part of either address. **CI**: `./gradlew --no-daemon testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease` (`build.yml:59`) green on the branch (`gh run watch`). |
| 4. On the phone: new entries listed; per new family one change approved, one denied | Needs this build on the phone. After the merge, the orchestrator bumps `BuildInfo` (`2.3.9-fork.2`, `VERSION_CODE` = max(fork, upstream) + 1), tags with Nicholas's OK (a public release), watches CI, then calls `update_buddy install=true` (AGENTS.md "Updating Buddy on the phone"). Then, through the phone's `buddy_settings`: the list shows every new name and section, with no address or key. For each family, approve one cheap change and deny one, and check its screen shows the approved value and kept the denied one: `default_agent` (or `default_model/<agent>`); `service/<id>` (if a service exists, otherwise noted); `download_server` (approve, then set it back); `voice_languages`; `chatgpt_voice` or `assist_mode`; `audio_device` (if more than one device); `reviewer`; `builtin_risky_words`; `button/*/<test label>` allow, then `""`; `background_browser` (deny on; approve only if Nicholas wants the 300 MB download); `accent` and `surface`; `face` (if a picture is saved); `chat/<this chat>/allowed/<a messaging app>`. Plus one stale-write check: ask to change `accent`, change it in Settings before approving → "changed while the card was open". |

## Risks

- **Prompt injection loosens protection.** `button/*/Pay = allow`, `builtin_risky_words off` or an allowed app can make
  risky taps card-free in apps that aren't on trust. Mitigation: the card says exactly that, in plain words, and
  approving it is the user's call. This is the same class of risk as 004's per-app trust. What remains is the user
  approving without reading.
- **Live lists move.** An audio device, a model or a sign-in can change between the check and the commit. The commit
  writes the checked value; the screen keeps a saved device that went away too. Accepted.
- **Background work isn't reported back.** `Mirrors.apply` and `Browser.install` run on their own threads, as from
  the screen. The reply says it started; progress shows on Settings (the browser's state line). Accepted.
- **JVM tests and Android classes.** Table rows must not call `svc` or load Android-backed classes when built, and
  availability checks can only be tested on the phone. Lazy choices and the factories above keep the tested parts
  Context-free. `accent(…)` parses hex in plain Java: `Theme.parse` uses `android.graphics.Color`, which is a stub
  in JVM tests. The setter may still use `Theme.parse`.
- **The listing grows** by about 25 lines plus sections. Long service model lists only show on reading the item.
  Accepted.
- **Open screens go stale**, as in 004: the tool never writes over a change it didn't show (gate step 5).
- **Device rows need a release.** The tag is public, so the orchestrator confirms with Nicholas first. If the release
  can't happen in this run, item 4 stays pending and the verdict is PARTIAL (as in 003/004).

## Open questions

None blocking.

For Nicholas (each has a default):
- A chat's model, effort, keep-awake and incognito (chat screen, not Settings). Default: not covered in 005. Each is one
  `chat/<id>/…` row later. Incognito would put the chat's deletion on a card ("deleted when Buddy restarts").
- Tagging the release for the device checks. Default: yes, once CI is green and the PR is merged (orchestrator asks).
