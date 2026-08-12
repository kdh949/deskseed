#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_case="${1:-all}"
set --
# shellcheck source=../run-operations-rehearsal.sh
source "$repository_root/scripts/run-operations-rehearsal.sh"

case "$test_case" in
  all|override-injection|descendant-pgid|daemon-swap|client-shadow|actual-smoke) ;;
  *)
    printf 'Usage: %s [all|override-injection|descendant-pgid|daemon-swap|client-shadow|actual-smoke]\n' "$0" >&2
    exit 2
    ;;
esac

if [[ "$test_case" == actual-smoke ]]; then
  actual_test_root="$(mktemp -d "${TMPDIR:-/tmp}/deskseed-operations.XXXXXX")"
  work_dir="$actual_test_root"
  redaction_values_file="$work_dir/redaction-values"
  : >"$redaction_values_file"
  chmod 600 "$redaction_values_file"
  actual_image_tag=""
  actual_image_id=""
  actual_marker=""

  cleanup_actual_smoke() {
    local exit_code=$?
    local current_id=""
    local current_owner=""
    trap - EXIT
    set +e
    if [[ -n "$docker_cli_path" && -n "$actual_image_tag" ]] \
      && current_id="$("$docker_cli_path" image inspect --format '{{.Id}}' "$actual_image_tag" 2>/dev/null)" \
      && current_owner="$("$docker_cli_path" image inspect \
        --format '{{index .Config.Labels "dev.deskseed.operations.boundary-smoke"}}' \
        "$current_id" 2>/dev/null)" \
      && [[ "$current_id" == "$actual_image_id" && "$current_owner" == "$actual_marker" ]]; then
      "$docker_cli_path" image rm "$current_id" >/dev/null 2>&1
    fi
    case "$actual_test_root" in
      "${TMPDIR:-/tmp}"/deskseed-operations.??????) rm -rf -- "$actual_test_root" ;;
      *) printf 'Refusing unexpected actual-smoke cleanup path: %s\n' "$actual_test_root" >&2; exit_code=1 ;;
    esac
    exit "$exit_code"
  }
  trap cleanup_actual_smoke EXIT

  capture_and_clear_docker_remote_overrides
  capture_docker_client_boundary
  initialize_anonymous_docker_client
  run_bounded_checked "actual anonymous PostgreSQL pull" 120 5 \
    "$docker_cli_path" pull postgres:17-alpine

  actual_marker="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
  actual_image_tag="deskseed-operations-boundary-smoke:$actual_marker"
  mkdir -m 700 "$work_dir/scratch"
  printf 'FROM scratch\nLABEL dev.deskseed.operations.boundary-smoke="%s"\n' \
    "$actual_marker" >"$work_dir/scratch/Dockerfile"
  run_bounded_checked "actual anonymous scratch build" 120 5 \
    "$docker_cli_path" build --tag "$actual_image_tag" "$work_dir/scratch"
  actual_image_id="$("$docker_cli_path" image inspect --format '{{.Id}}' "$actual_image_tag")"
  [[ -n "$actual_image_id" ]]
  [[ "$("$docker_cli_path" image inspect \
    --format '{{index .Config.Labels "dev.deskseed.operations.boundary-smoke"}}' \
    "$actual_image_id")" == "$actual_marker" ]]
  verify_anonymous_docker_client
  "$docker_cli_path" image rm "$actual_image_id" >/dev/null
  ! "$docker_cli_path" image inspect "$actual_image_id" >/dev/null 2>&1
  ! "$docker_cli_path" image inspect "$actual_image_tag" >/dev/null 2>&1
  actual_image_tag=""
  verify_user_docker_config_unchanged
  printf 'PASS: actual anonymous postgres pull and exact owned scratch-image build/removal completed within 120s bounds.\n'
  exit 0
fi

