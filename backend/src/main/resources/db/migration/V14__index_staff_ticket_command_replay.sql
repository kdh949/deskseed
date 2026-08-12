create index ticket_audits_staff_command_replay_idx
    on ticket_audits (actor_id, command_id, created_at, id)
    where actor_type = 'STAFF'
      and actor_id is not null;
