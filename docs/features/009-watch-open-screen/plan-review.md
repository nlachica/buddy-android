# Plan review: 009-watch-open-screen
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Picking without talking changes the specified default - `brief.md:24` defines exactly two events: last talked-to chat and latest reply reaching the watch. `plan.md:48-54` and `plan.md:93-95` add a third event, merely picking a row. After talking to A and receiving its answer, pick B without speaking or calling, leave, and reopen: the plan opens B although neither qualifying event occurred for B. The existing write in `wear/src/main/java/app/buddy/assistant/wear/ChatsActivity.java:159-162` does not establish the new brief's intended rule. -> Keep the picked chat in the talk intent, but update the persisted default only on the qualifying talk/reply events. Specify the no-history fallback separately. Add this pick-without-talking reopen check; if selection is intended to change the durable default too, obtain a brief clarification rather than silently broadening the rule.

F2 [major] Replies from a previously talked-to chat are filtered out before the default update - The assertion at `plan.md:56` that a reply can only come for `Talk.chat` is false. The phone tracks multiple chats (`app/src/watchlink/java/app/buddy/assistant/WatchLink.java:42-43`), adds each accepted chat without removing other running chats (`:127-128`), and relays each tracked chat's replies (`:143-156`); entries remain until their runs finish (`:230-236`). On the watch, `wear/src/main/java/app/buddy/assistant/wear/Talk.java:242` discards a different chat before the planned `REPLY` write at `:257-260`. Thus send to A, then send to B while A is still working, then let A reply while B is still waiting: A's reply reaches the watch listener, but reopening still chooses B, contrary to `brief.md:24`. The planned test at `plan.md:119` only picks B without sending and misses this case. -> Separate recording a received reply's chat from the active conversation's display/speech filter, and specify how the incoming chat ID gets its correct title/accent without changing the phone protocol or switching the live conversation. Add the A-send/B-send/A-reply ordering check. If only replies accepted into the current conversation should count, that narrower meaning needs a brief clarification.

F3 [major] The merge's unconditional registration reuse breaks retry after registration failure - `plan.md:82-85` removes `register()` from `join(false)` on the assumption that the listener is still registered. On `feat/006-watch-quicktalk-keeps-reply`, `wear/src/main/java/app/buddy/assistant/wear/Talk.java:184-189` retains the failed registration task and calls `fail`; `next()` at `:196-207` sends only from that task's success callback. Failure queues spoken error output and ends the wait, so the service can remain alive during speech (`main`'s `Talk.java:307-328`, `:357-364`). If quick talk retries the same chat during that speech, `begin` joins the service, and the proposed `join(false)` attaches the new send to an already failed task. Nothing is sent, even if registration would now succeed; the user gets another timeout. -> Reuse registration only when it has not failed; retry a failed/cancelled registration before dispatching the next words (or retain safe re-registration). Add a focused failure/retry trace alongside the successful join-during-speech check. This is introduced by the proposed 006/007 merge adjustment, not a request to redesign 006.

Review coverage: read the brief, research, plan and decisions, checked the current activities/service, phone relay and CI configuration, and inspected the actual 006 merge hunks with `git merge-tree`. The seven-conflict description and clean activity merges match the repository. No code was changed or executed; Watch4 alias launch/remapping and lifecycle behavior still require the planned device verification.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - A pick no longer writes the default. `open()` drops its prefs write and only passes the chat in the talk
intent (done-when 3). `ChatsActivity.remember(...)` is called only on words sent, a call started and a reply the watch
takes (Design 5, step 3). No record (fresh install): the list, as today. An install updated from 003 starts from the
record 003's last pick wrote. Test plan case 2 adds: talk to B, its answer comes, pill → pick A without words or call,
Back out, app-list tap → B. Decision appended.

F2: accepted for the text and the test, rejected for counting replies Talk drops. The plan's claim was wrong. The
phone relays every chat the watch sent to until its run ends (`WatchLink.java:127-128`, `:143-156`, `:230-236`), and
`Talk.java:242` drops the other chats' messages. Design 5 now says so and defines "reached the watch" as a reply Talk
takes (shown and said). Case 2 adds the A-send / B-send / A-reply check, with B expected. Recording a dropped reply is
rejected:
(1) Talk only hears it while B is live: B's wait or speech. After B's end only `/buddy/say` gets through
(`Talk.java:235`), and once Talk stops nothing listens. In that window he is waiting for or hearing B, so opening A
would put A's empty screen (its reply was never shown) over B's conversation. The same reply a minute later wouldn't
count: a timing lottery, not a rule.
(2) It isn't the ask's "the chat that I'm working on that just responded to me" (B), nor the goal's "a chat that has
just replied to him" (A's reply never reached him).
(3) It needs a stored title per chat, since the reply carries only id and text. A dependable version needs an
always-on watch listener, or phone changes (non-goal).
With (a), the default is in practice the last chat talked to. The REPLY write stays as one line that keeps the brief's
rule true locally. Recorded as a decision and as Q3 for the orchestrator to confirm with Nicholas. Not blocking: the
plan goes ahead with (a).

F3: accepted - Step 1's rule is now: `join(false)` calls `register()` again only when the kept registration failed or
was cancelled (`registration.isComplete() && !registration.isSuccessful()`), then `next()`. A successful registration
is reused, because adding the same listener twice may be refused as a duplicate and fail the wait. A pending one is
waited on, and its failure handler fails the joined wait as on a fresh start. The test plan adds a merge trace for the
Code Reviewer (failure → `fail` spoken → quick talk joins → re-register → send; plus the succeeded and pending cases;
a registration failure can't be forced on the watch). Device case 4 adds a join while the last answer is still being
said. R4 updated, decision appended.

Orchestrator's question (app list vs Home double-press): unchanged, Design 1. The double-press is pointed (adb,
`setting_home_key_double_press`) at an exported `<activity-alias>` `.QuickTalk` with no intent filter, so it is not in
the app list. The start intent's component names the alias, both in `onCreate` and in `onNewIntent` (singleTask reuses
the live `ChatsActivity`). That tells the two starts apart without guessing from flags or the referrer, and an unmapped
key only costs one tap. Q2 now notes that the stored value can be read before coding: `package/class` means a
component start works; a bare package means R1's fallback (a second app-list entry, Nicholas's OK).