test_root="$(mktemp -d "${TMPDIR:-/tmp}/deskseed-operations.XXXXXX")"
work_dir="$test_root/work"
fake_bin="$test_root/bin"
fake_user_docker_config="$test_root/user-docker"
fake_socket="$test_root/docker.sock"
fake_docker_log="$test_root/docker.log"
fake_daemon_id_file="$test_root/daemon-id"
fake_pull_pid_file="$test_root/pull-helper.pid"
fake_pull_child_pid_file="$test_root/pull-helper-child.pid"
fake_build_pid_file="$test_root/build-helper.pid"
fake_build_child_pid_file="$test_root/build-helper-child.pid"
fake_leader_exit_pid_file="$test_root/leader-exit-helper.pid"
fake_leader_exit_child_pid_file="$test_root/leader-exit-helper-child.pid"
fake_leader_exit_log="$test_root/leader-exit.log"
socket_server_pid=""
mkdir -m 700 "$work_dir" "$fake_bin" "$fake_user_docker_config" \
  "$fake_user_docker_config/cli-plugins"
redaction_values_file="$work_dir/redaction-values"
: >"$redaction_values_file"
chmod 600 "$redaction_values_file"
printf 'fake-daemon-id\n' >"$fake_daemon_id_file"
: >"$fake_docker_log"

cleanup_test_fixture() {
  local exit_code=$?
  local pid
  local pid_file
  trap - EXIT
  set +e
  for pid_file in \
    "$fake_pull_pid_file" "$fake_pull_child_pid_file" \
    "$fake_build_pid_file" "$fake_build_child_pid_file" \
    "$fake_leader_exit_pid_file" "$fake_leader_exit_child_pid_file"; do
    if [[ -s "$pid_file" ]]; then
      pid="$(sed -n '1p' "$pid_file")"
      [[ "$pid" =~ ^[0-9]+$ ]] && kill -KILL "$pid" >/dev/null 2>&1
    fi
  done
  if [[ -n "$socket_server_pid" ]]; then
    kill "$socket_server_pid" >/dev/null 2>&1
    wait "$socket_server_pid" >/dev/null 2>&1
  fi
  case "$test_root" in
    "${TMPDIR:-/tmp}"/deskseed-operations.??????) rm -rf -- "$test_root" ;;
    *) printf 'Refusing unexpected test directory cleanup: %s\n' "$test_root" >&2; exit_code=1 ;;
  esac
  exit "$exit_code"
}
trap cleanup_test_fixture EXIT

start_fake_socket_server() {
  python3 -u - "$fake_socket" <<'PY' &
import socket
import sys
import time

path = sys.argv[1]
server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(path)
server.listen(1)
while True:
    time.sleep(60)
PY
  socket_server_pid=$!
  for _ in $(seq 1 100); do
    [[ -S "$fake_socket" ]] && return 0
    sleep 0.01
  done
  return 1
}

replace_fake_socket_server() {
  kill "$socket_server_pid"
  wait "$socket_server_pid" >/dev/null 2>&1 || true
  socket_server_pid=""
  rm -f -- "$fake_socket"
  start_fake_socket_server
}

start_fake_socket_server

cat >"$fake_user_docker_config/config.json" <<'JSON'
{"auths":{},"credsStore":"hanging-helper"}
JSON
chmod 600 "$fake_user_docker_config/config.json"

for plugin in compose buildx; do
  cat >"$fake_user_docker_config/cli-plugins/docker-$plugin" <<EOF
#!/usr/bin/env bash
printf '%s test-plugin\n' '$plugin'
EOF
  chmod 700 "$fake_user_docker_config/cli-plugins/docker-$plugin"
done

cat >"$fake_bin/docker-credential-hanging-helper" <<'SH'
#!/usr/bin/env bash
set -eu
kind="${DESKSEED_FAKE_HANG_KIND:?}"
pid_file="${DESKSEED_FAKE_HELPER_PID_FILE:?}"
child_pid_file="${DESKSEED_FAKE_HELPER_CHILD_PID_FILE:?}"
printf '%s\n' "$$" >"$pid_file"
if [[ "$kind" == leader-exits ]]; then
  trap 'exit 0' TERM
else
  trap '' TERM
fi
python3 -c 'import signal,time; signal.signal(signal.SIGHUP, signal.SIG_IGN); signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(300)' &
child=$!
printf '%s\n' "$child" >"$child_pid_file"
wait "$child"
SH
chmod 700 "$fake_bin/docker-credential-hanging-helper"

