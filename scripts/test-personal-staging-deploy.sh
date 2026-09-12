#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
workflow="$repository_root/.github/workflows/build-personal-staging-images.yml"
ci_workflow="$repository_root/.github/workflows/ci.yml"
staging_compose="$repository_root/compose.personal-staging.yaml"
observability_compose="$repository_root/compose.personal-staging-observability.yaml"
observability_alloy="$repository_root/ops/observability/personal-staging/alloy/config.alloy"
observability_profile="$repository_root/backend/src/main/resources/application-personal-staging-observability.yml"
observability_nginx="$repository_root/frontend/nginx.personal-staging-observability.conf"
observability_bind_validator="$repository_root/scripts/validate-personal-staging-observability-bind.py"
deploy_script="$repository_root/scripts/deploy-personal-server.sh"
expected_sha=125727bbd2194bcf0937a7eca452231ffc7a4bb1
backend_image="ghcr.io/kdh949/deskseed-backend:$expected_sha"
frontend_image="ghcr.io/kdh949/deskseed-frontend:$expected_sha"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local expected="$2"
  grep -F -- "$expected" "$file" >/dev/null ||
    fail "Expected $file to contain: $expected"
}

for required_file in "$workflow" "$staging_compose" "$observability_compose" \
  "$observability_alloy" "$observability_profile" "$observability_nginx" \
  "$observability_bind_validator" "$deploy_script"; do
  [[ -f "$required_file" ]] || fail "Required deployment artifact is missing: $required_file"
done

assert_contains "$workflow" "workflow_call:"
assert_contains "$workflow" "workflow_dispatch:"
assert_contains "$workflow" "packages: write"
assert_contains "$workflow" 'deskseed-${{ matrix.service }}:${{ github.sha }}'
assert_contains "$workflow" "platforms: linux/amd64"
assert_contains "$workflow" "- service: backend"
assert_contains "$workflow" "- service: frontend"
if grep -Eq 'self-hosted|ssh|scp' "$workflow"; then
  fail "Image publication must not access the personal server."
fi

assert_contains "$ci_workflow" "publish-personal-staging-images:"
assert_contains "$ci_workflow" "needs: ci-gate"
assert_contains "$ci_workflow" "if: github.event_name == 'push' && github.ref == 'refs/heads/main'"
assert_contains "$ci_workflow" "uses: ./.github/workflows/build-personal-staging-images.yml"

assert_contains "$staging_compose" 'image: ghcr.io/kdh949/deskseed-backend:${IMAGE_TAG:?IMAGE_TAG is required}'
assert_contains "$staging_compose" 'image: ghcr.io/kdh949/deskseed-frontend:${IMAGE_TAG:?IMAGE_TAG is required}'
if grep -F ':latest' "$staging_compose" >/dev/null; then
  fail "Personal staging images must not use latest tags."
fi
if [[ "$(grep -Fxc '    build: !reset null' "$staging_compose")" -ne 2 ]]; then
  fail "Personal staging Compose must clear both application build definitions."
fi

assert_contains "$observability_compose" "SPRING_PROFILES_INCLUDE: personal-staging-observability"
assert_contains "$observability_compose" "DESKSEED_LOKI_OTLP_HTTP_ENDPOINT"
assert_contains "$observability_compose" "image: grafana/alloy:v1.18.0"
assert_contains "$observability_profile" "enabled: false"
assert_contains "$observability_alloy" "logs   = [otelcol.processor.batch.logs.input]"
assert_contains "$observability_alloy" "traces = [otelcol.processor.batch.traces.input]"
if grep -Eq '/var/run/docker.sock|discovery\.docker|loki\.source\.docker|/var/lib/docker|privileged:|PYROSCOPE_' \
  "$observability_compose" "$observability_alloy"; then
  fail "Personal staging observability must not collect Docker/host data or enable profiling."
fi

test_root="$(mktemp -d "${TMPDIR:-/tmp}/deskseed-personal-staging-test.XXXXXX")"
chmod 700 "$test_root"
fake_bin="$test_root/bin"
command_log="$test_root/commands.log"
env_file="$test_root/production.env"
mkdir "$fake_bin"
: >"$command_log"
: >"$env_file"
chmod 600 "$env_file"

