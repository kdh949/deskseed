#!/usr/bin/env sh
set -eu

: "${POSTGRES_HOST:?POSTGRES_HOST is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${DATABASE_MIGRATION_USERNAME:?DATABASE_MIGRATION_USERNAME is required}"
: "${DATABASE_MIGRATION_PASSWORD:?DATABASE_MIGRATION_PASSWORD is required}"
: "${DATABASE_RUNTIME_USERNAME:?DATABASE_RUNTIME_USERNAME is required}"

export PGPASSWORD="$DATABASE_MIGRATION_PASSWORD"

psql \
  --host "$POSTGRES_HOST" \
  --username "$DATABASE_MIGRATION_USERNAME" \
  --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set="runtime_role=$DATABASE_RUNTIME_USERNAME" \
  --file=/opt/deskseed/postgres/configure-runtime-role.sql

psql \
  --host "$POSTGRES_HOST" \
  --username "$DATABASE_MIGRATION_USERNAME" \
  --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set="runtime_role=$DATABASE_RUNTIME_USERNAME" \
  --file=/opt/deskseed/postgres/verify-runtime-role.sql

unset PGPASSWORD
