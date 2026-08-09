# ADR 0026 — Object-storage attachment pipeline

## Status
Accepted for post-MVP

## Context
Attachments are large, untrusted, and have different retention and delivery requirements from ticket rows.

## Decision
Store attachment metadata in PostgreSQL and bytes in S3-compatible private object storage. Use bounded uploads, quarantine/scan states, short-lived authorized download URLs, and access audit. Never store public bucket URLs or arbitrary file bytes in ticket tables.

## Alternatives
- Database BLOBs: rejected for backup/query impact.
- Public object URLs: rejected for authorization leakage.
- Local filesystem only: unsuitable for reliable multi-instance/self-hosted upgrades.

## Consequences
Object storage and malware scanner become optional deployment dependencies when attachments are enabled.
