create table active_ticket_search_documents (
    ticket_id uuid primary key references tickets(id) on delete cascade,
    document_version integer not null default 1,
    rank_schema_version integer not null default 1,
    ticket_number bigint not null,
    subject_text text not null,
    requester_name_text text not null,
    requester_email_text text not null,
    group_name_text text not null,
    assignee_name_text text not null,
    public_comment_text text not null,
    internal_comment_text text not null,
    refreshed_at timestamptz not null,
    staff_document text generated always as (
        subject_text || E'\n' ||
        requester_name_text || E'\n' ||
        requester_email_text || E'\n' ||
        group_name_text || E'\n' ||
        assignee_name_text || E'\n' ||
        public_comment_text || E'\n' ||
        internal_comment_text
    ) stored,
    constraint active_ticket_search_document_version_current check (document_version = 1),
    constraint active_ticket_search_rank_schema_current check (rank_schema_version = 1)
);

create unique index active_ticket_search_documents_ticket_number_idx
    on active_ticket_search_documents (ticket_number);

create table terminal_ticket_search_documents (
    ticket_id uuid primary key references tickets(id) on delete cascade,
    document_version integer not null default 1,
    rank_schema_version integer not null default 1,
    ticket_number bigint not null,
    subject_text text not null,
    requester_name_text text not null,
    requester_email_text text not null,
    group_name_text text not null,
    assignee_name_text text not null,
    public_comment_text text not null,
    internal_comment_text text not null,
    finalized_at timestamptz not null,
    staff_document text generated always as (
        subject_text || E'\n' ||
        requester_name_text || E'\n' ||
        requester_email_text || E'\n' ||
        group_name_text || E'\n' ||
        assignee_name_text || E'\n' ||
        public_comment_text || E'\n' ||
        internal_comment_text
    ) stored,
    constraint terminal_ticket_search_document_version_current check (document_version = 1),
    constraint terminal_ticket_search_rank_schema_current check (rank_schema_version = 1)
);

create unique index terminal_ticket_search_documents_ticket_number_idx
    on terminal_ticket_search_documents (ticket_number);

create table ticket_search_projection_backfill_state (
    projection_version integer primary key,
    last_ticket_number bigint not null default 0,
    processed_count bigint not null default 0,
    status varchar(20) not null default 'PENDING',
    started_at timestamptz,
    updated_at timestamptz not null default transaction_timestamp(),
    completed_at timestamptz,
    constraint ticket_search_projection_backfill_version_current check (projection_version = 1),
    constraint ticket_search_projection_backfill_status_valid check (
        status in ('PENDING', 'RUNNING', 'COMPLETE')
    ),
    constraint ticket_search_projection_backfill_counts_valid check (
        last_ticket_number >= 0 and processed_count >= 0
    )
);

insert into ticket_search_projection_backfill_state (projection_version)
values (1);

create or replace function reject_terminal_ticket_search_document_update()
returns trigger
language plpgsql
as $$
begin
    raise exception 'terminal ticket search documents are immutable';
end;
$$;

create trigger terminal_ticket_search_document_update_rejected
before update on terminal_ticket_search_documents
for each row execute function reject_terminal_ticket_search_document_update();

create or replace function refresh_split_ticket_search_document(p_ticket_id uuid)
returns void
language plpgsql
as $$
declare
    changed_count bigint;
