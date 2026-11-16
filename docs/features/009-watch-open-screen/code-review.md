# Code review: 009-watch-open-screen
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

F1 [minor] Carry the call-default check into device verification - `docs/features/009-watch-open-screen/plan.md:139-140` checks that a call opens, but its default-chat scenarios still cover only spoken messages and replies. The separate write at `wear/src/main/java/app/buddy/assistant/wear/TalkActivity.java:158` is correctly after the permission guard, but its persistence and denied-permission behavior have no planned device proof. This is the still-open minor finding from plan-review round 2. -> In verification, start with default A, pick B, start/end a call, reopen normally and after force-stop, and expect B. Separately deny B's microphone permission before starting the call and verify that the default stays A. No production-code change is needed for this finding.

Reviewed `main...d4968fb4cbb8efa697a107ec7899eb49db9db7fd`, the complete touched Java files, manifest and resources,
the 006 merge message and prior review/decisions, and the connected `Phone`, `WatchLink`, `Call`, `CallActivity` and
`Look` paths. No blocker or major correctness, security, data-handling or scope finding remains open.

Done-when coverage:

| Item | Implementation and evidence |
|---|---|
| 1. App list opens the default talk screen without the mic | `ChatsActivity.java:60-89` routes ordinary starts without `speak`; `TalkActivity.java:125-127` requires that extra to auto-listen. Existing mic/call handlers remain connected. Cold/warm launcher behavior still needs the planned Watch4 checks. |
| 2. Most recent qualifying chat persists | `ChatsActivity.java:93-96` owns the single preferences write; `open()` only passes the selected chat. Writes occur on a valid recognition result (`TalkActivity.java:190-194`), a permitted call (`:150-159`), and an accepted reply after the chat filter (`Talk.java:315-334`). These execute on the main thread. Force-stop and competing-chat checks remain for the Verifier, plus F1. |
| 3. Visible chat picker | The fixed pill starts the list with `list=true` (`TalkActivity.java:66-82`); routing returns without redirecting, and a row opens its selected chat without `speak`. Round-screen fit, long titles, Back and notification-root navigation need device verification. |
| 4. Quick talk and reply retention | The exported, filterless alias targets the existing singleTask activity. Routing marks only alias starts for listening. The merged relaunch counter, redraw ownership and finishing-`onStop` rule are retained; joining the service preserves existing lines and speech. The real Home key, alias reuse and 006 regression cases still need this build's device checks. |
| 5. CI green | Exact-commit workflow run 43786011094 passed; details below. |

The required 006/007 merge traces pass by code inspection:

- Failed registration: `register()` records its task and calls `fail` on failure (`Talk.java:229-234`). If error
  speech keeps the service alive, the next same-chat `begin` joins; `join(false)` replaces the completed unsuccessful
  task before `next()` attaches the send (`:156-175`, `:256-262`).
- Successful registration: `join(false)` reuses it without another `addListener`; `end()` retains the listener so
  late `/buddy/say` messages still reach the queued voice replies (`:308-318`, `:390-406`).
- Pending registration: it is reused; `next()` waits for success, and the retained failure handler applies to the
  joined wait. Run/send generations prevent an earlier abandoned send from dispatching into that wait.
- Follow-up failure preserves an accepted earlier run through `settle`; a done while words are in flight clears
  `accepted` without immediately ending the wait. Queue cleanup, monitoring and the speaker-ownership call to
  `Call.hangUp()` survive the merge.

No new dependency, permission, phone protocol or unnecessary abstraction was added. The one preferences helper
and existing list activity are sufficient. The accepted interpretation of replies and the documented 006 startup
edge case are not reopened; the orchestrator's outstanding Q3 remains recorded in the plan/decisions.

Validation:

- [CI run 43786011094](https://github.com/nlachica/buddy-android/actions/runs/43786011094) is green for the exact
  reviewed HEAD. Queried with `gh ... -R nlachica/buddy-android`: helper and sync-script tests, the Gradle unit-test
  and release-build step including `:wear:assembleRelease`, F-Droid Google-code exclusion, and reproducibility passed.
- Locally, both changed XML files parse, and `git diff --check main...HEAD -- wear README.md` passes. The full diff
  check reports only documentation whitespace in 006's plan and 009's empty verification template; no style finding.
- No local Android build or device interaction was run. `verify.md` has no results yet; CI does not establish the
  Samsung key mapping, lifecycle behavior, speech overlap, persistence or layout. Those remain the Verifier's gate,
  including correcting the README's firmware-dependent adb command from actual device evidence.

VERDICT: APPROVE
