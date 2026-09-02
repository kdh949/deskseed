#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="full"
evidence_file=""
evidence_directory=""
evidence_basename=""
evidence_target_prepared=false
start_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
current_stage="argument parsing"
event_rows=()
docker_server_version="NOT_CHECKED"
docker_compose_version="NOT_CHECKED"
docker_buildx_version="NOT_CHECKED"
docker_preflight_passed=false
docker_context_name="NOT_CHECKED"
docker_cli_path=""
docker_cli_fingerprint="NOT_CHECKED"
docker_daemon_endpoint=""
docker_daemon_endpoint_fingerprint="NOT_CHECKED"
docker_daemon_socket_path=""
docker_daemon_socket_identity_fingerprint="NOT_CHECKED"
docker_daemon_identity=""
docker_daemon_identity_fingerprint="NOT_CHECKED"
docker_original_config_directory=""
docker_user_config_file=""
docker_user_config_fingerprint_before="NOT_CHECKED"
docker_user_config_unchanged="NOT_VERIFIED"
docker_compose_plugin_path=""
docker_compose_plugin_fingerprint="NOT_CHECKED"
docker_buildx_plugin_path=""
docker_buildx_plugin_fingerprint="NOT_CHECKED"
docker_client_config_directory=""
docker_client_config_file=""
docker_client_config_fingerprint="NOT_CHECKED"
docker_client_boundary_initialized=false
docker_inherited_cleared_override_names="NONE"
docker_inherited_retained_proxy_names="NONE"
docker_retained_proxy_fingerprint_before="NOT_CHECKED"
docker_pull_timeout_seconds="${DESKSEED_OPERATIONS_PULL_TIMEOUT_SECONDS:-300}"
docker_build_timeout_seconds="${DESKSEED_OPERATIONS_BUILD_TIMEOUT_SECONDS:-2400}"
docker_termination_grace_seconds="${DESKSEED_OPERATIONS_TERMINATION_GRACE_SECONDS:-10}"
effective_uid="NOT_CHECKED"
effective_gid="NOT_CHECKED"
work_dir=""
admin_password_file=""
admin_cookie_file=""
restore_cookie_file=""
backup_file=""
redaction_values_file=""
failure_diagnostic_file=""
failure_command=""
failure_exit_code=""
failure_diagnostic=""
source_started=false
restore_started=false
resource_identity_initialized=false
resource_name_preflight_passed=false
backend_build_context_fingerprint=""
frontend_build_context_fingerprint=""
operations_input_fingerprint=""
ownership_overlay_fingerprint=""
postgres_wrapper_dockerfile_fingerprint=""

resource_owner_label_key="dev.deskseed.operations.run"
run_marker=""
run_id=""
image_tag=""
source_project=""
restore_project=""
ownership_overlay_file=""
postgres_wrapper_context=""
owned_container_records=()
owned_network_records=()
owned_volume_records=()
owned_image_ids=()
source_backend_port="${DESKSEED_OPERATIONS_BACKEND_PORT:-28080}"
source_frontend_port="${DESKSEED_OPERATIONS_FRONTEND_PORT:-25173}"
restore_backend_port="${DESKSEED_OPERATIONS_RESTORE_BACKEND_PORT:-28081}"
restore_frontend_port="${DESKSEED_OPERATIONS_RESTORE_FRONTEND_PORT:-25174}"
database_name="deskseed_rehearsal"
postgres_base_image="postgres:17-alpine"
bootstrap_role="deskseed_bootstrap"
migration_role="deskseed_migration"
runtime_role="deskseed_runtime"
bootstrap_password=""
migration_password=""
runtime_password=""
admin_password=""
audit_key=""
agent_cursor_key=""
audit_cursor_key=""
admin_email="operations-admin@deskseed.test"
DESKSEED_REHEARSAL_BACKEND_IMAGE=""
DESKSEED_REHEARSAL_FRONTEND_IMAGE=""
DESKSEED_REHEARSAL_POSTGRES_IMAGE=""

usage() {
  cat <<'EOF'
Usage: ./scripts/run-operations-rehearsal.sh [--smoke] [--evidence-file PATH]

The default full mode pulls base images and rebuilds while bypassing Docker
layer-cache reuse. It does not delete Docker's existing build cache.
--smoke exercises the same install/upgrade/backup/restore checks with the local
build cache, making iterative verification faster without weakening data checks.
Evidence contains no generated credentials or customer access token.
Public-image pull waits are capped at 300 seconds and build waits at 2400
seconds by default; validated DESKSEED_OPERATIONS_*_TIMEOUT_SECONDS overrides
are recorded in evidence.
EOF
}

while (($# > 0)); do
  case "$1" in
    --smoke)
      mode="smoke"
      shift
      ;;
    --evidence-file)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      evidence_file="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

compose_files=(
  --file "$repository_root/compose.yaml"
  --file "$repository_root/compose.e2e.yaml"
  --file "$repository_root/scripts/operations/compose.rehearsal.yaml"
)

record() {
  local check="$1"
  local result="$2"
  local detail="$3"
  detail="${detail//$'\t'/ }"
  detail="${detail//$'\n'/ }"
  event_rows+=("$check"$'\t'"$result"$'\t'"$detail")
  printf '[%s] %s: %s\n' "$result" "$check" "$detail"
}

set_failure_context() {
  local label="$1"
  local exit_code="$2"
  local diagnostic="${3:-}"
  if [[ -z "$failure_command" ]]; then
    failure_command="$label"
    failure_exit_code="$exit_code"
  fi
  if [[ -z "$failure_diagnostic" && -n "$diagnostic" ]]; then
    failure_diagnostic="$diagnostic"
  fi
}

prepare_evidence_destination() {
  local requested_parent
  local requested_basename
  [[ -n "$evidence_file" ]] || return 0

  requested_parent="$(dirname "$evidence_file")"
  requested_basename="$(basename "$evidence_file")"
  [[ -n "$requested_basename" && "$requested_basename" != "." && "$requested_basename" != ".." ]] || {
    echo "Evidence path must name a file." >&2
    return 2
  }
  mkdir -p "$requested_parent"
  evidence_directory="$(cd "$requested_parent" && pwd -P)"
  evidence_basename="$requested_basename"
  evidence_file="$evidence_directory/$evidence_basename"

  if [[ -L "$evidence_file" ]]; then
    echo "Refusing to replace a symbolic-link evidence target: $evidence_file" >&2
    return 2
  fi
  if [[ -e "$evidence_file" && ! -f "$evidence_file" ]]; then
    echo "Evidence target must be a regular file: $evidence_file" >&2
    return 2
  fi
  if [[ -e "$evidence_file" && ! -O "$evidence_file" ]]; then
    echo "Refusing to replace an evidence file owned by another user: $evidence_file" >&2
    return 2
  fi
  if [[ ! -w "$evidence_directory" ]]; then
    echo "Evidence directory is not writable: $evidence_directory" >&2
    return 2
  fi
  evidence_target_prepared=true
}

register_redaction_value() {
  local value="$1"
  [[ -n "$redaction_values_file" && -n "$value" ]] || return 0
  printf '%s\n' "$value" >>"$redaction_values_file"
}

sanitize_diagnostic_stream() {
  local bounded_output_file="$1"
  python3 -u -c '
from collections import deque
from pathlib import Path
import re
import sys

output_path = Path(sys.argv[1])
secret_path = Path(sys.argv[2])
secrets = []
if secret_path.is_file():
    secrets = sorted(
        {value for value in secret_path.read_text(encoding="utf-8", errors="ignore").splitlines() if len(value) >= 4},
        key=len,
        reverse=True,
    )

sensitive_key = re.compile(
    r"(?<![A-Za-z0-9])([A-Za-z0-9_-]*(?:authorization|cookie|password|passwd|pwd|token|secret|api[-_]?key|access[-_]?key|session)[A-Za-z0-9_-]*)"
    r"(\s*[:=]\s*)(\"[^\"\r\n]*\"|[^\s,;]+)",
    re.IGNORECASE,
)
authorization = re.compile(r"\b(bearer|basic)\s+[^\s,;]+", re.IGNORECASE)
url_credential = re.compile(
    r"((?:[a-z][a-z0-9+.-]*:)?[a-z][a-z0-9+.-]*://[^:/\s]+:)[^@/\s]+@",
    re.IGNORECASE,
)
high_entropy = re.compile(r"(?<![A-Za-z0-9])[A-Za-z0-9_+./=-]{24,}(?![A-Za-z0-9])")
control = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
tail = deque(maxlen=63)

def sanitize(raw):
    text = control.sub("?", raw.rstrip("\r\n"))
    for secret in secrets:
        text = text.replace(secret, "[REDACTED]")
    text = authorization.sub(lambda match: f"{match.group(1)} [REDACTED]", text)
    text = sensitive_key.sub(lambda match: f"{match.group(1)}{match.group(2)}[REDACTED]", text)
    text = url_credential.sub(r"\1[REDACTED]@", text)
    text = high_entropy.sub("[REDACTED]", text)
    if len(text) > 1024:
        text = text[:1000] + " ... [line truncated]"
    return text

for raw_line in sys.stdin:
    line = sanitize(raw_line)
    print(line, flush=True)
    tail.append(line)

encoded = "\n".join(tail).encode("utf-8", errors="replace")
if len(encoded) > 8192:
    prefix = b"[earlier diagnostic bytes truncated]\n"
    encoded = encoded[-(8192 - len(prefix)):]
    while encoded and encoded[0] & 0xC0 == 0x80:
        encoded = encoded[1:]
    encoded = prefix + encoded
output_path.write_bytes(encoded)
' "$bounded_output_file" "$redaction_values_file"
}

run_checked() {
  local label="$1"
  shift
  local capture_file="$work_dir/command-diagnostic-$RANDOM.txt"
  local command_status
  local sanitizer_status
  local pipeline_statuses

  if "$@" 2>&1 | sanitize_diagnostic_stream "$capture_file"; then
    pipeline_statuses=("${PIPESTATUS[@]}")
  else
    pipeline_statuses=("${PIPESTATUS[@]}")
  fi
  command_status="${pipeline_statuses[0]}"
  sanitizer_status="${pipeline_statuses[1]:-1}"

  if [[ "$command_status" -ne 0 || "$sanitizer_status" -ne 0 ]]; then
    if [[ "$command_status" -eq 0 ]]; then
      command_status="$sanitizer_status"
      label="$label diagnostic sanitizer"
    fi
    failure_command="$label"
    failure_exit_code="$command_status"
    failure_diagnostic_file="$capture_file"
    return "$command_status"
  fi
  rm -f -- "$capture_file"
}

# Run a shell function in the current shell when it intentionally mutates
# runner state. `run_checked` uses a pipeline, so a function passed to it runs
# in a subshell and its assignments would be lost. Output is still bounded and
# redacted before it reaches the terminal or failure evidence.
run_stateful_checked() {
  local label="$1"
  shift
  local stdout_file="$work_dir/stateful-stdout-$RANDOM.txt"
  local stderr_file="$work_dir/stateful-stderr-$RANDOM.txt"
  local capture_file="$work_dir/command-diagnostic-$RANDOM.txt"
  local command_status=0
  local sanitizer_status=0

  if "$@" >"$stdout_file" 2>"$stderr_file"; then
    command_status=0
  else
    command_status=$?
  fi

  if { cat "$stdout_file"; cat "$stderr_file"; } |
    sanitize_diagnostic_stream "$capture_file" >&2; then
    sanitizer_status=0
  else
    sanitizer_status=$?
  fi

  if [[ "$command_status" -ne 0 || "$sanitizer_status" -ne 0 ]]; then
    if [[ "$command_status" -eq 0 ]]; then
      command_status="$sanitizer_status"
      label="$label diagnostic sanitizer"
    fi
    failure_command="$label"
    failure_exit_code="$command_status"
    failure_diagnostic_file="$capture_file"
    rm -f -- "$stdout_file" "$stderr_file"
    return "$command_status"
  fi
  rm -f -- "$stdout_file" "$stderr_file" "$capture_file"
}

run_bounded_command() {
  local timeout_seconds="$1"
  local termination_grace_seconds="$2"
  shift 2
  python3 -u - "$timeout_seconds" "$termination_grace_seconds" "$@" <<'PY'
import os
import signal
import subprocess
import sys
import time

timeout_seconds = int(sys.argv[1])
grace_seconds = int(sys.argv[2])
command = sys.argv[3:]
if timeout_seconds < 1 or grace_seconds < 1 or not command:
    raise SystemExit(2)

process = subprocess.Popen(
    command,
    stdin=subprocess.DEVNULL,
    stdout=None,
    stderr=None,
    start_new_session=True,
)
process_group_id = process.pid

def signal_process_group(signum):
    try:
        os.killpg(process_group_id, signum)
    except ProcessLookupError:
        pass

def process_group_exists():
    try:
        os.killpg(process_group_id, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True

def wait_for_process_group_absence(wait_seconds):
    deadline = time.monotonic() + wait_seconds
    while True:
        process.poll()
        if not process_group_exists():
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.05)

def terminate_process_group():
    signal_process_group(signal.SIGTERM)
    if not wait_for_process_group_absence(grace_seconds):
        signal_process_group(signal.SIGKILL)
        if not wait_for_process_group_absence(max(1, grace_seconds)):
            print(
                f"bounded command could not prove process group {process_group_id} absent after KILL",
                file=sys.stderr,
                flush=True,
            )
            return False
    try:
        process.wait(timeout=1)
    except subprocess.TimeoutExpired:
        print(
            f"bounded command process-group leader {process.pid} was not reapable after group exit",
            file=sys.stderr,
            flush=True,
        )
        return False
    return True

def forward_signal(signum, _frame):
    if not terminate_process_group():
        raise SystemExit(125)
    raise SystemExit(128 + signum)

for forwarded in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
    signal.signal(forwarded, forward_signal)

try:
    return_code = process.wait(timeout=timeout_seconds)
except subprocess.TimeoutExpired:
    print(
        f"bounded command timed out after {timeout_seconds}s; "
        f"terminating its process group with {grace_seconds}s grace",
        file=sys.stderr,
        flush=True,
    )
    if not terminate_process_group():
        raise SystemExit(125)
    raise SystemExit(124)
raise SystemExit(return_code)
PY
}

run_bounded_checked() {
  local label="$1"
  local timeout_seconds="$2"
  local termination_grace_seconds="$3"
  shift 3
  run_checked "$label" run_bounded_command \
    "$timeout_seconds" "$termination_grace_seconds" "$@"
}

capture_checked_output() {
  local label="$1"
  local output_variable="$2"
  shift 2
  local stdout_file="$work_dir/command-stdout-$RANDOM.txt"
  local stderr_file="$work_dir/command-stderr-$RANDOM.txt"
  local capture_file="$work_dir/command-diagnostic-$RANDOM.txt"
  local command_status=0
  local sanitizer_status=0
  local captured_output=""

  if "$@" >"$stdout_file" 2>"$stderr_file"; then
    command_status=0
  else
    command_status=$?
  fi

  if [[ "$command_status" -ne 0 ]]; then
    if { cat "$stdout_file"; cat "$stderr_file"; } |
      sanitize_diagnostic_stream "$capture_file" >&2; then
      sanitizer_status=0
    else
      sanitizer_status=$?
    fi
  elif sanitize_diagnostic_stream "$capture_file" <"$stderr_file" >&2; then
    sanitizer_status=0
  else
    sanitizer_status=$?
  fi

  if [[ "$command_status" -ne 0 || "$sanitizer_status" -ne 0 ]]; then
    if [[ "$command_status" -eq 0 ]]; then
      command_status="$sanitizer_status"
      label="$label diagnostic sanitizer"
    fi
    failure_command="$label"
    failure_exit_code="$command_status"
    failure_diagnostic_file="$capture_file"
    rm -f -- "$stdout_file" "$stderr_file"
    return "$command_status"
  fi

  captured_output="$(<"$stdout_file")"
  printf -v "$output_variable" '%s' "$captured_output"
  rm -f -- "$stdout_file" "$stderr_file" "$capture_file"
}

fail_check() {
  local label="$1"
  local diagnostic="$2"
  set_failure_context "$label" 1 "$diagnostic"
  echo "$diagnostic" >&2
  return 1
}

