# Code review: 008-upstream-in-update
<!-- Rounds: "## Round N - Code Reviewer (model)", findings F1.., "VERDICT: APPROVE|REVISE", then "### Coder response (round N)" -->

## Round 1 - Code Reviewer (gpt-6-astra)

F1 [minor] Release guard does not validate the numeric fork counter - `tools/upstream-sync.sh:35-36` only checks that removing `-fork.*` changes VERSION, so it accepts a nonnumeric or empty counter despite promising `<upstream>-fork.<n>`. Reproduced in throwaway repositories using the existing test helpers: merge upstream 1.0.1, make a fix-forward commit with VERSION `1.0.1-fork.next` and VERSION_CODE 14, then run `--release` on it; the guard exits 0 and prints `git tag v1.0.1-fork.next ... && git push origin v1.0.1-fork.next`. `Updates.compare` treats `next` as zero, so that release would not be offered to a phone on `1.0.1-fork.1` despite its higher Android version code. Normal script-generated versions are valid, so this affects manually prepared fix-forward candidates. -> Validate the numeric fork suffix before accepting the candidate, and add malformed/empty-counter cases to the existing script tests.

No blocker or major findings. Reviewed `git diff main...HEAD` at `9717a80cdb56745bf3f6d62e29bb790c49611fd0`, the touched implementation and workflow, the brief/plan/decisions, and prior plan-review resolutions. The tool and Settings paths implement the planned detection and handoff, preserve the install approval route, and retain existing output when the upstream note is absent. The sync and recovery cases follow the approved plan; no additional security or unnecessary-abstraction findings. The accepted reproducibility-step ordering is not reopened.

Validation: `bash tools/upstream-sync-test.sh` passed all cases; `bash linux/test/run.sh` passed 31 fixtures/runs; `/tmp/buddy-build-007/check.sh github` passed 57 JUnit tests (the existing local javac/android.jar shim harness, not a Gradle APK build). GitHub Actions [run 43781593371](https://github.com/nlachica/buddy-android/actions/runs/43781593371) completed successfully for the exact reviewed HEAD, covering the configured Android builds/tests and reproducibility check.

Coverage limit: this approves the implementation for the planned rollout, not final feature verification. Done-when items 1 and 4 still require R2's actual upstream sync, release, phone installation, and feature/hardware evidence; `verify.md` remains open as the plan requires.

VERDICT: APPROVE
