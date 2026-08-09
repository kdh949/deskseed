# Coverage Assessment: v0.3 → v0.5

## Conclusion

v0.3 preserved the product direction and was strong enough to begin selected backend slices, especially audit and integration foundations. It was not sufficient to implement every discussed capability consistently end to end.

v0.5 closes the documentation gaps and removes duplicate competing drafts. Core MVP has implementation-ready contracts; post-MVP capabilities have detailed blueprints and an explicit contract-freeze process.

## v0.3 strengths

- Kotlin/Spring modular monolith direction.
- first comment instead of ticket description.
- transfer vs child-ticket semantics.
- public/internal projection boundary.
- ticket/access/security audit direction.
- Platform API, IntegrationClient, external references, webhook and SDK direction.
- high-level SLA/analytics/automation plan.

## Gaps that prevented full implementation

1. No single requirement-to-document-to-test trace.
2. Frontend screens and interactions were not detailed enough for consistent implementation.
3. “Zendesk-like” lacked a safe Garden/brand/trademark boundary.
4. Core Customer/Agent/Admin/Audit API was not represented in one OpenAPI outline.
5. DB/index/permission/state/command/event rules were distributed or conceptual.
6. SLA business-time and policy-version algorithms were not implementation-level.
7. Trigger ordering, loop prevention, scheduled automation, and provenance needed a detailed specification.
8. Explore-like datasets, backlog snapshots, drill-down permission, and metric governance needed a detailed specification.
9. Views/tags/custom fields/macros/search, attachments/rich text/redaction, and email/channel adapters needed explicit staged contracts.
10. Codex had no complete small-PR release train and human review loop.
11. Self-hosted upgrade, backup/restore, accessibility, visual regression, and release gates needed consolidation.

## v0.5 remediation

| Gap | v0.5 source |
|---|---|
| Requirement loss | `docs/26-requirement-traceability.md` |
| Implementation method | `docs/27`, `docs/50`, `tasks/` |
| Frontend IA/design/screens/state | `docs/28~31`, `docs/40`, `docs/51`, `design/wireframes/` |
| Core API | `api/core-api-outline-v1.yaml`, `docs/39` |
| Schema/permission/commands | `docs/32~34`, `db/` |
| SLA/OLA | `docs/44` |
| Trigger/automation | `docs/45` |
| Analytics/Explore/export | `docs/16`, `docs/46` |
| Ticketing breadth/search | `docs/47` |
| Files/content | `docs/48` |
| Email/channels/notifications | `docs/49` |
| Admin settings | `docs/52` |
| Self-hosted operations | `docs/36` |
| Test/release | `docs/21`, `docs/35`, `docs/40`, `checklists/` |

## Remaining intentional decisions

The following remain provisional because product intent alone cannot finalize them.

- legal retention periods and employee-monitoring policy.
- whether encrypted raw search queries are retained.
- production IdP, SSO and MFA policy.
- Platform API network exposure.
- final open-source license and product name.
- exact customer reopen window.
- mail/object-storage providers.
- measured thresholds for Kafka, Elasticsearch/OpenSearch, Redis, or Kubernetes.

These are visible in the decision register/settings catalog rather than silently omitted.
