# Plan review: 004-self-modify
<!-- Rounds: "## Round N - Plan Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Planner response (round N)" -->

## Round 1 - Plan Reviewer (gpt-6-astra)

F1 [major] Alternate self-install paths bypass the new card - The brief requires an approval card before each self-install, but the plan gates only `update_buddy` (`plan.md:124-127`). `Tools.installApk` copies any APK and opens the installer without a Buddy card (`app/src/main/java/app/buddy/assistant/Tools.java:2102-2132`), so the agent can download the same fork build and use that tool instead. The sheet exemption also assumes a human initiated it: Check updates and the sheet's Update button are unprotected (`SetupActivity.java:787-795`, `Updates.java:214-246`), and `Guard.offLimits` permits unprotected Buddy controls when `agents_tap_buddy` is on (`Guard.java:485-494`). -> Cover self-package APKs in `install_apk` with the same mandatory approval gate (or refuse that route and direct callers to the gated updater), while preserving other-app installs. Make the exempt update-sheet action genuinely user-only. Add denial/bypass checks for both routes, including with `agents_tap_buddy` enabled.

F2 [major] Masking the MCP key field does not keep credentials out of tool output - The plan reads full MCP `{url,on}` values and masks only the separate key (`plan.md:32,96-100`). `McpServers.addressOk` accepts arbitrary HTTPS URLs (`app/src/main/java/app/buddy/assistant/McpServers.java:94-95`), and the complete URL is stored in Prefs (`McpServers.java:139-153`). Existing URLs can carry credentials in user-info, query strings or opaque path segments; returning them violates the brief's no-secrets requirement. The existing relay deliberately exports neither URL nor key to agents (`McpServers.java:135-137`). -> Keep existing raw server URLs private; expose a safe identifier/host and key-presence indicator, and permit on/off changes without requiring the caller to round-trip the URL. Define redaction for list/read/change replies, cards and errors. Test representative credential-bearing URLs as well as separately stored keys.

F3 [major] A pending card is not bound to the state it describes - The planned `validate → card → apply` path has no post-approval state check (`plan.md:33,46-55`), although a card can wait five minutes (`app/src/main/java/app/buddy/assistant/BuddyService.java:899-901`) and UI edits or concurrent tool requests can change settings meanwhile (`McpServer.java:59`, `RulesActivity.java:431-445`). For example, a card showing only a rule's `on` change can later overwrite a newly edited instruction when its old whole-rule snapshot is saved. This loses data and applies changes absent from the card; accepting stale screens (`plan.md:205`) does not address stale writes. -> Snapshot the full target state without mutating cached objects, then re-read and compare immediately before committing. If it changed or disappeared, return unchanged and require a fresh request/card. Serialize the comparison and write with the relevant store/UI mutation path, without blocking the main thread during approval. Test changes/deletion while the asker is pending.

F4 [major] Rule JSON can select a different target than the setting name - The plan uses `rule/<id>` plus `Rules.Rule` JSON and `Rules.put`, with validation specified only for `pkg` and `approval` (`plan.md:94`, `decisions.md`, "Value formats"). `Rule.of` accepts the JSON's independent `id` (`app/src/main/java/app/buddy/assistant/Rules.java:47-49`), and `Rules.put` replaces whichever rule has that ID (`Rules.java:135-146`). Thus a request named `rule/A` with JSON `id=B` can show A's old value while overwriting B; omitting the ID instead generates a new rule. -> Make the path ID authoritative for edits, reject conflicting IDs, and generate a fresh ID internally for `rule/new`. Validate the editable fields against the UI constraints, including a nonempty instruction (`RulesActivity.java:436-440`). Test mismatched/omitted IDs and ensure no other rule changes.

F5 [major] Slug validation does not confine skill reads and writes - `plan.md:95` constrains the name to a slug but then reads/writes `Skills.dir/<name>/SKILL.md`. Existing skill enumeration follows directory and file symlinks (`app/src/main/java/app/buddy/assistant/Skills.java:40-49,190-193`). `Linux.write` replaces a leaf symlink but follows a symlinked parent directory (`Linux.java:358-362`). A valid slug can therefore reference a skill directory linked outside the skills root, letting the new settings tool read unrelated files or overwrite an external `SKILL.md` under a misleading skill card. -> Define and enforce filesystem confinement for list/read/create/edit/delete; the simplest safe tool policy is to reject linked skill directories/files and verify canonical containment before access and again before applying an approved write. Add temporary-directory tests for linked directories and linked `SKILL.md` files, proving that no external contents are returned or changed.

VERDICT: REVISE

### Planner response (round 1)

F1: accepted - The carded `update_buddy` is now the only way an agent gets Buddy's installer opened on Buddy's own
package (plan B.4, Step 5). In the github flavor, `install_apk` reads the copied APK's package (`getPackageArchiveInfo`)
and refuses Buddy's own package, or an APK Android can't read: it deletes the copy and points to `update_buddy
install=true`. Other apps install as before. It refuses rather than adding a second card, because one carded route
meets the brief and a fork-signed APK only comes from the fork's CI, which publishes it as a release. The sheet's
Update button gets `Ui.protect`, so it stays user-only with `agents_tap_buddy` on. Opening the sheet installs nothing,
so that stays as it is. `Updates.install` and `installApk` write `install.apk` and open the installer under one lock,
so one route can't swap a file another has just checked. `update_buddy` uses the gate's own `approval()` helper, so
the gate's unit tests cover its denial. Device checks were added for both routes, including the sheet tap with
`agents_tap_buddy` on (Test plan 3). `phone_shell`'s `pm install` is noted in Risks: it has its own per-command card,
and that switch is read-only in the tool.

