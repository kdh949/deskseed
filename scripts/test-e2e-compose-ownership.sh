#!/usr/bin/env bash
set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=e2e-compose-ownership.sh
source "$repository_root/scripts/e2e-compose-ownership.sh"

command -v docker >/dev/null 2>&1 || {
  printf 'Required command is unavailable: docker\n' >&2
  exit 127
}
docker info >/dev/null 2>&1 || {
  printf 'Docker Engine is unavailable.\n' >&2
  exit 2
}

test_root="$(mktemp -d "${TMPDIR:-/tmp}/deskseed-e2e-ownership.XXXXXX")"
chmod 700 "$test_root"
test_container_ids=()
test_network_ids=()
test_volume_names=()
test_image_tags=()
test_image_ids=()

cleanup_test_fixture() {
  local exit_code=$?
  local resource
  trap - EXIT
  set +e
  for resource in "${test_container_ids[@]}"; do
    [[ -n "$resource" ]] && docker container rm --force --volumes "$resource" >/dev/null 2>&1
  done
  for resource in "${test_network_ids[@]}"; do
    [[ -n "$resource" ]] && docker network rm "$resource" >/dev/null 2>&1
  done
  for resource in "${test_volume_names[@]}"; do
    case "$resource" in
      deskseed-customer-e2e-*) docker volume rm "$resource" >/dev/null 2>&1 ;;
    esac
  done
  for resource in "${test_image_tags[@]}"; do
    case "$resource" in
      deskseed-customer-e2e-*:*) docker image rm "$resource" >/dev/null 2>&1 ;;
      deskseed-e2e-ownership-sentinel:*) docker image rm "$resource" >/dev/null 2>&1 ;;
    esac
  done
  for resource in "${test_image_ids[@]}"; do
    [[ -n "$resource" ]] && docker image rm "$resource" >/dev/null 2>&1
  done
  if [[ "$test_root" == "${TMPDIR:-/tmp}"/deskseed-e2e-ownership.?????? ]]; then
    [[ ! -e "$test_root/Dockerfile.sentinel" ]] || unlink "$test_root/Dockerfile.sentinel"
    [[ ! -e "$test_root/Dockerfile.owned" ]] || unlink "$test_root/Dockerfile.owned"
    [[ ! -e "$e2e_ownership_overlay_file" ]] || unlink "$e2e_ownership_overlay_file"
    rmdir "$test_root" >/dev/null 2>&1 || exit_code=1
  else
    printf 'Refusing unexpected test directory cleanup: %s\n' "$test_root" >&2
    exit_code=1
  fi
  exit "$exit_code"
}
trap cleanup_test_fixture EXIT

e2e_initialize_resource_identity customer "$test_root"
[[ "$e2e_run_id" =~ ^[0-9a-f]{40}$ ]]
[[ "$e2e_run_marker" == "deskseed-e2e-customer-$e2e_run_id" ]]
[[ "$e2e_project" == "deskseed-customer-e2e-${e2e_run_id:0:32}" ]]
[[ -f "$e2e_ownership_overlay_file" && ! -L "$e2e_ownership_overlay_file" ]]

compose_config="$(
  DESKSEED_E2E_CONTAINER_UID="$(id -u)" \
  DESKSEED_E2E_CONTAINER_GID="$(id -g)" \
    docker compose \
      --project-name "$e2e_project" \
      --file "$repository_root/compose.yaml" \
      --file "$repository_root/compose.e2e.yaml" \
      --file "$e2e_ownership_overlay_file" \
      config
)"
[[ "$compose_config" == *"$e2e_backend_image"* ]]
[[ "$compose_config" == *"$e2e_frontend_image"* ]]
[[ "$(grep -o "$e2e_run_marker" <<<"$compose_config" | wc -l | tr -d '[:space:]')" -ge 7 ]]

sentinel_dockerfile="$test_root/Dockerfile.sentinel"
printf 'FROM scratch\nLABEL dev.deskseed.e2e.cleanup-test="%s"\nCMD ["/sentinel"]\n' \
  "$e2e_run_id" >"$sentinel_dockerfile"