cat >"$fake_bin/docker" <<'SH'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >>"${DESKSEED_FAKE_DOCKER_LOG:?}"
case "${1:-}" in
  context)
    case "${2:-}" in
      show) printf 'test-local\n' ;;
      inspect) printf 'unix://%s\n' "${DESKSEED_FAKE_DOCKER_SOCKET:?}" ;;
      *) exit 2 ;;
    esac
    ;;
  version)
    printf '29.6.2\n'
    ;;
  info)
    if [[ "${2:-}" == --format ]]; then
      sed -n '1p' "${DESKSEED_FAKE_DAEMON_ID_FILE:?}"
    else
      printf 'Fake Docker daemon\n'
    fi
    ;;
  compose)
    [[ -x "${DOCKER_CONFIG:-}/cli-plugins/docker-compose" || -x "${DESKSEED_FAKE_USER_DOCKER_CONFIG:?}/cli-plugins/docker-compose" ]]
    printf '5.3.1\n'
    ;;
  buildx)
    [[ -x "${DOCKER_CONFIG:-}/cli-plugins/docker-buildx" || -x "${DESKSEED_FAKE_USER_DOCKER_CONFIG:?}/cli-plugins/docker-buildx" ]]
    printf 'buildx test\n'
    ;;
  pull|build)
    for cleared_name in \
      DOCKER_AUTH_CONFIG BUILDKIT_HOST BUILDX_BUILDER BUILDX_CONFIG \
      DOCKER_API_VERSION DOCKER_CUSTOM_HEADERS DOCKER_DEFAULT_PLATFORM \
      DOCKER_CLI_PLUGIN_SOCKET DOCKER_CLI_PLUGIN_USE_DIAL_STDIO DOCKER_CLI_HOOKS \
      DOCKER_CLI_OTEL_EXPORTER_OTLP_ENDPOINT \
      DOCKER_CONTENT_TRUST DOCKER_CONTENT_TRUST_SERVER \
      DOCKER_CONTEXT DOCKER_TLS_VERIFY DOCKER_CERT_PATH DOCKER_TLS \
      BUILDX_DEFAULT_POLICY EXPERIMENTAL_BUILDKIT_SOURCE_POLICY BUILDKIT_SOURCE_POLICY \
      COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES COMPOSE_ENV_FILES COMPOSE_PATH_SEPARATOR \
      OTEL_EXPORTER_OTLP_ENDPOINT OTEL_EXPORTER_OTLP_HEADERS OTEL_EXPORTER_OTLP_PROTOCOL \
      OTEL_EXPORTER_OTLP_TRACES_ENDPOINT OTEL_EXPORTER_OTLP_TRACES_HEADERS OTEL_EXPORTER_OTLP_TRACES_PROTOCOL \
      OTEL_EXPORTER_OTLP_METRICS_ENDPOINT OTEL_EXPORTER_OTLP_METRICS_HEADERS OTEL_EXPORTER_OTLP_METRICS_PROTOCOL \
      OTEL_EXPORTER_OTLP_LOGS_ENDPOINT OTEL_EXPORTER_OTLP_LOGS_HEADERS OTEL_EXPORTER_OTLP_LOGS_PROTOCOL \
      OTEL_TRACES_EXPORTER OTEL_METRICS_EXPORTER OTEL_LOGS_EXPORTER; do
      [[ "${!cleared_name+x}" != x ]]
    done
    [[ "${DOCKER_BUILDKIT:-}" == 1 ]]
    [[ "${COMPOSE_DOCKER_CLI_BUILD:-}" == 1 ]]
    [[ "${COMPOSE_DISABLE_ENV_FILE:-}" == 1 ]]
    [[ "${OTEL_SDK_DISABLED:-}" == true ]]
    [[ "${HTTP_PROXY:-}" == "${DESKSEED_EXPECTED_HTTP_PROXY:-}" ]]
    [[ "${https_proxy:-}" == "${DESKSEED_EXPECTED_HTTPS_PROXY:-}" ]]
    exec docker-credential-hanging-helper get
    ;;
  ps)
    ;;
  container|network|volume|image)
    case "${2:-}" in
      ls) ;;
      inspect) exit 1 ;;
      *) exit 2 ;;
    esac
    ;;
  *)
    exit 2
    ;;
esac
SH
chmod 700 "$fake_bin/docker"

