# Operations rehearsal evidence

- Status: `PASS`
- Started (UTC): `2026-08-11T19:11:57Z`
- Finished (UTC): `2026-08-11T19:13:18Z`
- Git revision: `d1f7bfbaea6946992d2bf7403f51c5dbc4948fdb`
- Git branch: `chore/11-portfolio-release-hardening`
- Git working tree: `dirty` (release branch under construction)
- Mode: `smoke`
- Command: `./scripts/run-operations-rehearsal.sh --smoke --evidence-file /tmp/deskseed-operations-post-review-smoke.md`
- Host: `Darwin arm64`
- Docker Engine: `29.6.2`
- Docker Compose: `5.3.1`
- Isolation: unique Compose projects and fresh named volumes; the cleanup attempt and zero-artifact verification result were recorded before this evidence was rendered.
- Secrets: credential-bearing files stayed under a mode-0700 temporary directory and values were omitted from evidence/stdout; generated values traversed process env/argv during this local rehearsal, so privileged same-host observation was out of scope.

| Check | Result | Evidence |
|---|---|---|
| prerequisites | PASS | Docker Engine/Compose, curl, git, and Python 3 are available; rehearsal ports are free |
| migration inventory | PASS | upgrade path is V11 to V13 |
| smoke build | PASS | backend/frontend built with the local Docker layer cache allowed |
| image pin | PASS | source and restore use one run-scoped backend image sha256:7199332d5fad3d68a8a19f18bf298a9172b00d54727b9b6ed160289a0bae2ed3 and frontend image sha256:671bf8d29b4bb919d2de46929aec34023144680ff12585dc4988728f97d0715e |
| fresh-volume V11 install | PASS | new source volume reached backend health with Flyway V11 and Hibernate validate |
| Flyway history UPDATE denial | PASS | runtime credential was denied |
| V11 role split | PASS | migration role owns DDL; runtime role passed least-privilege verification |
| V11 API smoke | PASS | anonymous submit/lookup, admin login, staff ticket read, ticket audit (1), and access audit (2) succeeded for ticket #1000 |
| canonical audit UPDATE denial | PASS | runtime credential was denied |
| canonical audit DELETE denial | PASS | runtime credential was denied |
| runtime DDL denial | PASS | runtime credential was denied |
| Flyway upgrade | PASS | same volume advanced V11 to V13 in 12s; Hibernate validate and backend/frontend health passed; pre-upgrade ticket remained readable |
| pg_dump backup | PASS | custom-format no-owner/no-ACL snapshot started 2026-08-11T19:13:02Z; 93402 bytes; sha256 be2e8c77f514905d00d078d416d7bd460648aca6739b0e3980220fdd78dda361; 174ms |
| pg_restore data parity | PASS | fresh restore volume matched counts (tickets=1, ticket-audits=1, access-audits=4, admin-audits=3, projection=9) in 217ms |
| restored Flyway history UPDATE denial | PASS | restored runtime credential was denied |
| post-restore application smoke | PASS | V13 backend/frontend health, public token lookup, restored admin login, staff read, and new access audit passed in 10s |
| RPO boundary | PASS | the pre-backup synthetic ticket and all audited reads through the pg_dump snapshot were recovered; no WAL/PITR claim |
| cleanup verification | PASS | source/restore containers, networks, volumes, run-scoped images, and the secret temporary directory are absent |

## RPO/RTO interpretation

This rehearsal uses a single logical `pg_dump` snapshot and does not configure WAL archiving or point-in-time recovery. The recovered ticket and audit counts prove recovery through the snapshot only. Operational worst-case RPO is therefore the backup interval plus changes after the dump snapshot begins; it is not zero. The measured recovery-validation duration is a local RTO observation, not an SLA.

## Scope limitation

There is no prior tagged release image. The upgrade proof gives the runtime role default read/append startup privileges, runs the current application against Flyway target V11 with Hibernate validation enabled, creates data, then advances that same volume to the repository latest migration. Full mode pulls base images and bypasses build-cache reuse for its build steps; it does not delete Docker build cache. It is still a same-host rehearsal rather than an independent second-machine certification.

The split-role setup in this run came from `run-operations-rehearsal.sh` and the private rehearsal overlay. Base Compose does not create or wire the migration/runtime roles, and no TLS-enabled production deployment manifest is provided.
