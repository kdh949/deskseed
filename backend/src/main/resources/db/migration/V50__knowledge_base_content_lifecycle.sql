create table knowledge_categories (
    id uuid primary key,
    slug varchar(120) not null,
    title varchar(200) not null,
    description varchar(1000) not null default '',
    status varchar(20) not null,
    display_order integer not null,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    archived_at timestamptz null,
    constraint knowledge_categories_slug_bounded check (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    constraint knowledge_categories_title_bounded check (length(btrim(title)) between 1 and 200 and title !~ '[[:cntrl:]]'),
    constraint knowledge_categories_description_bounded check (length(description) <= 1000 and description !~ '[[:cntrl:]]'),
    constraint knowledge_categories_status_valid check (status in ('ACTIVE', 'ARCHIVED')),
    constraint knowledge_categories_display_order_valid check (display_order >= 0),
    constraint knowledge_categories_archive_timestamp check (
        (status = 'ARCHIVED' and archived_at is not null) or (status = 'ACTIVE' and archived_at is null)
    )
);

create unique index knowledge_categories_slug_unique
    on knowledge_categories (lower(btrim(slug)));
create unique index knowledge_categories_active_display_order_unique
    on knowledge_categories (display_order)
    where status = 'ACTIVE';

create table knowledge_sections (
    id uuid primary key,
    category_id uuid not null references knowledge_categories(id),
    slug varchar(120) not null,
    title varchar(200) not null,
    description varchar(1000) not null default '',
    status varchar(20) not null,
    display_order integer not null,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    archived_at timestamptz null,
    constraint knowledge_sections_slug_bounded check (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    constraint knowledge_sections_title_bounded check (length(btrim(title)) between 1 and 200 and title !~ '[[:cntrl:]]'),
    constraint knowledge_sections_description_bounded check (length(description) <= 1000 and description !~ '[[:cntrl:]]'),
    constraint knowledge_sections_status_valid check (status in ('ACTIVE', 'ARCHIVED')),
    constraint knowledge_sections_display_order_valid check (display_order >= 0),
    constraint knowledge_sections_archive_timestamp check (
        (status = 'ARCHIVED' and archived_at is not null) or (status = 'ACTIVE' and archived_at is null)
    )
);

create unique index knowledge_sections_category_slug_unique
    on knowledge_sections (category_id, lower(btrim(slug)));
create unique index knowledge_sections_category_active_display_order_unique
    on knowledge_sections (category_id, display_order)
    where status = 'ACTIVE';

create table knowledge_articles (
    id uuid primary key,
    section_id uuid not null references knowledge_sections(id),
    slug varchar(120) not null,
    lifecycle varchar(20) not null,
    audience_type varchar(30) not null,
    audience_version integer not null default 1,
    current_published_revision_id uuid null,
    author_id uuid not null references staff_accounts(id),
    reviewer_id uuid null references staff_accounts(id),
    published_at timestamptz null,
    archived_at timestamptz null,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint knowledge_articles_slug_bounded check (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    constraint knowledge_articles_lifecycle_valid check (lifecycle in ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'UNPUBLISHED', 'ARCHIVED')),
    constraint knowledge_articles_audience_valid check (audience_type in ('PUBLIC', 'SIGNED_IN_CUSTOMER', 'STAFF', 'SELECTED_STAFF_GROUPS')),
    constraint knowledge_articles_audience_version_valid check (audience_version > 0),
    constraint knowledge_articles_lifecycle_timestamps check (
        (lifecycle = 'PUBLISHED' and published_at is not null and archived_at is null)
        or (lifecycle = 'ARCHIVED' and archived_at is not null)
        or (lifecycle not in ('PUBLISHED', 'ARCHIVED') and published_at is null and archived_at is null)
    )
);

create unique index knowledge_articles_slug_unique
    on knowledge_articles (lower(btrim(slug)));
create index knowledge_articles_section_lifecycle_idx
    on knowledge_articles (section_id, lifecycle, updated_at desc, id desc);

create table knowledge_article_audience_groups (
    article_id uuid not null references knowledge_articles(id) on delete cascade,
    group_id uuid not null references support_groups(id),
    primary key (article_id, group_id)
);

create index knowledge_article_audience_groups_group_idx
    on knowledge_article_audience_groups (group_id, article_id);

create table knowledge_article_revisions (
    id uuid primary key,
    article_id uuid not null references knowledge_articles(id),
    revision_number integer not null,
    title varchar(300) not null,
    document_json jsonb not null,
    plain_text text not null,
    summary varchar(1000) not null default '',
    change_note varchar(1000) not null default '',
    content_checksum char(64) not null,
    created_by_staff_id uuid not null references staff_accounts(id),
    created_at timestamptz not null,
    constraint knowledge_article_revisions_number_valid check (revision_number > 0),
    constraint knowledge_article_revisions_title_bounded check (length(btrim(title)) between 1 and 300 and title !~ '[[:cntrl:]]'),
    constraint knowledge_article_revisions_document_object check (jsonb_typeof(document_json) = 'object'),
    constraint knowledge_article_revisions_plain_text_bounded check (length(plain_text) between 1 and 500000 and plain_text !~ '[[:cntrl:]]'),
    constraint knowledge_article_revisions_summary_bounded check (length(summary) <= 1000 and summary !~ '[[:cntrl:]]'),
    constraint knowledge_article_revisions_change_note_bounded check (length(change_note) <= 1000 and change_note !~ '[[:cntrl:]]'),
    constraint knowledge_article_revisions_checksum_shape check (content_checksum ~ '^[0-9a-f]{64}$'),
    constraint knowledge_article_revisions_article_number_unique unique (article_id, revision_number)
);

alter table knowledge_articles
    add constraint knowledge_articles_current_published_revision_fk
    foreign key (current_published_revision_id) references knowledge_article_revisions(id);

create index knowledge_article_revisions_article_created_idx
    on knowledge_article_revisions (article_id, revision_number desc);

create or replace function reject_knowledge_article_revision_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Knowledge article revisions are immutable';
end;
$$;

create trigger knowledge_article_revisions_immutable
before update or delete on knowledge_article_revisions
for each row execute function reject_knowledge_article_revision_mutation();