export PATH="$fake_bin:$PATH"
export DOCKER_CONFIG="$fake_user_docker_config"
export DESKSEED_FAKE_DOCKER_SOCKET="$fake_socket"
export DESKSEED_FAKE_USER_DOCKER_CONFIG="$fake_user_docker_config"
export DESKSEED_FAKE_DOCKER_LOG="$fake_docker_log"
export DESKSEED_FAKE_DAEMON_ID_FILE="$fake_daemon_id_file"

if [[ "$test_case" == all || "$test_case" == override-injection ]]; then
  export DOCKER_AUTH_CONFIG='{"auths":{"registry.example":{"auth":"test-only-secret"}}}'
  export BUILDKIT_HOST='tcp://remote-builder.invalid:1234'
  export BUILDX_BUILDER='remote-builder-test'
  export BUILDX_CONFIG="$test_root/remote-buildx-config"
  export DOCKER_API_VERSION='1.24'
  export DOCKER_CUSTOM_HEADERS='X-Test-Authorization=test-only-header-secret'
  export DOCKER_DEFAULT_PLATFORM='linux/arm64'
  export DOCKER_CLI_PLUGIN_SOCKET="$test_root/plugin.sock"
  export DOCKER_CLI_PLUGIN_USE_DIAL_STDIO=1
  export DOCKER_CLI_HOOKS="$test_root/hooks"
  export DOCKER_CLI_OTEL_EXPORTER_OTLP_ENDPOINT='https://docker-telemetry.invalid/v1/traces'
  export DOCKER_CONTENT_TRUST=1
  export DOCKER_CONTENT_TRUST_SERVER='https://trust.invalid/test'
  export DOCKER_CONTEXT='test-local'
  export DOCKER_TLS_VERIFY=1
  export DOCKER_CERT_PATH="$test_root/certs"
  export DOCKER_TLS=1
  export BUILDX_DEFAULT_POLICY="$test_root/default-policy.json"
  export EXPERIMENTAL_BUILDKIT_SOURCE_POLICY="$test_root/experimental-policy.json"
  export BUILDKIT_SOURCE_POLICY="$test_root/source-policy.json"
  export COMPOSE_FILE="$test_root/untrusted-compose.yaml"
  export COMPOSE_PROJECT_NAME='untrusted-project'
  export COMPOSE_PROFILES='untrusted-profile'
  export COMPOSE_ENV_FILES="$test_root/untrusted.env"
  export COMPOSE_PATH_SEPARATOR=';'
  export DOCKER_BUILDKIT=0
  export COMPOSE_DOCKER_CLI_BUILD=0
  export COMPOSE_DISABLE_ENV_FILE=0
  export OTEL_EXPORTER_OTLP_ENDPOINT='https://telemetry.invalid'
  export OTEL_EXPORTER_OTLP_HEADERS='authorization=otel-header-secret'
  export OTEL_EXPORTER_OTLP_PROTOCOL='http/protobuf'
  export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT='https://telemetry.invalid/v1/traces'
  export OTEL_EXPORTER_OTLP_TRACES_HEADERS='authorization=otel-trace-secret'
  export OTEL_EXPORTER_OTLP_TRACES_PROTOCOL='http/protobuf'
  export OTEL_EXPORTER_OTLP_METRICS_ENDPOINT='https://telemetry.invalid/v1/metrics'
  export OTEL_EXPORTER_OTLP_METRICS_HEADERS='authorization=otel-metric-secret'
  export OTEL_EXPORTER_OTLP_METRICS_PROTOCOL='http/protobuf'
  export OTEL_EXPORTER_OTLP_LOGS_ENDPOINT='https://telemetry.invalid/v1/logs'
  export OTEL_EXPORTER_OTLP_LOGS_HEADERS='authorization=otel-log-secret'
  export OTEL_EXPORTER_OTLP_LOGS_PROTOCOL='http/protobuf'
  export OTEL_TRACES_EXPORTER='otlp'
  export OTEL_METRICS_EXPORTER='otlp'
  export OTEL_LOGS_EXPORTER='otlp'
  export OTEL_SDK_DISABLED=false
  export HTTP_PROXY='http://proxy-user:proxy-password-test@proxy.invalid:8080'
  export https_proxy='http://lower-user:lower-proxy-password-test@proxy.invalid:8081'
  export DESKSEED_EXPECTED_HTTP_PROXY="$HTTP_PROXY"
  export DESKSEED_EXPECTED_HTTPS_PROXY="$https_proxy"
  export DESKSEED_EXPECTED_AUTH_CONFIG="$DOCKER_AUTH_CONFIG"
