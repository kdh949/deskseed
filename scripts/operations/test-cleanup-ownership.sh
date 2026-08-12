#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../run-operations-rehearsal.sh
source "$repository_root/scripts/run-operations-rehearsal.sh"

for command_name in docker python3; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'Required command is unavailable: %s\n' "$command_name" >&2
    exit 127
  }
done
docker_cli_path="$(resolve_docker_cli_binary)"
docker_cli_fingerprint="$(sha256_file "$docker_cli_path")"
"$docker_cli_path" info >/dev/null 2>&1 || {
  printf 'Docker Engine is unavailable.\n' >&2
  exit 2
}

test_root="$(mktemp -d "${TMPDIR:-/tmp}/deskseed-operations.XXXXXX")"
work_dir="$test_root"
test_cleanup_container_ids=()
test_cleanup_network_ids=()
test_cleanup_volume_names=()
test_cleanup_image_tags=()
test_cleanup_image_ids=()

cleanup_test_fixture() {
  local exit_code=$?
  local resource
  trap - EXIT
  set +e
  for resource in "${test_cleanup_container_ids[@]}"; do
    [[ -n "$resource" ]] && "$docker_cli_path" container rm --force "$resource" >/dev/null 2>&1
  done
  for resource in "${test_cleanup_network_ids[@]}"; do
    [[ -n "$resource" ]] && "$docker_cli_path" network rm "$resource" >/dev/null 2>&1
  done
  for resource in "${test_cleanup_volume_names[@]}"; do
    case "$resource" in
      deskseed-ops-*) "$docker_cli_path" volume rm "$resource" >/dev/null 2>&1 ;;
    esac
  done
  for resource in "${test_cleanup_image_tags[@]}"; do
    case "$resource" in
      deskseed-operations-*:*) "$docker_cli_path" image rm "$resource" >/dev/null 2>&1 ;;
    esac
  done
  for resource in "${test_cleanup_image_ids[@]}"; do
    [[ -n "$resource" ]] && "$docker_cli_path" image rm "$resource" >/dev/null 2>&1
  done
  case "$test_root" in
    "${TMPDIR:-/tmp}"/deskseed-operations.??????) rm -rf -- "$test_root" ;;
    *) printf 'Refusing unexpected test directory cleanup: %s\n' "$test_root" >&2; exit_code=1 ;;
  esac
  exit "$exit_code"
}
trap cleanup_test_fixture EXIT

run_stateful_checked "initialize test run-scoped Docker identity" initialize_resource_identity
[[ "$resource_identity_initialized" == true ]]
[[ -n "$run_marker" && -n "$run_id" && -n "$source_project" && -n "$restore_project" ]]
[[ -f "$ownership_overlay_file" && -d "$postgres_wrapper_context" ]]
[[ "${compose_files[${#compose_files[@]}-1]}" == "$ownership_overlay_file" ]]
docker_preflight_passed=true
resource_name_preflight_passed=false

sentinel_dockerfile="$work_dir/Dockerfile.sentinel"
printf 'FROM scratch\nLABEL dev.deskseed.operations.cleanup-test="%s"\n' \
  "$run_marker" >"$sentinel_dockerfile"
sentinel_tag="deskseed-operations-sentinel:$image_tag"
"$docker_cli_path" build --tag "$sentinel_tag" --file "$sentinel_dockerfile" "$work_dir" >/dev/null
sentinel_image_id="$("$docker_cli_path" image inspect --format '{{.Id}}' "$sentinel_tag")"
test_cleanup_image_tags+=("$sentinel_tag")
test_cleanup_image_ids+=("$sentinel_image_id")

expect_preflight_collision() {
  local kind="$1"
  if assert_resource_names_absent >/dev/null 2>&1; then
    printf 'Expected %s collision to fail preflight.\n' "$kind" >&2
    exit 1
  fi
  resource_name_preflight_passed=false
}

