# Operations rehearsal evidence

- Status: `PASS`
- Started (UTC): `2026-08-12T02:33:04Z`
- Finished (UTC): `2026-08-12T02:35:21Z`
- Git revision: `47c8e5065ec3aff84b3506e4a806ac8149e09b58`
- Git branch: `feature/pr17-pr18-review-followup`
- Git working tree: `dirty` (release branch under construction)
- Mode: `full`
- Command: `./scripts/run-operations-rehearsal.sh --evidence-file <path>`
- Host: `Darwin arm64`
- Rehearsal container identity: host UID `501`, GID `20` (UID 0 is rejected)
- Docker Engine: `29.6.2`
- Docker Compose: `5.3.1`
- Docker client boundary: exact CLI `sha256:d5f08d666045b1e77a3f97cde421c6a287c45f635b0ec18e9471b82f2eb61003`; task-owned anonymous mode-0600 config with no credential helper; validated local unix endpoint `sha256:b14fac23a8b1e7b52733e74b3a04feb708d2faf8db62f048672d119aafc54e03`; socket identity `sha256:b73dc0bf99da201140d62bb1bc88746de632e0e88e626597f750c0ce7bccf1a9`; daemon identity `sha256:cc6aa50015b2ecf91dabae320c1cad2b0ed71772284711dd3277ee187a145788`; preexisting user config `VERIFIED_UNCHANGED`.
- Docker environment boundary: inherited cleared/pinned variables `NONE`; OpenTelemetry SDK/exporters are disabled to prevent CLI/Compose/Buildx command metadata export; retained proxy variables `NONE` are fingerprint-verified and redacted but intentionally preserved because the client is pinned to a unix socket and registry/build fetches are daemon/BuildKit-side. Daemon-side registry mirrors, proxy, and auth remain outside runner control.
- Docker command bounds: pull `300s`; each build/Compose-build `2400s`; timeout sends TERM to the command process group, waits `10s`, then sends KILL and verifies the entire process group absent even if its leader exited first.
- Isolation: a mktemp-derived cryptographic run marker labels every created container, network, volume, and image; exact identities are captured before cleanup, and zero run/project artifacts are verified before this evidence is rendered.
- Secrets: when allocated, credential-bearing files stay under a mode-0700 temporary directory; command diagnostics are bounded and generically redacted before terminal/evidence output. Generated values traverse process env/argv during this local rehearsal, so privileged same-host observation is out of scope.

| Check | Result | Evidence |
|---|---|---|
| Docker client boundary | PASS | validated exact Docker CLI plus local unix socket/daemon identity fingerprints; inherited auth/build selectors are cleared or pinned; task-owned mode-0600 anonymous config excludes credential helpers and preserves Compose/Buildx plugin identities; user config is unchanged |
| resource ownership preflight | PASS | cryptographically random run marker, both Compose projects, resource names, image tags, and ownership labels were absent before allocation |
| prerequisites | PASS | Docker Engine 29.6.2, Compose 5.3.1, Buildx, curl, git, id, and Python 3 are available through the anonymous client boundary; rehearsal ports are free; host UID 501/GID 20 is non-root |
| migration inventory | PASS | upgrade path is V11 to V15 |
| no-cache build | PASS | PostgreSQL and build base images were pulled; backend/frontend build steps bypassed cache reuse with --no-cache; existing Docker build cache was not deleted |
| image pin | PASS | source and restore are configured with run-scoped backend sha256:a4234ae421fee9d6d21bd50ca9e91c92a956b4ee2f284390381469f56d117f7f, frontend sha256:a0a4e6e97aa1c5043c23972b0369b62460d21ffb81c375272f9751b5dc2e4af5, and PostgreSQL sha256:714313f47e0866d279656a5679c2446693198d3a56127178e2bc550af0a46c77 (postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193) images |
| source database image pin | PASS | source database container uses the captured PostgreSQL image sha256:714313f47e0866d279656a5679c2446693198d3a56127178e2bc550af0a46c77 |
| fresh-volume V11 install | PASS | new source volume reached backend health with Flyway V11 and Hibernate validate |
| Flyway history UPDATE denial | PASS | runtime credential was denied |
| V11 role split | PASS | migration role owns DDL; runtime role passed least-privilege verification |
| V11 API smoke | PASS | anonymous submit/lookup, admin login, staff ticket read, ticket audit (1), and access audit (2) succeeded for ticket #1000 |
| canonical audit UPDATE denial | PASS | runtime credential was denied |
| canonical audit DELETE denial | PASS | runtime credential was denied |
| runtime DDL denial | PASS | runtime credential was denied |
| Flyway upgrade | PASS | same volume advanced V11 to V15 in 6s; Hibernate validate and backend/frontend health passed; pre-upgrade ticket remained readable |
| pg_dump backup | PASS | custom-format no-owner/no-ACL snapshot started 2026-08-12T02:35:02Z; 97874 bytes; sha256 5b64d53245a469431fd97d01e3aebf94fbb3b44ef6918109f2642cd8be56374f; 343ms |
| restore database image pin | PASS | restore database container uses the same captured PostgreSQL image sha256:714313f47e0866d279656a5679c2446693198d3a56127178e2bc550af0a46c77 |
| pg_restore data parity | PASS | fresh restore volume matched counts (tickets=1, ticket-audits=1, access-audits=4, admin-audits=3, projection=9) in 363ms |
| restored Flyway history UPDATE denial | PASS | restored runtime credential was denied |
| post-restore application smoke | PASS | V15 backend/frontend health, public token lookup, restored admin login, staff read, and new access audit passed in 7s |
| RPO boundary | PASS | the pre-backup synthetic ticket and all audited reads through the pg_dump snapshot were recovered; no WAL/PITR claim |
| pre-cleanup source freeze | PASS | operations inputs plus backend/frontend context fingerprints captured before image build still match after restore verification |
| cleanup verification | PASS | source/restore containers, networks, volumes, run-scoped images, and the secret workspace including the anonymous Docker config are absent |
| user Docker config immutability | PASS | the preexisting Docker client config remained byte/metadata-identical; the rehearsal used only its deleted task-owned anonymous config |
| final source freeze | PASS | captured operations inputs and backend/frontend build contexts still match after cleanup and immediately before evidence rendering |

