-- Flyway owns the audit tables. Temporarily suspend the append-only guard only
-- inside this owner-run migration so legacy routine representations can be
-- replaced without changing fingerprints or protected ciphertext.
alter table search_audit_details
    disable trigger search_audit_details_immutable;

update search_audit_details
set query_redacted = '[PROTECTED]'
where query_redacted <> '[PROTECTED]';

alter table search_audit_details
    enable trigger search_audit_details_immutable;

update audit_activity_projection
set query_redacted = '[PROTECTED]'
where query_redacted is not null
  and query_redacted <> '[PROTECTED]';

alter table search_audit_details
    add constraint search_audit_query_redacted_content_free
        check (query_redacted = '[PROTECTED]');

alter table audit_activity_projection
    add constraint audit_projection_query_redacted_content_free
        check (query_redacted is null or query_redacted = '[PROTECTED]');
