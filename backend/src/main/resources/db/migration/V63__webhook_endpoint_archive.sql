alter table webhook_endpoints
    add column archived_at timestamptz null,
    add constraint webhook_endpoint_archive_requires_disabled check (archived_at is null or enabled = false);

create index webhook_endpoints_archive_list_idx
    on webhook_endpoints (created_at desc, id desc)
    where archived_at is null;
