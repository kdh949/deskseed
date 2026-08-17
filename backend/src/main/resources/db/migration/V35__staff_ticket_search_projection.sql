create table ticket_search_documents (
    ticket_id uuid primary key references tickets(id) on delete cascade,
    document_version integer not null default 1,
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
    constraint ticket_search_document_version_current check (document_version = 1)
);

create unique index ticket_search_documents_ticket_number_idx
    on ticket_search_documents (ticket_number);
create index ticket_search_documents_staff_trgm_idx
    on ticket_search_documents using gin (staff_document gin_trgm_ops);

create or replace function refresh_ticket_search_document(p_ticket_id uuid)
returns void
language plpgsql
as $$
begin
    perform pg_advisory_xact_lock_shared(hashtext('deskseed:ticket-search-documents:rebuild'));

    insert into ticket_search_documents (
        ticket_id,
        document_version,
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
        ticket.id,
        1,
        ticket.ticket_number,
        lower(ticket.subject),
        lower(coalesce(requester.name, '')),
        lower(coalesce(requester.email_normalized, '')),
        lower(coalesce(ticket_group.name, '')),
        lower(coalesce(assignee.display_name, '')),
        coalesce(
            string_agg(lower(comment.body), E'\n' order by comment.created_at, comment.id)
                filter (where comment.visibility = 'PUBLIC'),
            ''
        ),
        coalesce(
            string_agg(lower(comment.body), E'\n' order by comment.created_at, comment.id)
                filter (where comment.visibility = 'INTERNAL'),
            ''
        ),
        transaction_timestamp()
    from tickets ticket
    left join customers requester on requester.id = ticket.requester_id
    left join support_groups ticket_group on ticket_group.id = ticket.group_id
    left join staff_accounts assignee on assignee.id = ticket.assignee_id
    left join ticket_comments comment on comment.ticket_id = ticket.id
    where ticket.id = p_ticket_id
    group by
        ticket.id,
        ticket.ticket_number,
        ticket.subject,
        requester.name,
        requester.email_normalized,
        ticket_group.name,
        assignee.display_name
    on conflict (ticket_id) do update set
        document_version = excluded.document_version,
        ticket_number = excluded.ticket_number,
        subject_text = excluded.subject_text,
        requester_name_text = excluded.requester_name_text,
        requester_email_text = excluded.requester_email_text,
        group_name_text = excluded.group_name_text,
        assignee_name_text = excluded.assignee_name_text,
        public_comment_text = excluded.public_comment_text,
        internal_comment_text = excluded.internal_comment_text,
        refreshed_at = excluded.refreshed_at;
end;
$$;

create or replace function rebuild_ticket_search_documents()
returns bigint
language plpgsql
as $$
declare
    rebuilt_count bigint;
begin
    perform pg_advisory_xact_lock(hashtext('deskseed:ticket-search-documents:rebuild'));

    insert into ticket_search_documents (
        ticket_id,
        document_version,
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
        ticket.id,
        1,
        ticket.ticket_number,
        lower(ticket.subject),
        lower(coalesce(requester.name, '')),
        lower(coalesce(requester.email_normalized, '')),
        lower(coalesce(ticket_group.name, '')),
        lower(coalesce(assignee.display_name, '')),
        coalesce(
            string_agg(lower(comment.body), E'\n' order by comment.created_at, comment.id)
                filter (where comment.visibility = 'PUBLIC'),
            ''
        ),
        coalesce(
            string_agg(lower(comment.body), E'\n' order by comment.created_at, comment.id)
                filter (where comment.visibility = 'INTERNAL'),
            ''
        ),
        transaction_timestamp()
    from tickets ticket
    left join customers requester on requester.id = ticket.requester_id
    left join support_groups ticket_group on ticket_group.id = ticket.group_id
    left join staff_accounts assignee on assignee.id = ticket.assignee_id
    left join ticket_comments comment on comment.ticket_id = ticket.id
    group by
        ticket.id,
        ticket.ticket_number,
        ticket.subject,
        requester.name,
        requester.email_normalized,
        ticket_group.name,
        assignee.display_name
    on conflict (ticket_id) do update set
        document_version = excluded.document_version,
        ticket_number = excluded.ticket_number,
        subject_text = excluded.subject_text,
        requester_name_text = excluded.requester_name_text,
        requester_email_text = excluded.requester_email_text,
        group_name_text = excluded.group_name_text,
        assignee_name_text = excluded.assignee_name_text,
        public_comment_text = excluded.public_comment_text,
        internal_comment_text = excluded.internal_comment_text,
        refreshed_at = excluded.refreshed_at;

    delete from ticket_search_documents document
    where not exists (select 1 from tickets ticket where ticket.id = document.ticket_id);

    select count(*) into rebuilt_count from ticket_search_documents;
    return rebuilt_count;
end;
$$;

create or replace function refresh_ticket_search_document_from_ticket()
returns trigger
language plpgsql
as $$
begin
    perform refresh_ticket_search_document(new.id);
    return new;
end;
$$;

create trigger tickets_search_document_inserted
after insert on tickets
for each row execute function refresh_ticket_search_document_from_ticket();

create trigger tickets_search_document_changed
after update of ticket_number, requester_id, subject, group_id, assignee_id on tickets
for each row execute function refresh_ticket_search_document_from_ticket();

create or replace function append_ticket_search_comment()
returns trigger
language plpgsql
as $$
begin
    perform pg_advisory_xact_lock_shared(hashtext('deskseed:ticket-search-documents:rebuild'));

    update ticket_search_documents
    set public_comment_text = case
            when new.visibility = 'PUBLIC' and public_comment_text = '' then lower(new.body)
            when new.visibility = 'PUBLIC' then public_comment_text || E'\n' || lower(new.body)
            else public_comment_text
        end,
        internal_comment_text = case
            when new.visibility = 'INTERNAL' and internal_comment_text = '' then lower(new.body)
            when new.visibility = 'INTERNAL' then internal_comment_text || E'\n' || lower(new.body)
            else internal_comment_text
        end,
        refreshed_at = transaction_timestamp()
    where ticket_id = new.ticket_id;
    return new;
end;
$$;

create trigger ticket_comments_search_document_inserted
after insert on ticket_comments
for each row execute function append_ticket_search_comment();

create or replace function refresh_ticket_search_document_from_comment()
returns trigger
language plpgsql
as $$
begin
    perform refresh_ticket_search_document(old.ticket_id);
    if tg_op = 'UPDATE' and new.ticket_id <> old.ticket_id then
        perform refresh_ticket_search_document(new.ticket_id);
    end if;
    return old;
end;
$$;

create trigger ticket_comments_search_document_changed
after update or delete on ticket_comments
for each row execute function refresh_ticket_search_document_from_comment();

create or replace function refresh_ticket_search_requester_labels()
returns trigger
language plpgsql
as $$
begin
    perform pg_advisory_xact_lock_shared(hashtext('deskseed:ticket-search-documents:rebuild'));
    update ticket_search_documents document
    set requester_name_text = lower(new.name),
        requester_email_text = lower(new.email_normalized),
        refreshed_at = transaction_timestamp()
    from tickets ticket
    where ticket.id = document.ticket_id and ticket.requester_id = new.id;
    return new;
end;
$$;

create trigger customers_search_document_changed
after update of name, email_normalized on customers
for each row execute function refresh_ticket_search_requester_labels();

create or replace function refresh_ticket_search_group_label()
returns trigger
language plpgsql
as $$
begin
    perform pg_advisory_xact_lock_shared(hashtext('deskseed:ticket-search-documents:rebuild'));
    update ticket_search_documents document
    set group_name_text = lower(new.name),
        refreshed_at = transaction_timestamp()
    from tickets ticket
    where ticket.id = document.ticket_id and ticket.group_id = new.id;
    return new;
end;
$$;

create trigger support_groups_search_document_changed
after update of name on support_groups
for each row execute function refresh_ticket_search_group_label();

create or replace function refresh_ticket_search_assignee_label()
returns trigger
language plpgsql
as $$
begin
    perform pg_advisory_xact_lock_shared(hashtext('deskseed:ticket-search-documents:rebuild'));
    update ticket_search_documents document
    set assignee_name_text = lower(new.display_name),
        refreshed_at = transaction_timestamp()
    from tickets ticket
    where ticket.id = document.ticket_id and ticket.assignee_id = new.id;
    return new;
end;
$$;

create trigger staff_accounts_search_document_changed
after update of display_name on staff_accounts
for each row execute function refresh_ticket_search_assignee_label();

select rebuild_ticket_search_documents();
