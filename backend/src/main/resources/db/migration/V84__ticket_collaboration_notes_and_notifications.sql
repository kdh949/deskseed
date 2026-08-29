create table ticket_collaboration_notes (
    id uuid primary key,
    ticket_id uuid not null references tickets(id),
    author_staff_id uuid not null references staff_accounts(id),
    body text not null,
    client_command_id uuid not null,
    command_fingerprint varchar(64) not null,
    audit_id uuid not null references ticket_audits(id),
    created_at timestamptz not null,
    constraint ticket_collaboration_notes_body_valid check (
        length(btrim(body)) between 1 and 4000
    ),
    constraint ticket_collaboration_notes_fingerprint_valid check (
        command_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    constraint ticket_collaboration_notes_actor_command_unique unique (
        author_staff_id,
        client_command_id
    ),
    constraint ticket_collaboration_notes_audit_unique unique (audit_id)
);

create index ticket_collaboration_notes_timeline_idx
    on ticket_collaboration_notes (ticket_id, created_at desc, id desc);

create table ticket_collaboration_note_mentions (
    note_id uuid not null references ticket_collaboration_notes(id),
    staff_id uuid not null references staff_accounts(id),
    primary key (note_id, staff_id)
);

create index ticket_collaboration_note_mentions_staff_idx
    on ticket_collaboration_note_mentions (staff_id, note_id);

create table staff_notifications (
    id uuid primary key,
    recipient_staff_id uuid not null references staff_accounts(id),
    notification_type varchar(40) not null,
    ticket_id uuid not null references tickets(id),
    note_id uuid not null references ticket_collaboration_notes(id),
    created_at timestamptz not null,
    read_at timestamptz null,
    constraint staff_notifications_type_valid check (
        notification_type = 'COLLABORATION_MENTION'
    ),
    constraint staff_notifications_recipient_note_unique unique (
        recipient_staff_id,
        note_id
    )
);

create index staff_notifications_recipient_timeline_idx
    on staff_notifications (recipient_staff_id, created_at desc, id desc);

create index staff_notifications_recipient_unread_idx
    on staff_notifications (recipient_staff_id, created_at desc, id desc)
    where read_at is null;

create or replace function reject_collaboration_history_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'collaboration note history rows are immutable';
end;
$$;

create trigger ticket_collaboration_notes_immutable
before update or delete on ticket_collaboration_notes
for each row execute function reject_collaboration_history_mutation();

create trigger ticket_collaboration_note_mentions_immutable
before update or delete on ticket_collaboration_note_mentions
for each row execute function reject_collaboration_history_mutation();
