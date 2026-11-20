# Plan review: {{SLUG}}
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6.2-astra)

F1 [blocker] The regression still has no demonstrated cause or fix - `brief.md:27` requires finding what changed. `diagnosis.md:32` says the failing recording state was never observed, and `plan.md:71-82` leaves Part B open. The 3–4 s command delay also occurred on 2.1.326 (`plan.md:33`), so that delay alone cannot explain the regression. -> Record a comparison that isolates the changed condition, holding the CLI or Buddy version fixed. Specify the smallest resulting fix and a check that fails before it and returns the spoken words afterward.

F2 [major] The echo filter discards genuine dictation - Under `plan.md:53-55`, a real box containing “Hold space to record a memo” returns an empty string. `ClaudeDictation.java:144-145` then reports no speech. -> Keep the readiness check separate from the parser's rejection of the actual `/voice hold` command and its output. Add that sentence as a positive fixture alongside the echo fixture.

F3 [major] A failed warm-up can disable later attempts - `plan.md:56-59` proceeds after timeout even if voice mode never enabled. `ClaudeDictation.java:180` reuses any live same-language session, and line 194 sets its language before sending the command. If warm-up times out with voice off, every subsequent tap skips setup and loses the user's speech. -> On activation failure, leave the session eligible for a fresh start and use the existing startup-error path. Verify a missing acknowledgement followed by a successful same-language retry.

F4 [major] The local test command targets a missing checkout - `plan.md:93-95` calls `/tmp/buddy-build-026/check.sh github`. That script hardcodes `/root/.buddy/work/buddy-fork-026` at line 6 and discovers tests there at lines 18-19. The directory is absent in this workspace. -> Run the harness against this worktree with an isolated output directory, or use the branch's Gradle CI job. Name `ClaudeDictationTest` explicitly for the red/green check so another suite cannot stand in for it.

F5 [minor] The device check observes the wrong UI state - `plan.md:115-118` expects the sentence in the box. Talk displays partials in the voice bar and immediately calls `send()` on the final words (`ChatActivity.java:1931-1945`). -> Start with an empty draft, check the sent user message for the spoken sentence, and check that silence adds no message.

F6 [minor] Q3 checks object lifetime instead of active routing - `plan.md:138-140` asks whether a surviving `LiveWatch.call` enables the rule. `LiveWatch.java:116-135` already shows that `end()` clears `on` and `took`, and `noEarpiece` requires `on`. -> Focus the 026 comparison on whether a call teardown path skips `end()` and leaves the routing predicate true during Talk.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted, but research does it, not the plan. The plan already stopped at Part B. Q1 now asks for a controlled
comparison with one variable at a time: (a) the same Buddy flow and only the CLI changed (2.1.326 against 2.1.328); (b)
if 2.1.326 can't be found, CLI 2.1.328 fixed and only Buddy's timing changed: `prepare` (MicServer stops about 1.5 s after
`/voice hold`), `listen` (MicServer stays up), and Part A's wait. Each run uses a stand-in MicServer on 8766 that serves a WAV. Part B
cannot be coded until it has a named cause, the smallest fix, and a repro that shows the echo or "no speech" before the
fix and the WAV's words after it (new `## Part B acceptance`). The reviewer's point about 2.1.326 is right and was
already the hypothesis: the slow command could only hurt once voice mode stopped being on at start. Q1 now tests that
directly.
F2: accepted. The parser's text guard now rejects only text that starts with `/voice`. The command, whether typed or
echoed with its `⎿` output, always starts with it. `Hold space to record` is used only by the readiness wait. New
positive fixture T10: a box holding `Hold space to record a memo` returns that text. T6 and T9 still return `""` (the
`/voice` prefix and the box rule).
F3: accepted, except the unit test. If the wait times out, `ensureSession` now finishes the session, sets
`session = null` and throws the existing `dictation_claude_code_no_start`. `listen` shows it through the existing
`dictation_claude_start_failed` path, `prepare` ignores it, and the next tap starts a fresh session (`ready()` is false).
`session = null` is required: `finish()` only hangs up, and `exited` turns true later. Rejected: a unit test of a missing
acknowledgement followed by a retry. `ensureSession` needs `Linux.exec`, `TermSession.startHidden` and a pty, so testing
it means a new session seam, and the brief asks for the smallest change to upstream code. Proof instead: a diff check
(the timeout branch nulls `session` before the throw) and the device check. Cost, recorded in Risks: if the CLI drops
`Hold space to record`, each tap now fails loudly after 15 s instead of costing 15 s once.
F4: accepted. The `buddy-fork-026` harness is gone. Step 6 uses a copy of it pointed at this worktree, with its own
output and `gen` directories (`/tmp/buddy-build-028/check.sh`, command in the plan; tried on `LeftoverTest`).
Red/green names `app.buddy.assistant.ClaudeDictationTest` explicitly.
F5: accepted. Device check rewritten: start with an empty draft, check that the sent user message is the spoken
sentence (Talk sends on `onFinal`, `ChatActivity.java:1936-1940`), and check that a silent tap shows the "no speech"
toast and adds no message.
F6: accepted. Q3 now asks about teardown, not object lifetime. `LiveWatch.begin` is called only from `CodexLive.start`
for a phone Live call (`CodexLive.java:253`). `LiveWatch.end` is called only from `CodexLive.end`, which returns early when
`ses == null` (`:266-268`). Does any path end a phone Live call, or fail after `begin`, without reaching `LiveWatch.end()`,
leaving `on` true during Talk?

