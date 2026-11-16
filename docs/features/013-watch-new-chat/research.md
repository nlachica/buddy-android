# Research: 013-watch-new-chat
<!-- Researcher writes here. Sources on every claim. -->

## 2026-11-16 (claude-sonnet-5-5)

## Summary
- Everything keys on a chat id the *phone* owns: the watch's talk screen, `Talk.chat`, the `last` prefs, the reply filter
  (`chat.equals(in.optString("chat"))`, `Talk.java:320`), `/buddy/status`, the call header (`WatchCall.java:78,118`) and
  the ongoing notification (`Talk.java:303`). A new chat has no id until the phone creates it. That is the central design question.
- Only the phone creates chats: `ChatHub.create(backend, select, dev)` (`ChatHub.java:215`) → `Sessions.create`
  (`Sessions.java:191`) which makes a 12-char id, saves the chat file at once (`save(s)`, line 198) and puts it first in the list.
  There is no cleanup of empty chats, so creating on a request that is then cancelled leaves a ghost chat.
- "Buddy's default agent and settings": `Prefs.backend(c)` (default "claude", `Prefs.java:134`) is the default agent;
  `create(backend, select)` takes Linux tools from `Prefs.devMode` (`Sessions.java:186-188`) and background from
  `Prefs.backgroundDefault` (`Sessions.java:193`). Phone-side callers that do the same: `Bubble.java:1637`
  (`hub.create(Prefs.backend(svc))`), `OnboardingActivity.java:501`. The New chat sheet (`MainActivity.java:487-522`)
  refuses agents not `SetupState.signedIn(this, agent)` (`SetupState.java:38`); the watch path has no such check yet.
- Phone `deliver` (`WatchLink.java:130-155`) already does: empty text → error, unknown chat → `watch_chat_gone`,
  `BuddyService.get()==null` → `watch_buddy_off`, else `hub.send`, `/buddy/heard`, `tracked.put`. Chat creation fits
  naturally *after* those checks (so the unreachable/off case creates nothing) if the watch sends a "new chat" marker
  instead of an id.
- The phone answers every `/buddy/*` reply with a `chat` field; the watch needs the real id on `/buddy/heard`
  (`Talk.java:327-332`, filtered at line 320 by the old id). A new-chat flow must either have the watch learn the id
  from `heard` (then adopt it into `Talk.chat`, `last` prefs, notification, status checks) or get the id before sending.
- Watch UI entry points: list rows are built in `ChatsActivity.showChats` (`ChatsActivity.java:150-168`); a row's tap →
  `open()` → `Talk.intent(...)` (line 170-172). The list is also shown by 009's `"list"` extra (`ChatsActivity.java:68`).
  The empty-list case returns early (line 153-156), so a "New chat" row must be added before that return to show with zero chats.
- Auto-listen exists only through the `"speak"` extra on `TalkActivity` (`TalkActivity.java:130-137`); a list pick
  deliberately does not listen (009 decision). The new-chat entry must set `speak` (brief: "listening").
- Watch has no unit tests (`wear/src` has only `main`); phone tests are in `app/src/testGithub` and `app/src/test`
  (CI runs `testGithubDebugUnitTest`, `.github/workflows/build.yml:63`). Verification of watch UI is device-only.
- Protocol compat: watch APK and phone APK are released together (`build.yml:107` note; 003 decisions). A new
  message field/path needs no version negotiation, but an old phone would treat an unknown chat id as `watch_chat_gone`.

## Repo map
- Watch (`wear/src/main/java/app/buddy/assistant/wear/`):
  - `ChatsActivity.java` – home/list; `route()` 63-70; `talkToLast` 77-85; `remember()` 88-91 (writes `last` prefs: chat,
    title, accent); `showChats` 150-168; `open` 170.
  - `TalkActivity.java` – talk screen. onCreate reads extras `chat/title/accent` (66-68); pill → list (83); mic → `listen()`;
    `onActivityResult` (≈196-211) does `remember` + `Talk.begin(..., Phone.SEND {chat,text})`; `call()` (≈160) needs a real chat.
  - `Talk.java` – process-wide conversation service. `begin()` 156-177; `intent()` 180; `onPhone` 316-390
    (chat filter 320; HEARD 327; REPLY does `remember` at 338; ERROR → `settle`); `askStatus` sends `STATUS{chat}`; `end()`.
  - `Phone.java` – paths (`CHATS, SEND, HEARD, ...`) and `send()` (uses capability lookup; unreachable → `phone_unreachable` / `phone_no_link`).
  - strings: `wear/src/main/res/values/strings.xml` (`chats_empty` l.8, `talk_chats` l.20, `retry` l.12). Check other locales exist with `ls wear/src/main/res`.
