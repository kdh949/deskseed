-- Preserve the policy ordering that selected each candidate even if definitions later move.
alter table automation_candidates add column position_snapshot integer;

update automation_candidates candidate
   set position_snapshot = definition.position
  from automation_definitions definition
 where definition.id = candidate.automation_id;

alter table automation_candidates alter column position_snapshot set not null;
alter table automation_candidates add constraint automation_candidates_position_snapshot_valid
    check (position_snapshot between 1 and 10000);

drop index automation_candidates_claim_idx;
create index automation_candidates_claim_idx
    on automation_candidates (available_at, eligible_at, position_snapshot, automation_id, automation_version, id)
    where status in ('PENDING', 'RETRY_SCHEDULED');

create index automation_candidates_ticket_order_idx
    on automation_candidates (ticket_id, solved_at, position_snapshot, automation_id, automation_version, id)
    where status in ('PENDING', 'LEASED', 'RETRY_SCHEDULED');
