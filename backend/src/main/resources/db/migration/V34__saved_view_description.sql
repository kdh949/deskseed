alter table saved_ticket_views
    add column description varchar(500) not null default '';

alter table saved_ticket_views
    add constraint saved_ticket_views_description_bounded
    check (length(description) <= 500 and description !~ '[[:cntrl:]]');
