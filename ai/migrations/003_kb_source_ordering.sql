alter table ai_kb_index_jobs
    add column source_version bigint not null;

alter table ai_kb_index_jobs
    add constraint ai_kb_index_job_source_version_positive check (source_version > 0);

create table ai_kb_article_state (
    workspace_key varchar(80) not null,
    article_id uuid not null,
    source_version bigint not null,
    action varchar(16) not null,
    event_id uuid not null references ai_kb_index_inbox(event_id),
    updated_at timestamptz not null,
    primary key (workspace_key, article_id),
    constraint ai_kb_article_state_version_positive check (source_version > 0),
    constraint ai_kb_article_state_action_valid check (action in ('UPSERT', 'DELETE'))
);
