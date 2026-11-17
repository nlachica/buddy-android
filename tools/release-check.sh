#!/bin/sh
# Pre-release batching check: is it a good moment to bump the version and tag, or should something land first?
# Run it before any version bump or `v*` tag (AGENTS.md, "Updating Buddy on the phone"). Reports, never changes anything.
#   tools/release-check.sh [--json] [--quiet]
#     --json   one JSON object (for agents)       --quiet  only the RECOMMENDATION line
# Exit: 0 ready (release now) | 10 not ready: do what RECOMMENDATION says first | 1 error (gh/git failed: fail closed)
# Reads: open PRs of the fork (gh), origin's feat/* and fix/* branches, `pipe` processes (ps), other git worktrees of
# this repo, and official Buddy's latest release against the version in origin/main's BuildInfo.
# Env: RELEASE_CHECK_FRESH_DAYS (default 3): a branch without PR newer than this counts as work in flight.
# POSIX sh, busybox-friendly (no grep --include, no GNU-only flags).
set -u
set -f # ps args are split into words below; never glob them
FORK=nlachica/buddy-android
UPSTREAM=ghorbelhamdi/buddy-android
BI=app/src/main/java/app/buddy/assistant/BuildInfo.java
FRESH_DAYS=${RELEASE_CHECK_FRESH_DAYS:-3}
TAB=$(printf '\t')

die() { echo "release-check: $*" >&2; exit 1; }
js() { printf '%s' "$1" | tr -d '\n\r\t' | sed 's/\\/\\\\/g; s/"/\\"/g'; }
fmt_age() { if [ "$1" -ge 86400 ]; then echo "$(($1 / 86400))d"; elif [ "$1" -ge 3600 ]; then echo "$(($1 / 3600))h"; else echo "$(($1 / 60))m"; fi; }
# vgt A B: success when dotted version A is greater than B
vgt() { awk -v a="$1" -v b="$2" 'BEGIN{na=split(a,x,"[.]");nb=split(b,y,"[.]");n=(na>nb?na:nb);
  for(i=1;i<=n;i++){p=x[i]+0;q=y[i]+0;if(p>q)exit 0;if(p<q)exit 1}exit 1}'; }
join() { if [ -z "$1" ]; then printf '%s' "$2"; else printf '%s%s%s' "$1" "$3" "$2"; fi; } # join <acc> <item> <sep>

json=0 quiet=0
for a in "$@"; do
  case $a in
    --json) json=1 ;;
    --quiet) quiet=1 ;;
    -h | --help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $a (try --help)" ;;
  esac
done

top=$(git rev-parse --show-toplevel 2>/dev/null) || die "not inside a git repo"
cd "$top" || die "cannot cd to $top"
here=$(pwd -P)
git fetch -q --prune origin || die "git fetch origin failed"
git rev-parse -q --verify refs/remotes/origin/main >/dev/null || die "origin/main not found"
now=$(date +%s)
main7=$(git rev-parse --short origin/main)

