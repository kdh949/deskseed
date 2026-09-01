#!/usr/bin/env bash
set -Eeuo pipefail

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${DESKSEED_RUNTIME_ROLE:?DESKSEED_RUNTIME_ROLE is required}"
: "${DESKSEED_RUNTIME_PASSWORD:?DESKSEED_RUNTIME_PASSWORD is required}"

if [[ ! "$DESKSEED_RUNTIME_ROLE" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
  echo "DESKSEED_RUNTIME_ROLE must be a simple PostgreSQL identifier." >&2
  exit 2
fi

# PostgreSQL's official image invokes this file only for an empty data directory.
# psql variables keep the generated credential out of SQL text and quote it safely.
psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set="runtime_role=$DESKSEED_RUNTIME_ROLE" \
  --set="runtime_password=$DESKSEED_RUNTIME_PASSWORD" <<'SQL'
select format(
    'create role %I login password %L nosuperuser nocreatedb nocreaterole noinherit',
    :'runtime_role',
    :'runtime_password'
)
where not exists (
    select 1 from pg_roles where rolname = :'runtime_role'
)
\gexec
SQL

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set="migration_role=$POSTGRES_USER" \
  --set="runtime_role=$DESKSEED_RUNTIME_ROLE" \
  --file=/opt/deskseed/operations/configure-default-runtime-privileges.sql
