# Security Scan and Remediation Record

## Sealed baseline scan

- Tool: Codex Security plugin 0.1.18, Standard whole-repository scan
- Scan ID: `3b70ac7f-e747-4f10-9b1b-0e71f3e58e56`
- Target revision: `d1f7bfbaea6946992d2bf7403f51c5dbc4948fdb`
- Snapshot digest:
  `codex-security-snapshot/v1:sha256:2b44f54a206a0d00169fbc52343850cb861d8feccafd36be93bd22a21acfed42`
- Started: `2026-08-11T17:51:14Z`
- Sealed: `2026-08-11T18:35:42Z`
- Review: 72 files plus parent threat-map review; seven candidates independently
  validated exactly once
- Result: 5 reportable findings — 3 medium, 2 low; one informational path deferred,
  one informational path suppressed
- Coverage: partial because anonymous durable-write abuse remains deferred behind the
  explicitly unsupported public-deployment boundary

Canonical workbench digests:

- `findings.json`: `7d9c164c60140ad9f6020d66695debc121699208931100307c2df4038cc32b28`
- `coverage.json`: `0bb7ced8f14e328a1dd92dd390c06c489db42be1b18525bb44c4f4b8d973bba6`

The workbench artifact directory is temporary, so this repository record preserves the
stable scan identity, target, counts, dispositions and remediation proof. It does not
contain exploit payloads, credentials or real customer data.

## Post-scan release delta review

The sealed scan above remains the reproducible baseline; it was not relabelled as a scan
of later uncommitted work. A targeted source-to-sink and regression review was repeated on
2026-08-12 after the staff actor-consistency, exact command-replay, and release-evidence
cleanup changes. The reviewed security-sensitive source identities were:

- `78b82efd0ce3097f2bbfe5160a33561d396f98085183f10d15dcd92167adc536`
  — `StaffSessionValidationFilter.kt`;
- `2edbcbebe9b7070f9db6249461c422a539adedc74c63ee23942664eb6210c18d`
  — `frontend/src/api/client.ts`;
- `b993b8a4c91eb3a93813b4340c333049208bf2991abd1f3a04bcd5e89bffc731`
  — `StaffSessionContext.tsx`;
- `c1fd1c85985f4314a61cba9c4c92f5fddf1110438e6586a4e1b029de843a2c07`
  — `StaffTicketCommandReplayStore.kt`;
- `1b7d8691ca440effe042f2d9c8303b4928dfceb09fe7586c7dd9cdd6895b6b37`
  — V14's partial staff-command lookup index; and
- `4990c5fde47dd394d1e8ca85c21b249f407a27d1049fc66f1999f759830f1eea`
  — the performance runner's exact-profile, source-freeze, exact-ID container, and
  anonymous-volume cleanup boundary;
- `2f74185187ceff2f6e9addb7fabcb0d8591c9ebf7fb85242dfe8ff0575124f8d`
  — the operations runner's exact Docker client/daemon, anonymous credential boundary,
  bounded process-group termination, owned-resource cleanup and final source freeze; and
- `16ea0be3c35746eee40d220e7c1ff96cb3a62171a36fa540c2784e3f9928e1a2`
  — the shared real-stack E2E/Compose ownership boundary used by the current wrappers.

The delta review found no remaining Critical or Important defect in the implemented
actor-consistency or comment-replay paths. A realm-local, canonical expected-staff actor
is compared with—but never selects—the authenticated session principal. Mismatch is
rejected before controller entry, last-activity extension, mutation, or audit success;
the real ticket-command regression proves ticket version/priority, comment count,
TicketAudit/Event counts, and `LAST_ACTIVITY_AT` all remain unchanged. One actor and
generation snapshot spans both CSRF acquisition and its unsafe request, preventing a
held request from crossing an A-to-B account transition.

Exact staff update replay is serialized by a transaction advisory lock and matched
against content-free immutable audit metadata. An exact retry returns the original
result; payload, ticket, operation, and ambiguous multi-match reuse return a stable 409
without a second mutation. The browser preserves the same command ID and exact payload
through an ambiguous response, remount, and manual-refresh success or failure, and it
blocks composer mode changes while a command is in flight.