capture_expected_failure_diagnostic() {
  local label="$1"
  local exit_code="$2"
  local source_file="$3"
  local capture_file="$work_dir/command-diagnostic-$RANDOM.txt"
  local sanitizer_status=0

  if sanitize_diagnostic_stream "$capture_file" <"$source_file" >&2; then
    sanitizer_status=0
  else
    sanitizer_status=$?
  fi
  if [[ "$sanitizer_status" -ne 0 ]]; then
    exit_code="$sanitizer_status"
    label="$label diagnostic sanitizer"
  fi
  failure_command="$label"
  failure_exit_code="$exit_code"
  failure_diagnostic_file="$capture_file"
  return "$exit_code"
}

append_failure_diagnostic_file() {
  local label="$1"
  local path="$2"
  local captured=""
  [[ -s "$path" ]] || return 0
  captured="$(<"$path")"
  if [[ -n "$failure_diagnostic" ]]; then
    failure_diagnostic="$failure_diagnostic

$label:
$captured"
  else
    failure_diagnostic="$label:
$captured"
  fi
}

capture_stack_diagnostic() {
  local label="$1"
  shift
  local capture_file="$work_dir/stack-diagnostic-$RANDOM.txt"
  if "$@" 2>&1 | sanitize_diagnostic_stream "$capture_file" >&2; then
    :
  else
    :
  fi
  append_failure_diagnostic_file "$label" "$capture_file"
  rm -f -- "$capture_file"
  return 0
}

capture_owned_project_logs() {
  local project="$1"
  local label="$2"
  local record
  local recorded_project
  local container_id
  local container_name
  local actual_owner
  local actual_project
  for record in "${owned_container_records[@]}"; do
    IFS=$'\t' read -r recorded_project container_id container_name <<<"$record"
    [[ "$recorded_project" == "$project" ]] || continue
    actual_owner="$("$docker_cli_path" container inspect --format '{{index .Config.Labels "dev.deskseed.operations.run"}}' "$container_id" 2>/dev/null)" || continue
    actual_project="$("$docker_cli_path" container inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$container_id" 2>/dev/null)" || continue
    [[ "$actual_owner" == "$run_marker" && "$actual_project" == "$project" ]] || continue
    capture_stack_diagnostic "$label container $container_name ($container_id)" \
      "$docker_cli_path" logs --tail 120 "$container_id"
  done
}

on_error() {
  local exit_code=$?
  set_failure_context "$current_stage" "$exit_code"
}

on_signal() {
  local signal_name="$1"
  local exit_code="$2"
  current_stage="interrupted by $signal_name"
  set_failure_context "signal $signal_name" "$exit_code" "The rehearsal process received $signal_name."
  exit "$exit_code"
}

epoch_millis() {
  python3 -c 'import time; print(time.time_ns() // 1_000_000)'
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{ print $1 }'
  else
    printf 'NOT_AVAILABLE'
  fi
}

sha256_text() {
  python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())'
}