F2: accepted - No stored MCP address, or any part of it (host included), goes into a reply, card or error. A server
shows as `mcp/<id> = on|off (key: set|none, N tools)`. Value forms changed: an existing server takes `on`, `off` or
`""`, so the URL never round-trips. A URL is accepted only to add a keyless server under a free id, and the card
shows that URL, which is the agent's own input. On/off and delete never connect to the server, so no error can
carry its address. A unit test covers credential-bearing URLs (user-info, query token, secret path segment) and a
saved key in the list line, read text and card (Test plan 1). This supersedes the `{url,on}` format (decisions.md).

F3: accepted - The gate is now snapshot → card → re-check-and-commit (plan A, gate steps 2-5). On the main thread
it reads the whole current value (whole rule or schedule JSON, whole SKILL.md, the server's stored entry). New values
are built as fresh objects, and cached objects aren't touched. After the user's APPROVED, one main-thread task
re-reads and compares with the snapshot, then writes. If the target changed or is gone, the reply is "changed while
the card was open; ask again" and nothing is written. The settings screens write on the main thread, so this is
serialized with them and with other tool commits. The card waits on the tool thread. MCP discovery (network) runs
after approval and before the commit. `main` is an injected `Executor` (`Runnable::run` in tests). Unit tests: the
fake asker changes or deletes the setting before approving, and the setter is never called. A device check covers a
rule edited in Settings while the card waits.

F4: accepted - For `rule/<id>` the path id wins: the rule must exist, an `id` in the value must be absent or equal,
and the tool sets it before `Rule.of`. `rule/new` refuses an `id` and gets a fresh one, not in use, at commit. The
checks follow `RulesActivity.save` and the editor's choices: `pkg` non-empty, instruction non-empty after trimming,
`approval`/`seconds`/`limit` from the editor's sets, and `backend` from the agents that run rules. Unit tests cover a
mismatched id, an omitted id, `rule/new` with an id, and an empty instruction. The real `org.json` is added as a
test-only dependency so `Rule.of` runs in JVM tests. A device check shows other rules unchanged.

F5: accepted - One check, `skillFile(root, name)`, guards list, read, create, edit and delete. The name must be a
slug, neither the skill folder nor its `SKILL.md` may be a symlink (`Files.isSymbolicLink`, as `Skills.isLink`
already does), and the canonical path must be inside the canonical skills folder. Linked skills are listed by name
only and can't be read or changed. The listing doesn't use `Skills.all`, which reads through links. The commit
re-runs the check right before `Linux.write`/`Skills.delete`. JVM temp-folder tests use real symlinks to a linked
folder and a linked `SKILL.md`: they are refused, and the outside file is never returned and stays unchanged. The
remaining millisecond swap window is in Risks: that agent can already write in Buddy's Linux with its own shell.

## Round 2 - Plan Reviewer (gpt-6-astra)

F1 [major] The APK lock ends before the recipient finishes reading the file - The round-1 fix in `plan.md:178-179` locks copying/checking and opening the installer, but every request still uses the same `cache/install.apk` and content URI (`app/src/main/java/app/buddy/assistant/FilesProvider.java:29-35,55-59`). `Updates.install` and `Tools.installApk` only hand that URI to `startActivity` (`Updates.java:118-122`, `Tools.java:2128-2131`); the file is opened separately by `FilesProvider.openFile` (`FilesProvider.java:72-77`). Android's [shared-file access documentation](https://developer.android.com/training/secure-file-sharing/request-file#AccessFile) likewise separates receiving a URI from opening its descriptor. Thus the proposed lock does not bind the installer's later read to the checked APK: another install request can truncate/replace it after the first launch, and the newly planned refusal can delete it (`plan.md:170-172`). This can corrupt a pending self-update or make its installer consume a different APK than the approved one. -> Give each install attempt its own staged file and URI, validate and launch that exact file, and retain it unchanged while the installer can consume it. Have refusal/failure delete only that attempt's file; define bounded cleanup without removing another pending handoff. Add a regression check that delays opening/reading the first URI until a second successful or refused request has run, and verifies the first still yields the original bytes. This is new evidence about the proposed round-1 fix, not an objection to refusing self-APKs in `install_apk`.

F2 [major] Main-thread commits do not serialize with the existing skill importer - The stale-write fix relies on the claim that settings screens write on the main thread (`plan.md:67-70`), including skills (`plan.md:52-54,129`). However, `SkillsActivity.onActivityResult` starts a background `skill-import` thread that calls `Skills.importUri` (`app/src/main/java/app/buddy/assistant/SkillsActivity.java:164-180`). That method writes directly into the destination skill directory, including existing files (`Skills.java:125-130,150-162`). An import can therefore write a new SKILL.md between the tool's comparison and its write/delete, losing content absent from the approval card; imports can also interleave with `Linux.write`, which deletes and recreates the file (`Linux.java:358-362`). This is an ordinary UI operation, distinct from the explicitly accepted hostile Linux symlink-swap risk. -> Serialize the tool's skill re-check/write/delete with Buddy's own create/import/delete mutation paths using a shared skill-store critical section; keep URI reading and archive preparation outside that section and never hold it while waiting for approval. Add a coordinated concurrent-import test proving an import cannot land between comparison and commit and that an import completed before the comparison causes the stale request to be refused. The main-thread approach can remain for stores whose writers actually run there.

VERDICT: REVISE
