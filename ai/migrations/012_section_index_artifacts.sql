alter table ai_kb_reconciliation_runs
    add column target_artifact_generation bigint null,
    add column index_contract_version varchar(80) null,
    add column chunker_version varchar(80) null,
    add column normalization_version varchar(80) null,
    add column embedding_model varchar(100) null,
    add column embedding_dimension integer null,
    add constraint ai_kb_reconciliation_artifact_generation_positive
        check (target_artifact_generation is null or target_artifact_generation > 0),
    add constraint ai_kb_reconciliation_embedding_dimension_positive
        check (embedding_dimension is null or embedding_dimension > 0),
    add constraint ai_kb_reconciliation_index_spec_shape check (
        (target_artifact_generation is null and index_contract_version is null
            and chunker_version is null and normalization_version is null
            and embedding_model is null and embedding_dimension is null)
        or (target_artifact_generation is not null and index_contract_version is not null
            and chunker_version is not null and normalization_version is not null
            and embedding_model is not null and embedding_dimension is not null)
    );

alter table ai_kb_index_jobs
    add column artifact_generation bigint null,
    add column reconciliation_run_id uuid null references ai_kb_reconciliation_runs(run_id),
    add constraint ai_kb_index_job_artifact_generation_positive
        check (artifact_generation is null or artifact_generation > 0),
    add constraint ai_kb_index_job_build_shape check (
        (artifact_generation is null and reconciliation_run_id is null)
        or (artifact_generation is not null and reconciliation_run_id is not null)
    );

alter table ai_kb_published_generations
    add column artifact_generation bigint null;

update ai_kb_published_generations
set artifact_generation = generation;

alter table ai_kb_published_generations
    alter column artifact_generation set not null,
    add constraint ai_kb_published_artifact_generation_positive check (artifact_generation > 0);

alter table ai_kb_revisions
    add column artifact_generation bigint null,
    add column category_title varchar(200) not null default 'PUBLIC',
    add column section_title varchar(200) not null default 'PUBLIC';

update ai_kb_revisions revision
set artifact_generation = coalesce(
    (select published.artifact_generation
     from ai_kb_published_generations published
     where published.workspace_key = revision.workspace_key),
    1
);

alter table ai_kb_revisions
    alter column artifact_generation set not null,
    alter column category_title drop default,
    alter column section_title drop default,
    add constraint ai_kb_revision_artifact_generation_positive check (artifact_generation > 0),
    add constraint ai_kb_revision_category_title_bounded check (
        length(btrim(category_title)) between 1 and 200 and category_title !~ '[[:cntrl:]]'
    ),
    add constraint ai_kb_revision_section_title_bounded check (
        length(btrim(section_title)) between 1 and 200 and section_title !~ '[[:cntrl:]]'
    );

alter table ai_kb_chunks
    add column artifact_generation bigint null,
    add column search_text text null,
    add column embedding_input_sha256 char(64) null;

update ai_kb_chunks chunk
set artifact_generation = revision.artifact_generation,
    search_text = concat_ws(E'\n', revision.title, chunk.content),
    embedding_input_sha256 = chunk.content_sha256
from ai_kb_revisions revision
where revision.workspace_key = chunk.workspace_key
  and revision.article_id = chunk.article_id
  and revision.revision_id = chunk.revision_id;

alter table ai_kb_chunks
    alter column artifact_generation set not null,
    alter column search_text set not null,
    alter column embedding_input_sha256 set not null,
    add constraint ai_kb_chunk_artifact_generation_positive check (artifact_generation > 0),
    add constraint ai_kb_chunk_search_text_bounded check (length(search_text) between 1 and 12000),
    add constraint ai_kb_chunk_embedding_input_hash_shape
        check (embedding_input_sha256 ~ '^[0-9a-f]{64}$');

alter table ai_kb_chunks drop constraint ai_kb_chunks_article_id_revision_id_fkey;
alter table ai_kb_chunks drop constraint ai_kb_chunk_revision_ordinal_unique;
alter table ai_kb_revisions drop constraint ai_kb_revisions_pkey;

alter table ai_kb_revisions
    add primary key (workspace_key, artifact_generation, article_id, revision_id);

