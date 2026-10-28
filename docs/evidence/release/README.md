# Release Evidence

This directory records the commands, environments and results used to check the portfolio release.

## Layout

- `verification-summary.md`: gate status, command, result and evidence link
- `security/`: threat model, validated findings and local scan outputs
- `performance/`: fixture manifest, raw query plans and measured summaries
- `operations/`: clean install, migration, backup, restore and rollback-rehearsal logs
- `ui/`: automated visual/accessibility results and the manual review record
- `supply-chain/`: dependency, advisory, license and image baseline

## Reproduction

Run these commands from the repository root. The browser suite uses API fixtures; the P1 contract and customer attachment scripts start their own Compose stacks.

```bash
make check
npm --prefix frontend exec playwright install chromium
bash scripts/run-frontend-browser-e2e.sh
bash scripts/run-p1-contract-e2e.sh
bash scripts/run-authenticated-customer-attachment-e2e.sh
bash scripts/run-release-performance.sh
bash scripts/run-operations-rehearsal.sh \
  --evidence-file /tmp/deskseed-operations-full-evidence.md
```

Dependency and advisory scan commands are listed in `supply-chain/baseline.md`. Run them separately from the local checks.

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
- `NOT RUN`: no execution record is available.

Generated evidence is refreshed deliberately, reviewed as a diff, and committed with the
code or documentation that changes its interpretation.
