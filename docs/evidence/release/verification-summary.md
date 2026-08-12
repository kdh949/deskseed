# Release Verification Summary

- Branch: `feature/pr17-pr18-review-followup`
- Base revision: `47c8e5065ec3aff84b3506e4a806ac8149e09b58`
- Evidence date: 2026-08-12 (Asia/Seoul)
- Dataset: synthetic only

This file is updated by the release-hardening work. Until the final verification pass,
unexecuted rows remain `NOT RUN` rather than inheriting status from older artifacts.

| Area | Status | Command / evidence |
|---|---|---|
| GitHub PR CI | PASS | PR #22 code-bearing head run `31564637144`: documentation contracts, backend, frontend quality, Chromium browser, Compose ownership/health, anonymous-request real stack and Audit Explorer real stack all passed; no retry/skip or auto-merge. Later evidence-only commits must retain a green documentation contract check before merge |
| Documentation/OpenAPI validator | PASS | `python3 scripts/validate_documentation.py`; 55 canonical docs, 26 briefs, 37 ADRs, 56 operations, 84 visual baselines; 26/26 implemented staff-session operations expose the expected-actor header and actor 400/409 contract |
| Frontend format/lint/type/unit/build | PASS | `npm run format:check && npm run lint && npm run typecheck && npm test && npm run build`; 14 files, 150/150, 0 failed/skipped; JS 136.08 kB gzip, CSS 9.74 kB gzip |
| Backend fresh suite | PASS | `cd backend && ./gradlew clean test`; 29 suites, 134/134, 0 failed/errors/skipped; BUILD SUCCESSFUL in 1m11s |
| Customer real-stack E2E | PASS | current ownership-isolated wrapper, `bash scripts/run-release-e2e.sh`; Core 5/5 in 7.5s including Views/Workspace, real two-session conflict, transfer and child |
| Audit Explorer real-stack E2E | PASS | same current wrapper, isolated second Compose stack; 1/1 list/detail/reveal/self-audit/projection/authorization scenario |
| Compose ownership/health | PASS | ownership fault/config harness preserved preexisting and replacement container/network/volume/image sentinels, `bash scripts/compose-smoke.sh` passed, and all four owner-label resource classes were 0 at `2026-08-12T00:27:16Z` |
| Visual/axe/keyboard automation | LIMITED | final frontend source passed Chromium 41/41 and Firefox 41/41 with 0 retry/skip. A current macOS WebKit launch succeeded but `browserContext.newPage()` timed out before application setup, so the earlier base-source 35/35 + 6/6 result is historical rather than final-source proof; see `ui/automated-and-visual-review.md` |
| Human visual/screen-reader review | NOT RUN | full 84-image diff, VoiceOver/NVDA and human keyboard/zoom/reduced-motion sign-off remain open |
| Strict audit/auth/conflict/idempotency regressions | PASS | included in the 134-test clean backend run: stable 503/non-leak, denied reveal self-audit, encryption-key-independent session origin, immutable projection snapshots/concurrent rebuild, actor mismatch command immutability, mixed-conflict atomicity, exact/concurrent replay and misuse 409 |
| Security baseline | LIMITED | sealed scan plus current delta review; two findings fixed, auditor-grant and public-exposure controls remain risks, and IDEM-002 rejected-attempt observability is limited; see `security/security-scan.md` |
| Release-scale performance | PASS | V1-V15, 100k Customer/1M Ticket/2M Comment, 11 exact plans including O(1) projection status, all five View p95 values 0.362-9.367 ms against the predeclared 50 ms local budget, 34 source hashes/six freeze checkpoints, and PERF-003 PASS; exact cleanup verified |
| Install/upgrade/backup/restore | LIMITED (current-image path PASS) | final isolated full rehearsal completed in 137s: no-cache build, fresh V11 install, V11→V15 in 6s, 97,874-byte `pg_dump` in 343ms, fresh restore parity in 363ms, post-restore application smoke in 7s, role denials, exact cleanup and final source freeze all PASS; no prior tagged binary exists, so formal OPS-001 remains LIMITED |
| Operations gate accounting | LIMITED / NOT MET | OPS-001 is LIMITED because no previous tagged binary exists; OPS-002 passes only for the local synthetic scope; OPS-003 and OPS-005 remain LIMITED; OPS-004 readiness/alerts/central logging is NOT MET and blocks a production-stable claim |
| npm advisory baseline | PASS | `npm audit --omit=dev` and `npm audit`; 0 vulnerabilities on the final lockfile |
| Backend/container/license baseline | LIMITED | Gradle runtime graph and deterministic 265-location license audit PASS; the final no-cache rehearsal pinned backend `sha256:a4234ae4…`, frontend `sha256:a0a4e6e9…` and PostgreSQL `sha256:714313f4…`; Scout evidence is LIMITED and the final container CVE/SPDX verdict is UNKNOWN because its local indexer did not complete |

The first sandboxed `make frontend-check` attempt failed before installing dependencies
with `ENOTFOUND registry.npmjs.org`. Re-running the identical command with network access
completed successfully; this was an environment restriction, not a test retry masking a
flaky result. npm emitted peer-dependency warnings for Garden's transitive Reach packages
declaring React 16/17 while the application uses React 18.3.1. The warning remains a
supply-chain compatibility item until the dependency baseline is complete.

The pre-upgrade production audit reported two high and one moderate vulnerable package
paths (`react-router` and `styled-components` via `postcss`). Exact same-major fixed
versions were available, so `react-router` moved from 7.9.6 to 7.18.2 and
`styled-components` from 6.1.19 to 6.5.2. Both the production-only and all-dependency
audits then reported zero advisories. The final local production build is 135.84 kB gzip
JavaScript and remains below the 200 kB release budget. Bundle growth is recorded as an
observation rather than hidden by changing the budget.