Release-delta verification completed with:

```bash
cd backend
./gradlew clean test                         # 29 suites, 134/134, 0 skipped

cd ../frontend
npm run format:check
npm run lint
npm run typecheck
npm test                                    # 14 files, 150/150
npm run build

PLAYWRIGHT_BROWSER=chromium npm run test:e2e:dev  # 41/41
PLAYWRIGHT_BROWSER=firefox npm run test:e2e:dev   # 41/41
PLAYWRIGHT_BROWSER=webkit npm run test:e2e:dev    # 35/35 + 6/6, fresh processes

cd ..
bash scripts/run-release-performance.sh --scale release

./scripts/run-operations-rehearsal.sh \
  --evidence-file docs/evidence/release/operations/2026-08-12-macos-arm64-full.md
```

The release-scale run applied V1-V15, checked all 34 recorded source hashes at six
freeze checkpoints without a mismatch, measured all five Agent Views plus four Audit
Explorer page reads, the exact O(1) projection-status read and command-replay lookup, and removed and verified absence of
its exact owned container, anonymous data volume, and scratch directory. Host headroom
passed without an override; the separately unmeasured Docker Desktop VM quota boundary
remains recorded rather than silently treated as capacity proof.

The final operations rehearsal independently matched the current runner and all recorded
input/build-context hashes, built backend/frontend without cache reuse, exercised V11→V15,
runtime/Flyway/canonical-ledger denials, checksumed backup and same-image fresh restore,
then verified exact absence of its containers, networks, volumes, images, anonymous Docker
configuration and secret workspace. The current E2E/Compose wrappers likewise use
cryptographic run ownership and preserve preexisting/replacement sentinels; their fault
harness and real customer, Audit Explorer and health runs left zero owner-labelled resources.

## Findings and release disposition

| Finding | Baseline | Release disposition |
|---|---:|---|
| Routine audit stores plaintext-equivalent arbitrary search content (CWE-312) | Medium | **FIXED** — routine rows use only `[PROTECTED]`; V13 scrubs existing canonical/projection rows and prevents content-bearing replacements; keyed fingerprint and policy-gated ciphertext remain separate |
| Baseline auditor automatically receives reveal/export/rebuild authorities (CWE-269) | Medium | OPEN DESIGN RISK — reason/recent-auth/no-store/self-audit and no export artifact reduce impact; explicit persisted grant model is not implemented |
| PUBLIC/INTERNAL drafts outlive staff session without TTL (CWE-922) | Medium | **FIXED** — 12-hour recovery-validity TTL, authenticated-session expiry sweep, active-session owner marker, exact staff-namespace purge on logout/401/account change, stale-writer rejection and stale-401 generation checks are covered by regressions |

| Anonymous email can reuse/mutate an unverified customer actor (CWE-287) | Low | KNOWN LIMITATION — no prior-ticket access; public deployment remains blocked until verified identity |
| Login throttle pair permits identity rotation/shared-proxy contention (CWE-307) | Low | KNOWN LIMITATION — per-pair control exists; layered ingress/account/global admission remains required before public exposure |

### PR #17/#18 post-scan delta

The follow-up review adds a separate fixed-format session-fingerprint key, removes
identifier truncation, uses collision-free cursor filter encoding, captures immutable
actor/group projection snapshots, serializes rebuild with canonical writers, bounds linked
opens, and records reveal-specific denied events. The current clean backend run covers
134/134 tests; frontend covers 150/150 plus typecheck/lint/format/build. V1-V15 release
performance and the V11→V15 full operations rehearsal match the current sources and clean
up their exact owned resources. This delta changes no conclusion about the still-open
auditor-grant/public-deployment limitations.

Deferred informational path: an Internet-exposed anonymous endpoint can amplify one
request into several durable rows. This is real, but the supported release is local or
private-network only and explicitly blocks public exposure until abuse controls exist.

Suppressed informational path: the manual localhost demo printed its newly issued
synthetic ticket capability. No cross-boundary victim impact was established; release
hardening nevertheless removes the stdout token as defense in depth. The script still
uses the capability in a bounded request but does not print it.

## Remediation proof

