create table outbound_mail_intents (
    id uuid primary key,
    idempotency_key varchar(200) not null unique,
    stable_message_id varchar(200) not null unique,
    template_key varchar(60) not null,
    template_version integer not null,
    sender_address varchar(254) not null,
    recipient_address varchar(254) not null,
    subject varchar(200) not null,
    text_body text not null,
    ticket_id uuid null references tickets(id),
    comment_id uuid null references ticket_comments(id),
    customer_id uuid null references customers(id),
    actor_type varchar(30) not null,
    actor_id uuid null,
    source varchar(40) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    command_id varchar(100) not null,
    status varchar(30) not null,
    attempt_count integer not null default 0,
    cycle_attempt_count integer not null default 0,
    max_attempts integer not null,
    retry_cycle integer not null default 0,
    manual_retry_count integer not null default 0,
    next_attempt_at timestamptz null,
    lease_expires_at timestamptz null,
    last_error_code varchar(80) null,
    queued_at timestamptz not null,
    sent_at timestamptz null,
    failed_at timestamptz null,
    version bigint not null default 0,
    constraint outbound_mail_template_valid check (
        template_key in ('CUSTOMER_MAGIC_LINK', 'REQUEST_RECEIVED', 'PUBLIC_AGENT_REPLY')
    ),
    constraint outbound_mail_template_version_positive check (template_version > 0),
    constraint outbound_mail_status_valid check (
        status in ('QUEUED', 'SENDING', 'RETRY_WAIT', 'SENT', 'FAILED')
    ),
    constraint outbound_mail_attempt_counts_valid check (
        attempt_count >= 0 and cycle_attempt_count >= 0 and max_attempts > 0
        and retry_cycle >= 0 and manual_retry_count >= 0 and attempt_count <= max_attempts
    ),
    constraint outbound_mail_body_bounded check (char_length(text_body) between 1 and 30000),
    constraint outbound_mail_subject_not_blank check (length(btrim(subject)) > 0),
    constraint outbound_mail_queue_time_valid check (
        (status in ('QUEUED', 'RETRY_WAIT') and next_attempt_at is not null and lease_expires_at is null)
        or (status = 'SENDING' and next_attempt_at is null and lease_expires_at is not null)
        or (status = 'SENT' and sent_at is not null and next_attempt_at is null and lease_expires_at is null)
        or (status = 'FAILED' and failed_at is not null and next_attempt_at is null and lease_expires_at is null)
    )
);

create index outbound_mail_intents_due_idx
    on outbound_mail_intents (next_attempt_at, queued_at, id)
    where status in ('QUEUED', 'RETRY_WAIT');

create index outbound_mail_intents_stale_lease_idx
    on outbound_mail_intents (lease_expires_at, id)
    where status = 'SENDING';

create index outbound_mail_intents_ticket_idx
    on outbound_mail_intents (ticket_id, queued_at, id)
    where ticket_id is not null;

create index outbound_mail_intents_comment_idx
    on outbound_mail_intents (comment_id)
    where comment_id is not null;

create table outbound_mail_attempts (
    id uuid primary key,
    intent_id uuid not null references outbound_mail_intents(id),
    attempt_number integer not null,
    retry_cycle integer not null,
    cycle_attempt_number integer not null,
    provider varchar(40) not null,
    status varchar(30) not null,
    provider_message_id varchar(200) null,
    failure_class varchar(40) null,
    failure_code varchar(80) null,
    started_at timestamptz not null,
    finished_at timestamptz null,
    next_retry_at timestamptz null,
    constraint outbound_mail_attempt_number_unique unique (intent_id, attempt_number),
    constraint outbound_mail_attempt_numbers_valid check (
        attempt_number > 0 and retry_cycle >= 0 and cycle_attempt_number > 0
    ),
    constraint outbound_mail_attempt_status_valid check (
        status in ('IN_PROGRESS', 'SUCCEEDED', 'RETRYABLE_FAILED', 'PERMANENT_FAILED', 'ABANDONED')
    )
);

create index outbound_mail_attempts_timeline_idx
    on outbound_mail_attempts (intent_id, attempt_number);

create table outbound_mail_delivery_events (
    id uuid primary key,
    intent_id uuid not null references outbound_mail_intents(id),
    attempt_id uuid null references outbound_mail_attempts(id),
    event_type varchar(60) not null,
    actor_type varchar(30) not null,
    actor_id uuid null,
    source varchar(40) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    reason_code varchar(80) null,
    reason_text varchar(500) null,
    occurred_at timestamptz not null,
    constraint outbound_mail_delivery_event_type_valid check (
        event_type in (
            'MAIL_QUEUED', 'MAIL_ATTEMPT_STARTED', 'MAIL_ATTEMPT_SUCCEEDED',
            'MAIL_ATTEMPT_FAILED', 'MAIL_ATTEMPT_ABANDONED',
            'MAIL_TERMINAL_FAILED', 'MAIL_MANUAL_RETRY_REQUESTED'
        )
    )
);

create index outbound_mail_delivery_events_timeline_idx
    on outbound_mail_delivery_events (intent_id, occurred_at, id);

create or replace function reject_outbound_mail_delivery_event_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Outbound mail delivery event history is append-only';
end;
$$;

create trigger outbound_mail_delivery_events_immutable
before update or delete on outbound_mail_delivery_events
for each row execute function reject_outbound_mail_delivery_event_mutation();
