# PR Review Checklist

Current local review status is backed by
[`docs/evidence/release/verification-summary.md`](../docs/evidence/release/verification-summary.md).
Hosted PR checks and human approval remain separate.

- [x] Linked requirement and gate IDs
- [x] No Ticket.description
- [x] Customer projection cannot expose internal data
- [x] Assignment/group invariant preserved
- [x] Transfer and child semantics not mixed
- [x] Mutation and canonical audit atomic
- [x] Sensitive read audit policy applied
- [x] Permission checked server-side
- [x] Problem Details contract preserved
- [ ] Migration tested from previous release
- [x] UI loading/empty/error/conflict states
- [x] Automated keyboard/accessibility/visual tests
- [x] No secret/token/body in logs
- [x] Docs and decision register updated
