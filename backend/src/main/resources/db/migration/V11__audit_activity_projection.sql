create table audit_activity_projection_state (
    id integer primary key,
    state varchar(20) not null,
    last_rebuilt_at timestamptz null,
    last_failure_at timestamptz null,
    constraint audit_activity_projection_state_singleton check (id = 1),
    constraint audit_activity_projection_state_valid check (
        state in ('CURRENT', 'DEGRADED', 'REBUILDING')
    )
);

insert into audit_activity_projection_state (id, state)
values (1, 'CURRENT');

create table audit_activity_projection (
    id uuid primary key,
    ledger_type varchar(30) not null,
    source_event_id uuid not null,
    source_parent_id uuid null,
    occurred_at timestamptz not null,
    actor_type varchar(30) not null,
    actor_id uuid null,
    actor_display_snapshot varchar(100) not null,
    source varchar(40) not null,
    action varchar(80) not null,
    outcome varchar(20) not null,
    resource_type varchar(40) null,
    resource_id uuid null,
    ticket_id uuid null,
    ticket_number bigint null,
    group_id uuid null,
    field_name varchar(60) null,
    old_value_json jsonb null,
    new_value_json jsonb null,
    metadata_json jsonb not null,
    request_id varchar(100) null,
    correlation_id varchar(100) null,
    interaction_id uuid null,
    session_fingerprint varchar(100) null,
    auth_type varchar(40) null,
    ip_address varchar(64) null,
    user_agent varchar(256) null,
    origin_search_event_id uuid null,
    query_redacted varchar(500) null,
    search_fingerprint varchar(100) null,
    search_filters_json jsonb null,
    search_sort varchar(100) null,
    search_result_count bigint null,
    protected_content_available boolean not null default false,
    projected_at timestamptz not null,
    constraint audit_activity_projection_source_unique unique (ledger_type, source_event_id),
    constraint audit_activity_projection_ledger_valid check (
        ledger_type in ('TICKET_CHANGE', 'ACCESS_SEARCH', 'ADMIN_SECURITY')
    ),
    constraint audit_activity_projection_outcome_valid check (
        outcome in ('SUCCEEDED', 'DENIED', 'FAILED')
    ),
    constraint audit_activity_projection_search_count_valid check (
        search_result_count is null or search_result_count >= 0
    )
);

create index audit_activity_projection_cursor_idx
    on audit_activity_projection (occurred_at desc, id desc);
create index audit_activity_projection_ledger_cursor_idx
    on audit_activity_projection (ledger_type, occurred_at desc, id desc);
create index audit_activity_projection_actor_cursor_idx
    on audit_activity_projection (actor_id, occurred_at desc, id desc);
create index audit_activity_projection_ticket_cursor_idx
    on audit_activity_projection (ticket_number, occurred_at desc, id desc)
    where ticket_number is not null;
create index audit_activity_projection_action_cursor_idx
    on audit_activity_projection (action, occurred_at desc, id desc);
create index audit_activity_projection_group_cursor_idx
    on audit_activity_projection (group_id, occurred_at desc, id desc)
    where group_id is not null;
create index audit_activity_projection_field_cursor_idx
    on audit_activity_projection (field_name, occurred_at desc, id desc)
    where field_name is not null;
create index audit_activity_projection_source_cursor_idx
    on audit_activity_projection (source, occurred_at desc, id desc);
create index audit_activity_projection_outcome_cursor_idx
    on audit_activity_projection (outcome, occurred_at desc, id desc);
create index audit_activity_projection_request_idx
    on audit_activity_projection (request_id)
    where request_id is not null;
create index audit_activity_projection_correlation_idx
    on audit_activity_projection (correlation_id)
    where correlation_id is not null;
create index audit_activity_projection_search_fingerprint_cursor_idx
    on audit_activity_projection (search_fingerprint, occurred_at desc, id desc)
    where search_fingerprint is not null;
create index audit_activity_projection_origin_search_idx
    on audit_activity_projection (origin_search_event_id, occurred_at, id)
    where origin_search_event_id is not null;

