#!/usr/bin/env bash
set -Eeuo pipefail

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${DESKSEED_MIGRATION_ROLE:?DESKSEED_MIGRATION_ROLE is required}"
: "${DESKSEED_MIGRATION_PASSWORD:?DESKSEED_MIGRATION_PASSWORD is required}"
: "${DESKSEED_RUNTIME_ROLE:?DESKSEED_RUNTIME_ROLE is required}"
: "${DESKSEED_RUNTIME_PASSWORD:?DESKSEED_RUNTIME_PASSWORD is required}"

for role_name in "$POSTGRES_USER" "$DESKSEED_MIGRATION_ROLE" "$DESKSEED_RUNTIME_ROLE"; do
  if [[ ! "$role_name" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
    echo "PostgreSQL role names must be simple identifiers." >&2
    exit 2
  fi
done
if [[ "$POSTGRES_USER" == "$DESKSEED_MIGRATION_ROLE" \
  || "$POSTGRES_USER" == "$DESKSEED_RUNTIME_ROLE" \
  || "$DESKSEED_MIGRATION_ROLE" == "$DESKSEED_RUNTIME_ROLE" ]]; then
  echo "Bootstrap, migration, and runtime roles must be distinct." >&2
  exit 2
fi

# PostgreSQL's official image invokes this file only for an empty data directory.
# psql variables keep the generated credential out of SQL text and quote it safely.
psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set="database_name=$POSTGRES_DB" \
  --set="migration_role=$DESKSEED_MIGRATION_ROLE" \
  --set="migration_password=$DESKSEED_MIGRATION_PASSWORD" \
  --set="runtime_role=$DESKSEED_RUNTIME_ROLE" \
  --set="runtime_password=$DESKSEED_RUNTIME_PASSWORD" <<'SQL'
select format(
    'create role %I login password %L nosuperuser nocreatedb nocreaterole noinherit',
    :'migration_role',
    :'migration_password'
)
where not exists (
    select 1 from pg_roles where rolname = :'migration_role'
)
\gexec

select format(
    'create role %I login password %L nosuperuser nocreatedb nocreaterole noinherit',
    :'runtime_role',
    :'runtime_password'
)
where not exists (
    select 1 from pg_roles where rolname = :'runtime_role'
)
\gexec

select format('revoke all on database %I from public', :'database_name') \gexec
select format('grant connect, create, temporary on database %I to %I', :'database_name', :'migration_role') \gexec
select format('grant connect on database %I to %I', :'database_name', :'runtime_role') \gexec
revoke create on schema public from public;
grant usage on schema public to :"runtime_role";
alter schema public owner to :"migration_role";
SQL

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set="migration_role=$DESKSEED_MIGRATION_ROLE" \
  --set="runtime_role=$DESKSEED_RUNTIME_ROLE" \
  --file=/opt/deskseed/operations/configure-default-runtime-privileges.sql