alter table ai_kb_chunks
    add constraint ai_kb_chunks_artifact_revision_fkey
        foreign key (workspace_key, artifact_generation, article_id, revision_id)
        references ai_kb_revisions(workspace_key, artifact_generation, article_id, revision_id)
        on delete cascade,
    add constraint ai_kb_chunk_artifact_revision_ordinal_unique
        unique (workspace_key, artifact_generation, article_id, revision_id, ordinal);

drop index ai_kb_current_idx;
create index ai_kb_current_idx
    on ai_kb_revisions (workspace_key, artifact_generation, article_id, indexed_at desc);

drop index ai_kb_chunks_content_fts_idx;
create index ai_kb_chunks_search_fts_idx
    on ai_kb_chunks using gin (to_tsvector('simple', search_text));
create index ai_kb_chunks_artifact_revision_idx
    on ai_kb_chunks (workspace_key, artifact_generation, article_id, revision_id, ordinal);

create table ai_kb_index_artifacts (
    workspace_key varchar(80) not null,
    artifact_generation bigint not null,
    canonical_corpus_revision bigint not null,
    reconciliation_run_id uuid null unique references ai_kb_reconciliation_runs(run_id),
    state varchar(20) not null,
    index_contract_version varchar(80) not null,
    chunker_version varchar(80) not null,
    normalization_version varchar(80) not null,
    embedding_model varchar(100) not null,
    embedding_dimension integer not null,
    created_at timestamptz not null,
    completed_at timestamptz null,
    failure_code varchar(80) null,
    primary key (workspace_key, artifact_generation),
    constraint ai_kb_index_artifact_generation_positive check (artifact_generation > 0),
    constraint ai_kb_index_artifact_corpus_revision_positive check (canonical_corpus_revision > 0),
    constraint ai_kb_index_artifact_dimension_positive check (embedding_dimension > 0),
    constraint ai_kb_index_artifact_state_valid check (state in ('BUILDING', 'COMPLETE', 'FAILED')),
    constraint ai_kb_index_artifact_completion_shape check (
        (state = 'BUILDING' and completed_at is null and failure_code is null)
        or (state = 'COMPLETE' and completed_at is not null and failure_code is null)
        or (state = 'FAILED' and completed_at is not null and failure_code is not null)
    )
);

insert into ai_kb_index_artifacts (
    workspace_key, artifact_generation, canonical_corpus_revision, reconciliation_run_id,
    state, index_contract_version, chunker_version, normalization_version,
    embedding_model, embedding_dimension, created_at, completed_at
)
select published.workspace_key, published.artifact_generation, published.canonical_corpus_revision,
       published.reconciliation_run_id, 'COMPLETE', 'public-kb-artifact-v2',
       'public-kb-fixed-1800-v1', 'legacy-text-v1', 'openai/text-embedding-3-small',
       1536, published.published_at, published.published_at
from ai_kb_published_generations published;

create table ai_kb_index_publication_history (
    workspace_key varchar(80) not null,
    publication_epoch bigint not null,
    artifact_generation bigint not null,
    canonical_corpus_revision bigint not null,
    action varchar(16) not null,
    reason varchar(500) null,
    published_at timestamptz not null,
    primary key (workspace_key, publication_epoch),
    foreign key (workspace_key, artifact_generation)
        references ai_kb_index_artifacts(workspace_key, artifact_generation),
    constraint ai_kb_index_publication_epoch_positive check (publication_epoch > 0),
    constraint ai_kb_index_publication_artifact_positive check (artifact_generation > 0),
    constraint ai_kb_index_publication_corpus_positive check (canonical_corpus_revision > 0),
    constraint ai_kb_index_publication_action_valid check (action in ('INITIAL', 'PUBLISH', 'RESTORE')),
    constraint ai_kb_index_publication_reason_bounded check (
        reason is null or (length(btrim(reason)) between 1 and 500 and reason !~ '[[:cntrl:]]')
    )
);

insert into ai_kb_index_publication_history (
    workspace_key, publication_epoch, artifact_generation, canonical_corpus_revision,
    action, reason, published_at
)
select workspace_key, generation, artifact_generation, canonical_corpus_revision,
       'INITIAL', null, published_at
from ai_kb_published_generations;
