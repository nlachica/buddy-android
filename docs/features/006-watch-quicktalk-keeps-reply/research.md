# Research: 006-watch-quicktalk-keeps-reply
<!-- Researcher writes here. Sources on every claim. -->

## 2026-11-15 research (claude-sonnet-5-5)

## Summary
- Two independent things drop the first answer on a reopen; both are on the watch, the phone side already copes.
  1. `ChatsActivity` is `launchMode="singleTask"` (`wear/src/main/AndroidManifest.xml:~40`). A launcher open of the live task clears
     `TalkActivity` above it; `TalkActivity.onDestroy` then calls `Talk.left()` (`TalkActivity.java:109`) → `Talk.end()` (`Talk.java:123-125,263-275`):
     `waiting=false`, phone listener removed. Later replies are no longer received. 003 decisions.md:190-200 records this as a known cost.
  2. Even with the screen kept, the new message goes through `Talk.begin` (`Talk.java:101-114`) → `onStartCommand` (`Talk.java:143-172`), which
     wipes `lines`, resets `waiting/heard/status`, bumps `run` (so the first run's callbacks become `!active`), calls
     `h.removeCallbacksAndMessages(null)`, removes the listener, and `hush()`es (drops pending and early utterances). So a second `begin`
     replaces the first conversation by design ("one at a time, a new one replaces it", `Talk.java:57`).
- Phone side needs no change: `WatchLink.deliver` (`WatchLink.java:93-117`) calls `hub.send`, which for a running chat adds a note
  (`ChatHub.java:283-285`, `addNote` `:797-811`), replies `heard`, and keeps `tracked.put(chat,node)` (key = chat id, not run).
  Replies (`/buddy/reply`) and one `/buddy/done` (sent by `endCheck` once the chat is not running, `WatchLink.java:155-164`) therefore arrive
  on the same chat key in order: the story, then the "four" (if the note is answered as a continuation), then `done`.
- So the watch must: (a) not end the wait on a launcher reopen; (b) let a second send while `Talk.waiting` for the same chat append to the
  running conversation (keep `lines`, listener, utterance queue, deadlines) instead of resetting it.
- `Talk` state is static and process-wide; `TalkActivity` only draws it (`TalkActivity.java:153-180`). `Talk.waiting`/`Talk.chat` are the source of truth.
- The `news` rule (`TalkActivity.java:87`): `chat.equals(Talk.chat) && (Talk.waiting || Talk.unseen)` suppresses auto-listen. This is why a
  reopen while waiting shows the lines and does NOT listen (003 done-when for unseen reply), yet the 003 verify says the second quick talk
  did hear "what is two plus two" (`003/verify.md:11`). That is only consistent if the new `TalkActivity.onCreate` ran after the old one's
  `onDestroy` had already set `waiting=false` and `onChange == null` was... see risk R1: ordering is unverified.
- Brief done-when 3: "quick talk with nothing pending listens; an unseen reply is shown first" must keep working. A reopen *while waiting*
  now needs to listen (the user wants to speak) even though `waiting` is true: the `news` guard must distinguish waiting from unseen (design question for the Planner).
- No build/test script for `:wear` is in the repo: no `check-wear.sh` found (`find` returned nothing; 003 reviews mention one, probably an untracked local helper). CI compiles it: `.github/workflows/build.yml` `:wear:assembleRelease`.

## Repo map
- `wear/src/main/AndroidManifest.xml`: `ChatsActivity` singleTask + launcher (exported); `TalkActivity` not exported, default mode; `Talk` service `foregroundServiceType=remoteMessaging`.
- `wear/.../ChatsActivity.java:40-71`: `onCreate` (if `b == null`) and `onNewIntent` both call `talkToLast()` → `startActivity(Talk.intent(...))` from prefs `last` (`chat,title,accent`). `onStart` always calls `load()` (chat list request, `:73-118`).
- `wear/.../TalkActivity.java`: `onCreate:48-89` (auto `listen()` if `b==null && !news`); `onStart:92-97` sets `Talk.onChange`, clears `unseen`; `onStop:100-104` clears `onChange`; `onDestroy:107-110` `Talk.left()` if finishing and same chat; `listen:112-123` (RecognizerIntent for result); `onActivityResult:126-138` cancel → return, else `Talk.begin(...)`; mic button `:76-79` = `Talk.silence()` + `listen()`.
- `wear/.../Talk.java`: statics `chat,title,accent,lines,waiting,heard,status,unseen,onChange,sender,instance` (`:58-71`); `begin:101`; `intent:117`; `left:123`; `silence:128`; `changed:132` (sets `unseen` when no screen); `onStartCommand:143`; `active:175`; `onPhone:188-237` (HEARD adds YOU line; REPLY → `say`; DONE/ERROR → `end()`; STATUS); `end:263`; `maybeStop:277`; speech `say/speak/ttsInit/finished/hush:300-367`. `end()` posts `forceStop` after `SPEAK_MS` and `maybeStop()` stops the service when nothing pending.
- Timers: `NO_HEARD_MS` 10 s, `QUIET_MS` 30 s, `STATUS_MS` 10 s, `CEILING_MS` 15 min (`Talk.java:75-81`); `onStartCommand` re-arms ceiling and `noHeard` and `wake.acquire(WAKE_MS)`.
- Phone: `app/src/watchlink/java/app/buddy/assistant/WatchLink.java` (github flavor only, `src/watchlink`); `deliver:93`, `relay:125`, `endCheck:155`. Messages are chat-keyed; the watch ignores other chats (`Talk.java:196`).
- Prior decisions: `docs/features/003-wear-companion/decisions.md:190-211` (singleTask; unseen reply shown first). `docs/decisions/` does not exist.
- Reusable: `Talk.waiting/chat` checks, `Phone.send(..., valid, done)` with a `valid` supplier (`Phone.java:~28`), `Talk.Sender`.

## Prior art
- 003 code-review rounds 4-5 and decisions explicitly accepted this cost: `003/decisions.md:197-198` ("reopening while the watch waits for a reply finishes the old talk screen") and `003/verify.md:11` (device fail vs done-when 2). No upstream issue searched (fork-only code; `wear/` is not in upstream, unverified).
- Android: singleTask delivers launcher starts to the existing instance via `onNewIntent` and finishes activities above it in the task (https://developer.android.com/guide/components/activities/tasks-and-back-stack#ManifestForTasks; per 003 decisions.md:192-195, not re-fetched here).
- Alternatives (not choosing): `launchMode` change/`FLAG_ACTIVITY_REORDER_TO_FRONT`, making `ChatsActivity.onNewIntent` re-use the top `TalkActivity` instead of starting a new one, or flagging `TalkActivity.onDestroy` not to call `left()` when it is replaced by a relaunch. Note `Talk.left()` exists so swiping the screen away stops the wait (003 behaviour that should stay).

## Constraints and risks
- R1 (unverified, needs device or careful reasoning): on singleTask relaunch, whether the old `TalkActivity.onDestroy` or the new one's `onCreate` runs first decides if `Talk.waiting` is already false (`news` false → listens) or still true (`news` true → no listening, only the lines). The Planner/Verifier must not assume; make the fix independent of that order (e.g. an explicit "reopen" extra, or a `left()` that is skipped for a relaunch).
- R2: `Talk.begin` is also the only path that starts the foreground service; `startForegroundService` from a visible activity is allowed (`Talk.java:112`). A second begin while a service is running calls `onStartCommand` again; any in-place append must avoid re-running its reset block.
- R3: `heard` for the second message arrives as a new `HEARD` → second YOU line, and `onPhone` sets `h.removeCallbacks(noHeard)`; a second `noHeard` timer is needed for the new send (else a failed second send never errors), but must not reset `ceiling`/`quiet` incorrectly.
- R4: `done` arrives once, when the chat stops running (`WatchLink.java:155-164`); if the note is a continuation after the first answer, the watch should stay waiting until that single `done`. If the first run ends and `done` arrives before the second message is accepted, the second send is a normal new run (existing path) and the first `done` already ended the wait: the code must handle both orders.
- R5: If the first reply is already being spoken when the second message is sent, `hush()` in `onStartCommand` and the mic button's `Talk.silence()` (`TalkActivity.java:77`, called only on mic taps) cut it off; done-when 1 wants "each once" and 2 wants the first answer spoken even if the mic is cancelled. Auto-listen on reopen does not call `silence()` (`onCreate` calls `listen()` directly).
- R6: The recognizer screen is an activity result of `TalkActivity` (`startActivityForResult`); if `TalkActivity` is destroyed and recreated by the singleTask relaunch, the result comes to the new instance only if it launched the recognizer; the old one's result is lost with it (consistent with R1/summary 1).
- The `ChatsActivity.onStart` `load()` listener/timer also runs on each reopen; harmless but extra phone traffic.
- Phone-side non-goal: do not change `WatchLink`/`ChatHub`; "as a note if the chat is busy" is already `ChatHub.send:283-285`.
- Verification needs the Watch4 (adb to the watch, see 003 verify notes) and the fork's CI-built watch APK (`build.yml` builds `:wear:assembleRelease`); brief done-when 4. I did not run anything on device or build.

## Answers to open questions
None (plan.md is an empty template).
