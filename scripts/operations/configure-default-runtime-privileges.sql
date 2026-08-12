\set ON_ERROR_STOP on

\if :{?migration_role}
\else
  \echo 'missing required psql variable: migration_role'
  \quit 2
\endif

\if :{?runtime_role}
\else
  \echo 'missing required psql variable: runtime_role'
  \quit 2
\endif

-- During a migration-first rollout Hibernate validation runs through the runtime
-- credential before the post-migration least-privilege pass. New objects therefore
-- receive only the read/append privileges needed to start. configure-runtime-role.sql
-- is always rerun before traffic to grant ordinary mutable tables and revoke canonical
-- ledger mutation privileges explicitly.
alter default privileges for role :"migration_role" in schema public
    grant select, insert on tables to :"runtime_role";

alter default privileges for role :"migration_role" in schema public
    grant usage, select on sequences to :"runtime_role";

\echo 'Default migration-to-runtime startup privileges configured.'