begin
    -- A bounded backfill batch takes the exclusive form of this lock. Normal product
    -- writes take the shared form so a batch cannot overwrite a concurrently refreshed row.
    perform pg_advisory_xact_lock_shared(hashtext('deskseed:split-ticket-search-documents:backfill'));

    with source as materialized (
        select
            ticket.id as ticket_id,
            ticket.ticket_number,
            ticket.status,
            ticket.updated_at,
            lower(ticket.subject) as subject_text,
            lower(coalesce(requester.name, '')) as requester_name_text,
            lower(coalesce(requester.email_normalized, '')) as requester_email_text,
            lower(coalesce(ticket_group.name, '')) as group_name_text,
            lower(coalesce(assignee.display_name, '')) as assignee_name_text,
            coalesce(
                string_agg(lower(comment.body), E'\n' order by comment.created_at, comment.id)
                    filter (where comment.visibility = 'PUBLIC'),
                ''
            ) as public_comment_text,
            coalesce(
                string_agg(lower(comment.body), E'\n' order by comment.created_at, comment.id)
                    filter (where comment.visibility = 'INTERNAL'),
                ''
            ) as internal_comment_text
        from tickets ticket
        left join customers requester on requester.id = ticket.requester_id
        left join support_groups ticket_group on ticket_group.id = ticket.group_id
        left join staff_accounts assignee on assignee.id = ticket.assignee_id
        left join ticket_comments comment on comment.ticket_id = ticket.id
        where ticket.id = p_ticket_id
        group by
            ticket.id,
            ticket.ticket_number,
            ticket.status,
            ticket.updated_at,
            ticket.subject,
            requester.name,
            requester.email_normalized,
            ticket_group.name,
            assignee.display_name
    ),
    active_upsert as (
        insert into active_ticket_search_documents (
            ticket_id,
            document_version,
            rank_schema_version,
            ticket_number,
            subject_text,
            requester_name_text,
            requester_email_text,
            group_name_text,
            assignee_name_text,
            public_comment_text,
            internal_comment_text,
            refreshed_at
        )
        select
            ticket_id,
            1,
            1,
            ticket_number,
            subject_text,
            requester_name_text,
            requester_email_text,
            group_name_text,
            assignee_name_text,
            public_comment_text,
            internal_comment_text,
            transaction_timestamp()
        from source
        where status <> 'CLOSED'
        on conflict (ticket_id) do update set
            document_version = excluded.document_version,
            rank_schema_version = excluded.rank_schema_version,
            ticket_number = excluded.ticket_number,
            subject_text = excluded.subject_text,
            requester_name_text = excluded.requester_name_text,
            requester_email_text = excluded.requester_email_text,
            group_name_text = excluded.group_name_text,
            assignee_name_text = excluded.assignee_name_text,
            public_comment_text = excluded.public_comment_text,
            internal_comment_text = excluded.internal_comment_text,
            refreshed_at = excluded.refreshed_at
        returning ticket_id
    ),
    terminal_insert as (
        insert into terminal_ticket_search_documents (
            ticket_id,
            document_version,
            rank_schema_version,
            ticket_number,
            subject_text,
            requester_name_text,
            requester_email_text,
            group_name_text,
            assignee_name_text,
            public_comment_text,
            internal_comment_text,
            finalized_at
        )
        select
            ticket_id,
            1,
            1,
            ticket_number,
            subject_text,
            requester_name_text,
            requester_email_text,
            group_name_text,
            assignee_name_text,
            public_comment_text,
            internal_comment_text,
            updated_at
        from source
        where status = 'CLOSED'
        on conflict (ticket_id) do nothing
        returning ticket_id
    ),
    active_cleanup as (
        delete from active_ticket_search_documents document
        using source
        where document.ticket_id = source.ticket_id
          and source.status = 'CLOSED'
        returning document.ticket_id
    ),
    terminal_cleanup as (
        delete from terminal_ticket_search_documents document
        using source
        where document.ticket_id = source.ticket_id
          and source.status <> 'CLOSED'
        returning document.ticket_id
    )
    select count(*) into changed_count
    from active_upsert
    full join terminal_insert using (ticket_id)
    full join active_cleanup using (ticket_id)
    full join terminal_cleanup using (ticket_id);
end;
$$;

create or replace function refresh_split_ticket_search_document_from_ticket()
returns trigger
language plpgsql
as $$
begin
    perform refresh_split_ticket_search_document(new.id);
    return new;
end;
$$;

create trigger tickets_split_search_document_inserted
after insert on tickets
for each row execute function refresh_split_ticket_search_document_from_ticket();

create trigger tickets_split_search_document_changed
after update of ticket_number, requester_id, subject, status, group_id, assignee_id on tickets
for each row execute function refresh_split_ticket_search_document_from_ticket();

create or replace function refresh_split_ticket_search_document_from_comment()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'INSERT' then
        perform refresh_split_ticket_search_document(new.ticket_id);
        return new;
    end if;

    perform refresh_split_ticket_search_document(old.ticket_id);
    if tg_op = 'UPDATE' and new.ticket_id <> old.ticket_id then
        perform refresh_split_ticket_search_document(new.ticket_id);
    end if;
    return old;
end;
$$;

create trigger ticket_comments_split_search_document_inserted
after insert on ticket_comments
for each row execute function refresh_split_ticket_search_document_from_comment();

create trigger ticket_comments_split_search_document_changed
after update or delete on ticket_comments
for each row execute function refresh_split_ticket_search_document_from_comment();

create or replace function refresh_active_ticket_search_requester_labels()
returns trigger
language plpgsql
as $$
begin
    perform pg_advisory_xact_lock_shared(hashtext('deskseed:split-ticket-search-documents:backfill'));
    update active_ticket_search_documents document
    set requester_name_text = lower(new.name),
        requester_email_text = lower(new.email_normalized),
        refreshed_at = transaction_timestamp()
    from tickets ticket
    where ticket.id = document.ticket_id
      and ticket.requester_id = new.id
      and ticket.status <> 'CLOSED';
    return new;
end;
$$;

create trigger customers_active_search_document_changed
after update of name, email_normalized on customers
for each row execute function refresh_active_ticket_search_requester_labels();