preexisting_container_name="${source_project}-db-1"
preexisting_container_id="$("$docker_cli_path" container create --name "$preexisting_container_name" "$sentinel_tag" true)"
test_cleanup_container_ids+=("$preexisting_container_id")
expect_preflight_collision container
[[ "$("$docker_cli_path" container inspect --format '{{.Id}}' "$preexisting_container_name")" == "$preexisting_container_id" ]]
"$docker_cli_path" container rm "$preexisting_container_id" >/dev/null

preexisting_network_name="${source_project}_default"
preexisting_network_id="$("$docker_cli_path" network create "$preexisting_network_name")"
test_cleanup_network_ids+=("$preexisting_network_id")
expect_preflight_collision network
[[ "$("$docker_cli_path" network inspect --format '{{.Id}}' "$preexisting_network_name")" == "$preexisting_network_id" ]]
"$docker_cli_path" network rm "$preexisting_network_id" >/dev/null

preexisting_volume_name="${source_project}_deskseed-postgres"
"$docker_cli_path" volume create "$preexisting_volume_name" >/dev/null
test_cleanup_volume_names+=("$preexisting_volume_name")
expect_preflight_collision volume
"$docker_cli_path" volume inspect "$preexisting_volume_name" >/dev/null
"$docker_cli_path" volume rm "$preexisting_volume_name" >/dev/null

"$docker_cli_path" image tag "$sentinel_image_id" "$DESKSEED_REHEARSAL_BACKEND_IMAGE"
test_cleanup_image_tags+=("$DESKSEED_REHEARSAL_BACKEND_IMAGE")
expect_preflight_collision image
[[ "$("$docker_cli_path" image inspect --format '{{.Id}}' "$DESKSEED_REHEARSAL_BACKEND_IMAGE")" == "$sentinel_image_id" ]]
"$docker_cli_path" image rm "$DESKSEED_REHEARSAL_BACKEND_IMAGE" >/dev/null

resource_name_preflight_passed=true
owned_dockerfile="$work_dir/Dockerfile.owned"
printf 'FROM scratch\n' >"$owned_dockerfile"
"$docker_cli_path" build \
  --label "$resource_owner_label_key=$run_marker" \
  --tag "$DESKSEED_REHEARSAL_BACKEND_IMAGE" \
  --tag "$DESKSEED_REHEARSAL_FRONTEND_IMAGE" \
  --file "$owned_dockerfile" "$work_dir" >/dev/null
owned_image_id="$("$docker_cli_path" image inspect --format '{{.Id}}' "$DESKSEED_REHEARSAL_BACKEND_IMAGE")"
test_cleanup_image_tags+=("$DESKSEED_REHEARSAL_BACKEND_IMAGE" "$DESKSEED_REHEARSAL_FRONTEND_IMAGE")
test_cleanup_image_ids+=("$owned_image_id")

owned_container_name="${source_project}-db-1"
owned_container_id="$("$docker_cli_path" container create \
  --name "$owned_container_name" \
  --label "$resource_owner_label_key=$run_marker" \
  --label "com.docker.compose.project=$source_project" \
  "$sentinel_tag" true)"
owned_extra_container_name="${source_project}-backend-1"
owned_extra_container_id="$("$docker_cli_path" container create \
  --name "$owned_extra_container_name" \
  --label "$resource_owner_label_key=$run_marker" \
  --label "com.docker.compose.project=$source_project" \
  "$sentinel_tag" true)"
test_cleanup_container_ids+=("$owned_container_id" "$owned_extra_container_id")

owned_network_name="${source_project}_default"
owned_network_id="$("$docker_cli_path" network create \
  --label "$resource_owner_label_key=$run_marker" \
  --label "com.docker.compose.project=$source_project" \
  "$owned_network_name")"
owned_extra_network_name="${source_project}_owned-extra"
owned_extra_network_id="$("$docker_cli_path" network create \
  --label "$resource_owner_label_key=$run_marker" \
  --label "com.docker.compose.project=$source_project" \
  "$owned_extra_network_name")"
