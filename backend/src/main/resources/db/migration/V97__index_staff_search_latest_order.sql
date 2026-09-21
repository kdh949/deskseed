create index tickets_staff_search_latest_idx
    on tickets (updated_at desc, ticket_number desc)
    include (id);
