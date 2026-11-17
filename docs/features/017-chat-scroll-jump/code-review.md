# Code review: {{SLUG}}
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

Reviewed `git diff main...HEAD` at `316c257361eee4a960634ded9ead5824e50feed8`, the brief, final plan, decisions, plan-review history, and the affected callers in Bubble, ChatActivity and ChatHub. No blocker, major, minor or nit findings.

The implementation follows the approved plan: both end-scroll helpers avoid moving focus; the full-chat guard compares the focused reply and both selection offsets while retaining immediate rectangle requests and existing focus navigation; resume appends missed messages while reading back; same-chat Bubble returns reuse the views and refresh the header; trimming is deferred while reading back; and both automatic log-close entry points use the preservation guard. No new security, data-handling or unnecessary-abstraction issue found.

Validation: independently compiled and ran `ChatScrollTest` with JDK 17 and JUnit 4.13.2 from the app module directory: **OK (2 tests)**. These are the planned source-regression guards, not behavioral UI tests. The normal JVM could not start (`Failed to mark memory page as executable`). Retrying `./gradlew --offline --no-daemon testGithubDebugUnitTest` with JDK 17 reached Gradle but failed resolving the uncached Android application plugin `9.4.1`, before compilation or tests. The full build/unit suite and CI are therefore not verified by this review. `git diff --check main...HEAD` found only trailing whitespace in the existing verification-template verdict line; no code whitespace issue.

This is code approval, not feature completion. Done-when 1 still requires the controlled before/after phone reproduction; done-when 2 still requires the planned position, log, navigation and selection checks on both screens; done-when 3 still requires green CI and Nicholas's phone check. No device checks were performed here. A continuing Bubble jump must fail verification and return to diagnosis within this feature, as the plan requires.

VERDICT: APPROVE
