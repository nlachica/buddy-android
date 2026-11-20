# Code review: {{SLUG}}
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6.2-astra)

F1 [major] A menu row can replace the dictated message - `app/src/main/java/app/buddy/assistant/ClaudeDictation.java:254` accepts the box's bottom rule as a top rule. I ran `promptText("────\n❯ hello\n────\n❯ menu item\n")` and got `menu item`. This is the footer case anticipated in `plan.md:88`. Once settled, Talk sends that text to the chat. -> Require a closing box rule below the candidate. Continue scanning upward when a candidate has no closing rule. Add this fixture expecting `hello`.

F2 [minor] An exited CLI can pass the voice readiness check - `app/src/main/java/app/buddy/assistant/ClaudeDictation.java:208` checks only the last screen. If the CLI prints the marker and exits between polls, `TermSession.ended()` retains the screen (`TermSession.java:134`) and writes to the dead session are discarded (`TermSession.java:169`). The current tap proceeds to recording and can report “no speech” for a startup failure. -> Include `session.exited ||` in the final check, as the first prompt wait does at line 200.

F3 [minor] The echo guard drops valid box text after the command name - `app/src/main/java/app/buddy/assistant/ClaudeDictation.java:268` returned an empty string for a box containing `/voice hold the mic`. Buddy's typed command ends at `hold`, and its echoed form continues with `⎿`. -> Match the exact normalized command or the command followed by its output marker. Add this case alongside T11.

All 11 committed parser tests passed locally. The [CI run for the reviewed commit](https://github.com/nlachica/buddy-android/actions/runs/44568708492) passed both jobs. The on-device dictation check is pending. The timing change addresses an observed race, but the same delay existed on 2.1.326 (`plan.md:36`), so the trigger for the reported regression remains unconfirmed.

VERDICT: REVISE

### Coder response (round 1)

F1: fixed in a67c4c6 - `promptText` now needs a closing rule below the candidate and otherwise keeps scanning upward; T12 (`hello` beside a menu row) added.
F2: fixed in a67c4c6 - the final voice-readiness check is `session.exited || !…contains("Hold space to record")`. No unit test (plan: A4/A5 have none).
F3: fixed in a67c4c6 - the guard is `t.equals("/voice hold") || t.startsWith("/voice hold ⎿")`; T13 (`/voice hold the mic` passes) added; T5, T9 still hold.

`ClaudeDictationTest` 13/13 locally. The device check is still pending.

## Round 2 - Code Reviewer (gpt-6.2-astra)

F1 [major] A blank row inside the input box discards the whole transcript - `app/src/main/java/app/buddy/assistant/ClaudeDictation.java:261` rejects the candidate as soon as it reaches a blank row, even when the next row is the closing rule. I ran the compiled parser on `────\n❯ hello\n\n────` and got `""`. The base parser returns `hello`. A box with two paragraphs separated by a blank row also returned `""`, which makes `readResult` report “no speech.” -> Scan through blank rows to the closing rule and let the existing whitespace normalization join the text. Keep rejecting candidates that reach the end of the screen without a closing rule. Add a regression test for a blank row inside a closed box and keep the footer test.

F2 [minor] The echo test no longer exercises the output-marker guard - `app/src/test/java/app/buddy/assistant/ClaudeDictationTest.java:44` gives T6 an opening rule and no closing rule. It passes even if the `t.startsWith("/voice hold ⎿")` check is deleted. -> Add an assertion with both the command and its output inside `box(...)`, so removing the guard makes the test fail.

The round 1 menu, exited-session and command-prefix fixes are present. All 13 committed parser tests passed locally. Both CI jobs passed for `09a67c3`. The on-device Talk check is pending, and the original regression trigger remains unconfirmed. [CI run](https://github.com/nlachica/buddy-android/actions/runs/44682636328)

VERDICT: REVISE

### Coder response (round 2)

F1: fixed in this round's fix commit - blank rows inside the box no longer stop the scan; `promptText` runs to the closing rule and the whitespace normalization joins the rows. A candidate that reaches the end of the screen still has no closing rule, so T12 (footer menu row) holds. T14 added: `❯ hello`, blank, rule gives `hello`; a two-paragraph box gives `one two`.
F2: fixed in this round's fix commit - T6 now also asserts the command plus its `⎿` output inside `box(...)`, which only the `/voice hold ⎿` guard rejects (delete the guard and it returns the text).

`ClaudeDictationTest` 14/14 locally. The device check is still pending.

## Round 3 - Code Reviewer (gpt-6.2-astra)

F1 [major] A cancelled tap can reopen the microphone server and leave it running - `app/src/main/java/app/buddy/assistant/ClaudeDictation.java:60` starts the server unconditionally on a worker. If the chat pauses before that worker runs, `cancel()` closes the server, then the worker opens it again. If session startup fails, the stale-listener guard at line 65 skips cleanup. The real server can then accept microphone clients after the voice UI has closed. I ran the production dictation class with a delayed worker, a stand-in for MicServer, and a forced startup failure. After cancellation the listener was inactive in both builds, but the server stayed open on `36d0cbb` and closed on `main`. -> Keep the stale-error guard. Make the worker's listener check and server start atomic with respect to cancellation's server stop, so a cancelled worker cannot reopen the socket. Cover cancellation before the worker starts, including an immediate retry that must retain its own microphone.

Both round 2 findings are resolved. All 14 parser tests passed locally, including the closed-box echo and blank-row cases. The helper-tests and APK jobs passed for the [reviewed commit](https://github.com/nlachica/buddy-android/actions/runs/44683546096). The on-device Talk check is pending, so the original regression trigger remains unconfirmed.

VERDICT: REVISE
