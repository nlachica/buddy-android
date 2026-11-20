# Code review: {{SLUG}}
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6.2-astra)

F1 [blocker] The timezone tests fail to compile in CI - `app/src/test/java/app/buddy/assistant/LinuxTimeZoneTest.java:65`. With the Java 8 API, `b.position(data)` returns `Buffer`, which has no `put(byte[])`. I reproduced the error with `javac --release 8` at the same commit as CI run 44682601357. All nine tests pass against the Java 17 API. -> Call `b.position(data);` on its own line, then `b.put(A).put(B);`. Rerun the required CI build.

F2 [major] The runtime check bypasses the launcher's timezone selection - `docs/features/029-local-timezone/plan.md:139`. The driver calls the old `env(TimeZone)` signature and copies the region file directly. The post-install check at line 154 expects that region in `TZ`, although the launcher now selects `Etc/GMT+7` for Dawson Creek. Substituting the region string just to compile would test a different path. -> For each zone and test instant, call `zoneId(zone, instantMillis)`, pass its result to both `copyZone` and `env`, and check the three runtimes at that same instant. Include the Vancouver midnight case from plan review and update the installed-file and `TZ` expectations.

F3 [major] Verifier cleanup can delete files installed by another session - `docs/features/029-local-timezone/plan.md:149`. The executable instructions still recursively remove the live zoneinfo tree. The absence check before the test cannot protect files installed during it, and the decision at `decisions.md:70` explicitly requires preserving them. -> Replace the recursive command in the test plan. Track files created by the check, verify contents and package ownership before unlinking, and use `rmdir` only on empty directories the check created. Leave unexpected changes intact.

F4 [major] The diagnosis includes private chat details - `docs/features/029-local-timezone/diagnosis.md:15`. The branch includes transcript identifiers and a quotation about a personal healthcare appointment at a named location. The UTC and local timestamps already demonstrate the date error. -> Remove the identifiers, quotation and appointment details, including the reference at line 32. Keep the date-boundary example.

F5 [minor] A failed lookup is silent and can be cached - `app/src/main/java/app/buddy/assistant/Linux.java:113`. When `copyZone` returns false for an unrecognized database or missing entry, `zone` still sets `zoned`. If an empty regular target already exists, later launches skip the copy and the failure leaves no diagnostic. -> Set `zoned` only after a successful copy and log a false result while letting the launch continue.

VERDICT: REVISE
