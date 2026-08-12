# Release Evidence

This directory contains sanitized, reviewable evidence for the portfolio release. It is
part of the release contract: a PASS without a command, environment and durable output is
not a release claim.

## Layout

- `verification-summary.md`: gate status, command, result and evidence link
- `security/`: threat model, validated findings and local scan outputs
- `performance/`: fixture manifest, raw query plans and measured summaries
- `operations/`: clean install, migration, backup, restore and rollback-rehearsal logs
- `ui/`: automated visual/accessibility results and the manual review record
- `supply-chain/`: dependency, advisory, license and image baseline

## Reproduction

Run the commands from the repository root on the commit recorded in each artifact.

```bash
make check
bash scripts/run-release-e2e.sh
bash scripts/run-release-performance.sh
bash scripts/run-operations-rehearsal.sh \
  --evidence-file /tmp/deskseed-operations-full-evidence.md
```

Supply-chain checks depend on registry/advisory services and are intentionally listed as
separate commands in `supply-chain/baseline.md`, so an unavailable external scan cannot
make the local checks pass by implication.

The scripts may create large, ignored working data under `build/release-evidence/` and
write only the bounded, sanitized evidence intended for review into this directory. The
committed evidence must never contain passwords, access tokens, `Authorization` headers,
session cookies, raw comment bodies, raw search queries, or private customer data.

## Result vocabulary

- `PASS`: the documented command ran successfully and the artifact supports the claim.
- `FAIL`: the command or acceptance criterion failed; it is a release blocker unless an
  owner explicitly accepts the risk.
- `LIMITED`: an in-scope check ran with a documented environmental limitation.
- `N/A`: the surface is not implemented in this release and no completion claim is made.
- `NOT RUN`: no evidence exists yet. This is never equivalent to PASS.

Generated evidence is refreshed deliberately, reviewed as a diff, and committed with the
code or documentation that changes its interpretation.
