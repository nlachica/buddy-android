#!/bin/sh
# PreToolUse hook (Bash): a release tag command (`git tag v...`, `git push origin v...`) runs tools/release-check.sh
# --quiet and is blocked (exit 2, the recommendation on stderr) unless the check says ready (exit 0).
# Override, only when Nicholas says "release now": RELEASE_CHECK_OK=1 in the environment, or as a prefix of the command.
# Any other command passes untouched. Paths are relative to this file, so it works from any worktree.
input=$(cat)
sp='[[:space:]]+'
git="git($sp-C$sp[^[:space:];&|]+)?$sp"
rest="[^;&|]*"
printf '%s' "$input" | grep -Eq "${git}tag${sp}(${rest}${sp})?v[0-9]|${git}push${sp}${rest}origin${sp}(${rest}${sp})?v[0-9]" || exit 0
[ "${RELEASE_CHECK_OK:-}" != 1 ] || exit 0
printf '%s' "$input" | grep -Eq "RELEASE_CHECK_OK=1" && exit 0
check=$(dirname "$0")/../../tools/release-check.sh
out=$(sh "$check" --quiet 2>&1); rc=$?
[ $rc -ne 0 ] || exit 0
{
  echo "Release tag blocked by tools/release-check.sh (exit $rc):"
  echo "$out"
  echo "Follow the recommendation, or if Nicholas said to release now, rerun with RELEASE_CHECK_OK=1 before the command."
} >&2
exit 2
