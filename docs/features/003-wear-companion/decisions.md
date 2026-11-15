# Decisions: 003-wear-companion
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-15 - The phone's watch link lives in `app/src/watchlink/`, added only to the github source set
- Options: (a) a separate folder wired into the github source set (java, res, manifest); (b) `app/src/github/`
  (standard flavor folder); (c) `app/src/github/` plus an exclusion rule in build.sh; (d) a separate phone APK.
- Choice: (a). fdroid never compiles it, and build.sh (javac against android.jar, `app/src/main` +
  `app/src/$FLAVOR/java`) never sees it, so `FLAVOR=github ./build.sh` keeps working unchanged. Its APK has no watch
  link, which it couldn't have anyway: no Gradle, no Play services. (b) breaks build.sh's github build; (c) puts a
  rule in a script where a folder needs none; (d) can't work: the Data Layer needs the watch's package, and the link
  needs ChatHub in-process.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Plain MessageClient JSON under `/buddy/*`; the phone pushes heard/step/reply/done/error
- Options: (a) MessageClient both ways, one path per message kind, the phone pushing a run's messages as they come;
  (b) `DataClient` synced chat list + messages; (c) `MessageClient.sendRequest` RPC; (d) one path with a `type` field.
- Choice: (a). Small text fits MessageClient (no persistence needed: the phone chat keeps everything), and a run's
  several replies are a stream anyway. `heard` acknowledges every send, so both speech paths look the same
  downstream. ChannelClient stays for audio (path B, phase 2's `/buddy/live`). No version field: both APKs come from
  one CI run.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Watch app: module `:wear`, applicationId `app.buddy.assistant`, namespace `app.buddy.assistant.wear`, plain Java views
- Options: (a) plain `Activity` + views in code, Play services the only dependency; (b) Compose for Wear (Kotlin);
  (c) androidx.wear widgets/ambient.
- Choice: (a). It matches the phone app (Java 8, no XML layouts, no Kotlin), and 2 screens need nothing more. The same
  applicationId and signing key are required by the Data Layer; a namespace of its own keeps its R class and
  activities apart from the phone's. Version and signing as `:app` (BuildInfo.java, `BUDDY_KEYSTORE*`).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - `android.useAndroidX=true` project-wide
- Options: (a) flip the flag in `gradle.properties`; (b) try a subproject override for `:wear` only.
- Choice: (a). Play services needs androidx.core, and AGP options are build-wide (a subproject gradle.properties
  isn't a supported way to set them; unverified, not worth the risk). `:app` fdroid has no dependencies, so its APK
  can't change. Step 1 proves it by comparing with main's fdroid APK before any main-code edit.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Speech input is a swappable step: A watch recognizer, else B phone transcribes; pending the Watch4 probe
- Options: (A) `ACTION_RECOGNIZE_SPEECH` on the watch, text sent; (B) watch records PCM → ChannelClient → phone feeds
  it to the user's dictation engine through a `MicServer` feed; (C) a cloud STT key on the watch; (D) build both.
- Choice: A if the probe gets text back, else B. A is about 25 lines with no mic permission. B is about 150 lines
  and reuses the phone's engines, whose PCM format already matches. C is a new key, a new service and a privacy
  change; D is YAGNI. The phone's `heard` reply makes everything after this step identical either way. The final
  pick is the orchestrator's, on the probe result.
- By: Planner (claude-opus-5-5); pending probe

## 2026-11-15 - A watch message needs Buddy's accessibility service; a watch run holds a wake lock and stays quiet on the phone
- Options: for "Buddy off": (a) refuse with a clear message; (b) send anyway. For the run: (a) partial wake lock
  "buddy:watch", 15 min cap, plus `Session.watch` skipping phone read-aloud and the "run ended" notification;
  (b) no changes.
- Choice: (a) for both. Agent events only reach ChatHub through BuddyService's MCP server, so a run sent without it
  never answers; done-when 4 asks for a clear message. The wake lock copies notification rules' pattern for a run
  with the screen off. Done-when 3 wants the watch to speak, so the phone speaking too (read-aloud on), or Galaxy
  Wearable mirroring the phone's end notification as a second buzz, would be duplicates the feature itself causes.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - What reaches the watch: each BUDDY/ERROR message (spoken), STEP lines as status, done at run end
- Options: (a) forward each message as it comes; (b) only `ses.lastReply` at the end.
- Choice: (a), as the phone's own read-aloud speaks each BUDDY message. STEP lines give the watch a sign of life for
  slow models and approvals, and reset its 120 s screen-on clock. Text is cleaned for speech on the phone with a
  `Voice.spoken()` extracted from `Voice.speak` (one copy, localized "code"/"link" words) and cut at 3000 chars.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The fork gets its own release key, stored as the fork's CI secrets; needs Nicholas's OK
- Options: (a) new keystore → `BUDDY_KEYSTORE_BASE64`/`_PASSWORD` secrets on nlachica/buddy-android, backed up where
  Nicholas says; (b) local-only signing by the orchestrator; (c) debug keys.
- Choice: (a). CI then signs the phone and watch APKs alike (a Data Layer requirement), and the fork's
  reproducibility check passes. (b) leaves CI artifacts uninstallable; (c) debug keys differ per machine and
  wouldn't pair. Done by the orchestrator after his OK, never written into files.
- By: Planner (claude-opus-5-5); needs Nicholas

## 2026-11-15 - A watch note that becomes a second run stays the watch's; the relay decides "done" one main-loop turn later
- Options: (a) post the end check from `onChatsChanged`, so it runs after `buddy_done`'s posted note continuation;
  (b) a runtime Session flag "continuation pending" set and cleared by ChatHub; (c) end at the first `running == false`
  (round 1's bug).
- Choice: (a). ChatHub posts the continuation before its `changed()` with no `changed()` in between
  (`ChatHub.java:701-729`), so the posted check sees either the second run (keep tracking, `ses.watch` kept) or the true
  end. Failure to start and Stop end through the same check. (b) adds main-code state for the same answer; a comment at
  ChatHub's post pins the order instead. (c) drops the answer to the watch's question (plan review F1).
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The watch's conversation lives in `Talk`, a foreground service from send to the end
- Options: (a) listener in `TalkActivity` until `onDestroy`; (b) a watch `WearableListenerService` as the receiver;
  (c) a foreground service started at send holding listener, TTS, deadlines and lines; (d) both (b) and (c).
- Choice: (c). The wait outlives the screen: the watch face comes back and a stopped activity's process can be
  reclaimed, so (a) loses replies. (b) restarts the process for each message but leaves it cached right after, so
  speech can still be cut. (c) starts while the activity is visible (allowed on every Android version), keeps the
  process for the whole wait, speaks each reply once and keeps the lines for the activity; it ends on done, error,
  lost contact, 15 minutes or a swipe-away. Type `remoteMessaging` (no runtime permission), `mediaPlayback` as the
  fallback. (d) is two receivers for one job.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - After `heard`, the watch checks with `/buddy/status` after 30 s of silence; never re-sends
- Options: (a) a plain timeout; (b) Data Layer connection listeners; (c) an application-level status request.
- Choice: (c). It covers a lost link (send fails or no answer in 10 s), a dead or restarted Buddy (`running` false,
  runtime-only), and a lost `done`, with one tiny message per 30 s of silence. (a) can't tell a slow run from a dead
  one; (b) misses Buddy dying while the link stays up. Re-sending an accepted message would run it twice.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The watch gets every eligible chat (90 KB guard), not the first 20
- Options: (a) cap at 20; (b) a "More" page; (c) all, titles cut to 40 characters, stopping at 90 KB.
- Choice: (c). A chat past a cap can't be picked; at about 100 bytes a row, roughly 900 chats fit under
  MessageClient's 100 KB limit, so paging isn't needed.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Switching the phone to the fork follows a checklist that rides Buddy's own Backup
- Options: (a) Backup → uninstall → install → Restore as is; (b) new backup code for the missing parts; (c) tar the
  missing setup into `root/.buddy/work` so the built-in Backup carries it, plus inventory, push, check, Linux setup
  before Restore, and post-restore checks.
- Choice: (c). (a) loses setup outside `Backup.java:28-30` and skips the Linux part without Linux set up; (b) is a
  subsystem for a one-time move. Sign-ins stay out (redone). The uninstall ends this pipeline's own session (it runs
  in the official Buddy's Linux), so everything is pushed first and Nicholas runs the switch.
- By: Planner (claude-opus-5-5); the switch itself needs Nicholas

## 2026-11-15 - Fork updates are manual pairs; Update notices off
- Options: (a) point `Updates` at the fork's releases; (b) document manual updates and turn off Update notices.
- Choice: (b). The updater only knows the official repository (`Updates.java:41-45`), whose APK Android refuses over the
  fork, and a fork-aware updater (watch APK included) is its own feature.
- By: Planner (claude-opus-5-5)

## 2026-11-15 - The watch's `Talk` holds a partial wake lock while it works, so its deadlines run with the screen off
- Options: (a) partial wake lock `buddy:talk` from `begin` until `Talk` stops, 20-minute timeout; (b) absolute
  `elapsedRealtime` deadlines rechecked when the activity returns or a message arrives; (c) `AlarmManager` wake-ups.
- Choice: (a). Handler delays stop during CPU sleep and the screen is allowed to sleep; the user who lowers the
  wrist is waiting to hear the answer, so only an awake check can tell them the phone is gone (end messages are now
  spoken). Doze leaves a foreground service's wake locks on, and `Talk` is foreground for exactly that span. (b)
  says nothing until the user looks and doesn't bound the 15-minute ceiling; it stays as Risk 12's fallback. (c)
  inexact alarms are deferred in Doze, exact ones need `SCHEDULE_EXACT_ALARM`. Cost: CPU awake for the wait, usually
  a minute or two. (Plan review round 2, F1.)
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Watch requests are sent only after the listener is registered; `Talk.begin` takes the sender
- Options: (a) `Talk.begin(context, chat, title, send)` runs `send` when `addListener`'s Task succeeds, sends nothing
  on failure; (b) the activity sends right after `begin` (round 1's design).
- Choice: (a). Starting a service and adding a listener are both asynchronous, and MessageClient keeps nothing, so a
  fast `heard` or refusal could beat the listener and end in a false timeout. `ChatsActivity` does the same for
  `/buddy/chats`. (Plan review round 2, F2.)
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Watch speech: every utterance waits for TTS init in order; `Talk` stops only after the last one
- Options: (a) an ordered list until init, then an utterance count, and two-step finish: end of the wait, then stop
  when nothing is left to say (at most 5 minutes after the end); (b) the phone's single `pending` text
  (`Voice.java:243-260`); (c) stop at the end of the wait.
- Choice: (a). Several replies can arrive before a cold engine is ready, and `done` can arrive while a long reply
  plays; (b) drops all but the last, (c) cuts speech and lets the process go. The 5-minute bound (a 3000-character
  reply takes about 3½ minutes) and the wake lock's 20-minute timeout stop a hung engine keeping the watch awake;
  lines are kept either way. (Plan review round 2, F3.)
- By: Planner (claude-opus-5-5)

## 2026-11-15 - Speech: path A (the watch's RecognizerIntent) is built; path B is not
- Options: A or B, as in the plan (Open question 1: the Watch4 probe was still pending).
- Choice: A, on the orchestrator's instruction. `TalkActivity.listen()` is the only speech-specific code. If the probe
  later shows the Watch4 has no recognizer, the activity says "This watch has no speech recognizer." and B has to be
  built (plan, "The speech piece"); nothing built here has to change for it.
- By: Coder (claude-sonnet-5-5), on the orchestrator's instruction

## 2026-11-15 - `Talk.begin` also takes the accent, and its `send` reports success or failure
- Options: (a) the plan's `begin(context, chat, title, Runnable send)`; (b) `begin(context, chat, title, accent, Sender)`
  where `Sender.send(Phone.Sent done)` hands back the send result.
- Choice: (b). With a Runnable, `Talk` could not know the send failed ("Couldn't send. Is your phone nearby?") and would
  wait 10 s for a `heard` that can never come. The accent is kept so the ongoing notification can reopen the screen in
  the user's colour. Same behaviour otherwise.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - Smaller choices while coding
- The CI "no Google code in the fdroid dex" step uses `grep -a ... >/dev/null`, not `grep -aq` (as in the plan): with
  `pipefail`, `grep -q` quitting early can break unzip's pipe and make the negated check pass on a hit.
- An empty message from the watch is answered with the existing `hub_something_wrong` text, not a new string (the
  watch never sends one; the check only protects the phone).
- `Talk` shows "Buddy is speaking · <chat>" in its ongoing notification once the wait has ended and speech is still
  going, so the notification doesn't say "Waiting" while the watch talks.
- New `watch_*` phone strings are English only; the other languages fall back to it (CONTRIBUTING, "Translations").

## 2026-11-15 - Opening the watch app goes straight to the last chat (brief, last clarification)
- Options: (a) a launcher-only trampoline activity; (b) `ChatsActivity.onCreate` starts `TalkActivity` itself when a last
  chat is saved; (c) a tile.
- Choice: (b). The last chat (id, title, accent) is kept in plain SharedPreferences `last`, written when a chat is tapped
  in the list. `TalkActivity` already starts listening on a fresh open, and Back from it lands on the list, so no new
  screen. Only a fresh create (`savedInstanceState == null`) redirects, so Android recreating the list doesn't. No saved
  chat (first launch) shows the list as before. If the saved chat was deleted on the phone, the send fails with the
  usual error; the list is one Back away.
- Known limit (superseded below): if the app's task is still alive, the launcher resumed whatever was on top.
- By: Coder (claude-sonnet-5-5)

## 2026-11-15 - A launcher open of a live task also goes straight to talking: `ChatsActivity` is `singleTask` (code-review round 4, F1)
- Options: (a) `launchMode="singleTask"` on `ChatsActivity` + `onNewIntent`; (b) `onResume` redirect with a flag; (c) leave
  the limit.
- Choice: (a). Android delivers a launcher start to a live task's root only for singleTask/singleInstance; it clears
  the talk screen above and calls `onNewIntent`, which does what `onCreate` does (`talkToLast`). Only explicit starts of
  `ChatsActivity` (the launcher, so the Home double-press too) take this path; Back, recreation, the notification (opens
  `TalkActivity` directly) and the recognizer's return don't, so nothing re-listens by itself. No saved chat: the list as before.
- Cost: reopening while the watch waits for a reply finishes the old talk screen, which stops the wait (same as swiping
  it away, `TalkActivity.onDestroy`); the new talk screen shows the lines so far and listens.
- Not checked here: the actual Watch4 launcher/double-press behaviour (compile only); for the Verifier.
- By: Coder (claude-sonnet-5-5)
