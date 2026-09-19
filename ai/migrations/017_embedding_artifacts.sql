create table ai_embedding_artifacts (
    artifact_key char(64) primary key,
    key_version varchar(40) not null,
    model_snapshot varchar(160) not null,
    embedding_dimension integer not null,
    normalization_version varchar(80) not null,
    embedding_input_sha256 char(64) not null,
    actual_model varchar(160) not null,
    embedding vector(1536) not null,
    created_at timestamptz not null,
    last_used_at timestamptz not null,
    constraint ai_embedding_artifact_key_shape
        check (artifact_key ~ '^[0-9a-f]{64}$'),
    constraint ai_embedding_artifact_key_version_valid
        check (key_version = 'embedding-artifact-v1'),
    constraint ai_embedding_artifact_model_snapshot_bounded check (
        length(btrim(model_snapshot)) between 1 and 160
        and model_snapshot !~ '[[:cntrl:]]'
    ),
    constraint ai_embedding_artifact_dimension_supported
        check (embedding_dimension = 1536),
    constraint ai_embedding_artifact_normalization_bounded check (
        length(btrim(normalization_version)) between 1 and 80
        and normalization_version !~ '[[:cntrl:]]'
    ),
    constraint ai_embedding_artifact_input_hash_shape
        check (embedding_input_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ai_embedding_artifact_actual_model_bounded check (
        length(btrim(actual_model)) between 1 and 160
        and actual_model !~ '[[:cntrl:]]'
    ),
    unique (model_snapshot, embedding_dimension, normalization_version, embedding_input_sha256)
);

alter table ai_kb_chunks
    add column embedding_artifact_key char(64) null,
    add constraint ai_kb_chunk_embedding_artifact_key_shape check (
        embedding_artifact_key is null
        or embedding_artifact_key ~ '^[0-9a-f]{64}$'
    ),
    add constraint ai_kb_chunk_embedding_artifact_fkey
        foreign key (embedding_artifact_key)
        references ai_embedding_artifacts(artifact_key);

create index ai_kb_chunks_embedding_artifact_idx
    on ai_kb_chunks (embedding_artifact_key)
    where embedding_artifact_key is not null;
