# Code review: 005-settings-coverage
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

Reviewed `main...HEAD` at `33740889670e4986dc65db54e369b319dc5118df`, the brief, plan, decisions, inventory, and affected screen/store handlers. No earlier review findings or Coder rejections exist.

F1 [major] A pending app-permission card can silently become a persistent scheduled permission - `app/src/main/java/app/buddy/assistant/SettingsTool.java:1229`: `scheduled` is captured when the row is created, but its snapshot at line 1231 contains only `on`/`off`. Start with an unscheduled chat and an app not allowed; request `on`; add a schedule in the chat UI while the card is open; approve. The snapshot still equals `off`, so the setter succeeds although the card promised only "until this chat's task ends". `ChatHub.setAllowed` (line 523) now saves the permission for scheduled runs, and `runEnded` (line 980) keeps it. A scratch probe through the real `allowedApp`/`change` seam reproduced the stale card and successful write after the schedule flag changed. -> Include scheduled/non-scheduled scope in this row's snapshot and derive the card from that snapshot, refusing the write if the scope changes before commit. Keep the shared gate unchanged; add a schedule-added-during-card regression test that asserts no write.

F2 [minor] Model validation accepts multiple line breaks - `app/src/main/java/app/buddy/assistant/SettingsTool.java:497`: `v.matches(".*\\s.*")` only rejects whitespace when the entire string can match; Java's `.` does not cross line breaks. Consequently `one\ntwo\nthree` passes `model.check`, reproduced against the compiled implementation. Both model families can save a value outside the planned one-line, no-whitespace contract, producing invalid model requests (or the reviewer's fallback rather than the chosen model). -> Search for whitespace with a matcher using `find()`, or validate the entire value against a no-whitespace pattern. Add cases with multiple LF and CR/LF sequences for both families.

F3 [blocker] Required CI has not run successfully - `docs/features/005-settings-coverage/plan.md:238` and `.github/workflows/build.yml:59`: the required Gradle tests and phone/Wear release builds have no green result. The orchestrator supplied `HTTP 403: Must have admin rights to Repository` dispatching workflow `377393827` on `ghorbelhamdi/buddy-android`. Locally, `origin` is `nlachica/buddy-android` and `upstream` is `ghorbelhamdi/buddy-android`; the pipeline runner invokes `gh workflow run` without an explicit repository. This is a dispatch failure, not evidence that compilation failed, but red CI is explicitly a blocker for this run. -> Have the orchestrator target the intended fork explicitly for dispatch and run lookup/watch, resolve its Actions access if needed, and attach a green run for the reviewed commit. Do not substitute the scratch javac check for Gradle/Android/Wear CI.

F4 [major] Embedded NULs in a service name expose a secret-bearing address - `app/src/main/java/app/buddy/assistant/SettingsTool.java:584`: the service snapshot joins unescaped fields with NUL, while `view` at lines 591–593 selects fields by index and name validation at line 602 rejects only blank/CR/LF. A name `Work\u0000x\u0000y` is accepted; after saving it, reading `service/a/models` returns the service's full OpenAI address as the current shown-model list. A scratch probe using only synthetic data printed `service/a/models = https://synthetic.invalid/v1?token=FAKE_SECRET`. Three embedded NULs similarly move the Anthropic address into that position. JSON tool arguments can carry these characters, and reads require no approval. -> Use an escaped/structured snapshot (the existing JSON library is sufficient) and render only the intended non-secret field. Reject control characters in new names as appropriate, but also safely handle already-stored names. Add no-secret regression cases with embedded NULs for reads, cards and stale-write replies.

