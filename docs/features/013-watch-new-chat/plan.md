# Plan: 013-watch-new-chat
<!-- Planner writes: Goal, Design, Steps, Test plan, Risks, Open questions -->

## Goal
The watch chat list (the 009 picker) gets a **New chat** entry at the top. Tapping it opens the watch talk screen
already listening. The first thing said there starts a new chat on the phone, with Buddy's default agent
(`Prefs.backend`) and the Settings defaults for new chats. The reply is shown and spoken on the watch as for any chat.
Done when: (1) New chat → a new chat with the spoken message appears on the phone, and the reply is shown and spoken
on the watch; (2) phone unreachable → the usual watch error and no ghost chat; (3) 003-012 behaviour is unchanged and
CI is green.

Non-goals (brief): choosing the agent, model or Linux tools from the watch; renaming or deleting chats. Also out:
showing the phone's later title for the chat on the watch, and changing the phone's own New chat sheet.

## Design
**The watch makes the chat id; the phone creates the chat when the first words arrive.**

1. **List.** `ChatsActivity.showChats` adds a "New chat" row first, before the chats and before the empty-list note.
   It is an accent pill with dark text, like Retry, so it reads as an action and not as a chat. The row is only shown
   once the phone has answered with the list, so an error screen shows only Retry. A tap makes a fresh id in the
   phone's format, 12 lower-case hex characters (`UUID.randomUUID()` without dashes, first 12), and starts
   `Talk.intent(id, "New chat", accent)` with the extras `"new"` and `"speak"`. It starts the screen like `open()`
   does, so 006/009's relaunch counter isn't touched. `speak` reuses 009's auto-listen (the id can't equal `Talk.chat`,
   so there's never "news" that would block it).
2. **Talk screen.** `TalkActivity` keeps `fresh = extra "new"`. While it is fresh:
   - each send's JSON gets `"new": true` (the value at the moment of speaking);
   - the send doesn't write the default chat (`remember`), because the chat may never come to exist;
   - the call button is hidden (the phone has no such chat for `WatchCall` to find).

   `draw()` clears `fresh` for good once `Talk.lines` has a YOU line for this chat. A YOU line comes only from
   `/buddy/heard`, so it means the phone took the words and the chat exists.
   Everything else (`Talk`, the reply filter, status checks, the notification, `ReadAloud`, the call header) works
   unchanged, because the watch has the real id from the start.
3. **Default chat.** `Talk.onPhone` `HEARD` also calls `ChatsActivity.remember(chat, title, accent)`. For existing
   chats this repeats the write already done at send time (a HEARD only passes the filter for `Talk.chat`, which was
   just remembered), so 009 is unchanged. For a new chat it is the "words sent" event of 009, at the moment the chat
   exists. The existing write at `REPLY` stays.
4. **Phone.** `WatchLink.deliver(node, chat, text, isNew)`:
   - `make = ses == null && isNew && newChatId(chat)` (`newChatId` checks `[0-9a-f]{12}`, the format
     `ChatHub.pruneLinux` already requires (`ChatHub.java:112`; one id outside it stops Linux pruning while that chat exists). The id
     also becomes a file name, `chats/<id>.json`);
   - checks, in the existing order: empty text → `hub_something_wrong`; `ses == null && !make` → `watch_chat_gone`;
     `BuddyService.get() == null` → `watch_buddy_off`;
   - only then, if `make`: `hub.sessions.create(chat, Prefs.backend(app), false, Prefs.devMode(app))` followed by
     `hub.changed()`. The chat gets the default agent, Linux tools from Settings and background from Settings. It is
     created with `select=false`, so it doesn't become the phone bubble's chat;
   - `hub.send`. If that returns an error and the chat was just made, `hub.delete(ses)` before the refusal. The
     chat is still empty at that point (send's errors come before `sessions.add`);
   - the rest is unchanged: `/buddy/heard` with the same id, `tracked.put`, `ses.watch`, relay.

   A repeated SEND with the same id finds the chat and is an ordinary send (a note if it is running). A retry after a
   lost HEARD, or a second phrase on the same screen, therefore never makes a second chat.
5. **`Sessions.create(String id, String backend, boolean select, boolean dev)`.** The body of today's
   `create(backend, select, dev)` moves here unchanged. The old method delegates with the random id. The defaults for
   new chats stay in one place, so the watch path follows any future upstream change to them.

Protocol change (released together, 003): `/buddy/send` `{chat, text, new?}`. `new: true` = "create this chat if
it doesn't exist". No new paths, and every reply already carries the same `chat`.

No ghost chats, by construction:
- an unreachable phone: nothing reaches the phone, and `last` isn't written;
- a recognizer cancelled before speaking: nothing is sent;
- Buddy off: the chat is refused before it is created;
- a send the phone refuses: the chat is deleted again.

Alternatives considered:
- The phone makes the id and the watch adopts it from `/buddy/heard`: a pending state in `Talk`, `TalkActivity`, the
  notification and `last`, plus a duplicate chat on a retry after a lost HEARD.
- A separate `/buddy/new` round trip after speech: two messages, and a ghost if the phone vanishes between them.
- Creating the chat on tap: an empty chat whenever the recognizer is cancelled or the phone is unreachable (done-when 2).
- The watch building the `Session` itself in `WatchLink`: copies `create`'s defaults, and they drift when upstream
  changes them.
- A `ChatHub.create(id, …)` overload: a second upstream file touched, for a call `WatchLink` can make through
  `hub.sessions`, as it already does.
- Refusing when the default agent isn't signed in (as the phone's New chat sheet does): the phone's voice path
  (`Bubble.talk`) doesn't check. The chat then holds the words and the agent's error, which the watch says, so it
  isn't a ghost (decisions).

