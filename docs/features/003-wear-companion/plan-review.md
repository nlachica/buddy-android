# Plan review: 003-wear-companion
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Tracking ends before an unconsumed watch note gets its answer - `plan.md:53-66` assumes a message sent to a running chat gets its reply from that run, then untracks at the first `running == false`. In fact, `app/src/main/java/app/buddy/assistant/ChatHub.java:699-729` ends the current run, posts `sendNoteAsMessage` for unconsumed notes, and calls `changed()` before that posted continuation starts (`ChatHub.java:797-807`). The relay therefore sends `done`, clears `ses.watch`, and drops the answer to the watch's actual question; that answer can also speak on the phone. This fails the busy-chat test in `plan.md:231` and done-when 2-3. -> Preserve watch ownership across the queued-note continuation, with an explicit way to distinguish that transition from final completion; handle continuation startup failure and Stop as terminal outcomes. Add a device or focused regression check where the first run makes no further phone action after the watch note, so the note necessarily becomes a second run.

F2 [major] Activity lifetime does not guarantee the promised delayed spoken reply - `plan.md:135-139` releases the screen after inactivity but promises that keeping a listener until `onDestroy` will still speak the answer; `plan.md:233` makes this a verification requirement. Wear OS can return to the watch face automatically, and Android can reclaim a cached activity's process without calling `onDestroy`. These platform behaviors make the promised delivery unreliable even when the user merely lowers their wrist, rather than deliberately leaving the conversation. Google's [MessageClient reference](https://developers.google.com/android/reference/com/google/android/gms/wearable/MessageClient) directs background receivers to `WearableListenerService`; see also [Wear OS screen transitions](https://developer.android.com/training/wearables/always-on) and [process lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle). -> Define a small watch-side background receiver and bounded TTS playback lifetime for the pending conversation, including enough retained state to show the received answer when the activity returns and prevent duplicate speech. Verify a delayed reply after the 120-second screen release and after activity/process reclamation. Keeping an activity listener registered alone is insufficient.

F3 [major] Reachability errors stop being handled after acknowledgment - The only response deadlines in `plan.md:127-139` cover chat-list loading and the initial `heard`/`error`. Once `heard` arrives, disconnecting the phone, disabling Buddy, or losing a reply leaves the watch saying “Still working on your phone. I'll read the answer out when it comes” indefinitely. `plan.md:250-251` acknowledges lost messages but the proposed status does not disclose that loss; the claim at `plan.md:66` that `done` always comes is not valid when Buddy dies or the transport fails. Done-when 4 requires a clear unreachable message. -> Specify a bounded post-acknowledgment liveness/error path, using connection changes and an application-level status check or an honest timeout state. Do not automatically resend an accepted user command. Add tests that disconnect the link and stop Buddy after `heard`, checking that the watch leaves the misleading working state.

F4 [major] The proposed uninstall migration can lose setup beyond the acknowledged sign-ins - `plan.md:242-245,272-273` presents Backup → uninstall → install → Restore as the migration, mentioning only sign-ins/API keys as needing recovery. `app/src/main/java/app/buddy/assistant/Backup.java:28-30` backs up an explicit Linux allowlist, which excludes configuration such as `/root/.claude/settings.json`, `/root/.claude/agents`, `/root/.codex/config.toml`, and custom `/root/.local/bin` tools. Uninstall removes those too. Also, `Backup.java:88-89,111-116` restores the Linux archive only if Linux has already been installed; that setup step is missing. -> Before requesting approval to uninstall, inventory the setup that must survive, verify a recoverable backup outside the app's private storage covers the omitted configuration, and record how tools/configuration will be restored. Explicitly set up Linux before restoring and verify the chats/workspace/setup afterward. This needs a concrete migration checklist, not a new backup subsystem; keep credentials out of feature docs.

F5 [minor] The twentieth-chat cutoff silently makes older chats unavailable - `plan.md:46-48,89` caps the list at 20 without a continuation, while `plan.md:231` expects it to match the phone's eligible chats. A wanted chat beyond that cutoff cannot be selected. -> Provide a simple continuation/“More” request, or explicitly resolve and document this limitation against the brief; include a case with more than 20 eligible chats.

F6 [minor] Step 1's Wear CI gate precedes the task that builds Wear - `plan.md:188-195` requires CI to build the new module before Step 2 adds `:wear:assembleRelease`. The existing `.github/workflows/build.yml:59` invokes only `assembleGithubRelease assembleFdroidRelease`, neither of which builds an unflavored Wear module. -> Put the Wear build invocation in the skeleton commit, or combine Steps 1-2, so the stated gate actually verifies the module.

F7 [minor] Fork migration leaves the phone updater targeting the incompatible upstream APK - The new signing key and paired-release policy (`plan.md:30-33,255-256`) are not reflected in the existing updater: `app/src/main/java/app/buddy/assistant/Updates.java:41-45,65-77` hard-codes the upstream repository and its `buddy.apk`, and `Updates.java:127-138` can announce those releases on the fork. Those APKs cannot update the fork-signed installation. -> For this feature, document manual updates of the matching fork pair and disabling upstream update notices during migration, including the limitation of the existing update action. A general fork-updater redesign can remain separate.

VERDICT: REVISE

### Planner response (round 1)
F1: accepted - Confirmed in code: `buddy_done` posts `sendNoteAsMessage` (`ChatHub.java:719-722`) before its
`changed()` (`:729`), with no `changed()` in between. The relay now posts its end check to the main looper instead of
deciding inside `onChatsChanged`, so the check runs after the continuation's turn: running again → still tracked,
`ses.watch` kept (the second run's answer goes to the watch, quiet on the phone); idle → `done`. A continuation that
can't start (its ERROR is relayed) and Stop (the `hub_note_not_sent` step is relayed, outcome `stopped`) end through
the same check. Step 3 adds a comment at ChatHub's post pinning the order. Test plan 2 adds the
"long text answer, then the watch note becomes a second run" check and the Stop-while-queued check. A Session flag was
the other option; it adds main-code state for the same answer.
F2: accepted (partly) - The listener, TTS, deadlines and lines move out of `TalkActivity` into `Talk`, a watch
service that is foreground from send until `done`, an error, lost contact, 15 minutes, or the user swiping the talk
screen away. It starts while the activity is on screen, so it's allowed on every Android version. Each reply is spoken
once, by `Talk`; the activity only draws, and doesn't auto-listen when `Talk` holds unseen lines for that chat or when
Android recreates it. Not a `WearableListenerService` as the receiver: its process is cached again as soon as delivery
returns, so it doesn't bound TTS playback, while the foreground service keeps the process for the whole wait
(reclamation prevented rather than survived; what's left is Risk 4). Test plan 4 adds a delay past the 120 s
release with the watch face back, "Don't keep activities", and `am kill` while waiting.
F3: accepted - After `heard`, `Talk` sends `/buddy/status {chat}` whenever 30 s pass with nothing from the phone;
`running: false` without `done`, no answer in 10 s, or a failed send each end the wait with a clear message. It
never re-sends the message. The phone answers from `Session.running` (runtime-only, so a restarted Buddy says false,
which is true). Removed the claim that `done` always comes. Test plan 4 adds airplane mode and force-stopping Buddy on the
phone after `heard`.
F4: accepted - New "Switching the phone to the fork" checklist: an inventory of setup outside `Backup.java:28-30`
(paths and sign-ins to redo, names only, in `migration.md`), every repo pushed (this pipeline runs inside the official
Buddy's Linux, so the uninstall ends it), the missing config tarred into `root/.buddy/work` so the built-in Backup
carries it to Downloads, the zip checked, then Nicholas uninstalls, installs, sets up Linux **before** Restore,
extracts the extras, reinstalls packages, signs in, and checks chats/repos/config/tools. No new backup code;
credentials stay out of the archive and the docs.
F5: accepted - No count cap: every eligible chat, titles cut to 40 characters, with a 90 KB guard (about 900 chats)
under MessageClient's 100 KB limit, stated as the limit. No "More" paging: all of them fit in one message. Test plan 2
checks a phone with more than 20 eligible chats.
F6: accepted - `:wear:assembleRelease` moves into Step 1's commit (`build.yml:59`); Step 2 keeps the APK
collection, release note and the fdroid gms check.
F7: accepted - Docs (Step 8) and the checklist (step 10): update by installing the matching fork pair; turn off
Settings → Update notices and don't use "update Buddy" on the fork, since `Updates.java:41-45,65-77` only knows the
official repository, whose APK Android refuses over the fork. Risk 11 records it; no updater change.

## Round 2 - Plan Reviewer (gpt-6-astra)

The queued-note ordering is supported by `ChatHub.java:699-729,797-807`; the migration checklist, CI ordering and manual-update documentation address their round-1 findings. The foreground service and status request address the earlier process-lifetime and missing-liveness designs, with the remaining issues below.

F1 [major] The watch's deadlines stop advancing during CPU sleep - `plan.md:167-177` puts the 30-second status interval, 10-second response deadline and 15-minute ceiling on one Handler, while `plan.md:188-191` deliberately allows the watch screen to sleep. The only partial wake lock is on the **phone** (`plan.md:91-94`). A foreground service does not itself prevent the watch CPU from suspending ([Android awake-state guidance](https://developer.android.com/develop/background-work/background-tasks/awake)); Handler delays use uptime and exclude deep sleep ([Handler reference](https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,%20long))). Consequently, after the wrist drops and the link or Buddy dies, the status check and service ceiling can be delayed by arbitrary sleep time; even returning to the screen does not automatically expire those pending timers. The “within about 40 s” test and bounded service lifetime (`plan.md:332,362-363`) are not established by the proposed mechanism. -> Specify sleep-aware deadline handling. The smaller battery-friendly option is absolute `elapsedRealtime` deadlines, immediate revalidation when the activity returns or a callback wakes the service, and an explicit distinction between awake-time checking and suspended-time behavior. If the stated wall-clock bounds must hold while asleep, provide a bounded wake strategy instead. Test a disconnect after the screen has actually gone off, including returning after the conversation deadline; keep the no-resend rule.

F2 [minor] Request sending is not ordered after receiver readiness - `plan.md:159-163,197-200` has the activity call `Talk.begin(...)` and then send immediately, while the newly started service registers the receiver. Starting a service is not a synchronous ready handshake, and [`MessageClient.addListener`](https://developers.google.com/android/reference/com/google/android/gms/wearable/MessageClient) itself returns a Task. A fast `heard` or refusal can arrive before the receiver is registered, producing a false timeout; there is no persisted message to recover. The chat-list request (`plan.md:150-152`) needs the same ordering. -> Make successful listener registration a prerequisite for sending, preferably by letting `Talk` send the pending command when ready; handle registration failure without sending. Add a cold-start check with an immediate phone refusal and a fast acknowledgment.

F3 [minor] TTS readiness and foreground lifetime through playback need an explicit contract - `plan.md:162-166` starts TTS and speaks every arriving reply immediately, but [TextToSpeech requires initialization to complete before synthesis](https://developer.android.com/reference/android/speech/tts/TextToSpeech). The existing phone implementation already buffers pending speech for this reason (`app/src/main/java/app/buddy/assistant/Voice.java:243-260`). Also, `plan.md:173-177` defers TTS shutdown until the queue is spoken but does not clearly defer dropping foreground status; `done` can arrive while a long reply is still playing. -> Retain all pending utterances until successful initialization, drain them once in order, and keep `Talk` foreground until the last utterance completes or fails. Bound failed initialization/playback cleanup and preserve shown text. Check a cold TTS engine with multiple quick replies followed immediately by `done`, with the activity off screen.

VERDICT: REVISE

### Planner response (round 2)
F1: accepted - Bounded wake: `Talk` holds a partial wake lock `"buddy:talk"` (watch `WAKE_LOCK`) from `begin` until
it stops, with a 20-minute timeout (the 15-minute ceiling plus the longest speech, F3). Its Handler deadlines then run
in real time with the screen off. Doze turns off partial wake locks only for apps below foreground-service state, and
`Talk` is foreground for exactly that span. I chose this over `elapsedRealtime` deadlines rechecked on wake because
someone who lowers the wrist is waiting to *hear* the answer. Only an awake check can tell them the phone is gone, so
`Talk`'s own end messages are now spoken too. Nothing else on the watch waits with the screen off: `ChatsActivity`'s
8 s and `TalkActivity`'s 120 s run while it's on. The cost is CPU time for the wait, usually a minute or two (Risk 8).
Test plan 4 adds the screen-off test: on the wrist, off the charger, adb disconnected, airplane mode after 2 minutes
of dark screen. The watch must speak "Lost contact" within about 40 s with the screen still off. Opening it 20+
minutes later must show the ended conversation, with no auto-listen, no notification, no wake lock and the message in
the phone chat once. New Risk 12: if Samsung's power management still holds the timers, that test shows it, and your
recheck-on-wake option is the fallback (also in Alternatives).
F2: accepted - `Talk.begin(context, chat, title, send)` takes the sending step (A: `/buddy/send`; B: opening the
`/buddy/talk` stream) and runs it only when `addListener`'s Task succeeds. If adding fails it sends nothing and ends
with "Couldn't connect to your phone. Try again." The 10 s deadline starts at the send. `ChatsActivity` sends
`/buddy/chats` only after its own `addListener` succeeds (failure → the error with Retry), and its 8 s starts at that
send. Test plan 2 adds cold-start checks after a watch force-stop: the list loads, a fast `heard` arrives, and an
immediate `watch_buddy_off` refusal shows. None may end in a false timeout.
F3: accepted - Every utterance waits in an ordered list until TTS init reports, then they're spoken in order, once.
The phone's `Voice.java:243-260` keeps a single `pending` text, which isn't enough here. An `UtteranceProgressListener`
counts what is left to say. `Talk` now finishes in two steps:
- **End** of the wait (`done`, error, lost contact, 15 minutes, swipe-away): deadlines and listener off; the screen
  shows the end.
- **Stop**, once nothing is left to say, or TTS failed, or at most 5 minutes after the end (a 3000-character reply
  takes about 3½ minutes): TTS off, wake lock released, foreground dropped.

The lines are kept either way. Test plan 3 adds the cold-engine test: watch TTS engine and watch app force-stopped,
three quick replies, `done` during the first, wrist down. All three must be spoken in order, once, with `Talk`
foreground until the last word.
