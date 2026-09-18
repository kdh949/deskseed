alter table ticket_comments
    add column sequence_number bigint;

with ranked_comments as (
    select id,
           row_number() over (partition by ticket_id order by created_at, id) as sequence_number
    from ticket_comments
)
update ticket_comments comment
set sequence_number = ranked.sequence_number
from ranked_comments ranked
where ranked.id = comment.id;

alter table ticket_comments
    alter column sequence_number set not null,
    add constraint ticket_comment_sequence_positive check (sequence_number > 0),
    add constraint ticket_comment_sequence_unique unique (ticket_id, sequence_number);

create function assign_ticket_comment_sequence() returns trigger
language plpgsql
as $$
begin
    if new.sequence_number is null then
        perform 1 from tickets where id = new.ticket_id for update;
        select coalesce(max(comment.sequence_number), 0) + 1
        into new.sequence_number
        from ticket_comments comment
        where comment.ticket_id = new.ticket_id;
    end if;
    return new;
end;
$$;

create trigger ticket_comments_assign_sequence
before insert on ticket_comments
for each row execute function assign_ticket_comment_sequence();
