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
CUSTOMER_IDENTITY_CONTRACT = ROOT / "api/customer-identity-api-v1.yaml"


class ApiDocumentationQualityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.document: dict[str, Any] = yaml.safe_load(PLATFORM_CONTRACT.read_text(encoding="utf-8"))

    def validate(self, document: dict[str, Any]) -> list[str]:
        return validate_api_reference_quality(PLATFORM_CONTRACT, document)

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


class CustomerIdentityContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.document: dict[str, Any] = yaml.safe_load(
            CUSTOMER_IDENTITY_CONTRACT.read_text(encoding="utf-8"),
        )
        self.core_document: dict[str, Any] = yaml.safe_load(CORE_CONTRACT.read_text(encoding="utf-8"))

    def operation(self, path: str, method: str = "post") -> dict[str, Any]:
        operation = self.document["paths"][path][method]
        self.assertIsInstance(operation, dict)
        return operation

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

    def test_password_primary_operation_family_is_complete_without_claiming_runtime_delivery(self) -> None:
        expected = {
            ("/api/v1/customer/registrations", "post"): "requestCustomerRegistration",
            (
                "/api/v1/customer/registration-verifications",
                "post",
            ): "verifyCustomerRegistration",
            (
                "/api/v1/customer/auth/password-sessions",
                "post",
            ): "createCustomerPasswordSession",
            (
                "/api/v1/customer/auth/password-reset-requests",
                "post",
            ): "requestCustomerPasswordReset",
            (
                "/api/v1/customer/auth/password-resets",
                "post",
            ): "resetCustomerPassword",
            (
                "/api/v1/customer/me/registration",
                "put",
            ): "completePasswordlessCustomerRegistration",
            ("/api/v1/customer/me", "get"): "getCurrentCustomer",
        }

        for (path, method), operation_id in expected.items():
            with self.subTest(path=path, method=method):
                operation = self.operation(path, method)
                self.assertEqual(operation_id, operation["operationId"])
                if operation_id == "getCurrentCustomer":
                    self.assertEqual("FROZEN", operation["x-deskseed-contract-status"])
                else:
                    self.assertNotIn(
                        "x-deskseed-contract-status",
                        operation,
                        "FROZEN is reserved for routes present in the runtime document",
                    )

        self.assertNotIn(
            "/api/v1/customer/me",
            self.core_document["paths"],
            "CurrentCustomer must have one committed contract owner",
        )

    def test_authentication_request_operations_expose_generic_rate_limit_contract(self) -> None:
        paths = (
            "/api/v1/customer/registrations",
            "/api/v1/customer/auth/password-sessions",
            "/api/v1/customer/auth/magic-link-requests",
            "/api/v1/customer/auth/magic-link-sessions",
            "/api/v1/customer/auth/password-reset-requests",
            "/api/v1/customer/auth/password-resets",
        )

        for path in paths:
            with self.subTest(path=path):
                operation = self.operation(path)
                self.assertEqual(
                    "POSTGRESQL_PURPOSE_DESTINATION_NETWORK",
                    operation["x-deskseed-rate-limit"],
                )
                response = self.resolve(operation["responses"]["429"])
                self.assertIn("Retry-After", response["headers"])
                problem = self.resolve(response["content"]["application/problem+json"]["schema"])
                self.assertEqual(
                    "/problems/customer-authentication-rate-limited",
                    problem["properties"]["type"]["const"],
                )

    def test_password_login_and_magic_link_do_not_create_an_authentication_bypass(self) -> None:
        password_login = self.operation("/api/v1/customer/auth/password-sessions")
        invalid_credentials = self.resolve(password_login["responses"]["401"])
        invalid_problem = self.resolve(
            invalid_credentials["content"]["application/problem+json"]["schema"],
        )
        self.assertEqual(
            "/problems/customer-credentials-invalid",
            invalid_problem["properties"]["type"]["const"],
        )

        for path in (
            "/api/v1/customer/auth/magic-link-requests",
            "/api/v1/customer/auth/magic-link-sessions",
        ):
            with self.subTest(path=path):
                self.assertEqual(
                    "PASSWORDLESS_ONLY",
                    self.operation(path)["x-deskseed-authentication-eligibility"],
                )

    def test_current_customer_exposes_bounded_registration_and_credential_state(self) -> None:
        current = self.document["components"]["schemas"]["CurrentCustomer"]
        required = set(current["required"])
        self.assertTrue(
            {
                "companyName",
                "credentialState",
                "registrationState",
                "availableAuthenticationMethods",
            }.issubset(required),
        )
        self.assertNotIn("password", current["properties"])
        self.assertNotIn("passwordHash", current["properties"])
        self.assertNotIn("sessionId", current["properties"])

        methods = self.resolve(current["properties"]["availableAuthenticationMethods"]["items"])
        self.assertEqual({"MAGIC_LINK", "PASSWORD"}, set(methods["enum"]))

    def test_mutation_examples_are_synthetic_and_manually_owned(self) -> None:
        request_schema_names = {
            "CustomerRegistrationRequest",
            "CustomerRegistrationVerificationRequest",
            "CustomerPasswordSessionRequest",
            "MagicLinkRequest",
            "MagicLinkConsume",
            "PasswordlessRegistrationCompletionRequest",
            "CustomerPasswordResetRequest",
            "CustomerPasswordResetConsume",
            "ClaimRequest",
        }

        for schema_name in request_schema_names:
            with self.subTest(schema=schema_name):
                schema = self.document["components"]["schemas"][schema_name]
                self.assertEqual("MANUAL", schema["x-deskseed-documentation-review"])
                example = schema["example"]
                validator = Draft202012Validator(
                    {
                        "$schema": "https://json-schema.org/draft/2020-12/schema",
                        "components": self.document["components"],
                        "$ref": f"#/components/schemas/{schema_name}",
                    },
                )
                self.assertFalse(
                    list(validator.iter_errors(example)),
                    f"request example must satisfy {schema_name}",
                )
                for key in ("password", "newPassword"):
                    if key in example:
                        self.assertTrue(str(example[key]).startswith("synthetic "))
                if "token" in example:
                    self.assertIn("not-valid", str(example["token"]))

    def test_reset_revokes_sessions_without_implicitly_logging_in(self) -> None:
        operation = self.operation("/api/v1/customer/auth/password-resets")
        self.assertEqual("REVOKE_ALL", operation["x-deskseed-session-transition"])
        success = operation["responses"]["204"]
        self.assertNotIn("content", success)
        self.assertIn("Set-Cookie", success["headers"])


class CustomerConsentContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.document: dict[str, Any] = yaml.safe_load(CORE_CONTRACT.read_text(encoding="utf-8"))

    def operation(self, path: str, method: str = "get") -> dict[str, Any]:
        operation = self.document["paths"][path][method]
        self.assertIsInstance(operation, dict)
        return operation

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

    @staticmethod
    def parameter_refs(operation: dict[str, Any]) -> set[str]:
        return {
            str(parameter["$ref"])
            for parameter in operation.get("parameters", [])
            if isinstance(parameter, dict) and "$ref" in parameter
        }

    def validator(self, schema_name: str) -> Draft202012Validator:
        return Draft202012Validator(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "components": self.document["components"],
                "$ref": f"#/components/schemas/{schema_name}",
            },
        )

    def test_customer_and_admin_operation_family_is_complete_and_blueprint_only(self) -> None:
        expected = {
            ("/api/v1/customer/consent-policies", "get"): "listCurrentCustomerConsentPolicies",
            ("/api/v1/admin/customer-consent-policies", "get"): "listCustomerConsentPolicies",
            ("/api/v1/admin/customer-consent-policies", "post"): "createCustomerConsentPolicy",
            (
                "/api/v1/admin/customer-consent-policies/{policyId}",
                "get",
            ): "getCustomerConsentPolicy",
            (
                "/api/v1/admin/customer-consent-policies/{policyId}",
                "put",
            ): "updateCustomerConsentPolicyDraft",
            (
                "/api/v1/admin/customer-consent-policies/{policyId}/publish",
                "post",
            ): "publishCustomerConsentPolicy",
            (
                "/api/v1/admin/customer-consent-policies/{policyId}/archive",
                "post",
            ): "archiveCustomerConsentPolicy",
        }
        declared_blueprints = set(
            self.document["x-deskseed-staff-actor-consistency"]["blueprintOnlyOperationIds"],
        )

        for (path, method), operation_id in expected.items():
            with self.subTest(path=path, method=method):
                operation = self.operation(path, method)
                self.assertEqual(operation_id, operation["operationId"])
                self.assertNotIn("x-deskseed-contract-status", operation)
                if path.startswith("/api/v1/admin/"):
                    self.assertIn(operation_id, declared_blueprints)

    def test_admin_policy_boundary_requires_authority_actor_csrf_and_precondition(self) -> None:
        admin_operations = (
            self.operation("/api/v1/admin/customer-consent-policies"),
            self.operation("/api/v1/admin/customer-consent-policies", "post"),
            self.operation("/api/v1/admin/customer-consent-policies/{policyId}"),
            self.operation("/api/v1/admin/customer-consent-policies/{policyId}", "put"),
            self.operation("/api/v1/admin/customer-consent-policies/{policyId}/publish", "post"),
            self.operation("/api/v1/admin/customer-consent-policies/{policyId}/archive", "post"),
        )
        for operation in admin_operations:
            with self.subTest(operation=operation["operationId"]):
                self.assertEqual([{"staffSession": []}], operation["security"])
                self.assertEqual(
                    "customer-consent:manage",
                    operation["x-deskseed-required-authority"],
                )
                self.assertIn(
                    "#/components/parameters/ExpectedStaffActorHeader",
                    self.parameter_refs(operation),
                )

        writes = {
            "createCustomerConsentPolicy": self.operation(
                "/api/v1/admin/customer-consent-policies",
                "post",
            ),
            "updateCustomerConsentPolicyDraft": self.operation(
                "/api/v1/admin/customer-consent-policies/{policyId}",
                "put",
            ),
            "publishCustomerConsentPolicy": self.operation(
                "/api/v1/admin/customer-consent-policies/{policyId}/publish",
                "post",
            ),
            "archiveCustomerConsentPolicy": self.operation(
                "/api/v1/admin/customer-consent-policies/{policyId}/archive",
                "post",
            ),
        }
        expected_events = {
            "createCustomerConsentPolicy": "CUSTOMER_CONSENT_POLICY_CREATED",
            "updateCustomerConsentPolicyDraft": "CUSTOMER_CONSENT_POLICY_DRAFT_UPDATED",
            "publishCustomerConsentPolicy": "CUSTOMER_CONSENT_POLICY_PUBLISHED",
            "archiveCustomerConsentPolicy": "CUSTOMER_CONSENT_POLICY_ARCHIVED",
        }
        for operation_id, operation in writes.items():
            with self.subTest(operation=operation_id):
                refs = self.parameter_refs(operation)
                self.assertIn("#/components/parameters/CsrfHeader", refs)
                expected_precondition = (
                    "#/components/parameters/CustomerConsentIfNoneMatch"
                    if operation_id == "createCustomerConsentPolicy"
                    else "#/components/parameters/CustomerConsentIfMatch"
                )
                self.assertIn(expected_precondition, refs)
                self.assertEqual("FAIL_CLOSED", operation["x-deskseed-audit-failure"])
                self.assertEqual(
                    [expected_events[operation_id]],
                    operation["x-deskseed-security-audit-events"],
                )

    def test_public_projection_contains_only_current_customer_safe_version_fields(self) -> None:
        operation = self.operation("/api/v1/customer/consent-policies")
        self.assertNotIn("security", operation)
        response = self.resolve(operation["responses"]["200"])
        cache_control = self.resolve(response["headers"]["Cache-Control"])
        self.assertEqual("no-store", cache_control["schema"]["const"])

        schema = self.document["components"]["schemas"]["CurrentCustomerConsentPolicy"]
        self.assertFalse(schema["additionalProperties"])
        fields = set(schema["properties"])
        self.assertTrue(
            {
                "policyId",
                "policyKey",
                "version",
                "title",
                "document",
                "checksumSha256",
                "required",
                "displayOrder",
                "effectiveAt",
            }.issubset(fields),
        )
        self.assertTrue(
            fields.isdisjoint(
                {
                    "lifecycle",
                    "draft",
                    "versions",
                    "aggregateVersion",
                    "publishedByStaffId",
                    "acceptances",
                    "customerId",
                },
            ),
        )
        example = response["content"]["application/json"]["example"]
        self.assertFalse(list(self.validator("CurrentCustomerConsentPolicyList").iter_errors(example)))

    def test_consent_document_subset_rejects_active_or_unbounded_content_shapes(self) -> None:
        validator = self.validator("CustomerConsentDocument")
        valid = {
            "schemaVersion": 1,
            "blocks": [{"type": "paragraph", "text": "합성 정책 문서입니다."}],
        }
        invalid = {
            "raw html property": {
                "schemaVersion": 1,
                "blocks": [
                    {"type": "paragraph", "text": "합성 정책", "html": "<strong>unsafe</strong>"},
                ],
            },
            "script text": {
                "schemaVersion": 1,
                "blocks": [{"type": "paragraph", "text": "<script>unsafe</script>"}],
            },
            "attachment block": {
                "schemaVersion": 1,
                "blocks": [
                    {"type": "attachment", "attachmentId": "00000000-0000-4000-8000-000000000001"},
                ],
            },
            "code block": {
                "schemaVersion": 1,
                "blocks": [{"type": "code", "text": "alert(1)"}],
            },
            "unsafe url": {
                "schemaVersion": 1,
                "blocks": [{"type": "link", "text": "정책", "url": "http://example.test"}],
            },
            "control character": {
                "schemaVersion": 1,
                "blocks": [{"type": "paragraph", "text": "합성\u0007정책"}],
            },
        }

        self.assertFalse(list(validator.iter_errors(valid)))
        for description, document in invalid.items():
            with self.subTest(description=description):
                self.assertTrue(list(validator.iter_errors(document)))

    def test_admin_request_examples_are_manual_synthetic_and_schema_valid(self) -> None:
        for schema_name in (
            "CreateCustomerConsentPolicyRequest",
            "UpdateCustomerConsentPolicyDraftRequest",
        ):
            with self.subTest(schema=schema_name):
                schema = self.document["components"]["schemas"][schema_name]
                self.assertEqual("MANUAL", schema["x-deskseed-documentation-review"])
                if "policyKey" in schema["example"]:
                    self.assertTrue(schema["example"]["policyKey"].startswith("test-"))
                self.assertIn("합성", schema["example"]["title"])
                self.assertFalse(list(self.validator(schema_name).iter_errors(schema["example"])))

    def test_stale_policy_contract_is_non_mutating_and_returns_current_version(self) -> None:
        response = self.resolve(
            self.operation(
                "/api/v1/admin/customer-consent-policies/{policyId}",
                "put",
            )["responses"]["412"],
        )
        schema = self.resolve(response["content"]["application/problem+json"]["schema"])
        self.assertEqual(
            "/problems/customer-consent-precondition-failed",
            schema["allOf"][1]["properties"]["type"]["const"],
        )
        self.assertIn("ETag", response["headers"])


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
