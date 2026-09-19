alter table ai_kb_reconciliation_runs
    add column canonical_corpus_revision bigint null,
    add column published_generation bigint null;

alter table ai_kb_reconciliation_runs
    drop constraint ai_kb_reconciliation_status_valid;

update ai_kb_reconciliation_runs
set status = 'FAILED', last_error_code = coalesce(last_error_code, 'LEGACY_UNPUBLISHED')
where status = 'SUCCEEDED';

drop index ai_kb_reconciliation_one_running_idx;
create unique index ai_kb_reconciliation_one_active_idx
    on ai_kb_reconciliation_runs (workspace_key)
    where status in ('RUNNING', 'INDEXING');

alter table ai_kb_reconciliation_runs
    add constraint ai_kb_reconciliation_status_valid
        check (status in ('RUNNING', 'INDEXING', 'SUCCEEDED', 'FAILED')),
    add constraint ai_kb_reconciliation_corpus_revision_positive
        check (canonical_corpus_revision is null or canonical_corpus_revision > 0),
    add constraint ai_kb_reconciliation_generation_positive
        check (published_generation is null or published_generation > 0),
    add constraint ai_kb_reconciliation_publish_shape
        check (
            (status = 'SUCCEEDED' and canonical_corpus_revision is not null and published_generation is not null)
            or (status <> 'SUCCEEDED' and published_generation is null)
        );

alter table ai_kb_reconciliation_seen
    add column source_version bigint null,
    add column public_revision char(64) null,
    add constraint ai_kb_reconciliation_seen_source_version_positive
        check (source_version is null or source_version > 0),
    add constraint ai_kb_reconciliation_seen_public_revision_shape
        check (public_revision is null or public_revision ~ '^[0-9a-f]{64}$');

create table ai_kb_published_generations (
    workspace_key varchar(80) primary key,
    generation bigint not null,
    canonical_corpus_revision bigint not null,
    reconciliation_run_id uuid not null unique references ai_kb_reconciliation_runs(run_id),
    snapshot_token uuid not null unique,
    published_at timestamptz not null,
    constraint ai_kb_published_generation_positive check (generation > 0),
    constraint ai_kb_published_corpus_revision_positive check (canonical_corpus_revision > 0)
);

alter table ai_result_cache
    drop constraint ai_result_cache_key_version,
    drop constraint ai_result_cache_feature,
    add constraint ai_result_cache_key_version check (
        key_version in ('result-cache-summary-triage-v1', 'result-cache-reply-v1')
    ),
    add constraint ai_result_cache_feature check (
        feature in ('ticket.summary', 'ticket.triage', 'ticket.reply_draft')
    ),
    add constraint ai_result_cache_key_feature_match check (
        (key_version = 'result-cache-summary-triage-v1' and feature in ('ticket.summary', 'ticket.triage'))
        or (key_version = 'result-cache-reply-v1' and feature = 'ticket.reply_draft')
    );