# ---------- open PRs ----------
JQ='.[] | ((.statusCheckRollup // []) as $c | (if ($c|length)==0 then "pending"
  elif any($c[]; ((.conclusion // "")|IN("FAILURE","TIMED_OUT","CANCELLED","ACTION_REQUIRED","STARTUP_FAILURE")) or ((.state // "")|IN("FAILURE","ERROR"))) then "fail"
  elif any($c[]; ((.status // "COMPLETED") != "COMPLETED") or ((.state // "")|IN("PENDING","EXPECTED"))) then "pending"
  else "pass" end)) as $ci | [.number, .headRefName, .mergeable, .isDraft, $ci] | @tsv'
prs=$(gh pr list -R "$FORK" --state open --limit 100 --json number,headRefName,mergeable,isDraft,statusCheckRollup --jq "$JQ") ||
  die "gh pr list failed (is gh logged in and online?)"

ready="" pend="" excl="" ready_n=0 pend_n=0 sync_pr_ready="" heads="" t_prs="" j_prs="" npr=0
while IFS=$TAB read -r num br mrg draft ci; do
  [ -n "$num" ] || continue
  npr=$((npr + 1))
  heads="$heads
$br"
  if [ "$draft" = true ]; then st=excluded why="draft"
  elif [ "$ci" = fail ]; then st=excluded why="failing CI"
  elif [ "$mrg" = CONFLICTING ]; then st=excluded why="merge conflicts"
  elif [ "$ci" = pending ] || [ "$mrg" != MERGEABLE ]; then st=pending why="CI pending"
  else st=ready why=""
  fi
  case $st in
    ready) ready=$(join "$ready" "#$num" " "); ready_n=$((ready_n + 1))
      case $br in sync/upstream-*) sync_pr_ready=1 ;; esac ;;
    pending) pend=$(join "$pend" "#$num" " "); pend_n=$((pend_n + 1)) ;;
    excluded) excl=$(join "$excl" "#$num $why" ", ") ;;
  esac
  t_prs="$t_prs
  #$num  $br  CI $ci  $(printf '%s' "$mrg" | tr 'A-Z' 'a-z')  -> $st${why:+ ($why)}"
  j_prs=$(join "$j_prs" "{\"number\":$num,\"branch\":\"$(js "$br")\",\"ci\":\"$ci\",\"mergeable\":\"$(js "$mrg")\",\"draft\":${draft:-false},\"status\":\"$st\"}" ",")
done <<EOF
$prs
EOF

# ---------- remote feat/* and fix/* branches, unmerged and without an open PR ----------
fresh="" t_br="" j_br="" nbr=0
refs=$(git for-each-ref --format='%(refname:short) %(committerdate:raw)' refs/remotes/origin/feat refs/remotes/origin/fix) ||
  die "git for-each-ref failed"
