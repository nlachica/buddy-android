# Code review: {{SLUG}}
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6.2-astra)

F1 [blocker] The timezone tests fail to compile in CI - `app/src/test/java/app/buddy/assistant/LinuxTimeZoneTest.java:65`. With the Java 8 API, `b.position(data)` returns `Buffer`, which has no `put(byte[])`. I reproduced the error with `javac --release 8` at the same commit as CI run 44682601357. All nine tests pass against the Java 17 API. -> Call `b.position(data);` on its own line, then `b.put(A).put(B);`. Rerun the required CI build.

F2 [major] The runtime check bypasses the launcher's timezone selection - `docs/features/029-local-timezone/plan.md:139`. The driver calls the old `env(TimeZone)` signature and copies the region file directly. The post-install check at line 154 expects that region in `TZ`, although the launcher now selects `Etc/GMT+7` for Dawson Creek. Substituting the region string just to compile would test a different path. -> For each zone and test instant, call `zoneId(zone, instantMillis)`, pass its result to both `copyZone` and `env`, and check the three runtimes at that same instant. Include the Vancouver midnight case from plan review and update the installed-file and `TZ` expectations.

F3 [major] Verifier cleanup can delete files installed by another session - `docs/features/029-local-timezone/plan.md:149`. The executable instructions still recursively remove the live zoneinfo tree. The absence check before the test cannot protect files installed during it, and the decision at `decisions.md:70` explicitly requires preserving them. -> Replace the recursive command in the test plan. Track files created by the check, verify contents and package ownership before unlinking, and use `rmdir` only on empty directories the check created. Leave unexpected changes intact.

F4 [major] The diagnosis includes private chat details - `docs/features/029-local-timezone/diagnosis.md:15`. The branch includes transcript identifiers and a quotation about a personal healthcare appointment at a named location. The UTC and local timestamps already demonstrate the date error. -> Remove the identifiers, quotation and appointment details, including the reference at line 32. Keep the date-boundary example.

F5 [minor] A failed lookup is silent and can be cached - `app/src/main/java/app/buddy/assistant/Linux.java:113`. When `copyZone` returns false for an unrecognized database or missing entry, `zone` still sets `zoned`. If an empty regular target already exists, later launches skip the copy and the failure leaves no diagnostic. -> Set `zoned` only after a successful copy and log a false result while letting the launch continue.

VERDICT: REVISE

### Coder response (round 1)
- F1: fixed in fa4de4f (`b.position(..)` is its own statement, twice, so no `Buffer.put`). `javac --release 8` shows no
  `position`/`put` error now (the other errors are the missing JUnit/Android classes in my scratch run). CI is the required check and has not run.
- F2: fixed in c891de4. The plan's pre-merge check now calls `zoneId(zone, t)` per test instant, passes that id to
  `copyZone` and `env`, and expects `Etc/GMT+7` files and `TZ`. A Vancouver case is added: 2026-11-20 07:00Z (t=1795158000,
  local midnight) must print `2026-11-20 00:00 -0700`. The post-install check expects `Etc/GMT+7`, no byte count.
- F3: fixed in c891de4. The recursive `rm -rf` is gone: the driver records what it created, unlinks a file only if its
  bytes match and no apk package owns it, `rmdir`s only empty directories it made, and leaves anything else intact.
- F4: fixed in c891de4. Session ids, the quotation and the appointment are removed from `diagnosis.md`, with the
  date-boundary example kept. The `runs/` logs that still hold the old text are git-ignored (`docs/features/*/runs/`), so they are not in the branch.
- F5: fixed in d504942. `zoned` is set only when `copyZone` returns true, and false is logged and retried next launch.

## Round 2 - Code Reviewer (gpt-6.2-astra)

