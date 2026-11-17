#!/bin/sh
# PreToolUse hook (Bash): a command that creates a release tag (`git tag v1.2`, quoted or not) or pushes one
# (`git push origin v1.2`, `... refs/tags/x`, `--tags`) runs tools/release-check.sh --quiet in the repository the command
# targets (`git -C dir`, or a `cd dir` before it) and is blocked (exit 2, the recommendation on stderr) unless the check
# says ready (exit 0). Listing, deleting or verifying tags and `push --dry-run` pass untouched, like every other command.
# Override, only when Nicholas says "release now": RELEASE_CHECK_OK=1 in the environment, or as a leading env assignment
# of the git command itself (`RELEASE_CHECK_OK=1 git tag v1`); the same text inside a message or argument counts for nothing.
# The check script is the one next to this hook, so it works from any worktree.
input=$(cat)
# the command string out of the JSON payload, unescaped
cmd=$(printf '%s' "$input" | sed -nE 's/.*"command"[[:space:]]*:[[:space:]]*"(([^"\\]|\\.)*)".*/\1/p' | head -n 1)
[ -n "$cmd" ] || exit 0
# One line per git tag-creation/tag-push found: "<ok 0|1>" then TAB-separated directories (cd's, then -C's) to run in.
hits=$(printf '%s\n' "$cmd" | awk '
function flush() { if (n > 0) seg(); n = 0 }
function unesc(s,   o, i, c, d) {
  o = ""
  for (i = 1; i <= length(s); i++) {
    c = substr(s, i, 1)
    if (c == "\\") { i++; d = substr(s, i, 1); o = o (d == "n" || d == "r" ? "\001" : d == "t" ? " " : d) } else o = o c
  }
  return o
}

# a heredoc body (after <<EOF, << 'EOF', <<-EOF) is data, not commands: remember its delimiter
function add(t) {
  w[++n] = t
  if (hd_next) { hd = t; hd_next = 0 }
  else if (t ~ /^<<</) { }
  else if (t == "<<" || t == "<<-") hd_next = 1
  else if (t ~ /^<<-?./) { hd = t; sub(/^<<-?/, "", hd) }
}
function skip_heredoc(   e, line) {
  while (p < len) {
    e = index(substr(s, p + 1), "\001"); line = e ? substr(s, p + 1, e - 1) : substr(s, p + 1)
    sub(/^\t+/, "", line)
    p += e ? e : len - p
    if (line == hd) break
  }
  hd = ""
}
function seg(   i, k, a, isenv, ok, dirs, verb, list, push, tags, dry, del, name, nopt, remote, ref, parts, m, cl, ch, j, cons) {
  i = 1; ok = 0
  while (i <= n && w[i] ~ /^[A-Za-z_][A-Za-z_0-9]*=/) { if (w[i] == "RELEASE_CHECK_OK=1") ok = 1; i++ }
  if (i > n) return
  if (w[i] == "cd" && i < n) { cwd = cwd (cwd == "" ? "" : "\t") w[i + 1]; return }
  if (w[i] != "git") return
  i++; dirs = ""
  while (i <= n && w[i] ~ /^-/) {
    if (w[i] == "-C" && i < n) { dirs = dirs "\t" w[i + 1]; i += 2 }
    else if ((w[i] == "-c" || w[i] == "--git-dir" || w[i] == "--work-tree" || w[i] == "--namespace") && i < n) i += 2
    else i++
  }
  verb = w[i]; i++
  list = 0; del = 0; name = ""; dry = 0; tags = 0; push = 0; nopt = 0; remote = ""
  for (; i <= n; i++) {
    a = w[i]
    if (nopt == 0 && a == "--") { nopt = 1; continue }
    if (nopt == 0 && a ~ /^--/) {
      if (verb == "tag" && (a == "--list" || a == "--delete" || a == "--verify" || a ~ /^--contains|^--no-contains|^--merged|^--no-merged|^--points-at|^--sort|^--column|^--format/)) list = 1
      if (verb == "tag" && (a == "--message" || a == "--file" || a == "--local-user") && i < n) i++
      if (verb == "push") {
        if (a == "--dry-run") dry = 1
        if (a == "--delete") del = 1
        if (a == "--tags") tags = 1
        if ((a == "--repo" || a == "--push-option" || a == "--receive-pack" || a == "--exec") && i < n) i++
      }
      continue
    }
    if (nopt == 0 && a ~ /^-./) {
      cl = substr(a, 2); cons = 0
      for (j = 1; j <= length(cl); j++) {
        ch = substr(cl, j, 1)
        if (verb == "tag") {
          if (ch ~ /[ldvn]/) list = 1
          if (ch ~ /[mFu]/) { if (j == length(cl) && i < n) i++; break }
        } else if (verb == "push") {
          if (ch == "n") dry = 1
          if (ch == "d") del = 1
          if (ch == "o") { if (j == length(cl) && i < n) i++; break }
        }
      }
      continue
    }
    if (verb == "tag") { if (name == "") name = a }
    else if (verb == "push") {
      if (remote == "") { remote = a; continue }
      ref = a; sub(/^\+/, "", ref)
      m = split(ref, parts, ":")
      for (j = 1; j <= m; j++) if (parts[j] ~ /^(refs\/tags\/|v[0-9])/) tags = 1
    }
  }
  if (verb == "tag" && !list && name ~ /^v[0-9]/) hit(ok, dirs)
  if (verb == "push" && tags && !dry && !del) hit(ok, dirs)
}
function hit(ok, dirs) { print ok (cwd == "" ? "" : "\t" cwd) dirs }
{
  s = unesc($0); len = length(s); q = ""; tok = ""; has = 0
  for (p = 1; p <= len; p++) {
    c = substr(s, p, 1)
    if (q != "") {
      if (c == q) q = ""
      else if (q == "\"" && c == "\\" && p < len) { p++; tok = tok substr(s, p, 1) }
      else tok = tok c
    } else if (c == "\"" || c == "\047") { q = c; has = 1 }
    else if (c == "\\" && p < len) { p++; tok = tok substr(s, p, 1); has = 1 }
    else if (c == " " || c == "\t") { if (tok != "" || has) add(tok); tok = ""; has = 0 }
    else if (c == ";" || c == "&" || c == "|" || c == "\001" || c == "(" || c == ")" || c == "`") {
      if (tok != "" || has) add(tok); tok = ""; has = 0; flush()
      if (c == "\001" && hd != "") skip_heredoc()
    } else tok = tok c
  }
  if (tok != "" || has) add(tok); flush()
}')
[ -n "$hits" ] || exit 0
[ "${RELEASE_CHECK_OK:-}" != 1 ] || exit 0

check=$(cd "$(dirname "$0")/../.." && pwd)/tools/release-check.sh
here=$(pwd)
rc=0; out=""; blocked=""
while IFS= read -r line; do
  [ -n "$line" ] || continue
  ok=${line%%"$(printf '\t')"*}
  [ "$ok" != 1 ] || continue # RELEASE_CHECK_OK=1 leads this very command
  dirs=${line#"$ok"}
  # cd to each directory in turn (a relative -C is relative to the one before)
  out=$(
    IFS=$(printf '\t'); set -f; set -- $dirs
    for d in "$@"; do cd "$d" 2>/dev/null || { echo "cannot enter $d to find the repository this command targets"; exit 1; }; done
    sh "$check" --quiet 2>&1
  ); rc=$?
  [ $rc -eq 0 ] || { blocked=1; break; }
done <<EOF
$hits
EOF
[ -n "$blocked" ] || exit 0
{
  echo "Release tag blocked by tools/release-check.sh (exit $rc):"
  echo "$out"
  echo "Follow the recommendation, or if Nicholas said to release now, rerun with RELEASE_CHECK_OK=1 before the command."
} >&2
exit 2
