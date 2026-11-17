# Plan review: {{SLUG}}
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Initial AudioContext resume can prevent the recovery code and diagnostics from starting - Design 2 puts the state listener after the first context log and recovery in the existing timer (`docs/features/020-watch-voice-fix/plan.md:41`). However, `start()` first awaits `ctx.resume()` (`app/src/main/java/app/buddy/assistant/CodexLive.java:941`); the log, microphone/ownTrack setup, peer connection and timer all come later (`:942`, `:943`, `:953`, `:977`). A resume blocked from starting can remain pending, rather than reject or resolve while suspended ([Web Audio resume algorithm](https://webaudio.github.io/web-audio-api/#dom-audiocontext-resume)). Thus the proposed changes cannot cover that startup case or even log its context state. I evaluated the extracted current PAGE with an AudioContext whose resume promise stays pending: no timer was installed, no context line logged and no state handler installed. This establishes the control-flow gap, not that it caused the recorded watch failure (whose data channel reportedly opened). -> Install the initial state log/listener immediately after creating the context and make the initial resume nonblocking, sharing the planned single-in-flight guard with subsequent recovery. Continue setting up the graph, connection and timers while resume is pending; retain the acknowledged device-policy limitation rather than assuming repeated resume calls overcome it. Add a small executable check that a pending initial resume does not prevent setup/diagnostics, and that later resolution or rejection releases the guard appropriately.

F2 [minor] Successful calls do not exercise the pause recovery being added - The local check only parses the page, and the device checks ask for normal calls plus “every audio element paused is followed by audio element playing” (`docs/features/020-watch-voice-fix/plan.md:108`). A call with no pause passes that condition without testing the fix. There is also no check of rejected play retries, the in-flight guard or shutdown suppression, which are the new branches in Design 1 (`:28`). -> Extend the existing one-off Node check with a small behavioral check against the extracted PAGE and fake media/timer objects: trigger a pause, reject one play attempt and tick the backstop, hold a play promise pending across ticks, and close the peer/clear the source before another pause. Assert recovery/retry, at most one pending attempt, and no playback restart after hang-up. Keep the real phone/watch checks for actual audio delivery; no new framework or production abstraction is needed.

Review checks: read the brief, diagnosis, research placeholder, plan, decisions and relevant Java/test code; ran the supplied syntax check successfully for both pages and the pending-resume control-flow probe above. No Android build, CI or device audio test was run in this plan review. Only this review file was changed.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - `start()` no longer awaits the first resume. `ctx.onstatechange` is set right after `new
AudioContext()`, then the first context line (with `page <visibilityState>`), then `wake()`. `wake()` is the
single-in-flight resume the 70 ms timer also calls, and a resolve or a reject clears its flag (plan Design 2, Step 1).
This removes a line rather than adding one, and matches read-aloud's `ctx.resume()` without await
(`CodexSpeech.java:414`). The device-policy limit stays acknowledged: retries don't beat a refusal (Risks 3). The
executable check is part 1 of the new behaviour check. A pending first resume still installs both timers, logs
`audio context suspended` and sets `onstatechange`. A rejection or a resolution releases the guard.

F2: accepted - the Node page check gains a behaviour part. It runs the extracted page in `vm` with a short, fixed list
of fakes and asserts: a pause resumes, a pending `play()` blocks a second one across ticks, a rejected one is retried
with its error logged once, pause logs stop at 5, the `rtp` line has the new fields, and hang-up resumes nothing
(Test plan, "Page check", parts 2-4). The Coder writes it first, and it must fail on the current page. To keep it
small: plain Node, committed as `docs/features/020-watch-voice-fix/pagecheck.js`, not in CI, no production change for
testability. Device checks still prove the audio itself.

## Round 2 - Plan Reviewer (gpt-6-astra)

No new findings. Round 1 F1 and F2 are resolved in the revised plan.

F1 resolution checked: Design 2 installs the initial log and state listener before a nonblocking `wake()`, with one in-flight resume and guard release on either outcome (`docs/features/020-watch-voice-fix/plan.md:41-53`). The planned pending-resume check covers continued setup, retry after rejection, and later suspension (`:151-154`). This addresses the ordering at `app/src/main/java/app/buddy/assistant/CodexLive.java:941-953`; it also matches the [Web Audio resume algorithm](https://webaudio.github.io/web-audio-api/#dom-audiocontext-resume), which retains promises when a context is not allowed to start. Device-policy failure remains explicitly unproven and is not claimed to be solved by retries.

F2 resolution checked: the committed page check will exercise pause recovery, pending-play suppression, rejected-play retries, duplicate-error suppression, log capping, stats fields and hang-up (`docs/features/020-watch-voice-fix/plan.md:155-163`). The existing shutdown closes the peer before clearing the element's source and closing the context (`app/src/main/java/app/buddy/assistant/CodexLive.java:1003-1009`), matching the proposed recovery guards.

The four done-when items have implementation steps and verification paths. The shared page serves both phone and watch calls; setting diagnostics use the existing `LiveWatch.poll` change branch and log sink; rotation stays in the existing serial logger. The plan retains actual phone/watch audio delivery and log rotation as after-switch checks with a PARTIAL verification verdict until demonstrated. No additional abstraction, permission or unrelated routing change is needed.

Review checks: reread the brief, research placeholder, diagnosis, revised plan, decisions and round 1; inspected the page, logger, watch routing and setting test, shared speech tap, BuildInfo and build workflow. Reran the plan's syntax script successfully for both current pages. The proposed behaviour check is not implemented yet; no Android build, CI run or device audio test was performed in this review. Only this review file was changed.

VERDICT: APPROVE
