#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

script_repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
app_dir="${DESKSEED_APP_DIR:-$script_repository_root}"
env_file="${DESKSEED_PRODUCTION_ENV_FILE:-/etc/deskseed/production.env}"
lock_file="${DESKSEED_DEPLOY_LOCK_FILE:-/tmp/deskseed-personal-staging-deploy.lock}"
project_name="${DESKSEED_PROJECT_NAME:-deskseed}"
expected_sha="${1:-}"

if [[ ! "$expected_sha" =~ ^[0-9a-f]{40}$ ]]; then
  printf 'Deployment SHA must be exactly 40 lowercase hexadecimal characters.\n' >&2
  exit 2
fi

if [[ ! "$project_name" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
  printf 'DESKSEED_PROJECT_NAME must contain only lowercase letters, digits, underscores, and hyphens.\n' >&2
  exit 2
fi

for command_name in curl docker flock git stat uname; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'Required command is unavailable: %s\n' "$command_name" >&2
    exit 127
  }
done

if [[ ! -d "$app_dir" ]]; then
  printf 'Deskseed application directory does not exist: %s\n' "$app_dir" >&2
  exit 2
fi
cd "$app_dir"
app_dir="$(pwd -P)"

exec 9>"$lock_file"
if ! flock -n 9; then
  printf 'Another Deskseed deployment is already running.\n' >&2
  exit 1
fi

repository_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  printf 'Deskseed application directory is not a Git checkout.\n' >&2
  exit 2
}
if [[ "$repository_root" != "$app_dir" ]]; then
  printf 'DESKSEED_APP_DIR must be the Deskseed repository root.\n' >&2
  exit 2
fi

current_sha="$(git rev-parse HEAD)"
if [[ "$current_sha" != "$expected_sha" ]]; then
  printf 'Server HEAD does not match the requested deployment SHA.\n' >&2
  exit 1
fi
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  printf 'Server checkout is dirty; deployment refused.\n' >&2
  exit 1
fi

if [[ "$(uname -s)" != "Linux" ]]; then
  printf 'Personal staging images currently support Linux hosts only.\n' >&2
  exit 1
fi
case "$(uname -m)" in
  x86_64 | amd64) ;;
  *)
    printf 'Personal staging images currently support linux/amd64 only.\n' >&2
    exit 1
    ;;
esac

if [[ ! -f "$env_file" || -L "$env_file" ]]; then
  printf 'Production env must be a regular non-symlink file: %s\n' "$env_file" >&2
  exit 2
fi
env_mode="$(stat -c '%a' "$env_file" 2>/dev/null || stat -f '%Lp' "$env_file")"
if [[ "$env_mode" != "600" ]]; then
  printf 'Production env must have mode 0600: %s\n' "$env_file" >&2
  exit 2
fi

export IMAGE_TAG="$expected_sha"
ghcr_token="${GHCR_TOKEN:-}"
unset GHCR_TOKEN
compose=(
  docker compose
  --project-name "$project_name"
  --env-file "$env_file"
  --file "$repository_root/compose.yaml"
  --file "$repository_root/compose.production.yaml"
  --file "$repository_root/compose.personal-staging.yaml"
)

"${compose[@]}" config --quiet
resolved_images="$("${compose[@]}" config --images)"
required_images=(
  "ghcr.io/kdh949/deskseed-backend:$expected_sha"
  "ghcr.io/kdh949/deskseed-frontend:$expected_sha"
)
for image in "${required_images[@]}"; do
  if [[ "$(grep -Fxc "$image" <<<"$resolved_images")" -ne 1 ]]; then
    printf 'Merged Compose does not resolve exactly one required application image: %s\n' "$image" >&2
    exit 1
  fi
done

docker_config_directory=""
cleanup() {
  local exit_code=$?
  trap - EXIT
  if [[ -n "$docker_config_directory" ]]; then
    case "$docker_config_directory" in
      "${TMPDIR:-/tmp}"/deskseed-docker-config.??????)
        rm -rf -- "$docker_config_directory" || exit_code=1
        ;;
      *)
        printf 'Refusing unexpected Docker config cleanup: %s\n' "$docker_config_directory" >&2
        exit_code=1
        ;;
    esac
  fi
  exit "$exit_code"
}
trap cleanup EXIT