## RPO/RTO interpretation

This rehearsal uses a single logical `pg_dump` snapshot and does not configure WAL archiving or point-in-time recovery. The recovered ticket and audit counts prove recovery through the snapshot only. Operational worst-case RPO is therefore the backup interval plus changes after the dump snapshot begins; it is not zero. The measured recovery-validation duration is a local RTO observation, not an SLA.

## Scope limitation

There is no prior tagged release image. The upgrade proof gives the runtime role default read/append startup privileges, runs the current application against Flyway target V11 with Hibernate validation enabled, creates data, then advances that same volume to the repository latest migration. Full mode pulls base images and bypasses build-cache reuse for its build steps; it does not delete Docker build cache. It is still a same-host rehearsal rather than an independent second-machine certification. Image acquisition intentionally uses an anonymous task-owned Docker client config against the already validated local unix daemon; private-registry credentials and credential helpers are outside this public-image rehearsal. Docker volumes expose no immutable removal ID, so cleanup compares their full captured identity immediately before the name-based Docker API call; a malicious peer with the same Docker-daemon authority can still race that check and is outside this single-principal rehearsal. The split-role proof uses the private rehearsal overlay; base Compose does not create or wire migration/runtime roles, and this repository provides no TLS-enabled production deployment manifest.

## Source provenance

