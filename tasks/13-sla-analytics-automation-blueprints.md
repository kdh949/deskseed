# Codex Brief 13 — Promote a Post-MVP Blueprint

## Goal

Convert exactly one capability from `BLUEPRINT_READY` to `IMPLEMENTATION_READY` before writing product code.

## Required sources

- requirement row in `docs/26-requirement-traceability.md`
- relevant detailed spec: `docs/44`, `45`, or `46`
- `docs/39-api-contract-freeze-plan.md`
- `docs/50-codex-implementation-runbook.md`

## Output

- scoped PRD and user scenario.
- state/permission/failure semantics.
- Flyway migration draft.
- OpenAPI/UI contract.
- metric or event glossary entry.
- verification gates and performance hypothesis.
- explicit non-goals.

## Rule

Do not implement SLA, analytics, and automation together. The first recommended slice is First Reply SLA only (`tasks/16`).