docker_config_identity_fingerprint() {
  local path="$1"
  python3 - "$path" <<'PY'
import hashlib
import json
import os
import stat
import sys

path = os.path.abspath(sys.argv[1])
digest = hashlib.sha256()
if not os.path.lexists(path):
    digest.update(b"ABSENT\0")
else:
    metadata = os.lstat(path)
    kind = "symlink" if stat.S_ISLNK(metadata.st_mode) else "regular" if stat.S_ISREG(metadata.st_mode) else "other"
    identity = {
        "kind": kind,
        "mode": stat.S_IMODE(metadata.st_mode),
        "uid": metadata.st_uid,
        "gid": metadata.st_gid,
        "device": metadata.st_dev,
        "inode": metadata.st_ino,
        "size": metadata.st_size,
        "mtime_ns": metadata.st_mtime_ns,
    }
    digest.update(json.dumps(identity, sort_keys=True).encode())
    digest.update(b"\0")
    if stat.S_ISLNK(metadata.st_mode):
        target = os.readlink(path)
        digest.update(target.encode())
        digest.update(b"\0")
        resolved = os.path.realpath(path)
        if os.path.isfile(resolved):
            with open(resolved, "rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
    elif stat.S_ISREG(metadata.st_mode):
        with open(path, "rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
print(digest.hexdigest())
PY
}

append_boundary_variable_name() {
  local destination_name="$1"
  local variable_name="$2"
  local current_value="${!destination_name}"
  if [[ "$current_value" == NONE ]]; then
    printf -v "$destination_name" '%s' "$variable_name"
  else
    printf -v "$destination_name" '%s,%s' "$current_value" "$variable_name"
  fi
}

register_bounded_environment_value() {
  local variable_name="$1"
  local value="$2"
  if [[ ${#value} -gt 8192 || "$value" == *$'\n'* || "$value" == *$'\r'* || "$value" == *$'\t'* ]]; then
    printf 'Refusing an unbounded or control-character Docker environment override: %s\n' \
      "$variable_name" >&2
    return 2
  fi
  register_redaction_value "$value"
}

docker_proxy_environment_fingerprint() {
  local variable_name
  for variable_name in \
    HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY \
    http_proxy https_proxy all_proxy no_proxy; do
    if [[ "${!variable_name+x}" == x ]]; then
      printf 'SET\0%s\0%s\0' "$variable_name" "${!variable_name}"
    else
      printf 'UNSET\0%s\0' "$variable_name"
    fi
  done | sha256_text
}

# Capture secret-bearing inherited values only in the mode-0600 redaction
# store, then remove every registry/build/plugin/Compose selector that could
# bypass the anonymous local-daemon boundary. Context/TLS selectors are kept
# only until capture_docker_client_boundary resolves the current endpoint.
capture_and_clear_docker_remote_overrides() {
  local variable_name
  local value
  local deferred_clear_names=" DOCKER_CONTEXT DOCKER_TLS_VERIFY DOCKER_CERT_PATH DOCKER_TLS "
  docker_inherited_cleared_override_names="NONE"
  docker_inherited_retained_proxy_names="NONE"

  for variable_name in \
    DOCKER_AUTH_CONFIG \
    BUILDKIT_HOST BUILDX_BUILDER BUILDX_CONFIG \
    DOCKER_API_VERSION DOCKER_CUSTOM_HEADERS DOCKER_DEFAULT_PLATFORM \
    DOCKER_CLI_PLUGIN_SOCKET DOCKER_CLI_PLUGIN_USE_DIAL_STDIO DOCKER_CLI_HOOKS \
    DOCKER_CLI_OTEL_EXPORTER_OTLP_ENDPOINT \
    DOCKER_CONTENT_TRUST DOCKER_CONTENT_TRUST_SERVER \
    BUILDX_DEFAULT_POLICY EXPERIMENTAL_BUILDKIT_SOURCE_POLICY BUILDKIT_SOURCE_POLICY \
    COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES COMPOSE_ENV_FILES COMPOSE_PATH_SEPARATOR \
    OTEL_EXPORTER_OTLP_ENDPOINT OTEL_EXPORTER_OTLP_HEADERS OTEL_EXPORTER_OTLP_PROTOCOL \
    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT OTEL_EXPORTER_OTLP_TRACES_HEADERS OTEL_EXPORTER_OTLP_TRACES_PROTOCOL \
    OTEL_EXPORTER_OTLP_METRICS_ENDPOINT OTEL_EXPORTER_OTLP_METRICS_HEADERS OTEL_EXPORTER_OTLP_METRICS_PROTOCOL \
    OTEL_EXPORTER_OTLP_LOGS_ENDPOINT OTEL_EXPORTER_OTLP_LOGS_HEADERS OTEL_EXPORTER_OTLP_LOGS_PROTOCOL \
    OTEL_TRACES_EXPORTER OTEL_METRICS_EXPORTER OTEL_LOGS_EXPORTER \
    DOCKER_BUILDKIT COMPOSE_DOCKER_CLI_BUILD COMPOSE_DISABLE_ENV_FILE OTEL_SDK_DISABLED \
    DOCKER_CONTEXT DOCKER_TLS_VERIFY DOCKER_CERT_PATH DOCKER_TLS; do
    if [[ "${!variable_name+x}" == x ]]; then
      value="${!variable_name}"
      register_bounded_environment_value "$variable_name" "$value" || return $?
      append_boundary_variable_name docker_inherited_cleared_override_names "$variable_name"
      if [[ "$deferred_clear_names" != *" $variable_name "* ]]; then
        unset "$variable_name"
      fi
    fi
  done

  for variable_name in \
    HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY \
    http_proxy https_proxy all_proxy no_proxy; do
    if [[ "${!variable_name+x}" == x ]]; then
      value="${!variable_name}"
      register_bounded_environment_value "$variable_name" "$value" || return $?
      append_boundary_variable_name docker_inherited_retained_proxy_names "$variable_name"
    fi
  done

  export DOCKER_BUILDKIT=1
  export COMPOSE_DOCKER_CLI_BUILD=1
  export COMPOSE_DISABLE_ENV_FILE=1
  export OTEL_SDK_DISABLED=true
  docker_retained_proxy_fingerprint_before="$(docker_proxy_environment_fingerprint)"
}

resolve_docker_cli_binary() {
  local candidate
  [[ "$(type -t docker 2>/dev/null || true)" == file ]] || {
    echo "Docker must resolve directly to an executable file, not a shell function, alias, or builtin." >&2
    return 2
  }
  candidate="$(type -P docker)" || return $?
  python3 - "$candidate" <<'PY'
import os
import stat
import sys

path = os.path.realpath(sys.argv[1])
metadata = os.stat(path)
if not stat.S_ISREG(metadata.st_mode):
    raise SystemExit(1)
if metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
    raise SystemExit(1)
if not os.access(path, os.X_OK):
    raise SystemExit(1)
print(path)
PY
}

unix_socket_identity_fingerprint() {
  local socket_path="$1"
  python3 - "$socket_path" <<'PY'
import hashlib
import json
import os
import stat
import sys

path = os.path.abspath(sys.argv[1])
link_metadata = os.lstat(path)
resolved = os.path.realpath(path)
socket_metadata = os.stat(resolved)
if not stat.S_ISSOCK(socket_metadata.st_mode):
    raise SystemExit(1)
identity = {
    "path_kind": "symlink" if stat.S_ISLNK(link_metadata.st_mode) else "socket",
    "path_device": link_metadata.st_dev,
    "path_inode": link_metadata.st_ino,
    "path_mode": stat.S_IMODE(link_metadata.st_mode),
    "path_uid": link_metadata.st_uid,
    "path_gid": link_metadata.st_gid,
    "link_target": os.readlink(path) if stat.S_ISLNK(link_metadata.st_mode) else None,
    "resolved_path": resolved,
    "socket_device": socket_metadata.st_dev,
    "socket_inode": socket_metadata.st_ino,
    "socket_mode": stat.S_IMODE(socket_metadata.st_mode),
    "socket_uid": socket_metadata.st_uid,
    "socket_gid": socket_metadata.st_gid,
}
encoded = json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()
print(hashlib.sha256(encoded).hexdigest())
PY
}

validate_docker_timeout_configuration() {
  local value
  for value in \
    "$docker_pull_timeout_seconds" \
    "$docker_build_timeout_seconds" \
    "$docker_termination_grace_seconds"; do
    [[ "$value" =~ ^[0-9]+$ ]] || return 1
  done
  ((docker_pull_timeout_seconds >= 30 && docker_pull_timeout_seconds <= 1800)) || return 1
  ((docker_build_timeout_seconds >= 300 && docker_build_timeout_seconds <= 7200)) || return 1
  ((docker_termination_grace_seconds >= 1 && docker_termination_grace_seconds <= 60)) || return 1
}

resolve_docker_cli_plugin() {
  local plugin="$1"
  local candidate
  local command_candidate=""
  local candidates=()
  if command_candidate="$(command -v "docker-$plugin" 2>/dev/null)"; then
    candidates+=("$command_candidate")
  fi
  candidates+=(
    "$docker_original_config_directory/cli-plugins/docker-$plugin"
    "/usr/local/lib/docker/cli-plugins/docker-$plugin"
    "/usr/local/libexec/docker/cli-plugins/docker-$plugin"
    "/usr/lib/docker/cli-plugins/docker-$plugin"
    "/usr/libexec/docker/cli-plugins/docker-$plugin"
    "/Applications/Docker.app/Contents/Resources/cli-plugins/docker-$plugin"
  )
  for candidate in "${candidates[@]}"; do
    [[ -e "$candidate" && -x "$candidate" ]] || continue
    python3 - "$candidate" <<'PY'
import os
import stat
import sys

path = os.path.realpath(sys.argv[1])
metadata = os.stat(path)
if not stat.S_ISREG(metadata.st_mode):
    raise SystemExit(1)
if metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
    raise SystemExit(1)
if not os.access(path, os.X_OK):
    raise SystemExit(1)
print(path)
PY
    return $?
  done
  return 1
}

validate_local_docker_endpoint() {
  local endpoint="$1"
  local socket_path
  [[ ${#endpoint} -le 1024 && "$endpoint" == unix:///* ]] || return 1
  [[ "$endpoint" != *$'\n'* && "$endpoint" != *$'\r'* && "$endpoint" != *$'\t'* ]] || return 1
  socket_path="${endpoint#unix://}"
  [[ "$socket_path" == /* && -S "$socket_path" ]]
}

capture_docker_client_boundary() {
  local configured_directory
  local endpoint
  local daemon_identity
  local compose_version
  local buildx_version

  docker_cli_path="$(resolve_docker_cli_binary)" || return $?
  docker_cli_fingerprint="$(sha256_file "$docker_cli_path")"
  [[ "$docker_cli_fingerprint" != NOT_AVAILABLE ]] || return 1

  if [[ -n "${DOCKER_CONFIG:-}" ]]; then
    configured_directory="$DOCKER_CONFIG"
  elif [[ -n "${HOME:-}" ]]; then
    configured_directory="$HOME/.docker"
  else
    echo "HOME or DOCKER_CONFIG is required to fingerprint the current Docker client configuration." >&2
    return 2
  fi
  docker_original_config_directory="$(python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "$configured_directory")"
  docker_user_config_file="$docker_original_config_directory/config.json"
  if [[ -e "$docker_user_config_file" && ! -f "$docker_user_config_file" ]]; then
    echo "The active Docker client config is not a regular file." >&2
    return 2
  fi
  docker_user_config_fingerprint_before="$(docker_config_identity_fingerprint "$docker_user_config_file")" || return $?

  if [[ -n "${DOCKER_CONTEXT:-}" ]]; then
    docker_context_name="$DOCKER_CONTEXT"
    [[ ${#docker_context_name} -le 255 \
       && "$docker_context_name" != *$'\n'* \
       && "$docker_context_name" != *$'\r'* \
       && "$docker_context_name" != *$'\t'* ]] || return 2
    endpoint="$("$docker_cli_path" context inspect "$docker_context_name" --format '{{.Endpoints.docker.Host}}')" || return $?
  elif [[ -n "${DOCKER_HOST:-}" ]]; then
    docker_context_name="DOCKER_HOST"
    endpoint="$DOCKER_HOST"
  else
    docker_context_name="$("$docker_cli_path" context show)" || return $?
    [[ -n "$docker_context_name" && ${#docker_context_name} -le 255 ]] || return 2
    endpoint="$("$docker_cli_path" context inspect "$docker_context_name" --format '{{.Endpoints.docker.Host}}')" || return $?
  fi
  validate_local_docker_endpoint "$endpoint" || {
    echo "The operations rehearsal requires a validated local unix-socket Docker daemon endpoint." >&2
    return 2
  }
  docker_daemon_endpoint="$endpoint"
  docker_daemon_endpoint_fingerprint="$(printf '%s' "$endpoint" | sha256_text)"
  docker_daemon_socket_path="${endpoint#unix://}"
  docker_daemon_socket_identity_fingerprint="$(
    unix_socket_identity_fingerprint "$docker_daemon_socket_path"
  )" || return $?

  export DOCKER_HOST="$docker_daemon_endpoint"
  unset DOCKER_CONTEXT DOCKER_TLS_VERIFY DOCKER_CERT_PATH DOCKER_TLS

  docker_server_version="$("$docker_cli_path" version --format '{{.Server.Version}}')" || return $?
  daemon_identity="$("$docker_cli_path" info --format '{{.ID}}')" || return $?
  [[ -n "$docker_server_version" && -n "$daemon_identity" \
     && ${#docker_server_version} -le 128 && ${#daemon_identity} -le 255 ]] || return 2
  docker_daemon_identity="$daemon_identity"
  docker_daemon_identity_fingerprint="$(printf '%s' "$daemon_identity" | sha256_text)"

  compose_version="$("$docker_cli_path" compose version --short)" || return $?
  buildx_version="$("$docker_cli_path" buildx version)" || return $?
  [[ -n "$compose_version" && -n "$buildx_version" ]] || return 2
  docker_compose_version="$compose_version"
  docker_buildx_version="$buildx_version"
  docker_compose_plugin_path="$(resolve_docker_cli_plugin compose)" || return $?
  docker_buildx_plugin_path="$(resolve_docker_cli_plugin buildx)" || return $?
  docker_compose_plugin_fingerprint="$(sha256_file "$docker_compose_plugin_path")"
  docker_buildx_plugin_fingerprint="$(sha256_file "$docker_buildx_plugin_path")"
}

validate_anonymous_docker_client_files() {
  python3 - \
    "$docker_client_config_directory" \
    "$docker_client_config_file" \
    "$docker_compose_plugin_path" \
    "$docker_buildx_plugin_path" <<'PY'
import json
import os
from pathlib import Path
import stat
import sys

config_directory, config_file, compose_plugin, buildx_plugin = map(Path, sys.argv[1:])
directory_metadata = config_directory.lstat()
file_metadata = config_file.lstat()
plugins_directory = config_directory / "cli-plugins"
plugins_metadata = plugins_directory.lstat()
if not stat.S_ISDIR(directory_metadata.st_mode) or stat.S_IMODE(directory_metadata.st_mode) != 0o700:
    raise SystemExit(1)
if not stat.S_ISDIR(plugins_metadata.st_mode) or stat.S_IMODE(plugins_metadata.st_mode) != 0o700:
    raise SystemExit(1)
if not stat.S_ISREG(file_metadata.st_mode) or stat.S_IMODE(file_metadata.st_mode) != 0o600:
    raise SystemExit(1)
if directory_metadata.st_uid != os.getuid() or plugins_metadata.st_uid != os.getuid() or file_metadata.st_uid != os.getuid():
    raise SystemExit(1)
payload = json.loads(config_file.read_text(encoding="utf-8"))
if payload != {"auths": {}}:
    raise SystemExit(1)
for name, expected in (("docker-compose", compose_plugin), ("docker-buildx", buildx_plugin)):
    link = config_directory / "cli-plugins" / name
    link_metadata = link.lstat()
    if not stat.S_ISLNK(link_metadata.st_mode) or link_metadata.st_uid != os.getuid():
        raise SystemExit(1)
    if link.resolve(strict=True) != expected.resolve(strict=True):
        raise SystemExit(1)
PY
}

verify_user_docker_config_unchanged() {
  local current_fingerprint
  [[ -n "$docker_user_config_file" && "$docker_user_config_fingerprint_before" != NOT_CHECKED ]] || return 1
  current_fingerprint="$(docker_config_identity_fingerprint "$docker_user_config_file")" || return $?
  [[ "$current_fingerprint" == "$docker_user_config_fingerprint_before" ]]
}

docker_boundary_environment_is_safe() {
  local variable_name
  for variable_name in \
    DOCKER_AUTH_CONFIG \
    BUILDKIT_HOST BUILDX_BUILDER BUILDX_CONFIG \
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
    [[ "${!variable_name+x}" != x ]] || return 1
  done
  [[ "${DOCKER_BUILDKIT:-}" == 1 \
     && "${COMPOSE_DOCKER_CLI_BUILD:-}" == 1 \
     && "${COMPOSE_DISABLE_ENV_FILE:-}" == 1 \
     && "${OTEL_SDK_DISABLED:-}" == true \
     && "$docker_retained_proxy_fingerprint_before" != NOT_CHECKED \
     && "$(docker_proxy_environment_fingerprint)" == "$docker_retained_proxy_fingerprint_before" ]]
}

verify_anonymous_docker_client() {
  local current_cli_path
  local current_daemon_identity
  local current_socket_fingerprint
  local current_server_version
  current_cli_path="$(resolve_docker_cli_binary)" || return $?
  [[ "$docker_client_boundary_initialized" == true \
     && "${DOCKER_CONFIG:-}" == "$docker_client_config_directory" \
     && "${DOCKER_HOST:-}" == "$docker_daemon_endpoint" \
     && -n "$docker_cli_path" \
     && "$current_cli_path" == "$docker_cli_path" \
     && "$(sha256_file "$docker_cli_path")" == "$docker_cli_fingerprint" \
     && -f "$docker_client_config_file" \
     && ! -L "$docker_client_config_file" \
     && "$(sha256_file "$docker_client_config_file")" == "$docker_client_config_fingerprint" \
     && "$(sha256_file "$docker_compose_plugin_path")" == "$docker_compose_plugin_fingerprint" \
     && "$(sha256_file "$docker_buildx_plugin_path")" == "$docker_buildx_plugin_fingerprint" ]] || return 1
  docker_boundary_environment_is_safe || return $?
  current_socket_fingerprint="$(
    unix_socket_identity_fingerprint "$docker_daemon_socket_path"
  )" || return $?
  [[ "$current_socket_fingerprint" == "$docker_daemon_socket_identity_fingerprint" ]] || return 1
  validate_anonymous_docker_client_files || return $?
  current_server_version="$("$docker_cli_path" version --format '{{.Server.Version}}')" || return $?
  current_daemon_identity="$("$docker_cli_path" info --format '{{.ID}}')" || return $?
  [[ "$current_server_version" == "$docker_server_version" \
     && "$current_daemon_identity" == "$docker_daemon_identity" ]]
}

initialize_anonymous_docker_client() {
  local isolated_compose_version
  local isolated_buildx_version
  [[ -n "$work_dir" && -d "$work_dir" \
     && -n "$docker_daemon_endpoint" \
     && -n "$docker_compose_plugin_path" \
     && -n "$docker_buildx_plugin_path" ]] || return 1
  docker_client_config_directory="$work_dir/docker-client"
  docker_client_config_file="$docker_client_config_directory/config.json"
  mkdir -m 700 "$docker_client_config_directory"
  mkdir -m 700 "$docker_client_config_directory/cli-plugins"
  printf '{"auths":{}}\n' >"$docker_client_config_file"
  chmod 600 "$docker_client_config_file"
  ln -s "$docker_compose_plugin_path" \
    "$docker_client_config_directory/cli-plugins/docker-compose"
  ln -s "$docker_buildx_plugin_path" \
    "$docker_client_config_directory/cli-plugins/docker-buildx"
  docker_client_config_fingerprint="$(sha256_file "$docker_client_config_file")"
  validate_anonymous_docker_client_files || return $?

  export DOCKER_CONFIG="$docker_client_config_directory"
  export DOCKER_HOST="$docker_daemon_endpoint"
  unset \
    DOCKER_AUTH_CONFIG \
    BUILDKIT_HOST BUILDX_BUILDER BUILDX_CONFIG \
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
    OTEL_TRACES_EXPORTER OTEL_METRICS_EXPORTER OTEL_LOGS_EXPORTER
  export DOCKER_BUILDKIT=1
  export COMPOSE_DOCKER_CLI_BUILD=1
  export COMPOSE_DISABLE_ENV_FILE=1
  export OTEL_SDK_DISABLED=true
  docker_client_boundary_initialized=true
  verify_anonymous_docker_client || return $?
  isolated_compose_version="$("$docker_cli_path" compose version --short)" || return $?
  isolated_buildx_version="$("$docker_cli_path" buildx version)" || return $?
  [[ "$isolated_compose_version" == "$docker_compose_version" \
     && "$isolated_buildx_version" == "$docker_buildx_version" ]] || return 1
  verify_user_docker_config_unchanged
}

build_context_fingerprint() {
  local context_directory="$1"
  local context_kind="$2"
  python3 - "$context_directory" "$context_kind" <<'PY'
import hashlib
import os
from pathlib import Path
import stat
import sys

root = Path(sys.argv[1])
kind = sys.argv[2]
excluded_directories = {
    "backend": {".gradle", ".gradle-user-home", ".kotlin", "build", "bin", ".idea"},
    "frontend": {
        "node_modules",
        "dist",
        "test-results",
        "playwright-report",
        ".idea",
    },
}[kind]
excluded_suffixes = {
    "backend": {".iml", ".DS_Store", ".local"},
    "frontend": {".log", ".local"},
}[kind]
excluded_names = {
    "backend": {".env"},
    "frontend": {".env", ".npmrc"},
}[kind]
digest = hashlib.sha256()
count = 0
if kind == "backend":
    candidates = [root / ".dockerignore"]
    candidates.extend((root / "backend").rglob("*"))
    candidates.extend(
        root / "api" / name
        for name in (
            "core-api-outline-v1.yaml",
            "customer-identity-api-v1.yaml",
            "platform-api-outline-v1.yaml",
        )
    )
else:
    candidates = list(root.rglob("*"))
for path in sorted(candidates, key=lambda candidate: candidate.as_posix()):
    relative = path.relative_to(root)
    if any(part in excluded_directories for part in relative.parts):
        continue
    if path.name in excluded_names or path.name.startswith(".env."):
        continue
    if path.is_dir() and not path.is_symlink():
        continue
    if any(path.name.endswith(suffix) for suffix in excluded_suffixes):
        continue
    metadata = path.lstat()
    digest.update(relative.as_posix().encode("utf-8"))
    digest.update(b"\0")
    digest.update(oct(stat.S_IMODE(metadata.st_mode)).encode("ascii"))
    digest.update(b"\0")
    if path.is_symlink():
        digest.update(b"L\0")
        digest.update(os.readlink(path).encode("utf-8"))
    else:
        digest.update(b"F\0")
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    digest.update(b"\0")
    count += 1
print(f"{digest.hexdigest()} files={count}")
PY
}

rehearsal_input_fingerprint() {
  python3 - \
    "$repository_root/scripts/run-operations-rehearsal.sh" \
    "$repository_root/compose.yaml" \
    "$repository_root/compose.e2e.yaml" \
    "$repository_root/scripts/operations/compose.rehearsal.yaml" \
    "$repository_root/scripts/operations/postgres-init-runtime-role.sh" \
    "$repository_root/scripts/operations/configure-default-runtime-privileges.sql" \
    "$repository_root/scripts/postgres/configure-runtime-role.sql" \
    "$repository_root/scripts/postgres/verify-runtime-role.sql" <<'PY'
import hashlib
from pathlib import Path
import sys

digest = hashlib.sha256()
for raw_path in sys.argv[1:]:
    path = Path(raw_path)
    digest.update(path.name.encode("utf-8"))
    digest.update(b"\0")
    digest.update(path.read_bytes())
    digest.update(b"\0")
print(f"{digest.hexdigest()} files={len(sys.argv) - 1}")
PY
}

verify_build_context_unchanged() {
  local current_backend_fingerprint
  local current_frontend_fingerprint
  current_backend_fingerprint="$(build_context_fingerprint "$repository_root" backend)"
  current_frontend_fingerprint="$(build_context_fingerprint "$repository_root/frontend" frontend)"
  if [[ "$current_backend_fingerprint" != "$backend_build_context_fingerprint" \
     || "$current_frontend_fingerprint" != "$frontend_build_context_fingerprint" ]]; then
    echo "Backend or frontend Docker build context changed during the rehearsal." >&2
    return 1
  fi
}

verify_rehearsal_inputs_unchanged() {
  local current_fingerprint
  current_fingerprint="$(rehearsal_input_fingerprint)"
  if [[ "$current_fingerprint" != "$operations_input_fingerprint" ]]; then
    echo "Operations runner, Compose overlay, or runtime-role SQL changed during the rehearsal." >&2
    return 1
  fi
}

initialize_resource_identity() {
  local workspace_suffix
  local random_component
  local slug
  local postgres_wrapper_dockerfile

  [[ -n "$work_dir" && -d "$work_dir" ]] || return 1
  workspace_suffix="${work_dir##*.}"
  random_component="$(python3 -c 'import secrets; print(secrets.token_hex(20))')" || return $?
  slug="$(printf '%s-%s' "$workspace_suffix" "$random_component" | tr '[:upper:]' '[:lower:]')"
  [[ "$slug" =~ ^[a-z0-9-]+$ ]] || return 1

  run_marker="deskseed-operations-$slug"
  run_id="$slug"
  image_tag="${slug:0:47}"
  source_project="deskseed-ops-source-${slug:0:32}"
  restore_project="deskseed-ops-restore-${slug:0:32}"
  DESKSEED_REHEARSAL_BACKEND_IMAGE="deskseed-operations-backend:$image_tag"
  DESKSEED_REHEARSAL_FRONTEND_IMAGE="deskseed-operations-frontend:$image_tag"
  DESKSEED_REHEARSAL_POSTGRES_IMAGE="deskseed-operations-postgres:$image_tag"
  ownership_overlay_file="$work_dir/compose.ownership.yaml"
  postgres_wrapper_context="$work_dir/postgres-image-context"
  mkdir -m 700 "$postgres_wrapper_context"
  postgres_wrapper_dockerfile="$postgres_wrapper_context/Dockerfile"

  {
    printf 'services:\n'
    for service in db backend frontend; do
      printf '  %s:\n' "$service"
      printf '    labels:\n'
      printf '      %s: "%s"\n' "$resource_owner_label_key" "$run_marker"
      if [[ "$service" == backend || "$service" == frontend ]]; then
        printf '    build:\n'
        printf '      labels:\n'
        printf '        %s: "%s"\n' "$resource_owner_label_key" "$run_marker"
      fi
    done
    printf 'volumes:\n'
    printf '  deskseed-postgres:\n'
    printf '    labels:\n'
    printf '      %s: "%s"\n' "$resource_owner_label_key" "$run_marker"
    printf 'networks:\n'
    printf '  default:\n'
    printf '    labels:\n'
    printf '      %s: "%s"\n' "$resource_owner_label_key" "$run_marker"
  } >"$ownership_overlay_file"
  chmod 600 "$ownership_overlay_file"
  {
    printf 'ARG POSTGRES_BASE_IMAGE\n'
    printf 'FROM ${POSTGRES_BASE_IMAGE}\n'
  } >"$postgres_wrapper_dockerfile"
  chmod 600 "$postgres_wrapper_dockerfile"
  compose_files+=(--file "$ownership_overlay_file")
  ownership_overlay_fingerprint="$(sha256_file "$ownership_overlay_file")"
  postgres_wrapper_dockerfile_fingerprint="$(sha256_file "$postgres_wrapper_dockerfile")"
  resource_identity_initialized=true
}

verify_generated_ownership_inputs_unchanged() {
  [[ "$resource_identity_initialized" == true \
     && -f "$ownership_overlay_file" \
     && ! -L "$ownership_overlay_file" \
     && "$(sha256_file "$ownership_overlay_file")" == "$ownership_overlay_fingerprint" \
     && -f "$postgres_wrapper_context/Dockerfile" \
     && ! -L "$postgres_wrapper_context/Dockerfile" \
     && "$(sha256_file "$postgres_wrapper_context/Dockerfile")" == "$postgres_wrapper_dockerfile_fingerprint" ]]
}

append_owned_container_record() {
  local project="$1"
  local container_id="$2"
  local container_name="$3"
  local record
  for record in "${owned_container_records[@]}"; do
    [[ "${record#*$'\t'}" == "$container_id"$'\t'* ]] && return 0
  done
  owned_container_records+=("$project"$'\t'"$container_id"$'\t'"$container_name")
  if [[ "$project" == "$source_project" ]]; then
    source_started=true
  elif [[ "$project" == "$restore_project" ]]; then
    restore_started=true
  fi
}

append_owned_network_record() {
  local project="$1"
  local network_id="$2"
  local network_name="$3"
  local record
  for record in "${owned_network_records[@]}"; do
    [[ "${record#*$'\t'}" == "$network_id"$'\t'* ]] && return 0
  done
  owned_network_records+=("$project"$'\t'"$network_id"$'\t'"$network_name")
}

append_owned_volume_record() {
  local project="$1"
  local volume_name="$2"
  local fingerprint="$3"
  local record
  for record in "${owned_volume_records[@]}"; do
    [[ "$record" == "$project"$'\t'"$volume_name"$'\t'* ]] && return 0
  done
  owned_volume_records+=("$project"$'\t'"$volume_name"$'\t'"$fingerprint")
}

append_owned_image_id() {
  local image_id="$1"
  local existing
  for existing in "${owned_image_ids[@]}"; do
    [[ "$existing" == "$image_id" ]] && return 0
  done
  owned_image_ids+=("$image_id")
}

volume_identity_fingerprint() {
  local volume_name="$1"
  local inspect_json
  inspect_json="$("$docker_cli_path" volume inspect "$volume_name")" || return $?
  python3 -c '
import hashlib, json, sys
value = json.load(sys.stdin)[0]
identity = {
    "Name": value.get("Name"),
    "CreatedAt": value.get("CreatedAt"),
    "Driver": value.get("Driver"),
    "Mountpoint": value.get("Mountpoint"),
    "Options": value.get("Options") or {},
    "Labels": value.get("Labels") or {},
    "Scope": value.get("Scope"),
}
encoded = json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()
print(hashlib.sha256(encoded).hexdigest())
' <<<"$inspect_json"
}

discover_owned_project_resources() {
  local project="$1"
  local ids
  local names
  local resource_id
  local resource_name
  local actual_id
  local actual_owner
  local actual_project
  local fingerprint
  local discovery_failed=0

  if ! ids="$("$docker_cli_path" container ls --all --quiet --no-trunc \
      --filter "label=$resource_owner_label_key=$run_marker" \
      --filter "label=com.docker.compose.project=$project")"; then
    echo "Could not list owned containers for $project." >&2
    discovery_failed=1
  else
    for resource_id in $ids; do
      actual_id="$("$docker_cli_path" container inspect --format '{{.Id}}' "$resource_id" 2>/dev/null)" || {
        echo "Could not inspect an owned container candidate for $project." >&2
        discovery_failed=1
        continue
      }
      actual_owner="$("$docker_cli_path" container inspect --format '{{index .Config.Labels "dev.deskseed.operations.run"}}' "$resource_id" 2>/dev/null)" || {
        discovery_failed=1
        continue
      }
      actual_project="$("$docker_cli_path" container inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$resource_id" 2>/dev/null)" || {
        discovery_failed=1
        continue
      }
      resource_name="$("$docker_cli_path" container inspect --format '{{.Name}}' "$resource_id" 2>/dev/null)" || {
        discovery_failed=1
        continue
      }
      resource_name="${resource_name#/}"
      if [[ "$actual_id" != "$resource_id" || "$actual_owner" != "$run_marker" || "$actual_project" != "$project" || -z "$resource_name" ]]; then
        echo "Container ownership inspection disagreed with the discovery filters for $project." >&2
        discovery_failed=1
        continue
      fi
      append_owned_container_record "$project" "$actual_id" "$resource_name"
    done
  fi

  if ! ids="$("$docker_cli_path" network ls --quiet --no-trunc \
      --filter "label=$resource_owner_label_key=$run_marker" \
      --filter "label=com.docker.compose.project=$project")"; then
    echo "Could not list owned networks for $project." >&2
    discovery_failed=1
  else
    for resource_id in $ids; do
      actual_id="$("$docker_cli_path" network inspect --format '{{.Id}}' "$resource_id" 2>/dev/null)" || {
        discovery_failed=1
        continue
      }
      actual_owner="$("$docker_cli_path" network inspect --format '{{index .Labels "dev.deskseed.operations.run"}}' "$resource_id" 2>/dev/null)" || {
        discovery_failed=1
        continue
      }
      actual_project="$("$docker_cli_path" network inspect --format '{{index .Labels "com.docker.compose.project"}}' "$resource_id" 2>/dev/null)" || {
        discovery_failed=1
        continue
      }
      resource_name="$("$docker_cli_path" network inspect --format '{{.Name}}' "$resource_id" 2>/dev/null)" || {
        discovery_failed=1
        continue
      }
      if [[ "$actual_id" != "$resource_id" || "$actual_owner" != "$run_marker" || "$actual_project" != "$project" || -z "$resource_name" ]]; then
        echo "Network ownership inspection disagreed with the discovery filters for $project." >&2
        discovery_failed=1
        continue
      fi
      append_owned_network_record "$project" "$actual_id" "$resource_name"
    done
  fi

  if ! names="$("$docker_cli_path" volume ls --quiet \
      --filter "label=$resource_owner_label_key=$run_marker" \
      --filter "label=com.docker.compose.project=$project")"; then
    echo "Could not list owned volumes for $project." >&2
    discovery_failed=1
  else
    for resource_name in $names; do
      actual_owner="$("$docker_cli_path" volume inspect --format '{{index .Labels "dev.deskseed.operations.run"}}' "$resource_name" 2>/dev/null)" || {
        discovery_failed=1
        continue
      }
      actual_project="$("$docker_cli_path" volume inspect --format '{{index .Labels "com.docker.compose.project"}}' "$resource_name" 2>/dev/null)" || {
        discovery_failed=1
        continue
      }
      fingerprint="$(volume_identity_fingerprint "$resource_name")" || {
        discovery_failed=1
        continue
      }
      if [[ "$actual_owner" != "$run_marker" || "$actual_project" != "$project" || -z "$fingerprint" ]]; then
        echo "Volume ownership inspection disagreed with the discovery filters for $project." >&2
        discovery_failed=1
        continue
      fi
      append_owned_volume_record "$project" "$resource_name" "$fingerprint"
    done
  fi

  return "$discovery_failed"
}

discover_owned_images() {
  local ids
  local image_id
  local actual_id
  local actual_owner
  local discovery_failed=0
  if ! ids="$("$docker_cli_path" image ls --quiet --no-trunc \
      --filter "label=$resource_owner_label_key=$run_marker")"; then
    echo "Could not list run-owned images." >&2
    return 1
  fi
  for image_id in $ids; do
    actual_id="$("$docker_cli_path" image inspect --format '{{.Id}}' "$image_id" 2>/dev/null)" || {
      discovery_failed=1
      continue
    }
    actual_owner="$("$docker_cli_path" image inspect --format '{{index .Config.Labels "dev.deskseed.operations.run"}}' "$image_id" 2>/dev/null)" || {
      discovery_failed=1
      continue
    }
    if [[ "$actual_id" != "$image_id" || "$actual_owner" != "$run_marker" ]]; then
      echo "Image ownership inspection disagreed with the discovery filter." >&2
      discovery_failed=1
      continue
    fi
    append_owned_image_id "$actual_id"
  done
  return "$discovery_failed"
}

discover_all_owned_resources() {
  local discovery_failed=0
  [[ "$resource_identity_initialized" == true ]] || return 0
  discover_owned_project_resources "$source_project" || discovery_failed=1
  discover_owned_project_resources "$restore_project" || discovery_failed=1
  discover_owned_images || discovery_failed=1
  return "$discovery_failed"
}

assert_project_absent() {
  local project="$1"
  local service
  local output
  local expected_name
  for service in db backend frontend; do
    for expected_name in "${project}-${service}-1" "${project}_${service}_1"; do
      if "$docker_cli_path" container inspect "$expected_name" >/dev/null 2>&1; then
        echo "Preflight found a preexisting container named $expected_name; it will not be touched." >&2
        return 1
      fi
    done
  done
  if "$docker_cli_path" network inspect "${project}_default" >/dev/null 2>&1; then
    echo "Preflight found a preexisting network named ${project}_default; it will not be touched." >&2
    return 1
  fi
  if "$docker_cli_path" volume inspect "${project}_deskseed-postgres" >/dev/null 2>&1; then
    echo "Preflight found a preexisting volume named ${project}_deskseed-postgres; it will not be touched." >&2
    return 1
  fi
  output="$("$docker_cli_path" container ls --all --quiet --no-trunc \
    --filter "label=com.docker.compose.project=$project")" || return 1
  [[ -z "$output" ]] || {
    echo "Preflight found containers carrying the Compose project label $project." >&2
    return 1
  }
  output="$("$docker_cli_path" network ls --quiet --no-trunc \
    --filter "label=com.docker.compose.project=$project")" || return 1
  [[ -z "$output" ]] || {
    echo "Preflight found networks carrying the Compose project label $project." >&2
    return 1
  }
  output="$("$docker_cli_path" volume ls --quiet \
    --filter "label=com.docker.compose.project=$project")" || return 1
  [[ -z "$output" ]] || {
    echo "Preflight found volumes carrying the Compose project label $project." >&2
    return 1
  }
}

assert_resource_names_absent() {
  local output
  local image_name
  assert_project_absent "$source_project" || return 1
  assert_project_absent "$restore_project" || return 1
  output="$("$docker_cli_path" container ls --all --quiet --no-trunc \
    --filter "label=$resource_owner_label_key=$run_marker")" || return 1
  [[ -z "$output" ]] || return 1
  output="$("$docker_cli_path" network ls --quiet --no-trunc \
    --filter "label=$resource_owner_label_key=$run_marker")" || return 1
  [[ -z "$output" ]] || return 1
  output="$("$docker_cli_path" volume ls --quiet \
    --filter "label=$resource_owner_label_key=$run_marker")" || return 1
  [[ -z "$output" ]] || return 1
  output="$("$docker_cli_path" image ls --quiet --no-trunc \
    --filter "label=$resource_owner_label_key=$run_marker")" || return 1
  [[ -z "$output" ]] || return 1
  for image_name in \
    "$DESKSEED_REHEARSAL_BACKEND_IMAGE" \
    "$DESKSEED_REHEARSAL_FRONTEND_IMAGE" \
    "$DESKSEED_REHEARSAL_POSTGRES_IMAGE"; do
    if "$docker_cli_path" image inspect "$image_name" >/dev/null 2>&1; then
      echo "Preflight found a preexisting image tag $image_name; it will not be touched." >&2
      return 1
    fi
  done
  resource_name_preflight_passed=true
}

run_resource_creating_checked() {
  local label="$1"
  shift
  local command_status=0
  local discovery_status=0
  if run_checked "$label" "$@"; then
    command_status=0
  else
    command_status=$?
  fi
  if discover_all_owned_resources; then
    discovery_status=0
  else
    discovery_status=$?
    echo "Ownership discovery failed after resource-creating command: $label" >&2
  fi
  if [[ "$command_status" -ne 0 ]]; then
    return "$command_status"
  fi
  if [[ "$discovery_status" -ne 0 ]]; then
    set_failure_context "$label ownership discovery" "$discovery_status" \
      "Docker resources could not be captured with verified run ownership."
    return "$discovery_status"
  fi
}

run_bounded_resource_creating_checked() {
  local label="$1"
  local timeout_seconds="$2"
  shift 2
  local command_status=0
  local discovery_status=0
  if run_bounded_checked "$label" "$timeout_seconds" \
      "$docker_termination_grace_seconds" "$@"; then
    command_status=0
  else
    command_status=$?
  fi
  if discover_all_owned_resources; then
    discovery_status=0
  else
    discovery_status=$?
    echo "Ownership discovery failed after bounded resource-creating command: $label" >&2
  fi
  if [[ "$command_status" -ne 0 ]]; then
    return "$command_status"
  fi
  if [[ "$discovery_status" -ne 0 ]]; then
    set_failure_context "$label ownership discovery" "$discovery_status" \
      "Docker resources could not be captured with verified run ownership."
    return "$discovery_status"
  fi
}

run_source_compose_build_bounded() {
  local label="$1"
  shift
  verify_generated_ownership_inputs_unchanged || return 1
  verify_anonymous_docker_client || return 1
  run_bounded_resource_creating_checked "$label" "$docker_build_timeout_seconds" \
    env \
      "DESKSEED_BACKEND_PORT=$source_backend_port" \
      "DESKSEED_FRONTEND_PORT=$source_frontend_port" \
      "$docker_cli_path" compose --project-name "$source_project" "${compose_files[@]}" "$@"
}

source_compose() {
  verify_generated_ownership_inputs_unchanged || {
    echo "Generated Docker ownership inputs changed or disappeared during the rehearsal." >&2
    return 1
  }
  verify_anonymous_docker_client || {
    echo "Anonymous Docker client boundary changed during the rehearsal." >&2
    return 1
  }
  DESKSEED_BACKEND_PORT="$source_backend_port" \
  DESKSEED_FRONTEND_PORT="$source_frontend_port" \
    "$docker_cli_path" compose --project-name "$source_project" "${compose_files[@]}" "$@"
}

restore_compose() {
  verify_generated_ownership_inputs_unchanged || {
    echo "Generated Docker ownership inputs changed or disappeared during the rehearsal." >&2
    return 1
  }
  verify_anonymous_docker_client || {
    echo "Anonymous Docker client boundary changed during the rehearsal." >&2
    return 1
  }
  DESKSEED_BACKEND_PORT="$restore_backend_port" \
  DESKSEED_FRONTEND_PORT="$restore_frontend_port" \
  DESKSEED_CORS_ALLOWED_ORIGINS="http://127.0.0.1:$restore_frontend_port" \
    "$docker_cli_path" compose --project-name "$restore_project" "${compose_files[@]}" "$@"
}

source_admin_psql() {
  source_compose exec -T -e "PGPASSWORD=$migration_password" db \
    psql --username "$migration_role" --dbname "$database_name" --set=ON_ERROR_STOP=1 "$@"
}

restore_admin_psql() {
  restore_compose exec -T -e "PGPASSWORD=$migration_password" db \
    psql --username "$migration_role" --dbname "$database_name" --set=ON_ERROR_STOP=1 "$@"
}

source_scalar() {
  source_admin_psql --tuples-only --no-align --quiet --command "$1" | tr -d '[:space:]'
}

restore_scalar() {
  restore_admin_psql --tuples-only --no-align --quiet --command "$1" | tr -d '[:space:]'
}

assert_source_migration_role_restricted() {
  local flags
  flags="$(source_scalar \
    "select concat(rolsuper, ':', rolcreatedb, ':', rolcreaterole) from pg_roles where rolname = '$migration_role'")"
  [[ "$flags" == "f:f:f" ]] || {
    echo "Migration role unexpectedly has cluster administration flags: $flags" >&2
    return 1
  }
}

assert_restore_migration_role_restricted() {
  local flags
  flags="$(restore_scalar \
    "select concat(rolsuper, ':', rolcreatedb, ':', rolcreaterole) from pg_roles where rolname = '$migration_role'")"
  [[ "$flags" == "f:f:f" ]] || {
    echo "Restored migration role unexpectedly has cluster administration flags: $flags" >&2
    return 1
  }
}

create_logical_backup() {
  source_compose exec -T -e "PGPASSWORD=$migration_password" db \
    pg_dump --username "$migration_role" --dbname "$database_name" \
      --format=custom --no-owner --no-privileges >"$backup_file"
}

restore_logical_backup() {
  restore_compose exec -T -e "PGPASSWORD=$migration_password" db \
    pg_restore --username "$migration_role" --dbname "$database_name" \
      --no-owner --no-privileges <"$backup_file"
}

wait_for_database() {
  local stack="$1"
  local attempt
  for attempt in $(seq 1 60); do
    if "$stack" exec -T db pg_isready \
      --username "$migration_role" --dbname "$database_name" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_for_url() {
  local url="$1"
  local attempt
  for attempt in $(seq 1 90); do
    if curl --fail --silent --show-error "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

compose_service_image_id() {
  local stack="$1"
  local service="$2"
  local container_id
  container_id="$("$stack" ps --quiet "$service")"
  [[ -n "$container_id" ]] || return 1
  "$docker_cli_path" container inspect --format '{{.Image}}' "$container_id"
}

latest_migration_version() {
  local path
  local filename
  local version
  local latest=0
  for path in "$repository_root"/backend/src/main/resources/db/migration/V*__*.sql; do
    filename="${path##*/}"
    version="${filename#V}"
    version="${version%%__*}"
    [[ "$version" =~ ^[0-9]+$ ]] || continue
    ((version > latest)) && latest="$version"
  done
  printf '%s' "$latest"
}

configure_source_runtime_role() {
  source_admin_psql --set="runtime_role=$runtime_role" \
    --file /opt/deskseed/postgres/configure-runtime-role.sql || return $?
  source_admin_psql --set="runtime_role=$runtime_role" \
    --file /opt/deskseed/postgres/verify-runtime-role.sql
}

configure_restore_runtime_role() {
  restore_admin_psql \
    --set="migration_role=$migration_role" \
    --set="runtime_role=$runtime_role" \
    --file /opt/deskseed/operations/configure-default-runtime-privileges.sql || return $?
  restore_admin_psql --set="runtime_role=$runtime_role" \
    --file /opt/deskseed/postgres/configure-runtime-role.sql || return $?
  restore_admin_psql --set="runtime_role=$runtime_role" \
    --file /opt/deskseed/postgres/verify-runtime-role.sql
}

configure_default_runtime_privileges() {
  source_admin_psql \
    --set="migration_role=$migration_role" \
    --set="runtime_role=$runtime_role" \
    --file /opt/deskseed/operations/configure-default-runtime-privileges.sql
}

assert_runtime_statement_denied() {
  local sql="$1"
  local label="$2"
  local probe_output="$work_dir/runtime-probe.out"
  if source_compose exec -T -e "PGPASSWORD=$runtime_password" db \
    psql --host 127.0.0.1 --username "$runtime_role" --dbname "$database_name" \
      --set=ON_ERROR_STOP=1 --command "$sql" >"$probe_output" 2>&1; then
    echo "Runtime privilege probe unexpectedly succeeded: $label" >&2
    capture_expected_failure_diagnostic "$label expected permission denial" 1 "$probe_output"
    return $?
  fi
  if ! python3 -c \
    'import pathlib,sys; raise SystemExit("permission denied" not in pathlib.Path(sys.argv[1]).read_text())' \
    "$probe_output"; then
    echo "Runtime privilege probe failed for a reason other than permission denial: $label" >&2
    capture_expected_failure_diagnostic "$label permission-denial assertion" 1 "$probe_output"
    return $?
  fi
  record "$label" PASS "runtime credential was denied"
}

assert_restore_runtime_statement_denied() {
  local sql="$1"
  local label="$2"
  local probe_output="$work_dir/restore-runtime-probe.out"
  if restore_compose exec -T -e "PGPASSWORD=$runtime_password" db \
    psql --host 127.0.0.1 --username "$runtime_role" --dbname "$database_name" \
      --set=ON_ERROR_STOP=1 --command "$sql" >"$probe_output" 2>&1; then
    echo "Restored runtime privilege probe unexpectedly succeeded: $label" >&2
    capture_expected_failure_diagnostic "$label expected permission denial" 1 "$probe_output"
    return $?
  fi
  if ! python3 -c \
    'import pathlib,sys; raise SystemExit("permission denied" not in pathlib.Path(sys.argv[1]).read_text())' \
    "$probe_output"; then
    echo "Restored runtime privilege probe failed for a reason other than permission denial: $label" >&2
    capture_expected_failure_diagnostic "$label permission-denial assertion" 1 "$probe_output"
    return $?
  fi
  record "$label" PASS "restored runtime credential was denied"
}

write_json_request() {
  local output="$1"
  local subject="$2"
  python3 - "$output" "$subject" <<'PY'
import json
import sys

path, subject = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(
        {
            "name": "Operations rehearsal customer",
            "email": "operations-customer@deskseed.test",
            "subject": subject,
            "message": "Synthetic public comment for install and restore verification.",
        },
        handle,
    )
PY
}

submit_public_ticket() {
  local base_url="$1"
  local subject="$2"
  local request_file="$work_dir/request.json"
  local response_file="$work_dir/submitted.json"
  run_checked "write anonymous ticket request fixture" \
    write_json_request "$request_file" "$subject"
  run_checked "submit anonymous ticket request" curl --fail --silent --show-error \
    --header 'Content-Type: application/json' \
    --data @"$request_file" \
    --output "$response_file" \
    "$base_url/api/v1/requests"
  capture_checked_output "parse anonymous ticket number" ticket_number \
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ticketNumber"])' "$response_file"
  capture_checked_output "parse anonymous ticket access token" access_token \
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["accessToken"])' "$response_file"
  if [[ ! "$ticket_number" =~ ^[0-9]+$ ]]; then
    fail_check "validate anonymous ticket number" \
      "Anonymous submission returned a non-numeric ticket number."
  fi
  if [[ -z "$access_token" ]]; then
    fail_check "validate anonymous ticket access token" \
      "Anonymous submission did not return an access token."
  fi
}

verify_public_ticket() {
  local base_url="$1"
  local output="$2"
  run_checked "read anonymous ticket with access token" curl --fail --silent --show-error \
    --header "X-Request-Access-Token: $access_token" \
    --output "$output" \
    "$base_url/api/v1/requests/$ticket_number"
  run_checked "validate anonymous ticket projection" python3 - "$output" "$ticket_number" "$ticket_subject" <<'PY'
import json
import sys

path, expected_number, expected_subject = sys.argv[1:]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
assert payload["ticketNumber"] == int(expected_number)
assert payload["subject"] == expected_subject
assert len(payload["comments"]) == 1
assert payload["comments"][0]["body"] == "Synthetic public comment for install and restore verification."
PY
}

login_and_read_ticket() {
  local base_url="$1"
  local cookie_file="$2"
  local prefix="$3"
  local csrf_file="$work_dir/${prefix}-csrf.json"
  local login_file="$work_dir/${prefix}-login.json"
  local me_file="$work_dir/${prefix}-me.json"
  local ticket_file="$work_dir/${prefix}-agent-ticket.json"
  local csrf_token
  local csrf_header
  local interaction_id

  run_checked "$prefix fetch staff CSRF token" curl --fail --silent --show-error \
    --cookie "$cookie_file" --cookie-jar "$cookie_file" \
    --output "$csrf_file" "$base_url/api/v1/agent/csrf"
  capture_checked_output "$prefix parse staff CSRF token" csrf_token \
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$csrf_file"
  capture_checked_output "$prefix parse staff CSRF header" csrf_header \
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["headerName"])' "$csrf_file"
  run_checked "$prefix write staff login fixture" python3 - "$login_file" "$admin_email" "$admin_password" <<'PY'
import json
import sys

path, email, password = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump({"email": email, "password": password}, handle)
PY
  run_checked "$prefix submit staff login" curl --fail --silent --show-error \
    --cookie "$cookie_file" --cookie-jar "$cookie_file" \
    --header "$csrf_header: $csrf_token" \
    --header 'Content-Type: application/json' \
    --data @"$login_file" \
    --output /dev/null \
    "$base_url/api/v1/agent/session"
  run_checked "$prefix read current staff session" curl --fail --silent --show-error \
    --cookie "$cookie_file" --cookie-jar "$cookie_file" \
    --output "$me_file" "$base_url/api/v1/agent/me"
  run_checked "$prefix validate current staff identity" python3 - "$me_file" "$admin_email" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
assert payload["email"] == sys.argv[2]
assert payload["role"] == "ADMIN"
PY
  capture_checked_output "$prefix generate staff interaction id" interaction_id \
    python3 -c 'import uuid; print(uuid.uuid4())'
  run_checked "$prefix read staff ticket" curl --fail --silent --show-error \
    --cookie "$cookie_file" --cookie-jar "$cookie_file" \
    --header "X-Interaction-Id: $interaction_id" \
    --header 'X-Deskseed-Read-Intent: NAVIGATION' \
    --output "$ticket_file" \
    "$base_url/api/v1/agent/tickets/$ticket_number"
  run_checked "$prefix validate staff ticket projection" python3 - "$ticket_file" "$ticket_number" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
assert payload["ticket"]["ticketNumber"] == int(sys.argv[2])
assert any(comment["visibility"] == "PUBLIC" for comment in payload["comments"])
PY
}

render_evidence() {
  local exit_code="$1"
  local destination="$evidence_file"
  local temporary_evidence=""
  local render_status=0
  local row
  local check
  local result
  local detail
  local git_revision="NOT_AVAILABLE"
  local git_branch="NOT_AVAILABLE"
  local git_working_tree="unknown"
  local host_system="NOT_AVAILABLE"
  local host_architecture="NOT_AVAILABLE"
  local recovery_verified=false
  local upgrade_verified=false
  [[ -n "$destination" ]] || return 0
  if [[ "$evidence_target_prepared" != true ]]; then
    prepare_evidence_destination || return $?
    destination="$evidence_file"
  fi
  temporary_evidence="$(mktemp "$evidence_directory/.${evidence_basename}.tmp.XXXXXX")" || return $?
  chmod 600 "$temporary_evidence"

  if command -v git >/dev/null 2>&1; then
    git_revision="$(git -C "$repository_root" rev-parse HEAD 2>/dev/null || printf NOT_AVAILABLE)"
    git_branch="$(git -C "$repository_root" branch --show-current 2>/dev/null || printf NOT_AVAILABLE)"
    if git -C "$repository_root" status --porcelain >/dev/null 2>&1; then
      if [[ -n "$(git -C "$repository_root" status --porcelain 2>/dev/null)" ]]; then
        git_working_tree="dirty"
      else
        git_working_tree="clean"
      fi
    fi
  fi
  if command -v uname >/dev/null 2>&1; then
    host_system="$(uname -s 2>/dev/null || printf NOT_AVAILABLE)"
    host_architecture="$(uname -m 2>/dev/null || printf NOT_AVAILABLE)"
  fi

  if (
    set -e
    printf '# Operations rehearsal evidence\n\n'
    printf -- '- Status: `%s`\n' "$([[ "$exit_code" -eq 0 ]] && printf PASS || printf FAIL)"
    printf -- '- Started (UTC): `%s`\n' "$start_utc"
    printf -- '- Finished (UTC): `%s`\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf -- '- Git revision: `%s`\n' "$git_revision"
    printf -- '- Git branch: `%s`\n' "$git_branch"
    if [[ "$git_working_tree" == "dirty" ]]; then
      printf -- '- Git working tree: `dirty` (release branch under construction)\n'
    else
      printf -- '- Git working tree: `%s`\n' "$git_working_tree"
    fi
    printf -- '- Mode: `%s`\n' "$mode"
    printf -- '- Command: `./scripts/run-operations-rehearsal.sh%s --evidence-file <path>`\n' "$([[ "$mode" == smoke ]] && printf ' --smoke' || true)"
    printf -- '- Host: `%s %s`\n' "$host_system" "$host_architecture"
    printf -- '- Rehearsal container identity: host UID `%s`, GID `%s` (UID 0 is rejected)\n' "$effective_uid" "$effective_gid"
    printf -- '- Docker Engine: `%s`\n' "$docker_server_version"
    printf -- '- Docker Compose: `%s`\n' "$docker_compose_version"
    printf -- '- Docker client boundary: exact CLI `sha256:%s`; task-owned anonymous mode-0600 config with no credential helper; validated local unix endpoint `sha256:%s`; socket identity `sha256:%s`; daemon identity `sha256:%s`; preexisting user config `%s`.\n' \
      "$docker_cli_fingerprint" \
      "$docker_daemon_endpoint_fingerprint" \
      "$docker_daemon_socket_identity_fingerprint" \
      "$docker_daemon_identity_fingerprint" \
      "$docker_user_config_unchanged"
    printf -- '- Docker environment boundary: inherited cleared/pinned variables `%s`; OpenTelemetry SDK/exporters are disabled to prevent CLI/Compose/Buildx command metadata export; retained proxy variables `%s` are fingerprint-verified and redacted but intentionally preserved because the client is pinned to a unix socket and registry/build fetches are daemon/BuildKit-side. Daemon-side registry mirrors, proxy, and auth remain outside runner control.\n' \
      "$docker_inherited_cleared_override_names" \
      "$docker_inherited_retained_proxy_names"
    printf -- '- Docker command bounds: pull `%ss`; each build/Compose-build `%ss`; timeout sends TERM to the command process group, waits `%ss`, then sends KILL and verifies the entire process group absent even if its leader exited first.\n' \
      "$docker_pull_timeout_seconds" \
      "$docker_build_timeout_seconds" \
      "$docker_termination_grace_seconds"
    printf -- '- Isolation: a mktemp-derived cryptographic run marker labels every created container, network, volume, and image; exact identities are captured before cleanup, and zero run/project artifacts are verified before this evidence is rendered.\n'
    printf -- '- Secrets: when allocated, credential-bearing files stay under a mode-0700 temporary directory; command diagnostics are bounded and generically redacted before terminal/evidence output. Generated values traverse process env/argv during this local rehearsal, so privileged same-host observation is out of scope.\n\n'
    printf '| Check | Result | Evidence |\n'
    printf '|---|---|---|\n'
    for row in "${event_rows[@]}"; do
      IFS=$'\t' read -r check result detail <<<"$row"
      if [[ "$check" == "RPO boundary" && "$result" == "PASS" ]]; then
        recovery_verified=true
      fi
      if [[ "$check" == "Flyway upgrade" && "$result" == "PASS" ]]; then
        upgrade_verified=true
      fi
      detail="${detail//|/\\|}"
      printf '| %s | %s | %s |\n' "$check" "$result" "$detail"
    done
    if [[ "$exit_code" -ne 0 ]]; then
      printf '| overall | FAIL | stopped during `%s`; bounded diagnostic follows when available |\n' "$current_stage"
    fi
    if [[ "$exit_code" -ne 0 ]]; then
      printf '\n## Failure context\n\n'
      printf -- '- Failing subcommand: `%s`\n' "${failure_command:-$current_stage}"
      printf -- '- Exit status: `%s`\n' "${failure_exit_code:-$exit_code}"
      if [[ -n "$failure_diagnostic" ]]; then
        printf '\nBounded, secret-redacted diagnostic (at most 64 lines and 8 KiB per captured source):\n\n'
        while IFS= read -r diagnostic_line || [[ -n "$diagnostic_line" ]]; do
          printf '    %s\n' "$diagnostic_line"
        done <<<"$failure_diagnostic"
      else
        printf '\nNo subprocess diagnostic was available; use the recorded stage and exit status for reproduction.\n'
      fi
    fi
    printf '\n## RPO/RTO interpretation\n\n'
    if [[ "$recovery_verified" == true ]]; then
      printf 'This rehearsal uses a single logical `pg_dump` snapshot and does not configure WAL archiving or point-in-time recovery. '
      printf 'The recovered ticket and audit counts prove recovery through the snapshot only. '
      printf 'Operational worst-case RPO is therefore the backup interval plus changes after the dump snapshot begins; it is not zero. '
      printf 'The measured recovery-validation duration is a local RTO observation, not an SLA.\n\n'
    else
      printf 'This run does not prove recovery, RPO, or RTO because the `RPO boundary` check did not pass. '
      printf 'That interpretation is applicable only after the logical `pg_dump`, restored data-parity, and post-restore application checks pass. '
      printf 'The intended method does not configure WAL archiving or point-in-time recovery; no recovery outcome is claimed by this evidence.\n\n'
    fi
    printf '## Scope limitation\n\n'
    if [[ "$upgrade_verified" == true ]]; then
      printf 'There is no prior tagged release image. The upgrade proof gives the runtime role default read/append startup privileges, runs the current application against Flyway target V11 with Hibernate validation enabled, creates data, then advances that same volume to the repository latest migration. '
    else
      printf 'There is no prior tagged release image. A passing rehearsal is intended to give the runtime role default read/append startup privileges, run the current application against Flyway target V11 with Hibernate validation enabled, create data, and then advance that same volume to the repository latest migration. '
      printf 'Because the `Flyway upgrade` check did not pass, this evidence does not prove that upgrade path. '
    fi
    printf 'Full mode pulls base images and bypasses build-cache reuse for its build steps; it does not delete Docker build cache. It is still a same-host rehearsal rather than an independent second-machine certification. '
    printf 'Image acquisition intentionally uses an anonymous task-owned Docker client config against the already validated local unix daemon; private-registry credentials and credential helpers are outside this public-image rehearsal. '
    printf 'Docker volumes expose no immutable removal ID, so cleanup compares their full captured identity immediately before the name-based Docker API call; a malicious peer with the same Docker-daemon authority can still race that check and is outside this single-principal rehearsal. '
    printf 'The split-role proof uses the private rehearsal overlay; base Compose does not create or wire migration/runtime roles, and this repository provides no TLS-enabled production deployment manifest.\n'
    printf '\n## Source provenance\n\n'
    printf -- '- `%s  operations runner/Compose/runtime-role inputs captured before build`\n' \
      "${operations_input_fingerprint:-NOT_CAPTURED}"
    printf -- '- `%s  backend Docker build context captured immediately before build (.dockerignore applied)`\n' \
      "${backend_build_context_fingerprint:-NOT_CAPTURED}"
    printf -- '- `%s  frontend Docker build context captured immediately before build (.dockerignore applied)`\n' \
      "${frontend_build_context_fingerprint:-NOT_CAPTURED}"
    printf -- '- `%s  generated ownership overlay (content derived from this runner and the run-scoped marker; removed with the secret workspace)`\n' \
      "${ownership_overlay_fingerprint:-NOT_CAPTURED}"
    printf -- '- `%s  generated marker-labeled PostgreSQL wrapper Dockerfile (content derived from this runner; removed with the secret workspace)`\n' \
      "${postgres_wrapper_dockerfile_fingerprint:-NOT_CAPTURED}"
    for evidence_source in \
      "$repository_root/scripts/run-operations-rehearsal.sh" \
      "$repository_root/compose.yaml" \
      "$repository_root/compose.e2e.yaml" \
      "$repository_root/scripts/operations/compose.rehearsal.yaml" \
      "$repository_root/scripts/operations/postgres-init-runtime-role.sh" \
      "$repository_root/scripts/operations/configure-default-runtime-privileges.sql" \
      "$repository_root/scripts/postgres/configure-runtime-role.sql" \
      "$repository_root/scripts/postgres/verify-runtime-role.sql" \
      "$repository_root/backend/Dockerfile" \
      "$repository_root/frontend/Dockerfile" \
      "$repository_root/backend/src/main/resources/application.yml" \
      "$repository_root/backend/src/main/resources/db/migration"/V*.sql; do
      printf -- '- `%s  %s`\n' \
        "$(sha256_file "$evidence_source")" \
        "${evidence_source#"$repository_root"/}"
    done
  ) >"$temporary_evidence"; then
    render_status=0
  else
    render_status=$?
  fi

  if [[ "$render_status" -ne 0 ]]; then
    rm -f -- "$temporary_evidence"
    return "$render_status"
  fi
  if [[ -L "$destination" || ( -e "$destination" && ! -f "$destination" ) || ( -e "$destination" && ! -O "$destination" ) ]]; then
    echo "Evidence target changed to an unsafe file during the rehearsal: $destination" >&2
    rm -f -- "$temporary_evidence"
    return 2
  fi
  if mv -f -- "$temporary_evidence" "$destination"; then
    :
  else
    render_status=$?
    rm -f -- "$temporary_evidence"
    return "$render_status"
  fi
}

verify_zero_artifacts() {
  local project
  local output
  local image_name
  local image_id
  local record
  local recorded_project
  local resource_id
  local resource_name
  local fingerprint
  local verification_failed=0

  if ! "$docker_cli_path" info >/dev/null 2>&1; then
    echo "Cleanup verification could not reach the Docker daemon." >&2
    return 1
  fi

  for project in "$source_project" "$restore_project"; do
    if ! output="$("$docker_cli_path" ps --all \
      --filter "label=com.docker.compose.project=$project" \
      --format '{{.ID}}')"; then
      echo "Cleanup verification could not list containers for $project." >&2
      verification_failed=1
    elif [[ -n "$output" ]]; then
      echo "Cleanup left a container for $project." >&2
      verification_failed=1
    fi

    if ! output="$("$docker_cli_path" volume ls \
      --filter "label=com.docker.compose.project=$project" \
      --format '{{.Name}}')"; then
      echo "Cleanup verification could not list volumes for $project." >&2
      verification_failed=1
    elif [[ -n "$output" ]]; then
      echo "Cleanup left a volume for $project." >&2
      verification_failed=1
    fi

    if ! output="$("$docker_cli_path" network ls \
      --filter "label=com.docker.compose.project=$project" \
      --format '{{.Name}}')"; then
      echo "Cleanup verification could not list networks for $project." >&2
      verification_failed=1
    elif [[ -n "$output" ]]; then
      echo "Cleanup left a network for $project." >&2
      verification_failed=1
    fi
  done

  if ! output="$("$docker_cli_path" ps --all --quiet --no-trunc \
      --filter "label=$resource_owner_label_key=$run_marker")"; then
    echo "Cleanup verification could not list run-labeled containers." >&2
    verification_failed=1
  elif [[ -n "$output" ]]; then
    echo "Cleanup left one or more run-labeled containers." >&2
    verification_failed=1
  fi
  if ! output="$("$docker_cli_path" network ls --quiet --no-trunc \
      --filter "label=$resource_owner_label_key=$run_marker")"; then
    echo "Cleanup verification could not list run-labeled networks." >&2
    verification_failed=1
  elif [[ -n "$output" ]]; then
    echo "Cleanup left one or more run-labeled networks." >&2
    verification_failed=1
  fi
  if ! output="$("$docker_cli_path" volume ls --quiet \
      --filter "label=$resource_owner_label_key=$run_marker")"; then
    echo "Cleanup verification could not list run-labeled volumes." >&2
    verification_failed=1
  elif [[ -n "$output" ]]; then
    echo "Cleanup left one or more run-labeled volumes." >&2
    verification_failed=1
  fi
  if ! output="$("$docker_cli_path" image ls --quiet --no-trunc \
      --filter "label=$resource_owner_label_key=$run_marker")"; then
    echo "Cleanup verification could not list run-labeled images." >&2
    verification_failed=1
  elif [[ -n "$output" ]]; then
    echo "Cleanup left one or more run-labeled images." >&2
    verification_failed=1
  fi

  for record in "${owned_container_records[@]}"; do
    IFS=$'\t' read -r recorded_project resource_id resource_name <<<"$record"
    if "$docker_cli_path" container inspect "$resource_id" >/dev/null 2>&1; then
      echo "Cleanup left captured container ID $resource_id." >&2
      verification_failed=1
    fi
    if "$docker_cli_path" container inspect "$resource_name" >/dev/null 2>&1; then
      echo "Cleanup container name is occupied after cleanup: $resource_name." >&2
      verification_failed=1
    fi
  done
  for record in "${owned_network_records[@]}"; do
    IFS=$'\t' read -r recorded_project resource_id resource_name <<<"$record"
    if "$docker_cli_path" network inspect "$resource_id" >/dev/null 2>&1; then
      echo "Cleanup left captured network ID $resource_id." >&2
      verification_failed=1
    fi
    if "$docker_cli_path" network inspect "$resource_name" >/dev/null 2>&1; then
      echo "Cleanup network name is occupied after cleanup: $resource_name." >&2
      verification_failed=1
    fi
  done
  for record in "${owned_volume_records[@]}"; do
    IFS=$'\t' read -r recorded_project resource_name fingerprint <<<"$record"
    if "$docker_cli_path" volume inspect "$resource_name" >/dev/null 2>&1; then
      echo "Cleanup volume name is still occupied after cleanup: $resource_name." >&2
      verification_failed=1
    fi
  done
  for image_id in "${owned_image_ids[@]}"; do
    if "$docker_cli_path" image inspect "$image_id" >/dev/null 2>&1; then
      echo "Cleanup left captured image ID $image_id." >&2
      verification_failed=1
    fi
  done

  for image_name in \
    "$DESKSEED_REHEARSAL_BACKEND_IMAGE" \
    "$DESKSEED_REHEARSAL_FRONTEND_IMAGE" \
    "$DESKSEED_REHEARSAL_POSTGRES_IMAGE"; do
    if "$docker_cli_path" image inspect "$image_name" >/dev/null 2>&1; then
      echo "Cleanup left run-scoped image $image_name." >&2
      verification_failed=1
    fi
  done

  return "$verification_failed"
}

cleanup_owned_containers() {
  local record
  local project
  local container_id
  local container_name
  local actual_id
  local actual_name
  local actual_owner
  local actual_project
  local replacement_id
  local cleanup_failed=0

  for record in "${owned_container_records[@]}"; do
    IFS=$'\t' read -r project container_id container_name <<<"$record"
    if "$docker_cli_path" container inspect "$container_id" >/dev/null 2>&1; then
      actual_id="$("$docker_cli_path" container inspect --format '{{.Id}}' "$container_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_name="$("$docker_cli_path" container inspect --format '{{.Name}}' "$container_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_name="${actual_name#/}"
      actual_owner="$("$docker_cli_path" container inspect --format '{{index .Config.Labels "dev.deskseed.operations.run"}}' "$container_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_project="$("$docker_cli_path" container inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$container_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      if [[ "$actual_id" != "$container_id" || "$actual_name" != "$container_name" \
         || "$actual_owner" != "$run_marker" || "$actual_project" != "$project" ]]; then
        echo "Refusing to remove a captured container whose identity or ownership changed: $container_name." >&2
        cleanup_failed=1
        continue
      fi
      if ! "$docker_cli_path" container rm --force --volumes "$container_id" >/dev/null 2>&1; then
        echo "Failed to remove owned container ID $container_id." >&2
        cleanup_failed=1
      elif "$docker_cli_path" container inspect "$container_id" >/dev/null 2>&1 || ! "$docker_cli_path" info >/dev/null 2>&1; then
        echo "Could not verify owned container ID absence: $container_id." >&2
        cleanup_failed=1
      fi
    elif ! "$docker_cli_path" info >/dev/null 2>&1; then
      echo "Could not prove captured container absence because Docker is unavailable." >&2
      cleanup_failed=1
    fi

    if replacement_id="$("$docker_cli_path" container inspect --format '{{.Id}}' "$container_name" 2>/dev/null)"; then
      local successor_record
      local successor_project
      local successor_id
      local successor_name
      local replacement_is_tracked_successor=false
      for successor_record in "${owned_container_records[@]}"; do
        IFS=$'\t' read -r successor_project successor_id successor_name <<<"$successor_record"
        if [[ "$successor_project" == "$project" && "$successor_id" == "$replacement_id" \
           && "$successor_name" == "$container_name" ]]; then
          replacement_is_tracked_successor=true
          break
        fi
      done
      if [[ "$replacement_is_tracked_successor" == true ]]; then
        :
      elif [[ "$replacement_id" != "$container_id" ]]; then
        echo "Preserved replacement container at captured name $container_name; cleanup is failed closed." >&2
        cleanup_failed=1
      else
        echo "Captured container name still resolves to its owned ID after cleanup: $container_name." >&2
        cleanup_failed=1
      fi
    elif ! "$docker_cli_path" info >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  done
  return "$cleanup_failed"
}

cleanup_owned_networks() {
  local record
  local project
  local network_id
  local network_name
  local actual_id
  local actual_name
  local actual_owner
  local actual_project
  local replacement_id
  local cleanup_failed=0

  for record in "${owned_network_records[@]}"; do
    IFS=$'\t' read -r project network_id network_name <<<"$record"
    if "$docker_cli_path" network inspect "$network_id" >/dev/null 2>&1; then
      actual_id="$("$docker_cli_path" network inspect --format '{{.Id}}' "$network_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_name="$("$docker_cli_path" network inspect --format '{{.Name}}' "$network_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_owner="$("$docker_cli_path" network inspect --format '{{index .Labels "dev.deskseed.operations.run"}}' "$network_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_project="$("$docker_cli_path" network inspect --format '{{index .Labels "com.docker.compose.project"}}' "$network_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      if [[ "$actual_id" != "$network_id" || "$actual_name" != "$network_name" \
         || "$actual_owner" != "$run_marker" || "$actual_project" != "$project" ]]; then
        echo "Refusing to remove a captured network whose identity or ownership changed: $network_name." >&2
        cleanup_failed=1
        continue
      fi
      if ! "$docker_cli_path" network rm "$network_id" >/dev/null 2>&1; then
        echo "Failed to remove owned network ID $network_id." >&2
        cleanup_failed=1
      elif "$docker_cli_path" network inspect "$network_id" >/dev/null 2>&1 || ! "$docker_cli_path" info >/dev/null 2>&1; then
        echo "Could not verify owned network ID absence: $network_id." >&2
        cleanup_failed=1
      fi
    elif ! "$docker_cli_path" info >/dev/null 2>&1; then
      cleanup_failed=1
    fi

    if replacement_id="$("$docker_cli_path" network inspect --format '{{.Id}}' "$network_name" 2>/dev/null)"; then
      local successor_record
      local successor_project
      local successor_id
      local successor_name
      local replacement_is_tracked_successor=false
      for successor_record in "${owned_network_records[@]}"; do
        IFS=$'\t' read -r successor_project successor_id successor_name <<<"$successor_record"
        if [[ "$successor_project" == "$project" && "$successor_id" == "$replacement_id" \
           && "$successor_name" == "$network_name" ]]; then
          replacement_is_tracked_successor=true
          break
        fi
      done
      if [[ "$replacement_is_tracked_successor" == true ]]; then
        :
      elif [[ "$replacement_id" != "$network_id" ]]; then
        echo "Preserved replacement network at captured name $network_name; cleanup is failed closed." >&2
        cleanup_failed=1
      else
        echo "Captured network name still resolves to its owned ID after cleanup: $network_name." >&2
        cleanup_failed=1
      fi
    elif ! "$docker_cli_path" info >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  done
  return "$cleanup_failed"
}

cleanup_owned_volumes() {
  local record
  local project
  local volume_name
  local expected_fingerprint
  local actual_fingerprint
  local actual_owner
  local actual_project
  local cleanup_failed=0

  for record in "${owned_volume_records[@]}"; do
    IFS=$'\t' read -r project volume_name expected_fingerprint <<<"$record"
    if "$docker_cli_path" volume inspect "$volume_name" >/dev/null 2>&1; then
      actual_owner="$("$docker_cli_path" volume inspect --format '{{index .Labels "dev.deskseed.operations.run"}}' "$volume_name" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_project="$("$docker_cli_path" volume inspect --format '{{index .Labels "com.docker.compose.project"}}' "$volume_name" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_fingerprint="$(volume_identity_fingerprint "$volume_name")" || {
        cleanup_failed=1
        continue
      }
      if [[ "$actual_owner" != "$run_marker" || "$actual_project" != "$project" \
         || "$actual_fingerprint" != "$expected_fingerprint" ]]; then
        echo "Preserved replacement or ownership-mismatched volume $volume_name; cleanup is failed closed." >&2
        cleanup_failed=1
        continue
      fi
      # Docker volumes have no immutable removal handle. The complete inspected
      # identity is checked immediately before this name-based API call. A
      # malicious peer controlling the same Docker daemon can still race this
      # check; daemon-principal isolation is an operational prerequisite.
      if ! "$docker_cli_path" volume rm "$volume_name" >/dev/null 2>&1; then
        echo "Failed to remove verified owned volume $volume_name." >&2
        cleanup_failed=1
      elif "$docker_cli_path" volume inspect "$volume_name" >/dev/null 2>&1 || ! "$docker_cli_path" info >/dev/null 2>&1; then
        echo "Could not verify owned volume absence: $volume_name." >&2
        cleanup_failed=1
      fi
    elif ! "$docker_cli_path" info >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  done
  return "$cleanup_failed"
}

cleanup_owned_images() {
  local image_id
  local image_name
  local actual_id
  local actual_owner
  local cleanup_failed=0

  for image_id in "${owned_image_ids[@]}"; do
    if "$docker_cli_path" image inspect "$image_id" >/dev/null 2>&1; then
      actual_id="$("$docker_cli_path" image inspect --format '{{.Id}}' "$image_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_owner="$("$docker_cli_path" image inspect --format '{{index .Config.Labels "dev.deskseed.operations.run"}}' "$image_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      if [[ "$actual_id" != "$image_id" || "$actual_owner" != "$run_marker" ]]; then
        echo "Refusing to remove captured image whose exact ID no longer proves run ownership: $image_id." >&2
        cleanup_failed=1
        continue
      fi
      if ! "$docker_cli_path" image rm "$image_id" >/dev/null 2>&1; then
        echo "Failed to remove owned image ID $image_id." >&2
        cleanup_failed=1
      elif "$docker_cli_path" image inspect "$image_id" >/dev/null 2>&1 || ! "$docker_cli_path" info >/dev/null 2>&1; then
        echo "Could not verify owned image ID absence: $image_id." >&2
        cleanup_failed=1
      fi
    elif ! "$docker_cli_path" info >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  done

  for image_name in \
    "$DESKSEED_REHEARSAL_BACKEND_IMAGE" \
    "$DESKSEED_REHEARSAL_FRONTEND_IMAGE" \
    "$DESKSEED_REHEARSAL_POSTGRES_IMAGE"; do
    [[ -n "$image_name" ]] || continue
    if actual_id="$("$docker_cli_path" image inspect --format '{{.Id}}' "$image_name" 2>/dev/null)"; then
      echo "Preserved replacement or incompletely removed image tag $image_name ($actual_id); cleanup is failed closed." >&2
      cleanup_failed=1
    elif ! "$docker_cli_path" info >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  done
  return "$cleanup_failed"
}

cleanup_resources() {
  local cleanup_failed=0
  local cleanup_boundary_verified=false

  if [[ "$docker_preflight_passed" == true && "$resource_identity_initialized" == true ]]; then
    if ! verify_anonymous_docker_client; then
      echo "Cleanup refused Docker discovery/removal because the pinned client, socket, or daemon identity changed." >&2
      cleanup_failed=1
    else
      cleanup_boundary_verified=true
      if ! discover_all_owned_resources; then
        echo "Cleanup could not discover every run-labeled Docker resource." >&2
        cleanup_failed=1
      fi
      cleanup_owned_containers || cleanup_failed=1
      cleanup_owned_networks || cleanup_failed=1
      cleanup_owned_volumes || cleanup_failed=1
      cleanup_owned_images || cleanup_failed=1

      if ! verify_anonymous_docker_client; then
        echo "Cleanup refused zero-artifact inspection because the pinned daemon boundary changed during removal." >&2
        cleanup_failed=1
        cleanup_boundary_verified=false
      elif ! verify_zero_artifacts; then
        cleanup_failed=1
      fi

      if [[ "$cleanup_boundary_verified" == true ]] \
        && ! verify_anonymous_docker_client; then
        echo "Cleanup could not prove the pinned daemon boundary remained stable after zero-artifact inspection." >&2
        cleanup_failed=1
      fi
    fi
  fi

  if [[ -z "$work_dir" ]]; then
    :
  elif [[ "$work_dir" == *deskseed-operations.* ]]; then
    if [[ -e "$work_dir" ]] && ! rm -rf -- "$work_dir"; then
      echo "Failed to remove the secret temporary directory." >&2
      cleanup_failed=1
    fi
  else
    echo "Refusing to remove an unexpected temporary-directory path." >&2
    cleanup_failed=1
  fi

  if [[ -n "$work_dir" && ( -e "$work_dir" || -L "$work_dir" ) ]]; then
    echo "Cleanup left the secret temporary directory." >&2
    cleanup_failed=1
  fi

  return "$cleanup_failed"
}

on_exit() {
  local command_exit_code=$?
  local final_exit_code="$command_exit_code"
  local failure_stage="$current_stage"
  trap - EXIT ERR
  trap '' HUP INT TERM
  set +e
  if [[ -n "$failure_diagnostic_file" && -s "$failure_diagnostic_file" ]]; then
    append_failure_diagnostic_file "Failing subcommand output" "$failure_diagnostic_file"
  fi
  if [[ "$command_exit_code" -ne 0 ]]; then
    if [[ "$source_started" == true && -n "$work_dir" && -d "$work_dir" ]]; then
      capture_owned_project_logs "$source_project" "Source stack logs"
    fi
    if [[ "$restore_started" == true && -n "$work_dir" && -d "$work_dir" ]]; then
      capture_owned_project_logs "$restore_project" "Restore stack logs"
    fi
  fi

  current_stage="cleanup"
  if cleanup_resources; then
    if [[ "$docker_preflight_passed" == true ]]; then
      record "cleanup verification" PASS "source/restore containers, networks, volumes, run-scoped images, and the secret workspace including the anonymous Docker config are absent"
    else
      record "cleanup verification" PASS "rehearsal resources were not allocated before preflight completed; no secret temporary directory remains"
    fi
  else
    record "cleanup verification" FAIL "one or more resources or the secret temporary directory could not be removed or verified absent"
    set_failure_context "cleanup verification" 1
    final_exit_code=1
  fi

  if [[ "$docker_user_config_fingerprint_before" != NOT_CHECKED ]]; then
    if verify_user_docker_config_unchanged; then
      docker_user_config_unchanged="VERIFIED_UNCHANGED"
      record "user Docker config immutability" PASS \
        "the preexisting Docker client config remained byte/metadata-identical; the rehearsal used only its deleted task-owned anonymous config"
    else
      docker_user_config_unchanged="CHANGED"
      record "user Docker config immutability" FAIL \
        "the preexisting Docker client config identity changed during the rehearsal; PASS evidence is refused"
      set_failure_context "user Docker config immutability" 1
      final_exit_code=1
    fi
  fi

  if [[ "$command_exit_code" -eq 0 && "$final_exit_code" -eq 0 ]]; then
    current_stage="post-cleanup source provenance verification"
    if ! verify_rehearsal_inputs_unchanged; then
      set_failure_context "post-cleanup operations-input fingerprint assertion" 1 \
        "Operations inputs changed during cleanup; PASS evidence was refused."
      record "final source freeze" FAIL \
        "operations runner/Compose/runtime-role inputs changed after the application checks"
      final_exit_code=1
    elif ! verify_build_context_unchanged; then
      set_failure_context "post-cleanup build-context fingerprint assertion" 1 \
        "Backend or frontend build context changed during cleanup; PASS evidence was refused."
      record "final source freeze" FAIL \
        "backend or frontend Docker build context changed after the application checks"
      final_exit_code=1
    else
      record "final source freeze" PASS \
        "captured operations inputs and backend/frontend build contexts still match after cleanup and immediately before evidence rendering"
    fi
  fi

  if [[ "$command_exit_code" -ne 0 ]]; then
    current_stage="$failure_stage"
  fi
  if ! render_evidence "$final_exit_code"; then
    echo "Failed to render operations evidence." >&2
    final_exit_code=1
  fi
  exit "$final_exit_code"
}
main() {
local command_path
current_stage="evidence target preflight"
prepare_evidence_destination || exit $?
trap on_exit EXIT
trap on_error ERR
trap 'on_signal SIGHUP 129' HUP
trap 'on_signal SIGINT 130' INT
trap 'on_signal SIGTERM 143' TERM

current_stage="required command preflight"
for command_name in curl docker git id python3; do
  if [[ "$command_name" == docker ]]; then
    if [[ "$(type -t docker 2>/dev/null || true)" == file ]]; then
      command_path="$(type -P docker || true)"
    else
      command_path=""
    fi
  else
    command_path="$(command -v "$command_name" 2>/dev/null || true)"
  fi
  if [[ -z "$command_path" ]]; then
    set_failure_context "required command preflight: $command_name" 127 \
      "Required command is unavailable: $command_name"
    echo "Required command is unavailable: $command_name" >&2
    exit 127
  fi
done

current_stage="non-root host user preflight"
effective_uid="$(id -u)"
effective_gid="$(id -g)"
if [[ "$effective_uid" == 0 ]]; then
  set_failure_context "non-root host user preflight" 2 \
    "The operations rehearsal rejects host UID 0."
  echo "Operations rehearsal must run as a non-root host user so the backend non-root boundary is exercised." >&2
  exit 2
fi

current_stage="Docker timeout configuration preflight"
if ! validate_docker_timeout_configuration; then
  set_failure_context "Docker timeout configuration preflight" 2 \
    "Docker pull/build/grace timeout values are outside the supported bounded ranges."
  echo "Docker timeout configuration is invalid." >&2
  exit 2
fi

current_stage="secret workspace allocation"
umask 077
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/deskseed-operations.XXXXXX")"
redaction_values_file="$work_dir/redaction-values"
: >"$redaction_values_file"
chmod 600 "$redaction_values_file"
for initial_docker_boundary_value in \
  "${DOCKER_CONFIG:-}" \
  "${DOCKER_HOST:-}" \
  "${HOME:-}/.docker"; do
  register_redaction_value "$initial_docker_boundary_value"
done
current_stage="Docker inherited override boundary"
run_stateful_checked "redact and clear inherited Docker registry/build overrides" \
  capture_and_clear_docker_remote_overrides
current_stage="Docker client boundary capture"
run_stateful_checked "capture current local Docker client boundary" \
  capture_docker_client_boundary
current_stage="anonymous Docker client initialization"
run_stateful_checked "initialize anonymous task-owned Docker client" \
  initialize_anonymous_docker_client
docker_preflight_passed=true
record "Docker client boundary" PASS \
  "validated exact Docker CLI plus local unix socket/daemon identity fingerprints; inherited auth/build selectors are cleared or pinned; task-owned mode-0600 anonymous config excludes credential helpers and preserves Compose/Buildx plugin identities; user config is unchanged"
run_stateful_checked "initialize run-scoped Docker identity" initialize_resource_identity
current_stage="run-scoped Docker resource preflight"
run_checked "verify run-scoped Docker names and labels are absent" assert_resource_names_absent
record "resource ownership preflight" PASS \
  "cryptographically random run marker, both Compose projects, resource names, image tags, and ownership labels were absent before allocation"
admin_password_file="$work_dir/first-admin-password"
admin_cookie_file="$work_dir/admin.cookies"
restore_cookie_file="$work_dir/restore-admin.cookies"
backup_file="$work_dir/deskseed.dump"
for docker_boundary_value in \
  "$docker_cli_path" \
  "$docker_daemon_endpoint" \
  "${docker_daemon_endpoint#unix://}" \
  "$docker_daemon_identity" \
  "$docker_original_config_directory" \
  "$docker_user_config_file" \
  "$docker_compose_plugin_path" \
  "$docker_buildx_plugin_path" \
  "$docker_client_config_directory"; do
  register_redaction_value "$docker_boundary_value"
done

capture_checked_output "generate migration-role password" migration_password \
  python3 -c 'import secrets; print(secrets.token_urlsafe(36))'
capture_checked_output "generate database-bootstrap password" bootstrap_password \
  python3 -c 'import secrets; print(secrets.token_urlsafe(36))'
capture_checked_output "generate runtime-role password" runtime_password \
  python3 -c 'import secrets; print(secrets.token_urlsafe(36))'
capture_checked_output "generate bootstrap-admin password component" admin_password \
  python3 -c 'import secrets; print(f"Ops-{secrets.token_urlsafe(18)}-9aA!")'
capture_checked_output "generate access-audit key" audit_key \
  python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())'
capture_checked_output "generate agent cursor key" agent_cursor_key \
  python3 -c 'import secrets; print(secrets.token_urlsafe(48))'
capture_checked_output "generate audit cursor key" audit_cursor_key \
  python3 -c 'import secrets; print(secrets.token_urlsafe(48))'
for generated_secret in \
  "$bootstrap_password" "$migration_password" "$runtime_password" "$admin_password" "$audit_key" \
  "$agent_cursor_key" "$audit_cursor_key"; do
  register_redaction_value "$generated_secret"
done
printf '%s' "$admin_password" >"$admin_password_file"
chmod 600 "$admin_password_file"

export POSTGRES_DB="$database_name"
export POSTGRES_USER="$bootstrap_role"
export POSTGRES_PASSWORD="$bootstrap_password"
export DATABASE_BOOTSTRAP_USERNAME="$bootstrap_role"
export DATABASE_BOOTSTRAP_PASSWORD="$bootstrap_password"
export DATABASE_MIGRATION_USERNAME="$migration_role"
export DATABASE_MIGRATION_PASSWORD="$migration_password"
export DATABASE_RUNTIME_USERNAME="$runtime_role"
export DATABASE_RUNTIME_PASSWORD="$runtime_password"
export DESKSEED_ACCESS_AUDIT_KEY_LOCAL_V1="$audit_key"
export DESKSEED_AGENT_TICKET_CURSOR_SIGNING_KEY="$agent_cursor_key"
export DESKSEED_AUDIT_CURSOR_SIGNING_KEY="$audit_cursor_key"
export DESKSEED_BOOTSTRAP_ADMIN_EMAIL="$admin_email"
export DESKSEED_BOOTSTRAP_ADMIN_DISPLAY_NAME="Operations rehearsal admin"
export DESKSEED_BOOTSTRAP_ADMIN_PASSWORD_FILE="$admin_password_file"
export DESKSEED_E2E_CONTAINER_UID="$effective_uid"
export DESKSEED_E2E_CONTAINER_GID="$effective_gid"
export DESKSEED_CORS_ALLOWED_ORIGINS="http://127.0.0.1:$source_frontend_port"
export DESKSEED_REHEARSAL_BACKEND_IMAGE
export DESKSEED_REHEARSAL_FRONTEND_IMAGE
export DESKSEED_REHEARSAL_POSTGRES_IMAGE
export DESKSEED_REHEARSAL_FLYWAY_TARGET=11
export DESKSEED_REHEARSAL_DDL_AUTO=validate
export DESKSEED_BOOTSTRAP_ADMIN_ENABLED=false

current_stage="host port preflight"
run_checked "host port availability probe" python3 - \
  "$source_backend_port" "$source_frontend_port" "$restore_backend_port" "$restore_frontend_port" <<'PY'
import socket
import sys

for raw_port in sys.argv[1:]:
    port = int(raw_port)
    if not 1 <= port <= 65535:
        raise SystemExit(f"invalid port: {port}")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.25)
        if probe.connect_ex(("127.0.0.1", port)) == 0:
            raise SystemExit(f"port is already accepting connections: {port}")
PY
record "prerequisites" PASS "Docker Engine $docker_server_version, Compose $docker_compose_version, Buildx, curl, git, id, and Python 3 are available through the anonymous client boundary; rehearsal ports are free; host UID $effective_uid/GID $effective_gid is non-root"

current_stage="migration inventory"
capture_checked_output "discover latest Flyway migration" latest_version latest_migration_version
if [[ ! "$latest_version" =~ ^[0-9]+$ || "$latest_version" -le 11 ]]; then
  fail_check "migration inventory" \
    "The latest migration must be a numeric version newer than V11 for this rehearsal."
fi
record "migration inventory" PASS "upgrade path is V11 to V$latest_version"

current_stage="clean image build"
capture_checked_output "capture operations-input fingerprint" operations_input_fingerprint \
  rehearsal_input_fingerprint
run_checked "verify generated ownership inputs before image build" \
  verify_generated_ownership_inputs_unchanged
run_checked "verify anonymous Docker client before image acquisition" \
  verify_anonymous_docker_client
if [[ "$mode" == full ]]; then
  run_bounded_checked "docker pull PostgreSQL base image" \
    "$docker_pull_timeout_seconds" "$docker_termination_grace_seconds" \
    "$docker_cli_path" pull "$postgres_base_image"
else
  if ! "$docker_cli_path" image inspect "$postgres_base_image" >/dev/null 2>&1; then
    run_bounded_checked "docker pull PostgreSQL base image" \
      "$docker_pull_timeout_seconds" "$docker_termination_grace_seconds" \
      "$docker_cli_path" pull "$postgres_base_image"
  fi
fi
run_checked "verify operations inputs after PostgreSQL pull" verify_rehearsal_inputs_unchanged
capture_checked_output "capture backend build-context fingerprint" backend_build_context_fingerprint \
  build_context_fingerprint "$repository_root" backend
capture_checked_output "capture frontend build-context fingerprint" frontend_build_context_fingerprint \
  build_context_fingerprint "$repository_root/frontend" frontend
if [[ "$mode" == full ]]; then
  run_source_compose_build_bounded "Docker Compose no-cache backend/frontend build" \
    build --pull --no-cache backend frontend
  run_checked "verify operations inputs after no-cache build" verify_rehearsal_inputs_unchanged
  run_checked "verify Docker build contexts after no-cache build" verify_build_context_unchanged
  record "no-cache build" PASS "PostgreSQL and build base images were pulled; backend/frontend build steps bypassed cache reuse with --no-cache; existing Docker build cache was not deleted"
else
  run_source_compose_build_bounded "Docker Compose backend/frontend build" \
    build backend frontend
  run_checked "verify operations inputs after cached build" verify_rehearsal_inputs_unchanged
  run_checked "verify Docker build contexts after cached build" verify_build_context_unchanged
  record "smoke build" PASS "backend/frontend built with the local Docker layer cache allowed"
fi
run_checked "verify anonymous Docker client before PostgreSQL wrapper build" \
  verify_anonymous_docker_client
run_bounded_resource_creating_checked "build marker-labeled run-scoped PostgreSQL image" \
  "$docker_build_timeout_seconds" \
  "$docker_cli_path" build \
    --build-arg "POSTGRES_BASE_IMAGE=$postgres_base_image" \
    --label "$resource_owner_label_key=$run_marker" \
    --tag "$DESKSEED_REHEARSAL_POSTGRES_IMAGE" \
    --file "$postgres_wrapper_context/Dockerfile" \
    "$postgres_wrapper_context"
capture_checked_output "inspect run-scoped backend image ID" backend_image_id \
  "$docker_cli_path" image inspect --format '{{.Id}}' "$DESKSEED_REHEARSAL_BACKEND_IMAGE"
capture_checked_output "inspect run-scoped frontend image ID" frontend_image_id \
  "$docker_cli_path" image inspect --format '{{.Id}}' "$DESKSEED_REHEARSAL_FRONTEND_IMAGE"
capture_checked_output "inspect run-scoped PostgreSQL image ID" postgres_image_id \
  "$docker_cli_path" image inspect --format '{{.Id}}' "$DESKSEED_REHEARSAL_POSTGRES_IMAGE"
capture_checked_output "inspect PostgreSQL repository digest" postgres_image_digest \
  "$docker_cli_path" image inspect --format '{{index .RepoDigests 0}}' "$postgres_base_image"
capture_checked_output "inspect run-scoped backend image ownership" backend_image_owner \
  "$docker_cli_path" image inspect --format '{{index .Config.Labels "dev.deskseed.operations.run"}}' "$DESKSEED_REHEARSAL_BACKEND_IMAGE"
capture_checked_output "inspect run-scoped frontend image ownership" frontend_image_owner \
  "$docker_cli_path" image inspect --format '{{index .Config.Labels "dev.deskseed.operations.run"}}' "$DESKSEED_REHEARSAL_FRONTEND_IMAGE"
capture_checked_output "inspect run-scoped PostgreSQL image ownership" postgres_image_owner \
  "$docker_cli_path" image inspect --format '{{index .Config.Labels "dev.deskseed.operations.run"}}' "$DESKSEED_REHEARSAL_POSTGRES_IMAGE"
if [[ "$backend_image_owner" != "$run_marker" || "$frontend_image_owner" != "$run_marker" \
   || "$postgres_image_owner" != "$run_marker" ]]; then
  fail_check "run-scoped image ownership assertion" \
    "One or more run-scoped images do not carry the cryptographic ownership marker."
fi
record "image pin" PASS "source and restore are configured with run-scoped backend $backend_image_id, frontend $frontend_image_id, and PostgreSQL $postgres_image_id ($postgres_image_digest) images"

current_stage="fresh source database"
export DESKSEED_REHEARSAL_FLYWAY_TARGET=11
export DESKSEED_REHEARSAL_DDL_AUTO=validate
export DESKSEED_BOOTSTRAP_ADMIN_ENABLED=false
run_resource_creating_checked "start fresh source database container" source_compose up --detach db
run_checked "wait for fresh source database readiness" wait_for_database source_compose
run_checked "verify source migration role flags" assert_source_migration_role_restricted
capture_checked_output "inspect source PostgreSQL container image" source_postgres_image_id \
  compose_service_image_id source_compose db
if [[ "$source_postgres_image_id" != "$postgres_image_id" ]]; then
  fail_check "source database image pin assertion" \
    "The source database container does not use the captured PostgreSQL image."
fi
record "source database image pin" PASS "source database container uses the captured PostgreSQL image $source_postgres_image_id"
run_checked "configure source runtime default privileges" configure_default_runtime_privileges
run_resource_creating_checked "start V11 source backend container" source_compose up --detach backend
run_checked "wait for V11 source backend health" \
  wait_for_url "http://127.0.0.1:$source_backend_port/actuator/health"
capture_checked_output "read fresh source Flyway version" installed_version source_scalar \
  'select version from flyway_schema_history where success order by installed_rank desc limit 1'
if [[ "$installed_version" != "11" ]]; then
  fail_check "fresh-volume V11 version assertion" \
    "The fresh source database did not stop at Flyway V11."
fi
record "fresh-volume V11 install" PASS "new source volume reached backend health with Flyway V11 and Hibernate validate"

current_stage="V11 runtime role configuration"
run_checked "stop V11 source backend container" source_compose stop backend
run_checked "configure and verify V11 source runtime role" configure_source_runtime_role
assert_runtime_statement_denied \
  "update flyway_schema_history set success = true where installed_rank = -1" \
  "Flyway history UPDATE denial"
record "V11 role split" PASS "migration role owns DDL; runtime role passed least-privilege verification"

current_stage="V11 synthetic smoke"
export DESKSEED_BOOTSTRAP_ADMIN_ENABLED=true
run_resource_creating_checked "restart V11 source backend container" source_compose up --detach backend
run_checked "wait for restarted V11 source backend health" \
  wait_for_url "http://127.0.0.1:$source_backend_port/actuator/health"
ticket_subject="Operations rehearsal $run_id"
ticket_number=""
access_token=""
submit_public_ticket "http://127.0.0.1:$source_backend_port" "$ticket_subject"
register_redaction_value "$access_token"
verify_public_ticket "http://127.0.0.1:$source_backend_port" "$work_dir/v11-public-ticket.json"
login_and_read_ticket "http://127.0.0.1:$source_backend_port" "$admin_cookie_file" "v11"
capture_checked_output "count V11 ticket audits" v11_ticket_audits source_scalar \
  "select count(*) from ticket_audits audit join tickets ticket on ticket.id = audit.ticket_id where ticket.ticket_number = $ticket_number"
capture_checked_output "count V11 access audits" v11_access_audits source_scalar \
  "select count(*) from access_audit_events event join tickets ticket on ticket.id = event.resource_id where ticket.ticket_number = $ticket_number"
if [[ ! "$v11_ticket_audits" =~ ^[0-9]+$ || ! "$v11_access_audits" =~ ^[0-9]+$ ]] ||
  ((v11_ticket_audits < 1 || v11_access_audits < 1)); then
  fail_check "V11 API audit-count assertion" \
    "The V11 API smoke did not create both ticket-change and sensitive-read audit rows."
fi
record "V11 API smoke" PASS "anonymous submit/lookup, admin login, staff ticket read, ticket audit ($v11_ticket_audits), and access audit ($v11_access_audits) succeeded for ticket #$ticket_number"

assert_runtime_statement_denied \
  "update ticket_audits set source = source where ticket_id in (select id from tickets where ticket_number = $ticket_number)" \
  "canonical audit UPDATE denial"
assert_runtime_statement_denied \
  "delete from ticket_audit_events where audit_id in (select id from ticket_audits where ticket_id in (select id from tickets where ticket_number = $ticket_number))" \
  "canonical audit DELETE denial"
assert_runtime_statement_denied \
  "create table operations_runtime_ddl_probe (id integer)" \
  "runtime DDL denial"

current_stage="V11 to latest upgrade"
run_checked "stop source backend before Flyway upgrade" source_compose stop backend
export DESKSEED_REHEARSAL_FLYWAY_TARGET="$latest_version"
export DESKSEED_REHEARSAL_DDL_AUTO=validate
upgrade_started_epoch="$(date +%s)"
run_resource_creating_checked "start source backend for Flyway upgrade" source_compose up --detach backend
run_checked "wait for upgraded source backend health" \
  wait_for_url "http://127.0.0.1:$source_backend_port/actuator/health"
upgrade_duration_seconds="$(( $(date +%s) - upgrade_started_epoch ))"
capture_checked_output "read upgraded source Flyway version" installed_version source_scalar \
  'select version from flyway_schema_history where success order by installed_rank desc limit 1'
if [[ "$installed_version" != "$latest_version" ]]; then
  fail_check "Flyway upgrade version assertion" \
    "The source database did not reach the repository-latest Flyway version."
fi
run_checked "verify upgraded source runtime role" configure_source_runtime_role
verify_public_ticket "http://127.0.0.1:$source_backend_port" "$work_dir/upgraded-public-ticket.json"
login_and_read_ticket "http://127.0.0.1:$source_backend_port" "$admin_cookie_file" "upgraded"
run_resource_creating_checked "start upgraded source frontend container" source_compose up --detach frontend
run_checked "wait for upgraded source frontend health" \
  wait_for_url "http://127.0.0.1:$source_frontend_port/"
record "Flyway upgrade" PASS "same volume advanced V11 to V$latest_version in ${upgrade_duration_seconds}s; Hibernate validate and backend/frontend health passed; pre-upgrade ticket remained readable"

current_stage="logical backup"
capture_checked_output "count source tickets before backup" source_ticket_count source_scalar \
  'select count(*) from tickets'
capture_checked_output "count source ticket audits before backup" source_ticket_audit_count source_scalar \
  'select count(*) from ticket_audits'
capture_checked_output "count source access audits before backup" source_access_audit_count source_scalar \
  'select count(*) from access_audit_events'
capture_checked_output "count source admin audits before backup" source_admin_audit_count source_scalar \
  'select count(*) from admin_security_audit_events'
capture_checked_output "count source audit projection rows before backup" source_projection_count source_scalar \
  'select count(*) from audit_activity_projection'
backup_started_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
backup_started_epoch_ms="$(epoch_millis)"
run_checked "create logical pg_dump backup" create_logical_backup
backup_duration_millis="$(( $(epoch_millis) - backup_started_epoch_ms ))"
if [[ ! -s "$backup_file" ]]; then
  fail_check "pg_dump backup file assertion" \
    "The logical backup command did not produce a non-empty dump file."
fi
capture_checked_output "measure logical backup bytes" backup_bytes \
  python3 -c 'import os,sys; print(os.path.getsize(sys.argv[1]))' "$backup_file"
capture_checked_output "hash logical backup" backup_sha256 \
  python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$backup_file"
record "pg_dump backup" PASS "custom-format no-owner/no-ACL snapshot started $backup_started_utc; ${backup_bytes} bytes; sha256 $backup_sha256; ${backup_duration_millis}ms"

current_stage="fresh restore database"
run_resource_creating_checked "start fresh restore database container" restore_compose up --detach db
run_checked "wait for fresh restore database readiness" wait_for_database restore_compose
capture_checked_output "inspect restore PostgreSQL container image" restore_postgres_image_id \
  compose_service_image_id restore_compose db
if [[ "$restore_postgres_image_id" != "$postgres_image_id" ||
  "$restore_postgres_image_id" != "$source_postgres_image_id" ]]; then
  fail_check "restore database image pin assertion" \
    "The restore database container does not use the same captured PostgreSQL image as the source."
fi
record "restore database image pin" PASS "restore database container uses the same captured PostgreSQL image $restore_postgres_image_id"
restore_started_epoch_ms="$(epoch_millis)"
run_checked "restore logical pg_dump backup" restore_logical_backup
restore_duration_millis="$(( $(epoch_millis) - restore_started_epoch_ms ))"
capture_checked_output "count restored tickets" restored_ticket_count restore_scalar \
  'select count(*) from tickets'
capture_checked_output "count restored ticket audits" restored_ticket_audit_count restore_scalar \
  'select count(*) from ticket_audits'
capture_checked_output "count restored access audits" restored_access_audit_count restore_scalar \
  'select count(*) from access_audit_events'
capture_checked_output "count restored admin audits" restored_admin_audit_count restore_scalar \
  'select count(*) from admin_security_audit_events'
capture_checked_output "count restored audit projection rows" restored_projection_count restore_scalar \
  'select count(*) from audit_activity_projection'
if [[ "$restored_ticket_count" != "$source_ticket_count" ||
  "$restored_ticket_audit_count" != "$source_ticket_audit_count" ||
  "$restored_access_audit_count" != "$source_access_audit_count" ||
  "$restored_admin_audit_count" != "$source_admin_audit_count" ||
  "$restored_projection_count" != "$source_projection_count" ]]; then
  fail_check "pg_restore data-parity assertion" \
    "The restored canonical/audit/projection row counts do not match the source snapshot."
fi
record "pg_restore data parity" PASS "fresh restore volume matched counts (tickets=$source_ticket_count, ticket-audits=$source_ticket_audit_count, access-audits=$source_access_audit_count, admin-audits=$source_admin_audit_count, projection=$source_projection_count) in ${restore_duration_millis}ms"

current_stage="post-restore role and application verification"
run_checked "verify restored migration role flags" assert_restore_migration_role_restricted
run_checked "configure and verify restored runtime role" configure_restore_runtime_role
assert_restore_runtime_statement_denied \
  "update flyway_schema_history set success = true where installed_rank = -1" \
  "restored Flyway history UPDATE denial"
export DESKSEED_REHEARSAL_FLYWAY_TARGET="$latest_version"
export DESKSEED_REHEARSAL_DDL_AUTO=validate
export DESKSEED_BOOTSTRAP_ADMIN_ENABLED=true
recovery_validation_started_epoch="$(date +%s)"
run_resource_creating_checked "start restored backend/frontend containers" \
  restore_compose up --detach --no-build backend frontend
run_checked "wait for restored backend health" \
  wait_for_url "http://127.0.0.1:$restore_backend_port/actuator/health"
run_checked "wait for restored frontend health" \
  wait_for_url "http://127.0.0.1:$restore_frontend_port/"
verify_public_ticket "http://127.0.0.1:$restore_backend_port" "$work_dir/restored-public-ticket.json"
login_and_read_ticket "http://127.0.0.1:$restore_backend_port" "$restore_cookie_file" "restored"
capture_checked_output "read restored Flyway version" restored_version restore_scalar \
  'select version from flyway_schema_history where success order by installed_rank desc limit 1'
capture_checked_output "count restored synthetic-ticket audits" restored_ticket_audits restore_scalar \
  "select count(*) from ticket_audits audit join tickets ticket on ticket.id = audit.ticket_id where ticket.ticket_number = $ticket_number"
capture_checked_output "count restored synthetic-ticket access audits" restored_access_audits restore_scalar \
  "select count(*) from access_audit_events event join tickets ticket on ticket.id = event.resource_id where ticket.ticket_number = $ticket_number"
if [[ "$restored_version" != "$latest_version" ]]; then
  fail_check "post-restore Flyway version assertion" \
    "The restored database does not report the repository-latest Flyway version."
fi
if [[ "$restored_ticket_audits" != "$v11_ticket_audits" ]]; then
  fail_check "post-restore ticket-audit parity assertion" \
    "The restored synthetic ticket audit count differs from the V11 source snapshot."
fi
if [[ ! "$restored_access_audits" =~ ^[0-9]+$ || ! "$v11_access_audits" =~ ^[0-9]+$ ]] ||
  ((restored_access_audits <= v11_access_audits)); then
  fail_check "post-restore access-audit append assertion" \
    "The restored application read did not append a new access audit row."
fi
recovery_validation_duration_seconds="$(( $(date +%s) - recovery_validation_started_epoch ))"
record "post-restore application smoke" PASS "V$restored_version backend/frontend health, public token lookup, restored admin login, staff read, and new access audit passed in ${recovery_validation_duration_seconds}s"
record "RPO boundary" PASS "the pre-backup synthetic ticket and all audited reads through the pg_dump snapshot were recovered; no WAL/PITR claim"

current_stage="build context provenance verification"
run_checked "verify operations inputs after restore checks" verify_rehearsal_inputs_unchanged
run_checked "verify Docker build contexts after restore checks" verify_build_context_unchanged
record "pre-cleanup source freeze" PASS "operations inputs plus backend/frontend context fingerprints captured before image build still match after restore verification"

current_stage="complete"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main
fi