fi

if declare -F capture_and_clear_docker_remote_overrides >/dev/null 2>&1; then
  capture_and_clear_docker_remote_overrides
elif [[ "$test_case" == all || "$test_case" == override-injection ]]; then
  printf 'Missing Docker remote-override boundary implementation.\n' >&2
  exit 1
fi

capture_docker_client_boundary
before_fingerprint="$docker_user_config_fingerprint_before"
initialize_anonymous_docker_client

[[ "$DOCKER_HOST" == "unix://$fake_socket" ]]
[[ "$DOCKER_CONFIG" == "$docker_client_config_directory" ]]
[[ "$docker_cli_path" == "$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$fake_bin/docker")" ]]
[[ "$(sha256_file "$docker_cli_path")" == "$docker_cli_fingerprint" ]]
[[ "$(stat -f '%Lp' "$docker_client_config_file" 2>/dev/null || stat -c '%a' "$docker_client_config_file")" == 600 ]]
python3 - "$docker_client_config_file" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
assert payload == {"auths": {}}
PY
[[ -x "$DOCKER_CONFIG/cli-plugins/docker-compose" ]]
[[ -x "$DOCKER_CONFIG/cli-plugins/docker-buildx" ]]
verify_user_docker_config_unchanged
[[ "$before_fingerprint" == "$docker_user_config_fingerprint_before" ]]

if [[ "$test_case" == all || "$test_case" == client-shadow ]]; then
  docker() {
    printf 'function-shadow-invoked\n' >>"$fake_docker_log"
    "$docker_cli_path" "$@"
  }
  if verify_anonymous_docker_client; then
    printf 'Anonymous client accepted a Docker shell-function shadow.\n' >&2
    exit 1
  fi
  unset -f docker
  verify_anonymous_docker_client

  shadow_bin="$test_root/shadow-bin"
  mkdir -m 700 "$shadow_bin"
  {
    printf '#!/usr/bin/env bash\n'
    printf 'printf "PATH-shadow-invoked\\n" >>%q\n' "$fake_docker_log"
    printf 'exec %q "$@"\n' "$docker_cli_path"
  } >"$shadow_bin/docker"
  chmod 700 "$shadow_bin/docker"
  original_path="$PATH"
  PATH="$shadow_bin:$PATH"
  export PATH
  if verify_anonymous_docker_client; then
    printf 'Anonymous client accepted a mid-run Docker PATH shadow.\n' >&2
    exit 1
  fi
  ! rg -F 'PATH-shadow-invoked' "$fake_docker_log" >/dev/null
  PATH="$original_path"
  export PATH
  verify_anonymous_docker_client
  if [[ "$test_case" == client-shadow ]]; then
    printf 'PASS: Docker function and mid-run PATH shadows were rejected without invocation.\n'
    exit 0
  fi
fi

if [[ "$test_case" == all || "$test_case" == override-injection ]]; then
  for cleared_name in \
    DOCKER_AUTH_CONFIG BUILDKIT_HOST BUILDX_BUILDER BUILDX_CONFIG \
    DOCKER_API_VERSION DOCKER_CUSTOM_HEADERS DOCKER_DEFAULT_PLATFORM \
    DOCKER_CLI_PLUGIN_SOCKET DOCKER_CLI_PLUGIN_USE_DIAL_STDIO DOCKER_CLI_HOOKS \
    DOCKER_CLI_OTEL_EXPORTER_OTLP_ENDPOINT \
    DOCKER_CONTENT_TRUST DOCKER_CONTENT_TRUST_SERVER \
    DOCKER_CONTEXT DOCKER_TLS_VERIFY DOCKER_CERT_PATH DOCKER_TLS \
    BUILDX_DEFAULT_POLICY EXPERIMENTAL_BUILDKIT_SOURCE_POLICY BUILDKIT_SOURCE_POLICY \
    COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES COMPOSE_ENV_FILES COMPOSE_PATH_SEPARATOR \
    OTEL_EXPORTER_OTLP_ENDPOINT OTEL_EXPORTER_OTLP_HEADERS OTEL_EXPORTER_OTLP_PROTOCOL \
    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT OTEL_EXPORTER_OTLP_TRACES_HEADERS OTEL_EXPORTER_OTLP_TRACES_PROTOCOL \
    OTEL_EXPORTER_OTLP_METRICS_ENDPOINT OTEL_EXPORTER_OTLP_METRICS_HEADERS OTEL_EXPORTER_OTLP_METRICS_PROTOCOL \
    OTEL_EXPORTER_OTLP_LOGS_ENDPOINT OTEL_EXPORTER_OTLP_LOGS_HEADERS OTEL_EXPORTER_OTLP_LOGS_PROTOCOL \
    OTEL_TRACES_EXPORTER OTEL_METRICS_EXPORTER OTEL_LOGS_EXPORTER; do
    if [[ "${!cleared_name+x}" == x ]]; then
      printf 'Docker override survived anonymous initialization: %s\n' "$cleared_name" >&2
      exit 1
    fi
  done
  [[ "$DOCKER_BUILDKIT" == 1 ]]
  [[ "$COMPOSE_DOCKER_CLI_BUILD" == 1 ]]
  [[ "$COMPOSE_DISABLE_ENV_FILE" == 1 ]]
  [[ "$OTEL_SDK_DISABLED" == true ]]
  [[ "$HTTP_PROXY" == "$DESKSEED_EXPECTED_HTTP_PROXY" ]]
  [[ "$https_proxy" == "$DESKSEED_EXPECTED_HTTPS_PROXY" ]]
  rg -F 'test-only-secret' "$redaction_values_file" >/dev/null
  rg -F 'proxy-password-test' "$redaction_values_file" >/dev/null
  rg -F 'otel-header-secret' "$redaction_values_file" >/dev/null
  sanitized_probe="$test_root/sanitized-probe"
  printf '%s\n%s\n%s\n' \
    "$DESKSEED_EXPECTED_AUTH_CONFIG" "$HTTP_PROXY" 'authorization=otel-header-secret' | \
    sanitize_diagnostic_stream "$sanitized_probe" >/dev/null
  ! rg -F 'test-only-secret' "$sanitized_probe" >/dev/null
  ! rg -F 'proxy-password-test' "$sanitized_probe" >/dev/null
  ! rg -F 'otel-header-secret' "$sanitized_probe" >/dev/null
  if [[ "$test_case" == override-injection ]]; then
    printf 'PASS: injected auth/build overrides were redacted, cleared, and verified absent.\n'
    exit 0
  fi
fi

assert_process_gone() {
  local pid_file="$1"
  local pid
  [[ -s "$pid_file" ]]
  pid="$(sed -n '1p' "$pid_file")"
  [[ "$pid" =~ ^[0-9]+$ ]]
  for _ in $(seq 1 100); do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.02
  done
  printf 'Timed command left process alive: %s\n' "$pid" >&2
  return 1
}

if [[ "$test_case" != daemon-swap ]]; then
  export DESKSEED_FAKE_HANG_KIND=pull
  export DESKSEED_FAKE_HELPER_PID_FILE="$fake_pull_pid_file"
  export DESKSEED_FAKE_HELPER_CHILD_PID_FILE="$fake_pull_child_pid_file"
  if run_bounded_checked "fault-injected hanging Docker pull" 1 1 \
      "$docker_cli_path" pull public.example/test:latest; then
    printf 'Hanging Docker pull unexpectedly completed.\n' >&2
    exit 1
  else
    pull_status=$?
  fi
  [[ "$pull_status" -eq 124 ]]
  assert_process_gone "$fake_pull_pid_file"
  assert_process_gone "$fake_pull_child_pid_file"

  failure_command=""
  failure_exit_code=""
  failure_diagnostic_file=""
  export DESKSEED_FAKE_HANG_KIND=build
  export DESKSEED_FAKE_HELPER_PID_FILE="$fake_build_pid_file"
  export DESKSEED_FAKE_HELPER_CHILD_PID_FILE="$fake_build_child_pid_file"
  if run_bounded_checked "fault-injected hanging Docker build" 1 1 \
      "$docker_cli_path" build .; then
    printf 'Hanging Docker build unexpectedly completed.\n' >&2
    exit 1
  else
    build_status=$?
  fi
  [[ "$build_status" -eq 124 ]]
  assert_process_gone "$fake_build_pid_file"
  assert_process_gone "$fake_build_child_pid_file"

  failure_command=""
  failure_exit_code=""
  failure_diagnostic_file=""
  export DESKSEED_FAKE_HANG_KIND=leader-exits
  export DESKSEED_FAKE_HELPER_PID_FILE="$fake_leader_exit_pid_file"
  export DESKSEED_FAKE_HELPER_CHILD_PID_FILE="$fake_leader_exit_child_pid_file"
  if run_bounded_command 1 1 "$docker_cli_path" pull public.example/test:latest \
      >"$fake_leader_exit_log" 2>&1; then
    printf 'Leader-exit fault unexpectedly completed.\n' >&2
    exit 1
  else
    leader_exit_status=$?
  fi
  [[ "$leader_exit_status" -eq 124 ]]
  assert_process_gone "$fake_leader_exit_pid_file"
  assert_process_gone "$fake_leader_exit_child_pid_file"
  verify_user_docker_config_unchanged

  if [[ "$test_case" == descendant-pgid ]]; then
    printf 'PASS: leader-exit/TERM-ignoring descendant process group was terminated and verified absent.\n'
    exit 0
  fi
fi

docker_preflight_passed=true
resource_identity_initialized=true
run_marker="test-run-marker"
source_project="deskseed-test-source"
restore_project="deskseed-test-restore"
DESKSEED_REHEARSAL_BACKEND_IMAGE="deskseed-test-backend:test"
DESKSEED_REHEARSAL_FRONTEND_IMAGE="deskseed-test-frontend:test"
DESKSEED_REHEARSAL_POSTGRES_IMAGE="deskseed-test-postgres:test"
owned_container_records=()
owned_network_records=()
owned_volume_records=()
owned_image_ids=()
printf 'replacement-daemon-id\n' >"$fake_daemon_id_file"
docker_log_lines_before="$(wc -l <"$fake_docker_log" | tr -d ' ')"
if verify_anonymous_docker_client; then
  printf 'Anonymous client accepted a replaced daemon identity.\n' >&2
  exit 1
fi
docker_log_lines_after="$(wc -l <"$fake_docker_log" | tr -d ' ')"
[[ "$docker_log_lines_after" -eq $((docker_log_lines_before + 2)) ]]
daemon_probe_commands="$(sed -n "$((docker_log_lines_before + 1)),${docker_log_lines_after}p" "$fake_docker_log")"
[[ "$daemon_probe_commands" == version* ]]
[[ "$daemon_probe_commands" == *$'\n'info* ]]
[[ "$daemon_probe_commands" != *$'\n'ps* ]]
[[ "$daemon_probe_commands" != *$'\n'container* ]]
[[ "$daemon_probe_commands" != *$'\n'network* ]]
[[ "$daemon_probe_commands" != *$'\n'volume* ]]
[[ "$daemon_probe_commands" != *$'\n'image* ]]
printf 'fake-daemon-id\n' >"$fake_daemon_id_file"
verify_anonymous_docker_client
replace_fake_socket_server
printf 'replacement-daemon-id\n' >"$fake_daemon_id_file"
docker_log_lines_before="$(wc -l <"$fake_docker_log" | tr -d ' ')"
if cleanup_resources; then
  printf 'Cleanup accepted a replaced daemon socket/identity.\n' >&2
  exit 1
fi
docker_log_lines_after="$(wc -l <"$fake_docker_log" | tr -d ' ')"
[[ "$docker_log_lines_after" == "$docker_log_lines_before" ]]
[[ ! -e "$work_dir" ]]
verify_user_docker_config_unchanged

if [[ "$test_case" == daemon-swap ]]; then
  printf 'PASS: daemon/socket replacement failed closed before Docker discovery or removal.\n'
  exit 0
fi

printf 'PASS: anonymous mode-0600 Docker config preserved the validated local daemon endpoint and excluded credential helpers.\n'
printf 'PASS: Docker function and mid-run PATH shadows were rejected without invocation.\n'
printf 'PASS: injected auth/build overrides were redacted, cleared, and verified absent.\n'
printf 'PASS: hanging pull/build plus leader-exit descendant process trees were terminated at the bounded timeout.\n'
printf 'PASS: daemon/socket replacement failed closed before Docker discovery or removal.\n'
printf 'PASS: the preexisting user Docker config remained byte/metadata-identical.\n'