- Phone (`app/src/watchlink/java/app/buddy/assistant/`): `WatchLink.java` (`onMessageReceived` 59-85, `chats()` 88-106
  returns `{accent, chats:[{id,title,running}]}`, `deliver` 131-155, `refuse` 157, relay 163-, `endCheck`); `WatchCall.java`
  (chat from channel header, `find` at 118 → `watch_chat_gone`). Strings `watch_*` in `app/src/main/res/values/strings.xml:739-741` (English only, per 003).
- Phone core: `Sessions.Session.label()` (`Sessions.java:147`) = `title` or `session_new_chat` ("New chat" + brain name) – a usable title for the watch pill before the agent titles the chat.
  `ChatHub.send(ses, text)` returns an error string or null (`ChatHub.java:250`).
- Docs: 003 plan has the message table (`docs/features/003-wear-companion/plan.md`), 007 adds live paths, 009 decisions (default chat, list/`speak` semantics), 012 for the latest watch work.
- Build/test: `./gradlew testGithubDebugUnitTest :wear:assembleRelease assembleGithubRelease assembleFdroidRelease` (CI, `build.yml:63`). `app/build.sh` is the fdroid/offline path (no `src/watchlink`).

## Prior art
- In-repo only. Phone-side "new chat from voice with default agent" is `Bubble.talk()` (`Bubble.java:1631-1640`): creates with
  `Prefs.backend(svc)` and sends. `MainActivity.java:518-523` is the explicit sheet (agent + dev switch, requires signed-in).
- Nothing in upstream watch code (the watch app is a fork-only feature, 003); no external references needed.

## Constraints and risks
- **Ghost chats**: any create-before-speech design leaves an empty chat if the user cancels the recognizer or the phone
  vanishes. Done-when 2 requires "no ghost chat" when the phone is unreachable. Creating inside `deliver` after a successful
  `Phone.send` path (i.e. on the first message) satisfies this by construction; creating on a separate `/buddy/new` request
  does not unless the watch only issues it after speech (then it is just a longer `SEND`).
- **Unusable default agent**: `Prefs.backend` may name an agent that isn't signed in (`SetupState.signedIn`); phone
  `deliver` would create the chat and `hub.send` would then fail, leaving a ghost. Check before creating (unverified what `hub.send` does for a signed-out agent; read `ChatHub.java:288+`).
- **`select` flag**: `Sessions.create(..., select=true)` makes the new chat the bubble's current chat (`Sessions.java:195`);
  a watch chat arguably shouldn't take over the phone's bubble (rule chats use `select=false`, `Triggers.java:543`). Planner's call.
- **Id arrives late** (if created on first send): `Talk.begin` sets `Talk.chat` before the send; `onPhone` drops anything whose `chat` differs (line 320);
  `STATUS`, notification, `remember`, `Call.start` all use the old value. Needs a defined "pending new chat" state, and the
  `last` prefs must not store an empty/sentinel id (`talkToLast` would reopen a dead chat; `ChatsActivity.java:80-84`).
- **Ids as sentinel**: the phone treats unknown ids as `watch_chat_gone`; a sentinel such as `""` hits `ses == null`. Use an explicit field (e.g. `"new": true`) rather than relying on an empty id.
- **Call button on a not-yet-created chat** fails (`WatchCall.java:118-120`); the new-chat screen must hide/disable it or create first.
- **006/009 relaunch counter**: `ChatsActivity.relaunches` / `TalkActivity.opened` (`TalkActivity.java:57,66,155`) – starting TalkActivity from the list (not via `route`) doesn't touch it; keep new-chat start the same as `open()`.
- **Unreachable phone**: the existing `Phone.send`/`Talk` error path (`Phone.java:52-62`, `Talk.settle`) covers it; with creation on the phone nothing is created.
- Duplicate-send risk: `Talk.begin` can queue/join sends; if a retry re-sends a "new" request after the phone created the chat but the `heard` was lost, a second chat appears (10 s `NO_HEARD_MS`, `Talk.java:~97`). Idempotency (e.g. a watch-generated request id) is something the Planner should weigh.
- Phone Wear classes are github-flavor only; no F-Droid impact (`build.yml:82`).

## Answers to open questions
None (plan.md has no Open questions yet).
