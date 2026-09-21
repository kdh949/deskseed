create index if not exists active_ticket_search_documents_staff_document_trgm_idx
    on active_ticket_search_documents using gin (staff_document gin_trgm_ops);

create index if not exists terminal_ticket_search_documents_staff_document_trgm_idx
    on terminal_ticket_search_documents using gin (staff_document gin_trgm_ops);
