#!/usr/bin/env python3
"""Validate Deskseed documentation and machine-readable contracts.

This read-only validator checks semantic contract integrity. It deliberately does
not generate inventory artifacts or claim that the Kotlin/React product builds.
"""
from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import unquote, urlparse

import yaml
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]

REQUIRED_MACHINE = {
    "api/core-api-outline-v1.yaml",
    "api/customer-identity-api-v1.yaml",
    "api/platform-api-outline-v1.yaml",
    "api/api-surface-catalog-v0.6.yaml",
    "api/ui-route-catalog-v0.6.yaml",
    "api/audit-activity-event-v1.schema.json",
    "api/integration-event-envelope-v1.schema.json",
    "db/schema-blueprint-v0.6.sql",
}
# The repository retains this pre-v0.6 planning draft for historical context.
# The v0.6 Core outline is the canonical contract validated below.
NON_CANONICAL_OPENAPI_DRAFTS = {"api/mvp-target.yaml"}
# The base document is an input to the deterministic Core bundle. It is validated
# through the committed compatibility artifact so its operations are not counted a
# second time by repository-wide uniqueness checks.
OPENAPI_COMPOSITION_SOURCES = {"api/core-api-base-v1.yaml"}
REFERENCE_OPENAPI_CONTRACTS = {
    "api/core-api-outline-v1.yaml",
    "api/customer-identity-api-v1.yaml",
    "api/platform-api-outline-v1.yaml",
}
HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
MANUAL_DOCUMENTATION_MARKER = "MANUAL"
AUTOMATED_DESCRIPTION_PATTERNS = (
    re.compile(r"^.+ 요청 또는 응답 모델입니다\.$"),
    re.compile(r"^.+ 값입니다\.$"),
    re.compile(r"^.+ 식별자입니다\.$"),
    re.compile(r"^.+에 사용하는 입력 또는 표시 문자열입니다\.$"),
    re.compile(r"^.+의 허용된 열거 값입니다\.$"),
    re.compile(r"^(?:경로|조회 조건|HTTP 헤더|쿠키)로 전달하는 .+ 값입니다\.$"),
    re.compile(r"^UTC 기준 ISO 8601 시각입니다\.$"),
    re.compile(r"^낙관적 동시성 제어에 사용하는 버전입니다\.$"),
    re.compile(r"^서버가 발급한 불투명 페이지 커서입니다\. 값을 해석하거나 변경하지 않습니다\.$"),
    re.compile(r"^민감한 인증 값입니다\. 예시는 사용할 수 없는 합성 값이며 로그나 감사 기록에 저장하면 안 됩니다\.$"),
    re.compile(r"^(?:요청이 정상적으로 처리되었습니다|요청 형식이나 입력값이 유효하지 않습니다|인증되지 않은 요청입니다|인증된 주체에게 필요한 권한이 없습니다|조회할 수 없거나 노출하면 안 되는 자원입니다|동시성, 상태 또는 멱등성 충돌이 발생했습니다|요청 제한을 초과했으며 Retry-After 정책을 따라야 합니다|서버가 안전하게 요청을 완료할 수 없었습니다|요청 처리 결과입니다)\.$"),
)
PLACEHOLDER_EXAMPLE_VALUES = {"예시 값"}
REQ_DEF_RE = re.compile(r"^\|\s*(REQ-[A-Z]+-[0-9]{3})\s*\|", re.MULTILINE)
DECISION_DEF_RE = re.compile(r"^\|\s*(D-[0-9]{3})\s*\|", re.MULTILINE)
GATE_DEF_RE = re.compile(r"^###\s+([A-Z][A-Z0-9-]*-[0-9]{3})\b", re.MULTILINE)
MARKDOWN_LINK_RE = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".avif"}
GENERATED_DIRECTORY_NAMES = {
    ".git",
    ".gradle",
    ".gradle-user-home",
    "__pycache__",
    "build",
    "dist",
    "node_modules",
    "playwright-report",
    "test-results",
    # "artifacts",
}
GENERATED_PATH_PREFIXES = {Path("backend/bin")}
E2E_VISUAL_BASELINE_DIRECTORY = ROOT / "frontend/e2e/__screenshots__"
APPROVED_DESKSEED_ASSET_DIRECTORIES = (
    ROOT / "frontend/apps/customer-portal/src/assets/deskseed",
    ROOT / "frontend/apps/staff-console/src/assets/deskseed",
)


