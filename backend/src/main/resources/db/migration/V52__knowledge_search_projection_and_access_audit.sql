-- V50 owns the canonical knowledge aggregate. This additive migration owns only
-- derived search data, feedback totals, and the separate restricted-read audit.
-- The projection is rebuilt from immutable published revisions; it never becomes
-- a second source of truth for article content.

create unique index knowledge_sections_slug_global_unique
    on knowledge_sections (lower(btrim(slug)));

create table knowledge_search_documents (
    article_id uuid primary key references knowledge_articles(id) on delete cascade,
    revision_id uuid not null references knowledge_article_revisions(id),
    search_document tsvector not null,
    indexed_at timestamptz not null
);

create index knowledge_search_documents_fts_idx
    on knowledge_search_documents using gin (search_document);

create index knowledge_article_revisions_title_trgm_idx
    on knowledge_article_revisions using gin (title gin_trgm_ops);

create or replace function refresh_knowledge_search_document()
returns trigger
language plpgsql
as $$
declare
    revision knowledge_article_revisions%rowtype;
begin
    if new.lifecycle <> 'PUBLISHED' or new.current_published_revision_id is null then
        delete from knowledge_search_documents where article_id = new.id;
        return new;
    end if;

    select * into revision
      from knowledge_article_revisions
     where id = new.current_published_revision_id;

    insert into knowledge_search_documents (article_id, revision_id, search_document, indexed_at)
    values (
        new.id,
        revision.id,
        setweight(to_tsvector('simple', revision.title), 'A') ||
            setweight(to_tsvector('simple', coalesce(revision.summary, '')), 'B') ||
            setweight(to_tsvector('simple', revision.plain_text), 'C'),
        now()
    )
    on conflict (article_id) do update
        set revision_id = excluded.revision_id,
            search_document = excluded.search_document,
            indexed_at = excluded.indexed_at;
    return new;
end;
$$;

create trigger knowledge_articles_refresh_search_document
after insert or update of lifecycle, current_published_revision_id on knowledge_articles
for each row execute function refresh_knowledge_search_document();

create table knowledge_search_index_status (
    singleton boolean primary key default true,
    state varchar(20) not null,
    last_rebuilt_at timestamptz null,
    updated_at timestamptz not null,
    constraint knowledge_search_index_status_singleton check (singleton),
    constraint knowledge_search_index_status_valid check (state in ('IDLE', 'REBUILDING', 'FAILED'))
);

insert into knowledge_search_index_status (singleton, state, last_rebuilt_at, updated_at)
values (true, 'IDLE', null, now());

create table knowledge_article_feedback_totals (
    article_id uuid primary key references knowledge_articles(id) on delete cascade,
    helpful_count bigint not null default 0,
    not_helpful_count bigint not null default 0,
    updated_at timestamptz not null,
    constraint knowledge_article_feedback_helpful_nonnegative check (helpful_count >= 0),
    constraint knowledge_article_feedback_not_helpful_nonnegative check (not_helpful_count >= 0)
);

create table knowledge_access_audit_events (
    id uuid primary key,
    event_type varchar(40) not null,
    actor_id uuid not null references staff_accounts(id),
    actor_display_snapshot varchar(100) not null,
    source varchar(40) not null,
    session_fingerprint varchar(100) not null,
    article_id uuid null references knowledge_articles(id),
    ticket_number bigint null,
    query_redacted varchar(500) null,
    query_fingerprint varchar(100) null,
    query_key_version varchar(64) null,
    query_ciphertext bytea null,
    query_expires_at timestamptz null,
    result_count bigint null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    occurred_at timestamptz not null,
    constraint knowledge_access_audit_event_type_valid check (
        event_type in ('KNOWLEDGE_SEARCH_EXECUTED', 'KNOWLEDGE_ARTICLE_VIEWED', 'TICKET_KNOWLEDGE_SUGGESTED')
    ),
    constraint knowledge_access_audit_source_valid check (source = 'AGENT_UI'),
    constraint knowledge_access_audit_ticket_positive check (ticket_number is null or ticket_number > 0),
    constraint knowledge_access_audit_result_nonnegative check (result_count is null or result_count >= 0),
    constraint knowledge_access_audit_search_shape check (
        (event_type in ('KNOWLEDGE_SEARCH_EXECUTED', 'TICKET_KNOWLEDGE_SUGGESTED')
            and query_redacted is not null and query_fingerprint is not null and query_key_version is not null
            and query_ciphertext is not null and query_expires_at is not null and result_count is not null)
        or
        (event_type = 'KNOWLEDGE_ARTICLE_VIEWED'
            and query_redacted is null and query_fingerprint is null and query_key_version is null
            and query_ciphertext is null and query_expires_at is null and result_count is null)
    )
);

create index knowledge_access_audit_actor_occurred_idx
    on knowledge_access_audit_events (actor_id, occurred_at desc, id desc);
create index knowledge_access_audit_article_occurred_idx
    on knowledge_access_audit_events (article_id, occurred_at desc, id desc)
    where article_id is not null;
create index knowledge_access_audit_query_fingerprint_idx
    on knowledge_access_audit_events (query_fingerprint, occurred_at desc, id desc)
    where query_fingerprint is not null;

create trigger knowledge_access_audit_events_immutable
before update or delete on knowledge_access_audit_events
for each row execute function reject_access_audit_mutation();