F1 [major] The runtime check selects 1970 offsets - `docs/features/029-local-timezone/plan.md:139` passes epoch seconds to `zoneId`, which takes milliseconds. With the branch code and the specified Java 17 harness, Vancouver at `1795158000` selects `Etc/GMT+8`, moving the local midnight check back to the previous date. Passing `1795158000000L` selects the expected `Etc/GMT+7`. The summer Troll check also selects UTC with the documented call. -> Define `t` as epoch seconds. Pass `t * 1000L` to Java and JavaScript's `Date`, and pass `t` to BusyBox and Python.

F2 [major] Private chat details remain in the public branch history - `docs/features/029-local-timezone/diagnosis.md:15` at commit `29f174e` still contains the material identified in round 1. GitHub serves that commit, and it is an ancestor of the published review head `ac65921`. A normal merge would bring it into `main`'s history. -> Build the merge candidate from the sanitized tree on a clean base and rerun CI. Coordinate removal of the exposed branch history and any retained GitHub references with Nicholas.

F3 [minor] The Berlin smoke check expects a file the launcher never creates - `docs/features/029-local-timezone/plan.md:170` expects `Europe/Berlin`. The launcher selects `Etc/GMT-1` in winter and `Etc/GMT-2` in summer. The unit-test expectations at line 112 and the DST restart guidance at line 183 also describe the superseded design. -> Check the expected offset and the file at `/usr/share/zoneinfo/$TZ`, and align those two passages with the accepted launch-time offset decision.

All nine timezone tests passed locally with `javac --release 8`. The required [Actions build](https://github.com/nlachica/buddy-android/actions/runs/44683393716) passed on `ac65921`. The three-runtime check and post-install smoke test remain for the Verifier and orchestrator.

VERDICT: REVISE

### Coder response (round 2)
- F1: fixed in the commit below (plan.md only). The runtime check passes `t * 1000L` to `zoneId` and says `t` is epoch
  seconds: `t` for `date -d @t` and python, `t * 1000` for node. Not re-run here; the Verifier runs it.
- F2: not fixable by a Coder commit - needs Nicholas. `29f174e` is on `origin/fix/029-local-timezone`, so removing it
  means rewriting a published branch and a force push, and I don't push or rewrite published history. HEAD's tree is
  already clean (`diagnosis.md` has no ids, quotation or appointment; `runs/` is git-ignored). Options for Nicholas:
  (a) a fresh branch from `main` with one commit of the current tree, a new PR, delete the old remote branch, and ask
  GitHub support to drop cached views of `29f174e`; (b) rewrite this branch (`git rebase -i` to sanitize `29f174e`,
  `push --force-with-lease`) and then the same GitHub cleanup. Either way CI must rerun on the new head. Until then,
  don't merge this branch.
- F3: fixed in the commit below (plan.md only). The Berlin smoke check expects `Etc/GMT-1` (`Etc/GMT-2` in summer) and
  the file at `/usr/share/zoneinfo/$TZ`; the unit-test bullets now describe `env(String)` and `zoneId(zone, ms)`; the
  restart note says a DST change reaches only processes started after it.

## Round 3 - Code Reviewer (gpt-6.2-astra)

F1 [major] Private chat details remain in the published branch history (round 2 F2) - `docs/features/029-local-timezone/diagnosis.md:15` in commit `29f174e`. The current file is redacted, but `git ls-remote` confirms the published branch points to `65b7150`, and `git merge-base --is-ancestor 29f174e 65b7150` succeeds. A merge commit would carry the private material into `main`. -> Create a clean candidate from the redacted tree on `main`, confirm the old commit is absent from its ancestry, and rerun CI. Coordinate cleanup of the exposed branch and retained GitHub references with Nicholas.

The plan now passes milliseconds to Java and checks Berlin's selected offset file. All nine timezone tests passed locally with `javac --release 8`. Both [CI jobs](https://github.com/nlachica/buddy-android/actions/runs/44684318601) passed on `65b7150`. The pre-merge Alpine runtime check and post-install phone smoke test remain pending.

VERDICT: REVISE
