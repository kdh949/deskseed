#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
migration_directory="$repository_root/backend/src/main/resources/db/migration"
default_postgres_image="postgres:17-alpine"
postgres_image="${PERF_POSTGRES_IMAGE:-$default_postgres_image}"
scale="release"
output_directory=""
keep_container="false"

usage() {
  printf '%s\n' \
    "Usage: bash scripts/run-release-performance.sh [--scale smoke|release]" \
    "       [--output-dir PATH] [--keep-container]" \
    "" \
    "The default profile is release: 100k customers, 1M tickets, 2M comments," \
    "1M ticket audit events, 500k access audit rows, and 100k admin audit rows." \
    "Use --scale smoke to validate the harness with bounded local data." \
    "Validated PERF_* count variables may override a profile; every override is" \
    "recorded in the generated manifest. PERF_ACCESS_REPETITIONS controls the" \
    "committed sensitive-read access-audit A/B sample count."
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scale)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      scale="$2"
      shift 2
      ;;
    --output-dir)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      output_directory="$2"
      shift 2
      ;;
    --keep-container)
      keep_container="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$scale" in
  smoke)
    default_customer_count=1000
    default_ticket_count=10000
    default_comments_per_ticket=2
    default_ticket_audit_count=10000
    default_access_audit_count=5000
    default_admin_audit_count=1000
    default_staff_count=100
    default_group_count=10
    default_repetitions=15
    default_access_repetitions=50
    default_memory=2g
    default_cpus=2
    default_minimum_free_gib=2
    ;;
  release)
    default_customer_count=100000
    default_ticket_count=1000000
    default_comments_per_ticket=2
    default_ticket_audit_count=1000000
    default_access_audit_count=500000
    default_admin_audit_count=100000
    default_staff_count=1000
    default_group_count=100
    default_repetitions=30
    default_access_repetitions=100
    default_memory=6g
    default_cpus=2
    # Heap, indexes, temporary index builds and PostgreSQL WAL share Docker's
    # writable layer. Refuse a full run without conservative host headroom.
    default_minimum_free_gib=16
    ;;
  *)
    printf 'Unsupported scale: %s\n' "$scale" >&2
    exit 2
    ;;
esac

default_seed=424242
default_base_time="2026-08-12T00:00:00Z"
seed="${PERF_SEED:-$default_seed}"
base_time="${PERF_BASE_TIME:-$default_base_time}"
customer_count="${PERF_CUSTOMER_COUNT:-$default_customer_count}"
ticket_count="${PERF_TICKET_COUNT:-$default_ticket_count}"
comments_per_ticket="${PERF_COMMENTS_PER_TICKET:-$default_comments_per_ticket}"
ticket_audit_count="${PERF_TICKET_AUDIT_COUNT:-$default_ticket_audit_count}"
access_audit_count="${PERF_ACCESS_AUDIT_COUNT:-$default_access_audit_count}"
admin_audit_count="${PERF_ADMIN_AUDIT_COUNT:-$default_admin_audit_count}"
staff_count="${PERF_STAFF_COUNT:-$default_staff_count}"
group_count="${PERF_GROUP_COUNT:-$default_group_count}"
repetitions="${PERF_REPETITIONS:-$default_repetitions}"
access_repetitions="${PERF_ACCESS_REPETITIONS:-$default_access_repetitions}"
docker_memory="${PERF_DOCKER_MEMORY:-$default_memory}"
docker_cpus="${PERF_DOCKER_CPUS:-$default_cpus}"
minimum_free_gib="$default_minimum_free_gib"
allow_low_disk="${DESKSEED_PERF_ALLOW_LOW_DISK:-0}"
# Prospective PERF-001 acceptance boundary. This fixed local DB-component budget
# is intentionally not user-overridable and is recorded before every run.
queue_latency_budget_ms=50
# Prospective REQ-SRCH-001 database-component boundary for the exact count and
# first score page on the canonical 1M-ticket profile. This is not an HTTP SLO.
search_latency_budget_ms=250

validate_unsigned_integer() {
  local name="$1"
  local value="$2"
  case "$value" in
    ''|*[!0-9]*)
      printf '%s must be an unsigned integer, got: %s\n' "$name" "$value" >&2
      exit 2
      ;;
  esac
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  else
    shasum -a 256 "$1" | awk '{ print $1 }'
  fi
}

sha256_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{ print $1 }'
  else
    shasum -a 256 | awk '{ print $1 }'
  fi
}

performance_source_files=(
  "$repository_root/scripts/run-release-performance.sh"
  "$repository_root/scripts/release-performance-fixture.sql"
  "$repository_root/scripts/release-performance-explain.sql"
  "$repository_root/scripts/release-performance-latency.sql"
  "$repository_root/scripts/release-performance-query-cardinality.sql"
  "$repository_root/scripts/release-performance-access-overhead.sql"
  "$repository_root/scripts/release-performance-candidate-indexes.sql"
  "$repository_root/scripts/release-performance-counts.sql"
  "$repository_root/scripts/release-performance-sizes.sql"
  "$repository_root/scripts/release-performance-state.sql"
  "$repository_root/scripts/release-performance-settings.sql"
  "$repository_root/docs/performance/README.md"
  "$repository_root/docs/performance/agent-ticket-read-query-plan.md"
  "$repository_root/backend/src/main/kotlin/dev/deskseed/ticketing/StaffTicketReadApi.kt"
  "$repository_root/backend/src/main/kotlin/dev/deskseed/ticketing/internal/StaffTicketQueryRepository.kt"
  "$repository_root/backend/src/main/kotlin/dev/deskseed/ticketing/internal/StaffTicketCommandReplayStore.kt"
  "$repository_root/backend/src/main/kotlin/dev/deskseed/staffaccess/internal/AgentTicketReadApplicationService.kt"
  "$repository_root/backend/src/main/kotlin/dev/deskseed/staffaccess/internal/AgentTicketReadController.kt"
  "$repository_root/backend/src/main/kotlin/dev/deskseed/staffaccess/internal/AgentTicketSearchApplicationService.kt"
  "$repository_root/backend/src/main/kotlin/dev/deskseed/staffaccess/internal/AgentTicketSearchCursorCodec.kt"
  "$repository_root/backend/src/main/kotlin/dev/deskseed/audit/internal/JpaAccessAuditWriter.kt"
)

