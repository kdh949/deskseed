#!/usr/bin/env bash

# Run-scoped Docker ownership boundary shared by the full-stack E2E runners.
# Callers must use Bash and install their own EXIT trap.

e2e_resource_owner_label_key="dev.deskseed.e2e.run"
e2e_resource_identity_initialized=false
e2e_docker_preflight_passed=false
e2e_resources_captured=false
e2e_run_id=""
e2e_run_marker=""
e2e_project=""
e2e_backend_image=""
e2e_frontend_image=""
e2e_ownership_overlay_file=""
e2e_owned_container_records=()
e2e_owned_network_records=()
e2e_owned_volume_records=()
e2e_owned_image_ids=()

e2e_initialize_resource_identity() {
  local profile="$1"
  local work_dir="$2"
  local random_component

  [[ "$profile" =~ ^[a-z0-9-]+$ ]] || {
    printf 'Invalid E2E ownership profile: %s\n' "$profile" >&2
    return 2
  }
  [[ -d "$work_dir" && ! -L "$work_dir" ]] || {
    printf 'E2E ownership work directory must be a real directory.\n' >&2
    return 2
  }

  random_component="$(LC_ALL=C od -An -N20 -tx1 /dev/urandom | tr -d '[:space:]')" || return $?
  [[ "$random_component" =~ ^[0-9a-f]{40}$ ]] || {
    printf 'Could not create a cryptographically strong E2E run identity.\n' >&2
    return 1
  }

  e2e_run_id="$random_component"
  e2e_run_marker="deskseed-e2e-$profile-$e2e_run_id"
  e2e_project="deskseed-$profile-e2e-${e2e_run_id:0:32}"
  e2e_backend_image="deskseed-$profile-e2e-backend:$e2e_run_id"
  e2e_frontend_image="deskseed-$profile-e2e-frontend:$e2e_run_id"
  e2e_ownership_overlay_file="$work_dir/compose.ownership.yaml"
  e2e_owned_container_records=()
  e2e_owned_network_records=()
  e2e_owned_volume_records=()
  e2e_owned_image_ids=()
  e2e_docker_preflight_passed=false
  e2e_resources_captured=false

  {
    printf 'services:\n'
    printf '  db:\n'
    printf '    labels:\n'
    printf '      "%s": "%s"\n' "$e2e_resource_owner_label_key" "$e2e_run_marker"
    printf '  mailpit:\n'
    printf '    labels:\n'
    printf '      "%s": "%s"\n' "$e2e_resource_owner_label_key" "$e2e_run_marker"
    for service in backend frontend; do
      local image_name="$e2e_backend_image"
      [[ "$service" == frontend ]] && image_name="$e2e_frontend_image"
      printf '  %s:\n' "$service"
      printf '    image: "%s"\n' "$image_name"
      printf '    labels:\n'
      printf '      "%s": "%s"\n' "$e2e_resource_owner_label_key" "$e2e_run_marker"
      printf '    build:\n'
      printf '      labels:\n'
      printf '        "%s": "%s"\n' "$e2e_resource_owner_label_key" "$e2e_run_marker"
    done
    printf 'volumes:\n'
    printf '  deskseed-postgres:\n'
    printf '    labels:\n'
    printf '      "%s": "%s"\n' "$e2e_resource_owner_label_key" "$e2e_run_marker"
    printf 'networks:\n'
    printf '  default:\n'
    printf '    labels:\n'
    printf '      "%s": "%s"\n' "$e2e_resource_owner_label_key" "$e2e_run_marker"
  } >"$e2e_ownership_overlay_file"
  chmod 600 "$e2e_ownership_overlay_file"
  e2e_resource_identity_initialized=true
}

e2e_verify_ownership_overlay() {
  [[ "$e2e_resource_identity_initialized" == true \
    && -f "$e2e_ownership_overlay_file" \
    && ! -L "$e2e_ownership_overlay_file" ]]
}

