#!/usr/bin/env sh
set -eu
umask 077

if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
  echo "usage: $0 <agent-read|public-request|customer-auth-limiter|collaboration-websocket> <absolute-env-file> <absolute-results-directory> [absolute-fixture-directory]" >&2
  exit 2
fi
scenario=$1
environment_file=$2
results_directory=$3
fixture_directory=${4:-}
case "$scenario" in
  agent-read|public-request|customer-auth-limiter|collaboration-websocket) ;;
  *) echo "unsupported scenario" >&2; exit 2 ;;
esac
case "$environment_file:$results_directory" in
  /*:/*) ;;
  *) echo "env file and results directory must be absolute paths" >&2; exit 2 ;;
esac
if [ -n "$fixture_directory" ]; then
  case "$fixture_directory" in /*) ;; *) echo "fixture directory must be absolute" >&2; exit 2 ;; esac
  test -d "$fixture_directory"
  if [ "$scenario" = agent-read ]; then
    test -f "$fixture_directory/search-corpus.json"
  fi
fi
test -f "$environment_file"
python3 - "$environment_file" "$fixture_directory" <<'PY'
import pathlib,stat,sys
files=[pathlib.Path(sys.argv[1])]
if sys.argv[2]:
    files.extend(p for p in pathlib.Path(sys.argv[2]).glob('*.json'))
if any(stat.S_IMODE(p.stat().st_mode) & 0o077 for p in files):
    raise SystemExit('Environment and fixture JSON files must not be accessible to group/others (use chmod 600)')
PY
mkdir -p "$results_directory"
run_directory=$(mktemp -d "$results_directory/${scenario}-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
script_revision=$(git -C "$repository_root" rev-parse HEAD)
python3 - "$repository_root" "$run_directory" "$scenario" "$fixture_directory" <<'PY'
import datetime,hashlib,json,pathlib,subprocess,sys
root,out,scenario,fixture=pathlib.Path(sys.argv[1]),pathlib.Path(sys.argv[2]),sys.argv[3],sys.argv[4]
files=list((root/'tests/load').rglob('*.js'))+list((root/'scripts/load').glob('*.py'))+[root/'scripts/load/run-k6.sh']
record={'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scenario':scenario,'image':'grafana/k6:2.0.0',
        'revision':subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip(),
        'scriptSha256':{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
corpus=pathlib.Path(fixture)/'search-corpus.json' if fixture else None
if corpus and corpus.is_file():record['corpusSha256']=hashlib.sha256(corpus.read_bytes()).hexdigest()
(out/'runner-manifest.json').write_text(json.dumps(record,indent=2)+'\n')
PY
set -- docker run --rm \
  --user "$(id -u):$(id -g)" \
  --env-file "$environment_file" \
  -e 'K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99),max' \
  -e K6_PROMETHEUS_RW_STALE_MARKERS=true \
  -e K6_NO_USAGE_REPORT=true \
  -e RESULTS_DIRECTORY=/results \
  -e "LOAD_SCRIPT_REVISION=$script_revision" \
  -v "$repository_root/tests/load:/scripts:ro" \
  -v "$run_directory:/results"
if [ -n "$fixture_directory" ]; then
  set -- "$@" -v "$fixture_directory:/fixtures:ro"
  if [ "$scenario" = agent-read ]; then
    set -- "$@" -e STAFF_SEARCH_CORPUS=/fixtures/search-corpus.json
  fi
  if [ -f "$fixture_directory/staff-accounts.json" ]; then
    set -- "$@" -e STAFF_ACCOUNTS_FILE=/fixtures/staff-accounts.json
  fi
fi
set -- "$@" grafana/k6:2.0.0 run \
  --out experimental-prometheus-rw \
  --summary-export "/results/${scenario}-raw-summary.json" \
  "/scripts/scenarios/${scenario}.js"
status=0
"$@" || status=$?
printf '%s\n' "$status" > "$run_directory/exit-code.txt"
python3 - "$run_directory/runner-manifest.json" "$status" <<'PY'
import datetime,json,pathlib,sys
path=pathlib.Path(sys.argv[1]);record=json.loads(path.read_text())
record.update(finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),exitCode=int(sys.argv[2]))
path.write_text(json.dumps(record,indent=2)+'\n')
PY
printf 'Load evidence: %s\n' "$run_directory"
exit "$status"