write_performance_source_manifest() {
  local evidence_source
  local migration_source
  local -a migration_sources
  for evidence_source in "${performance_source_files[@]}"; do
    if [[ ! -f "$evidence_source" || -L "$evidence_source" ]]; then
      printf 'Missing or unsafe performance source: %s\n' "$evidence_source" >&2
      return 1
    fi
    printf '%s %s\n' \
      "$(sha256_file "$evidence_source")" \
      "${evidence_source#"$repository_root"/}"
  done
  migration_sources=("$migration_directory"/V*.sql)
  if [[ ${#migration_sources[@]} -eq 0 \
     || ! -f "${migration_sources[0]}" ]]; then
    printf 'No performance migration sources found in %s\n' "$migration_directory" >&2
    return 1
  fi
  for migration_source in "${migration_sources[@]}"; do
    if [[ ! -f "$migration_source" || -L "$migration_source" ]]; then
      printf 'Missing or unsafe performance migration source: %s\n' \
        "$migration_source" >&2
      return 1
    fi
    printf '%s %s\n' \
      "$(sha256_file "$migration_source")" \
      "${migration_source#"$repository_root"/}"
  done
}

captured_source_fingerprint=""
capture_performance_source_fingerprint() {
  local manifest="$working_output_directory/source-manifest.txt"
  write_performance_source_manifest > "$manifest"
  captured_source_fingerprint="$(sha256_file "$manifest")"
  {
    printf 'captured_source_fingerprint=%s\n' "$captured_source_fingerprint"
    printf 'capture_stage=before_first_docker_action\n'
  } > "$working_output_directory/source-fingerprint-checks.txt"
}

verify_performance_source_fingerprint() {
  local stage="$1"
  local current_fingerprint
  current_fingerprint="$(write_performance_source_manifest | sha256_stream)" || return 1
  printf 'verified_%s=%s\n' "$stage" "$current_fingerprint" \
    >> "$working_output_directory/source-fingerprint-checks.txt"
  if [[ -z "$captured_source_fingerprint" \
     || "$current_fingerprint" != "$captured_source_fingerprint" ]]; then
    printf 'Performance source fingerprint changed at %s.\n' "$stage" >&2
    return 1
  fi
}

validate_unsigned_integer seed "$seed"
validate_unsigned_integer customer_count "$customer_count"
validate_unsigned_integer ticket_count "$ticket_count"
validate_unsigned_integer comments_per_ticket "$comments_per_ticket"
validate_unsigned_integer ticket_audit_count "$ticket_audit_count"
validate_unsigned_integer access_audit_count "$access_audit_count"
validate_unsigned_integer admin_audit_count "$admin_audit_count"
validate_unsigned_integer staff_count "$staff_count"
validate_unsigned_integer group_count "$group_count"
validate_unsigned_integer repetitions "$repetitions"
validate_unsigned_integer access_repetitions "$access_repetitions"
validate_unsigned_integer minimum_free_gib "$minimum_free_gib"
if [[ "$allow_low_disk" != "0" && "$allow_low_disk" != "1" ]]; then
  printf 'DESKSEED_PERF_ALLOW_LOW_DISK must be 0 or 1.\n' >&2
  exit 2
fi

if [[ "$customer_count" -lt 1 \
   || "$ticket_count" -lt 100 \
   || "$comments_per_ticket" -lt 1 \
   || "$ticket_audit_count" -lt 1 \
   || "$ticket_audit_count" -gt "$ticket_count" \
   || "$access_audit_count" -lt 10 \
   || "$admin_audit_count" -lt 1 \
   || "$staff_count" -lt 42 \
   || "$group_count" -lt 1 \
   || "$group_count" -gt "$staff_count" \
   || "$repetitions" -lt 3 \
   || "$repetitions" -gt 100 \
   || "$access_repetitions" -lt 30 \
   || "$access_repetitions" -gt 1000 ]]; then
  printf 'Performance fixture parameters violate the documented bounds.\n' >&2
  exit 2
fi

if [[ "$scale" == "release" ]] && {
  [[ "$customer_count" -lt 100000 ]] \
    || [[ "$ticket_count" -lt 1000000 ]] \
    || [[ "$comments_per_ticket" -lt 2 ]] \
    || [[ "$ticket_audit_count" -lt 1000000 ]] \
    || [[ "$access_audit_count" -lt 500000 ]] \
    || [[ "$admin_audit_count" -lt 100000 ]] \
    || [[ "$staff_count" -lt 1000 ]] \
    || [[ "$group_count" -lt 100 ]] \
    || [[ "$repetitions" -lt 30 ]] \
    || [[ "$access_repetitions" -lt 100 ]];
}; then
  printf 'The release profile cannot be reduced below its documented row/cardinality/repetition floor.\n' >&2
  printf 'Use --scale smoke for a non-release harness run.\n' >&2
  exit 2
fi

if [[ "$scale" == "release" ]] && [[ \
      "$postgres_image" != "$default_postgres_image" \
   || "$seed" != "$default_seed" \
   || "$base_time" != "$default_base_time" \
   || "$customer_count" != "$default_customer_count" \
   || "$ticket_count" != "$default_ticket_count" \
   || "$comments_per_ticket" != "$default_comments_per_ticket" \
   || "$ticket_audit_count" != "$default_ticket_audit_count" \
   || "$access_audit_count" != "$default_access_audit_count" \
   || "$admin_audit_count" != "$default_admin_audit_count" \
   || "$staff_count" != "$default_staff_count" \
   || "$group_count" != "$default_group_count" \
   || "$repetitions" != "$default_repetitions" \
   || "$access_repetitions" != "$default_access_repetitions" \
   || "$docker_memory" != "$default_memory" \
   || "$docker_cpus" != "$default_cpus" \
   || "$keep_container" != "false" ]]; then
  printf 'The canonical release artifact requires the exact documented profile.\n' >&2
  printf 'Use --scale smoke and a separately owned output directory for overrides.\n' >&2
  exit 2
fi

case "$base_time" in
  ????-??-??T??:??:??Z) ;;
  *)
    printf 'PERF_BASE_TIME must use UTC YYYY-MM-DDTHH:MM:SSZ form.\n' >&2
    exit 2
    ;;
esac

if [[ -z "$output_directory" ]]; then
  output_directory="$repository_root/docs/evidence/release/performance/$scale"