cleanup() {
  local exit_code=$?
  trap - EXIT
  case "$test_root" in
    "${TMPDIR:-/tmp}"/deskseed-personal-staging-test.??????)
      rm -rf -- "$test_root" || exit_code=1
      ;;
    *)
      printf 'Refusing unexpected test directory cleanup: %s\n' "$test_root" >&2
      exit_code=1
      ;;
  esac
  exit "$exit_code"
}
trap cleanup EXIT

write_executable() {
  local path="$1"
  shift
  printf '%s\n' "$@" >"$path"
  chmod 755 "$path"
}

write_executable "$fake_bin/git" \
  '#!/usr/bin/env bash' \
  'set -Eeuo pipefail' \
  'case "$*" in' \
  '  "rev-parse --show-toplevel") printf "%s\n" "$FAKE_REPOSITORY_ROOT" ;;' \
  '  "rev-parse HEAD") printf "%s\n" "$FAKE_HEAD_SHA" ;;' \
  '  "status --porcelain --untracked-files=normal") printf "%s" "${FAKE_GIT_STATUS:-}" ;;' \
  '  *) printf "Unexpected git command: %s\n" "$*" >&2; exit 1 ;;' \
  'esac'

write_executable "$fake_bin/flock" \
  '#!/usr/bin/env bash' \
  'exit 0'

write_executable "$fake_bin/uname" \
  '#!/usr/bin/env bash' \
  'case "${1:-}" in' \
  '  -s) printf "%s\n" "${FAKE_OPERATING_SYSTEM:-Linux}" ;;' \
  '  -m) printf "%s\n" "${FAKE_MACHINE_ARCH:-x86_64}" ;;' \
  '  *) printf "Unexpected uname arguments: %s\n" "$*" >&2; exit 1 ;;' \
  'esac'

write_executable "$fake_bin/curl" \
  '#!/usr/bin/env bash' \
  'printf "%s\n" "{\"status\":\"UP\"}"'

write_executable "$fake_bin/ip" \
  '#!/usr/bin/env bash' \
  'set -Eeuo pipefail' \
  'if [[ "$*" == "-o addr show" ]]; then' \
  '  printf "2: eth0    inet %s/24 scope global eth0\n" "${FAKE_ASSIGNED_OBSERVABILITY_BIND_ADDRESS:-10.20.30.40}"' \
  '  exit 0' \
  'fi' \
  'printf "Unexpected ip arguments: %s\n" "$*" >&2' \
  'exit 1'

