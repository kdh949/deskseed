#!/usr/bin/env python3
"""Focused regression tests for deterministic, collision-safe Core OpenAPI bundling."""

from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "bundle_core_openapi.py"


class CoreOpenApiBundleTest(unittest.TestCase):
    def run_bundle(self, directory: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(directory / "scripts" / "bundle_core_openapi.py")],
            cwd=directory,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_committed_artifact_matches_deterministic_fragment_bundle(self) -> None:
        completed = subprocess.run(
            ["python3", str(SCRIPT), "--check"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_fragment_collision_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            copied = Path(temporary) / "repository"
            shutil.copytree(ROOT, copied)
            base = yaml.safe_load((copied / "api" / "core-api-base-v1.yaml").read_text(encoding="utf-8"))
            duplicate_path = next(iter(base["paths"]))
            duplicate_method = next(iter(base["paths"][duplicate_path]))
            (copied / "api" / "core-api-fragments" / "90-duplicate.yaml").write_text(
                yaml.safe_dump(
                    {
                        "x-deskseed-fragment": {"owner": "test"},
                        "paths": {duplicate_path: {duplicate_method: {"operationId": "duplicateCoreOperation"}}},
                    },
                    allow_unicode=True,
                    sort_keys=False,
                ),
                encoding="utf-8",
            )
            completed = self.run_bundle(copied)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("duplicate path+method", completed.stderr)


if __name__ == "__main__":
    unittest.main()
