# Contributing

## Before coding

- Identify the current roadmap milestone.
- Read the PRD, domain model, architecture, and relevant ADRs.
- Write or update acceptance criteria before broad implementation.

## Pull request completion checklist

- [ ] Scope belongs to the current milestone.
- [ ] Domain terminology is consistent with `docs/02-domain-model.md`.
- [ ] API and OpenAPI changed together.
- [ ] Flyway migration is forward-only and reviewed.
- [ ] Visibility and authorization are enforced server-side.
- [ ] Audit semantics are covered.
- [ ] Concurrency behavior is explicit.
- [ ] Domain/module/API tests were added.
- [ ] `ApplicationModules.verify()` passes.
- [ ] Documentation or ADR was updated when a decision changed.
- [ ] The author can explain AI-generated code and rejected alternatives.
