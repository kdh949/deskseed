drop trigger ticket_audit_events_immutable on ticket_audit_events;

alter table ticket_audit_events
    add column occurred_at timestamptz;

update ticket_audit_events event
set occurred_at = audit.created_at
from ticket_audits audit
where audit.id = event.audit_id;

alter table ticket_audit_events
    alter column occurred_at set not null;

create trigger ticket_audit_events_immutable
before update or delete on ticket_audit_events
for each row execute function reject_audit_mutation();
