#!/usr/bin/env python3
"""Classify a cumulative PR diff into Deskseed CI verification gates."""

from __future__ import annotations

import argparse
import dataclasses
import html
import json
import os
from pathlib import PurePosixPath
import re
import subprocess
from typing import Callable, Iterable, Sequence


OUTPUT_NAMES = (
    "seed_contracts",
    "backend",
    "backend_fast",
    "backend_contract",
    "backend_integration",
    "backend_migration",
    "backend_slow",
    "frontend_quality",
    "frontend_storybook",
    "frontend_e2e",
    "compose",
    "run_all",
)

SHA_PATTERN = re.compile(r"[0-9a-fA-F]{40}")
FRONTEND_RENDERING_EXTENSIONS = {
    ".css",
    ".gif",
    ".jpeg",
    ".jpg",
    ".less",
    ".otf",
    ".png",
    ".sass",
    ".scss",
    ".svg",
    ".ttf",
    ".webp",
    ".woff",
    ".woff2",
}
SEED_CONTRACT_SCRIPTS = {
    "scripts/bundle_core_openapi.py",
    "scripts/test_api_documentation_quality.py",
    "scripts/test_core_openapi_bundle.py",
    "scripts/validate_documentation.py",
    "scripts/validate_goal_wave_ownership.py",
    "scripts/verify_seed.py",
    "scripts/requirements-docs.txt",
}
COMPOSE_SCRIPTS = {
    "scripts/compose-smoke.sh",
    "scripts/e2e-compose-ownership.sh",
    "scripts/test-e2e-compose-ownership.sh",
}
GENERATED_DOCUMENTATION = {
    "FILE-MANIFEST.txt",
    "VALIDATION-REPORT.md",
}


@dataclasses.dataclass
class ChangeClassification:
    seed_contracts: bool = False
    backend: bool = False
    backend_fast: bool = False
    backend_contract: bool = False
    backend_integration: bool = False
    backend_migration: bool = False
    backend_slow: bool = False
    frontend_quality: bool = False
    frontend_storybook: bool = False
    frontend_e2e: bool = False
    compose: bool = False
    run_all: bool = False
    reason: str = "classified paths"

    @classmethod
    def fallback(cls, reason: str) -> "ChangeClassification":
        return cls(
            seed_contracts=True,
            backend=True,
            backend_fast=True,
            backend_contract=True,
            backend_integration=True,
            backend_migration=True,
            backend_slow=True,
            frontend_quality=True,
            frontend_storybook=True,
            frontend_e2e=True,
            compose=True,
            run_all=True,
            reason=reason,
        )

    def as_outputs(self) -> dict[str, bool]:
        return {name: bool(getattr(self, name)) for name in OUTPUT_NAMES}

    def include(self, *names: str) -> None:
        for name in names:
            setattr(self, name, True)

    def include_backend(self, *categories: str) -> None:
        self.backend = True
        self.include(*categories)


def _normalized_path(raw_path: str) -> str:
    path = raw_path.replace("\\", "/")
    pure_path = PurePosixPath(path)
    if not path or pure_path.is_absolute() or ".." in pure_path.parts or path.startswith("./"):
        raise ValueError("unsafe or empty changed path")
    return path


def classify_changes(paths: Iterable[str], event_type: str) -> ChangeClassification:
    if event_type == "push":
        return ChangeClassification.fallback("main push runs every release gate")
    if event_type != "pull_request":
        return ChangeClassification.fallback("unsupported event type")

    try:
        changed_paths = sorted({_normalized_path(path) for path in paths})
    except (TypeError, ValueError):
        return ChangeClassification.fallback("invalid changed path")
    if not changed_paths:
        return ChangeClassification.fallback("empty pull request diff")

    result = ChangeClassification()
    for path in changed_paths:
        if _is_global_or_unknown_control_file(path):
            return ChangeClassification.fallback(f"global control file: {path}")

        if path.startswith("api/") or path in SEED_CONTRACT_SCRIPTS:
            result.include("seed_contracts")
            result.include_backend("backend_contract")
        elif path in GENERATED_DOCUMENTATION:
            result.include("seed_contracts")
        elif _is_documentation(path):
            result.include("seed_contracts")
        elif _is_backend_global_build(path):
            return ChangeClassification.fallback(f"backend global build file: {path}")
        elif _is_backend_migration(path):
            result.include_backend("backend_integration", "backend_migration")
            result.include("compose")
        elif _is_backend_test_infrastructure(path):
            result.include_backend(
                "backend_fast",
                "backend_contract",
                "backend_integration",
                "backend_migration",
                "backend_slow",
            )
        elif _is_backend_global_runtime(path):
            result.include_backend(
                "backend_fast",
                "backend_contract",
                "backend_integration",
                "backend_migration",
                "backend_slow",
            )
            result.include("compose")
        elif path.startswith("backend/src/main/"):
            _include_backend_main_path(result, path)
        elif path.startswith("backend/src/test/"):
            _include_backend_test_path(result, path)
        elif _is_frontend_dependency_environment(path):
            result.include("frontend_quality", "frontend_storybook", "frontend_e2e")
        elif _is_frontend_e2e(path):
            result.include("frontend_quality", "frontend_e2e")
        elif _is_frontend_storybook_or_rendering(path):
            result.include("frontend_quality", "frontend_storybook")
        elif _is_frontend_quality(path):
            result.include("frontend_quality")
        elif _is_compose_runtime(path):
            result.include("compose")
        else:
            return ChangeClassification.fallback(f"unclassified path: {path}")

    if result.frontend_e2e:
        result.frontend_quality = True
    return result