create or replace function refresh_active_ticket_search_group_label()
returns trigger
language plpgsql
as $$
begin
    perform pg_advisory_xact_lock_shared(hashtext('deskseed:split-ticket-search-documents:backfill'));
    update active_ticket_search_documents document
    set group_name_text = lower(new.name),
        refreshed_at = transaction_timestamp()
    from tickets ticket
    where ticket.id = document.ticket_id
      and ticket.group_id = new.id
      and ticket.status <> 'CLOSED';
    return new;
end;
$$;

create trigger support_groups_active_search_document_changed
after update of name on support_groups
for each row execute function refresh_active_ticket_search_group_label();

create or replace function refresh_active_ticket_search_assignee_label()
returns trigger
language plpgsql
as $$
begin
    perform pg_advisory_xact_lock_shared(hashtext('deskseed:split-ticket-search-documents:backfill'));
    update active_ticket_search_documents document
    set assignee_name_text = lower(new.display_name),
        refreshed_at = transaction_timestamp()
    from tickets ticket
    where ticket.id = document.ticket_id
      and ticket.assignee_id = new.id
      and ticket.status <> 'CLOSED';
    return new;
end;
$$;

create trigger staff_accounts_active_search_document_changed
after update of display_name on staff_accounts
for each row execute function refresh_active_ticket_search_assignee_label();

create or replace function reconcile_split_ticket_search_documents()
returns table (
    canonical_active_count bigint,
    projected_active_count bigint,
    missing_active_count bigint,
    unexpected_active_count bigint,
    canonical_terminal_count bigint,
    projected_terminal_count bigint,
    missing_terminal_count bigint,
    unexpected_terminal_count bigint,
    duplicate_count bigint
)
language sql
stable
as $$
    select
        (select count(*) from tickets where status <> 'CLOSED'),
        (select count(*) from active_ticket_search_documents),
        (
            select count(*)
            from tickets ticket
            left join active_ticket_search_documents document on document.ticket_id = ticket.id
            where ticket.status <> 'CLOSED' and document.ticket_id is null
        ),
        (
            select count(*)
            from active_ticket_search_documents document
            join tickets ticket on ticket.id = document.ticket_id
            where ticket.status = 'CLOSED'
        ),
        (select count(*) from tickets where status = 'CLOSED'),
        (select count(*) from terminal_ticket_search_documents),
        (
            select count(*)
            from tickets ticket
            left join terminal_ticket_search_documents document on document.ticket_id = ticket.id
            where ticket.status = 'CLOSED' and document.ticket_id is null
        ),
        (
            select count(*)
            from terminal_ticket_search_documents document
            join tickets ticket on ticket.id = document.ticket_id
            where ticket.status <> 'CLOSED'
        ),
        (
            select count(*)
            from active_ticket_search_documents active_document
            join terminal_ticket_search_documents terminal_document using (ticket_id)
        );
$$;

create or replace function backfill_split_ticket_search_documents(p_batch_size integer default 100)
returns table (
    batch_processed integer,
    checkpoint_ticket_number bigint,
    total_processed bigint,
    backfill_status varchar
)
language plpgsql
as $$
declare
    previous_checkpoint bigint;
    next_checkpoint bigint;
    selected_count integer;
    accumulated_count bigint;
    current_status varchar(20);
    changed_count bigint;