## Round 2 - Plan Reviewer (gpt-6.2-astra)

F1 [blocker] The cause of the regression is still unverified (round 1 F1 remains open) - `brief.md:27` requires finding what changed. `research.md:2` still contains only the template, and `plan.md:83-102` lists competing hypotheses. Changing the wait on 2.1.328 could show that a longer wait helps without explaining why dictation previously worked. A WAV injected at the socket also bypasses the Android microphone and routing suspected in Q3 (`MicServer.java:113`). -> Record a working and failing comparison that isolates the changed condition, then name the smallest fix and show that the same failing setup returns the spoken sentence after it. If the evidence points to routing, compare the affected call and device state on the phone.

F2 [major] A prompt timeout can bypass the new voice-mode wait on the next tap - `ClaudeDictation.java:194` sets the reusable session language before either startup wait. If the first prompt arrives just after its 30 s deadline, line 200 has already thrown, the process is still alive, and no one has sent `/voice hold`. The next same-language tap returns at line 180. The cleanup in `plan.md:63-69` covers only the second wait. -> Use the same session cleanup for both startup timeouts. Check that a failed warm-up followed by a same-language tap reaches voice activation before Buddy starts holding space.

F3 [major] A cancelled attempt's timeout can stop the next recording - During the new 15 s wait, the user can cancel Talk and tap again. The second tap starts microphone capture before waiting for the session lock (`ClaudeDictation.java:50-63`). If the first attempt then times out, its queued error calls `end(l, ...)`. Only the listener assignment in `end` checks ownership. It still stops the shared microphone and calls the old listener's `onEnd`, which hides the recording bar (`ClaudeDictation.java:169-174`, `ChatActivity.java:1943-1945`). -> Have the main-thread error callback return immediately when its listener is no longer current. Check cancel and immediate retry with the first acknowledgement withheld and the second supplied. The second tap should deliver its sentence.

F4 [minor] The command guard also discards genuine box text beginning with `/voice` - `plan.md:59-62` rejects a real input such as `/voicemail`, although Buddy sends exactly `/voice hold` (`ClaudeDictation.java:202`). -> Match the normalized command itself and its observed command-output form. Add a positive fixture for a longer word sharing the prefix alongside T5 and T6.

VERDICT: REVISE

### Planner response (round 2)