class ComposeYamlLoader(yaml.SafeLoader):
    """Safe YAML loader for Docker Compose's collection override tags."""


def _construct_compose_override(loader: ComposeYamlLoader, node: yaml.Node) -> Any:
    if isinstance(node, yaml.SequenceNode):
        return loader.construct_sequence(node)
    if isinstance(node, yaml.MappingNode):
        return loader.construct_mapping(node)
    return loader.construct_scalar(node)


for compose_tag in ("!override", "!reset"):
    ComposeYamlLoader.add_constructor(compose_tag, _construct_compose_override)


def load_yaml_document(path: Path) -> Any:
    loader = ComposeYamlLoader if path.name.startswith("compose") else yaml.SafeLoader
    return yaml.load(path.read_text(encoding="utf-8"), Loader=loader)


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def is_generated(path: Path) -> bool:
    relative = path.relative_to(ROOT)
    return any(part in GENERATED_DIRECTORY_NAMES for part in relative.parts) or any(
        relative == prefix or relative.is_relative_to(prefix) for prefix in GENERATED_PATH_PREFIXES
    )


def all_files(pattern: str) -> list[Path]:
    return sorted(path for path in ROOT.glob(pattern) if path.is_file() and not is_generated(path))


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
            if method.lower() not in HTTP_METHODS:
                continue
            if isinstance(operation, dict):
                operations.append((str(route), method.lower(), str(operation.get("operationId", ""))))
    return operations


def is_automated_description(value: str) -> bool:
    """Reject prose that can be inferred from a field name or primitive type alone."""
    normalized = " ".join(value.split())
    return any(pattern.fullmatch(normalized) for pattern in AUTOMATED_DESCRIPTION_PATTERNS)


def contains_placeholder_example(value: Any) -> bool:
    if isinstance(value, str):
        return value.strip() in PLACEHOLDER_EXAMPLE_VALUES
    if isinstance(value, dict):
        return any(contains_placeholder_example(child) for child in value.values())
    if isinstance(value, list):
        return any(contains_placeholder_example(child) for child in value)
    return False


def implemented_request_schema_names(location: str, document: dict[str, Any]) -> tuple[set[str], list[str]]:
    """Return named request schemas and operations using an inline input schema.

    Core and Customer Identity only freeze selected operations. Platform is a
    current implementation contract, so all of its request bodies are covered.
    """
    names: set[str] = set()
    inline_schema_operations: list[str] = []
    is_platform_contract = location == "api/platform-api-outline-v1.yaml"
    for route, path_item in (document.get("paths") or {}).items():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            if method.lower() not in HTTP_METHODS or not isinstance(operation, dict):
                continue
            if not is_platform_contract and operation.get("x-deskseed-contract-status") != "FROZEN":
                continue
            request_body = operation.get("requestBody") or {}
            content = request_body.get("content") if isinstance(request_body, dict) else None
            if not isinstance(content, dict):
                continue
            for media_type in content.values():
                schema = media_type.get("schema") if isinstance(media_type, dict) else None
                if not isinstance(schema, dict):
                    continue
                reference = schema.get("$ref")
                if isinstance(reference, str) and reference.startswith("#/components/schemas/"):
                    names.add(reference.rsplit("/", 1)[-1])
                else:
                    inline_schema_operations.append(f"{method.upper()} {route}")
    return names, inline_schema_operations


