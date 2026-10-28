#!/usr/bin/env python3
"""Validate committed API contracts, references, schemas and authentication headers."""
from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

import yaml
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_CONTRACTS = {
    "core-api-outline-v1.yaml",
    "customer-identity-api-v1.yaml",
    "platform-api-outline-v1.yaml",
    "api-surface-catalog-v0.6.yaml",
    "ui-route-catalog-v0.6.yaml",
    "audit-activity-event-v1.schema.json",
    "integration-event-envelope-v1.schema.json",
}
# The base is checked through its bundle; mvp-target is a historical planning draft.
NON_RUNTIME_CONTRACTS = {"core-api-base-v1.yaml", "mvp-target.yaml"}
HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
CREDENTIAL_PATTERNS = (
    re.compile(r"\bBearer\s+", re.IGNORECASE),
    re.compile(r"\b(?:sk|pk)_(?:live|prod)_", re.IGNORECASE),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}"),
    re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"),
)


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


def validate_openapi(document: dict[str, Any], location: str) -> list[str]:
    errors: list[str] = []
    if not str(document.get("openapi", "")).startswith("3.1."):
        errors.append(f"Expected OpenAPI 3.1: {location}")
    operations = openapi_operations(document)
    for route, method, operation_id in operations:
        if not operation_id:
            errors.append(f"Missing operationId: {location} {method.upper()} {route}")
    for operation_id, count in Counter(op for _, _, op in operations if op).items():
        if count > 1:
            errors.append(f"Duplicate OpenAPI operationId {operation_id}: {location}")
    for name, schema in (document.get("components", {}).get("schemas", {})).items():
        try:
            Draft202012Validator.check_schema(schema)
        except Exception as exc:
            errors.append(f"Invalid schema {name} in {location}: {exc}")
    for node in walk(document):
        if not isinstance(node, dict):
            continue
        pointer = node.get("$ref")
        if isinstance(pointer, str) and pointer.startswith("#/"):
            try:
                resolve_pointer(document, pointer)
            except (KeyError, IndexError, TypeError, ValueError) as exc:
                errors.append(f"Unresolved local $ref {pointer} in {location}: {exc}")
        for key in ("example", "examples"):
            if key in node:
                rendered = json.dumps(node[key], ensure_ascii=False, default=str)
                if any(pattern.search(rendered) for pattern in CREDENTIAL_PATTERNS):
                    errors.append(f"API example resembles a credential: {location}")
    return errors


def validate_staff_actor_contract(core_api: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    actor_policy = core_api.get("x-deskseed-staff-actor-consistency") or {}
    declared_blueprint_actor_operations = {
        str(item) for item in actor_policy.get("blueprintOnlyOperationIds", [])
    }
    actual_blueprint_actor_operations: set[str] = set()
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
            elif method_lower in {"get", "head", "options", "trace"}:
                csrf_ref = "#/components/parameters/CsrfHeader"
                if csrf_ref in parameter_refs:
                    errors.append(
                        f"Safe staff operation must not require CSRF header: "
                        f"{method_lower.upper()} {route} ({operation_id})"
                    )
            if not uses_staff_session:
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

    return errors


def validate() -> list[str]:
    errors: list[str] = []
    api = ROOT / "api"
    for required in sorted(REQUIRED_CONTRACTS):
        if not (api / required).is_file():
            errors.append(f"Missing required API contract: api/{required}")
    operation_locations: dict[str, set[str]] = {}
    for path in sorted(api.glob("*.yaml")):
        if path.name in NON_RUNTIME_CONTRACTS:
            continue
        location = path.relative_to(ROOT).as_posix()
        try:
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            if not isinstance(document, dict):
                raise ValueError("expected a YAML mapping")
        except Exception as exc:
            errors.append(f"Invalid YAML {location}: {exc}")
            continue
        if "openapi" not in document:
            continue
        errors.extend(validate_openapi(document, location))
        for _, _, operation_id in openapi_operations(document):
            if operation_id:
                operation_locations.setdefault(operation_id, set()).add(location)
        if path.name == "core-api-outline-v1.yaml":
            errors.extend(validate_staff_actor_contract(document))
    for operation_id, locations in sorted(operation_locations.items()):
        if len(locations) > 1:
            errors.append(f"Duplicate OpenAPI operationId {operation_id}: {', '.join(sorted(locations))}")
    for path in sorted(api.glob("*.schema.json")):
        try:
            Draft202012Validator.check_schema(json.loads(path.read_text(encoding="utf-8")))
        except Exception as exc:
            errors.append(f"Invalid JSON Schema {path.name}: {exc}")
    return errors


def main() -> int:
    if len(sys.argv) != 1:
        print("Usage: python3 scripts/validate_api_contracts.py", file=sys.stderr)
        return 2
    errors = validate()
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    print(json.dumps({"result": "PASS" if not errors else "FAIL"}))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
