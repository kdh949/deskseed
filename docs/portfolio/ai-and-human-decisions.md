# AI Assistance and Human Decisions

Deskseed uses AI development agents as implementation and review tools. They are not the
product owner, security risk owner, legal counsel or release approver. This record makes
that boundary inspectable rather than presenting generated work as an unaudited result.

## How AI was used in this release

- inventory existing code, contracts, tests and release gates
- propose a bounded hardening plan and identify documentation drift
- write regression tests before behavior fixes where applicable
- compose reproducible performance and operations scripts
- run an independent read-only security scan and separate validation pass
- update architecture, runbook and evidence documentation
- execute local build, test, browser, Docker and database checks

AI output was accepted only when it was supported by source review and a named validation
command. “The agent said it passed” is not release evidence.

## Human-owned decisions

| Decision | Current release choice | Why it remains human-owned |
|---|---|---|
| Public deployment | Not approved | Anonymous email ownership and abuse controls are incomplete; exposure and risk appetite belong to the operator |
| Repository license | Not selected | Copyright and redistribution terms require an owner decision; absence of a license is disclosed, not guessed |
| Security risk acceptance | No silent acceptance | Remaining findings need an accountable owner, remediation target and deployment boundary |
| Production secret/KMS provider | Not selected | The repository validates required secret inputs but does not choose an operator's trust provider |
| Backup RPO/RTO | Rehearsal measurement only | Actual targets depend on data value, storage and staffing |
| Visual acceptance | Human review required | Screenshot equality and axe cannot judge all information hierarchy, motion or screen-reader quality |
| Release/merge | Normal PR, no auto merge | A reviewer must compare evidence, risks and claims before merge |

## Release hardening decisions

| Decision | Evidence or consequence |
|---|---|
| Keep PostgreSQL and the modular monolith | No Kafka, Redis, Elasticsearch or Kubernetes was added; release-scale query plans decide whether future change is justified |
| Keep strict audit failure semantics | Sensitive reads return a stable 503 without protected data if required audit persistence fails |
| Treat export as a skeleton | Request/self-audit is tested; artifact creation/download/expiry/deletion is explicitly not claimed |
| Separate migration and runtime DB authority | Production config already separates credentials; the release adds reproducible grants and canonical-ledger privilege verification |
| Fix advisories instead of accepting a stale baseline | Same-major frontend upgrades removed the measured npm high/moderate advisory paths and were followed by clean tests/build |
| Reuse immutable audit metadata for Core command replay | It avoids a second receipt store and raw-payload retention; exact retries replay, while mismatched key reuse fails closed. A dedicated rejected-attempt security event remains a documented observability limit |
| Add expected-actor consistency without changing authentication | A realm-local hint can stop stale cross-tab intent, but the server session remains the only actor authority and the header never selects a principal |
| Preserve implementation truth in public docs | Current routes and flows are separated from accepted blueprints and planned post-MVP work |

## Review protocol

Before merge, the human reviewer should:

1. inspect every `FAIL`, `LIMITED`, `N/A` and `NOT RUN` row in the release evidence;
2. review security findings and confirm that fixed findings have regression evidence;
3. review the 1280/1440/1920 visual diff and keyboard/screen-reader notes;
4. confirm the license/public-distribution decision;
5. reproduce at least the quick start and one restore smoke on a clean machine;
6. confirm that README feature claims match the actual routes, migrations and tests;
7. merge manually only after required CI checks and review are complete.

## Traceability

- Release brief: [`tasks/briefs/release-hardening.md`](../../tasks/briefs/release-hardening.md)
- Evidence index: [`docs/evidence/release/README.md`](../evidence/release/README.md)
- Verification summary: [`docs/evidence/release/verification-summary.md`](../evidence/release/verification-summary.md)
- Decision register: [`docs/25-implementation-decision-register.md`](../25-implementation-decision-register.md)
- ADR index: [`docs/portfolio/adr-index.md`](adr-index.md)
