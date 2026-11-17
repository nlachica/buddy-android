# Decisions: 023-release-check
<!-- Append-only. One entry each:
## YYYY-MM-DD - <decision>
- Options: ...
- Choice: ... because ...
- By: <role/model or Nicholas>
-->

## 2026-11-17 - What exit 10 means
- Options: 10 only for "wait"; 10 whenever the recommendation is anything but "release now".
- Choice: the second. Ready PRs still to merge or upstream still to bring in also exit 10, so the hook blocks a tag that would leave them out of the batch; the agent merges, reruns, gets 0. Failing, conflicting and draft PRs are listed as excluded and do not block.
- Orchestrator: the hook is a guard against forgetting the check, not a security boundary. Shell-parsing edge cases (comments, line continuations, repeated env assignments, subshell cd, relative pipe paths) are accepted as-is; RELEASE_CHECK_OK=1 is the escape hatch.
- Check: fetches `+refs/heads/main:refs/remotes/origin/main` explicitly, so single-branch or narrow-refspec clones never use a stale origin/main. `--commit <rev>` (default HEAD) is the commit the ancestry check uses; the hook passes the rev of `git tag <name> <rev>` when present. Tests added.
- By: Sonnet 5.5 (orchestrator task)

## 2026-11-17 - Branches without a PR
- Options: ignore; always wait; wait only while recent.
- Choice: recent (commit newer than `RELEASE_CHECK_FRESH_DAYS`, default 3) means work in flight, so wait; older ones are listed as stale and do not block, otherwise one abandoned branch blocks every release forever. "Has a PR" means an open PR; merged branches are those that are ancestors of origin/main (the fork merges with `--merge`).
- By: Sonnet 5.5

## 2026-11-17 - Detecting pipelines
- Options: grep the whole ps line for `pipe (run|plan-loop|code-loop)`; match on argv.
- Choice: argv. The script must be argv[0], or argv[1] after bash/sh/env, so a prompt or `bash -c` string that merely mentions the words does not count. The repo argument must resolve to this repo's git dir (a pipeline on another repo does not hold up a Buddy release); an unresolvable path counts, to fail safe. One pipeline shows as several processes: deduplicated by slug and stage. The "~N min" is a rough constant per stage minus elapsed (run 10, plan-loop 25, code-loop 45, floor 5).
- By: Sonnet 5.5

## 2026-11-17 - Worktrees
- Options: every other worktree; also skip the one the check runs in.
- Choice: skip the main checkout (first in `git worktree list`) and the worktree the check runs in (the caller's own work). "Unpushed" = commits not on `origin/<branch>`, or not on origin/main when the branch is not pushed at all.
- By: Sonnet 5.5

## 2026-11-17 - Checks run against GitHub, fail closed
- Choice: `gh` is always called with `-R nlachica/buddy-android`; the only other repo is read for official Buddy's latest release (`gh release view -R ghorbelhamdi/buddy-android`, the release GitHub marks Latest). A gh/git/ps failure is exit 1, and the hook blocks on it too. Versions compare as dotted numbers; the fork's base is origin/main's BuildInfo without `-fork.N`. A ready PR from `sync/upstream-*` counts as bringing upstream in. No `jq` needed: filtering uses `gh --jq`, JSON is printed by hand.
- By: Sonnet 5.5

## 2026-11-17 - Hook override
- Options: env var only; env var or inline prefix.
- Choice: both. Claude Code's env is fixed when the session starts, so an agent could never set it afterwards; `RELEASE_CHECK_OK=1 git tag v...` works because the hook sees the command text. AGENTS.md allows it only on Nicholas's "release now" and for the sync Tag step when another PR turned ready after the sync PR (that PR waits for the next release).
- By: Sonnet 5.5

## 2026-11-17 - Number clash
- The ask named `023-release-check`; the worktree `/root/.buddy/work/bf-023` already carries an unpushed `feat/023-idle-chat-delivers-messages`. Kept the asked name; rename one of them if both ship. (The check itself lists that worktree as unpushed work.)
- By: Sonnet 5.5

## 2026-11-17 - Codex review fixes (hook and check)
- Hook parses the command instead of grepping the payload. It takes the `command` string out of the JSON, unescapes it, tokenizes it with shell quoting (so `git tag "v2.1"` is seen, a quoted message is not), splits on `; & | ( ) \n` and skips heredoc bodies. Only two things trigger the check: `git tag` that creates a `v<digit>...` tag (no `-l/--list/-d/--delete/-v/--verify/-n`, option arguments of `-m/-F/-u` skipped) and `git push` of a tag (`v<digit>...` or `refs/tags/...` refspec, or `--tags`) without `--dry-run/-n` or `--delete`. Listing, deleting and dry runs pass. Unparseable shell fails towards blocking, which the override covers.
- `RELEASE_CHECK_OK=1` counts only in the environment or as a leading env assignment of the very `git` command, never inside a message, a later argument, a comment or another command's prefix.
- The check runs in the repo the command targets: `git -C dir` (and `cd dir` earlier in the command), directories entered in order so a relative `-C` follows a `cd`. A directory it cannot enter blocks ("cannot enter ..."). The check script itself is the one next to the hook.
- Check: HEAD must contain `origin/main` (after the fetch). If not: exit 10, "update this checkout first (git merge --ff-only origin/main), then rerun". Exit 0 now means a tag of this commit includes everything merged for the batch. JSON gets `head_has_main`.
- Check: branches come from `git ls-remote --heads origin` (feat/*, fix/*), not local remote-tracking refs, so a single-branch clone sees them. Their commits are fetched into `refs/release-check/` (pruned each run) for dates and the merged test; an unreadable branch is exit 1.
- Check: an open PR hides a branch only when its head branch name matches and `headRepositoryOwner` is `nlachica`; another fork's same-named PR does not. A PR without owner info does not hide it (fail safe).
- Check: a running pipeline counts when its repo argument is any clone of Buddy, whichever clone the check runs in: same git dir, same `origin` URL, an origin URL containing `buddy-android`, or a folder named `buddy-fork*`, `bf-*` or `buddy-android*`. An unresolvable path still counts; a resolvable repo that matches none does not.
- Tests added for each (`tools/release-check-test.sh`), also run with busybox sh/awk/sed/grep.
- By: Sonnet 5.5
