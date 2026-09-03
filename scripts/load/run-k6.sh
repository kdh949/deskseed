#!/usr/bin/env sh
set -eu

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <agent-read|public-request|customer-auth-limiter|collaboration-websocket> <absolute-env-file> <absolute-results-directory>" >&2
  exit 2
fi

scenario=$1
environment_file=$2
results_directory=$3
case "$scenario" in
  agent-read|public-request|customer-auth-limiter|collaboration-websocket) ;;
  *) echo "unsupported scenario: $scenario" >&2; exit 2 ;;
esac
case "$environment_file:$results_directory" in
  /*:/*) ;;
  *) echo "env file and results directory must be absolute paths" >&2; exit 2 ;;
esac
test -f "$environment_file"
mkdir -p "$results_directory"

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
source_commit=$(git -C "$repository_root" rev-parse HEAD)
source_dirty=false
if [ -n "$(git -C "$repository_root" status --porcelain --untracked-files=normal)" ]; then
  source_dirty=true
fi

docker run --rm \
  --env-file "$environment_file" \
  -e DESKSEED_GIT_SHA="$source_commit" \
  -e DESKSEED_GIT_DIRTY="$source_dirty" \
  -e 'K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99),max' \
  -e K6_PROMETHEUS_RW_STALE_MARKERS=true \
  -v "$repository_root/tests/load:/scripts:ro" \
  -v "$results_directory:/results" \
  grafana/k6:2.0.0 run \
  --out experimental-prometheus-rw \
  "/scripts/scenarios/${scenario}.js"