while read -r ref epoch tz; do
  [ -n "$ref" ] || continue
  name=${ref#origin/}
  git merge-base --is-ancestor "refs/remotes/$ref" refs/remotes/origin/main && continue # merged
  case "$heads
" in *"
$name
"*) continue ;; esac # has an open PR
  age=$((now - epoch)); [ "$age" -ge 0 ] || age=0
  if [ "$age" -lt $((FRESH_DAYS * 86400)) ]; then fl=fresh; fresh=$(join "$fresh" "$name" ", "); else fl=stale; fi
  nbr=$((nbr + 1))
  t_br="$t_br
  $name  last commit $(fmt_age "$age") ago  ($fl)"
  j_br=$(join "$j_br" "{\"name\":\"$(js "$name")\",\"age_seconds\":$age,\"fresh\":$([ $fl = fresh ] && echo true || echo false)}" ",")
done <<EOF
$refs
EOF

# ---------- running pipelines (processes `pipe run|plan-loop|code-loop ...`) ----------
ours=$(cd "$(git rev-parse --git-common-dir)" && pwd -P)
if psout=$(ps -eo pid,etime,args 2>/dev/null) || psout=$(ps -o pid,etime,args 2>/dev/null); then E=1
elif psout=$(ps -o pid,args 2>/dev/null); then E=0
else die "cannot list processes (ps failed)"; fi
pipes="" seen="|" t_pp="" j_pp="" npp=0
while read -r pid rest; do
  case $pid in '' | *[!0-9]*) continue ;; esac
  et="" args=$rest
  if [ $E = 1 ]; then et=${rest%% *}; args=${rest#"$et"}; fi
  set -- $args
  # the pipe script is argv[0] or argv[1] (`bash /path/pipe ...`, `env ... pipe ...`): never text inside a bigger string
  i=0 found=""
  while [ $# -gt 0 ] && [ $i -lt 3 ]; do
    tok=$1; shift; i=$((i + 1))
    if [ "${tok##*/}" = pipe ]; then found=1; break; fi
    case ${tok##*/} in bash | sh | ash | dash | env | -*) ;; *=*) ;; *) break ;; esac
  done
  [ -n "$found" ] || continue
  sub=${1:-}; [ $# -gt 0 ] && shift
  case $sub in
    run) stage=run; while [ $# -gt 0 ]; do case $1 in -*) shift ;; *) break ;; esac; done; [ $# -gt 0 ] && { stage="run $1"; shift; } ;;
    plan-loop | code-loop) stage=$sub ;;
    *) continue ;;
  esac
  while [ $# -gt 0 ]; do case $1 in -*) shift ;; *) break ;; esac; done
  repo=${1:-} slug=${2:-}
  [ -n "$slug" ] || continue
  if [ -d "$repo" ]; then # a pipeline for some other repo does not hold up this release
    theirs=$(cd "$repo" && cd "$(git rev-parse --git-common-dir 2>/dev/null)" 2>/dev/null && pwd -P) || theirs=""
    [ -z "$theirs" ] || [ "$theirs" = "$ours" ] || continue
  fi
  key="$slug $stage"
  case $seen in *"|$key|"*) continue ;; esac # the same pipeline shows as several processes
  seen="$seen$key|"
  secs=0
  [ -z "$et" ] || secs=$(printf '%s' "$et" | awk -F'[-:]' '{n=NF;s=$n+60*$(n-1);if(n>=3)s+=3600*$(n-2);if(n>=4)s+=86400*$(n-3);print s+0}')
  case $stage in "run "* | run) typ=10 ;; plan-loop) typ=25 ;; *) typ=45 ;; esac # rough minutes a stage takes
  left=$((typ - secs / 60)); [ $left -ge 5 ] || left=5
  num=${slug%%-*}; case $num in '' | *[!0-9]*) num=$slug ;; esac
  pipes=$(join "$pipes" "pipeline $num is in $stage (~$left min)" ", ")
  npp=$((npp + 1))
  t_pp="$t_pp
  $slug  $stage  running $(fmt_age "$secs"), ~$left min left (rough)"
  j_pp=$(join "$j_pp" "{\"slug\":\"$(js "$slug")\",\"stage\":\"$(js "$stage")\",\"elapsed_seconds\":$secs,\"est_minutes_left\":$left}" ",")
done <<EOF
$psout
EOF

# ---------- other worktrees with uncommitted or unpushed work ----------
wts="" t_wt="" j_wt="" nwt=0 first=1 wpath="" wbr=""
check_wt() {
  [ -n "$wpath" ] || return 0
  if [ $first = 1 ]; then first=0; return 0; fi # the main checkout
  [ "$wpath" != "$here" ] || return 0 # the worktree this check runs in is the caller's own work
  [ -d "$wpath" ] || return 0
  dirty=""; [ -z "$(git -C "$wpath" status --porcelain 2>/dev/null)" ] || dirty=1
  base=refs/remotes/origin/main
  if [ -n "$wbr" ] && git rev-parse -q --verify "refs/remotes/origin/$wbr" >/dev/null; then base=refs/remotes/origin/$wbr; fi
  ahead=$(git -C "$wpath" rev-list --count "$base..HEAD" 2>/dev/null) || ahead=0
  [ -n "$dirty" ] || [ "${ahead:-0}" -gt 0 ] || return 0
  what=""
  [ -z "$dirty" ] || what="uncommitted changes"
  [ "$ahead" -le 0 ] || what=$(join "$what" "$ahead unpushed commit(s)" " + ")
  nwt=$((nwt + 1))
  wts=$(join "$wts" "worktree $(basename "$wpath") (${wbr:-detached}) has $what" ", ")
  t_wt="$t_wt
  $wpath  ${wbr:-detached}  $what"
  j_wt=$(join "$j_wt" "{\"path\":\"$(js "$wpath")\",\"branch\":\"$(js "$wbr")\",\"uncommitted\":$([ -n "$dirty" ] && echo true || echo false),\"unpushed_commits\":$ahead}" ",")
}
wlist=$(git worktree list --porcelain) || die "git worktree list failed"
while IFS= read -r line; do
  case $line in
    "worktree "*) check_wt; wpath=${line#worktree }; wbr="" ;;
    "branch "*) wbr=${line#branch refs/heads/} ;;
    "") check_wt; wpath="" ;;
  esac
