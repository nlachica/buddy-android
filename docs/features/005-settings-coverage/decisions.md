# Decisions: 005-settings-coverage
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-15 - Every new setting is a `Scalar` row; families are Scalars built per item; the gate is untouched
- Options: a `Setting` subclass per row; a generic Prefs key editor; 004's `Scalar` (getter, setter, choices) with
  `check`/`card` overrides, and families built in `find()` like `chatSwitch`.
- Choice: `Scalar` rows, as the orchestrator asked ("004's style, table-driven rows"). Every new control is a value
  with choices and a screen-side rule, which a `check` override expresses (as `phone_commands` does for pairing).
  Family factories take the item's get/set, which is the `Scalar` constructor's own shape, so JVM tests can drive the real
  check, card and stale-write path with in-memory state. No new abstraction. `change()` stays as 004 has it.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - `Scalar` choices may be supplied lazily and match ignoring case (stored in the choice's spelling)
- Options: fixed varargs choices only (004); a `Supplier<String[]>` read at check/list time; separate subclasses for
  the dynamic rows.
- Choice: a supplier, with the varargs constructor wrapping its array. Two rows need it: `audio_device` (the devices
  connected now; mixed-case keys like `7:Galaxy Buds2`) and `download_server` (`custom` only once saved). It also
  keeps `scalars(null)` from loading `CodexSpeech`, which creates a `Handler` when it loads. All of 004's choices are
  lower case, so their behaviour doesn't change.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Named values for "nothing chosen" (`default`, `auto`); `""` keeps meaning delete / off the list
- Options: accept `""` for the stored-empty choices; name them and map to `""` in the setter.
- Choice: name them (`default_model`, `reviewer_model`, `live_agent`, `download_server` → `default`; `audio_device`
  → `auto`), as 004 did with `app/<pkg> = default`. The gate reads a checked `""` as "delete" (old must be null for
  "Already"), so `""` would always show a card. `""` stays the item value for delete or take off a list
  (`button/…`).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Scope: the eight Settings pages plus the chat's "Allowed without asking"; not the chat's model, effort, keep-awake, incognito
- Options: Settings pages only; plus the brief's named chat item; plus everything on the chat screen (research
  suggested model, effort, keep-awake, incognito).
- Choice: Settings pages plus `chat/<id>/allowed/<pkg>`. The brief's goal is "every setting on Buddy's Settings
  pages", its non-goal "only what the Settings screens already offer", and it names only "a chat's 'Allowed
  without asking'" from the chat screen. "Models" in its gap list means the Agents and models page (default models)
  and the reviewer's models. The other chat-screen switches are one row each if Nicholas wants them (open question).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Settings that lower protection stay changeable, each on its card with the effect spelled out
- Options: treat never-ask buttons, built-in words off, a chat's allowed apps and the reviewer as the approval gate
  (read-only, like `phone_commands_ask`); allow them on the card.
- Choice: allow them, each card naming the effect ("never ask; agents tap “Pay” without a card in every app"). This
  is 004's stance for per-app trust and a rule's approval mode. None of them reaches the gate: the settings and
  install cards come only from code, `Guard.offLimits`/`Ui.protect` run before any list (`Tools.java:1612`), never-ask
  is skipped in rule runs (`:1623`), and `phone_commands_ask` stays the one read-only switch. Nicholas's ask is
  "all the permissions … where Buddy can modify its own settings".
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Download server: the presets, and switching back to a saved custom server; its address is never shown or set
- Options: presets only; presets plus entering a custom address; presets plus `custom` when one is saved.
- Choice: presets plus `custom` when the user has saved one, shown as `custom` only. `Mirrors.address` allows a
  path, where a token can sit, so the address follows 004's MCP rule (no part of a stored address in any output).
  Entering one stays the user's (non-goal 1). The setter repeats the screen: `setMirror`, then `Mirrors.apply` on a thread.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - API key services: on/off and the shown-models list are covered; keys, addresses, add/edit/remove are not
