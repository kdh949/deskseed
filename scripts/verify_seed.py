#!/usr/bin/env python3
"""Fast, dependency-light checks for Deskseed's seed contracts.

This intentionally does not replace Gradle, frontend, or database tests. It catches
structural drift before those heavier checks run.
"""
from __future__ import annotations

import json
import re
import stat
import sys
from pathlib import Path
from typing import Iterable

try:
    import yaml
except ModuleNotFoundError:
    yaml = None  # type: ignore[assignment]

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []


def fail(message: str) -> None:
    ERRORS.append(message)


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def require_files(paths: Iterable[str]) -> None:
    for item in paths:
        path = ROOT / item
        if not path.is_file():
            fail(f"missing required file: {item}")


def parse_yaml_files() -> None:
    if yaml is None:
        print("- note: PyYAML is unavailable; full YAML parsing is skipped")
        return
    for path in sorted(ROOT.rglob("*.y*ml")):
        if not path.is_file():
            continue
        try:
            list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except Exception as exc:  # noqa: BLE001 - verifier should aggregate failures
            fail(f"invalid YAML {relative(path)}: {exc}")


def parse_json_files() -> None:
    for path in sorted(ROOT.rglob("*.json")):
        if not path.is_file() or "node_modules" in path.parts:
            continue
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            fail(f"invalid JSON {relative(path)}: {exc}")


def check_first_comment_model() -> None:
    migration = (
        ROOT
        / "backend/src/main/resources/db/migration/V1__initial_request_vertical_slice.sql"
    ).read_text(encoding="utf-8")
    match = re.search(
        r"create table tickets\s*\((.*?)\);",
        migration,
        flags=re.IGNORECASE | re.DOTALL,
    )
    if not match:
        fail("could not locate tickets table in V1 migration")
    elif re.search(r"\bdescription\b", match.group(1), flags=re.IGNORECASE):
        fail("tickets table must not contain a description column")

    domain_file = ROOT / "backend/src/main/kotlin/dev/deskseed/ticketing/internal/domain/Ticket.kt"
    entity_file = ROOT / "backend/src/main/kotlin/dev/deskseed/ticketing/internal/TicketEntity.kt"
    for path in (domain_file, entity_file):
        text = path.read_text(encoding="utf-8")
        if re.search(r"\b(val|var)\s+description\b", text):
            fail(f"description property is forbidden in {relative(path)}")

    if "visibility = CommentVisibility.PUBLIC" not in domain_file.read_text(encoding="utf-8"):
        fail("web request must create a first PUBLIC comment")


def check_module_boundaries() -> None:
    source_root = ROOT / "backend/src/main/kotlin/dev/deskseed"
    modules = {path.name for path in source_root.iterdir() if path.is_dir()}
    for path in sorted(source_root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        package_match = re.search(r"^package\s+dev\.deskseed(?:\.([A-Za-z0-9_]+))?", text, re.MULTILINE)
        source_module = package_match.group(1) if package_match else None
        for target_module in modules:
            if target_module == source_module:
                continue
            if re.search(rf"^import\s+dev\.deskseed\.{re.escape(target_module)}\.internal(?:\.|$)", text, re.MULTILINE):
                fail(
                    f"module {source_module or '<root>'} imports internal API of "
                    f"{target_module}: {relative(path)}"
                )
        if source_module == "foundation":
            for target_module in modules - {"foundation"}:
                if re.search(rf"^import\s+dev\.deskseed\.{re.escape(target_module)}(?:\.|$)", text, re.MULTILINE):
                    fail(
                        f"foundation must not depend on feature module {target_module}: "
                        f"{relative(path)}"
                    )


def check_openapi_contract() -> None:
    path = ROOT / "api/core-api-outline-v1.yaml"
    text = path.read_text(encoding="utf-8")
    required_markers = (
        "/api/v1/requests:",
        "/api/v1/requests/{ticketNumber}:",
        "X-Request-Access-Token",
    )
    for marker in required_markers:
        if marker not in text:
            fail(f"OpenAPI is missing required marker: {marker}")

    if yaml is None:
        return
    document = yaml.safe_load(text)
    paths = document.get("paths", {})
    if "/api/v1/requests" not in paths or "post" not in paths["/api/v1/requests"]:
        fail("OpenAPI must define POST /api/v1/requests")
    detail = paths.get("/api/v1/requests/{ticketNumber}", {})
    if "get" not in detail:
        fail("OpenAPI must define GET /api/v1/requests/{ticketNumber}")
    else:
        parameters = detail["get"].get("parameters", [])
        if not any(
            parameter.get("in") == "header"
            and parameter.get("name") == "X-Request-Access-Token"
            for parameter in parameters
        ):
            fail("customer request lookup must require X-Request-Access-Token")


def check_audit_immutability() -> None:
    migration = (
        ROOT
        / "backend/src/main/resources/db/migration/V1__initial_request_vertical_slice.sql"
    ).read_text(encoding="utf-8").lower()
    for trigger in ("ticket_audits_immutable", "ticket_audit_events_immutable"):
        if trigger not in migration:
            fail(f"missing append-only audit trigger: {trigger}")


def check_executable_scripts() -> None:
    for item in (
        "backend/gradlew",
        "scripts/demo-request.sh",
        "scripts/enrich_openapi_documentation.py",
        "scripts/verify_seed.py",
    ):
        path = ROOT / item
        mode = path.stat().st_mode if path.exists() else 0
        if not mode & stat.S_IXUSR:
            fail(f"script is not executable: {item}")


def check_documentation_status() -> None:
    traceability = (ROOT / "docs/26-requirement-traceability.md").read_text(encoding="utf-8")
    for label in ("IMPLEMENTATION_READY", "BLUEPRINT_READY"):
        if label not in traceability:
            fail(f"traceability matrix must explain status label: {label}")
    adr_count = len(list((ROOT / "docs/adr").glob("*.md")))
    if adr_count < 10:
        fail(f"expected at least 10 ADRs, found {adr_count}")


def main() -> int:
    require_files(
        [
            "README.md",
            "compose.yaml",
            "api/core-api-outline-v1.yaml",
            "api/api-surface-catalog-v0.6.yaml",
            "backend/build.gradle.kts",
            "backend/src/main/resources/db/migration/V1__initial_request_vertical_slice.sql",
            "frontend/package.json",
            "docs/01-prd-mvp.md",
            "docs/07-codebase-rules.md",
            "docs/26-requirement-traceability.md",
        ]
    )
    parse_yaml_files()
    parse_json_files()
    check_first_comment_model()
    check_module_boundaries()
    check_openapi_contract()
    check_audit_immutability()
    check_executable_scripts()
    check_documentation_status()

    if ERRORS:
        print("Deskseed seed verification FAILED", file=sys.stderr)
        for error in ERRORS:
            print(f"- {error}", file=sys.stderr)
        return 1

    print("Deskseed seed verification passed")
    print(f"- YAML files: {len(list(ROOT.rglob('*.yml'))) + len(list(ROOT.rglob('*.yaml')))}")
    print(f"- Kotlin files: {len(list(ROOT.rglob('*.kt')))}")
    print(f"- ADRs: {len(list((ROOT / 'docs/adr').glob('*.md')))}")
    print("- invariant: request body is the first PUBLIC comment")
    print("- invariant: feature modules do not import another module's internal package")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
