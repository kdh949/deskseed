# ADR 0027 — Email is a channel adapter over Ticket/Comment

## Status
Accepted for post-MVP

## Context
Email should not create a second ticket model. Inbound retries, threading, identity, bounces, and outbound delivery are asynchronous concerns.

## Decision
Normalize inbound email into ticket/comment commands through an adapter, deduplicate with provider/message identifiers, preserve threading metadata, and send outbound mail through a notification outbox. Keep provider-specific payloads at the adapter boundary.

## Alternatives
- Embed SMTP/provider calls in ticket transactions: rejected.
- Separate EmailConversation aggregate duplicating tickets: rejected initially.

## Consequences
Web form, email, and later messaging share conversation semantics while channel delivery state remains separate.