The CWE-312 remediation changes both new-write and historical-data paths:

- `SearchQueryProtection.protect` emits the constant routine representation
  `[PROTECTED]` for arbitrary input while preserving keyed fingerprint and authenticated
  ciphertext semantics;
- Flyway V13 rewrites existing `search_audit_details` and `audit_activity_projection`
  representations and adds database constraints that reject any value other than
  `[PROTECTED]`;
- migration regressions verify the canonical and projection scrub, constraint rejection,
  fingerprint preservation and ciphertext preservation;
- search-protection regressions cover token-shaped, phone/government-ID-shaped, health,
  multilingual and control-character input without storing content in routine fields.

The CWE-922 remediation binds persistent draft recovery to the active staff marker:

- expired drafts are rejected on read and expired, malformed or future-dated entries are
  swept when that staff session is established;
- logout, server-observed 401 and staff-account change purge only the departing staff's
  exact namespace;
- the owner marker is removed only when it still names this tab's departing account, and
  an API 401 is ignored after a newer account/session generation is established;
- pending/stale writers cannot recreate a draft after the owner marker is cleared or
  changed;
- protected in-memory staff state is cleared even when browser storage removal throws.

Verification commands:

```bash
cd backend
./gradlew test --tests '*SearchQueryProtectionTest' \
  --tests '*AuditActivityProjectionMigrationTest'

cd ../frontend
npx vitest run \
  src/features/ticket-workspace/ticketEditorModel.test.ts \
  src/features/staff-auth/StaffSessionContext.test.tsx
```

The focused remediation suites passed. Final whole-repository gate counts and any
remaining non-security blocker are reported in `../verification-summary.md`; a focused
PASS is not substituted for that final release gate.

## Controls independently upheld

- customer token lookup is ticket-bound and returns only PUBLIC content;
- INTERNAL comments, child relations and staff/audit metadata were not found in customer
  projections;
- required ticket-detail/search and Audit Explorer audit writes fail closed;
- command state, comments and change audit share a transaction;
- canonical ledgers have update/delete rejection triggers; runtime grant evidence is in
  the operations rehearsal;
- routine search audit rows and projections contain no query-derived content; protected
  reveal remains separately authorized, reason-bound and self-audited;
- persistent staff drafts have a 12-hour recovery-validity bound; expired/invalid entries
  are rejected on read and swept at the authenticated staff-session boundary;
- no supported SQL injection, unsafe HTML/XSS sink, SSRF, upload, XXE, unsafe
  deserialization or process-execution path was identified in implemented surfaces;
- development default credentials are distinct from production-required secret inputs.

## Limitations

- Static security review did not execute exploits or an external network scan.
- Browser storage cleanup is best-effort when the browser raises `SecurityError`; protected
  staff state still closes, and a later available session re-runs the namespace sweep, but
  this is not a physical-erasure guarantee for inaccessible origin storage.
- Session generations are scoped to one JavaScript context. A nonstandard same-account
  cross-tab session replacement that changes owner `A` to `A` without first removing the
  marker cannot be distinguished from the existing session by the storage signal; normal
  logout/expiry removes the marker and is covered.
- IDEM-002's duplicate-mutation and different-payload 409 conditions are implemented,
  but a rejected command-ID reuse attempt has no dedicated durable request-ID/security
  event. The original immutable receipt remains attributable; misuse-attempt
  observability is `LIMITED` rather than a full IDEM-002 PASS.
- V13 removes content-bearing routine search representations, but bytes may remain in
  pre-migration backups, retained WAL, and physical storage remnants until their normal
  retention/rotation lifecycle completes. Policy-controlled ciphertext deliberately
  retains an exact query until its configured expiry.
- Database owner/superuser tamper resistance is not solved by application grants/triggers;
  external signed checkpoints are future work.
- Dependency and container advisories are reported separately under
  `../supply-chain/baseline.md`; the container-scan evidence is `LIMITED`, while the actual
  high/critical CVE and SPDX verdict is `UNKNOWN` because the local Scout indexer did not
  complete.
- The repository has no approved public vulnerability-reporting channel yet; do not post
  tokens, customer data or exploit detail in a public issue.