- `2ed4b3f25e7a76febb5482511b9a07e1e76fba45917a41b736716866e8804056 files=8  operations runner/Compose/runtime-role inputs captured before build`
- `710f3628a8721ec98bfd55bcabc04df253a7e9c79de410bbf562cb313e4215c4 files=148  backend Docker build context captured immediately before build (.dockerignore applied)`
- `127017492f88a165b4f78233c4a44d3ff3cad7638555e7d812480fbbcf9decfb files=194  frontend Docker build context captured immediately before build (.dockerignore applied)`
- `dd65faa37a4b3fb9afd11266cac5da41201c8eb6a63ad63be11922bfbc308528  generated ownership overlay (content derived from this runner and the run-scoped marker; removed with the secret workspace)`
- `24d8d5d451de8c3b2fcf21f9ec88720b37bee46211fee85a1cd3ae28a0fff809  generated marker-labeled PostgreSQL wrapper Dockerfile (content derived from this runner; removed with the secret workspace)`
- `2f74185187ceff2f6e9addb7fabcb0d8591c9ebf7fb85242dfe8ff0575124f8d  scripts/run-operations-rehearsal.sh`
- `654fad6833200f506b8e0cc645be68e1143be25b2283dc18eddf81aba3547c1d  compose.yaml`
- `b027c2df90ec20c09c1fb15c1cd372a3d2f3772f9834ec942292b31cabff01e0  compose.e2e.yaml`
- `0b14d9240c34a8d84bdbdc00e0fb13065f1a22e604a2eb6a6c9e65701bc4c9f2  scripts/operations/compose.rehearsal.yaml`
- `fbf4d3f7ecf90e0cde4bce0327774af06573858319c839d54f4cc1842a927eaa  scripts/operations/postgres-init-runtime-role.sh`
- `0a25a2f2a2e9b56c6b6e81ce223a763c73ef3acaa8d3bb5eb10dc9751a944a00  scripts/operations/configure-default-runtime-privileges.sql`
- `09bd3ec48eb147bb30b65a24d503b71144e415e9017927b9dceb79eba906879f  scripts/postgres/configure-runtime-role.sql`
- `115792838492f8bc2b3879586037980cdc4ab3398d2f82c8a50a654d741f1b09  scripts/postgres/verify-runtime-role.sql`
- `d99159fea8a53ae90017d07ee6d464d71d539c174c545aaf198c8f8dd8bd309d  backend/Dockerfile`
- `62f30cbafcb4e6b77c44983e90cc696f9baadc399526cc3ce4ac32261b59b728  frontend/Dockerfile`
- `191b46c07e70349cbeb74f6c9f98ab690cef37a7f04d77ec99b2797b190324a0  backend/src/main/resources/application.yml`
- `25cbbaf89a554a3acd19ac3b8f52f6f8a6c123ee6a9349528aacd34fe0d85f91  backend/src/main/resources/db/migration/V10__security_auditor_role.sql`
- `ef737ee2794e1f728da7a9e6822a13838d0a51ac330cd001e37bf42f1e7a48ac  backend/src/main/resources/db/migration/V11__audit_activity_projection.sql`
- `3b8f8936b955440233cdec36fc4dcd1e95b6f6ee6a84d8ce9a165a7655b34501  backend/src/main/resources/db/migration/V12__audit_explorer_self_audit_and_export_skeleton.sql`
- `82700e6be6cd3676a35927b6c3a99d28f72c862011a8cf7e71431d06ff6c6c0f  backend/src/main/resources/db/migration/V13__sanitize_search_audit_routine_representation.sql`
- `1b7d8691ca440effe042f2d9c8303b4928dfceb09fe7586c7dd9cdd6895b6b37  backend/src/main/resources/db/migration/V14__index_staff_ticket_command_replay.sql`
- `5f38c31bda7822dbc93872af11108eea90c6723391c2806b26316ead973f8380  backend/src/main/resources/db/migration/V15__stabilize_audit_projection_history.sql`
- `a5f57d2a853b353ef10be59ca3384e6f035213e37ce77f29b19c6ed23b06c1cb  backend/src/main/resources/db/migration/V1__initial_request_vertical_slice.sql`
- `2572372e7b1f1f2010a54407dd2ea36d2fe4ea16e8d198e40a272f876019eeba  backend/src/main/resources/db/migration/V2__add_ticket_audit_command_context.sql`
- `9edca542a63f485f41f715c4e301705f7818555edc5627884bc5a1ff8b23c671  backend/src/main/resources/db/migration/V3__enforce_request_access_token_lifecycle.sql`
- `5e95fc1d529a83a9d70c9fce9f0844ed4a22954785b19180a98f44a6e42ac9f0  backend/src/main/resources/db/migration/V4__timestamp_ticket_audit_events.sql`
- `8fda85d76e7d0fd5d6598f73c7c75976608cc008288abff7381e5449164a0d2c  backend/src/main/resources/db/migration/V5__staff_authentication_and_groups.sql`
- `4f53a2a251bfe8463ec90741b6d19a3518cda7546cbdfc437e55ebbe341366b0  backend/src/main/resources/db/migration/V6__agent_ticket_read_and_access_audit.sql`
- `e3397810f9f849f2ba37d6258e781929f20221fd47f142ac250c1d05444fbe29  backend/src/main/resources/db/migration/V7__ticket_command_audit_concurrency.sql`
- `76d9d5271c552481d14d841bcbfa644abea063fdb6b6555cd0daf3ca39187720  backend/src/main/resources/db/migration/V8__ticket_parent_child_relations.sql`
- `e26e3a5c797feee5cc63272ae15d913f112647b670a1f8715a823b65cc7f2014  backend/src/main/resources/db/migration/V9__access_search_audit.sql`
