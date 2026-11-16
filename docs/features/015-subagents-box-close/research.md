# Research: 015-subagents-box-close
<!-- Researcher, 2026-11-16 -->

## Summary
- The "Sub-agents box" is `helpersBox` in `ChatActivity` (a rounded CARD column, hidden when the chat has no sub-agents), filled by `renderHelpers()`. There is a second, separate list in the bubble panel (`Bubble.helpersList`); the brief's non-goal says leave it alone.
- `renderHelpers()` is called from the chat's refresh (`ChatActivity.java:2035`, on every state change) and rebuilds the box only when the id signature `helpersShown` changes; per-card state (dot, state text) updates in place (`ChatActivity.java:823-868`).
- "Done" = `!Sessions.Session.running` for every `Helpers.of(hub, ses.id)` entry. `outcome` is `"failed"`/`"stopped"`/else done (`ChatActivity.java:857-862`). `Helpers.anyRunning` is private (`Helpers.java:51`).
- A "round" has no stored id. A new delegate round deletes all old helpers (`hub.delete(old)`, `Helpers.java:90`) and creates new sessions with new ids, `slot` 0..n (`Helpers.java:91-95`). So the id list (`helpersShown`) changes on every new round, which is a natural "fresh box" signal. Same-ids re-renders (state flips) are not a new round.
- A new round is refused while any helper runs (`Helpers.java:75-77, 85-88`), so a closed state can only be cleared by a new round after all are done.
- Close state is in-memory only unless stored: no `onSaveInstanceState` in `ChatActivity` (grep: none). Sub-agent sessions persist in `Sessions` (`parent`, `slot` saved/loaded, `Sessions.java:371`). A persistent flag would need a new field on `Session` (save/load near `Sessions.java:371,410`).
- Reports: the parent agent gets them as the `delegate`/`subagents` tool result (`Helpers.java:138-184`), which appears in the parent chat's steps. The user reaches each sub-agent's own chat only through the box's cards (tap opens `ChatActivity` with `id`, `ChatActivity.java:~912`) or the bubble panel rows (`Bubble.java:3049-3090`). Closing the box hides the cards, so the bubble panel rows are what remain (verify they show when the app chat box is closed: they key off `Helpers.of`, not the box).
- Look to match: `ms_close`, `ms_expand_less`, `ms_expand_more` drawables exist (`app/src/main/res/drawable/`). Box title is `sectionLabel` (13sp, MUTED, 600, padding 20/20/20/8, `Ui.java:245`). `icon(ctx, res, color, dp)` is the icon helper (used at `ChatActivity.java:~893`). Colors: CARD, CARD2, MUTED, OUTLINE, TEXT, ACCENT, OK, ERR.
- New strings go in `values/strings.xml` (helper strings at lines 2085-2093) and per-locale files exist (de, es, fr, hi, it, nl, pt, ...). (unverified: whether a test enforces locale coverage; `SettingsCoverageTest` exists but looks settings-specific.)

## Repo map
- Box creation: `ChatActivity.java:172-180` (`helpersBox`, `helpersShown` at `:748-749`).
- Render: `ChatActivity.java:821-880` (`renderHelpers`): helper case (`parent != null`) shows a single "Sub-agent of ..." row (must stay, no close); parent case shows title + horizontal card row. Title is added via `sectionLabel` at `:836`, so a close button needs a header row replacing/wrapping it.
- Cards: `helperCard` `:~880-932`; picture loop `loadHelperPictures` `:944-1016` (stops when `helperCards` empty or not resumed; `dropHelperCards` `:1018` unwatches browser tabs). Closing should call `dropHelperCards()` so the 1 s picture loop and `Browser.watch` stop.
- The "own" card ("This chat") only exists when helpers exist (`ownScreen = !l.isEmpty() && ...`, `:823`): hiding the box hides it too.
- Sessions/helpers: `Helpers.of` `Helpers.java:38`; round creation `:62-121`; `Session.slot`/`parent` `Sessions.java:94-96`; `running` `:104`; `outcome` `:136`.
- Bubble panel list (separate, not in scope): `Bubble.java:432-439, 3046-3090`.
- Docs to update when behavior changes: `docs/GUIDE.md:134` ("The next round of sub-agents replaces the last one's"), maybe `README.md:50`.

## Prior art
- `ChatActivity.java:2035` single render path; ordering of states already handled in place. No existing collapsible/close pattern for this box. Other closable cards in the chat (not checked in depth, `(unverified)`): grep `ms_close` usage in `ChatActivity`/`Ui` for a house style.
- No `docs/decisions/` directory exists; other features' `docs/features/00*/decisions.md` are the only precedent.

## Constraints and risks
- Re-render must not re-show a closed box on state flips; reset on a new id signature (new round) or when box was never closed. Key the closed state by the helpers' id signature (e.g. `closedFor = ids`), so the next round (new ids) shows fresh without extra bookkeeping.
- Close while any `running`: forbidden by the brief; the control must also vanish if a helper is still running (a helper `running` can flip back? After done it does not restart: delegate deletes and recreates). A helper could be added with `hub.create`... `h.running` set by `sendHelper`; `err != null` leaves `running` false with `outcome failed` (counts as done).
- Closed state across activity re-creation (rotation/reopen) is lost if kept in a field; decide in plan whether reopening the chat should show the box again (brief says only "hides it until the next delegate round"). Persisting means a `Session` field and JSON save/load.
- `renderHelpers` early-returns in the helper-chat case and the signature includes the "p"+parent label; don't break it.
- Hidden box means `loadHelperPictures` must stop (battery, `Browser.watch`); also `ownApp` lookups.
- Build/test: CI runs `./gradlew --no-daemon testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease :wear:assembleRelease` (`.github/workflows/build.yml:63`); unit tests in `app/src/test/java/app/buddy/assistant/` are JVM-only (no UI test for ChatActivity exists), so the visibility logic is best extracted into a small pure function for a test (a suggestion for the Planner). Helper tests `bash linux/test/run.sh` unaffected.
- The fork's tag/release flow (AGENTS.md) is separate; BuildInfo not touched by this feature unless the plan ships it.

## Answers to open questions
- None (plan.md has no open questions).
