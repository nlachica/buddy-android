# Verification: 008-upstream-in-update
| Done-when | Evidence | Result |
|---|---|---|
| Script merges upstream, detects conflicts | 2026-11-16 trial run on v2.3.10: exit 3, conflict docs/GUIDE.md, 5 both-changed files listed, BuildInfo 2.3.10-fork.1 code 212; trial worktree discarded | pass |
| update_buddy upstream note, no-upstream unchanged | Unit tests of Updates.report in CI (run on 9717a80 green); device check after the 2.3.10 sync release | pass (CI) |
| First real run brings 2.3.10 | Done as the sync right after this merge | see sync PR |
VERDICT: SHIP (merged without its own release; ships inside 2.3.10-fork.1)
