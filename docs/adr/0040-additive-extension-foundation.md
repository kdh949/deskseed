# ADR 0040: Additive extension foundation for parallel Wave 1 delivery

## Status

Accepted — 2026-08-18

## Context

Wave 1 needs concurrent feature branches to extend the Core OpenAPI contract, frontend routes and workspace slots, rule conditions, actions, query predicates, template variables, and analytics dimensions. Direct edits to a generated contract, a central route file, or growing `when` expressions would create a semantic merge queue and make a feature's rollback depend on unrelated lane changes.

`origin/main` already owns Flyway V35. The external Wave plan's nominal Foundation reservation begins at V35, so this delivery must preserve that applied/additive migration and reserve V36–V39 for Foundation work instead.

## Decision

- `api/core-api-base-v1.yaml` and owned `api/core-api-fragments/*.yaml` are the Core OpenAPI sources. `api/core-api-outline-v1.yaml` is a deterministic compatibility artifact built by `scripts/bundle_core_openapi.py`; feature lanes only edit their reserved fragment.
- Wave delivery used a repository-owned registry to reserve branch, migration, OpenAPI fragment, contribution-root, traceability, and progress ownership while lanes were active.
- Foundation supplies typed, versioned descriptor registries and safe AST contracts. Future conditions/actions are registered as handlers rather than added to a central enum or switch. Unknown/duplicate/incompatible descriptors fail closed.
- Foundation uses V36–V39 only. Wave 1 remains reserved as V40–V79. No applied migration is renamed, edited, or backfilled by this decision.
- Event publication and rendered extension hosts are delivered in the next Foundation stack positions so their individual transaction and UI risks remain independently reviewable and reversible.

## Consequences

- A lane can add a contract fragment and contribution without modifying generated Core OpenAPI or the central app shell.
- During active Wave delivery, bundle and ownership validators were required contract gates.
- The current OpenAPI artifact remains committed for runtime documentation and existing consumers; source/artifact drift fails validation.
- This does not introduce an external broker, cache, search cluster, or plugin runtime.

## Operational lifecycle

The Wave ownership registry and its validator were delivery-time coordination controls. They were retired after the reserved lanes completed. Owned OpenAPI sources, deterministic bundle parity, duplicate path/method/component rejection, additive Flyway history, and requirement traceability remain durable contract gates.

## References

- D-002, D-008, D-010, D-012, D-033, D-035, D-036, D-054
- ADR 0002, ADR 0008, ADR 0010, ADR 0012, ADR 0024, ADR 0025, ADR 0039
- REQ-FND-001, REQ-FND-002; ARCH-001, ARCH-002
