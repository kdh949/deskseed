#!/usr/bin/env python3
"""Validate Wave 0/1 lane ownership before parallel feature branches start."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[1]
OWNERSHIP_FILE = ROOT / "tasks" / "goal-wave-ownership.yaml"


def fail(message: str) -> None:
    raise SystemExit(f"goal-wave-ownership: {message}")


def as_nonempty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail(f"{label} must be a non-empty string")
    return value


def main() -> None:
    payload = yaml.safe_load(OWNERSHIP_FILE.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or payload.get("version") != 1:
        fail("version 1 document required")
    base_migration = payload.get("baseMigration")
    if not isinstance(base_migration, int):
        fail("baseMigration must be an integer")
    lanes = payload.get("lanes")
    if not isinstance(lanes, list) or not lanes:
        fail("at least one lane is required")

    ids: set[str] = set()
    branches: set[str] = set()
    ranges: list[tuple[int, int, str]] = []
    fragments: set[str] = set()
    for lane in lanes:
        if not isinstance(lane, dict):
            fail("each lane must be a mapping")
        lane_id = as_nonempty_string(lane.get("id"), "lane.id")
        if lane_id in ids:
            fail(f"duplicate lane id '{lane_id}'")
        ids.add(lane_id)
        reservation = lane.get("migrationRange")
        if not (
            isinstance(reservation, list)
            and len(reservation) == 2
            and all(isinstance(item, int) for item in reservation)
            and base_migration < reservation[0] <= reservation[1]
        ):
            fail(f"{lane_id} has an invalid migrationRange")
        ranges.append((reservation[0], reservation[1], lane_id))
        lane_branches = lane.get("branches")
        if not isinstance(lane_branches, list) or not lane_branches:
            fail(f"{lane_id} must reserve at least one branch")
        for branch in lane_branches:
            branch_name = as_nonempty_string(branch, f"{lane_id}.branches")
            if not branch_name.startswith("feature/goal/"):
                fail(f"{lane_id} branch '{branch_name}' must use feature/goal/")
            if branch_name in branches:
                fail(f"duplicate branch '{branch_name}'")
            branches.add(branch_name)
        for key in ("frontendContributionRoot", "traceabilitySection", "progress"):
            as_nonempty_string(lane.get(key), f"{lane_id}.{key}")
        fragment = lane.get("fragment")
        if fragment is None:
            continue
        fragment_path = as_nonempty_string(fragment, f"{lane_id}.fragment")
        if fragment_path in fragments:
            fail(f"fragment '{fragment_path}' has multiple owners")
        fragments.add(fragment_path)
        if not (ROOT / fragment_path).is_file():
            fail(f"fragment '{fragment_path}' does not exist")

    for previous, current in zip(sorted(ranges), sorted(ranges)[1:]):
        if current[0] <= previous[1]:
            fail(f"migration ranges overlap: {previous[2]} and {current[2]}")


if __name__ == "__main__":
    main()