if [[ -n "$ghcr_token" ]]; then
  docker_config_directory="$(mktemp -d "${TMPDIR:-/tmp}/deskseed-docker-config.XXXXXX")"
  chmod 700 "$docker_config_directory"
  export DOCKER_CONFIG="$docker_config_directory"
  printf '%s' "$ghcr_token" |
    docker login ghcr.io -u "${GHCR_USERNAME:-kdh949}" --password-stdin
  ghcr_token=""
fi

pull_attempts="${REGISTRY_PULL_ATTEMPTS:-6}"
pull_interval_seconds="${REGISTRY_PULL_INTERVAL_SECONDS:-10}"
if [[ ! "$pull_attempts" =~ ^[1-9][0-9]*$ ]]; then
  printf 'REGISTRY_PULL_ATTEMPTS must be a positive integer.\n' >&2
  exit 2
fi
if [[ ! "$pull_interval_seconds" =~ ^[0-9]+$ ]]; then
  printf 'REGISTRY_PULL_INTERVAL_SECONDS must be a non-negative integer.\n' >&2
  exit 2
fi

images_pulled=false
for attempt in $(seq 1 "$pull_attempts"); do
  if "${compose[@]}" pull; then
    images_pulled=true
    break
  fi
  if [[ "$attempt" -lt "$pull_attempts" ]]; then
    printf 'Images for %s are not ready; retrying %s/%s.\n' \
      "$expected_sha" "$attempt" "$pull_attempts" >&2
    sleep "$pull_interval_seconds"
  fi
done
if [[ "$images_pulled" != true ]]; then
  printf 'Required images for %s could not be pulled.\n' "$expected_sha" >&2
  exit 1
fi

for image in "${required_images[@]}"; do
  if ! image_revision="$(docker image inspect --format \
      '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
      "$image" 2>/dev/null)"; then
    printf 'Required application image is unavailable: %s\n' "$image" >&2
    exit 1
  fi
  if [[ "$image_revision" != "$expected_sha" ]]; then
    printf 'Application image revision label does not match deployment SHA: %s\n' \
      "$image" >&2
    exit 1
  fi
done

"${compose[@]}" up --detach --no-build --pull never db redis versitygw
"${compose[@]}" up --detach --no-build --pull never --force-recreate \
  db-migrate db-permissions backend frontend

verify_completed_job() {
  local service="$1"
  local container_id
  local state
  container_id="$("${compose[@]}" ps --all --quiet "$service")"
  if [[ -z "$container_id" ]]; then
    printf 'Required one-shot service has no container: %s\n' "$service" >&2
    return 1
  fi
  state="$(docker inspect --format '{{.State.Status}} {{.State.ExitCode}}' "$container_id")"
  if [[ "$state" != "exited 0" ]]; then
    printf 'Required one-shot service did not complete successfully: %s (%s)\n' \
      "$service" "$state" >&2
    return 1
  fi
}

verify_running_service() {
  local service="$1"
  if [[ -z "$("${compose[@]}" ps --status running --quiet "$service")" ]]; then
    printf 'Required service is not running: %s\n' "$service" >&2
    return 1
  fi
}

verify_completed_job db-migrate
verify_completed_job db-permissions
for service in db redis versitygw backend frontend; do
  verify_running_service "$service"
done

origin="$("${compose[@]}" port frontend 80 | sed -n '1p')"
if [[ -z "$origin" ]]; then
  printf 'Could not resolve the published frontend origin.\n' >&2
  exit 1
fi

health_attempts="${HEALTHCHECK_ATTEMPTS:-60}"
if [[ ! "$health_attempts" =~ ^[1-9][0-9]*$ ]]; then
  printf 'HEALTHCHECK_ATTEMPTS must be a positive integer.\n' >&2
  exit 2
fi

health_ready=false
for attempt in $(seq 1 "$health_attempts"); do
  if curl --fail --silent --show-error --connect-timeout 3 --max-time 5 \
      "http://$origin/actuator/health" >/dev/null 2>&1 &&
    curl --fail --silent --show-error --connect-timeout 3 --max-time 5 \
      "http://$origin/" >/dev/null 2>&1; then
    health_ready=true
    break
  fi
  sleep 2
done
if [[ "$health_ready" != true ]]; then
  printf 'Deployment finished, but frontend/backend health checks did not pass.\n' >&2
  "${compose[@]}" ps --all
  exit 1
fi

"${compose[@]}" ps --all
printf 'Personal staging deployment passed for %s.\n' "$expected_sha"
