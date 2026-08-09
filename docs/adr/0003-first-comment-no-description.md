# ADR 0003: The request body is the first public comment

- Status: Accepted
- Date: 2026-08-10

## Context

A ticket description and a conversation comment often duplicate the same customer message and create two histories with unclear edit semantics.

## Decision

`Ticket` has no `description` field or column. Creating a customer request atomically creates the ticket and its first `PUBLIC` comment. A customer-originated ticket may not be committed without that first comment.

## Alternatives

- Store `description` on Ticket and copy it to the conversation.
- Store only a description and introduce comments after the first reply.

## Consequences

- Rendering a ticket always reads its ordered comments.
- Search and analytics that need the initial body derive it from the first public comment.
- Public comment immutability/correction rules remain consistent from the first message onward.
