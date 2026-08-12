\set ON_ERROR_STOP on

\if :{?runtime_role}
\else
  \echo 'missing required psql variable: runtime_role'
  \quit 2
\endif

begin;

-- Flyway/migration credentials own DDL. The application credential receives only the
-- data privileges required by the current modular monolith.
revoke create on schema public from :"runtime_role";
grant usage on schema public to :"runtime_role";
grant select, insert, update, delete on all tables in schema public to :"runtime_role";
grant usage, select on all sequences in schema public to :"runtime_role";
revoke truncate, references, trigger, maintain on all tables in schema public from :"runtime_role";

-- Flyway history is migration control-plane state, not application data. The broad
-- ordinary-table grant above must never let the runtime credential read or mutate it.
revoke all privileges on table flyway_schema_history from :"runtime_role";

-- Canonical ledgers are insert/read only for the general application role. Their database
-- triggers are a second line of defence for an owner or accidentally over-granted role.
revoke update, delete, truncate, references, trigger, maintain on table
    ticket_audits,
    ticket_audit_events,
    access_audit_events,
    search_audit_details,
    search_audit_result_items,
    admin_security_audit_events
from :"runtime_role";

grant select, insert on table
    ticket_audits,
    ticket_audit_events,
    access_audit_events,
    search_audit_details,
    search_audit_result_items,
    admin_security_audit_events
to :"runtime_role";

-- Protected query ciphertext is immutable but intentionally deletable by the implemented
-- retention job. The projection and export-request skeleton are rebuildable/operational,
-- so they are not canonical ledgers and keep normal runtime data privileges.
revoke update on table search_audit_query_ciphertexts from :"runtime_role";
grant select, insert, delete on table search_audit_query_ciphertexts to :"runtime_role";

commit;

\echo 'Deskseed runtime role privileges configured; run verify-runtime-role.sql next.'