F1: accepted. As in round 1, research has to do this, not the plan. `research.md` is still empty because the
Researcher has not run yet, and Part B stays blocked until it does. Q1 now asks for a working/failing pair on the phone
itself. Buddy stays at fork.4 and only the CLI changes: point the `claude` symlink at 2.1.326 (`claude install 2.1.326`
or npm), then at 2.1.328, and Talk 3 times on each. That uses the real microphone and routing, so 026 is covered too.
The session's `.jsonl` `version` field confirms which CLI ran. Q1 then branches. If 326 works and 328 fails, the CLI is
the cause, and the pty run on 328 shows which Buddy step it breaks. If both fail, the cause is on Buddy's side: compare
the same Talk right after a force-stop with Talk after a phone Live call has ended (Q3). The pty-plus-WAV run now only
records what the screen shows in each state. It no longer tests the cause, and the timing-only comparison is gone as a
cause test, for the reason you gave. `## Part B acceptance` item 3 now requires the "after" check to repeat the setup
that failed: the pty with WAV for a CLI cause, the phone for a routing cause (after release). New Q5 for Nicholas: if
no failing setup can be reproduced, does Part A ship alone with the cause recorded as unproven?
F2: accepted, with a smaller fix than the cleanup at each timeout. `sessionLang` is now set only once voice mode is on.
`ensureSession` clears it next to `session = null` (line 182), drops the assignment at line 194, and sets it after the
`Hold space to record` wait succeeds. Any throw (either timeout, PREP, interrupt) therefore leaves `ready()` false, and
the next call's existing line 181 finishes the half-started session and starts a fresh one, `/voice hold` included. The
voice-wait timeout is now a plain `throw`, and its `session.finish(); session = null;` is gone. Line 200 is unchanged.
Proof: a diff check (no `sessionLang = lang` before the voice wait), plus fault check D1 below.
F3: accepted. In `listen`'s catch (line 65), the posted runnable now does `if (listener == l) end(l, ...)`. A cancelled
attempt already got `onEnd(null)` from `cancel()`, so its late error is dropped, and it no longer stops the next tap's
microphone or hides its bar. One upstream line changes and `end()` does not. Proof: fault check D2.
On the checks for F2/F3: no unit test, for the same reason as round 1 F3 (no Robolectric; `listen`/`ensureSession` need
`Linux.exec`, a pty and a Looper). Instead there are two device fault checks after release. They use a one-shot stand-in
for `claude`: a script that the `claude` symlink points to for the test. While it is armed, it prints a bare box and
sleeps, so voice mode never comes on. Unarmed, it execs the real binary. D1 (failed warm-up, then a same-language tap):
arm 1 start, force-stop Buddy, open a chat, wait 20 s, tap and speak; the sentence is sent. D2 (cancel and immediate
retry): arm 1 start, kill the hidden session (the process whose cwd is `/root/dictation`), tap, cancel within 10 s, tap
again at once and speak; the sentence is sent, with no "couldn't start" toast and the bar up until then.
F4: accepted. The guard now matches only Buddy's command: `(t + " ").startsWith("/voice hold ")` on the normalized
text. That covers the typed command (T5) and the echo with its `⎿` output (T1, T6, T9). New positive fixture T11:
boxes holding `/voicemail` and `/voice holder` come back unchanged.

## Round 3 - Plan Reviewer (gpt-6.2-astra)

F1 [blocker] The regression still has no demonstrated cause or repair - `brief.md:27` requires finding what changed. `diagnosis.md:32` says the failing recording state was never observed, `research.md:2` has no results, and `plan.md:135` still leaves the repair as a placeholder. Two failures on fork.4 would also leave the cause open. That comparison keeps Buddy fixed, so it cannot establish the “Buddy-side cause” claimed at `plan.md:222`. -> Run Q1 and record a working and failing pair that isolates the trigger. If both CLIs fail, hold the CLI fixed and isolate the relevant Buddy change or phone state. Use that evidence to specify the smallest repair and the before/after check on the failing setup.

F2 [major] The device checks can pass without exercising the startup failure they claim to cover - D1's stand-in prints `❯` immediately (`plan.md:179-185`), so it always passes the first prompt wait at `ClaudeDictation.java:197`. A build that cleans up only after the later voice-mode timeout would pass D1 while retaining round 2 F2. Also, any Claude launch can consume the armed counter, including the model-list query at `linux/buddy-run:123`. -> For D1, withhold the first prompt beyond 30 seconds while keeping the process alive, then check that the same-language tap launches a fresh session and sends the sentence. Keep D2's bare prompt to exercise the voice-mode timeout. Limit the stand-in to the dictation startup (`cd /root/dictation && exec claude`, `ClaudeDictation.java:193`) and record which launch took the fault so a normal successful tap cannot count as a pass.

VERDICT: REVISE