def validate_api_reference_quality(path: Path, document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    location = rel(path)

    def korean(value: Any) -> bool:
        return isinstance(value, str) and bool(re.search(r"[가-힣]", value))

    if document.get("x-deskseed-documentation-ownership") != MANUAL_DOCUMENTATION_MARKER:
        errors.append(f"API reference documentation must be manually owned: {location}")
    if not korean((document.get("info") or {}).get("description")):
        errors.append(f"API reference info description must be Korean: {location}")
    for tag in document.get("tags") or []:
        if isinstance(tag, dict) and not korean(tag.get("description")):
            errors.append(f"API reference tag description must be Korean: {location} {tag.get('name')}")
    for node in walk(document):
        if (
            isinstance(node, dict)
            and isinstance(node.get("description"), str)
            and not korean(node.get("description"))
        ):
            errors.append(f"API reference contains a non-Korean description: {location}")
            break

    for route, path_item in (document.get("paths") or {}).items():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            if method.lower() not in HTTP_METHODS:
                continue
            if not isinstance(operation, dict):
                continue
            operation_label = f"{method.upper()} {route}"
            if not korean(operation.get("summary")):
                errors.append(f"API reference summary must be Korean: {location} {operation_label}")
            if not korean(operation.get("description")):
                errors.append(f"API reference description must be Korean: {location} {operation_label}")

    schemas = ((document.get("components") or {}).get("schemas") or {})
    for node in walk(document):
        if not isinstance(node, dict) or not isinstance(node.get("description"), str):
            continue
        if is_automated_description(node["description"]):
            errors.append(
                f"API reference contains an inferred boilerplate description: {location}"
            )
            break

    request_schema_names, inline_schema_operations = implemented_request_schema_names(location, document)
    for operation_label in inline_schema_operations:
        errors.append(
            f"API reference implementation request must use a named schema for manual review: "
            f"{location} {operation_label}"
        )

    for schema_name in sorted(request_schema_names):
        schema = schemas.get(schema_name)
        if not isinstance(schema, dict):
            errors.append(f"API reference request schema is missing: {location} {schema_name}")
            continue
        if schema.get("x-deskseed-documentation-review") != MANUAL_DOCUMENTATION_MARKER:
            errors.append(
                f"API reference request schema must be manually reviewed: {location} {schema_name}"
            )
        if not korean(schema.get("description")) or is_automated_description(str(schema.get("description", ""))):
            errors.append(
                f"API reference request schema needs a Korean, domain-specific description: "
                f"{location} {schema_name}"
            )
        example = schema.get("example")
        if not isinstance(example, dict):
            errors.append(f"API reference request schema needs an object example: {location} {schema_name}")
            continue
        missing_required = sorted(set(schema.get("required") or []) - set(example))
        if missing_required:
            errors.append(
                f"API reference request example omits required fields: "
                f"{location} {schema_name} {', '.join(missing_required)}"
            )
        if contains_placeholder_example(example):
            errors.append(
                f"API reference request example contains a placeholder value: {location} {schema_name}"
            )

    forbidden_example_patterns = (
        re.compile(r"\bBearer\s+", re.IGNORECASE),
        re.compile(r"\b(?:sk|pk)_(?:live|prod)_", re.IGNORECASE),
        re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}"),
        re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"),
    )
    for node in walk(document):
        if not isinstance(node, dict):
            continue
        for key in ("example", "examples"):
            if key not in node:
                continue
            rendered = json.dumps(node[key], ensure_ascii=False, default=str)
            if contains_placeholder_example(node[key]):
                errors.append(f"API reference example contains a placeholder value: {location}")
                return errors
            if any(pattern.search(rendered) for pattern in forbidden_example_patterns):
                errors.append(f"API reference example resembles a real credential: {location}")
                return errors
    return errors


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


