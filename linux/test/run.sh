#!/usr/bin/env bash
# The helper's translation of each agent's output, tested on real recorded runs: every fixtures/<agent>/<case>_<cli
# version>.jsonl goes through buddy-run's stream_<agent>, and its events must match <case>_<cli version>.expected.jsonl
# (compared as sorted JSON). When an agent update changes its output, add the new version's recording next to the old
# one rather than replacing it, as long as Buddy supports both.
# Then the runner itself, on a stand-in agent that dies in different ways: fixtures/run/<case>.expected.jsonl is what the
# app must get (a run always ends with buddy_done, and with a buddy_error when the agent failed).
#   run.sh            run every fixture and case
#   run.sh --update   rewrite the expected files from the current helper (check the diff before committing)
set -u
cd "$(dirname "$0")" || exit 2
H=$(mktemp -d)
trap 'rm -rf "$H"' EXIT
# buddy-run sets its own PATH (Alpine's), which has $HOME/bin first: the tools it uses go there, wherever they are here
mkdir -p "$H/bin"
for t in jq cat sed sort paste find rm mv mkdir tail head grep awk pgrep wc tee ln basename readlink env timeout sleep; do
  p=$(command -v "$t") && ln -s "$p" "$H/bin/$t"
done
# Stand-ins for the run cases: a curl that keeps the events buddy-run posts (no listener, so the app's own port 8765 is
# left alone), and one agent under all five names that dies the way $BUDDY_FAKE says.
export EVENTS=$H/events BUDDY_FAKE_DIR=$H
cat >"$H/bin/curl" <<'EOF'
#!/bin/bash
for a; do last=$a; done
case $last in */event) { cat; echo; } >>"$EVENTS" ;; esac
EOF
cat >"$H/agent" <<'EOF'
#!/bin/bash
case ${1:-} in inspect|--version) exit 0 ;; esac
echo $$ >>"$BUDDY_FAKE_DIR/pids"
echo '{"type":"system","session_id":"abc"}'
bg() { sleep 60 & echo $! >>"$BUDDY_FAKE_DIR/pids"; }
case $BUDDY_FAKE in
  orphan) echo '{"type":"result","subtype":"success","result":"ok"}'; bg ;;
  killed) bg; kill -9 $$ ;;
  exit3) exit 3 ;;
  error) echo '{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Rate limited"}'; exit 1 ;;
  agentterm) kill -TERM $$ ;;
  producer) bg; kill -9 $PPID; sleep 60 ;;
  hang) exec sleep 60 ;;
esac
EOF
chmod +x "$H/bin/curl" "$H/agent"
for a in claude codex opencode agy grok; do ln -s "$H/agent" "$H/bin/$a"; done

update=${1:-}
n=0 failed=0
# check <label> <expected file> <events> [problem]: a problem (a timeout, a process left behind) fails the case by itself
check() {
  local label=$1 exp=$2 got=$3 problem=${4:-}
  n=$((n + 1))
  : >"$H/diff"
  if [ "$update" = --update ]; then
    printf '%s\n' "$got" >"$exp"
    echo "updated $exp"
  elif [ ! -f "$exp" ]; then
    echo "MISSING $exp (run.sh --update writes it)"
    failed=$((failed + 1))
  elif ! diff -u <(jq -S -c . "$exp") <(printf '%s\n' "$got") >"$H/diff" || [ -n "$problem" ]; then
    echo "FAIL $label${problem:+ ($problem)}"
    cat "$H/diff"
    failed=$((failed + 1))
  else
    echo "ok   $label"
  fi
}

for f in fixtures/*/*.jsonl; do
  case $f in *.expected.jsonl) continue ;; esac
  agent=$(basename "$(dirname "$f")")
  got=$(HOME=$H BUDDY_TEST_STREAM=$agent bash ../buddy-run test ask fixture "$agent" 0 x <"$f" | jq -S -c .)
  rm -rf "$H/.buddy" "$H/.codex"
  check "$f" "${f%.jsonl}.expected.jsonl" "$got"
done

# The run cases. Processes are killed by pid, never with pkill -f: that also matches the live Buddy helpers on a phone.
mkdir -p fixtures/run
P=$H/.buddy/sessions/run/pid
finish() { # <case> [problem]: compare the events, then kill what the agent left and clear the home
  [ -s "$H/pids" ] && kill -9 $(cat "$H/pids") 2>/dev/null
  check "run/$1" "fixtures/run/$1.expected.jsonl" "$(jq -S -c . "$H/events" 2>/dev/null)" "${2:-}"
  rm -rf "$H/.buddy" "$H/.claude" "$H/.gemini" "$H/.grok" "$H/.codex" "$H/.local" "$H/.agents" "$H/events" "$H/pids"
}
run_case() { # <case> <fake> <backend>
  HOME=$H BUDDY_FAKE=$2 timeout 20 bash ../buddy-run test ask run "$3" 0 hi >"$H/out" 2>&1
  local rc=$?
  finish "$1" "$([ "$rc" = 124 ] && echo "timed out")"
}
gone() { # <pid> <tenths of a second>: wait for it to end
  local i
  for i in $(seq "$2"); do kill -0 "$1" 2>/dev/null || return 0; sleep 0.1; done
  return 1
}
start_hang() { # a wrapper in the background on an agent that waits; ready when the agent has started
  HOME=$H BUDDY_FAKE=hang bash ../buddy-run test ask run claude 0 hi >"$H/out" 2>&1 &
  w=$!
  local i
  for i in $(seq 100); do
    grep -qs '"system"' "$H/events" && [ -s "$P" ] && return 0
    sleep 0.1
  done
  return 1
}

run_case claude_orphan orphan claude
for b in claude codex opencode agy grok; do run_case "${b}_killed" killed $b; done
run_case claude_exit3 exit3 claude
run_case claude_error error claude
run_case claude_agentterm agentterm claude
run_case claude_producer producer claude

# TERM to the wrapper: it stops the agent, reports it was cut off, and says done
start_hang || echo "the stand-in agent did not start"
kill -TERM "$w" 2>/dev/null
problem=""
gone "$w" 100 || { problem="the wrapper did not end"; kill -9 "$w" 2>/dev/null; }
gone "$(head -1 "$H/pids" 2>/dev/null)" 20 || problem="the agent is still running"
finish claude_term "$problem"

# the run section killed (the process in the session's pid file): the wrapper still reports and says done
start_hang || echo "the stand-in agent did not start"
kill -KILL "$(cat "$P")" 2>/dev/null
problem=""
gone "$w" 100 || { problem="the wrapper did not end"; kill -9 "$w" 2>/dev/null; }
finish claude_runkill "$problem"

# a session that is still working: the new request is refused with an error, and no done (the run in progress owns it)
mkdir -p "${P%/pid}"
(sleep 60 >/dev/null 2>&1 & echo $! >"$P")
busy=$(cat "$P")
problem=""
HOME=$H BUDDY_FAKE=orphan timeout 5 bash ../buddy-run test ask run claude 0 hi >"$H/out" 2>&1
[ $? = 124 ] && problem="timed out"
kill -9 "$busy" 2>/dev/null
finish claude_busy "$problem"

echo "$n fixtures and cases, $failed failed"
[ "$n" -gt 0 ] && [ "$failed" -eq 0 ]
