#!/usr/bin/env bash
# Run on the personal-staging host. Changes only diagnostic settings/extension/role.
set -euo pipefail
umask 077
[[ ${CONFIRM_PERSONAL_STAGING_DIAGNOSTICS:-} == true ]] || { echo 'Explicit personal-staging diagnostic confirmation required' >&2; exit 2; }
diagnostics_dir=${1:?usage: prepare-search-diagnostics-db.sh /absolute/private/directory}
[[ $diagnostics_dir = /* ]] || exit 2
container=deskseed-db-1
[[ $(docker inspect "$container" --format '{{index .Config.Labels "com.docker.compose.project"}}') == deskseed ]] || exit 2
psql_admin() { docker exec -i "$container" sh -c 'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' sh "$@"; }
preload=$(psql_admin -Atc 'SHOW shared_preload_libraries')
[[ -z $preload || $preload == pg_stat_statements ]] || { echo 'Review existing preload libraries before changing them' >&2; exit 2; }
[[ $(psql_admin -Atc "SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND state='active' AND pid<>pg_backend_pid()") == 0 ]] || { echo 'Active queries exist; retry during an idle interval' >&2; exit 2; }
[[ $(psql_admin -Atc 'SHOW log_statement') == none ]] || exit 2
[[ $(psql_admin -Atc 'SHOW log_min_duration_statement') == -1 ]] || exit 2
mkdir -p "$diagnostics_dir"
chmod 700 "$diagnostics_dir"
backup_dir=$(mktemp -d "$diagnostics_dir/before-XXXXXXXX")
docker cp "$container:/var/lib/postgresql/data/postgresql.auto.conf" "$backup_dir/postgresql.auto.conf"
chmod 600 "$backup_dir/postgresql.auto.conf"
psql_admin -Atc 'SHOW shared_preload_libraries; SHOW track_io_timing;' > "$backup_dir/settings.txt"
psql_admin <<'SQL'
ALTER SYSTEM SET shared_preload_libraries = 'pg_stat_statements';
ALTER SYSTEM SET track_io_timing = 'on';
-- Avoid retaining utility statements such as operational role setup in pg_stat_statements.
ALTER SYSTEM SET pg_stat_statements.track_utility = 'off';
SQL
if [[ -z $preload ]]; then docker restart --time 30 "$container"; else psql_admin -Atc 'SELECT pg_reload_conf()'; fi
for attempt in {1..30}; do
  if docker exec "$container" sh -c 'pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"'; then break; fi
  sleep 1
done
[[ $(psql_admin -Atc 'SHOW shared_preload_libraries') == pg_stat_statements ]] || exit 1
psql_admin -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements'
password_file="$diagnostics_dir/postgres-password"
role_exists=$(psql_admin -Atc "SELECT count(*) FROM pg_roles WHERE rolname='deskseed_search_monitor'")
if [[ $role_exists == 0 ]]; then
  [[ ! -e $password_file ]] || { echo 'Password file already exists; review before creating a role' >&2; exit 2; }
  openssl rand -hex 32 > "$password_file"
  # Secret goes directly to psql stdin, never arguments, environment, output or repository.
  python3 - "$password_file" <<'PY' | psql_admin
import pathlib, re, sys
secret=pathlib.Path(sys.argv[1]).read_text().strip()
assert re.fullmatch('[a-f0-9]{64}', secret)
print("CREATE ROLE deskseed_search_monitor LOGIN CONNECTION LIMIT 3 PASSWORD '"+secret+"';")
PY
else
  [[ -f $password_file ]] || { echo 'Existing monitor role has no local credential; do not reset it implicitly' >&2; exit 2; }
fi
psql_admin <<'SQL'
GRANT pg_monitor TO deskseed_search_monitor;
SELECT format('GRANT CONNECT ON DATABASE %I TO deskseed_search_monitor', current_database()) \gexec
GRANT USAGE ON SCHEMA public TO deskseed_search_monitor;
ALTER ROLE deskseed_search_monitor SET default_transaction_read_only = on;
ALTER ROLE deskseed_search_monitor SET statement_timeout = '5s';
SQL
psql_admin -Atc "SELECT rolname,rolsuper,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname='deskseed_search_monitor'; SHOW track_io_timing; SELECT count(*) FROM pg_stat_statements;"
printf 'Diagnostic database ready; configuration backup: %s\n' "$backup_dir"
