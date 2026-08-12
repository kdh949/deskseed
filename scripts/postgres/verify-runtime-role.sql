\set ON_ERROR_STOP on

\if :{?runtime_role}
\else
  \echo 'missing required psql variable: runtime_role'
  \quit 2
\endif

select exists (
    select 1
    from (
        values
            ('ticket_audits'),
            ('ticket_audit_events'),
            ('access_audit_events'),
            ('search_audit_details'),
            ('search_audit_result_items'),
            ('admin_security_audit_events')
    ) as canonical(table_name)
    where has_table_privilege(:'runtime_role', 'public.' || table_name, 'UPDATE')
       or has_table_privilege(:'runtime_role', 'public.' || table_name, 'DELETE')
       or has_table_privilege(:'runtime_role', 'public.' || table_name, 'TRUNCATE')
       or has_table_privilege(:'runtime_role', 'public.' || table_name, 'REFERENCES')
       or has_table_privilege(:'runtime_role', 'public.' || table_name, 'TRIGGER')
       or has_table_privilege(:'runtime_role', 'public.' || table_name, 'MAINTAIN')
       or not has_table_privilege(:'runtime_role', 'public.' || table_name, 'SELECT')
       or not has_table_privilege(:'runtime_role', 'public.' || table_name, 'INSERT')
) as canonical_privilege_violation,
exists (
    select 1
    from (
        values
            ('SELECT'),
            ('INSERT'),
            ('UPDATE'),
            ('DELETE'),
            ('TRUNCATE'),
            ('REFERENCES'),
            ('TRIGGER'),
            ('MAINTAIN')
    ) as privilege_to_reject(privilege_name)
    where has_table_privilege(
        :'runtime_role',
        'public.flyway_schema_history',
        privilege_to_reject.privilege_name
    )
) as flyway_history_privilege_violation,
has_schema_privilege(:'runtime_role', 'public', 'CREATE') as schema_create_violation,
not has_schema_privilege(:'runtime_role', 'public', 'USAGE') as schema_usage_missing,
has_table_privilege(:'runtime_role', 'public.search_audit_query_ciphertexts', 'UPDATE')
    as ciphertext_update_violation,
not has_table_privilege(:'runtime_role', 'public.search_audit_query_ciphertexts', 'DELETE')
    as ciphertext_retention_delete_missing
\gset

\if :canonical_privilege_violation
  \echo 'runtime role has an invalid canonical-ledger privilege'
  \quit 1
\endif

\if :flyway_history_privilege_violation
  \echo 'runtime role has a privilege on Flyway migration history'
  \quit 1
\endif

\if :schema_create_violation
  \echo 'runtime role can create objects in the application schema'
  \quit 1
\endif

\if :schema_usage_missing
  \echo 'runtime role cannot use the application schema'
  \quit 1
\endif

\if :ciphertext_update_violation
  \echo 'runtime role can update protected search-query ciphertext'
  \quit 1
\endif

\if :ciphertext_retention_delete_missing
  \echo 'runtime role cannot execute the implemented ciphertext-retention deletion'
  \quit 1
\endif

\echo 'PASS: runtime role is DDL-restricted, has no Flyway history privilege, and canonical ledgers are SELECT/INSERT only.'
