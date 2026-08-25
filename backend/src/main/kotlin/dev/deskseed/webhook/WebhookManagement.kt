package dev.deskseed.webhook

import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.organization.StaffAuthorityCatalog
import java.time.Instant
import java.util.UUID

const val WEBHOOK_MANAGE_AUTHORITY = StaffAuthorityCatalog.INTEGRATION_CLIENT_MANAGE
const val WEBHOOK_PRIVATE_TARGET_APPROVE_AUTHORITY = StaffAuthorityCatalog.WEBHOOK_PRIVATE_TARGET_APPROVE

enum class WebhookPayloadPolicy { METADATA_ONLY, PUBLIC_CONTENT }
enum class WebhookHealthState { CLOSED, OPEN, HALF_OPEN }
enum class WebhookDeliveryStatus { PENDING, IN_FLIGHT, SUCCEEDED, RETRY_SCHEDULED, DEAD_LETTERED, CANCELLED }

data class WebhookSubscription(
    val eventType: String,
    val version: Int,
    val payloadPolicy: WebhookPayloadPolicy,
)

data class WebhookEventDescriptor(
    val eventType: String,
    val version: Int,
    val payloadPolicies: Set<WebhookPayloadPolicy>,
)

/**
 * Single allowlist for administratively persisted subscriptions and the v1 serializer boundary.
 * A descriptor does not grant access to raw event data; serializers remain responsible for the
 * public visibility and field allowlist checks before a delivery intent is materialized.
 */
object WebhookEventCatalog {
    val descriptors: List<WebhookEventDescriptor> = listOf(
        WebhookEventDescriptor("ticket.created", 1, WebhookPayloadPolicy.entries.toSet()),
        WebhookEventDescriptor("ticket.updated", 1, WebhookPayloadPolicy.entries.toSet()),
        WebhookEventDescriptor("ticket.comment.created", 1, WebhookPayloadPolicy.entries.toSet()),
        WebhookEventDescriptor("ticket.status.changed", 1, WebhookPayloadPolicy.entries.toSet()),
        WebhookEventDescriptor("ticket.sla.changed", 1, WebhookPayloadPolicy.entries.toSet()),
        WebhookEventDescriptor("attachment.ready", 1, WebhookPayloadPolicy.entries.toSet()),
        WebhookEventDescriptor("ticket.trigger.executed", 1, WebhookPayloadPolicy.entries.toSet()),
    ).also { values ->
        require(values.map { it.eventType to it.version }.distinct().size == values.size) {
            "Webhook event descriptors must not duplicate an event type and version"
        }
    }

    fun supports(subscription: WebhookSubscription): Boolean = supports(
        subscription.eventType,
        subscription.version,
        subscription.payloadPolicy,
    )

    fun supports(eventType: String, version: Int, payloadPolicy: WebhookPayloadPolicy): Boolean = descriptors.any {
        it.eventType == eventType && it.version == version && payloadPolicy in it.payloadPolicies
    }
}

data class PrivateWebhookTargetApproval(
    val hostname: String,
    val port: Int,
    val cidrs: Set<String>,
    val reason: String,
)

data class CreateWebhookEndpointCommand(
    val name: String,
    val url: String,
    val subscriptions: Set<WebhookSubscription>,
    val privateTargetApproval: PrivateWebhookTargetApproval? = null,
)

data class UpdateWebhookEndpointCommand(
    val name: String? = null,
    val url: String? = null,
    val enabled: Boolean? = null,
    val subscriptions: Set<WebhookSubscription>? = null,
    val privateTargetApproval: PrivateWebhookTargetApproval? = null,
    val expectedVersion: Long,
)

data class RotateWebhookSecretCommand(val overlapSeconds: Long, val reason: String)

data class WebhookReasonCommand(val reason: String)

data class WebhookHealthView(
    val state: WebhookHealthState,
    val cooldownUntil: Instant?,
    val consecutiveFailures: Int,
    val lastSucceededAt: Instant?,
    val lastFailedAt: Instant?,
)

/** Aggregate-only operational view; it never exposes delivery payload, headers, or diagnostics. */
data class WebhookDeliverySummaryView(
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

data class WebhookEndpointView(
    val id: UUID,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val subscriptions: List<WebhookSubscription>,
    val targetClass: WebhookTargetClass,
    val health: WebhookHealthView,
    val deliverySummary: WebhookDeliverySummaryView,
    val archivedAt: Instant?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class WebhookEndpointIssue(
    val endpoint: WebhookEndpointView,
    val secret: String,
    val secretKeyVersion: Int,
)

data class WebhookDeliveryView(
    val id: UUID,
    val eventId: UUID,
    val endpointId: UUID,
    val status: WebhookDeliveryStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant?,
    val errorCategory: String?,
    val createdAt: Instant,
)

/** Redacted activity detail. Payload, secret, raw response headers, and response body stay server-side. */
data class WebhookDeliveryAttemptView(
    val attemptNumber: Int,
    val requestTimestamp: Instant,
    val responseStatus: Int?,
    val latencyMillis: Long?,
    val errorCategory: String?,
    val completedAt: Instant?,
)

data class WebhookDeliveryDetailView(
    val delivery: WebhookDeliveryView,
    val attempts: List<WebhookDeliveryAttemptView>,
)

interface WebhookAdministration {
    fun list(): List<WebhookEndpointView>
    fun get(endpointId: UUID): WebhookEndpointView
    fun create(command: CreateWebhookEndpointCommand, actor: IntegrationAdminActor): WebhookEndpointIssue
    fun update(endpointId: UUID, command: UpdateWebhookEndpointCommand, actor: IntegrationAdminActor): WebhookEndpointView
    fun deactivate(endpointId: UUID, command: WebhookReasonCommand, actor: IntegrationAdminActor): WebhookEndpointView
    fun archive(endpointId: UUID, command: WebhookReasonCommand, actor: IntegrationAdminActor): WebhookEndpointView
    fun rotateSecret(endpointId: UUID, command: RotateWebhookSecretCommand, actor: IntegrationAdminActor): WebhookEndpointIssue
    fun createTestDelivery(endpointId: UUID, command: WebhookReasonCommand, actor: IntegrationAdminActor): WebhookDeliveryView
    fun listDeliveries(endpointId: UUID): List<WebhookDeliveryView>
    fun getDelivery(endpointId: UUID, deliveryId: UUID): WebhookDeliveryView
    fun getDeliveryDetail(endpointId: UUID, deliveryId: UUID): WebhookDeliveryDetailView
    fun replayDelivery(endpointId: UUID, deliveryId: UUID, command: WebhookReasonCommand, actor: IntegrationAdminActor): WebhookDeliveryView
}

class WebhookEndpointNotFoundException : RuntimeException()
class WebhookDeliveryNotFoundException : RuntimeException()
class WebhookConflictException(val code: String, val currentVersion: Long? = null) : RuntimeException()
