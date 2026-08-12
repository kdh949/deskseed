# Stable Release Checklist

Current portfolio-RC status is backed by
[`docs/evidence/release/verification-summary.md`](../docs/evidence/release/verification-summary.md).
An unchecked item is a release blocker or owner gate, not an implicit waiver.

- [ ] Full CI green
- [x] Critical Playwright flows green
- [ ] Visual diffs reviewed
- [ ] Accessibility checks and manual keyboard pass
- [x] OpenAPI diff classified
- [x] Empty and current-image V11→V15 migrations tested
- [x] Backup and restore rehearsal
- [ ] Production readiness, alert/dashboard and central logging gate
- [x] Security threat-model delta
- [x] Dependency/license report
- [x] Performance baseline comparison
- [x] Audit integrity and fail-closed tests
- [x] Changelog and known limitations
- [x] Demo uses synthetic data