## Round 2 - Plan Reviewer (gpt-6-astra)

F1 [minor] Default-chat verification omits the call path - `plan.md:53-55` counts a call as talking, and `plan.md:109-110` correctly places the default write after microphone permission is granted, but the default-chat checks at `plan.md:140` exercise only spoken messages and replies. The call check at `plan.md:139` proves the call opens, not which chat a later launch selects. This is a distinct path through `wear/src/main/java/app/buddy/assistant/wear/TalkActivity.java:129-147`. -> Add a device check: with A as the default, pick B, start and end a call, then reopen (also after force-stop) and expect B. When microphone permission is denied before starting B's call, reopening should still select A.

Round 1 disposition: F1 is resolved by removing the write on selection (`plan.md:57-59`, `:108`). F3 is resolved by retrying a completed unsuccessful registration and retaining the successful or pending registration (`plan.md:94-100`), with the failure/retry trace at `:131-135`. F2 is not reopened: the Planner corrected the false relay claim, supplied a concrete rationale for counting replies accepted by the current conversation, and added the competing-chat check. The narrower interpretation and the orchestrator's outstanding Q3 remain explicitly recorded; this review does not substitute for Nicholas's answer.

Review coverage: checked the current watch activities, Talk and Call services, phone relay, manifest, Wear build configuration and CI, plus 006's activity changes and registration/queue/join implementation. The alias design is consistent with Android's [activity-alias contract](https://developer.android.com/guide/topics/manifest/activity-alias-element): explicit exported alias starts are supported and launch mode comes from the target. AOSP also retains the incoming intent while tracking the alias target separately ([ActivityRecord, lines 1889-1900](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/services/core/java/com/android/server/wm/ActivityRecord.java#L1889-L1900)), and uses the target component for stack matching ([Task, lines 1532-1535 and 1650-1653](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/services/core/java/com/android/server/wm/Task.java#L1532-L1535)). That supports the routing design; it does not prove Samsung's key handler accepts the hidden alias. Reading a stored component string alone does not prove that either: retain R1's real-key check and approval requirement for its visible fallback.

No implementation files changed and no build or device tests run. The planned Watch4 checks remain necessary for the key mapping, warm-launch routing, reply retention and round-screen layout. No blocker or major finding remains open.

VERDICT: APPROVE
