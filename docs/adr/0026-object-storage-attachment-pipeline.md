# ADR 0026 — Object-storage attachment pipeline

## Status
Accepted for post-MVP

## Context
Attachments are large, untrusted, and have different retention and delivery requirements from ticket rows.

## Decision
Store attachment metadata in PostgreSQL and bytes in S3-compatible private object storage. Use bounded uploads, quarantine/scan states, authorized private streaming downloads, and access audit. Never store public bucket URLs or arbitrary file bytes in ticket tables.

The supported self-hosted production adapter uses VersityGW with a private POSIX backend. Unknown-length uploads use bounded multipart parts rather than whole-stream buffering.

The normal rule remains that an attachment is CLEAN only after a scanner decision. A deployment may use `UPSTREAM_WAF` as that scanner source only when all upload routes are forced through Sophos WAF, upload antivirus is enabled, unscannable requests are blocked, the WAF scan/body limit covers the application upload limit, and direct origin access is denied. This exception requires explicit application and deployment acknowledgement and is recorded as the scan source. It must not become a generic no-scan mode.

Retention uses a short database claim/lease transaction, performs the idempotent object-store delete after commit, and records `EXPIRED` plus the required security audit in a second transaction. A failed remote delete releases the claim; a failed completion leaves the claim retryable after lease expiry. No S3 call runs while a PostgreSQL row lock or application transaction is held.

## Alternatives
- Database BLOBs: rejected for backup/query impact.
- Public object URLs: rejected for authorization leakage.
- Local filesystem only: unsuitable for reliable multi-instance/self-hosted upgrades.

## Consequences
Object storage and a malware decision source become deployment dependencies when attachments are enabled. The bundled production Compose uses internal plaintext HTTP to a same-host VersityGW; this requires explicit acknowledgement and does not protect against the Docker host/root threat boundary. External S3 endpoints must use HTTPS. Production backup/restore must keep PostgreSQL and all three VersityGW volumes in one consistent backup set; DB-only recovery is unsupported.
