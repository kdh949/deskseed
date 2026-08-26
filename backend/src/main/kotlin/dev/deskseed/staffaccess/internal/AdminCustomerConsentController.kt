package dev.deskseed.staffaccess.internal

import dev.deskseed.customerconsent.CanonicalCustomerConsentDocumentCodec
import dev.deskseed.customerconsent.CreateCustomerConsentPolicy
import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentLifecycle
import dev.deskseed.customerconsent.CustomerConsentPolicyActor
import dev.deskseed.customerconsent.CustomerConsentPolicyAdministration
import dev.deskseed.customerconsent.CustomerConsentPolicyDetail
import dev.deskseed.customerconsent.CustomerConsentPolicyDraftInput
import dev.deskseed.customerconsent.CustomerConsentPolicyPage
import dev.deskseed.customerconsent.CustomerConsentPolicySummary
import dev.deskseed.customerconsent.CustomerConsentPolicyVersion
import dev.deskseed.customerconsent.CustomerConsentPreconditionFailedException
import dev.deskseed.customerconsent.CustomerConsentValidationException
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.EXPECTED_STAFF_ACTOR_HEADER
import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.StaffRole
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/customer-consent-policies")
@Validated
internal class AdminCustomerConsentController(
    private val administration: CustomerConsentPolicyAdministration,
) {
    private val documents = CanonicalCustomerConsentDocumentCodec()

    @GetMapping
    fun list(
        @RequestParam(required = false) context: CustomerConsentContext?,
        @RequestParam(required = false) lifecycle: CustomerConsentLifecycle?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) size: Int,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<AdminCustomerConsentPolicyPageResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            administration.list(context, lifecycle, page, size, request.actor(principal, expectedActor)).toResponse(),
        )

    @PostMapping
    fun create(
        @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @Valid @RequestBody body: CreateCustomerConsentPolicyRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<AdminCustomerConsentPolicyResponse> {
        requireCreatePrecondition(ifNoneMatch)
        val created = administration.create(
            body.toCommand(documents),
            request.actor(principal, expectedActor),
        )
        return ResponseEntity.created(URI.create("/api/v1/admin/customer-consent-policies/${created.id}"))
            .cacheControl(CacheControl.noStore())
            .eTag(created.aggregateVersion.toString())
            .body(created.toResponse(documents))
    }

    @GetMapping("/{policyId}")
    fun get(
        @PathVariable policyId: UUID,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<AdminCustomerConsentPolicyResponse> =
        response(administration.get(policyId, request.actor(principal, expectedActor)))

    @PutMapping("/{policyId}")
    fun update(
        @PathVariable policyId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @Valid @RequestBody body: UpdateCustomerConsentPolicyDraftRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<AdminCustomerConsentPolicyResponse> {
        val actor = request.actor(principal, expectedActor)
        return response(administration.updateDraft(
            policyId,
            expectedVersion(ifMatch, policyId, actor),
            body.toDraft(documents),
            actor,
        ))
    }

    @PostMapping("/{policyId}/publish")
    fun publish(
        @PathVariable policyId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<AdminCustomerConsentPolicyResponse> {
        val actor = request.actor(principal, expectedActor)
        return response(administration.publish(policyId, expectedVersion(ifMatch, policyId, actor), actor))
    }

    @PostMapping("/{policyId}/archive")
    fun archive(
        @PathVariable policyId: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<AdminCustomerConsentPolicyResponse> {
        val actor = request.actor(principal, expectedActor)
        return response(administration.archive(policyId, expectedVersion(ifMatch, policyId, actor), actor))
    }

    private fun response(detail: CustomerConsentPolicyDetail): ResponseEntity<AdminCustomerConsentPolicyResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(detail.aggregateVersion.toString())
            .body(detail.toResponse(documents))

    private fun HttpServletRequest.actor(principal: StaffPrincipal, expectedActor: UUID): CustomerConsentPolicyActor {
        if (principal.id != expectedActor) throw CustomerConsentValidationException("Expected staff actor is invalid")
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return CustomerConsentPolicyActor(
            principal.id, principal.displayName, principal.role == StaffRole.ADMIN, principal.authorities,
            context.source, context.requestId, context.correlationId,
        )
    }

    private fun requireCreatePrecondition(value: String?) {
        if (value != "*") throw CustomerConsentPreconditionFailedException(0)
    }

    private fun expectedVersion(value: String?, policyId: UUID, actor: CustomerConsentPolicyActor): Long {
        if (value == null) {
            throw CustomerConsentPreconditionFailedException(administration.get(policyId, actor).aggregateVersion)
        }
        return ETAG.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw CustomerConsentValidationException("If-Match must be a quoted decimal version")
    }

    private companion object {
        val ETAG = Regex("\\\"(\\d+)\\\"")
    }
}

internal data class CreateCustomerConsentPolicyRequest(
    @field:NotBlank
    @field:Size(max = 80)
    @field:Pattern(regexp = "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
    val policyKey: String,
    @field:NotNull val context: CustomerConsentContext,
    @field:NotBlank @field:Size(max = 200) @field:Pattern(regexp = SAFE_TEXT) val title: String,
    @field:NotNull val document: JsonNode,
    val required: Boolean,
    @field:Min(0) @field:Max(10_000) val displayOrder: Int,
) {
    fun toCommand(documents: CanonicalCustomerConsentDocumentCodec) = CreateCustomerConsentPolicy(
        policyKey,
        context,
        CustomerConsentPolicyDraftInput(title, documents.decode(document), required, displayOrder),
    )
}

internal data class UpdateCustomerConsentPolicyDraftRequest(
    @field:NotBlank @field:Size(max = 200) @field:Pattern(regexp = SAFE_TEXT) val title: String,
    @field:NotNull val document: JsonNode,
    val required: Boolean,
    @field:Min(0) @field:Max(10_000) val displayOrder: Int,
) {
    fun toDraft(documents: CanonicalCustomerConsentDocumentCodec) =
        CustomerConsentPolicyDraftInput(title, documents.decode(document), required, displayOrder)
}

private const val SAFE_TEXT = "^[^<>\\x00-\\x1F\\x7F]*$"

internal data class AdminCustomerConsentPolicyPageResponse(
    val items: List<AdminCustomerConsentPolicySummaryResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

internal data class AdminCustomerConsentPolicySummaryResponse(
    val id: UUID,
    val policyKey: String,
    val context: CustomerConsentContext,
    val lifecycle: CustomerConsentLifecycle,
    val aggregateVersion: Long,
    val publishedVersion: Int?,
    val required: Boolean,
    val displayOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class AdminCustomerConsentPolicyResponse(
    val id: UUID,
    val policyKey: String,
    val context: CustomerConsentContext,
    val lifecycle: CustomerConsentLifecycle,
    val aggregateVersion: Long,
    val draft: CustomerConsentPolicyDraftResponse,
    val publishedVersion: CustomerConsentPolicyVersionResponse?,
    val versions: List<CustomerConsentPolicyVersionResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class CustomerConsentPolicyDraftResponse(
    val title: String,
    val document: Map<String, Any>,
    val required: Boolean,
    val displayOrder: Int,
    val draftVersion: Int,
    val updatedAt: Instant,
)

internal data class CustomerConsentPolicyVersionResponse(
    val policyId: UUID,
    val policyKey: String,
    val version: Int,
    val title: String,
    val document: Map<String, Any>,
    val plainText: String,
    val checksumSha256: String,
    val required: Boolean,
    val displayOrder: Int,
    val effectiveAt: Instant,
    val publishedByStaffId: UUID,
    val publishedByDisplayName: String,
    val publishedAt: Instant,
)

private fun CustomerConsentPolicyPage.toResponse() = AdminCustomerConsentPolicyPageResponse(
    items.map(CustomerConsentPolicySummary::toResponse), page, size, totalCount, totalPages,
)

private fun CustomerConsentPolicySummary.toResponse() = AdminCustomerConsentPolicySummaryResponse(
    id, policyKey, context, lifecycle, aggregateVersion, publishedVersion, required, displayOrder, createdAt, updatedAt,
)

private fun CustomerConsentPolicyDetail.toResponse(
    documents: CanonicalCustomerConsentDocumentCodec,
): AdminCustomerConsentPolicyResponse = AdminCustomerConsentPolicyResponse(
    id,
    policyKey,
    context,
    lifecycle,
    aggregateVersion,
    CustomerConsentPolicyDraftResponse(
        draft.title, documents.encode(draft.document), draft.required, draft.displayOrder, draft.version, updatedAt,
    ),
    publishedVersion?.toResponse(policyKey, documents),
    versions.map { it.toResponse(policyKey, documents) },
    createdAt,
    updatedAt,
)

private fun CustomerConsentPolicyVersion.toResponse(
    policyKey: String,
    documents: CanonicalCustomerConsentDocumentCodec,
) = CustomerConsentPolicyVersionResponse(
    policyId, policyKey, version, title, documents.encode(document), plainText, checksumSha256, required, displayOrder,
    effectiveAt, publishedByStaffId, publishedByDisplay, publishedAt,
)
