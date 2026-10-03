alter table ai_knowledge_index_outbox
    add column source_version bigint not null;

alter table ai_knowledge_index_outbox
    add constraint ai_kb_outbox_source_version_positive check (source_version > 0);

alter table ai_knowledge_manifest_snapshot_items
    add column source_version bigint not null;

alter table ai_knowledge_manifest_snapshot_items
    add constraint ai_kb_manifest_source_version_positive check (source_version > 0);