create view audit_activity_projection_source as
select
    md5('TICKET_CHANGE:' || event.id::text)::uuid as id,
    'TICKET_CHANGE'::varchar(30) as ledger_type,
    event.id as source_event_id,
    audit.id as source_parent_id,
    event.occurred_at,
    audit.actor_type,
    audit.actor_id,
    left(coalesce(staff.display_name, customer.name, audit.actor_type), 100)::varchar(100)
        as actor_display_snapshot,
    audit.source,
    event.event_type::varchar(80) as action,
    'SUCCEEDED'::varchar(20) as outcome,
    'TICKET'::varchar(40) as resource_type,
    ticket.id as resource_id,
    ticket.id as ticket_id,
    ticket.ticket_number,
    ticket.group_id,
    event.field_name,
    event.old_value_json::jsonb,
    event.new_value_json::jsonb,
    event.metadata_json::jsonb,
    audit.request_id,
    audit.correlation_id,
    null::uuid as interaction_id,
    null::varchar(100) as session_fingerprint,
    null::varchar(40) as auth_type,
    null::varchar(64) as ip_address,
    null::varchar(256) as user_agent,
    null::uuid as origin_search_event_id,
    null::varchar(500) as query_redacted,
    null::varchar(100) as search_fingerprint,
    null::jsonb as search_filters_json,
    null::varchar(100) as search_sort,
    null::bigint as search_result_count,
    false as protected_content_available,
    clock_timestamp() as projected_at
from ticket_audit_events event
join ticket_audits audit on audit.id = event.audit_id
join tickets ticket on ticket.id = audit.ticket_id
left join staff_accounts staff
    on audit.actor_type = 'STAFF' and staff.id = audit.actor_id
left join customers customer
    on audit.actor_type = 'CUSTOMER' and customer.id = audit.actor_id

union all

select
    md5('ACCESS_SEARCH:' || event.id::text)::uuid as id,
    'ACCESS_SEARCH'::varchar(30) as ledger_type,
    event.id as source_event_id,
    null::uuid as source_parent_id,
    event.occurred_at,
    event.actor_type,
    event.actor_id,
    event.actor_display_snapshot,
    event.source,
    event.action::varchar(80),
    event.outcome,
    event.resource_type,
    event.resource_id,
    case when event.resource_type = 'TICKET' then event.resource_id else null end as ticket_id,
    event.ticket_number,
    ticket.group_id,
    null::varchar(60) as field_name,
    null::jsonb as old_value_json,
    null::jsonb as new_value_json,
    jsonb_build_object('httpStatus', event.http_status) as metadata_json,
    event.request_id,
    event.correlation_id,
    event.interaction_id,
    event.session_fingerprint,
    event.auth_type,
    event.ip_address,
    event.user_agent,
    event.origin_search_event_id,
    search.query_redacted,
    search.query_fingerprint as search_fingerprint,
    search.normalized_filters as search_filters_json,
    search.sort as search_sort,
    search.result_count as search_result_count,
    ciphertext.access_event_id is not null as protected_content_available,
    clock_timestamp() as projected_at
from access_audit_events event
left join tickets ticket
    on event.resource_type = 'TICKET' and ticket.id = event.resource_id
left join search_audit_details search on search.access_event_id = event.id
left join search_audit_query_ciphertexts ciphertext on ciphertext.access_event_id = event.id

union all

select
    md5('ADMIN_SECURITY:' || event.id::text)::uuid as id,
    'ADMIN_SECURITY'::varchar(30) as ledger_type,
    event.id as source_event_id,
    null::uuid as source_parent_id,
    event.occurred_at,
    event.actor_type,
    event.actor_id,
    left(coalesce(event.actor_display_snapshot, event.actor_type), 100)::varchar(100),
    event.source,
    event.event_type::varchar(80) as action,
    event.outcome,
    event.target_type::varchar(40) as resource_type,
    event.target_id as resource_id,
    null::uuid as ticket_id,
    null::bigint as ticket_number,
    case
        when event.metadata_json::jsonb ->> 'groupId'
            ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        then (event.metadata_json::jsonb ->> 'groupId')::uuid
        else null
    end as group_id,
    event.metadata_json::jsonb ->> 'field' as field_name,
    null::jsonb as old_value_json,
    null::jsonb as new_value_json,
    event.metadata_json::jsonb,
    event.request_id,
    event.correlation_id,
    null::uuid as interaction_id,
    null::varchar(100) as session_fingerprint,
    null::varchar(40) as auth_type,
    null::varchar(64) as ip_address,
    null::varchar(256) as user_agent,
    null::uuid as origin_search_event_id,
    null::varchar(500) as query_redacted,
    null::varchar(100) as search_fingerprint,
    null::jsonb as search_filters_json,
    null::varchar(100) as search_sort,
    null::bigint as search_result_count,
    false as protected_content_available,
    clock_timestamp() as projected_at