def _is_global_or_unknown_control_file(path: str) -> bool:
    return (
        path == "AGENTS.md"
        or path.endswith("/AGENTS.md")
        or path == "Makefile"
        or path == ".java-version"
        or path.startswith(".github/workflows/")
        or path.startswith("scripts/ci/")
    )


def _is_documentation(path: str) -> bool:
    return (
        path.startswith(("docs/", "tasks/", "checklists/"))
        or path.lower().endswith(".md")
    )


def _is_backend_global_build(path: str) -> bool:
    return path in {
        "backend/build.gradle.kts",
        "backend/settings.gradle.kts",
        "backend/gradle.properties",
        "backend/gradlew",
        "backend/gradlew.bat",
    } or path.startswith("backend/gradle/wrapper/")


def _is_backend_migration(path: str) -> bool:
    return path.startswith("backend/src/main/resources/db/migration/")


def _is_backend_global_runtime(path: str) -> bool:
    return (
        path.startswith("backend/src/main/resources/application")
        or path == "backend/src/main/kotlin/dev/deskseed/DeskseedApplication.kt"
        or path == "backend/src/main/kotlin/dev/deskseed/SchedulingConfiguration.kt"
    )


def _is_backend_test_infrastructure(path: str) -> bool:
    return path.startswith("backend/src/test/resources/") or path.startswith(
        "backend/src/test/kotlin/dev/deskseed/testsupport/"
    )


def _include_backend_main_path(result: ChangeClassification, path: str) -> None:
    if "/domain/" in path:
        result.include_backend("backend_fast")
        return
    filename = PurePosixPath(path).name
    if any(token in filename for token in ("Worker", "Scheduler", "OutboxMaterializer")):
        result.include_backend("backend_integration", "backend_slow")
        return
    result.include_backend("backend_fast", "backend_contract", "backend_integration")


def _include_backend_test_path(result: ChangeClassification, path: str) -> None:
    filename = PurePosixPath(path).name
    if filename.endswith("MigrationTest.kt") or filename == "P1AdditiveMigrationTest.kt":
        result.include_backend("backend_migration")
    elif filename in {
        "ArchitectureTest.kt",
        "ApiDocumentationIntegrationTest.kt",
        "AttachmentProductionBoundaryTest.kt",
        "PlatformNetworkBoundaryTest.kt",
    } or "ContractTest" in filename:
        result.include_backend("backend_contract")
    elif filename in {
        "FirstReplySlaIntegrationTest.kt",
        "MailpitApiE2ETest.kt",
        "OrganizationConcurrencyIntegrationTest.kt",
        "OutboundMailDeliveryIntegrationTest.kt",
        "PlatformRateLimiterTest.kt",
        "StaffCollaborationWebSocketIntegrationTest.kt",
        "WebhookDeliveryWorkerIntegrationTest.kt",
        "WebhookOutboxMaterializerIntegrationTest.kt",
    }:
        result.include_backend("backend_slow")
    elif "IntegrationTest" in filename:
        result.include_backend("backend_integration")
    else:
        result.include_backend("backend_fast")


def _is_frontend_dependency_environment(path: str) -> bool:
    return path in {
        "frontend/package.json",
        "frontend/package-lock.json",
        "frontend/npm-shrinkwrap.json",
    }


def _is_frontend_e2e(path: str) -> bool:
    return path.startswith("frontend/e2e/") or path.startswith("frontend/playwright")


def _is_frontend_storybook_or_rendering(path: str) -> bool:
    suffix = PurePosixPath(path).suffix.lower()
    return (
        path.startswith("frontend/.storybook/")
        or path.startswith("frontend/src/design-system/")
        or path.startswith("frontend/public/")
        or ".stories." in path
        or ".story." in path
        or suffix in FRONTEND_RENDERING_EXTENSIONS
        or path.startswith("frontend/vitest")
    )


