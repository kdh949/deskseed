-- ADMIN operations use stable queued_at/id keyset pages, optionally constrained by status.
-- Retry locks use the primary key; this index is additive and does not alter outbox semantics.
create index outbound_mail_intents_operations_cursor_idx
    on outbound_mail_intents (queued_at desc, id desc);

create index outbound_mail_intents_operations_status_cursor_idx
    on outbound_mail_intents (status, queued_at desc, id desc);