sentinel_tag="deskseed-e2e-ownership-sentinel:$e2e_run_id"
docker build --tag "$sentinel_tag" --file "$sentinel_dockerfile" "$test_root" >/dev/null
sentinel_image_id="$(docker image inspect --format '{{.Id}}' "$sentinel_tag")"
test_image_tags+=("$sentinel_tag")
test_image_ids+=("$sentinel_image_id")

expect_preflight_collision() {
  local kind="$1"
  if e2e_assert_resource_names_absent >/dev/null 2>&1; then
    printf 'Expected %s collision to fail preflight.\n' "$kind" >&2
    exit 1
  fi
  [[ "$e2e_docker_preflight_passed" == false ]]
}

preexisting_container_name="${e2e_project}-db-1"
preexisting_container_id="$(docker container create --name "$preexisting_container_name" "$sentinel_tag")"
test_container_ids+=("$preexisting_container_id")
expect_preflight_collision container
[[ "$(docker container inspect --format '{{.Id}}' "$preexisting_container_name")" == "$preexisting_container_id" ]]
docker container rm "$preexisting_container_id" >/dev/null

preexisting_network_name="${e2e_project}_default"
preexisting_network_id="$(docker network create "$preexisting_network_name")"
test_network_ids+=("$preexisting_network_id")
expect_preflight_collision network
[[ "$(docker network inspect --format '{{.Id}}' "$preexisting_network_name")" == "$preexisting_network_id" ]]
docker network rm "$preexisting_network_id" >/dev/null

preexisting_volume_name="${e2e_project}_deskseed-postgres"
docker volume create "$preexisting_volume_name" >/dev/null
test_volume_names+=("$preexisting_volume_name")
expect_preflight_collision volume
docker volume inspect "$preexisting_volume_name" >/dev/null
docker volume rm "$preexisting_volume_name" >/dev/null

docker image tag "$sentinel_image_id" "$e2e_backend_image"
test_image_tags+=("$e2e_backend_image")
expect_preflight_collision image
[[ "$(docker image inspect --format '{{.Id}}' "$e2e_backend_image")" == "$sentinel_image_id" ]]
docker image rm "$e2e_backend_image" >/dev/null

e2e_assert_resource_names_absent
[[ "$e2e_docker_preflight_passed" == true ]]

# Simulate a normal trap firing after Compose created its first resource but
# before the runner reached the explicit post-start capture.
uncaptured_container_name="${e2e_project}-db-1"
uncaptured_container_id="$(docker container create \
  --name "$uncaptured_container_name" \
  --label "$e2e_resource_owner_label_key=$e2e_run_marker" \
  --label "com.docker.compose.project=$e2e_project" \
  "$sentinel_tag")"
test_container_ids+=("$uncaptured_container_id")
e2e_cleanup_owned_resources
! docker container inspect "$uncaptured_container_id" >/dev/null 2>&1

e2e_initialize_resource_identity smoke "$test_root"
smoke_compose_config="$(
  docker compose \
    --project-name "$e2e_project" \
    --file "$repository_root/compose.yaml" \
    --file "$e2e_ownership_overlay_file" \
    config
)"
[[ "$e2e_project" == "deskseed-smoke-e2e-${e2e_run_id:0:32}" ]]
[[ "$smoke_compose_config" == *"$e2e_backend_image"* ]]
[[ "$smoke_compose_config" == *"$e2e_frontend_image"* ]]
[[ "$(grep -o "$e2e_run_marker" <<<"$smoke_compose_config" | wc -l | tr -d '[:space:]')" -ge 7 ]]

e2e_initialize_resource_identity customer "$test_root"
e2e_assert_resource_names_absent

owned_dockerfile="$test_root/Dockerfile.owned"
printf 'FROM scratch\nCMD ["/owned"]\n' >"$owned_dockerfile"
docker build \
  --label "$e2e_resource_owner_label_key=$e2e_run_marker" \
  --tag "$e2e_backend_image" \
  --tag "$e2e_frontend_image" \
  --file "$owned_dockerfile" "$test_root" >/dev/null
owned_image_id="$(docker image inspect --format '{{.Id}}' "$e2e_backend_image")"
test_image_tags+=("$e2e_backend_image" "$e2e_frontend_image")
test_image_ids+=("$owned_image_id")

