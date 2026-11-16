# Code review: 011-settings-keys
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

No blocker or major findings. Reviewed `git diff main...HEAD`, the brief, plan, decisions and plan-review resolutions, the touched files, and the connected approval, caller identity, Linux root, MCP, provider, secret-storage and screen save paths. Assumed the existing single-phone usage, including concurrent tool requests and edits in Settings.

The implementation follows the revised plan: keys come from a bounded, confined file read; denial prevents verification and storage; existing-record verification uses the destination captured for the card; stale saves are refused; existing-record errors suppress stored addresses; new-server errors redact the supplied credential. MCP and service keys use the existing persistence APIs. The approval gate and F-Droid dispatch restriction are unchanged. No unnecessary dependency or abstraction was introduced.

Validation: `sh /tmp/buddy-build-011/check.sh github` compiled the sources and passed all 72 JVM tests, including the new key tests and existing approval/settings tests. This is the documented fallback harness (JDK 17, real org.json, Android/watch stubs, and an API-36 call omitted in its temporary source copy), not a Gradle/APK build. The tests cover the substantive planned file, masking, denial, removal, stale-destination and error-disclosure cases; concrete persistence and network integration were also inspected in source.

Not checked: Android release builds, Wear build, F-Droid reproducibility and device behavior. The post-install Composio add/tool-call and key-change checks remain required completion evidence in `verify.md`; this code-review approval does not mark those done. The accepted directory-swap race and pre-existing relay-redaction limitation remain as recorded in the plan.

VERDICT: APPROVE
