create table ticket_drafts (
    owner_staff_id uuid not null references staff_accounts(id),
    ticket_id uuid not null references tickets(id),
    composer_channel varchar(20) not null,
    body text not null,
    attachment_ids uuid[] not null default '{}',
    client_device_id uuid not null,
    base_ticket_version bigint not null,
    draft_version bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    expires_at timestamptz not null,
    primary key (owner_staff_id, ticket_id, composer_channel),
    constraint ticket_drafts_channel_valid check (composer_channel in ('PUBLIC_REPLY', 'INTERNAL_NOTE')),
    constraint ticket_drafts_body_bounded check (char_length(body) <= 20000),
    constraint ticket_drafts_content_not_empty check (length(btrim(body)) > 0 or cardinality(attachment_ids) > 0),
    constraint ticket_drafts_attachment_count_bounded check (cardinality(attachment_ids) <= 5),
    constraint ticket_drafts_ticket_version_valid check (base_ticket_version >= 0),
    constraint ticket_drafts_version_valid check (draft_version > 0),
    constraint ticket_drafts_expiry_valid check (expires_at > updated_at)
);

create index ticket_drafts_owner_recovery_idx
    on ticket_drafts (owner_staff_id, updated_at desc, ticket_id, composer_channel);

create index ticket_drafts_expiry_idx
    on ticket_drafts (expires_at, owner_staff_id, ticket_id, composer_channel);

create table ticket_draft_cleanup_lease (
    lease_name varchar(80) primary key,
    lease_owner varchar(100) null,
    lease_expires_at timestamptz null,
    constraint ticket_draft_cleanup_lease_name_valid check (lease_name = 'ticket-draft-expiry'),
    constraint ticket_draft_cleanup_lease_state_valid check (
        (lease_owner is null and lease_expires_at is null)
        or (lease_owner is not null and lease_expires_at is not null)
    )
);

insert into ticket_draft_cleanup_lease (lease_name, lease_owner, lease_expires_at)
values ('ticket-draft-expiry', null, null);