def validate() -> list[str]:
    errors: list[str] = []

    for required in sorted(REQUIRED_MACHINE):
        if not (ROOT / required).is_file():
            errors.append(f"Missing required contract artifact: {required}")

    md_files = all_files("**/*.md")
    for path in md_files:
        text = path.read_text(encoding="utf-8")
        if text.count("```") % 2:
            errors.append(f"Unbalanced fenced code block: {rel(path)}")
    errors.extend(validate_local_links(md_files))

    json_files = all_files("**/*.json")
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
    all_operation_ids: list[tuple[str, str]] = []
    for path in yaml_files:
        try:
            document = load_yaml_document(path)
        except Exception as exc:
            errors.append(f"Invalid YAML {rel(path)}: {exc}")
            continue
        if not isinstance(document, dict) or not str(document.get("openapi", "")).startswith("3.1"):
            continue
        if rel(path) in NON_CANONICAL_OPENAPI_DRAFTS | OPENAPI_COMPOSITION_SOURCES:
            continue
        if rel(path) in REFERENCE_OPENAPI_CONTRACTS:
            errors.extend(validate_api_reference_quality(path, document))
        operations = openapi_operations(document)
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

    req_path = ROOT / "docs/26-requirement-traceability.md"
    req_text = req_path.read_text(encoding="utf-8")
    req_defs = REQ_DEF_RE.findall(req_text)
    for item, count in Counter(req_defs).items():
        if count > 1:
            errors.append(f"Duplicate requirement definition: {item}")
    defined_reqs = set(req_defs)
    all_text = "\n".join(path.read_text(encoding="utf-8") for path in md_files)
    referenced_reqs = set(re.findall(r"\bREQ-[A-Z]+-[0-9]{3}\b", all_text))
    for missing in sorted(referenced_reqs - defined_reqs):
        errors.append(f"Requirement referenced but not defined: {missing}")

    decision_path = ROOT / "docs/25-implementation-decision-register.md"
    decision_defs = DECISION_DEF_RE.findall(decision_path.read_text(encoding="utf-8"))
    for item, count in Counter(decision_defs).items():
        if count > 1:
            errors.append(f"Duplicate decision definition: {item}")
    defined_decisions = set(decision_defs)
    referenced_decisions = set(re.findall(r"\bD-[0-9]{3}\b", all_text))
    for missing in sorted(referenced_decisions - defined_decisions):
        errors.append(f"Decision referenced but not defined: {missing}")

    gate_path = ROOT / "docs/21-minimum-verification-gates.md"
    gate_defs = GATE_DEF_RE.findall(gate_path.read_text(encoding="utf-8"))
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
            if item not in defined_decisions:
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

    actor_policy = core_api.get("x-deskseed-staff-actor-consistency") or {}
    declared_blueprint_actor_operations = {
        str(item) for item in actor_policy.get("blueprintOnlyOperationIds", [])
    }
    actual_blueprint_actor_operations: set[str] = set()
    bound_actor_operations: set[str] = set()
    csrf_bound_operations: set[str] = set()
    dual_use_actor_operations: set[str] = set()

    def local_refs(value: Any) -> set[str]:
        refs: set[str] = set()
        pending = [value]
        resolved: set[str] = set()
        while pending:
            current = pending.pop()
            for node in walk(current):
                if not isinstance(node, dict):
                    continue
                pointer = node.get("$ref")
                if not isinstance(pointer, str) or not pointer.startswith("#/"):
                    continue
                refs.add(pointer)
                if pointer in resolved:
                    continue
                resolved.add(pointer)
                try:
                    pending.append(resolve_pointer(core_api, pointer))
                except Exception:
                    # The generic OpenAPI pass reports the unresolved reference.
                    pass
        return refs

    for route, path_item in (core_api.get("paths") or {}).items():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            method_lower = str(method).lower()
            if method_lower not in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}:
                continue
            if not isinstance(operation, dict):
                continue
            security = operation.get("security") or []
            uses_staff_session = any(
                isinstance(requirement, dict) and "staffSession" in requirement
                for requirement in security
            )
            operation_id = str(operation.get("operationId", ""))
            is_dual_use_staff_csrf = operation_id == "getStaffCsrfToken"
            if not uses_staff_session and not is_dual_use_staff_csrf:
                continue
            if operation_id in declared_blueprint_actor_operations:
                actual_blueprint_actor_operations.add(operation_id)
                if operation.get("x-deskseed-contract-status") == "FROZEN":
                    errors.append(
                        f"Blueprint-only staff operation is marked FROZEN: {operation_id}"
                    )
                continue

            if operation.get("x-deskseed-contract-status") != "FROZEN":
                errors.append(
                    f"Implemented staff operation lacks FROZEN status: {operation_id}"
                )

            parameter_refs = [
                parameter.get("$ref")
                for parameter in operation.get("parameters", [])
                if isinstance(parameter, dict)
            ]
            expected_actor_ref = "#/components/parameters/ExpectedStaffActorHeader"
            if parameter_refs.count(expected_actor_ref) != 1:
                errors.append(
                    f"FROZEN staff operation must bind expected-actor header exactly once: "
                    f"{method_lower.upper()} {route} ({operation_id})"
                )

            responses = operation.get("responses") or {}
            invalid_actor_refs = local_refs(responses.get("400", {}))
            mismatch_refs = local_refs(responses.get("409", {}))
            if "#/components/schemas/InvalidExpectedStaffActorProblem" not in invalid_actor_refs:
                errors.append(
                    f"FROZEN staff operation lacks invalid-actor 400 contract: {operation_id}"
                )
            if "#/components/schemas/StaffSessionActorMismatchProblem" not in mismatch_refs:
                errors.append(
                    f"FROZEN staff operation lacks actor-mismatch 409 contract: {operation_id}"
                )

            if uses_staff_session and method_lower in {"post", "put", "patch", "delete"}:
                csrf_ref = "#/components/parameters/CsrfHeader"
                if parameter_refs.count(csrf_ref) != 1:
                    errors.append(
                        f"FROZEN unsafe staff operation must bind CSRF header exactly once: "
                        f"{method_lower.upper()} {route} ({operation_id})"
                    )
                else:
                    csrf_bound_operations.add(operation_id)
            elif method_lower in {"get", "head", "options", "trace"}:
                csrf_ref = "#/components/parameters/CsrfHeader"
                if csrf_ref in parameter_refs:
                    errors.append(
                        f"Safe staff operation must not require CSRF header: "
                        f"{method_lower.upper()} {route} ({operation_id})"
                    )
            if uses_staff_session:
                bound_actor_operations.add(operation_id)
            else:
                dual_use_actor_operations.add(operation_id)

    if actual_blueprint_actor_operations != declared_blueprint_actor_operations:
        errors.append(
            "Staff actor blueprint operation list mismatch: "
            f"declared={sorted(declared_blueprint_actor_operations)}, "
            f"actual={sorted(actual_blueprint_actor_operations)}"
        )
    if dual_use_actor_operations != {"getStaffCsrfToken"}:
        errors.append(
            "Dual-use staff actor operation binding mismatch: "
            f"{sorted(dual_use_actor_operations)}"
        )

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
    sql_text = (ROOT / "db/schema-blueprint-v0.6.sql").read_text(encoding="utf-8")
    if "tickets have no description column" not in sql_text:
        errors.append("SQL blueprint lacks the no-description invariant marker")
    if "first PUBLIC comment" not in all_text and "첫 PUBLIC Comment" not in all_text and "첫 `PUBLIC` comment" not in all_text:
        errors.append("First-public-comment invariant marker not found")

    statuses = set(re.findall(r"\|\s*(IMPLEMENTATION_READY|BLUEPRINT_READY|PROVISIONAL|DEFERRED)\s*\|", req_text))
    expected_statuses = {"IMPLEMENTATION_READY", "BLUEPRINT_READY", "PROVISIONAL", "DEFERRED"}
    if statuses != expected_statuses:
        errors.append(f"Requirement status vocabulary mismatch: {sorted(statuses)}")

    image_assets = [
        path
        for path in ROOT.rglob("*")
        if path.is_file()
        and not is_generated(path)
        and not path.is_relative_to(E2E_VISUAL_BASELINE_DIRECTORY)
        and not any(
            path.is_relative_to(directory)
            for directory in APPROVED_DESKSEED_ASSET_DIRECTORIES
        )
        and path.suffix.lower() in IMAGE_EXTENSIONS
    ]
    if image_assets:
        errors.append("Repository must not bundle unapproved visual assets: " + ", ".join(rel(p) for p in image_assets))

    return errors


def main() -> int:
    if len(sys.argv) != 1:
        print("Usage: python3 scripts/validate_documentation.py", file=sys.stderr)
        return 2
    errors = validate()
    for item in errors:
        print(f"ERROR: {item}", file=sys.stderr)
    print(json.dumps({"result": "PASS" if not errors else "FAIL"}, ensure_ascii=False))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