def _is_frontend_quality(path: str) -> bool:
    return (
        path.startswith("frontend/src/")
        or path.startswith("frontend/scripts/")
        or path == "frontend/index.html"
        or path.startswith("frontend/vite.config.")
        or path.startswith("frontend/tsconfig")
        or path.startswith("frontend/eslint.config.")
        or path.startswith("frontend/prettier.config.")
        or path == "frontend/.prettierrc"
    )


def _is_compose_runtime(path: str) -> bool:
    name = PurePosixPath(path).name
    return (
        path in COMPOSE_SCRIPTS
        or re.fullmatch(r"compose(?:\.[^.]+)*\.ya?ml", name) is not None
        or name == "Dockerfile"
        or name.startswith("Dockerfile.")
        or path == ".env.example"
    )


def collect_git_changes(
    base_sha: str,
    head_sha: str,
    *,
    runner: Callable[..., subprocess.CompletedProcess[bytes]] = subprocess.run,
    cwd: str | None = None,
) -> list[str]:
    if not SHA_PATTERN.fullmatch(base_sha) or not SHA_PATTERN.fullmatch(head_sha):
        raise ValueError("base and head must be full commit SHAs")
    command = [
        "git",
        "diff",
        "--name-status",
        "-z",
        "--find-renames=50%",
        "--diff-filter=ACDMRTUXB",
        f"{base_sha}...{head_sha}",
        "--",
    ]
    completed = runner(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if completed.returncode != 0:
        raise subprocess.CalledProcessError(completed.returncode, command)
    return _parse_name_status(completed.stdout)


def _parse_name_status(raw: bytes) -> list[str]:
    fields = raw.split(b"\0")
    if fields and fields[-1] == b"":
        fields.pop()
    paths: list[str] = []
    index = 0
    while index < len(fields):
        status = fields[index].decode("ascii", errors="strict")
        index += 1
        path_count = 2 if status.startswith(("R", "C")) else 1
        if not status or index + path_count > len(fields):
            raise ValueError("malformed git name-status output")
        for _ in range(path_count):
            paths.append(os.fsdecode(fields[index]))
            index += 1
    return list(dict.fromkeys(paths))


def _write_github_outputs(path: str, result: ChangeClassification) -> None:
    with open(path, "a", encoding="utf-8") as output:
        for name, value in result.as_outputs().items():
            output.write(f"{name}={'true' if value else 'false'}\n")


def _safe_summary_text(value: str, limit: int = 240) -> str:
    escaped_controls = "".join(
        character if character.isprintable() else f"\\x{ord(character):02x}"
        for character in value
    )
    if len(escaped_controls) > limit:
        escaped_controls = f"{escaped_controls[:limit]}..."
    return html.escape(escaped_controls, quote=True)


def _write_summary(path: str, event_type: str, changed_paths: Sequence[str], result: ChangeClassification) -> None:
    display_limit = 500
    with open(path, "a", encoding="utf-8") as summary:
        summary.write("## Change classification\n\n")
        summary.write(f"- Event: `{_safe_summary_text(event_type)}`\n")
        summary.write(f"- Decision: {_safe_summary_text(result.reason)}\n")
        summary.write(f"- Changed paths: {len(changed_paths)}\n\n")
        summary.write("| Gate output | Run |\n| --- | --- |\n")
        for name, value in result.as_outputs().items():
            summary.write(f"| `{name}` | `{'true' if value else 'false'}` |\n")
        summary.write("\n### Changed file paths\n\n")
        if not changed_paths:
            summary.write("No paths were available; conservative fallback applies.\n")
        else:
            for changed_path in changed_paths[:display_limit]:
                summary.write(f"- <code>{_safe_summary_text(changed_path)}</code>\n")
            if len(changed_paths) > display_limit:
                summary.write(f"- ... {len(changed_paths) - display_limit} additional paths omitted from the summary.\n")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-type", required=True)
    parser.add_argument("--base-sha")
    parser.add_argument("--head-sha")
    parser.add_argument("--changed-file", action="append", default=[])
    parser.add_argument("--github-output", default=os.environ.get("GITHUB_OUTPUT"))
    parser.add_argument("--github-step-summary", default=os.environ.get("GITHUB_STEP_SUMMARY"))
    args = parser.parse_args(argv)

    changed_paths: list[str] = []
    try:
        if args.changed_file:
            changed_paths = args.changed_file
        elif args.event_type == "pull_request":
            changed_paths = collect_git_changes(args.base_sha or "", args.head_sha or "")
        result = classify_changes(changed_paths, event_type=args.event_type)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        result = ChangeClassification.fallback(f"change detection failed: {type(error).__name__}")

    if args.github_output:
        _write_github_outputs(args.github_output, result)
    if args.github_step_summary:
        _write_summary(args.github_step_summary, args.event_type, changed_paths, result)
    print(json.dumps(result.as_outputs(), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
