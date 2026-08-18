#!/usr/bin/env python3
"""Regression checks for manually owned OpenAPI documentation quality."""
from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator
import yaml

sys.dont_write_bytecode = True

from validate_documentation import validate_api_reference_quality


ROOT = Path(__file__).resolve().parents[1]
CORE_CONTRACT = ROOT / "api/core-api-outline-v1.yaml"
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


class KnowledgeBaseContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.document: dict[str, Any] = yaml.safe_load(CORE_CONTRACT.read_text(encoding="utf-8"))

    def test_canonical_block_document_rejects_unknown_and_open_block_shapes(self) -> None:
        validator = Draft202012Validator(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "components": self.document["components"],
                "$ref": "#/components/schemas/KnowledgeBlockDocument",
            },
        )
        invalid_documents = {
            "an empty block object": {"schemaVersion": 1, "blocks": [{}]},
            "an HTML payload on a paragraph block": {
                "schemaVersion": 1,
                "blocks": [{"type": "paragraph", "text": "안전한 본문", "html": "<em>unsafe</em>"}],
            },
            "an unknown block type": {
                "schemaVersion": 1,
                "blocks": [{"type": "embed", "url": "https://example.test/embed"}],
            },
        }

        for description, document in invalid_documents.items():
            with self.subTest(description=description):
                self.assertTrue(
                    list(validator.iter_errors(document)),
                    f"KnowledgeBlockDocument must reject {description}",
                )

    def test_admin_article_list_contract_exposes_lifecycle_section_and_audience_filters(self) -> None:
        operation = self.document["paths"]["/api/v1/admin/knowledge/articles"]["get"]
        query_parameter_names = {
            parameter["name"]
            for parameter in (self.resolve(parameter) for parameter in operation["parameters"])
            if parameter["in"] == "query"
        }

        self.assertTrue(
            {"lifecycle", "sectionId", "audience"}.issubset(query_parameter_names),
            "admin article list must expose lifecycle, sectionId, and audience query filters",
        )

    def test_admin_article_list_contract_returns_dedicated_summary_page(self) -> None:
        operation = self.document["paths"]["/api/v1/admin/knowledge/articles"]["get"]
        response = self.resolve(operation["responses"]["200"])
        schema = response["content"]["application/json"]["schema"]

        self.assertEqual("#/components/schemas/AdminKnowledgeArticleSummaryPage", schema.get("$ref"))
        page = self.document["components"]["schemas"]["AdminKnowledgeArticleSummaryPage"]
        self.assertEqual(
            "#/components/schemas/AdminKnowledgeArticleSummary",
            page["properties"]["items"]["items"].get("$ref"),
        )

    def resolve(self, value: dict[str, Any]) -> dict[str, Any]:
        reference = value.get("$ref")
        if reference is None:
            return value
        self.assertTrue(reference.startswith("#/"), f"only local references are supported: {reference}")
        resolved: Any = self.document
        for part in reference.removeprefix("#/").split("/"):
            resolved = resolved[part]
        self.assertIsInstance(resolved, dict)
        return resolved


if __name__ == "__main__":
    unittest.main()
