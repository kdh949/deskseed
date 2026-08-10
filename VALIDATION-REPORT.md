# Validation Report — Deskseed Documentation Seed v0.6

Generated deterministically by `python3 scripts/validate_documentation.py --write`.

## Result

**PASS**

## Validated

- Canonical docs 00–54, tasks 00–25, and ADRs 0001–0034 are present and unique.
- Markdown fenced-code balance and relative Markdown links.
- JSON/YAML parsing and Draft 2020-12 JSON Schema validity.
- OpenAPI 3.1 operation IDs and local `$ref` resolution.
- Requirement, decision, verification-gate, task, document, and ADR identifiers.
- Requirement links from Core OpenAPI.
- Ticket body as first PUBLIC comment and no Ticket.description field.
- Required wireframes, checklists, API catalogs, and schema blueprint.
- No bundled Zendesk screenshots, logos, or other image assets.

## Counts

- Adr Files: 34
- Bundled Image Assets: 0
- Canonical Docs: 55
- Core Api Requirement Links: 34
- Decision Definitions: 46
- E2E Visual Baselines: 3
- Json Files: 8
- Markdown Files: 137
- Openapi Operations: 46
- Openapi Paths: 38
- Requirement Definitions: 75
- Task Briefs: 26
- Verification Gate Definitions: 127
- Yaml Files: 16

## Errors

None.

## Warnings / limitations

None from automated validation.

- This package is a documentation/contract seed. It does not prove that Kotlin/Spring or React code compiles or runs.
- `BLUEPRINT_READY` capabilities require the contract-freeze process before their first production vertical slice.
- Visual similarity guidance is a product-design boundary, not legal advice; independent branding remains mandatory.
- Retention, encryption-key management, MFA, and regulatory periods remain operator decisions until adopted.
