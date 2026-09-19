create table ai_public_knowledge_corpus_state (
    singleton boolean primary key default true check (singleton),
    revision bigint not null,
    updated_at timestamptz not null,
    constraint ai_public_knowledge_corpus_revision_positive check (revision > 0)
);

insert into ai_public_knowledge_corpus_state (singleton, revision, updated_at)
values (true, 1, clock_timestamp());

alter table ai_knowledge_manifest_snapshots
    add column canonical_public_corpus_revision bigint;

update ai_knowledge_manifest_snapshots
set canonical_public_corpus_revision = 1
where canonical_public_corpus_revision is null;

alter table ai_knowledge_manifest_snapshots
    alter column canonical_public_corpus_revision set not null,
    add constraint ai_knowledge_manifest_corpus_revision_positive
        check (canonical_public_corpus_revision > 0);
