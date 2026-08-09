# ADR 0024 — Ordered typed automation without arbitrary code

## Status
Accepted

## Context
Triggers and time-based automations must be understandable, auditable, and safe in a self-hosted product. Arbitrary scripts increase RCE, data exfiltration, and operational risk.

## Decision
Use versioned typed condition/action definitions, ordered evaluation, normal ticket commands, provenance, dry-run, idempotency, and loop controls. Network actions create durable webhook intents. Do not execute arbitrary JavaScript, SpEL, SQL, Kotlin, or Python.

## Alternatives
- Embedded script engine: rejected for security and support burden.
- Hard-code every rule: rejected for product usability.

## Consequences
Condition/action registries evolve deliberately; compatibility and simulation tests are mandatory.
