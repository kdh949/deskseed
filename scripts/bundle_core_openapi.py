#!/usr/bin/env python3
"""Build Deskseed's committed Core OpenAPI compatibility artifact from owned fragments."""

from __future__ import annotations

import argparse
import copy
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[1]
API = ROOT / "api"
BASE = API / "core-api-base-v1.yaml"
FRAGMENTS = API / "core-api-fragments"
OUTPUT = API / "core-api-outline-v1.yaml"


class BundleError(ValueError):
    pass


def load_yaml(path: Path) -> dict[str, Any]:
    payload = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise BundleError(f"{path.relative_to(ROOT)} must contain a YAML mapping")
    return payload


def merge_mapping(target: dict[str, Any], incoming: dict[str, Any], label: str, fragment: Path) -> None:
    for key, value in incoming.items():
        if key in target:
            raise BundleError(f"duplicate {label} '{key}' in {fragment.relative_to(ROOT)}")
        target[key] = copy.deepcopy(value)


def merge_paths(target: dict[str, Any], incoming: dict[str, Any], fragment: Path) -> None:
    for path, path_item in incoming.items():
        if not isinstance(path_item, dict):
            raise BundleError(f"path '{path}' in {fragment.relative_to(ROOT)} must be a mapping")
        existing = target.setdefault(path, {})
        if not isinstance(existing, dict):
            raise BundleError(f"base path '{path}' is not a mapping")
        for method, operation in path_item.items():
            if method in existing:
                raise BundleError(
                    f"duplicate path+method '{path} {method}' in {fragment.relative_to(ROOT)}",
                )
            existing[method] = copy.deepcopy(operation)


def merge_tags(target: list[Any], incoming: list[Any], fragment: Path) -> None:
    names = {tag.get("name") for tag in target if isinstance(tag, dict)}
    for tag in incoming:
        if not isinstance(tag, dict) or not isinstance(tag.get("name"), str):
            raise BundleError(f"tag in {fragment.relative_to(ROOT)} must have a string name")
        if tag["name"] in names:
            raise BundleError(f"duplicate tag '{tag['name']}' in {fragment.relative_to(ROOT)}")
        target.append(copy.deepcopy(tag))
        names.add(tag["name"])


def bundle() -> dict[str, Any]:
    document = load_yaml(BASE)
    if not str(document.get("openapi", "")).startswith("3.1"):
        raise BundleError("core-api-base-v1.yaml must be an OpenAPI 3.1 document")
    document.setdefault("paths", {})
    document.setdefault("components", {})
    document.setdefault("tags", [])
    if not isinstance(document["paths"], dict) or not isinstance(document["components"], dict):
        raise BundleError("base OpenAPI paths and components must be mappings")
    if not isinstance(document["tags"], list):
        raise BundleError("base OpenAPI tags must be a list")

    for fragment in sorted(FRAGMENTS.glob("*.yaml")):
        payload = load_yaml(fragment)
        metadata = payload.pop("x-deskseed-fragment", None)
        if not isinstance(metadata, dict) or not isinstance(metadata.get("owner"), str):
            raise BundleError(f"{fragment.relative_to(ROOT)} must declare x-deskseed-fragment.owner")
        paths = payload.pop("paths", {})
        components = payload.pop("components", {})
        tags = payload.pop("tags", [])
        if payload:
            raise BundleError(
                f"{fragment.relative_to(ROOT)} has unsupported top-level keys: {', '.join(sorted(payload))}",
            )
        if not isinstance(paths, dict) or not isinstance(components, dict) or not isinstance(tags, list):
            raise BundleError(f"{fragment.relative_to(ROOT)} has invalid paths/components/tags")
        merge_paths(document["paths"], paths, fragment)
        merge_tags(document["tags"], tags, fragment)
        for component_kind, entries in components.items():
            if not isinstance(entries, dict):
                raise BundleError(f"component group '{component_kind}' in {fragment.relative_to(ROOT)} must be a mapping")
            target = document["components"].setdefault(component_kind, {})
            if not isinstance(target, dict):
                raise BundleError(f"base component group '{component_kind}' is not a mapping")
            merge_mapping(target, entries, f"component {component_kind}", fragment)
    return document


def has_contribution_content() -> bool:
    for fragment in sorted(FRAGMENTS.glob("*.yaml")):
        payload = load_yaml(fragment)
        payload.pop("x-deskseed-fragment", None)
        if any(payload.get(key) for key in ("paths", "components", "tags")):
            return True
    return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail when the committed artifact is stale")
    args = parser.parse_args()
    document = bundle()
    # Before a lane contributes operations, preserving the original compatibility
    # artifact byte-for-byte makes the source/artifact migration independently
    # reviewable. Once a fragment contributes content, the deterministic YAML
    # serializer owns the complete generated output.
    rendered = (
        yaml.safe_dump(document, allow_unicode=True, sort_keys=False, width=120)
        if has_contribution_content()
        else BASE.read_text(encoding="utf-8")
    )
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != rendered:
            raise SystemExit("api/core-api-outline-v1.yaml is stale; run scripts/bundle_core_openapi.py")
        return
    OUTPUT.write_text(rendered, encoding="utf-8")


if __name__ == "__main__":
    main()
