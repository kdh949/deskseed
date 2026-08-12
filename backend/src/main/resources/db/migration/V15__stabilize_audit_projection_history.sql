alter table ticket_audits
    add column actor_display_snapshot varchar(100),
    add column group_id uuid references support_groups(id);

alter table ticket_audits disable trigger ticket_audits_immutable;

update ticket_audits audit
set actor_display_snapshot = left(
        coalesce(
            case when audit.actor_type = 'STAFF' then
                (select staff.display_name from staff_accounts staff where staff.id = audit.actor_id)
            end,
            case when audit.actor_type = 'CUSTOMER' then
                (select customer.name from customers customer where customer.id = audit.actor_id)
            end,
            audit.actor_type
        ),
        100
    ),
    group_id = (select ticket.group_id from tickets ticket where ticket.id = audit.ticket_id);

alter table ticket_audits enable trigger ticket_audits_immutable;

alter table ticket_audits
    alter column actor_display_snapshot set not null;

alter table access_audit_events
    add column group_id uuid references support_groups(id);

alter table access_audit_events disable trigger access_audit_events_immutable;

update access_audit_events event
set group_id = ticket.group_id
from tickets ticket
where event.resource_type = 'TICKET'
  and ticket.id = event.resource_id;

alter table access_audit_events enable trigger access_audit_events_immutable;

create function capture_ticket_audit_projection_snapshot()
returns trigger
language plpgsql
as $$
begin
    if new.actor_display_snapshot is null then
        select left(
            coalesce(
                case when new.actor_type = 'STAFF' then
                    (select staff.display_name from staff_accounts staff where staff.id = new.actor_id)
                end,
                case when new.actor_type = 'CUSTOMER' then
                    (select customer.name from customers customer where customer.id = new.actor_id)
                end,
                new.actor_type
            ),
            100
        ) into new.actor_display_snapshot;
    end if;
    if new.actor_display_snapshot is null then
        new.actor_display_snapshot := left(new.actor_type, 100);
    end if;
    if new.group_id is null then
        select ticket.group_id into new.group_id
        from tickets ticket
        where ticket.id = new.ticket_id;
    end if;
    return new;
end;
$$;

create trigger ticket_audit_projection_snapshot_captured
before insert on ticket_audits
for each row execute function capture_ticket_audit_projection_snapshot();

create function capture_access_audit_projection_snapshot()
returns trigger
language plpgsql
as $$
begin
    if new.group_id is null and new.resource_type = 'TICKET' and new.resource_id is not null then
        select ticket.group_id into new.group_id
        from tickets ticket
        where ticket.id = new.resource_id;
    end if;
    return new;
end;
$$;

create trigger access_audit_projection_snapshot_captured
before insert on access_audit_events
for each row execute function capture_access_audit_projection_snapshot();

create or replace view audit_activity_projection_source as
select
    md5('TICKET_CHANGE:' || event.id::text)::uuid as id,
    'TICKET_CHANGE'::varchar(30) as ledger_type,
    event.id as source_event_id,
    audit.id as source_parent_id,
    event.occurred_at,
    audit.actor_type,
    audit.actor_id,
    audit.actor_display_snapshot,
    audit.source,
    event.event_type::varchar(80) as action,
    'SUCCEEDED'::varchar(20) as outcome,
    'TICKET'::varchar(40) as resource_type,
    audit.ticket_id as resource_id,
    audit.ticket_id as ticket_id,
    ticket.ticket_number,
    audit.group_id,
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
    event.group_id,
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

alter table audit_activity_projection_state
    add column projected_count bigint not null default 0;

update audit_activity_projection_state
set projected_count = (select count(*) from audit_activity_projection)
where id = 1;

create or replace function refresh_audit_activity_projection(p_ledger_type varchar, p_source_event_id uuid)
returns void
language plpgsql
as $$
declare
    deleted_count bigint;
    inserted_count bigint;
begin
    perform pg_advisory_xact_lock_shared(hashtext('deskseed:audit-activity-projection:rebuild'));

    delete from audit_activity_projection
    where ledger_type = p_ledger_type and source_event_id = p_source_event_id;
    get diagnostics deleted_count = row_count;

    insert into audit_activity_projection
    select *
    from audit_activity_projection_source source
    where source.ledger_type = p_ledger_type
      and source.source_event_id = p_source_event_id;
    get diagnostics inserted_count = row_count;

    update audit_activity_projection_state
    set projected_count = greatest(projected_count - deleted_count + inserted_count, 0)
    where id = 1;
end;
$$;

create or replace function rebuild_audit_activity_projection()
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
    set state = 'CURRENT',
        projected_count = total_count,
        last_rebuilt_at = clock_timestamp(),
        last_failure_at = null
    where id = 1;

    return next;
end;
$$;

select * from rebuild_audit_activity_projection();
