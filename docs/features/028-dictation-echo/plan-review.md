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