Validation and coverage:
- `/tmp/buddy-build-005/check.sh github`: PASS, 42 tests, including the new coverage tests and existing approval/family tests. This compiles scratch copies against android-all with the documented API-36 omission and Os shim; it does not validate the Gradle, watchlink, Wear, packaging or native build.
- Separate scratch probes against those compiled classes reproduced F1, F2 and F4. No production code or repository tests were modified.
- The inventory assigns final covered/excluded statuses, and the implemented families follow the plan's scope. No additional YAGNI or convention finding warrants a change.
- Phone listing, availability checks, approve/deny checks for all 12 families, and device stale-write evidence remain pending: `verify.md` has no evidence yet. As the plan schedules these after installation, this review does not claim the device done-when item is satisfied; verification must remain PARTIAL until they are recorded.

VERDICT: REVISE

### Coder response (round 1)

- F1: fixed in the commit "005 review round 1". `allowedApp` takes a `BooleanSupplier` for the schedule and puts the scope in the snapshot (`on`/`off` plus `+scheduled`); `view` strips it, and the card's "scheduled runs" text comes from the snapshot. A schedule added while the card is open now makes the write stale. Test: `aScheduleAddedWhileTheAllowedAppCardIsOpenIsNotWritten` (no write, reply says it changed). The shared gate is unchanged.
- F2: fixed in the same commit. Whitespace is searched with `Pattern.find()`, so any line break is refused. Cases with several LF and CR/LF added for both model families (`default_model` and `reviewer_model`).
- F3: not a code change; the Coder can't dispatch CI, and this run's orchestrator instructions say not to tag or release. Left to the orchestrator: dispatch with `-R nlachica/buddy-android` (and look up/watch the run the same way), then attach the green run for the reviewed commit. Until then the Gradle/watchlink/Wear builds are CI-only and unverified; the scratch javac run is not a substitute.
- F4: fixed in the same commit. The service snapshot is a JSON array and `view` reads the one field it needs, so an embedded NUL can't move an address into another part. New names are refused if they contain any control character (`\p{Cntrl}`, which covers CR and LF); an already-stored name is read safely. Test: `aServiceNameWithControlCharactersNeverMovesAnAddressIntoAnotherPart` covers a read, a card and a stale-write reply with no secret in any of them.

Tests: `/tmp/buddy-build-005/check.sh github` and `fdroid` both OK, 44 tests (javac + JUnit against android-all, no Gradle).

## Round 2 - Code Reviewer (gpt-6-astra)

Reviewed `main...HEAD` at `08d0b12e3cae4401218eddc6c05a70448e91cbe6`, including the Round 1 fixes, the brief, plan, decisions, inventory, tests, and the affected screen/store handlers. No production code or repository tests were modified.

F3 [blocker] Required CI remains unverified - `docs/features/005-settings-coverage/plan.md:238` and `.github/workflows/build.yml:59`: the orchestrator again supplied the HTTP 403 dispatch failure against `ghorbelhamdi/buddy-android`. A read-only GitHub API lookup of `nlachica/buddy-android/actions/runs?head_sha=08d0b12e3cae4401218eddc6c05a70448e91cbe6` returned `total_count: 0`. There is still no green Gradle/Android/Wear build for this commit. The Coder correctly left repository access and dispatch to the orchestrator; this remains an infrastructure/verification blocker, not a demonstrated compilation defect. -> Target the fork explicitly (`-R nlachica/buddy-android`) for dispatch and run lookup/watch, resolve its Actions access if necessary, and attach a successful required workflow run for the reviewed commit before approval.

Resolved findings from Round 1:
- F1: resolved. `allowedApp` now includes scheduled scope in its snapshot and derives the card's scope from that snapshot. The regression test adds a schedule during approval and verifies that the setter is never called.
- F2: resolved. The model validator uses a whitespace matcher with `find()`. The tests reject multiple line breaks in both model families and CR/LF sequences in the default-model family.
- F4: resolved. Service snapshots use a JSON array; rendering selects the intended field without delimiter ambiguity. New names reject control characters, and the regression test checks reads, a card and a stale-write reply using an already-stored name containing NULs, without exposing either synthetic address.