from admin_security_audit_events event;

create function refresh_audit_activity_projection(p_ledger_type varchar, p_source_event_id uuid)
returns void
language plpgsql
as $$
begin
    delete from audit_activity_projection
    where ledger_type = p_ledger_type and source_event_id = p_source_event_id;

    insert into audit_activity_projection
    select *
    from audit_activity_projection_source source
    where source.ledger_type = p_ledger_type
      and source.source_event_id = p_source_event_id;
end;
$$;

create function safely_refresh_audit_activity_projection()
returns trigger
language plpgsql
as $$
declare
    source_id uuid;
    ledger varchar(30);
begin
    if tg_table_name = 'ticket_audit_events' then
        source_id := new.id;
        ledger := 'TICKET_CHANGE';
    elsif tg_table_name = 'access_audit_events' then
        source_id := new.id;
        ledger := 'ACCESS_SEARCH';
    elsif tg_table_name = 'admin_security_audit_events' then
        source_id := new.id;
        ledger := 'ADMIN_SECURITY';
    elsif tg_op = 'DELETE' then
        source_id := old.access_event_id;
        ledger := 'ACCESS_SEARCH';
    else
        source_id := new.access_event_id;
        ledger := 'ACCESS_SEARCH';
    end if;

    perform refresh_audit_activity_projection(ledger, source_id);
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
exception when others then
    begin
        update audit_activity_projection_state
        set state = 'DEGRADED', last_failure_at = clock_timestamp()
        where id = 1;
    exception when others then
        null;
    end;
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$$;

create trigger ticket_audit_events_projected
after insert on ticket_audit_events
for each row execute function safely_refresh_audit_activity_projection();

create trigger access_audit_events_projected
after insert on access_audit_events
for each row execute function safely_refresh_audit_activity_projection();

create trigger search_audit_details_projected
after insert on search_audit_details
for each row execute function safely_refresh_audit_activity_projection();

create trigger search_audit_query_ciphertexts_projected
after insert or delete on search_audit_query_ciphertexts
for each row execute function safely_refresh_audit_activity_projection();

create trigger admin_security_audit_events_projected
after insert on admin_security_audit_events
for each row execute function safely_refresh_audit_activity_projection();

create function rebuild_audit_activity_projection()
returns table (
    ticket_change_count bigint,
    access_search_count bigint,
    admin_security_count bigint,
    total_count bigint
)
language plpgsql
as $$
begin
    if not pg_try_advisory_xact_lock(hashtext('deskseed:audit-activity-projection:rebuild')) then
        raise exception 'Audit activity projection rebuild is already running' using errcode = '55P03';
    end if;

    update audit_activity_projection_state
    set state = 'REBUILDING'
    where id = 1;

    delete from audit_activity_projection;
    insert into audit_activity_projection
    select * from audit_activity_projection_source;

    select
        count(*) filter (where ledger_type = 'TICKET_CHANGE'),
        count(*) filter (where ledger_type = 'ACCESS_SEARCH'),
        count(*) filter (where ledger_type = 'ADMIN_SECURITY'),
        count(*)
    into ticket_change_count, access_search_count, admin_security_count, total_count
    from audit_activity_projection;

    update audit_activity_projection_state
    set state = 'CURRENT', last_rebuilt_at = clock_timestamp(), last_failure_at = null
    where id = 1;

    return next;
end;
$$;

select * from rebuild_audit_activity_projection();
