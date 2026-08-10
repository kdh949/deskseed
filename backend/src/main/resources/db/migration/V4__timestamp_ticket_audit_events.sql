alter table ticket_audit_events
    add column occurred_at timestamptz;

update ticket_audit_events event
set occurred_at = audit.created_at
from ticket_audits audit
where audit.id = event.audit_id;

alter table ticket_audit_events
    alter column occurred_at set not null;