e2e_assert_resource_names_absent() {
  local output
  local service
  local expected_name

  e2e_docker_preflight_passed=false
  e2e_verify_ownership_overlay || {
    printf 'E2E Docker ownership identity is not initialized.\n' >&2
    return 1
  }
  docker info >/dev/null 2>&1 || {
    printf 'Docker Engine is unavailable during E2E preflight.\n' >&2
    return 1
  }

  for service in db mailpit backend frontend; do
    for expected_name in "${e2e_project}-${service}-1" "${e2e_project}_${service}_1"; do
      if docker container inspect "$expected_name" >/dev/null 2>&1; then
        printf 'Preflight found a preexisting container named %s; it will not be touched.\n' "$expected_name" >&2
        return 1
      fi
    done
  done
  if docker network inspect "${e2e_project}_default" >/dev/null 2>&1; then
    printf 'Preflight found a preexisting network named %s; it will not be touched.\n' "${e2e_project}_default" >&2
    return 1
  fi
  if docker volume inspect "${e2e_project}_deskseed-postgres" >/dev/null 2>&1; then
    printf 'Preflight found a preexisting volume named %s; it will not be touched.\n' "${e2e_project}_deskseed-postgres" >&2
    return 1
  fi
  for expected_name in "$e2e_backend_image" "$e2e_frontend_image"; do
    if docker image inspect "$expected_name" >/dev/null 2>&1; then
      printf 'Preflight found a preexisting image tag %s; it will not be touched.\n' "$expected_name" >&2
      return 1
    fi
  done

  output="$(docker container ls --all --quiet --no-trunc \
    --filter "label=com.docker.compose.project=$e2e_project")" || return 1
  [[ -z "$output" ]] || {
    printf 'Preflight found containers carrying Compose project label %s.\n' "$e2e_project" >&2
    return 1
  }
  output="$(docker network ls --quiet --no-trunc \
    --filter "label=com.docker.compose.project=$e2e_project")" || return 1
  [[ -z "$output" ]] || {
    printf 'Preflight found networks carrying Compose project label %s.\n' "$e2e_project" >&2
    return 1
  }
  output="$(docker volume ls --quiet \
    --filter "label=com.docker.compose.project=$e2e_project")" || return 1
  [[ -z "$output" ]] || {
    printf 'Preflight found volumes carrying Compose project label %s.\n' "$e2e_project" >&2
    return 1
  }

  output="$(docker container ls --all --quiet --no-trunc \
    --filter "label=$e2e_resource_owner_label_key=$e2e_run_marker")" || return 1
  [[ -z "$output" ]] || return 1
  output="$(docker network ls --quiet --no-trunc \
    --filter "label=$e2e_resource_owner_label_key=$e2e_run_marker")" || return 1
  [[ -z "$output" ]] || return 1
  output="$(docker volume ls --quiet \
    --filter "label=$e2e_resource_owner_label_key=$e2e_run_marker")" || return 1
  [[ -z "$output" ]] || return 1
  output="$(docker image ls --quiet --no-trunc \
    --filter "label=$e2e_resource_owner_label_key=$e2e_run_marker")" || return 1
  [[ -z "$output" ]] || return 1

  e2e_docker_preflight_passed=true
}

e2e_append_container_record() {
  local container_id="$1"
  local container_name="$2"
  local record
  local recorded_id
  for record in "${e2e_owned_container_records[@]}"; do
    IFS=$'\t' read -r recorded_id _ <<<"$record"
    [[ "$recorded_id" == "$container_id" ]] && return 0
  done
  e2e_owned_container_records+=("$container_id"$'\t'"$container_name")
}

e2e_append_network_record() {
  local network_id="$1"
  local network_name="$2"
  local record
  local recorded_id
  for record in "${e2e_owned_network_records[@]}"; do
    IFS=$'\t' read -r recorded_id _ <<<"$record"
    [[ "$recorded_id" == "$network_id" ]] && return 0
  done
  e2e_owned_network_records+=("$network_id"$'\t'"$network_name")
}

