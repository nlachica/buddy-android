# Code review: 006-watch-quicktalk-keeps-reply
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

No blocker or major findings. Reviewed `main...3bba3f6`, the complete touched Java files, the phone's existing
`WatchLink`/watch `Phone` paths, and the brief, plan, decisions and plan-review history. The implementation follows
the approved design without changing the phone protocol or adding unrelated functionality.

Checked every planned event-order trace against the implementation (static review, not executed device tests):

| Trace | Evidence in `wear/src/main/java/app/buddy/assistant/wear/Talk.java` |
|---|---|
| T1 | `begin` preserves the live conversation and queues words; `next` waits for registration; `HEARD` sends the next words (`114-133`, `196-207`, `271-276`). |
| T2 | `DONE` clears `accepted` but leaves the wait alive while words are in flight; their later acknowledgement/reply/done complete normally (`271-290`). |
| T3 | A send failure calls `settle`; an accepted earlier run keeps its listener and wait (`203-205`, `229-243`). |
| T4 | A deferred done leaves `accepted` false, so a subsequent send failure with no queued words ends the wait (`239-241`, `284-290`). |
| T5 | Refusal and the acknowledgement deadline both use the same per-submission settlement (`104`, `291-293`). |
| T6 | A false status is ignored with words in flight and ends the wait otherwise (`294-298`). |
| T7 | A fresh conversation resets `accepted`; its failed/refused/timed-out only submission ends the wait (`117-125`, `229-241`). |
| T8 | Joining during speech cancels the speech-only stop deadline, registers again and sends without clearing lines or calling `hush` (`114-133`, `211-222`). |
| T9 | Settlement increments `sends`; both delayed dispatch and failure callbacks check that generation (`202-205`, `230`). |
| T10 | Settlement preserves `run`, so a pending status discovery remains valid (`229-243`, `314-320`). |
| T11 | Settlement explicitly re-arms `quiet` when it retains an accepted run (`236-238`). |
| T12 | After an ignored false status and failed added submission, that re-armed check can end the wait with nothing in flight (`236-238`, `294-298`). |

The activity changes implement the planned relaunch counter and finishing-`onStop` distinction, preserve ownership
of the redraw callback, and apply the `speak` extra only to quick-talk opens (`ChatsActivity.java:64-75`,
`TalkActivity.java:90-113`). The queue and two generations serve the reviewed ordering/cancellation cases; no unused
option, unnecessary abstraction, new permission, secret or unsafe input path was found. The documented startup
edge case in the Coder's decision is not reopened.

Validation and remaining evidence:

- [Fork CI run 43766489010](https://github.com/nlachica/buddy-android/actions/runs/43766489010) is green for the exact
  reviewed HEAD `3bba3f6c2300e26d1d57ac8054481b9bf2a76b04`: helper tests, the Gradle test/release-build step including
  `:wear:assembleRelease`, F-Droid Google-code exclusion and reproducibility checks passed. Queried with explicit
  `-R nlachica/buddy-android`; an unqualified lookup selected upstream and returned no runs.
- `git diff --check main...HEAD -- wear` passed. A local scratch compile was inconclusive: the default JVM could not
  initialize; JDK 17 ran but the retained `/tmp/stub6` resource/Look stubs lack the activity APIs. Those are local
  harness limitations, not evidence of a source build failure; the exact-commit CI build above is authoritative.
- `verify.md` is still empty. Done-when 1-3 and the Watch4 installation part of item 4 remain for the Verifier:
  both lifecycle orders, cancelling recognition, both messages/replies once, idle/unseen behavior, swipe-away and
  notification regressions, and the planned P1-P3 probes. The code implements those paths, but this review does not
  establish their device results. In particular, lifecycle ordering and speech/recognizer overlap retain the
  explicitly accepted device-verification risks.

VERDICT: APPROVE