elif [[ "$output_directory" != /* ]]; then
  output_directory="$repository_root/$output_directory"
fi

output_name="$(basename "$output_directory")"
case "$output_name" in
  ''|.|..|*[!A-Za-z0-9._-]*)
    printf 'Performance output directory must have a safe basename, got: %s\n' \
      "$output_name" >&2
    exit 2
    ;;
esac

output_parent_input="$(dirname "$output_directory")"
if [[ ! -d "$output_parent_input" ]]; then
  printf 'Performance output parent must already exist: %s\n' \
    "$output_parent_input" >&2
  exit 2
fi
output_parent="$(cd "$output_parent_input" && pwd -P)"
output_directory="$output_parent/$output_name"
evidence_root="$(cd "$repository_root/docs/evidence/release/performance" && pwd -P)"
ownership_marker_name=".deskseed-release-performance-evidence"
ownership_marker_value="deskseed-release-performance-evidence-v1"
output_is_dedicated="false"
case "$output_directory" in
  "$evidence_root/smoke"|"$evidence_root/release")
    output_is_dedicated="true"
    ;;
esac

validate_output_target() {
  if [[ ! -e "$output_directory" && ! -L "$output_directory" ]]; then
    return 0
  fi
  if [[ -L "$output_directory" || ! -d "$output_directory" ]]; then
    printf 'Refusing non-directory or symlink performance output target: %s\n' \
      "$output_directory" >&2
    return 1
  fi
  if [[ "$output_is_dedicated" == "true" ]]; then
    return 0
  fi
  local marker="$output_directory/$ownership_marker_name"
  if [[ -L "$marker" || ! -f "$marker" \
     || "$(sed -n '1p' "$marker")" != "$ownership_marker_value" \
     || "$(wc -l < "$marker" | tr -d ' ')" != "1" ]]; then
    printf 'Refusing to replace unowned performance output directory: %s\n' \
      "$output_directory" >&2
    return 1
  fi
}

validate_staging_directory() {
  local candidate="$1"
  local candidate_parent
  local candidate_name
  local expected_prefix=".${output_name}.in-progress."
  [[ -n "$candidate" && -d "$candidate" && ! -L "$candidate" ]] || return 1
  candidate_parent="$(cd "$(dirname "$candidate")" && pwd -P)"
  candidate_name="$(basename "$candidate")"
  [[ "$candidate_parent" == "$output_parent" ]] || return 1
  case "$candidate_name" in
    "${expected_prefix}"??????) return 0 ;;
    *) return 1 ;;
  esac
}

safe_remove_previous_output() {
  local candidate="$1"
  local expected="${output_directory}.previous.$$"
  [[ "$candidate" == "$expected" \
     && -d "$candidate" \
     && ! -L "$candidate" \
     && "$(cd "$(dirname "$candidate")" && pwd -P)" == "$output_parent" ]] \
    || return 1
  rm -rf -- "$candidate"
}

safe_remove_scratch_directory() {
  local candidate="$1"
  local candidate_parent
  local expected_parent
  local candidate_name
  local expected_prefix="deskseed-release-performance."
  [[ -n "$candidate" && -d "$candidate" && ! -L "$candidate" ]] || return 1
  candidate_parent="$(cd "$(dirname "$candidate")" && pwd -P)"
  expected_parent="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
  candidate_name="$(basename "$candidate")"
  [[ "$candidate_parent" == "$expected_parent" ]] || return 1
  case "$candidate_name" in
    "${expected_prefix}"??????) rm -rf -- "$candidate" ;;
    *) return 1 ;;
  esac
}

validate_output_target || exit 2
working_output_directory="$(mktemp -d "$output_parent/.${output_name}.in-progress.XXXXXX")"
validate_staging_directory "$working_output_directory" || {
  printf 'Refusing unsafe performance staging directory: %s\n' \
    "$working_output_directory" >&2
  exit 2
}
scratch_directory=""
container_name=""
container_id=""
container_run_id=""
container_started="false"
container_owned_for_cleanup="false"
container_volume_name=""
runtime_cleanup_complete="false"
evidence_published="false"
published_nonpass_status=""
failure_phase="preflight"

capture_container_volume_name() {
  local candidate_container="$1"
  docker inspect --format \
    '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' \
    "$candidate_container"
}

docker_volume_is_absent() {
  local candidate_volume="$1"
  if docker volume inspect "$candidate_volume" >/dev/null 2>&1; then
    return 1
  fi
  # A failed inspect proves absence only while the daemon itself is reachable.
  docker info >/dev/null 2>&1
}

container_is_owned() {
  local actual_id
  local actual_run_id
  actual_id="$(docker inspect --format '{{.Id}}' "$container_id" 2>/dev/null)" \
    || return 1
  actual_run_id="$(docker inspect --format \
    '{{index .Config.Labels "dev.deskseed.release-performance-run"}}' \
    "$container_id" 2>/dev/null)" || return 1
  [[ -n "$container_id" \
     && "$actual_id" == "$container_id" \
     && "$actual_run_id" == "$container_run_id" ]]
}

write_cleanup_status() {
  local status="$1"
  local container_result="$2"
  local volume_result="$3"
  local scratch_result="$4"
  local target_directory=""
  if [[ -n "$working_output_directory" \
     && -d "$working_output_directory" ]]; then
    target_directory="$working_output_directory"
  elif [[ "$evidence_published" == "true" \
       && -d "$output_directory" ]]; then
    target_directory="$output_directory"
  else
    return 1
  fi
  {
    printf 'status=%s\n' "$status"
    printf 'container_name=%s\n' "${container_name:-NOT_STARTED}"
    printf 'container_id=%s\n' "${container_id:-NOT_STARTED}"
    printf 'data_volume=%s\n' "${container_volume_name:-NOT_CAPTURED}"
    printf 'container_cleanup=%s\n' "$container_result"
    printf 'volume_cleanup=%s\n' "$volume_result"
    printf 'scratch_cleanup=%s\n' "$scratch_result"
  } > "$target_directory/cleanup-status.txt"
}

mark_published_evidence_failed() {
  local phase="$1"
  {
    printf '# %s release performance evidence\n\n' "$scale"
    printf -- '- Result: `FAILED`\n'
    printf -- '- Failed phase: `%s`\n' "$phase"
    printf -- '- Exit code: `1`\n'
    printf -- '- Finished at: `%s`\n\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf 'The command failed after publishing files. This directory must not be treated as PASS evidence.\n'
  } > "$output_directory/summary.md"
  {
    printf 'status=FAILED\n'
    printf 'phase=%s\n' "$phase"
    printf 'exit_code=1\n'
  } > "$output_directory/run-status.txt"
}

publish_evidence() {
  local previous_output_directory="${output_directory}.previous.$$"
  validate_output_target || return 1
  if ! validate_staging_directory "$working_output_directory"; then
    printf 'Refusing unsafe performance staging publish: %s\n' \
      "$working_output_directory" >&2
    return 1
  fi
  if [[ -e "$previous_output_directory" ]]; then
    printf 'Refusing to replace existing evidence backup: %s\n' \
      "$previous_output_directory" >&2
    return 1
  fi
  if [[ -e "$output_directory" ]]; then
    mv "$output_directory" "$previous_output_directory"
  fi
  if mv "$working_output_directory" "$output_directory"; then
    working_output_directory=""
    evidence_published="true"
    if [[ -e "$previous_output_directory" ]]; then
      if ! safe_remove_previous_output "$previous_output_directory"; then
        printf 'Refusing unsafe prior evidence cleanup: %s\n' \
          "$previous_output_directory" >&2
        failure_phase="publish_previous_cleanup"
        mark_published_evidence_failed "$failure_phase"
        return 1
      fi
    fi
    return 0
  fi
  if [[ -e "$previous_output_directory" && ! -e "$output_directory" ]]; then
    mv "$previous_output_directory" "$output_directory"
  fi
  return 1
}

cleanup_runtime_resources() {
  if [[ "$runtime_cleanup_complete" == "true" ]]; then
    return 0
  fi
  local cleanup_failed=0
  local container_result="NOT_STARTED"
  local volume_result="NOT_STARTED"
  local scratch_result="NOT_CREATED"
  if [[ "$container_started" == "true" ]]; then
    if [[ "$keep_container" == "true" ]]; then
      printf 'Kept performance container and data volume: %s / %s\n' \
        "$container_name" "${container_volume_name:-unknown}"
      container_result="KEPT_BY_REQUEST"
      volume_result="KEPT_BY_REQUEST"
    elif ! container_is_owned; then
      printf 'Refusing to remove a performance container whose ownership changed: %s\n' \
        "$container_name" >&2
      failure_phase="cleanup_container_ownership"
      container_result="REFUSED_OWNERSHIP_MISMATCH"
      volume_result="NOT_ATTEMPTED"
      cleanup_failed=1
    else
      container_owned_for_cleanup="true"
      if [[ -z "$container_volume_name" ]]; then
        container_volume_name="$(capture_container_volume_name "$container_id" 2>/dev/null || true)"
      fi
      case "$container_volume_name" in
        ''|*[!A-Za-z0-9_.-]*)
          printf 'Failed to identify a safe performance data volume before cleanup.\n' >&2
          failure_phase="cleanup_volume_identity"
          volume_result="IDENTITY_NOT_CAPTURED"
          cleanup_failed=1
          ;;
        *) volume_result="CAPTURED" ;;
      esac
      if docker rm --force --volumes "$container_id" >/dev/null 2>&1 \
         && ! docker inspect "$container_id" >/dev/null 2>&1 \
         && docker info >/dev/null 2>&1; then
        container_result="REMOVED_AND_VERIFIED_ABSENT"
        container_started="false"
      else
        printf 'Failed to remove or verify performance container absence: %s\n' \
          "$container_name" >&2
        failure_phase="cleanup_container"
        container_result="FAILED"
        cleanup_failed=1
      fi
    fi
  fi
  if [[ "$keep_container" != "true" \
     && "$container_owned_for_cleanup" == "true" \
     && -z "$container_volume_name" ]]; then
    printf 'Performance data volume identity remains unavailable for cleanup verification.\n' >&2
    failure_phase="cleanup_volume_identity"
    volume_result="IDENTITY_NOT_CAPTURED"
    cleanup_failed=1
  fi
  if [[ "$keep_container" != "true" \
     && "$container_owned_for_cleanup" == "true" \
     && -n "$container_volume_name" \
     && "$container_volume_name" != *[!A-Za-z0-9_.-]* ]]; then
    if ! docker_volume_is_absent "$container_volume_name"; then
      if ! docker volume rm "$container_volume_name" >/dev/null 2>&1 \
         || ! docker_volume_is_absent "$container_volume_name"; then
        printf 'Failed to remove performance data volume: %s\n' \
          "$container_volume_name" >&2
        failure_phase="cleanup_volume"
        volume_result="FAILED"
        cleanup_failed=1
      fi
    fi
    if docker_volume_is_absent "$container_volume_name"; then
      volume_result="REMOVED_AND_VERIFIED_ABSENT"
    fi
  fi
  if [[ -n "$scratch_directory" && -d "$scratch_directory" ]]; then
    if safe_remove_scratch_directory "$scratch_directory"; then
      scratch_result="REMOVED_AND_VERIFIED_ABSENT"
    else
      printf 'Refusing unsafe performance scratch cleanup: %s\n' \
        "$scratch_directory" >&2
      failure_phase="cleanup_scratch"
      scratch_result="FAILED"
      cleanup_failed=1
    fi
  fi
  if [[ "$cleanup_failed" -eq 0 ]]; then
    if write_cleanup_status \
        "PASS" "$container_result" "$volume_result" "$scratch_result"; then
      runtime_cleanup_complete="true"
    else
      printf 'Failed to persist performance cleanup verification.\n' >&2
      failure_phase="cleanup_evidence"
      cleanup_failed=1
    fi
  else
    write_cleanup_status "FAILED" "$container_result" "$volume_result" "$scratch_result" \
      || true
  fi
  return "$cleanup_failed"
}

cleanup() {
  local exit_code="$?"
  local cleanup_failed="false"
  trap - EXIT
  if ! cleanup_runtime_resources; then
    exit_code=1
    cleanup_failed="true"
  fi
  if [[ "$exit_code" -ne 0 && "$evidence_published" == "true" ]]; then
    if [[ "$published_nonpass_status" != "NOT_RUN" \
       || "$exit_code" -ne 2 \
       || "$cleanup_failed" != "false" ]]; then
      mark_published_evidence_failed "$failure_phase"
    fi
  fi
  if [[ "$exit_code" -ne 0 \
     && "$evidence_published" != "true" \
     && -n "$working_output_directory" \
     && -d "$working_output_directory" ]]; then
    {
      printf '# %s release performance evidence\n\n' "$scale"
      printf -- '- Result: `FAILED`\n'
      printf -- '- Failed phase: `%s`\n' "$failure_phase"
      printf -- '- Exit code: `%s`\n' "$exit_code"
      printf -- '- Finished at: `%s`\n\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
      printf 'Files in this directory are partial failure diagnostics, not PASS evidence.\n'
    } > "$working_output_directory/summary.md"
    {
      printf 'status=FAILED\n'
      printf 'phase=%s\n' "$failure_phase"
      printf 'exit_code=%s\n' "$exit_code"
    } > "$working_output_directory/run-status.txt"
    publish_evidence || printf 'Failed evidence remains at %s\n' \
      "$working_output_directory" >&2
  fi
  exit "$exit_code"
}
trap cleanup EXIT

printf '%s\n' "$ownership_marker_value" \
  > "$working_output_directory/$ownership_marker_name"
{
  printf '# %s release performance evidence\n\n' "$scale"
  printf -- '- Result: `IN PROGRESS`\n'
  printf -- '- Started at: `%s`\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
} > "$working_output_directory/summary.md"
printf 'status=IN_PROGRESS\nphase=preflight\n' \
  > "$working_output_directory/run-status.txt"

failure_phase="capture_source_fingerprint"
capture_performance_source_fingerprint

calculate_capacity() {
  local sizes_file="$1"
  local target_customers="$2"
  local target_tickets="$3"
  local target_comments="$4"
  local target_ticket_audits="$5"
  local target_access_audits="$6"
  local target_admin_audits="$7"
  local target_projection="$8"
  local source_customers="$9"
  local source_tickets="${10}"
  local source_comments="${11}"
  local source_ticket_audits="${12}"
  local source_access_audits="${13}"
  local source_admin_audits="${14}"
  local source_projection="${15}"
  awk -F, \
    -v target_customers="$target_customers" \
    -v target_tickets="$target_tickets" \
    -v target_comments="$target_comments" \
    -v target_ticket_audits="$target_ticket_audits" \
    -v target_access_audits="$target_access_audits" \
    -v target_admin_audits="$target_admin_audits" \
    -v target_projection="$target_projection" \
    -v source_customers="$source_customers" \
    -v source_tickets="$source_tickets" \
    -v source_comments="$source_comments" \
    -v source_ticket_audits="$source_ticket_audits" \
    -v source_access_audits="$source_access_audits" \
    -v source_admin_audits="$source_admin_audits" \
    -v source_projection="$source_projection" '
      function ratio(object_name) {
        if (object_name == "customers") return target_customers / source_customers
        if (object_name == "tickets") return target_tickets / source_tickets
        if (object_name == "ticket_search_documents") return target_tickets / source_tickets
        if (object_name == "ticket_comments") return target_comments / source_comments
        if (object_name == "ticket_audits") return target_ticket_audits / source_ticket_audits
        if (object_name == "ticket_audit_events") return target_ticket_audits / source_ticket_audits
        if (object_name == "access_audit_events") return target_access_audits / source_access_audits
        if (object_name == "admin_security_audit_events") return target_admin_audits / source_admin_audits
        if (object_name == "audit_activity_projection") return target_projection / source_projection
        return 0
      }
      NR > 1 && ($2 == "table_heap" || $2 == "table_indexes") {
        estimate += $4 * ratio($3)
      }
      END {
        relation_gib = estimate / 1024 / 1024 / 1024
        transient_gib = relation_gib * 3
        recommended_gib = int(transient_gib + 4)
        if (recommended_gib < transient_gib + 4) recommended_gib++
        printf "%.0f,%.3f,%.3f,%d", estimate, relation_gib, transient_gib, recommended_gib
      }
    ' "$sizes_file"
}

capacity_source="$repository_root/docs/evidence/release/performance/smoke/sizes-after.csv"
capacity_available="false"
estimated_relation_bytes=""
estimated_relation_gib=""
estimated_transient_gib=""
estimated_recommended_free_gib=""
if [[ -f "$capacity_source" ]]; then
  target_comment_count=$((ticket_count * comments_per_ticket))
  target_projection_count=$((ticket_audit_count + access_audit_count + admin_audit_count))
  capacity_values="$(calculate_capacity \
    "$capacity_source" \
    "$customer_count" \
    "$ticket_count" \
    "$target_comment_count" \
    "$ticket_audit_count" \
    "$access_audit_count" \
    "$admin_audit_count" \
    "$target_projection_count" \
    1000 \
    10000 \
    20000 \
    10000 \
    5000 \
    1000 \
    16000)"
  previous_ifs="$IFS"
  IFS=,
  capacity_parts=($capacity_values)
  IFS="$previous_ifs"
  estimated_relation_bytes="${capacity_parts[0]}"
  estimated_relation_gib="${capacity_parts[1]}"
  estimated_transient_gib="${capacity_parts[2]}"
  estimated_recommended_free_gib="${capacity_parts[3]}"
  capacity_available="true"
  if [[ "$estimated_recommended_free_gib" -gt "$minimum_free_gib" ]]; then
    minimum_free_gib="$estimated_recommended_free_gib"
  fi
fi

available_kib="$(df -Pk "$repository_root" | awk 'NR == 2 { print $4 }')"
available_bytes=$((available_kib * 1024))
minimum_free_bytes=$((minimum_free_gib * 1024 * 1024 * 1024))
available_gib="$(awk -v bytes="$available_bytes" 'BEGIN { printf "%.2f", bytes / 1024 / 1024 / 1024 }')"
measured_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

if [[ "$available_bytes" -lt "$minimum_free_bytes" && "$allow_low_disk" != "1" ]]; then
  {
    printf '# %s performance preflight\n\n' "$scale"
    printf -- '- Status: `NOT RUN`\n'
    printf -- '- Measured at: `%s`\n' "$measured_at"
    printf -- '- Available host filesystem space: `%s GiB`\n' "$available_gib"
    printf -- '- Required safety headroom: `%s GiB`\n' "$minimum_free_gib"
    printf -- '- Measurement boundary: host repository filesystem only; Docker data-root/VM quota is not measured\n'
    if [[ "$capacity_available" == "true" ]]; then
      printf -- '- Smoke-relation projection for requested counts: `%s GiB`\n' "$estimated_relation_gib"
      printf -- '- Estimated transient relation/index/WAL budget (3x): `%s GiB`\n' "$estimated_transient_gib"
    fi
    printf '\nThe fixture was not started. PostgreSQL heap, indexes, temporary index builds, '
    printf 'and WAL share Docker Desktop storage; overriding this guard without first '
    printf 'freeing space can exhaust the host filesystem. No release-scale result is claimed. '
    printf 'An operator can explicitly accept that risk with '
    printf '`DESKSEED_PERF_ALLOW_LOW_DISK=1`; the override is recorded.\n'
  } > "$working_output_directory/preflight.md"
  {
    printf '# %s release performance evidence\n\n' "$scale"
    printf -- '- Result: `NOT RUN`\n'
    printf -- '- Reason: insufficient disk headroom\n'
  } > "$working_output_directory/summary.md"
  printf 'status=NOT_RUN\nphase=preflight\nexit_code=2\n' \
    > "$working_output_directory/run-status.txt"
  published_nonpass_status="NOT_RUN"
  cleanup_runtime_resources
  publish_evidence
  printf 'Insufficient disk headroom: %s GiB available, %s GiB required.\n' \
    "$available_gib" "$minimum_free_gib" >&2
  exit 2
fi

{
  printf '# %s performance preflight\n\n' "$scale"
  if [[ "$available_bytes" -lt "$minimum_free_bytes" ]]; then
    printf -- '- Status: `PASS WITH EXPLICIT LOW-DISK OVERRIDE`\n'
  else
    printf -- '- Status: `PASS`\n'
  fi
  printf -- '- Measured at: `%s`\n' "$measured_at"
  printf -- '- Available host filesystem space before run: `%s GiB`\n' "$available_gib"
  printf -- '- Required safety headroom: `%s GiB`\n' "$minimum_free_gib"
  printf -- '- Measurement boundary: host repository filesystem only; Docker data-root/VM quota must be checked separately\n'
  printf -- '- `DESKSEED_PERF_ALLOW_LOW_DISK`: `%s`\n' "$allow_low_disk"
  if [[ "$capacity_available" == "true" ]]; then
    printf -- '- Smoke-relation projection for requested counts: `%s GiB`\n' "$estimated_relation_gib"
    printf -- '- Estimated transient relation/index/WAL budget (3x): `%s GiB`\n' "$estimated_transient_gib"
  fi
} > "$working_output_directory/preflight.md"

scratch_directory="$(mktemp -d "${TMPDIR:-/tmp}/deskseed-release-performance.XXXXXX")"
container_run_id="${scale}-$(basename "$scratch_directory" | sed 's/^deskseed-release-performance\.//')-$$-${RANDOM}"
container_name="deskseed-release-perf-$container_run_id"

wait_for_database() {
  local database_ready="false"
  local attempt
  for attempt in $(seq 1 60); do
    if docker exec "$container_id" \
        psql --no-psqlrc --set=ON_ERROR_STOP=1 \
          --username deskseed --dbname deskseed_perf \
          --command 'select 1;' >/dev/null 2>&1; then
      database_ready="true"
      break
    fi
    sleep 1
  done
  if [[ "$database_ready" != "true" ]]; then
    docker logs "$container_id" >&2
    printf 'Performance database did not become ready.\n' >&2
    exit 1
  fi
}

run_psql() {
  docker exec --interactive "$container_id" \
    psql --no-psqlrc --set=ON_ERROR_STOP=1 \
      --username deskseed --dbname deskseed_perf "$@"
}

record_duration() {
  local phase="$1"
  local start_epoch="$2"
  local end_epoch
  end_epoch="$(date +%s)"
  printf '%s,%s\n' "$phase" "$((end_epoch - start_epoch))" >> "$working_output_directory/durations.csv"
}

printf 'phase,duration_seconds\n' > "$working_output_directory/durations.csv"

run_started_epoch="$(date +%s)"
failure_phase="start_postgresql"
if ! container_id="$(docker run --detach \
    --name "$container_name" \
    --label dev.deskseed.release-performance=true \
    --label "dev.deskseed.release-performance-run=$container_run_id" \
    --network none \
    --memory "$docker_memory" \
    --cpus "$docker_cpus" \
    --shm-size 256m \
    --env POSTGRES_DB=deskseed_perf \
    --env POSTGRES_USER=deskseed \
    --env POSTGRES_HOST_AUTH_METHOD=trust \
    "$postgres_image")"; then
  candidate_run_id="$(docker inspect --format \
    '{{index .Config.Labels "dev.deskseed.release-performance-run"}}' \
    "$container_name" 2>/dev/null || true)"
  if [[ "$candidate_run_id" == "$container_run_id" ]]; then
    container_id="$(docker inspect --format '{{.Id}}' "$container_name")"
    container_started="true"
    container_owned_for_cleanup="true"
  fi
  exit 1
fi
container_started="true"
container_owned_for_cleanup="true"
failure_phase="capture_postgresql_volume"
container_volume_name="$(capture_container_volume_name "$container_id")"
case "$container_volume_name" in
  ''|*[!A-Za-z0-9_.-]*)
    printf 'Could not identify a safe PostgreSQL data volume for cleanup: %s\n' \
      "$container_volume_name" >&2
    exit 1
    ;;
esac
wait_for_database

migration_log="$working_output_directory/migrations.csv"
printf 'migration,duration_seconds\n' > "$migration_log"
migrations_started_epoch="$(date +%s)"
failure_phase="apply_schema_migrations"
for migration_version in $(seq 1 200); do
  for migration in "$migration_directory/V${migration_version}__"*.sql; do
    [[ -f "$migration" ]] || continue
    migration_started_epoch="$(date +%s)"
    if ! run_psql --quiet --single-transaction \
        < "$migration" > "$scratch_directory/migration.log" 2>&1; then
      printf 'Migration failed: %s\n' "$(basename "$migration")" >&2
      sed -n '1,120p' "$scratch_directory/migration.log" >&2
      exit 1
    fi
    migration_finished_epoch="$(date +%s)"
    printf '%s,%s\n' \
      "$(basename "$migration")" \
      "$((migration_finished_epoch - migration_started_epoch))" >> "$migration_log"
  done
done
record_duration "apply_schema_migrations" "$migrations_started_epoch"
failure_phase="verify_source_after_migrations"
verify_performance_source_fingerprint "after_migrations"

fixture_arguments=(
  --set="seed=$seed"
  --set="base_time=$base_time"
  --set="customer_count=$customer_count"
  --set="ticket_count=$ticket_count"
  --set="comments_per_ticket=$comments_per_ticket"
  --set="ticket_audit_count=$ticket_audit_count"
  --set="access_audit_count=$access_audit_count"
  --set="admin_audit_count=$admin_audit_count"
  --set="staff_count=$staff_count"
  --set="group_count=$group_count"
)

fixture_started_epoch="$(date +%s)"
failure_phase="load_fixture_and_rebuild_projection"
run_psql "${fixture_arguments[@]}" \
  < "$repository_root/scripts/release-performance-fixture.sql" \
  > "$working_output_directory/fixture-load.log" 2>&1
record_duration "load_fixture_and_rebuild_projection" "$fixture_started_epoch"

run_psql --csv --quiet "${fixture_arguments[@]}" \
  < "$repository_root/scripts/release-performance-counts.sql" \
  > "$working_output_directory/counts.csv" 2>&1
run_psql --csv --quiet \
  < "$repository_root/scripts/release-performance-state.sql" \
  > "$working_output_directory/projection-state.csv" 2>&1

if rg -q ',f$' "$working_output_directory/counts.csv"; then
  printf 'Fixture count or reference integrity check failed.\n' >&2
  exit 1
fi
if ! rg -q '^CURRENT,[0-9]+,t$' "$working_output_directory/projection-state.csv"; then
  printf 'Audit projection rebuild did not finish CURRENT.\n' >&2
  exit 1
fi
failure_phase="verify_source_after_fixture"
verify_performance_source_fingerprint "after_fixture"

drop_started_epoch="$(date +%s)"
failure_phase="measure_without_candidate_indexes"
run_psql --set="mode=drop" \
  < "$repository_root/scripts/release-performance-candidate-indexes.sql" \
  > "$working_output_directory/candidate-indexes-drop.log" 2>&1
record_duration "drop_candidate_indexes" "$drop_started_epoch"

docker restart "$container_id" >/dev/null
wait_for_database
run_psql --command 'select pg_stat_reset();' \
  > "$working_output_directory/pg-stat-reset-before.log" 2>&1

run_psql --set="seed=$seed" --set="base_time=$base_time" \
  < "$repository_root/scripts/release-performance-explain.sql" \
  > "$working_output_directory/plans-before.txt" 2>&1
run_psql --csv --quiet \
  --set="phase=before" \
  --set="repetitions=$repetitions" \
  --set="seed=$seed" \
  --set="base_time=$base_time" \
  < "$repository_root/scripts/release-performance-latency.sql" \
  > "$working_output_directory/latency-before.csv" 2>&1
run_psql --csv --quiet --set="phase=before" \
  < "$repository_root/scripts/release-performance-sizes.sql" \
  > "$working_output_directory/sizes-before.csv" 2>&1

create_started_epoch="$(date +%s)"
failure_phase="measure_with_candidate_indexes"
run_psql --set="mode=create" \
  < "$repository_root/scripts/release-performance-candidate-indexes.sql" \
  > "$working_output_directory/candidate-indexes-create.log" 2>&1
record_duration "create_candidate_indexes" "$create_started_epoch"

docker restart "$container_id" >/dev/null
wait_for_database
run_psql --command 'select pg_stat_reset();' \
  > "$working_output_directory/pg-stat-reset-after.log" 2>&1

run_psql --set="seed=$seed" --set="base_time=$base_time" \
  < "$repository_root/scripts/release-performance-explain.sql" \
  > "$working_output_directory/plans-after.txt" 2>&1
run_psql --csv --quiet \
  --set="phase=after" \
  --set="repetitions=$repetitions" \
  --set="seed=$seed" \
  --set="base_time=$base_time" \
  < "$repository_root/scripts/release-performance-latency.sql" \
  > "$working_output_directory/latency-after.csv" 2>&1
run_psql --csv --quiet --set="phase=after" \
  < "$repository_root/scripts/release-performance-sizes.sql" \
  > "$working_output_directory/sizes-after.csv" 2>&1
run_psql --csv --quiet \
  < "$repository_root/scripts/release-performance-settings.sql" \
  > "$working_output_directory/database-settings.csv" 2>&1
failure_phase="verify_source_after_measurement"
verify_performance_source_fingerprint "after_measurement"

expected_query_names=(
  queue_my_open_first_page
  queue_unassigned_my_groups_first_page
  queue_pending_first_page
  queue_recently_solved_first_page
  queue_my_child_tasks_first_page
  search_agent_workspace_exact_count
  search_agent_workspace_score_first_page
  audit_first_cursor_page
  audit_projection_status
  staff_command_replay_lookup
  audit_actor_and_date
  audit_ticket_and_date
  audit_action_and_date
)
for query_name in "${expected_query_names[@]}"; do
  if ! awk -F, -v expected="$query_name" \
      'NR > 1 && $1 == expected { found = 1 } END { exit !found }' \
      "$working_output_directory/latency-before.csv"; then
    printf 'Missing before-latency query: %s\n' "$query_name" >&2
    exit 1
  fi
  if ! awk -F, -v expected="$query_name" \
      'NR > 1 && $1 == expected { found = 1 } END { exit !found }' \
      "$working_output_directory/latency-after.csv"; then
    printf 'Missing after-latency query: %s\n' "$query_name" >&2
    exit 1
  fi
  plan_heading="$(printf '%s' "$query_name" | tr '[:lower:]' '[:upper:]')"
  if ! rg -q "^${plan_heading}$" "$working_output_directory/plans-before.txt"; then
    printf 'Missing before-plan query: %s\n' "$plan_heading" >&2
    exit 1
  fi
  if ! rg -q "^${plan_heading}$" "$working_output_directory/plans-after.txt"; then
    printf 'Missing after-plan query: %s\n' "$plan_heading" >&2
    exit 1
  fi
done
if [[ "$(awk 'END { print NR - 1 }' "$working_output_directory/latency-before.csv")" \
      -ne "${#expected_query_names[@]}" \
   || "$(awk 'END { print NR - 1 }' "$working_output_directory/latency-after.csv")" \
      -ne "${#expected_query_names[@]}" ]]; then
  printf 'Unexpected query count in latency evidence.\n' >&2
  exit 1
fi

{
  printf 'query_name,p95_ms,budget_ms,within_budget\n'
  awk -F, -v budget="$queue_latency_budget_ms" '
    NR > 1 && $1 ~ /^queue_/ {
      printf "%s,%s,%s,%s\n", $1, $6, budget, (($6 + 0) <= budget ? "t" : "f")
    }
  ' "$working_output_directory/latency-after.csv"
} > "$working_output_directory/queue-latency-budget.csv"
if [[ "$(awk 'END { print NR - 1 }' "$working_output_directory/queue-latency-budget.csv")" -ne 5 \
   || "$(awk -F, 'NR > 1 && $4 == "f" { failures++ } END { print failures + 0 }' \
       "$working_output_directory/queue-latency-budget.csv")" -ne 0 ]]; then
  printf 'A production queue query exceeded the fixed %s ms p95 budget.\n' \
    "$queue_latency_budget_ms" >&2
  exit 1
fi

{
  printf 'query_name,p95_ms,budget_ms,within_budget\n'
  awk -F, -v budget="$search_latency_budget_ms" '
    NR > 1 && $1 ~ /^search_agent_workspace_/ {
      printf "%s,%s,%s,%s\n", $1, $6, budget, (($6 + 0) <= budget ? "t" : "f")
    }
  ' "$working_output_directory/latency-after.csv"
} > "$working_output_directory/search-latency-budget.csv"
if [[ "$(awk 'END { print NR - 1 }' "$working_output_directory/search-latency-budget.csv")" -ne 2 \
   || "$(awk -F, 'NR > 1 && $4 == "f" { failures++ } END { print failures + 0 }' \
       "$working_output_directory/search-latency-budget.csv")" -ne 0 ]]; then
  printf 'An Agent Workspace search query exceeded the fixed %s ms p95 budget.\n' \
    "$search_latency_budget_ms" >&2
  exit 1
fi

run_psql --csv --quiet \
  --set="seed=$seed" \
  --set="base_time=$base_time" \
  < "$repository_root/scripts/release-performance-query-cardinality.sql" \
  > "$working_output_directory/query-cardinality.csv" 2>&1
if rg -q ',f$' "$working_output_directory/query-cardinality.csv"; then
  printf 'A production queue query lacks representative fixture cardinality.\n' >&2
  exit 1
fi

failure_phase="measure_access_audit_overhead"
run_psql --csv --quiet \
  --set="seed=$seed" \
  --set="base_time=$base_time" \
  --set="access_repetitions=$access_repetitions" \
  < "$repository_root/scripts/release-performance-access-overhead.sql" \
  > "$working_output_directory/access-audit-overhead.csv" 2>&1
failure_phase="verify_source_after_access_measurement"
verify_performance_source_fingerprint "after_access_measurement"

image_digest="$(docker image inspect "$postgres_image" --format '{{index .RepoDigests 0}}')"
postgres_version="$(run_psql --tuples-only --no-align --command 'show server_version;')"
docker_description="$(docker info --format 'server={{.ServerVersion}} os={{.OperatingSystem}} arch={{.Architecture}} cpus={{.NCPU}} memory_bytes={{.MemTotal}}')"
git_revision="$(git -C "$repository_root" rev-parse HEAD)"
if [[ -n "$(git -C "$repository_root" status --porcelain)" ]]; then
  git_dirty="true"
else
  git_dirty="false"
fi

{
  printf 'measured_at_utc=%s\n' "$measured_at"
  printf 'scale=%s\n' "$scale"
  printf 'git_revision=%s\n' "$git_revision"
  printf 'git_worktree_dirty=%s\n' "$git_dirty"
  printf 'host_os=%s\n' "$(uname -s)"
  printf 'host_arch=%s\n' "$(uname -m)"
  printf 'docker=%s\n' "$docker_description"
  printf 'container_cpu_limit=%s\n' "$docker_cpus"
  printf 'container_memory_limit=%s\n' "$docker_memory"
  printf 'container_network=none\n'
  printf 'postgres_image=%s\n' "$postgres_image"
  printf 'postgres_image_digest=%s\n' "$image_digest"
  printf 'postgres_server_version=%s\n' "$postgres_version"
  printf 'seed=%s\n' "$seed"
  printf 'base_time=%s\n' "$base_time"
  printf 'customer_count=%s\n' "$customer_count"
  printf 'ticket_count=%s\n' "$ticket_count"
  printf 'comments_per_ticket=%s\n' "$comments_per_ticket"
  printf 'ticket_audit_count=%s\n' "$ticket_audit_count"
  printf 'access_audit_count=%s\n' "$access_audit_count"
  printf 'admin_audit_count=%s\n' "$admin_audit_count"
  printf 'staff_count=%s\n' "$staff_count"
  printf 'group_count=%s\n' "$group_count"
  printf 'latency_repetitions=%s\n' "$repetitions"
  printf 'access_overhead_repetitions=%s\n' "$access_repetitions"
  printf 'queue_p95_budget_ms=%s\n' "$queue_latency_budget_ms"
  printf 'search_p95_budget_ms=%s\n' "$search_latency_budget_ms"
  printf 'allow_low_disk=%s\n' "$allow_low_disk"
  printf 'keep_container=%s\n' "$keep_container"
  printf 'captured_source_fingerprint=%s\n' "$captured_source_fingerprint"
  while read -r source_hash source_path; do
    printf 'source_sha256=%s %s\n' "$source_hash" "$source_path"
  done < "$working_output_directory/source-manifest.txt"
} > "$working_output_directory/environment.txt"

record_duration "total" "$run_started_epoch"

release_comment_count=$((1000000 * 2))
release_projection_count=$((1000000 + 500000 + 100000))
source_comment_count=$((ticket_count * comments_per_ticket))
source_projection_count=$((ticket_audit_count + access_audit_count + admin_audit_count))
release_capacity_values="$(calculate_capacity \
  "$working_output_directory/sizes-after.csv" \
  100000 \
  1000000 \
  "$release_comment_count" \
  1000000 \
  500000 \
  100000 \
  "$release_projection_count" \
  "$customer_count" \
  "$ticket_count" \
  "$source_comment_count" \
  "$ticket_audit_count" \
  "$access_audit_count" \
  "$admin_audit_count" \
  "$source_projection_count")"
previous_ifs="$IFS"
IFS=,
release_capacity_parts=($release_capacity_values)
IFS="$previous_ifs"
release_estimated_relation_bytes="${release_capacity_parts[0]}"
release_estimated_relation_gib="${release_capacity_parts[1]}"
release_estimated_transient_gib="${release_capacity_parts[2]}"
release_recommended_free_gib="${release_capacity_parts[3]}"

measured_relation_bytes="$(awk -F, '
  NR > 1 && ($2 == "table_heap" || $2 == "table_indexes") { total += $4 }
  END { printf "%.0f", total }
' "$working_output_directory/sizes-after.csv")"
measured_relation_mib="$(awk -v bytes="$measured_relation_bytes" \
  'BEGIN { printf "%.2f", bytes / 1024 / 1024 }')"
{
  printf '# Release capacity estimate from measured relation sizes\n\n'
  printf -- '- Source profile: `%s`\n' "$scale"
  printf -- '- Measured table heap + index bytes: `%s` (`%s MiB`)\n' \
    "$measured_relation_bytes" "$measured_relation_mib"
  printf -- '- Projected default release relation bytes: `%s` (`%s GiB`)\n' \
    "$release_estimated_relation_bytes" "$release_estimated_relation_gib"
  printf -- '- Transient relation/index/WAL allowance: `3x` (`%s GiB`)\n' \
    "$release_estimated_transient_gib"
  printf -- '- Additional host reserve: `4 GiB`\n'
  printf -- '- Recommended free space before full run: `%s GiB`\n' \
    "$release_recommended_free_gib"
  printf '\nThe projection scales each measured table heap and aggregate table-index size by '
  printf 'that table family’s row-count ratio from the recorded source profile to the default '
  printf 'release profile. When the source is already the release profile, the projection is '
  printf 'the measured full-scale relation footprint. '
  printf 'PostgreSQL page fill, B-tree height and WAL are nonlinear; the 3x allowance and '
  printf '4 GiB reserve deliberately keep the runner from treating a linear estimate as exact.\n'
} > "$working_output_directory/capacity-estimate.md"

failure_phase="render_summary"
{
  printf '# %s release performance evidence\n\n' "$scale"
  printf -- '- Result: `PASS`\n'
  printf -- '- Command: `bash scripts/run-release-performance.sh --scale %s`\n' "$scale"
  printf -- '- Fixture seed: `%s`; base time: `%s`\n' "$seed" "$base_time"
  printf -- '- PostgreSQL: `%s`; container limit: `%s CPU / %s memory`\n' \
    "$postgres_version" "$docker_cpus" "$docker_memory"
  printf '\n## Verified counts\n\n'
  printf '| Entity | Actual | Expected | Match |\n'
  printf '|---|---:|---:|:---:|\n'
  awk -F, 'NR > 1 { printf "| `%s` | %s | %s | %s |\n", $1, $2, $3, $4 }' \
    "$working_output_directory/counts.csv"
  printf '\n## Production queue cardinality\n\n'
  printf '| Query | Eligible rows | Returned first-page rows | Representative |\n'
  printf '|---|---:|---:|:---:|\n'
  awk -F, 'NR > 1 { printf "| `%s` | %s | %s | %s |\n", $1, $2, $3, $4 }' \
    "$working_output_directory/query-cardinality.csv"
  printf '\n## PERF-001 local queue latency budget\n\n'
  printf 'The fixed acceptance boundary declared before this run is warm-cache database-component '
  printf 'p95 <= `%s ms` for every exact DefaultStaffView query on the recorded 2-CPU / 6-GiB ' \
    "$queue_latency_budget_ms"
  printf 'release container profile, with one bounded joined SQL statement, a 51-row first page, '
  printf 'and representative fixture cardinality.\n\n'
  printf '| Query | After p95 (ms) | Budget (ms) | Pass |\n'
  printf '|---|---:|---:|:---:|\n'
  awk -F, 'NR > 1 { printf "| `%s` | %s | %s | %s |\n", $1, $2, $3, $4 }' \
    "$working_output_directory/queue-latency-budget.csv"
  printf '\n## REQ-SRCH-001 local search latency budget\n\n'
  printf 'The fixed acceptance boundary declared before this run is warm-cache database-component '
  printf 'p95 <= `%s ms` for both the exact result count and first score page on the recorded ' \
    "$search_latency_budget_ms"
  printf '1M-ticket, 2M-comment, 2-CPU / 6-GiB PostgreSQL profile. It is not an HTTP SLO.\n\n'
  printf '| Query | After p95 (ms) | Budget (ms) | Pass |\n'
  printf '|---|---:|---:|:---:|\n'
  awk -F, 'NR > 1 { printf "| `%s` | %s | %s | %s |\n", $1, $2, $3, $4 }' \
    "$working_output_directory/search-latency-budget.csv"
  printf '\n## Warm-cache server-side latency\n\n'
  printf '| Query | Before p50 (ms) | Before p95 (ms) | After p50 (ms) | After p95 (ms) | p95 change |\n'
  printf '|---|---:|---:|---:|---:|---:|\n'
  awk -F, '
    NR == FNR {
      if (FNR > 1) { before_p50[$1] = $5; before_p95[$1] = $6 }
      next
    }
    FNR > 1 {
      delta = before_p95[$1] == 0 ? 0 : (($6 - before_p95[$1]) / before_p95[$1]) * 100
      printf "| `%s` | %s | %s | %s | %s | %+.1f%% |\n", $1, before_p50[$1], before_p95[$1], $5, $6, delta
    }
  ' "$working_output_directory/latency-before.csv" "$working_output_directory/latency-after.csv"
  printf '\nAll five queue rows are exact current `StaffTicketQueryRepository.list` '
  printf 'shapes with empty optional filters, `cursor = null`, and the API default '
  printf '`limit + 1 = 51`. The measured `DefaultStaffView` cases are `MY_OPEN`, '
  printf '`UNASSIGNED_MY_GROUPS`, `PENDING`, `RECENTLY_SOLVED`, and `MY_CHILD_TASKS`; '
  printf 'there is no '
  printf '`PENDING_OR_ON_HOLD` or `RECENTLY_UPDATED` view. No synthetic queue '
  printf 'control query is mixed into this table.\n'
  printf '\nThe staff_command_replay_lookup row is the exact production receipt lookup '
  printf 'against ticket_audits, including the first-event metadata projection and '
  printf 'limit-2 duplicate detection. Migration V14’s partial replay index remains '
  printf 'installed in both phases; the before/after candidate-index comparison does '
  printf 'not remove this command-path correctness index.\n'
  printf '\nThe audit_projection_status row is the exact list-endpoint status query: '
  printf 'it derives transient `REBUILDING` from the advisory lock and reads the '
  printf 'stored projection count. It does not execute `count(*)` over the projection.\n'
  printf '\nThe `before` phase temporarily removes only '
  printf '`tickets_assignee_status_cursor_idx` and '
  printf '`audit_activity_projection_actor_cursor_idx`. The `after` phase recreates '
  printf 'their exact current migration definitions. This run does not add a schema '
  printf 'index; it validates whether the existing candidates earn their storage cost.\n'
  printf '\n## Required access-audit write overhead (PERF-003)\n\n'
  printf '| Phase | Samples | p50 (ms) | p95 (ms) | Throughput (ops/s) | Rows/op | Relation B/op | WAL B/op |\n'
  printf '|---|---:|---:|---:|---:|---:|---:|---:|\n'
  awk -F, 'NR > 1 {
    printf "| `%s` | %s | %s | %s | %s | %s | %s | %s |\n", \
      $1, $2, $3, $4, $5, $11, $12, $13
  }' "$working_output_directory/access-audit-overhead.csv"
  awk -F, '
    NR > 1 && $1 == "without_required_access_audit" {
      base_p50 = $3; base_p95 = $4; base_tps = $5
    }
    NR > 1 && $1 == "with_required_access_audit" {
      audit_p50 = $3; audit_p95 = $4; audit_tps = $5
    }
    END {
      p50_delta = base_p50 == 0 ? 0 : ((audit_p50 - base_p50) / base_p50) * 100
      p95_delta = base_p95 == 0 ? 0 : ((audit_p95 - base_p95) / base_p95) * 100
      tps_delta = base_tps == 0 ? 0 : ((audit_tps - base_tps) / base_tps) * 100
      printf "\nRecorded deltas: p50 %+.1f%%, p95 %+.1f%%, throughput %+.1f%%.\n", \
        p50_delta, p95_delta, tps_delta
    }
  ' "$working_output_directory/access-audit-overhead.csv"
  printf '\nThis is a single-client database-component comparison, not an HTTP '
  printf 'benchmark. Each sample commits the production repository’s three ticket-detail '
  printf 'SELECT shapes; the audited phase adds the production `API_RESOURCE_READ` '
  printf 'INSERT column/value shape and its projection trigger, using deterministic synthetic '
  printf 'identifiers and a synthetic session fingerprint. It excludes Spring/JDBC mapping, authorization '
  printf 'objects, assignment-option loading, JSON, network and browser time. The '
  printf 'without-audit path is counterfactual only: Deskseed keeps strict availability '
  printf 'semantics, so a sensitive read succeeds only after its required canonical audit '
  printf 'write commits. `relation_bytes_delta` has PostgreSQL page-allocation granularity; '
  printf '`wal_bytes_delta` captures the transaction-level byte cost. The audited '
  printf 'row amplification is one immutable `access_audit_events` row plus one '
  printf '`audit_activity_projection` row per successful read.\n'
  printf '\n## Durable raw evidence\n\n'
  printf -- '- `plans-before.txt` / `plans-after.txt`: raw `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`\n'
  printf -- '- `latency-before.csv` / `latency-after.csv`: p50/p95 from %s measured executions after one warm-up\n' "$repetitions"
  printf -- '- `query-cardinality.csv`: eligible and first-page rows for the exact production queue predicates\n'
  printf -- '- `queue-latency-budget.csv`: fixed PERF-001 p95 ceiling and per-View pass/fail\n'
  printf -- '- `search-latency-budget.csv`: fixed REQ-SRCH-001 p95 ceiling for exact count and first score page\n'
  printf -- '- `sizes-before.csv` / `sizes-after.csv`: heap, total-index and candidate-index sizes plus scan counts\n'
  printf -- '- `fixture-load.log`, `migrations.csv`, `durations.csv`: generation and phase timing\n'
  printf -- '- `environment.txt`, `database-settings.csv`: seed, exact image and database settings\n'
  printf -- '- `source-manifest.txt`, `source-fingerprint-checks.txt`: captured source hashes and freeze checkpoints\n'
  printf -- '- `access-audit-overhead.csv`: committed read-only baseline versus required audit-write p50/p95, throughput, row and byte amplification\n'
  printf -- '- `cleanup-status.txt`: owned container/data-volume identity and post-run absence verification\n'
  printf '\n## Representativeness limits\n\n'
  printf 'The tables and indexes come from the repository migration SQL in numeric order, '
  printf 'but this harness does not create Flyway history; migration upgrade behavior is an '
  printf 'operations gate. Data is deterministic and synthetic, with deliberately regular '
  printf 'cardinality rather than production skew. Latencies are server-side, warm-cache, '
  printf 'single-client samples in an isolated local container; they are not an API SLO or '
  printf 'production-capacity claim. Canonical audit rows are loaded with integrity constraints '
  printf 'active while per-row audit and ticket-search projection refresh triggers are paused, followed by the real '
  printf '`rebuild_audit_activity_projection()` and `rebuild_ticket_search_documents()` functions. Fixture load duration therefore is '
  printf 'not an online-ingestion benchmark.\n'
} > "$working_output_directory/summary.md"

printf 'status=PASS\nphase=complete\nexit_code=0\n' \
  > "$working_output_directory/run-status.txt"
failure_phase="cleanup_runtime_resources"
cleanup_runtime_resources
failure_phase="verify_source_after_cleanup"
verify_performance_source_fingerprint "after_cleanup"
failure_phase="publish_evidence"
publish_evidence
trap - EXIT
printf 'Release performance evidence written to %s\n' "$output_directory"
