drop trigger ticket_audits_immutable on ticket_audits;

alter table ticket_audits
    add column expected_version bigint not null default 0;

update ticket_audits
set expected_version = ticket_version;

alter table ticket_audits
    alter column expected_version drop default,
    add constraint ticket_audit_versions_valid check (
        expected_version >= 0 and ticket_version >= expected_version
    );

create index ticket_audits_conflict_fields_idx
    on ticket_audits (ticket_id, ticket_version, id);

create trigger ticket_audits_immutable
before update or delete on ticket_audits
for each row execute function reject_audit_mutation();
