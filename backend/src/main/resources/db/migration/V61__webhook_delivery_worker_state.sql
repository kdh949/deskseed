alter table webhook_endpoints
    add column half_open_claimed boolean not null default false,
    add column active_delivery_count integer not null default 0,
    add constraint webhook_endpoint_active_delivery_count check (active_delivery_count >= 0);

alter table webhook_deliveries
    add column endpoint_version bigint not null default 0;

create index webhook_endpoint_delivery_capacity_idx
    on webhook_endpoints (enabled, health_state, cooldown_until, active_delivery_count, id)
    where deactivated_at is null;
