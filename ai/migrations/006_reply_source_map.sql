alter table ai_jobs
    add column source_map_digest char(64) null,
    add column source_chunk_ids uuid[] null,
    add constraint ai_job_source_map_shape check (
        (source_map_digest is null and source_chunk_ids is null)
        or (
            source_map_digest ~ '^[0-9a-f]{64}$'
            and source_chunk_ids is not null
            and cardinality(source_chunk_ids) between 1 and 8
            and array_position(source_chunk_ids, null) is null
        )
    );