test_cleanup_network_ids+=("$owned_network_id" "$owned_extra_network_id")

owned_volume_name="${source_project}_deskseed-postgres"
"$docker_cli_path" volume create \
  --label "$resource_owner_label_key=$run_marker" \
  --label "com.docker.compose.project=$source_project" \
  "$owned_volume_name" >/dev/null
owned_extra_volume_name="${source_project}_owned-extra"
"$docker_cli_path" volume create \
  --label "$resource_owner_label_key=$run_marker" \
  --label "com.docker.compose.project=$source_project" \
  "$owned_extra_volume_name" >/dev/null
test_cleanup_volume_names+=("$owned_volume_name" "$owned_extra_volume_name")

discover_all_owned_resources
[[ "${#owned_container_records[@]}" -eq 2 ]]
[[ "${#owned_network_records[@]}" -eq 2 ]]
[[ "${#owned_volume_records[@]}" -eq 2 ]]
[[ "${#owned_image_ids[@]}" -eq 1 ]]
discover_all_owned_resources
[[ "${#owned_container_records[@]}" -eq 2 ]]
[[ "${#owned_network_records[@]}" -eq 2 ]]
[[ "${#owned_volume_records[@]}" -eq 2 ]]
[[ "${#owned_image_ids[@]}" -eq 1 ]]

"$docker_cli_path" container rm "$owned_container_id" >/dev/null
replacement_container_id="$("$docker_cli_path" container create --name "$owned_container_name" "$sentinel_tag" true)"
test_cleanup_container_ids+=("$replacement_container_id")
"$docker_cli_path" network rm "$owned_network_id" >/dev/null
replacement_network_id="$("$docker_cli_path" network create "$owned_network_name")"
test_cleanup_network_ids+=("$replacement_network_id")
"$docker_cli_path" volume rm "$owned_volume_name" >/dev/null
"$docker_cli_path" volume create "$owned_volume_name" >/dev/null
"$docker_cli_path" image tag "$sentinel_image_id" "$DESKSEED_REHEARSAL_BACKEND_IMAGE"

if cleanup_owned_containers; then
  printf 'Container replacement was not reported as cleanup failure.\n' >&2
  exit 1
fi
if cleanup_owned_networks; then
  printf 'Network replacement was not reported as cleanup failure.\n' >&2
  exit 1
fi
if cleanup_owned_volumes; then
  printf 'Volume replacement was not reported as cleanup failure.\n' >&2
  exit 1
fi
if cleanup_owned_images; then
  printf 'Image replacement was not reported as cleanup failure.\n' >&2
  exit 1
fi

[[ "$("$docker_cli_path" container inspect --format '{{.Id}}' "$owned_container_name")" == "$replacement_container_id" ]]
[[ "$("$docker_cli_path" network inspect --format '{{.Id}}' "$owned_network_name")" == "$replacement_network_id" ]]
"$docker_cli_path" volume inspect "$owned_volume_name" >/dev/null
[[ "$("$docker_cli_path" image inspect --format '{{.Id}}' "$DESKSEED_REHEARSAL_BACKEND_IMAGE")" == "$sentinel_image_id" ]]

! "$docker_cli_path" container inspect "$owned_extra_container_id" >/dev/null 2>&1
! "$docker_cli_path" network inspect "$owned_extra_network_id" >/dev/null 2>&1
! "$docker_cli_path" volume inspect "$owned_extra_volume_name" >/dev/null 2>&1
! "$docker_cli_path" image inspect "$owned_image_id" >/dev/null 2>&1
! "$docker_cli_path" image inspect "$DESKSEED_REHEARSAL_FRONTEND_IMAGE" >/dev/null 2>&1

printf 'PASS: preexisting container/network/volume/image sentinels survived collision checks.\n'
printf 'PASS: replacement sentinels survived while all still-owned exact resources were removed.\n'
