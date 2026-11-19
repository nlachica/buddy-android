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