write_executable "$fake_bin/docker" \
  '#!/usr/bin/env bash' \
  'set -Eeuo pipefail' \
  'printf "%s\n" "$*" >>"$COMMAND_LOG"' \
  'if [[ "${1:-}" == "compose" && "$*" == *" config --format json" ]]; then' \
  '  printf "%s\n" "{\"services\":{\"backend\":{\"ports\":[{\"host_ip\":\"${FAKE_OBSERVABILITY_BIND_ADDRESS:-10.20.30.40}\",\"published\":\"9090\",\"target\":9090}]},\"alloy\":{\"ports\":[{\"host_ip\":\"${FAKE_OBSERVABILITY_BIND_ADDRESS:-10.20.30.40}\",\"published\":\"12345\",\"target\":12345}]}}}"' \
  '  exit 0' \
  'fi' \
  'if [[ "${1:-}" == "compose" && "$*" == *" config --images" ]]; then' \
  '  printf "%s\n" \' \
  '    "postgres:17-alpine" \' \
  '    "flyway/flyway:12.4.0" \' \
  '    "ghcr.io/kdh949/deskseed-backend:$IMAGE_TAG" \' \
  '    "ghcr.io/kdh949/deskseed-frontend:$IMAGE_TAG" \' \
  '    "redis:8.2.9-alpine" \' \
  '    "ghcr.io/versity/versitygw:v1.4.1"' \
  '  if [[ "$*" == *"compose.personal-staging-observability.yaml"* ]]; then' \
  '    printf "%s\n" "grafana/alloy:v1.18.0"' \
  '  fi' \
  '  exit 0' \
  'fi' \
  'if [[ "${1:-} ${2:-}" == "image inspect" ]]; then' \
  '  image="${*: -1}"' \
  '  [[ -z "${MISSING_IMAGE:-}" || "$image" != "$MISSING_IMAGE" ]] || exit 1' \
  '  if [[ -n "${MISMATCH_REVISION_IMAGE:-}" && "$image" == "$MISMATCH_REVISION_IMAGE" ]]; then' \
  '    printf "%s\n" "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' \
  '  else' \
  '    printf "%s\n" "$IMAGE_TAG"' \
  '  fi' \
  '  exit 0' \
  'fi' \
  'if [[ "${1:-}" == "inspect" ]]; then' \
  '  printf "%s\n" "exited 0"' \
  '  exit 0' \
  'fi' \
  'if [[ "${1:-}" == "compose" && "$*" == *" ps --all --quiet db-migrate" ]]; then' \
  '  printf "%s\n" "db-migrate-container"' \
  '  exit 0' \
  'fi' \
  'if [[ "${1:-}" == "compose" && "$*" == *" ps --all --quiet db-permissions" ]]; then' \
  '  printf "%s\n" "db-permissions-container"' \
  '  exit 0' \
  'fi' \
  'if [[ "${1:-}" == "compose" && "$*" == *" ps --status running --quiet "* ]]; then' \
  '  printf "%s\n" "running-container"' \
  '  exit 0' \
  'fi' \
  'if [[ "${1:-}" == "compose" && "$*" == *" port frontend 80" ]]; then' \
  '  printf "%s\n" "127.0.0.1:18080"' \
  '  exit 0' \
  'fi' \
  'exit 0'

run_deploy() {
  env \
    PATH="$fake_bin:/usr/bin:/bin" \
    COMMAND_LOG="$command_log" \
    FAKE_REPOSITORY_ROOT="$repository_root" \
    FAKE_HEAD_SHA="${FAKE_HEAD_SHA:-$expected_sha}" \
    FAKE_GIT_STATUS="${FAKE_GIT_STATUS:-}" \
    FAKE_OPERATING_SYSTEM="${FAKE_OPERATING_SYSTEM:-Linux}" \
    FAKE_MACHINE_ARCH="${FAKE_MACHINE_ARCH:-x86_64}" \
    FAKE_OBSERVABILITY_BIND_ADDRESS="${FAKE_OBSERVABILITY_BIND_ADDRESS:-10.20.30.40}" \
    FAKE_ASSIGNED_OBSERVABILITY_BIND_ADDRESS="${FAKE_ASSIGNED_OBSERVABILITY_BIND_ADDRESS:-10.20.30.40}" \
    MISSING_IMAGE="${MISSING_IMAGE:-}" \
    MISMATCH_REVISION_IMAGE="${MISMATCH_REVISION_IMAGE:-}" \
    DESKSEED_APP_DIR="$repository_root" \
    DESKSEED_PRODUCTION_ENV_FILE="$env_file" \
    DESKSEED_DEPLOY_LOCK_FILE="$test_root/deploy.lock" \
    DESKSEED_PERSONAL_STAGING_OBSERVABILITY_ENABLED="${DESKSEED_PERSONAL_STAGING_OBSERVABILITY_ENABLED:-false}" \
    REGISTRY_PULL_ATTEMPTS=1 \
    REGISTRY_PULL_INTERVAL_SECONDS=0 \
    HEALTHCHECK_ATTEMPTS=1 \
    "$deploy_script" "$1"
}

: >"$command_log"
if run_deploy invalid-sha >"$test_root/invalid.out" 2>&1; then
  fail "Invalid deployment SHA was accepted."
fi
grep -F "Deployment SHA must be exactly 40 lowercase hexadecimal characters." "$test_root/invalid.out" >/dev/null
[[ ! -s "$command_log" ]] || fail "Invalid SHA reached Docker."

: >"$command_log"
FAKE_HEAD_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
export FAKE_HEAD_SHA
if run_deploy "$expected_sha" >"$test_root/mismatch.out" 2>&1; then
  fail "Mismatched server HEAD was accepted."
