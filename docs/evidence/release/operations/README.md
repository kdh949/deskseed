# Operations evidence

## Reproduction

Full fresh-build rehearsal:

```bash
./scripts/run-operations-rehearsal.sh \
  --evidence-file /tmp/deskseed-operations-full-evidence.md
```

Default full mode pulls public base images through a task-owned anonymous Docker client configuration and passes `--no-cache` to the build; this bypasses cache reuse for those build steps but does not delete existing Docker build cache. The runner pins the exact local Unix daemon, Docker CLI and plugins, applies bounded pull/build timeouts to the whole command process group, and refuses inherited registry-auth, remote-builder and telemetry overrides. The command above intentionally selects an evidence path outside the repository; if `--evidence-file` is omitted, no durable evidence file is published. The runner attempts exact owned-resource cleanup, verifies the run projects' containers/networks/volumes, run-scoped images, anonymous client configuration and secret directory are absent, records that result, and only then writes the requested evidence. Review the result for accidental sensitive data before adding it here.

- [`2026-08-12-macos-arm64-full.md`](2026-08-12-macos-arm64-full.md) — current full V11→V14 proof. In 139s it performed anonymous pulls, no-cache backend/frontend builds, fresh V11 install, split-role and immutable-ledger denials, V11→V14 upgrade, checksumed backup, same-PostgreSQL-image fresh restore, parity/application smoke, final source-freeze checks and exact cleanup. All 25 recorded checks passed.
- [`2026-08-12-macos-arm64-cleanup-smoke.md`](2026-08-12-macos-arm64-cleanup-smoke.md) — superseded V11→V13 development smoke retained only as historical cleanup/restore diagnostics. It is not the current release gate.

Raw database dumps, cookie jars, generated credentials, access tokens, and container logs are not release artifacts and must not be committed. The script's `EXIT` trap attempts to remove them and run-scoped images; a removal or absence-check failure makes the run and evidence fail.

Credential-bearing files are confined to a mode-0700 temporary directory and secret values are not written to evidence/stdout. Generated values still cross process environment/argument boundaries while Compose and API probes run, so this local rehearsal does not prove isolation from privileged observation on the same host. Daemon-side registry mirrors, proxy configuration and authentication also remain outside the runner's anonymous client boundary.

The split migration/runtime role proof is confined to this script and its private `scripts/operations/compose.rehearsal.yaml` overlay. Base `compose.yaml`/`.env` do not create or wire `DATABASE_MIGRATION_*` and `DATABASE_RUNTIME_*`, and this repository provides no TLS-enabled production deployment manifest. These artifacts prove a local isolated rehearsal, not supported production self-hosting.
