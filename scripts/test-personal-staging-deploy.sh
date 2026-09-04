#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
workflow="$repository_root/.github/workflows/build-personal-staging-images.yml"
ci_workflow="$repository_root/.github/workflows/ci.yml"
staging_compose="$repository_root/compose.personal-staging.yaml"
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

assert_not_contains() {
  local file="$1"
  local unexpected="$2"
  if grep -F -- "$unexpected" "$file" >/dev/null; then
    fail "Expected $file not to contain: $unexpected"
  fi
}

for required_file in "$workflow" "$staging_compose" "$deploy_script"; do
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
  'exit 0'

write_executable "$fake_bin/docker" \
  '#!/usr/bin/env bash' \
  'set -Eeuo pipefail' \
  'printf "docker-env disable=%s files=%s\n" "${COMPOSE_DISABLE_ENV_FILE:-unset}" "${COMPOSE_ENV_FILES+set}" >>"$COMMAND_LOG"' \
  'printf "%s\n" "$*" >>"$COMMAND_LOG"' \
  'if [[ "${1:-}" == "compose" && "$*" == *" config --images" ]]; then' \
  '  printf "%s\n" \' \
  '    "postgres:17-alpine" \' \
  '    "flyway/flyway:12.4.0" \' \
  '    "ghcr.io/kdh949/deskseed-backend:$IMAGE_TAG" \' \
  '    "ghcr.io/kdh949/deskseed-frontend:$IMAGE_TAG" \' \
  '    "redis:8.2.9-alpine" \' \
  '    "ghcr.io/versity/versitygw:v1.4.1"' \
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
  local secret_source="${2:-env-file}"
  local doppler_env_file_conflict="${3:-false}"
  local -a env_options=()

  if [[ "$secret_source" == "doppler" && "$doppler_env_file_conflict" != "true" ]]; then
    env_options=(-u DESKSEED_PRODUCTION_ENV_FILE)
  else
    env_options=("DESKSEED_PRODUCTION_ENV_FILE=$env_file")
  fi

  env \
    "${env_options[@]}" \
    PATH="$fake_bin:/usr/bin:/bin" \
    COMMAND_LOG="$command_log" \
    FAKE_REPOSITORY_ROOT="$repository_root" \
    FAKE_HEAD_SHA="${FAKE_HEAD_SHA:-$expected_sha}" \
    FAKE_GIT_STATUS="${FAKE_GIT_STATUS:-}" \
    FAKE_OPERATING_SYSTEM="${FAKE_OPERATING_SYSTEM:-Linux}" \
    FAKE_MACHINE_ARCH="${FAKE_MACHINE_ARCH:-x86_64}" \
    MISSING_IMAGE="${MISSING_IMAGE:-}" \
    MISMATCH_REVISION_IMAGE="${MISMATCH_REVISION_IMAGE:-}" \
    DESKSEED_APP_DIR="$repository_root" \
    DESKSEED_SECRET_SOURCE="$secret_source" \
    DESKSEED_DEPLOY_LOCK_FILE="$test_root/deploy.lock" \
    REGISTRY_PULL_ATTEMPTS=1 \
    REGISTRY_PULL_INTERVAL_SECONDS=0 \
    "$deploy_script" "$1"
}

: >"$command_log"
if run_deploy invalid-sha >"$test_root/invalid.out" 2>&1; then
  fail "Invalid deployment SHA was accepted."
fi
grep -F "Deployment SHA must be exactly 40 lowercase hexadecimal characters." "$test_root/invalid.out" >/dev/null
[[ ! -s "$command_log" ]] || fail "Invalid SHA reached Docker."

: >"$command_log"
if run_deploy "$expected_sha" unsupported >"$test_root/unsupported-source.out" 2>&1; then
  fail "Unsupported deployment secret source was accepted."
fi
grep -F "DESKSEED_SECRET_SOURCE must be env-file or doppler." \
  "$test_root/unsupported-source.out" >/dev/null
[[ ! -s "$command_log" ]] || fail "Unsupported secret source reached Docker."

: >"$command_log"
if run_deploy "$expected_sha" doppler true >"$test_root/dual-source.out" 2>&1; then
  fail "Doppler deployment accepted DESKSEED_PRODUCTION_ENV_FILE."
fi
grep -F "DESKSEED_PRODUCTION_ENV_FILE must be unset when DESKSEED_SECRET_SOURCE=doppler." \
  "$test_root/dual-source.out" >/dev/null
[[ ! -s "$command_log" ]] || fail "Ambiguous Doppler secret sources reached Docker."

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
assert_contains "$command_log" "--env-file $env_file"
assert_contains "$command_log" "up --detach --no-build --pull never db redis versitygw"
assert_contains "$command_log" "up --detach --no-build --pull never --force-recreate db-migrate db-permissions backend frontend"
if grep -Eq '(^| )build( |$)|:latest' "$command_log"; then
  fail "Deployment attempted an on-box build or mutable latest tag."
fi
assert_contains "$test_root/success.out" "Personal staging deployment passed for $expected_sha."

: >"$command_log"
export COMPOSE_ENV_FILES="$test_root/ambiguous.env"
export GHCR_TOKEN='doppler-ghcr-secret-sentinel'
run_deploy "$expected_sha" doppler >"$test_root/doppler-success.out" 2>&1 || {
  sed -n '1,240p' "$test_root/doppler-success.out" >&2
  fail "Valid Doppler-backed personal staging deployment simulation failed."
}
unset COMPOSE_ENV_FILES GHCR_TOKEN

assert_contains "$command_log" "docker-env disable=1 files="
assert_not_contains "$command_log" "--env-file"
assert_not_contains "$command_log" "doppler-ghcr-secret-sentinel"
assert_not_contains "$test_root/doppler-success.out" "doppler-ghcr-secret-sentinel"
assert_contains "$test_root/doppler-success.out" \
  "Personal staging deployment passed for $expected_sha."

printf 'Personal staging deployment contract passed.\n'
