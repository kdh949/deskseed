#!/usr/bin/env sh
set -eu

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <agent-read|public-request|customer-auth-limiter|collaboration-websocket|mixed-workload> <absolute-env-file> <absolute-results-directory>" >&2
  exit 2
fi

scenario=$1
environment_file=$2
results_directory=$3
case "$scenario" in
  agent-read|public-request|customer-auth-limiter|collaboration-websocket|mixed-workload) ;;
  *) echo "unsupported scenario: $scenario" >&2; exit 2 ;;
esac
case "$environment_file:$results_directory" in
  /*:/*) ;;
  *) echo "env file and results directory must be absolute paths" >&2; exit 2 ;;
esac
test -f "$environment_file"
mkdir -p "$results_directory"

# Read only the run identifier as data; never source an env file containing secrets.
requested_run_id=$(awk -F= '$1 == "TEST_RUN_ID" { value = substr($0, index($0, "=") + 1) } END { printf "%s", value }' "$environment_file" | tr -d '\r')
if [ -z "$requested_run_id" ]; then
  echo "TEST_RUN_ID must be set in the env file" >&2
  exit 2
fi
run_id=$(printf '%s' "$requested_run_id" | LC_ALL=C tr -c 'A-Za-z0-9._-' '-' | cut -c 1-80)
runner_result="$results_directory/$run_id-$scenario-runner.json"
for suffix in runner summary manifest; do
  if [ -e "$results_directory/$run_id-$scenario-$suffix.json" ]; then
    echo "Run evidence already exists; choose a new TEST_RUN_ID" >&2
    exit 2
  fi
done
# Reserve the filename without clobbering a concurrent run.
if ! (set -C; : > "$runner_result") 2>/dev/null; then
  echo "Run evidence already exists; choose a new TEST_RUN_ID" >&2
  exit 2
fi
started_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
record_exit() {
  runner_exit_code=$?
  trap - EXIT
  completed_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  printf '{"testRunId":"%s","scenario":"%s","startedAt":"%s","completedAt":"%s","exitCode":%s}\n' \
    "$run_id" "$scenario" "$started_at" "$completed_at" "$runner_exit_code" > "$runner_result"
  exit "$runner_exit_code"
}
trap record_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
source_commit=$(git -C "$repository_root" rev-parse HEAD)
source_dirty=false
if [ -n "$(git -C "$repository_root" status --porcelain --untracked-files=normal)" ]; then
  source_dirty=true
fi

docker run --rm \
  --env-file "$environment_file" \
  -e TEST_RUN_ID="$run_id" \
  -e DESKSEED_GIT_SHA="$source_commit" \
  -e DESKSEED_GIT_DIRTY="$source_dirty" \
  -e 'K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99),max' \
  -e K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=false \
  -e K6_PROMETHEUS_RW_STALE_MARKERS=true \
  -v "$repository_root/tests/load:/scripts:ro" \
  -v "$results_directory:/results" \
  grafana/k6:2.0.0 run \
  --out experimental-prometheus-rw \
  "/scripts/scenarios/${scenario}.js"
