#!/usr/bin/env python3
"""Validate the Deskseed v0.5 documentation seed.

This checks documentation, schemas, API outlines, IDs, local links and package
integrity. It deliberately does not claim that the Kotlin/React product builds.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import unquote, urlparse

import yaml
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]

REQUIRED_TOP = {
    "README.md",
    "IMPLEMENTATION-START-HERE.md",
    "CHANGELOG-v0.5.md",
    "AGENTS.md",
    "CODEX_TASK_TEMPLATE.md",
}
REQUIRED_MACHINE = {
    "api/core-api-outline-v1.yaml",
    "api/platform-api-outline-v1.yaml",
    "api/api-surface-catalog-v0.5.yaml",
    "api/ui-route-catalog-v0.5.yaml",
    "api/audit-activity-event-v1.schema.json",
    "api/integration-event-envelope-v1.schema.json",
    "db/schema-blueprint-v0.5.sql",
}
REQUIRED_WIREFRAMES = {
    "design/wireframes/agent-ticket-workspace.md",
    "design/wireframes/agent-views.md",
    "design/wireframes/customer-portal.md",
    "design/wireframes/admin-audit-integrations.md",
}
REQUIRED_CHECKLISTS = {
    "checklists/feature-readiness.md",
    "checklists/pr-review.md",
    "checklists/release.md",
}
EXPECTED_DOC_NUMBERS = set(range(0, 53))
EXPECTED_TASK_NUMBERS = set(range(0, 20))
EXPECTED_ADR_NUMBERS = set(range(1, 29))
# This is an onboarding brief that precedes the canonical 00-19 delivery
# sequence. It intentionally shares the bootstrap number but is not a release
# task in the contiguous task register.
NON_CANONICAL_TASK_BRIEFS = {"00-bootstrap-documentation-and-repository.md"}
# The repository retains this pre-v0.5 planning draft for historical context.
# The v0.5 Core outline is the canonical contract validated below.
NON_CANONICAL_OPENAPI_DRAFTS = {"api/mvp-target.yaml"}
MANIFEST_ROOT_FILES = {
    "AGENTS.md",
    "CHANGELOG-v0.5.md",
    "CODEX_TASK_TEMPLATE.md",
    "IMPLEMENTATION-START-HERE.md",
    "README.md",
}
MANIFEST_DIRECTORIES = {"api", "checklists", "db", "design", "docs", "scripts", "tasks"}

REQ_DEF_RE = re.compile(r"^\|\s*(REQ-[A-Z]+-[0-9]{3})\s*\|", re.MULTILINE)
DECISION_DEF_RE = re.compile(r"^\|\s*(D-[0-9]{3})\s*\|", re.MULTILINE)
GATE_DEF_RE = re.compile(r"^###\s+([A-Z][A-Z0-9-]*-[0-9]{3})\b", re.MULTILINE)
MARKDOWN_LINK_RE = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".avif"}


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def all_files(pattern: str) -> list[Path]:
    return sorted(ROOT.glob(pattern))


def walk(value: Any) -> Iterable[Any]:
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def resolve_pointer(document: Any, pointer: str) -> Any:
    if not pointer.startswith("#/"):
        raise ValueError("only local JSON pointers are checked")
    node = document
    for raw in pointer[2:].split("/"):
        part = raw.replace("~1", "/").replace("~0", "~")
        node = node[int(part)] if isinstance(node, list) else node[part]
    return node


def openapi_operations(document: dict[str, Any]) -> list[tuple[str, str, str]]:
    operations: list[tuple[str, str, str]] = []
    for route, path_item in (document.get("paths") or {}).items():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            if method.lower() not in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}:
                continue
            if isinstance(operation, dict):
                operations.append((str(route), method.lower(), str(operation.get("operationId", ""))))
    return operations


def numbered_files(directory: Path, digits: int, excluded: set[str] | None = None) -> tuple[list[int], list[str]]:
    numbers: list[int] = []
    invalid: list[str] = []
    excluded = excluded or set()
    for path in sorted(directory.glob("*.md")):
        if path.name in excluded:
            continue
        match = re.match(rf"^(\d{{{digits}}})-", path.name)
        if match:
            numbers.append(int(match.group(1)))
        else:
            invalid.append(path.name)
    return numbers, invalid


def validate_local_links(md_files: list[Path]) -> list[str]:
    errors: list[str] = []
    for path in md_files:
        text = path.read_text(encoding="utf-8")
        for raw_target in MARKDOWN_LINK_RE.findall(text):
            target = raw_target.strip().split()[0].strip("<>")
            parsed = urlparse(target)
            if parsed.scheme or target.startswith("#") or target.startswith("mailto:"):
                continue
            decoded = unquote(parsed.path)
            if not decoded:
                continue
            candidate = (path.parent / decoded).resolve()
            try:
                candidate.relative_to(ROOT.resolve())
            except ValueError:
                errors.append(f"Local link escapes package in {rel(path)}: {target}")
                continue
            if not candidate.exists():
                errors.append(f"Broken local Markdown link in {rel(path)}: {target}")
    return errors


def validate() -> tuple[list[str], list[str], dict[str, int]]:
    errors: list[str] = []
    warnings: list[str] = []
    counts: dict[str, int] = {}

    for required in sorted(REQUIRED_TOP | REQUIRED_MACHINE | REQUIRED_WIREFRAMES | REQUIRED_CHECKLISTS):
        if not (ROOT / required).is_file():
            errors.append(f"Missing required artifact: {required}")

    docs_numbers, docs_invalid = numbered_files(ROOT / "docs", 2)
    task_numbers, task_invalid = numbered_files(ROOT / "tasks", 2, NON_CANONICAL_TASK_BRIEFS)
    adr_numbers, adr_invalid = numbered_files(ROOT / "docs" / "adr", 4)
    if docs_invalid:
        errors.append(f"Unnumbered canonical docs: {', '.join(docs_invalid)}")
    if task_invalid:
        errors.append(f"Unnumbered task briefs: {', '.join(task_invalid)}")
    if adr_invalid:
        errors.append(f"Invalid ADR filenames: {', '.join(adr_invalid)}")
    for label, values in (("document", docs_numbers), ("task", task_numbers), ("ADR", adr_numbers)):
        for number, count in Counter(values).items():
            if count > 1:
                errors.append(f"Duplicate {label} number: {number}")
    if set(docs_numbers) != EXPECTED_DOC_NUMBERS:
        errors.append(f"Canonical docs must be contiguous 00-52; missing={sorted(EXPECTED_DOC_NUMBERS-set(docs_numbers))}, extra={sorted(set(docs_numbers)-EXPECTED_DOC_NUMBERS)}")
    if set(task_numbers) != EXPECTED_TASK_NUMBERS:
        errors.append(f"Task briefs must be contiguous 00-19; missing={sorted(EXPECTED_TASK_NUMBERS-set(task_numbers))}, extra={sorted(set(task_numbers)-EXPECTED_TASK_NUMBERS)}")
    if set(adr_numbers) != EXPECTED_ADR_NUMBERS:
        errors.append(f"ADRs must be contiguous 0001-0028; missing={sorted(EXPECTED_ADR_NUMBERS-set(adr_numbers))}, extra={sorted(set(adr_numbers)-EXPECTED_ADR_NUMBERS)}")
    counts.update(canonical_docs=len(docs_numbers), task_briefs=len(task_numbers), adr_files=len(adr_numbers))

    md_files = all_files("**/*.md")
    counts["markdown_files"] = len(md_files)
    for path in md_files:
        text = path.read_text(encoding="utf-8")
        if text.count("```") % 2:
            errors.append(f"Unbalanced fenced code block: {rel(path)}")
    errors.extend(validate_local_links(md_files))

    json_files = all_files("**/*.json")
    counts["json_files"] = len(json_files)
    for path in json_files:
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"Invalid JSON {rel(path)}: {exc}")
            continue
        if path.name.endswith(".schema.json"):
            try:
                Draft202012Validator.check_schema(document)
            except Exception as exc:
                errors.append(f"Invalid Draft 2020-12 JSON Schema {rel(path)}: {exc}")

    yaml_files = all_files("**/*.yaml") + all_files("**/*.yml")
    counts["yaml_files"] = len(yaml_files)
    all_operation_ids: list[tuple[str, str]] = []
    openapi_path_count = 0
    openapi_operation_count = 0
    for path in yaml_files:
        try:
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"Invalid YAML {rel(path)}: {exc}")
            continue
        if not isinstance(document, dict) or not str(document.get("openapi", "")).startswith("3.1"):
            continue
        if rel(path) in NON_CANONICAL_OPENAPI_DRAFTS:
            continue
        paths = document.get("paths") or {}
        openapi_path_count += len(paths)
        operations = openapi_operations(document)
        openapi_operation_count += len(operations)
        for route, method, operation_id in operations:
            if not operation_id:
                errors.append(f"Missing operationId: {rel(path)} {method.upper()} {route}")
            else:
                all_operation_ids.append((operation_id, rel(path)))
        for node in walk(document):
            if isinstance(node, dict) and isinstance(node.get("$ref"), str) and node["$ref"].startswith("#/"):
                try:
                    resolve_pointer(document, node["$ref"])
                except Exception as exc:
                    errors.append(f"Unresolved local $ref {node['$ref']} in {rel(path)}: {exc}")
    for operation_id, count in Counter(op for op, _ in all_operation_ids).items():
        if count > 1:
            locations = sorted(p for op, p in all_operation_ids if op == operation_id)
            errors.append(f"Duplicate OpenAPI operationId {operation_id}: {', '.join(locations)}")
    counts["openapi_paths"] = openapi_path_count
    counts["openapi_operations"] = openapi_operation_count

    req_path = ROOT / "docs/26-requirement-traceability.md"
    req_text = req_path.read_text(encoding="utf-8")
    req_defs = REQ_DEF_RE.findall(req_text)
    counts["requirement_definitions"] = len(req_defs)
    for item, count in Counter(req_defs).items():
        if count > 1:
            errors.append(f"Duplicate requirement definition: {item}")
    defined_reqs = set(req_defs)
    all_text = "\n".join(path.read_text(encoding="utf-8") for path in md_files)
    referenced_reqs = set(re.findall(r"\bREQ-[A-Z]+-[0-9]{3}\b", all_text))
    for missing in sorted(referenced_reqs - defined_reqs):
        errors.append(f"Requirement referenced but not defined: {missing}")
    if len(defined_reqs) < 60:
        warnings.append("Fewer than 60 requirement definitions found; re-check coverage")

    decision_path = ROOT / "docs/25-implementation-decision-register.md"
    decision_defs = DECISION_DEF_RE.findall(decision_path.read_text(encoding="utf-8"))
    counts["decision_definitions"] = len(decision_defs)
    for item, count in Counter(decision_defs).items():
        if count > 1:
            errors.append(f"Duplicate decision definition: {item}")

    gate_path = ROOT / "docs/21-minimum-verification-gates.md"
    gate_defs = GATE_DEF_RE.findall(gate_path.read_text(encoding="utf-8"))
    counts["verification_gate_definitions"] = len(gate_defs)
    for item, count in Counter(gate_defs).items():
        if count > 1:
            errors.append(f"Duplicate verification gate definition: {item}")
    defined_gates = set(gate_defs)
    gate_prefixes = {item.rsplit("-", 1)[0] for item in defined_gates}
    candidate_ids = set(re.findall(r"\b[A-Z][A-Z0-9-]*-[0-9]{3}\b", all_text))
    referenced_gates = {item for item in candidate_ids if item.rsplit("-", 1)[0] in gate_prefixes}
    for missing in sorted(referenced_gates - defined_gates):
        errors.append(f"Verification gate referenced but not defined: {missing}")
    # IDs explicitly formatted as code are intended as machine-checkable references.
    backticked_ids = set(re.findall(r"`([A-Z][A-Z0-9-]*-[0-9]{3})`", all_text))
    for item in sorted(backticked_ids):
        if item.startswith("REQ-"):
            if item not in defined_reqs:
                errors.append(f"Backticked requirement is not defined: {item}")
        elif item.startswith("D-"):
            if item not in set(decision_defs):
                errors.append(f"Backticked decision is not defined: {item}")
        elif item not in defined_gates:
            errors.append(f"Backticked verification ID is not defined: {item}")

    core_api = yaml.safe_load((ROOT / "api/core-api-outline-v1.yaml").read_text(encoding="utf-8"))
    api_req_refs: set[str] = set()
    for node in walk(core_api):
        if isinstance(node, dict) and isinstance(node.get("x-deskseed-requirements"), list):
            api_req_refs.update(str(item) for item in node["x-deskseed-requirements"])
    for missing in sorted(api_req_refs - defined_reqs):
        errors.append(f"Core OpenAPI references undefined requirement: {missing}")
    counts["core_api_requirement_links"] = len(api_req_refs)

    schema_doc = (ROOT / "docs/32-database-schema-and-index-blueprint.md").read_text(encoding="utf-8")
    ticket_match = re.search(r"### tickets\n(?P<body>.*?)(?:\n### |\n## 4\.)", schema_doc, re.DOTALL)
    if not ticket_match:
        errors.append("Could not locate tickets schema section")
    else:
        body = ticket_match.group("body")
        fields = body.split("의도적 부재:", 1)[0]
        if re.search(r"^description\s*$", fields, re.MULTILINE | re.IGNORECASE):
            errors.append("Ticket field list contains forbidden description field")
        if "description" not in body or "parent_id" not in body:
            errors.append("Ticket schema does not explicitly document intentional absence of description and parent_id")
    sql_text = (ROOT / "db/schema-blueprint-v0.5.sql").read_text(encoding="utf-8")
    if "tickets have no description column" not in sql_text:
        errors.append("SQL blueprint lacks the no-description invariant marker")
    if "first PUBLIC comment" not in all_text and "첫 PUBLIC Comment" not in all_text and "첫 `PUBLIC` comment" not in all_text:
        errors.append("First-public-comment invariant marker not found")

    statuses = set(re.findall(r"\|\s*(IMPLEMENTATION_READY|BLUEPRINT_READY|PROVISIONAL|DEFERRED)\s*\|", req_text))
    expected_statuses = {"IMPLEMENTATION_READY", "BLUEPRINT_READY", "PROVISIONAL", "DEFERRED"}
    if statuses != expected_statuses:
        errors.append(f"Requirement status vocabulary mismatch: {sorted(statuses)}")

    image_assets = [path for path in ROOT.rglob("*") if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS]
    counts["bundled_image_assets"] = len(image_assets)
    if image_assets:
        errors.append("Documentation seed must not bundle copied Zendesk visual assets: " + ", ".join(rel(p) for p in image_assets))

    validation_heading = (ROOT / "VALIDATION-REPORT.md").read_text(encoding="utf-8").splitlines()[0] if (ROOT / "VALIDATION-REPORT.md").exists() else ""
    if validation_heading and "v0.5" not in validation_heading:
        warnings.append("Existing validation report is stale; run with --write")

    return errors, warnings, counts


def write_manifest() -> None:
    lines: list[str] = []
    paths = [ROOT / name for name in MANIFEST_ROOT_FILES]
    paths.extend(
        path
        for directory in MANIFEST_DIRECTORIES
        for path in (ROOT / directory).rglob("*")
        if path.is_file() and not any(part.startswith(".") for part in path.relative_to(ROOT).parts)
    )
    for path in sorted(paths):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {path.stat().st_size:10d}  {rel(path)}")
    (ROOT / "FILE-MANIFEST.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_report(errors: list[str], warnings: list[str], counts: dict[str, int]) -> None:
    result = "PASS" if not errors else "FAIL"
    lines = [
        "# Validation Report — Deskseed Documentation Seed v0.5",
        "",
        f"Generated: {datetime.now(timezone.utc).isoformat()}",
        "",
        "## Result",
        "",
        f"**{result}**",
        "",
        "## Validated",
        "",
        "- Canonical docs 00–52, tasks 00–19, and ADRs 0001–0028 are present and unique.",
        "- Markdown fenced-code balance and relative Markdown links.",
        "- JSON/YAML parsing and Draft 2020-12 JSON Schema validity.",
        "- OpenAPI 3.1 operation IDs and local `$ref` resolution.",
        "- Requirement, decision, verification-gate, task, document, and ADR identifiers.",
        "- Requirement links from Core OpenAPI.",
        "- Ticket body as first PUBLIC comment and no Ticket.description field.",
        "- Required wireframes, checklists, API catalogs, and schema blueprint.",
        "- No bundled Zendesk screenshots, logos, or other image assets.",
        "",
        "## Counts",
        "",
    ]
    for key in sorted(counts):
        lines.append(f"- {key.replace('_', ' ').title()}: {counts[key]}")
    lines += ["", "## Errors", ""]
    lines += [f"- {item}" for item in errors] or ["None."]
    lines += ["", "## Warnings / limitations", ""]
    lines += [f"- {item}" for item in warnings] or ["None from automated validation."]
    lines += [
        "",
        "- This package is a documentation/contract seed. It does not prove that Kotlin/Spring or React code compiles or runs.",
        "- `BLUEPRINT_READY` capabilities require the contract-freeze process before their first production vertical slice.",
        "- Visual similarity guidance is a product-design boundary, not legal advice; independent branding remains mandatory.",
        "- Retention, encryption-key management, MFA, and regulatory periods remain operator decisions until adopted.",
    ]
    (ROOT / "VALIDATION-REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="write validation report and file manifest")
    args = parser.parse_args()
    errors, warnings, counts = validate()
    if args.write:
        write_manifest()
        # A stale-report warning is fixed by this same invocation; omit it from the new report.
        warnings = [item for item in warnings if "validation report is stale" not in item]
        write_report(errors, warnings, counts)
    for item in errors:
        print(f"ERROR: {item}", file=sys.stderr)
    for item in warnings:
        print(f"WARN: {item}", file=sys.stderr)
    print(json.dumps({"result": "PASS" if not errors else "FAIL", "counts": counts}, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
