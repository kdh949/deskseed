create table ticket_relations (
    id uuid primary key,
    source_ticket_id uuid not null references tickets(id),
    target_ticket_id uuid not null references tickets(id),
    relation_type varchar(40) not null,
    created_by_actor_type varchar(30) not null,
    created_by_actor_id uuid null,
    created_at timestamptz not null,
    constraint ticket_relation_type_valid check (relation_type in ('PARENT_CHILD')),
    constraint ticket_relation_actor_type_valid check (
        created_by_actor_type in (
            'CUSTOMER', 'STAFF', 'INTEGRATION_CLIENT', 'TRIGGER',
            'AUTOMATION', 'SYSTEM'
        )
    ),
    constraint ticket_relation_not_self check (source_ticket_id <> target_ticket_id),
    constraint ticket_relation_unique unique (
        source_ticket_id, target_ticket_id, relation_type
    )
);

create unique index ticket_relations_one_parent_idx
    on ticket_relations (target_ticket_id)
    where relation_type = 'PARENT_CHILD';

create index ticket_relations_parent_children_idx
    on ticket_relations (source_ticket_id, relation_type, created_at, target_ticket_id);
