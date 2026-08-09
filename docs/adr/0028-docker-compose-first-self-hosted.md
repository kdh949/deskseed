# ADR 0028 — Docker Compose is the first supported self-hosted topology

## Status
Accepted

## Context
The product is installed for one organization. A reproducible local/small-production deployment is needed before Kubernetes support.

## Decision
Support Docker Compose first with PostgreSQL, backend, frontend/reverse proxy, and optional object storage/mail services. Publish configuration, migrations, backup/restore, upgrade, health, and secret rotation procedures.

## Alternatives
- Kubernetes first: rejected for excessive operational complexity.
- Unspecified manual JAR deployment only: insufficient reproducibility.

## Consequences
Compose upgrade and restore drills are release gates. Kubernetes can be added after real operational demand.
