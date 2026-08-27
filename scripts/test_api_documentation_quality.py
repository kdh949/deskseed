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

    def test_password_primary_operation_family_tracks_runtime_delivery_per_operation(self) -> None:
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
                "/api/v1/customer/auth/magic-link-requests",
                "post",
            ): "requestCustomerMagicLink",
            (
                "/api/v1/customer/auth/magic-link-sessions",
                "post",
            ): "consumeCustomerMagicLink",
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
                if operation_id in {
                    "requestCustomerRegistration",
                    "verifyCustomerRegistration",
                    "createCustomerPasswordSession",
                    "requestCustomerPasswordReset",
                    "resetCustomerPassword",
                    "requestCustomerMagicLink",
                    "consumeCustomerMagicLink",
                    "completePasswordlessCustomerRegistration",
                    "getCurrentCustomer",
                }:
                    self.assertEqual("FROZEN", operation["x-deskseed-contract-status"])
                else:
                    self.assertNotIn(
                        "x-deskseed-contract-status",
                        operation,
                        "FROZEN is reserved for runtime-compatible operation semantics",
                    )

        self.assertNotIn(
            "/api/v1/customer/me",
            self.core_document["paths"],
            "CurrentCustomer must have one committed contract owner",
        )

    def test_authentication_request_operations_expose_generic_rate_limit_contract(self) -> None:
        operations = (
            ("/api/v1/customer/registrations", "post"),
            ("/api/v1/customer/registration-verifications", "post"),
            ("/api/v1/customer/auth/password-sessions", "post"),
            ("/api/v1/customer/auth/magic-link-requests", "post"),
            ("/api/v1/customer/auth/magic-link-sessions", "post"),
            ("/api/v1/customer/me/registration", "put"),
            ("/api/v1/customer/auth/password-reset-requests", "post"),
            ("/api/v1/customer/auth/password-resets", "post"),
        )

        for path, method in operations:
            with self.subTest(path=path, method=method):
                operation = self.operation(path, method)
                self.assertEqual(
                    "PURPOSE_DESTINATION_NETWORK",
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
                operation = self.operation(path)
                self.assertEqual(
                    "PASSWORDLESS_ONLY",
                    operation["x-deskseed-authentication-eligibility"],
                )
                self.assertEqual("FROZEN", operation["x-deskseed-contract-status"])

        magic_request = self.operation("/api/v1/customer/auth/magic-link-requests")
        self.assertNotIn("rate-limit 결과와 관계없이", magic_request["description"])
        self.assertIn("동일한 limiter 조건", magic_request["description"])

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

    def test_magic_link_consume_validation_matches_generic_invalid_proof_boundary(self) -> None:
        token = self.document["components"]["schemas"]["MagicLinkConsume"]["properties"]["token"]
        self.assertEqual(1, token["minLength"])
        self.assertEqual(256, token["maxLength"])
        validator = Draft202012Validator(token)
        self.assertFalse(list(validator.iter_errors("malformed-token-value")))
        self.assertTrue(list(validator.iter_errors("")))
        self.assertTrue(list(validator.iter_errors(" ")))
        self.assertTrue(list(validator.iter_errors("a\n")))
        self.assertTrue(list(validator.iter_errors("a" * 257)))

    def test_reset_revokes_sessions_without_implicitly_logging_in(self) -> None:
        operation = self.operation("/api/v1/customer/auth/password-resets")
        self.assertEqual("REVOKE_ALL", operation["x-deskseed-session-transition"])
        success = operation["responses"]["204"]
        self.assertNotIn("content", success)
        self.assertIn("Set-Cookie", success["headers"])

    def test_fixed_customer_security_problem_examples_match_the_closed_runtime_shape(self) -> None:
        problem_schema_names = (
            "InvalidCustomerIdentityRequestProblem",
            "InvalidCustomerCredentialsProblem",
            "InvalidCustomerOneTimeProofProblem",
            "CustomerSessionRequiredProblem",
            "CustomerCsrfRejectedProblem",
            "CustomerRegistrationConflictProblem",
            "CustomerAuthenticationRateLimitedProblem",
            "CustomerAuthenticationUnavailableProblem",
        )

        for schema_name in problem_schema_names:
            with self.subTest(schema=schema_name):
                schema = self.document["components"]["schemas"][schema_name]
                self.assertFalse(schema["additionalProperties"])
                self.assertTrue({"detail", "instance"}.issubset(schema["required"]))
                self.assertEqual(500, schema["properties"]["detail"]["maxLength"])
                self.assertEqual(2048, schema["properties"]["instance"]["maxLength"])
                validator = Draft202012Validator(
                    {
                        "$schema": "https://json-schema.org/draft/2020-12/schema",
                        "components": self.document["components"],
                        "$ref": f"#/components/schemas/{schema_name}",
                    },
                )
                self.assertFalse(
                    list(validator.iter_errors(schema["example"])),
                    f"problem example must satisfy {schema_name}",
                )


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

    def test_customer_and_admin_operation_family_tracks_runtime_freeze(self) -> None:
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
                self.assertEqual("FROZEN", operation["x-deskseed-contract-status"])
                self.assertNotIn(operation_id, declared_blueprints)

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

    def test_admin_policy_reads_declare_stable_storage_unavailable_problem(self) -> None:
        reads = (
            self.operation("/api/v1/admin/customer-consent-policies"),
            self.operation("/api/v1/admin/customer-consent-policies/{policyId}"),
        )
        for operation in reads:
            with self.subTest(operation=operation["operationId"]):
                self.assertEqual(
                    "#/components/responses/CustomerConsentUnavailable",
                    operation["responses"]["503"]["$ref"],
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

    def test_draft_has_no_client_selected_effective_time_and_publish_is_immediate(self) -> None:
        draft = self.document["components"]["schemas"]["CustomerConsentDraftInput"]
        self.assertNotIn("effectiveAt", draft["required"])
        self.assertNotIn("effectiveAt", draft["properties"])

        publish = self.operation(
            "/api/v1/admin/customer-consent-policies/{policyId}/publish",
            "post",
        )
        self.assertEqual("PUBLISHED_AT", publish["x-deskseed-effective-at"])
        self.assertIn("effectiveAt = publishedAt", publish["description"])

    def test_consent_document_declares_aggregate_and_transport_limits(self) -> None:
        document = self.document["components"]["schemas"]["CustomerConsentDocument"]
        self.assertEqual(
            50_000,
            document["x-deskseed-canonical-plain-text-max-characters"],
        )
        self.assertEqual(
            200_000,
            document["x-deskseed-canonical-plain-text-max-utf8-bytes"],
        )
        self.assertIn("canonicalization", document["description"])

        for operation in (
            self.operation("/api/v1/admin/customer-consent-policies", "post"),
            self.operation("/api/v1/admin/customer-consent-policies/{policyId}", "put"),
        ):
            with self.subTest(operation=operation["operationId"]):
                self.assertEqual(262_144, operation["x-deskseed-request-body-max-bytes"])

    def test_publish_serializes_the_twenty_current_policy_cap_per_context(self) -> None:
        current_list = self.document["components"]["schemas"][
            "CurrentCustomerConsentPolicyList"
        ]
        self.assertEqual(20, current_list["properties"]["policies"]["maxItems"])

        publish = self.operation(
            "/api/v1/admin/customer-consent-policies/{policyId}/publish",
            "post",
        )
        self.assertEqual(
            {"contextMaximum": 20, "enforcement": "PUBLISH_TRANSACTION_CONTEXT_SERIALIZATION"},
            publish["x-deskseed-current-policy-cap"],
        )
        self.assertIn("20", publish["description"])
        self.assertIn("직렬화", publish["description"])

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


class CustomerRequestFormContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.document: dict[str, Any] = yaml.safe_load(CORE_CONTRACT.read_text(encoding="utf-8"))

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

    def validator(self, schema_name: str) -> Draft202012Validator:
        return Draft202012Validator(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "components": self.document["components"],
                "$ref": f"#/components/schemas/{schema_name}",
            },
        )

    def test_candidate_projection_is_blueprint_only_and_has_no_ticket_view_side_effect(self) -> None:
        operation = self.operation("/api/v1/customer/ticket-form-projections")

        self.assertEqual("projectCustomerTicketForm", operation["operationId"])
        self.assertNotIn("x-deskseed-contract-status", operation)
        self.assertEqual("NONE", operation["x-deskseed-side-effects"])
        self.assertEqual("NONE", operation["x-deskseed-semantic-ticket-view"])
        self.assertIn("not an authorization token", operation["description"])
        self.assertEqual([{}], operation["security"])

    def test_initial_projection_is_blueprint_only_and_fixed_to_customer_requests(self) -> None:
        operation = self.operation("/api/v1/customer/ticket-forms", "get")

        self.assertNotIn("x-deskseed-contract-status", operation)
        self.assertEqual(["formId"], [parameter["name"] for parameter in operation["parameters"]])
        self.assertIn("CUSTOMER_REQUEST", operation["description"])

    def test_customer_projection_schemas_are_closed_and_exclude_staff_metadata(self) -> None:
        projection = self.document["components"]["schemas"]["CustomerTicketFormProjection"]
        projected_field = self.document["components"]["schemas"]["CustomerProjectedTicketField"]
        field = self.document["components"]["schemas"]["CustomerTicketFieldDefinition"]
        option = self.document["components"]["schemas"]["CustomerTicketFieldOption"]

        for schema in (projection, projected_field, field, option):
            self.assertFalse(schema["additionalProperties"])
        self.assertTrue(
            {
                "staffLabel",
                "staffDescription",
                "searchable",
                "analyticsEligible",
                "sensitive",
                "agentVisible",
                "agentEditable",
            }.isdisjoint(field["properties"]),
        )

    def test_request_creation_replaces_anonymous_and_privacy_consent_legacy_contracts(self) -> None:
        operation = self.operation("/api/v1/requests")
        schemas = self.document["components"]["schemas"]
        json_schema = operation["requestBody"]["content"]["application/json"]["schema"]

        self.assertEqual("#/components/schemas/CreateCustomerRequest", json_schema["$ref"])
        self.assertNotIn("CreateAnonymousRequest", schemas)
        self.assertNotIn("CreateAnonymousRequestMultipart", schemas)
        self.assertNotIn("CreateAnonymousRequestResult", schemas)
        self.assertNotIn("privacyConsent", str(operation))
        self.assertNotIn("privacyConsent", str(schemas["CreateCustomerRequest"]))
        self.assertNotIn("x-deskseed-contract-status", operation)

    def test_initial_request_has_stable_command_replay_and_conflict_contract(self) -> None:
        operation = self.operation("/api/v1/requests")
        request = self.document["components"]["schemas"]["CreateCustomerRequest"]
        result = self.document["components"]["schemas"]["CreateCustomerRequestResult"]

        self.assertIn("clientCommandId", request["required"])
        self.assertEqual("uuid", request["properties"]["clientCommandId"]["format"])
        self.assertEqual(
            {
                "identity": "CLIENT_COMMAND_ID",
                "scope": "CUSTOMER_OR_ANONYMOUS_DESTINATION",
                "payloadHash": "CANONICAL_REQUEST_AND_ORDERED_ATTACHMENT_MANIFEST",
                "samePayload": "SAME_TICKET_FRESH_ACCESS_GRANT",
                "differentPayload": "NON_MUTATING_409",
                "concurrency": "SINGLE_WINNER",
                "receiptRetention": "P7D",
            },
            operation["x-deskseed-idempotency"],
        )
        self.assertIn("replayed", result["required"])
        self.assertEqual("boolean", result["properties"]["replayed"]["type"])

        conflict = self.resolve(operation["responses"]["409"])
        conflict_refs = {
            schema["$ref"]
            for schema in conflict["content"]["application/problem+json"]["schema"]["anyOf"]
        }
        self.assertIn("#/components/schemas/CustomerRequestCommandConflictProblem", conflict_refs)

    def test_anonymous_multipart_uses_server_only_planned_customer_ownership(self) -> None:
        multipart = self.document["components"]["schemas"]["CreateCustomerRequestMultipart"]

        self.assertEqual(
            "SERVER_PLANNED_CUSTOMER_UUID",
            multipart["x-deskseed-anonymous-attachment-owner"],
        )
        self.assertIn("plannedCustomerId", multipart["description"])
        self.assertIn("Customer row를 만들지", multipart["description"])
        self.assertIn("같은 최종 transaction", multipart["description"])

    def test_form_version_freezes_semantics_but_not_display_copy(self) -> None:
        projection = self.document["components"]["schemas"]["CustomerTicketFormProjection"]
        snapshot = projection["x-deskseed-form-version-snapshot"]
        self.assertEqual(
            [
                "FIELD_AND_OPTION_IDENTITIES",
                "FIELD_TYPE_AND_VALIDATION",
                "OPTION_SET_AND_ORDER",
                "CUSTOMER_VISIBILITY_EDITABILITY_REQUIREDNESS",
                "PLACEMENT_ORDER_AND_CONDITION_RULES",
            ],
            snapshot["frozen"],
        )
        self.assertEqual("CURRENT_NOT_HISTORICAL", snapshot["displayLabelAndDescription"])
        self.assertEqual("NEW_ID_REQUIRED", snapshot["semanticChange"])

        field = self.document["components"]["schemas"]["CustomerTicketFieldDefinition"]
        option = self.document["components"]["schemas"]["CustomerTicketFieldOption"]
        self.assertIn("validation", field["required"])
        self.assertIn("order", option["required"])
        self.assertIn("snapshot 대상이 아닙니다", field["properties"]["label"]["description"])
        self.assertIn("snapshot 대상이 아닙니다", option["properties"]["label"]["description"])

    def test_json_and_multipart_use_the_same_customer_request_domain_command(self) -> None:
        operation = self.operation("/api/v1/requests")
        content = operation["requestBody"]["content"]
        multipart = self.resolve(content["multipart/form-data"]["schema"])

        self.assertEqual(
            "#/components/schemas/CreateCustomerRequest",
            content["application/json"]["schema"]["$ref"],
        )
        self.assertEqual(
            "#/components/schemas/CreateCustomerRequest",
            multipart["properties"]["request"]["$ref"],
        )
        self.assertEqual(["request"], multipart["required"])
        self.assertEqual(5, multipart["properties"]["attachments"]["maxItems"])
        self.assertEqual(
            "application/json",
            content["multipart/form-data"]["encoding"]["request"]["contentType"],
        )

    def test_create_request_pairing_typed_values_and_policy_versions_are_schema_valid(self) -> None:
        request = self.document["components"]["schemas"]["CreateCustomerRequest"]
        example = request["example"]

        self.assertEqual("MANUAL", request["x-deskseed-documentation-review"])
        self.assertFalse(request["additionalProperties"])
        self.assertEqual(["formVersion"], request["dependentRequired"]["formId"])
        self.assertEqual(["formId"], request["dependentRequired"]["formVersion"])
        self.assertEqual(
            "^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$",
            request["properties"]["fieldValues"]["propertyNames"]["pattern"],
        )
        self.assertTrue({"subject", "message", "fieldValues", "acceptedPolicies"}.issubset(request["required"]))
        self.assertFalse(list(self.validator("CreateCustomerRequest").iter_errors(example)))

        missing_version = copy.deepcopy(example)
        missing_version.pop("formVersion")
        self.assertTrue(list(self.validator("CreateCustomerRequest").iter_errors(missing_version)))

        invalid_key = copy.deepcopy(example)
        invalid_key["fieldValues"] = {"StaffOnly": {"shortTextValue": "protected"}}
        self.assertTrue(list(self.validator("CreateCustomerRequest").iter_errors(invalid_key)))

        policy = self.document["components"]["schemas"]["AcceptedCustomerPolicyVersion"]
        self.assertFalse(policy["additionalProperties"])
        self.assertEqual({"policyKey", "version"}, set(policy["required"]))

    def test_customer_field_values_accept_exactly_one_known_typed_property(self) -> None:
        validator = self.validator("CustomerTicketFieldValue")
        valid_values = (
            {"booleanValue": True},
            {"numberValue": 3.5},
            {"optionId": "00000000-0000-4000-8000-000000000101"},
            {"shortTextValue": "ORD-2026-1042"},
            {"longTextValue": "합성 테스트 문의의 추가 설명입니다."},
        )
        invalid_values = (
            {"optionId": "00000000-0000-4000-8000-000000000101", "shortTextValue": "mixed"},
            {"staffValue": "protected"},
            {},
        )

        for value in valid_values:
            with self.subTest(valid=value):
                self.assertFalse(list(validator.iter_errors(value)))
        for value in invalid_values:
            with self.subTest(invalid=value):
                self.assertTrue(list(validator.iter_errors(value)))

    def test_candidate_and_create_use_stable_existence_safe_problem_catalog(self) -> None:
        expected = {
            ("/api/v1/customer/ticket-form-projections", "400"): (
                "#/components/responses/CustomerTicketFormValidation",
                "/problems/customer-ticket-form-validation-failed",
            ),
            ("/api/v1/customer/ticket-form-projections", "404"): (
                "#/components/responses/CustomerTicketFormUnavailable",
                "/problems/customer-ticket-form-unavailable",
            ),
            ("/api/v1/customer/ticket-form-projections", "409"): (
                "#/components/responses/CustomerTicketFormVersionConflict",
                "/problems/customer-ticket-form-version-conflict",
            ),
            ("/api/v1/requests", "400"): (
                "#/components/responses/CustomerRequestValidation",
                "/problems/customer-request-validation-failed",
            ),
            ("/api/v1/requests", "503"): (
                "#/components/responses/CustomerRequestConfigurationUnavailable",
                "/problems/customer-request-configuration-unavailable",
            ),
        }

        for (path, status), (response_ref, problem_type) in expected.items():
            with self.subTest(path=path, status=status):
                response_value = self.operation(path)["responses"][status]
                self.assertEqual(response_ref, response_value["$ref"])
                response = self.resolve(response_value)
                schema = self.resolve(response["content"]["application/problem+json"]["schema"])
                self.assertEqual(problem_type, schema["allOf"][1]["properties"]["type"]["const"])
                self.assertEqual("no-store", response["headers"]["Cache-Control"]["schema"]["const"])

        conflict_value = self.operation("/api/v1/requests")["responses"]["409"]
        self.assertEqual("#/components/responses/CustomerRequestConflict", conflict_value["$ref"])
        conflict = self.resolve(conflict_value)
        conflict_types = {
            self.resolve(schema)["allOf"][1]["properties"]["type"]["const"]
            for schema in conflict["content"]["application/problem+json"]["schema"]["anyOf"]
        }
        self.assertEqual(
            {
                "/problems/customer-request-configuration-conflict",
                "/problems/customer-request-command-conflict",
            },
            conflict_types,
        )
        self.assertEqual("no-store", conflict["headers"]["Cache-Control"]["schema"]["const"])


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