begin
    if p_batch_size < 1 or p_batch_size > 1000 then
        raise exception 'split ticket search projection batch size must be between 1 and 1000';
    end if;

    -- One invocation is one bounded transaction. Operators commit between calls and can
    -- resume from the durable ticket-number checkpoint after interruption.
    perform pg_advisory_xact_lock(hashtext('deskseed:split-ticket-search-documents:backfill'));

    select state.last_ticket_number, state.processed_count, state.status
    into previous_checkpoint, accumulated_count, current_status
    from ticket_search_projection_backfill_state state
    where state.projection_version = 1
    for update;

    if current_status = 'COMPLETE' then
        return query select 0, previous_checkpoint, accumulated_count, current_status;
        return;
    end if;

    select count(*)::integer, max(batch.ticket_number)
    into selected_count, next_checkpoint
    from (
        select ticket.ticket_number
        from tickets ticket
        where ticket.ticket_number > previous_checkpoint
        order by ticket.ticket_number
        limit p_batch_size
    ) batch;

    if selected_count = 0 then
        update ticket_search_projection_backfill_state
        set status = 'COMPLETE',
            updated_at = transaction_timestamp(),
            completed_at = transaction_timestamp()
        where projection_version = 1;
        return query select 0, previous_checkpoint, accumulated_count, 'COMPLETE'::varchar;
        return;
    end if;

    with source as materialized (
        select
            ticket.id as ticket_id,
            ticket.ticket_number,
            ticket.status,
            ticket.updated_at,
            lower(ticket.subject) as subject_text,
            lower(coalesce(requester.name, '')) as requester_name_text,
            lower(coalesce(requester.email_normalized, '')) as requester_email_text,
            lower(coalesce(ticket_group.name, '')) as group_name_text,
            lower(coalesce(assignee.display_name, '')) as assignee_name_text,
            coalesce(
                string_agg(lower(comment.body), E'\n' order by comment.created_at, comment.id)
                    filter (where comment.visibility = 'PUBLIC'),
                ''
            ) as public_comment_text,
            coalesce(
                string_agg(lower(comment.body), E'\n' order by comment.created_at, comment.id)
                    filter (where comment.visibility = 'INTERNAL'),
                ''
            ) as internal_comment_text
        from tickets ticket
        left join customers requester on requester.id = ticket.requester_id
        left join support_groups ticket_group on ticket_group.id = ticket.group_id
        left join staff_accounts assignee on assignee.id = ticket.assignee_id
        left join ticket_comments comment on comment.ticket_id = ticket.id
        where ticket.ticket_number > previous_checkpoint
          and ticket.ticket_number <= next_checkpoint
        group by
            ticket.id,
            ticket.ticket_number,
            ticket.status,
            ticket.updated_at,
            ticket.subject,
            requester.name,
            requester.email_normalized,
            ticket_group.name,
            assignee.display_name
    ),
    active_upsert as (
        insert into active_ticket_search_documents (
            ticket_id,
            document_version,
            rank_schema_version,
            ticket_number,
            subject_text,
            requester_name_text,
            requester_email_text,
            group_name_text,
            assignee_name_text,
            public_comment_text,
            internal_comment_text,
            refreshed_at
        )
        select
            ticket_id,
            1,
            1,
            ticket_number,
            subject_text,
            requester_name_text,
            requester_email_text,
            group_name_text,
            assignee_name_text,
            public_comment_text,
            internal_comment_text,
            transaction_timestamp()
        from source
        where status <> 'CLOSED'
        on conflict (ticket_id) do update set
            document_version = excluded.document_version,
            rank_schema_version = excluded.rank_schema_version,
            ticket_number = excluded.ticket_number,
            subject_text = excluded.subject_text,
            requester_name_text = excluded.requester_name_text,
            requester_email_text = excluded.requester_email_text,
            group_name_text = excluded.group_name_text,
            assignee_name_text = excluded.assignee_name_text,
            public_comment_text = excluded.public_comment_text,
            internal_comment_text = excluded.internal_comment_text,
            refreshed_at = excluded.refreshed_at
        returning ticket_id
    ),
    terminal_insert as (
        insert into terminal_ticket_search_documents (
            ticket_id,
            document_version,
            rank_schema_version,
            ticket_number,
            subject_text,
            requester_name_text,
            requester_email_text,
            group_name_text,
            assignee_name_text,
            public_comment_text,
            internal_comment_text,
            finalized_at
        )
        select
            ticket_id,
            1,
            1,
            ticket_number,
            subject_text,
            requester_name_text,
            requester_email_text,
            group_name_text,
            assignee_name_text,
            public_comment_text,
            internal_comment_text,
            updated_at
        from source
        where status = 'CLOSED'
        on conflict (ticket_id) do nothing
        returning ticket_id
    ),
    active_cleanup as (
        delete from active_ticket_search_documents document
        using source
        where document.ticket_id = source.ticket_id
          and source.status = 'CLOSED'
        returning document.ticket_id
    ),
    terminal_cleanup as (
        delete from terminal_ticket_search_documents document
        using source
        where document.ticket_id = source.ticket_id
          and source.status <> 'CLOSED'
        returning document.ticket_id
    )
    select count(*) into changed_count
    from active_upsert
    full join terminal_insert using (ticket_id)
    full join active_cleanup using (ticket_id)
    full join terminal_cleanup using (ticket_id);

    accumulated_count := accumulated_count + selected_count;
    current_status := case
        when exists(select 1 from tickets where ticket_number > next_checkpoint) then 'RUNNING'
        else 'COMPLETE'
    end;

    update ticket_search_projection_backfill_state
    set last_ticket_number = next_checkpoint,
        processed_count = accumulated_count,
        status = current_status,
        started_at = coalesce(started_at, transaction_timestamp()),
        updated_at = transaction_timestamp(),
        completed_at = case when current_status = 'COMPLETE' then transaction_timestamp() else null end
    where projection_version = 1;

    return query select selected_count, next_checkpoint, accumulated_count, current_status;
end;
$$;

-- Backfill/reconciliation are operator functions owned by the migration role. Runtime
-- traffic maintains the split projection only through the product write triggers above.
revoke all on function backfill_split_ticket_search_documents(integer) from public;
revoke all on function reconcile_split_ticket_search_documents() from public;