## Steps
1. **Sessions id overload.** `app/src/main/java/app/buddy/assistant/Sessions.java`: add
   `create(String id, String backend, boolean select, boolean dev)` with today's body; `create(backend, select, dev)`
   becomes `return create(<random 12-hex id>, backend, select, dev);`. No behaviour change.
2. **Phone creates on first words.** `app/src/watchlink/java/app/buddy/assistant/WatchLink.java`:
   - `onMessageReceived` passes `in.optBoolean("new")`;
   - `deliver(..., boolean isNew)` as in Design 4;
   - `static boolean newChatId(String id)`;
   - the class doc adds "the New chat field is in docs/features/013-watch-new-chat/plan.md".

   New test: `app/src/testGithub/java/app/buddy/assistant/WatchLinkTest.java`. It accepts a phone-format id and
   rejects `""`, 11 and 13 chars, upper case, `../x`, and a value with a `/`.
3. **Watch list entry.** `wear/src/main/java/app/buddy/assistant/wear/ChatsActivity.java`: a `newChatRow(accent)`
   added first in `showChats` (before the empty check), whose tap starts the fresh talk screen (Design 1).
   `wear/src/main/res/values/strings.xml`: `chats_new` = "New chat" (list row, pill, notification and `last` title).
4. **Fresh talk screen.** `wear/src/main/java/app/buddy/assistant/wear/TalkActivity.java`: the `fresh` field from
   `"new"`; `call` becomes a field, `GONE` while fresh; `draw()` clears `fresh` on a YOU line when `mine`;
   `onActivityResult` skips `remember` while fresh and puts `"new", true` in the SEND JSON (captured before
   `Talk.begin`). `wear/src/main/java/app/buddy/assistant/wear/Talk.java`: `remember` in the `HEARD` case.
5. **Docs.** `README.md` Galaxy Watch, "Opening the app" bullet: one sentence. "At the top of the list, **New chat**
   listens right away; what you say first starts a new chat on the phone, with your default agent and settings."
6. **Ship and check** (orchestrator, after review and a green CI): merge, BuildInfo `-fork.<n>`, tag,
   `update_buddy install=true`, `adb install -r` the release's `buddy-watch.apk` on the watch, then the device rows
   below. The phone code changes, so the device rows run after ship and are fixed forward if needed (playbook
   learning).

## Test plan
| Done-when | Proof | Where |
|---|---|---|
| 1. New chat → phone chat, reply on the watch | Pill → list: "New chat" is the first row. Tap → recognizer opens at once. Say "what is two plus two": the pill says "New chat", "You: …", the reply is shown and spoken. On the phone, a new chat is at the top with that message and reply. Its agent is Settings' default and Linux tools follow the Settings default. The phone bubble's current chat is unchanged. Say a second phrase on the same screen: same phone chat, no second chat. The call button shows after the first words are heard, and a call works in that chat. App-list tap → that chat's talk screen (it's the default chat now). | device after ship (+ watch adb) |
| 2. Unreachable → usual error, no ghost | List loaded, then phone Bluetooth off. New chat → speak → "Can't reach your phone. Check Bluetooth." shown and said. Bluetooth back: the phone's list and the watch list have no new chat; an app-list tap opens the previous default chat. New chat → cancel the recognizer → Back → no chat on the phone. Accessibility service off → New chat → speak → the `watch_buddy_off` sentence, no chat. `WatchLinkTest` (id check). Code walk (Code Reviewer): `hub.send` error on a just-made chat → `hub.delete`; a second `new` SEND with an existing id → ordinary send. | device after ship; unit; review |
| 3. 003-012 unchanged, CI green | CI: `testGithubDebugUnitTest` (new `WatchLinkTest`, existing tests), `assembleGithubRelease assembleFdroidRelease :wear:assembleRelease`, F-Droid reproducibility, `helper-tests`. Device smoke: pick an existing chat → talk screen, no auto-listen (009); app-list tap → default chat; quick talk listens; a reply to an existing chat is spoken once; a call from an existing chat works; Watch read aloud (012) plays a phone reply. Phone: the phone's own New chat sheet still makes a selected chat (step 1 refactor). | CI; device after ship |

The wear module has no unit test setup (research), so the watch changes are proven by CI compile, review and the
device rows.

## Risks
- **`WatchLink` in a JVM test.** Its static `Handler(Looper.getMainLooper())` and `WearableListenerService` parent
  must load under `isReturnDefaultValues`. If the class doesn't load, the coder drops `WatchLinkTest`, the Code
  Reviewer checks the regex instead, and the coder records it in `decisions.md`. No new class is made just for a test.
- **Title on the watch stays "New chat"** (pill, notification, the `last` record) after the phone names the chat. The
  list shows the phone's title. This is the same as a chat renamed on the phone today, and out of scope.
- **Old phone + new watch:** a `new` SEND reads as an unknown chat → "That chat isn't on your phone any more". Both
  APKs ship from one release (README).
- **Id collision** with an existing phone chat: 48 random bits, negligible. The words would go to that chat.
- **Chat deleted on the phone before its first words were heard,** while the fresh watch screen is still open:
  speaking there makes a chat with the same id again. This is harmless.
- **Default agent not signed in:** the chat gets the words and the agent's error, which the watch says. This is what
  the phone's own voice talk does.
- **Upstream syncs:** `Sessions.create` gains a fork overload. It is a pure split, so a conflict is easy to resolve.
  Note: AGENTS' sync review globs `docs/features/00*/brief.md`, which misses features 010+. That is for the
  orchestrator, not this feature.

## Open questions
None blocking. One default Nicholas may want to flip (decisions): the new chat doesn't become the phone bubble's
current chat.
