create table staff_authority_grants (
    id uuid primary key,
    staff_id uuid not null references staff_accounts(id),
    authority varchar(40) not null,
    granted_by_staff_id uuid not null references staff_accounts(id),
    granted_at timestamptz not null,
    constraint staff_authority_grant_unique unique (staff_id, authority),
    constraint staff_authority_grant_value_valid check (
        authority in (
            'AUDIT_SEARCH_QUERY_REVEAL',
            'AUDIT_EXPORT',
            'AUDIT_PROJECTION_REBUILD'
        )
    )
);

create index staff_authority_grants_granted_by_idx
    on staff_authority_grants (granted_by_staff_id, granted_at desc, id);