Validation and coverage:
- `/tmp/buddy-build-005/check.sh github`: PASS, 44 tests.
- `/tmp/buddy-build-005/check.sh fdroid`: PASS, 44 tests.
- These checks compile scratch copies using javac/JUnit and android-all, with the documented API-36 omission and Os shim. They do not establish that Gradle, native libraries, watchlink, Wear, packaging or reproducibility checks pass.
- The inventory has final covered/excluded statuses, and the implemented families and JVM cases match the agreed scope. No additional blocker or major code, security, YAGNI or convention finding was identified.
- Phone listing, availability checks, approved/denied cards for the 12 families, and device stale-write evidence remain pending in the empty `verify.md`. The plan schedules these after installation; the device done-when item remains unfulfilled and verification must remain PARTIAL until evidence is recorded.

VERDICT: REVISE

### Coder response (round 2)

- F3: not fixed by the Coder, and no code change is needed. Round 2 finds no defect in the code: F1, F2 and F4 are resolved. The open item is a green CI run for `08d0b12`, and the Coder can't dispatch workflows or change repository access. This run also forbids tagging or releasing. Left to the orchestrator: run `gh workflow run -R nlachica/buddy-android` (and look up/watch the run with `-R nlachica/buddy-android` too), then attach the green run for the reviewed commit. Until then the Gradle, watchlink, Wear and packaging builds are CI-only and unverified; the scratch javac run is not a substitute.

Tests (re-run at HEAD, no code changes this round): `/tmp/buddy-build-005/check.sh github` OK, 44 tests; `/tmp/buddy-build-005/check.sh fdroid` OK, 44 tests (javac + JUnit against android-all, no Gradle).

## Round 3 - Code Reviewer (gpt-6-astra)

Reviewed `main...HEAD` at `a126bb4171e9957322ea285ace20309d989f6499`, the brief, plan, decisions, earlier rounds, checked inventory, touched Java files and relevant screen/store handlers. This final round reports blocker/major findings only. Production code and tests are identical to Round 2 (`08d0b12`); only review notes have changed since then. No code or tests were modified during this review.

F3 [blocker] Required CI still has no successful run - `docs/features/005-settings-coverage/plan.md:238` and `.github/workflows/build.yml:59`: the orchestrator again reports `HTTP 403: Must have admin rights to Repository` while dispatching workflow `377393827` against `ghorbelhamdi/buddy-android`. A read-only lookup of `nlachica/buddy-android/actions/runs?head_sha=a126bb4171e9957322ea285ace20309d989f6499` returned `total_count: 0`. The required Gradle unit tests and Android/Wear release builds therefore remain unverified. The Coder's response correctly assigns dispatch/access to the orchestrator; it does not resolve this explicit CI gate. This is an infrastructure/verification blocker, not evidence of a compilation defect. -> Have the orchestrator dispatch the intended branch explicitly with `gh workflow run build.yml -R nlachica/buddy-android --ref feat/005-settings-coverage`, use the same fork for run lookup/watch, resolve Actions access if needed, and attach a successful required workflow run for the reviewed commit before approval.

Validation and coverage:
- `/tmp/buddy-build-005/check.sh github`: PASS, 44 tests at this HEAD. This is the documented scratch javac/JUnit check against android-all with the API-36 omission and Os shim; it does not establish a successful Gradle, native, watchlink, Wear or packaging build. Round 2's F-Droid scratch pass applies to identical code; it was not repeated.
- Round 1 F1, F2 and F4 remain resolved: scheduled scope participates in the allowed-app snapshot, model whitespace uses a search, and JSON service snapshots prevent embedded NULs from shifting secret-bearing addresses into displayed fields. Their regression tests pass.
- The inventory and implemented families match the agreed scope. No additional blocker or major correctness, security, YAGNI or convention finding was identified.
- Phone listing, availability refusals, approved/denied cards for all 12 families and device stale-write evidence remain unrecorded in `verify.md`. These are scheduled after installation by the plan; the device done-when item remains unfulfilled and verification must remain PARTIAL until evidence is recorded.

VERDICT: REVISE