done <<EOF
$wlist
EOF
check_wt

# ---------- official Buddy ----------
base_ver=$(git show "origin/main:$BI" 2>/dev/null | sed -n 's/.*VERSION = "\(.*\)";.*/\1/p')
[ -n "$base_ver" ] || die "no VERSION in origin/main:$BI"
base=${base_ver%-fork.*}
up_tag=$(gh release view -R "$UPSTREAM" --json tagName --jq .tagName) || die "cannot read $UPSTREAM's latest release"
[ -n "$up_tag" ] || die "$UPSTREAM has no release"
up_newer=0; vgt "${up_tag#v}" "$base" && up_newer=1

# ---------- recommendation ----------
actions=""
if [ -n "$ready" ]; then
  if [ $ready_n -gt 1 ]; then actions="merge PRs $ready"; else actions="merge PR $ready"; fi
fi
if [ $up_newer = 1 ] && [ -z "$sync_pr_ready" ]; then actions=$(join "$actions" "bring in upstream $up_tag" ", "); fi
waits=""
[ -z "$pipes" ] || waits=$(join "$waits" "$pipes" "; ")
[ -z "$wts" ] || waits=$(join "$waits" "$wts" "; ")
[ -z "$pend" ] || waits=$(join "$waits" "CI still pending on $pend" "; ")
[ -z "$fresh" ] || waits=$(join "$waits" "branch $fresh has no PR yet" "; ")
if [ -n "$waits" ]; then
  if [ -n "$actions" ]; then rec="wait: $waits; then $actions, then release once"; else rec="wait: $waits; release after it"; fi
  code=10
elif [ -n "$actions" ]; then rec="$actions, then release once"; code=10
else rec="ready: release now"; code=0
fi
[ -z "$excl" ] || rec="$rec (excluded: $excl)"

# ---------- output ----------
if [ $json = 1 ]; then
  printf '{"exit":%s,"ready":%s,"recommendation":"%s","main":"%s","fork_version":"%s","upstream":{"latest":"%s","base":"%s","newer":%s},' \
    "$code" "$([ $code = 0 ] && echo true || echo false)" "$(js "$rec")" "$main7" "$(js "$base_ver")" "$(js "$up_tag")" "$(js "$base")" \
    "$([ $up_newer = 1 ] && echo true || echo false)"
  printf '"prs":[%s],"branches":[%s],"pipelines":[%s],"worktrees":[%s]}\n' "$j_prs" "$j_br" "$j_pp" "$j_wt"
elif [ $quiet = 1 ]; then
  echo "RECOMMENDATION: $rec"
else
  echo "Release check for $FORK (origin/main $main7, version $base_ver)"
  echo
  echo "Open PRs ($npr):${t_prs:-  none}"
  echo
  echo "Branches feat/* fix/* not merged, no open PR ($nbr; fresh = newer than ${FRESH_DAYS}d):${t_br:-  none}"
  echo
  echo "Running pipelines on this phone ($npp):${t_pp:-  none}"
  echo
  echo "Other worktrees with uncommitted or unpushed work ($nwt):${t_wt:-  none}"
  echo
  if [ $up_newer = 1 ]; then echo "Official Buddy: latest $up_tag is NEWER than the fork's base $base"
  else echo "Official Buddy: latest $up_tag, fork base $base: up to date"; fi
  echo
  echo "RECOMMENDATION: $rec"
fi
exit $code