e2e_append_volume_record() {
  local volume_name="$1"
  local fingerprint="$2"
  local record
  local recorded_name
  for record in "${e2e_owned_volume_records[@]}"; do
    IFS=$'\t' read -r recorded_name _ <<<"$record"
    [[ "$recorded_name" == "$volume_name" ]] && return 0
  done
  e2e_owned_volume_records+=("$volume_name"$'\t'"$fingerprint")
}

e2e_append_image_id() {
  local image_id="$1"
  local recorded_id
  for recorded_id in "${e2e_owned_image_ids[@]}"; do
    [[ "$recorded_id" == "$image_id" ]] && return 0
  done
  e2e_owned_image_ids+=("$image_id")
}

e2e_volume_identity_fingerprint() {
  local volume_name="$1"
  docker volume inspect --format \
    '{{.Name}}|{{.CreatedAt}}|{{.Driver}}|{{.Mountpoint}}|{{json .Options}}|{{json .Labels}}|{{.Scope}}' \
    "$volume_name"
}

e2e_capture_owned_resources() {
  local ids
  local names
  local resource_id
  local resource_name
  local actual_id
  local actual_owner
  local actual_project
  local fingerprint
  local capture_failed=0

  [[ "$e2e_docker_preflight_passed" == true ]] || {
    printf 'Refusing E2E resource capture before a successful preflight.\n' >&2
    return 1
  }
  [[ "$e2e_resources_captured" != true ]] || {
    printf 'Refusing to replace the immutable E2E resource capture.\n' >&2
    return 1
  }
  e2e_resources_captured=true
  e2e_owned_container_records=()
  e2e_owned_network_records=()
  e2e_owned_volume_records=()
  e2e_owned_image_ids=()

  if ! ids="$(docker container ls --all --quiet --no-trunc \
      --filter "label=$e2e_resource_owner_label_key=$e2e_run_marker" \
      --filter "label=com.docker.compose.project=$e2e_project")"; then
    capture_failed=1
  else
    for resource_id in $ids; do
      actual_id="$(docker container inspect --format '{{.Id}}' "$resource_id" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      actual_owner="$(docker container inspect --format "{{index .Config.Labels \"$e2e_resource_owner_label_key\"}}" "$resource_id" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      actual_project="$(docker container inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$resource_id" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      resource_name="$(docker container inspect --format '{{.Name}}' "$resource_id" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      resource_name="${resource_name#/}"
      if [[ "$actual_id" != "$resource_id" || "$actual_owner" != "$e2e_run_marker" \
         || "$actual_project" != "$e2e_project" || -z "$resource_name" ]]; then
        capture_failed=1
        continue
      fi
      e2e_append_container_record "$actual_id" "$resource_name"
    done
  fi

  if ! ids="$(docker network ls --quiet --no-trunc \
      --filter "label=$e2e_resource_owner_label_key=$e2e_run_marker" \
      --filter "label=com.docker.compose.project=$e2e_project")"; then
    capture_failed=1
  else
    for resource_id in $ids; do
      actual_id="$(docker network inspect --format '{{.Id}}' "$resource_id" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      actual_owner="$(docker network inspect --format "{{index .Labels \"$e2e_resource_owner_label_key\"}}" "$resource_id" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      actual_project="$(docker network inspect --format '{{index .Labels "com.docker.compose.project"}}' "$resource_id" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      resource_name="$(docker network inspect --format '{{.Name}}' "$resource_id" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      if [[ "$actual_id" != "$resource_id" || "$actual_owner" != "$e2e_run_marker" \
         || "$actual_project" != "$e2e_project" || -z "$resource_name" ]]; then
        capture_failed=1
        continue
      fi
      e2e_append_network_record "$actual_id" "$resource_name"
    done
  fi

  if ! names="$(docker volume ls --quiet \
      --filter "label=$e2e_resource_owner_label_key=$e2e_run_marker" \
      --filter "label=com.docker.compose.project=$e2e_project")"; then
    capture_failed=1
  else
    for resource_name in $names; do
      actual_owner="$(docker volume inspect --format "{{index .Labels \"$e2e_resource_owner_label_key\"}}" "$resource_name" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      actual_project="$(docker volume inspect --format '{{index .Labels "com.docker.compose.project"}}' "$resource_name" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      fingerprint="$(e2e_volume_identity_fingerprint "$resource_name")" || {
        capture_failed=1
        continue
      }
      if [[ "$actual_owner" != "$e2e_run_marker" || "$actual_project" != "$e2e_project" \
         || -z "$fingerprint" ]]; then
        capture_failed=1
        continue
      fi
      e2e_append_volume_record "$resource_name" "$fingerprint"
    done
  fi

  if ! ids="$(docker image ls --quiet --no-trunc \
      --filter "label=$e2e_resource_owner_label_key=$e2e_run_marker")"; then
    capture_failed=1
  else
    for resource_id in $ids; do
      actual_id="$(docker image inspect --format '{{.Id}}' "$resource_id" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      actual_owner="$(docker image inspect --format "{{index .Config.Labels \"$e2e_resource_owner_label_key\"}}" "$resource_id" 2>/dev/null)" || {
        capture_failed=1
        continue
      }
      if [[ "$actual_id" != "$resource_id" || "$actual_owner" != "$e2e_run_marker" ]]; then
        capture_failed=1
        continue
      fi
      e2e_append_image_id "$actual_id"
    done
  fi

  return "$capture_failed"
}

e2e_array_contains_image_id() {
  local expected_id="$1"
  local image_id
  for image_id in "${e2e_owned_image_ids[@]}"; do
    [[ "$image_id" == "$expected_id" ]] && return 0
  done
  return 1
}

e2e_assert_expected_stack_captured() {
  local service
  local modern_name
  local legacy_name
  local record
  local resource_id
  local resource_name
  local found
  local expected_image
  local image_id

  [[ "$e2e_resources_captured" == true ]] || return 1
  [[ "${#e2e_owned_container_records[@]}" -eq 4 \
    && "${#e2e_owned_network_records[@]}" -eq 1 \
    && "${#e2e_owned_volume_records[@]}" -eq 1 \
    && "${#e2e_owned_image_ids[@]}" -eq 2 ]] || {
    printf 'Captured E2E resource cardinality did not match the four-service stack.\n' >&2
    return 1
  }

  for service in db mailpit backend frontend; do
    modern_name="${e2e_project}-${service}-1"
    legacy_name="${e2e_project}_${service}_1"
    found=false
    for record in "${e2e_owned_container_records[@]}"; do
      IFS=$'\t' read -r resource_id resource_name <<<"$record"
      if [[ "$resource_name" == "$modern_name" || "$resource_name" == "$legacy_name" ]]; then
        found=true
        break
      fi
    done
    [[ "$found" == true ]] || return 1
  done

  IFS=$'\t' read -r resource_id resource_name <<<"${e2e_owned_network_records[0]}"
  [[ "$resource_name" == "${e2e_project}_default" ]] || return 1
  IFS=$'\t' read -r resource_name _ <<<"${e2e_owned_volume_records[0]}"
  [[ "$resource_name" == "${e2e_project}_deskseed-postgres" ]] || return 1

  for expected_image in "$e2e_backend_image" "$e2e_frontend_image"; do
    image_id="$(docker image inspect --format '{{.Id}}' "$expected_image" 2>/dev/null)" || return 1
    e2e_array_contains_image_id "$image_id" || return 1
  done
}

e2e_cleanup_owned_containers() {
  local record
  local container_id
  local container_name
  local actual_id
  local actual_name
  local actual_owner
  local actual_project
  local replacement_id
  local cleanup_failed=0

  for record in "${e2e_owned_container_records[@]}"; do
    IFS=$'\t' read -r container_id container_name <<<"$record"
    if docker container inspect "$container_id" >/dev/null 2>&1; then
      actual_id="$(docker container inspect --format '{{.Id}}' "$container_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_name="$(docker container inspect --format '{{.Name}}' "$container_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_name="${actual_name#/}"
      actual_owner="$(docker container inspect --format "{{index .Config.Labels \"$e2e_resource_owner_label_key\"}}" "$container_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_project="$(docker container inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$container_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      if [[ "$actual_id" != "$container_id" || "$actual_name" != "$container_name" \
         || "$actual_owner" != "$e2e_run_marker" || "$actual_project" != "$e2e_project" ]]; then
        printf 'Refusing to remove a captured container whose identity or ownership changed: %s.\n' "$container_name" >&2
        cleanup_failed=1
      elif ! docker container rm --force --volumes "$container_id" >/dev/null 2>&1; then
        printf 'Failed to remove owned container ID %s.\n' "$container_id" >&2
        cleanup_failed=1
      elif docker container inspect "$container_id" >/dev/null 2>&1; then
        cleanup_failed=1
      fi
    elif ! docker info >/dev/null 2>&1; then
      cleanup_failed=1
    fi

    if replacement_id="$(docker container inspect --format '{{.Id}}' "$container_name" 2>/dev/null)"; then
      if [[ "$replacement_id" != "$container_id" ]]; then
        printf 'Preserved replacement container at captured name %s; cleanup failed closed.\n' "$container_name" >&2
      else
        printf 'Captured container still exists after cleanup: %s.\n' "$container_name" >&2
      fi
      cleanup_failed=1
    elif ! docker info >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  done
  return "$cleanup_failed"
}

e2e_cleanup_owned_networks() {
  local record
  local network_id
  local network_name
  local actual_id
  local actual_name
  local actual_owner
  local actual_project
  local replacement_id
  local cleanup_failed=0

  for record in "${e2e_owned_network_records[@]}"; do
    IFS=$'\t' read -r network_id network_name <<<"$record"
    if docker network inspect "$network_id" >/dev/null 2>&1; then
      actual_id="$(docker network inspect --format '{{.Id}}' "$network_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_name="$(docker network inspect --format '{{.Name}}' "$network_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_owner="$(docker network inspect --format "{{index .Labels \"$e2e_resource_owner_label_key\"}}" "$network_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_project="$(docker network inspect --format '{{index .Labels "com.docker.compose.project"}}' "$network_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      if [[ "$actual_id" != "$network_id" || "$actual_name" != "$network_name" \
         || "$actual_owner" != "$e2e_run_marker" || "$actual_project" != "$e2e_project" ]]; then
        printf 'Refusing to remove a captured network whose identity or ownership changed: %s.\n' "$network_name" >&2
        cleanup_failed=1
      elif ! docker network rm "$network_id" >/dev/null 2>&1; then
        printf 'Failed to remove owned network ID %s.\n' "$network_id" >&2
        cleanup_failed=1
      elif docker network inspect "$network_id" >/dev/null 2>&1; then
        cleanup_failed=1
      fi
    elif ! docker info >/dev/null 2>&1; then
      cleanup_failed=1
    fi

    if replacement_id="$(docker network inspect --format '{{.Id}}' "$network_name" 2>/dev/null)"; then
      if [[ "$replacement_id" != "$network_id" ]]; then
        printf 'Preserved replacement network at captured name %s; cleanup failed closed.\n' "$network_name" >&2
      else
        printf 'Captured network still exists after cleanup: %s.\n' "$network_name" >&2
      fi
      cleanup_failed=1
    elif ! docker info >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  done
  return "$cleanup_failed"
}

e2e_cleanup_owned_volumes() {
  local record
  local volume_name
  local expected_fingerprint
  local actual_fingerprint
  local actual_owner
  local actual_project
  local cleanup_failed=0

  for record in "${e2e_owned_volume_records[@]}"; do
    IFS=$'\t' read -r volume_name expected_fingerprint <<<"$record"
    if docker volume inspect "$volume_name" >/dev/null 2>&1; then
      actual_owner="$(docker volume inspect --format "{{index .Labels \"$e2e_resource_owner_label_key\"}}" "$volume_name" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_project="$(docker volume inspect --format '{{index .Labels "com.docker.compose.project"}}' "$volume_name" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_fingerprint="$(e2e_volume_identity_fingerprint "$volume_name")" || {
        cleanup_failed=1
        continue
      }
      if [[ "$actual_owner" != "$e2e_run_marker" || "$actual_project" != "$e2e_project" \
         || "$actual_fingerprint" != "$expected_fingerprint" ]]; then
        printf 'Preserved replacement or ownership-mismatched volume %s; cleanup failed closed.\n' "$volume_name" >&2
        cleanup_failed=1
        continue
      fi
      # Docker volumes have no immutable removal handle. Inspect identity is
      # rechecked immediately before the name-based API call; Docker daemon
      # principal isolation remains an operational prerequisite.
      if ! docker volume rm "$volume_name" >/dev/null 2>&1; then
        printf 'Failed to remove verified owned volume %s.\n' "$volume_name" >&2
        cleanup_failed=1
      elif docker volume inspect "$volume_name" >/dev/null 2>&1; then
        cleanup_failed=1
      fi
    elif ! docker info >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  done
  return "$cleanup_failed"
}

e2e_cleanup_owned_images() {
  local image_id
  local image_name
  local actual_id
  local actual_owner
  local cleanup_failed=0

  for image_id in "${e2e_owned_image_ids[@]}"; do
    if docker image inspect "$image_id" >/dev/null 2>&1; then
      actual_id="$(docker image inspect --format '{{.Id}}' "$image_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      actual_owner="$(docker image inspect --format "{{index .Config.Labels \"$e2e_resource_owner_label_key\"}}" "$image_id" 2>/dev/null)" || {
        cleanup_failed=1
        continue
      }
      if [[ "$actual_id" != "$image_id" || "$actual_owner" != "$e2e_run_marker" ]]; then
        printf 'Refusing to remove a captured image whose ID no longer proves run ownership: %s.\n' "$image_id" >&2
        cleanup_failed=1
      elif ! docker image rm "$image_id" >/dev/null 2>&1; then
        printf 'Failed to remove owned image ID %s.\n' "$image_id" >&2
        cleanup_failed=1
      elif docker image inspect "$image_id" >/dev/null 2>&1; then
        cleanup_failed=1
      fi
    elif ! docker info >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  done

  for image_name in "$e2e_backend_image" "$e2e_frontend_image"; do
    if actual_id="$(docker image inspect --format '{{.Id}}' "$image_name" 2>/dev/null)"; then
      printf 'Preserved replacement or incompletely removed image tag %s (%s); cleanup failed closed.\n' \
        "$image_name" "$actual_id" >&2
      cleanup_failed=1
    elif ! docker info >/dev/null 2>&1; then
      cleanup_failed=1
    fi
  done
  return "$cleanup_failed"
}

e2e_cleanup_owned_resources() {
  local cleanup_failed=0

  if [[ "$e2e_docker_preflight_passed" == true \
     && "$e2e_resource_identity_initialized" == true ]]; then
    if [[ "$e2e_resources_captured" != true ]]; then
      # Close the normal EXIT/ERR window between Compose creating its first
      # resource and the runner's explicit post-start capture. This discovery
      # happens at most once; after an identity is captured, cleanup never
      # adopts a successor at the same name.
      e2e_capture_owned_resources || cleanup_failed=1
    fi
    e2e_cleanup_owned_containers || cleanup_failed=1
    e2e_cleanup_owned_networks || cleanup_failed=1
    e2e_cleanup_owned_volumes || cleanup_failed=1
    e2e_cleanup_owned_images || cleanup_failed=1
  fi
  return "$cleanup_failed"
}
