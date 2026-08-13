#!/usr/bin/env python3
"""Regression checks for manually owned OpenAPI documentation quality."""
from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path
from typing import Any

import yaml

sys.dont_write_bytecode = True

from validate_documentation import validate_api_reference_quality


ROOT = Path(__file__).resolve().parents[1]
PLATFORM_CONTRACT = ROOT / "api/platform-api-outline-v1.yaml"


class ApiDocumentationQualityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.document: dict[str, Any] = yaml.safe_load(PLATFORM_CONTRACT.read_text(encoding="utf-8"))

    def validate(self, document: dict[str, Any]) -> list[str]:
        return validate_api_reference_quality(PLATFORM_CONTRACT, document)

    def test_current_platform_contract_passes_manual_documentation_gate(self) -> None:
        self.assertEqual([], self.validate(self.document))

    def test_name_based_description_is_rejected(self) -> None:
        changed = copy.deepcopy(self.document)
        changed["components"]["schemas"]["CreateTicketRequest"]["properties"]["subject"][
            "description"
        ] = "subject 값입니다."

        self.assertTrue(
            any("inferred boilerplate description" in error for error in self.validate(changed)),
        )

    def test_placeholder_example_is_rejected(self) -> None:
        changed = copy.deepcopy(self.document)
        changed["components"]["schemas"]["CreateTicketRequest"]["example"]["subject"] = "예시 값"

        self.assertTrue(
            any("placeholder value" in error for error in self.validate(changed)),
        )

    def test_implemented_request_requires_manual_review_marker(self) -> None:
        changed = copy.deepcopy(self.document)
        changed["components"]["schemas"]["CreateTicketRequest"].pop(
            "x-deskseed-documentation-review"
        )

        self.assertTrue(
            any("must be manually reviewed" in error for error in self.validate(changed)),
        )

    def test_implemented_request_cannot_hide_from_review_in_an_inline_schema(self) -> None:
        changed = copy.deepcopy(self.document)
        changed["paths"]["/tickets"]["post"]["requestBody"]["content"]["application/json"][
            "schema"
        ] = {"type": "object", "properties": {"subject": {"type": "string"}}}

        self.assertTrue(
            any("must use a named schema for manual review" in error for error in self.validate(changed)),
        )


if __name__ == "__main__":
    unittest.main()
