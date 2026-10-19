#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

: "${RUN_ID:?Set a fresh numeric RUN_ID (1-4 digits)}"
PROFILE="${PROFILE:-5000}"
RESULT_ROOT="${RESULT_ROOT:-load-tests/results/m10c}"
STAMP="$(date -u +%Y-%m-%dT%H%M%SZ)"
RESULT_DIR="$RESULT_ROOT/${STAMP}-${PROFILE}-mixed-diagnostic-run-${RUN_ID}"
mkdir -p "$RESULT_DIR"

# No k6 (including setup) until the same diagnostic path has passed preflight.
if ! python3 -B scripts/m10c/collect.py --preflight \
  >"$RESULT_DIR/preflight.json" 2>"$RESULT_DIR/preflight.log"; then
  cat "$RESULT_DIR/preflight.log" >&2
  printf 'M10C preflight failed; k6 was not started. Evidence: %s\n' "$RESULT_DIR" >&2
  exit 1
fi
JMX_JAVA_HOME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["tomcat_jmx"]["jdk"]["java_home"])' "$RESULT_DIR/preflight.json")"

python3 -B scripts/m10c/collect.py \
  --java-home "$JMX_JAVA_HOME" \
  --output "$RESULT_DIR/runtime-samples.jsonl" \
  --interval "${DIAGNOSTIC_SAMPLE_INTERVAL:-5}" \
  >"$RESULT_DIR/collector.log" 2>&1 &
COLLECTOR_PID=$!
cleanup() {
  if [[ -n "${COLLECTOR_PID:-}" ]]; then
    kill "$COLLECTOR_PID" 2>/dev/null || true
    wait "$COLLECTOR_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

set +e
env \
  PROFILE="$PROFILE" \
  RUN_ID="$RUN_ID" \
  BASE_URL="${BASE_URL:-http://localhost:8080}" \
  RAMP_UP="${RAMP_UP:-5m}" \
  STEADY_DURATION="${STEADY_DURATION:-10m}" \
  RAMP_DOWN="${RAMP_DOWN:-1m}" \
  SETUP_TIMEOUT="${SETUP_TIMEOUT:-10m}" \
  HEARTBEAT_INTERVAL="${HEARTBEAT_INTERVAL:-15s}" \
  POLL_INTERVAL="${POLL_INTERVAL:-10s}" \
  DIAGNOSTIC_RESOLVE_MAX_ATTEMPTS="${DIAGNOSTIC_RESOLVE_MAX_ATTEMPTS:-3}" \
  DIAGNOSTIC_RESOLVE_BACKOFF="${DIAGNOSTIC_RESOLVE_BACKOFF:-5s}" \
  k6 run \
    --summary-export "$RESULT_DIR/k6-summary.json" \
    --out "json=$RESULT_DIR/k6-samples.json" \
    load-tests/k6/mixed-fleet-diagnostic.js \
    2>&1 | tee "$RESULT_DIR/k6-console.log"
K6_STATUS=${PIPESTATUS[0]}
set -e

if kill -0 "$COLLECTOR_PID" 2>/dev/null; then
  kill "$COLLECTOR_PID" 2>/dev/null || true
  set +e
  wait "$COLLECTOR_PID"
  COLLECTOR_STATUS=$?
  set -e
  printf 'stopped after workload; exit code %s\n' "$COLLECTOR_STATUS" \
    > "$RESULT_DIR/collector-status.txt"
else
  set +e
  wait "$COLLECTOR_PID"
  COLLECTOR_STATUS=$?
  set -e
  printf 'exited before workload completed; exit code %s\n' "$COLLECTOR_STATUS" \
    > "$RESULT_DIR/collector-status.txt"
fi
COLLECTOR_PID=''

printf '%s\n' "$K6_STATUS" > "$RESULT_DIR/k6-exit-code.txt"
git rev-parse HEAD > "$RESULT_DIR/git-sha.txt"
git status --porcelain --untracked-files=all > "$RESULT_DIR/git-status.txt"
printf 'Evidence: %s\nk6 exit code: %s\n' "$RESULT_DIR" "$K6_STATUS"
exit "$K6_STATUS"
