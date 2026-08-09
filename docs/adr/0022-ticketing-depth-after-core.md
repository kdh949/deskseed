# ADR 0022 — Add views, tags, custom fields, macros, and search after core commands

## Status
Accepted

## Context
Zendesk-like breadth is valuable, but these capabilities depend on stable ticket fields, permissions, audit semantics, and command execution.

## Decision
Complete core ticket processing, transfer, child collaboration, and audit first. Then introduce tags/views, typed custom fields/forms, macros, and permission-aware PostgreSQL search in that dependency order.

## Alternatives
- Build a generic dynamic workflow system first: rejected as speculative.
- Never support custom configuration: rejected because real help desks need it.

## Consequences
Schema seams and condition ASTs are documented now, while implementations remain staged and versioned.