fi
unset FAKE_HEAD_SHA
grep -F "Server HEAD does not match the requested deployment SHA." "$test_root/mismatch.out" >/dev/null
[[ ! -s "$command_log" ]] || fail "Mismatched SHA reached Docker."

: >"$command_log"
FAKE_GIT_STATUS='?? untracked-migration.sql'
export FAKE_GIT_STATUS
if run_deploy "$expected_sha" >"$test_root/dirty.out" 2>&1; then
  fail "Dirty server checkout was accepted."
fi
unset FAKE_GIT_STATUS
grep -F "Server checkout is dirty; deployment refused." "$test_root/dirty.out" >/dev/null
[[ ! -s "$command_log" ]] || fail "Dirty checkout reached Docker."

: >"$command_log"
export DESKSEED_PERSONAL_STAGING_OBSERVABILITY_ENABLED=not-a-boolean
if run_deploy "$expected_sha" >"$test_root/invalid-observability.out" 2>&1; then
  fail "Invalid personal-staging observability flag was accepted."
fi
unset DESKSEED_PERSONAL_STAGING_OBSERVABILITY_ENABLED
grep -F "DESKSEED_PERSONAL_STAGING_OBSERVABILITY_ENABLED must be true or false." \
  "$test_root/invalid-observability.out" >/dev/null
[[ ! -s "$command_log" ]] || fail "Invalid observability flag reached Docker."

: >"$command_log"
MISSING_IMAGE="$frontend_image"
export MISSING_IMAGE
if run_deploy "$expected_sha" >"$test_root/missing.out" 2>&1; then
  fail "Deployment continued with a missing application image."
fi
unset MISSING_IMAGE
grep -F "Required application image is unavailable: $frontend_image" "$test_root/missing.out" >/dev/null
if grep -F " up " "$command_log" >/dev/null; then
  fail "Container replacement started before every application image was available."
fi

: >"$command_log"
MISMATCH_REVISION_IMAGE="$backend_image"
export MISMATCH_REVISION_IMAGE
if run_deploy "$expected_sha" >"$test_root/revision.out" 2>&1; then
  fail "Deployment continued with a mismatched image revision label."
fi
unset MISMATCH_REVISION_IMAGE
grep -F "Application image revision label does not match deployment SHA: $backend_image" \
  "$test_root/revision.out" >/dev/null
if grep -F " up " "$command_log" >/dev/null; then
  fail "Container replacement started with a mismatched image revision label."
fi

: >"$command_log"
run_deploy "$expected_sha" >"$test_root/success.out" 2>&1 || {
  sed -n '1,240p' "$test_root/success.out" >&2
  fail "Valid personal staging deployment simulation failed."
}

assert_contains "$command_log" "pull"
assert_contains "$command_log" "$backend_image"
assert_contains "$command_log" "$frontend_image"
assert_contains "$command_log" "up --detach --no-build --pull never db redis versitygw"
assert_contains "$command_log" "up --detach --no-build --pull never --force-recreate db-migrate db-permissions backend frontend"
if grep -Eq '(^| )build( |$)|:latest' "$command_log"; then
  fail "Deployment attempted an on-box build or mutable latest tag."
fi
assert_contains "$test_root/success.out" "Personal staging deployment passed for $expected_sha."

: >"$command_log"
export DESKSEED_PERSONAL_STAGING_OBSERVABILITY_ENABLED=true
FAKE_OBSERVABILITY_BIND_ADDRESS=0.0.0.0
export FAKE_OBSERVABILITY_BIND_ADDRESS
if run_deploy "$expected_sha" >"$test_root/wildcard-ipv4.out" 2>&1; then
  fail "Wildcard IPv4 observability bind was accepted."
fi
unset FAKE_OBSERVABILITY_BIND_ADDRESS
grep -F "Personal-staging observability backend bind address must not be an unspecified address." \
  "$test_root/wildcard-ipv4.out" >/dev/null
if grep -F " pull" "$command_log" >/dev/null || grep -F " up " "$command_log" >/dev/null; then
  fail "Wildcard observability bind reached image pull or container replacement."
