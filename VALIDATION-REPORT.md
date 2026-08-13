# Validation Report — Deskseed Documentation Seed v0.6

Generated deterministically by `python3 scripts/validate_documentation.py --write`.

## Result

**PASS**

## Validated

- Canonical docs 00–55, tasks 00–25, and ADRs 0001–0039 are present and unique.
- Markdown fenced-code balance and relative Markdown links.
- JSON/YAML parsing and Draft 2020-12 JSON Schema validity.
- OpenAPI 3.1 operation IDs, local `$ref` resolution, and FROZEN staff expected-actor/CSRF/error bindings.
- Requirement, decision, verification-gate, task, document, and ADR identifiers.
- Requirement links from Core OpenAPI.
- Ticket body as first PUBLIC comment and no Ticket.description field.
- Required wireframes, checklists, API catalogs, and schema blueprint.
- No bundled Zendesk screenshots, logos, or other image assets.

## Counts

- Adr Files: 39
- Bundled Image Assets: 0
- Canonical Docs: 56
- Core Api Requirement Links: 44
- Decision Definitions: 54
- Dual Use Actor Bound Operations: 1
- E2E Visual Baselines: 12
- Json Files: 9
- Markdown Files: 190
- Openapi Operations: 82
- Openapi Paths: 66
- Requirement Definitions: 79
- Staff Actor Blueprint Operations: 3
- Staff Actor Bound Operations: 57
- Staff Csrf Bound Operations: 35
- Task Briefs: 26
- Verification Gate Definitions: 128
- Yaml Files: 16

## Errors

None.

## Warnings / limitations

None from automated validation.

- This package is a documentation/contract seed. It does not prove that Kotlin/Spring or React code compiles or runs.
- `BLUEPRINT_READY` capabilities require the contract-freeze process before their first production vertical slice.
- Visual similarity guidance is a product-design boundary, not legal advice; independent branding remains mandatory.
- Retention, encryption-key management, MFA, and regulatory periods remain operator decisions until adopted.
