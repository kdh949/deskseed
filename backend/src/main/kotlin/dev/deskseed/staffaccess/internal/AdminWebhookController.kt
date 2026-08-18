package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.webhook.CreateWebhookEndpointCommand
import dev.deskseed.webhook.PrivateWebhookTargetApproval
import dev.deskseed.webhook.RotateWebhookSecretCommand
import dev.deskseed.webhook.UpdateWebhookEndpointCommand
import dev.deskseed.webhook.WebhookAdministration
import dev.deskseed.webhook.WebhookDeliveryView
import dev.deskseed.webhook.WebhookDeliveryDetailView
import dev.deskseed.webhook.WebhookDeliverySummaryView
import dev.deskseed.webhook.WebhookEndpointIssue
import dev.deskseed.webhook.WebhookEndpointView
import dev.deskseed.webhook.WebhookPayloadPolicy
import dev.deskseed.webhook.WebhookReasonCommand
import dev.deskseed.webhook.WebhookSubscription
import dev.deskseed.webhook.WEBHOOK_PRIVATE_TARGET_APPROVE_AUTHORITY
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/integrations/webhooks")
@Validated
internal class AdminWebhookController(
    private val administration: WebhookAdministration,
) {
    @GetMapping
    fun list(): ResponseEntity<List<WebhookEndpointResponse>> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.list().map(WebhookEndpointView::toResponse))

    @PostMapping
    fun create(
        @Valid @RequestBody body: CreateWebhookEndpointRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<WebhookEndpointIssueResponse> {
        checkPrivateTargetAuthority(body.privateTargetApproval, principal)
        val issue = administration.create(body.toCommand(), request.actor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/integrations/webhooks/${issue.endpoint.id}"))
            .cacheControl(CacheControl.noStore())
            .body(issue.toResponse())
    }

    @GetMapping("/{endpointId}")
    fun get(@PathVariable endpointId: UUID): ResponseEntity<WebhookEndpointResponse> {
        val endpoint = administration.get(endpointId)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag(endpoint.etag())
            .body(endpoint.toResponse())
    }

    @PatchMapping("/{endpointId}")
    fun update(
        @PathVariable endpointId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: UpdateWebhookEndpointRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<WebhookEndpointResponse> {
        checkPrivateTargetAuthority(body.privateTargetApproval, principal)
        val endpoint = administration.update(endpointId, body.toCommand(ifMatch.version()), request.actor(principal))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(endpoint.etag()).body(endpoint.toResponse())
    }

    @PostMapping("/{endpointId}/deactivate")
    fun deactivate(
        @PathVariable endpointId: UUID,
        @Valid @RequestBody body: WebhookReasonRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<WebhookEndpointResponse> {
        val endpoint = administration.deactivate(endpointId, body.toCommand(), request.actor(principal))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(endpoint.etag()).body(endpoint.toResponse())
    }

    @PostMapping("/{endpointId}/archive")
    fun archive(
        @PathVariable endpointId: UUID,
        @Valid @RequestBody body: WebhookReasonRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<WebhookEndpointResponse> {
        val endpoint = administration.archive(endpointId, body.toCommand(), request.actor(principal))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(endpoint.etag()).body(endpoint.toResponse())
    }

    @PostMapping("/{endpointId}/rotate-secret")
    fun rotateSecret(
        @PathVariable endpointId: UUID,
        @Valid @RequestBody body: RotateWebhookSecretRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<WebhookEndpointIssueResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.rotateSecret(endpointId, body.toCommand(), request.actor(principal)).toResponse())

    @PostMapping("/{endpointId}/test-deliveries")
    fun createTestDelivery(
        @PathVariable endpointId: UUID,
        @Valid @RequestBody body: WebhookReasonRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<WebhookDeliveryResponse> = ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .body(administration.createTestDelivery(endpointId, body.toCommand(), request.actor(principal)).toResponse())

    @GetMapping("/{endpointId}/deliveries")
    fun listDeliveries(@PathVariable endpointId: UUID): ResponseEntity<List<WebhookDeliveryResponse>> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.listDeliveries(endpointId).map(WebhookDeliveryView::toResponse))

    @GetMapping("/{endpointId}/deliveries/{deliveryId}")
    fun getDelivery(
        @PathVariable endpointId: UUID,
        @PathVariable deliveryId: UUID,
    ): ResponseEntity<WebhookDeliveryDetailResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.getDeliveryDetail(endpointId, deliveryId).toResponse())

    @PostMapping("/{endpointId}/deliveries/{deliveryId}/replay")
    fun replay(
        @PathVariable endpointId: UUID,
        @PathVariable deliveryId: UUID,
        @Valid @RequestBody body: WebhookReasonRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<WebhookDeliveryResponse> = ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .body(administration.replayDelivery(endpointId, deliveryId, body.toCommand(), request.actor(principal)).toResponse())

    private fun checkPrivateTargetAuthority(approval: PrivateWebhookTargetApprovalRequest?, principal: StaffPrincipal) {
        if (approval != null && WEBHOOK_PRIVATE_TARGET_APPROVE_AUTHORITY !in principal.authorities) {
            throw AccessDeniedException("Private webhook target authority is required")
        }
    }

    private fun HttpServletRequest.actor(principal: StaffPrincipal): IntegrationAdminActor {
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return IntegrationAdminActor(principal.id, principal.displayName, context.source, context.requestId, context.correlationId)
    }
}

internal data class CreateWebhookEndpointRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotBlank @field:Size(max = 2048) val url: String,
    @field:NotEmpty @field:Size(max = 20) @field:Valid val subscriptions: Set<WebhookSubscriptionRequest>,
    @field:Valid val privateTargetApproval: PrivateWebhookTargetApprovalRequest? = null,
) {
    fun toCommand() = CreateWebhookEndpointCommand(name, url, subscriptions.map(WebhookSubscriptionRequest::toModel).toSet(), privateTargetApproval?.toModel())
}

internal data class UpdateWebhookEndpointRequest(
    @field:Size(min = 1, max = 100) val name: String? = null,
    @field:Size(min = 8, max = 2048) val url: String? = null,
    val enabled: Boolean? = null,
    @field:Size(min = 1, max = 20) @field:Valid val subscriptions: Set<WebhookSubscriptionRequest>? = null,
    @field:Valid val privateTargetApproval: PrivateWebhookTargetApprovalRequest? = null,
) {
    fun toCommand(expectedVersion: Long) = UpdateWebhookEndpointCommand(
        name, url, enabled, subscriptions?.map(WebhookSubscriptionRequest::toModel)?.toSet(), privateTargetApproval?.toModel(), expectedVersion,
    )
}

internal data class WebhookSubscriptionRequest(
    @field:NotBlank @field:Size(max = 160) val eventType: String,
    @field:Min(1) @field:Max(1) val version: Int,
    @field:NotNull val payloadPolicy: WebhookPayloadPolicy,
) {
    fun toModel() = WebhookSubscription(eventType, version, payloadPolicy)
}

internal data class PrivateWebhookTargetApprovalRequest(
    @field:NotBlank @field:Size(max = 253) val hostname: String,
    @field:Min(1) @field:Max(65_535) val port: Int,
    @field:NotEmpty @field:Size(max = 20) val cidrs: Set<@Size(min = 2, max = 64) String>,
    @field:NotBlank @field:Size(min = 3, max = 500) val reason: String,
) {
    fun toModel() = PrivateWebhookTargetApproval(hostname, port, cidrs, reason)
}

internal data class RotateWebhookSecretRequest(
    @field:Min(0) @field:Max(86_400) val overlapSeconds: Long,
    @field:NotBlank @field:Size(min = 3, max = 500) val reason: String,
) {
    fun toCommand() = RotateWebhookSecretCommand(overlapSeconds, reason)
}

internal data class WebhookReasonRequest(@field:NotBlank @field:Size(min = 3, max = 500) val reason: String) {
    fun toCommand() = WebhookReasonCommand(reason)
}

internal data class WebhookEndpointResponse(
    val id: UUID,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val subscriptions: List<WebhookSubscriptionResponse>,
    val targetClass: String,
    val health: WebhookHealthResponse,
    val deliverySummary: WebhookDeliverySummaryResponse,
    val archivedAt: Instant?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class WebhookSubscriptionResponse(val eventType: String, val version: Int, val payloadPolicy: String)
internal data class WebhookHealthResponse(
    val state: String,
    val cooldownUntil: Instant?,
    val consecutiveFailures: Int,
    val lastSucceededAt: Instant?,
    val lastFailedAt: Instant?,
)
internal data class WebhookDeliverySummaryResponse(
    val totalDeliveries: Long,
    val pendingDeliveries: Long,
    val inFlightDeliveries: Long,
    val retryScheduledDeliveries: Long,
    val succeededDeliveries: Long,
    val deadLetteredDeliveries: Long,
    val cancelledDeliveries: Long,
    val lastDeliveryAt: Instant?,
    val lastFailureAt: Instant?,
    val lastFailureCategory: String?,
)
internal data class WebhookEndpointIssueResponse(val endpoint: WebhookEndpointResponse, val secret: String, val secretKeyVersion: Int)
internal data class WebhookDeliveryResponse(
    val id: UUID,
    val eventId: UUID,
    val endpointId: UUID,
    val status: String,
    val attemptCount: Int,
    val nextAttemptAt: Instant?,
    val errorCategory: String?,
    val createdAt: Instant,
)
internal data class WebhookDeliveryDetailResponse(
    val delivery: WebhookDeliveryResponse,
    val attempts: List<WebhookDeliveryAttemptResponse>,
)
internal data class WebhookDeliveryAttemptResponse(
    val attemptNumber: Int,
    val requestTimestamp: Instant,
    val responseStatus: Int?,
    val latencyMillis: Long?,
    val errorCategory: String?,
    val completedAt: Instant?,
)

private fun WebhookEndpointView.toResponse() = WebhookEndpointResponse(
    id, name, url, enabled,
    subscriptions.map { WebhookSubscriptionResponse(it.eventType, it.version, it.payloadPolicy.name) },
    targetClass.name,
    WebhookHealthResponse(health.state.name, health.cooldownUntil, health.consecutiveFailures, health.lastSucceededAt, health.lastFailedAt),
    deliverySummary.toResponse(), archivedAt, version, createdAt, updatedAt,
)
private fun WebhookDeliverySummaryView.toResponse() = WebhookDeliverySummaryResponse(
    totalDeliveries, pendingDeliveries, inFlightDeliveries, retryScheduledDeliveries, succeededDeliveries, deadLetteredDeliveries, cancelledDeliveries,
    lastDeliveryAt, lastFailureAt, lastFailureCategory,
)
private fun WebhookEndpointIssue.toResponse() = WebhookEndpointIssueResponse(endpoint.toResponse(), secret, secretKeyVersion)
private fun WebhookDeliveryView.toResponse() = WebhookDeliveryResponse(
    id, eventId, endpointId, status.name, attemptCount, nextAttemptAt, errorCategory, createdAt,
)
private fun WebhookDeliveryDetailView.toResponse() = WebhookDeliveryDetailResponse(
    delivery.toResponse(),
    attempts.map { attempt ->
        WebhookDeliveryAttemptResponse(
            attempt.attemptNumber,
            attempt.requestTimestamp,
            attempt.responseStatus,
            attempt.latencyMillis,
            attempt.errorCategory,
            attempt.completedAt,
        )
    },
)
private fun WebhookEndpointView.etag() = "\"webhook-v$version\""
private fun String.version(): Long = removePrefix("\"").removeSuffix("\"").removePrefix("webhook-v").toLongOrNull()
    ?: throw IllegalArgumentException("Webhook If-Match is invalid")
