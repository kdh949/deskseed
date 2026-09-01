#!/usr/bin/env python3
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import yaml

from validate_documentation import load_yaml_document


class ValidateDocumentationYamlTest(unittest.TestCase):
    def test_compose_override_tags_are_loaded_as_safe_collections(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "compose.production.yaml"
            path.write_text(
                "services:\n"
                "  backend:\n"
                "    ports: !reset []\n"
                "    networks: !override [application]\n",
                encoding="utf-8",
            )

            document = load_yaml_document(path)

        self.assertEqual([], document["services"]["backend"]["ports"])
        self.assertEqual(["application"], document["services"]["backend"]["networks"])

    def test_unknown_tags_remain_invalid_outside_compose_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "contract.yaml"
            path.write_text("value: !override [unsafe]\n", encoding="utf-8")

            with self.assertRaises(yaml.constructor.ConstructorError):
                load_yaml_document(path)


if __name__ == "__main__":
    unittest.main()