fi

: >"$command_log"
FAKE_OBSERVABILITY_BIND_ADDRESS='::'
export FAKE_OBSERVABILITY_BIND_ADDRESS
if run_deploy "$expected_sha" >"$test_root/wildcard-ipv6-raw.out" 2>&1; then
  fail "Raw wildcard IPv6 observability bind was accepted."
fi
unset FAKE_OBSERVABILITY_BIND_ADDRESS
grep -F "Personal-staging observability backend bind address must not be an unspecified address." \
  "$test_root/wildcard-ipv6-raw.out" >/dev/null
if grep -F " pull" "$command_log" >/dev/null || grep -F " up " "$command_log" >/dev/null; then
  fail "Raw wildcard IPv6 observability bind reached image pull or container replacement."
fi

: >"$command_log"
FAKE_OBSERVABILITY_BIND_ADDRESS='[::]'
export FAKE_OBSERVABILITY_BIND_ADDRESS
if run_deploy "$expected_sha" >"$test_root/wildcard-ipv6.out" 2>&1; then
  fail "Bracketed wildcard IPv6 observability bind was accepted."
fi
unset FAKE_OBSERVABILITY_BIND_ADDRESS
grep -F "Personal-staging observability backend bind address must not be an unspecified address." \
  "$test_root/wildcard-ipv6.out" >/dev/null
if grep -F " pull" "$command_log" >/dev/null || grep -F " up " "$command_log" >/dev/null; then
  fail "Wildcard IPv6 observability bind reached image pull or container replacement."
fi

: >"$command_log"
FAKE_OBSERVABILITY_BIND_ADDRESS=10.20.30.41
FAKE_ASSIGNED_OBSERVABILITY_BIND_ADDRESS=10.20.30.40
export FAKE_OBSERVABILITY_BIND_ADDRESS FAKE_ASSIGNED_OBSERVABILITY_BIND_ADDRESS
if run_deploy "$expected_sha" >"$test_root/unassigned-bind.out" 2>&1; then
  fail "Unassigned observability bind was accepted."
fi
unset FAKE_OBSERVABILITY_BIND_ADDRESS FAKE_ASSIGNED_OBSERVABILITY_BIND_ADDRESS
grep -F "Personal-staging observability bind address is not assigned to this host." \
  "$test_root/unassigned-bind.out" >/dev/null
if grep -F " pull" "$command_log" >/dev/null || grep -F " up " "$command_log" >/dev/null; then
  fail "Unassigned observability bind reached image pull or container replacement."
fi

: >"$command_log"
FAKE_OBSERVABILITY_BIND_ADDRESS=8.8.8.8
FAKE_ASSIGNED_OBSERVABILITY_BIND_ADDRESS=8.8.8.8
export FAKE_OBSERVABILITY_BIND_ADDRESS FAKE_ASSIGNED_OBSERVABILITY_BIND_ADDRESS
if run_deploy "$expected_sha" >"$test_root/global-bind.out" 2>&1; then
  fail "Globally routable observability bind was accepted."
fi
unset FAKE_OBSERVABILITY_BIND_ADDRESS FAKE_ASSIGNED_OBSERVABILITY_BIND_ADDRESS
grep -F "Personal-staging observability backend bind address must not be globally routable." \
  "$test_root/global-bind.out" >/dev/null
if grep -F " pull" "$command_log" >/dev/null || grep -F " up " "$command_log" >/dev/null; then
  fail "Globally routable observability bind reached image pull or container replacement."
fi

: >"$command_log"
run_deploy "$expected_sha" >"$test_root/observability.out" 2>&1 || {
  sed -n '1,240p' "$test_root/observability.out" >&2
  fail "Personal staging observability deployment simulation failed."
}
unset DESKSEED_PERSONAL_STAGING_OBSERVABILITY_ENABLED

assert_contains "$command_log" "--file $repository_root/compose.personal-staging-observability.yaml"
assert_contains "$command_log" "up --detach --no-build --pull never db redis versitygw alloy"
assert_contains "$test_root/observability.out" "Personal staging deployment passed for $expected_sha."

printf 'Personal staging deployment contract passed.\n'