- Options: exclude the whole API keys screen; on/off only (research's lean); on/off plus shown models.
- Choice: `service/<id>` (on/off) and `service/<id>/models` (`all` or ids from the service's list). Neither is a
  secret, and `Providers.save(…, null)` keeps the saved key untouched, so no non-goal excludes them, and the brief
  asks for every such control. The listing shows the id, name and model counts, never a host, address or key (as
  for MCP).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Voice languages are one setting: a comma-separated list whose first entry is the one Buddy listens for
- Options: `voice_languages` + `voice_language_default` (two gestures on screen); one csv.
- Choice: one csv. Prefs already documents "first = default". The setter writes the list and `setVoiceLang(first)`,
  and the current value shows `voiceLang` first. One name means one card, and the change can't be left half done.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Setters repeat the screen's state changes, not its UI feedback; `theme` gains `Theme.changed`
- Options: repeat each handler exactly (toasts, voice sample, sign-in screen); state changes only.
- Choice: state changes only: `Mirrors.apply` and `Browser.install`/`stop` on their threads, `ChatHub.changed()`
  after a default model, `Theme.changed(svc)` after theme/accent/surface, `svc.applyFace()`, and `Voice.stopSpeaking()`
  after a voice or device change, so the next speech uses it. No toast, sample or screen opens from a service
  call the user didn't make. 004's `theme` row gets the same `Theme.changed` (the screen's own call,
  `SetupActivity:197`), so the bubble restyles at once.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Availability follows the screen: grey-outs become refusals; "save, then ask the user" becomes a note
- Options: refuse everything the screen would need the user for; mirror the screen exactly.
- Choice: what the screen greys out is refused with the reason and who fixes it (agent not signed in or without its
  plan, Android < 12 for wallpaper, no saved picture, an app `Guard.allowable` rejects). What the screen saves and
  then sends the user to fix (`pause_media` without notification access) is saved, with a note in the reply. Agents
  for default models are the union of the two "Chats on Default" screens: signed in, or with a usable service.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Model values: listed options, or a typed name with no spaces (at most 200 characters); `@service/model` only when listed
- Options: anything typed (the screen's text field); listed options only; listed or a plain typed name.
- Choice: listed, or a plain typed name, as the picker's "other" field allows, minus spaces and an unlisted
  `@service/…` that could point at a service the agent can't use. The reviewer gets no `@…` at all: its picker
  offers no services (`services=false`).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Button rules are named `button/<package or *>/<label>`, values ask, allow or ""
- Options: whole-list JSON per list; `button/<label>@<pkg>`; `button/<scope>/<label>` with a state value.
- Choice: one item per button and scope, since a button is on one list at most (`Guard.addRule` enforces it). The scope comes first,
  so a label may contain `/` or `@`. Set goes through `Guard.addRule`, and `""` removes the matching rule (by
  `Guard.norm` label and package) from both lists. The snapshot is the list holding it, so a move or removal made
  on the screen while the card is open is caught.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - A service's name is covered (`service/<id>/name`); key, addresses, add and remove stay excluded
- Options: keep the whole editor excluded because it holds the key; cover the name field alone (plan review F1).
- Choice: cover the name. It is a separate field and no secret, and `Providers.save(…, null)` keeps the key. This
  amends the earlier service decision, which covered on/off and shown models only. Blank is refused instead of
  derived from the address, so the tool never reads addresses. Removing a service stays out because it deletes the
  saved key.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Service rows look the service up live and lock `Providers.class`; the snapshot is the record minus models
- Options: getters/setters closing over a `Providers.P`; live lookup with an identity token in the snapshot; live
  lookup with the non-secret record as the snapshot (004's `mcp/<id>`).
- Choice: live lookup through a `find` seam, `get` null once removed, snapshot = name, addresses, off and shown (not
  models, which `Providers.refresh` rewrites in the background), `view` showing only the part, `lock()` =
  `Providers.class`, and `set` refusing a missing service without calling `store`. A captured `P` outlives removal,
  and `save` re-adds a missing id (plan review F3). The record reuses 004's pattern, so there is no new concept. A
  re-added copy with identical details (only the key differs) passes, and the user can't tell it apart either.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Button rules: an installed app is needed only to put a new app-scoped rule on a list
- Options: always require installation (first plan); never check; check only for a rule on neither list.
- Choice: check only for a new rule. The screen removes stored rules whatever the package's state, so a rule for
  an uninstalled app must stay removable and movable (plan review F4). The card falls back to the stored app label,
  then the package.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Phone evidence: one approved and one denied card per family; a missing prerequisite means PARTIAL
- Options: a few representative changes ("or" lists, prerequisites "noted"); every setting; one row per `find()`
  pattern plus one per Settings page's scalar group.
- Choice: per pattern plus per page group, 12 rows (plan review F2). Page scalars share the `Scalar` path and are
  unit-tested; each item family has its own parsing and storage. "Already" and refusals before a card are not
  evidence. A missing prerequisite (no service, no signed-in harness, no OK for the browser download) leaves its row
  pending and the verdict PARTIAL.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Availability checks are a `Scalar.ready(...)` hook run after the shape check, not a `check` override per row
- Options: an anonymous `Scalar` subclass overriding `check` per row (plan wording); one `ready` hook on `Scalar`
  that `check` calls once the value matches a choice.
- Choice: the hook (`new Scalar(...).ready(v -> ...)`). Same order the plan wants (shape, then what the screen
  greys out) in one place, and rows with their own parsing (`model`, `service`, `button`) call it too. Fewer anonymous classes.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - Unit tests run with javac + JUnitCore against a Robolectric android-all jar here, not Gradle
- Options: install Gradle + the Android plugin (no SDK on this Alpine proot); `/tmp/buddy-build-004/check.sh` (gone).
- Choice: `/tmp/buddy-build-005/check.sh github|fdroid` (JDK 17 from apk; Java 21 can't start here): compiles main and
  tests against `org.robolectric:android-all:15` plus the real `org.json`, with a stub `R.java` made from `res/` names
  and a two-method shim for `android.system.Os` (the stub jar CI uses returns defaults there; android-all needs natives).
  One API-36 call (`setShortCriticalText`) is blanked in a scratch copy of the sources, never in the repo. Not Gradle: CI
  is the first Gradle run of these tests.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - Code review round 3 F3 closed by the orchestrator
- F3 was only the CI gate: `pipe ci` dispatched to the upstream parent (gh's default repo on a fork). Fixed in the
  playbook (`GH_REPO` from origin); CI run 43756642973 on 79a3e73 is green (unit tests + github, fdroid, wear builds).
  No code finding was open. By: Orchestrator (claude-opus-5-5)

## 2026-11-17 - Upstream 2.3.11 sync: `ask_new_apps` registered
- Upstream added the switch "Ask about new apps" (`Prefs.askNewApps`/`setAskNewApps`, SetupActivity). Registered it in
  `SettingsTool.scalars` as `ask_new_apps` (on/off) next to `agents_tap_buddy`, so it takes the same approval card
  with old and new value as the other safety settings. It is already in Backup's list. By: Orchestrator (claude-sonnet-5-5)

## 2026-11-17 - Upstream 2.3.12 sync: three switches registered
- Upstream added "Outside sessions in background" (`outside_background`), "Phone tools on Tailscale" (`tailnet_tools`) and
  "Pause media while listening" (`pause_media_for_listening`, pref `listen_pause_media`). Registered as on/off scalars in
  `SettingsTool.scalars` so each takes the approval card with old and new value. The paired-computers list
  (`tailnet_devices`) is not registered: it holds pairing keys, and stays this phone's own (Backup `OWN`). By: Orchestrator (claude-sonnet-5-5)

## 2026-11-17 - Upstream 2.3.12 sync: editing a rule keeps the fields the value leaves out (`chat`)
- Review found `rule/<id>` rebuilt the rule from the value alone, so an edit dropped `chat` (the binding 2.3.12's
  `add_notification_rule` sets) and reset every other omitted field to its default.
- Choice: the value is laid over the rule's current JSON (`merged` in `SettingsTool.rule`), for both the check and the
  write; `rule/new` is unchanged. `chat` is named in the setting's description. Merge, not replace, so a one-field edit works.
  By: Orchestrator (claude-sonnet-5-5)