owned_container_name="${e2e_project}-db-1"
owned_container_id="$(docker container create \
  --name "$owned_container_name" \
  --label "$e2e_resource_owner_label_key=$e2e_run_marker" \
  --label "com.docker.compose.project=$e2e_project" \
  "$sentinel_tag")"
owned_extra_container_name="${e2e_project}-backend-1"
owned_extra_container_id="$(docker container create \
  --name "$owned_extra_container_name" \
  --label "$e2e_resource_owner_label_key=$e2e_run_marker" \
  --label "com.docker.compose.project=$e2e_project" \
  "$sentinel_tag")"
test_container_ids+=("$owned_container_id" "$owned_extra_container_id")

owned_network_name="${e2e_project}_default"
owned_network_id="$(docker network create \
  --label "$e2e_resource_owner_label_key=$e2e_run_marker" \
  --label "com.docker.compose.project=$e2e_project" \
  "$owned_network_name")"
owned_extra_network_name="${e2e_project}_owned-extra"
owned_extra_network_id="$(docker network create \
  --label "$e2e_resource_owner_label_key=$e2e_run_marker" \
  --label "com.docker.compose.project=$e2e_project" \
  "$owned_extra_network_name")"
test_network_ids+=("$owned_network_id" "$owned_extra_network_id")

owned_volume_name="${e2e_project}_deskseed-postgres"
docker volume create \
  --label "$e2e_resource_owner_label_key=$e2e_run_marker" \
  --label "com.docker.compose.project=$e2e_project" \
  "$owned_volume_name" >/dev/null
owned_extra_volume_name="${e2e_project}_owned-extra"
docker volume create \
  --label "$e2e_resource_owner_label_key=$e2e_run_marker" \
  --label "com.docker.compose.project=$e2e_project" \
  "$owned_extra_volume_name" >/dev/null
test_volume_names+=("$owned_volume_name" "$owned_extra_volume_name")

e2e_capture_owned_resources
[[ "${#e2e_owned_container_records[@]}" -eq 2 ]]
[[ "${#e2e_owned_network_records[@]}" -eq 2 ]]
[[ "${#e2e_owned_volume_records[@]}" -eq 2 ]]
[[ "${#e2e_owned_image_ids[@]}" -eq 1 ]]
if e2e_capture_owned_resources; then
  printf 'A second ownership capture was not rejected.\n' >&2
  exit 1
fi

docker container rm "$owned_container_id" >/dev/null
replacement_container_id="$(docker container create --name "$owned_container_name" "$sentinel_tag")"
test_container_ids+=("$replacement_container_id")
docker network rm "$owned_network_id" >/dev/null
replacement_network_id="$(docker network create "$owned_network_name")"
test_network_ids+=("$replacement_network_id")
docker volume rm "$owned_volume_name" >/dev/null
docker volume create "$owned_volume_name" >/dev/null
docker image tag "$sentinel_image_id" "$e2e_backend_image"

if e2e_cleanup_owned_resources; then
  printf 'Replacement resources were not reported as cleanup failure.\n' >&2
  exit 1
fi

[[ "$(docker container inspect --format '{{.Id}}' "$owned_container_name")" == "$replacement_container_id" ]]
[[ "$(docker network inspect --format '{{.Id}}' "$owned_network_name")" == "$replacement_network_id" ]]
docker volume inspect "$owned_volume_name" >/dev/null
[[ "$(docker image inspect --format '{{.Id}}' "$e2e_backend_image")" == "$sentinel_image_id" ]]

! docker container inspect "$owned_extra_container_id" >/dev/null 2>&1
! docker network inspect "$owned_extra_network_id" >/dev/null 2>&1
! docker volume inspect "$owned_extra_volume_name" >/dev/null 2>&1
! docker image inspect "$owned_image_id" >/dev/null 2>&1
! docker image inspect "$e2e_frontend_image" >/dev/null 2>&1

printf 'PASS: preexisting container/network/volume/image sentinels survived fail-closed preflight.\n'
printf 'PASS: replacement sentinels survived while exact still-owned resources were removed.\n'
