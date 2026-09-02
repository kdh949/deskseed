alter table attachment_objects
    add column cleanup_claim_id uuid null,
    add column cleanup_lease_expires_at timestamptz null,
    add column cleanup_attempt_count integer not null default 0,
    add constraint attachment_objects_cleanup_claim_shape check (
        (cleanup_claim_id is null and cleanup_lease_expires_at is null)
        or (cleanup_claim_id is not null and cleanup_lease_expires_at is not null)
    ),
    add constraint attachment_objects_cleanup_attempt_count_valid check (cleanup_attempt_count >= 0);

create index attachment_objects_cleanup_lease_idx
    on attachment_objects (expires_at, cleanup_lease_expires_at, id)
    where scan_status in ('QUARANTINED', 'CLEAN', 'INFECTED', 'FAILED');
