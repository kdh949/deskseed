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

data class WebhookEndpointView(
    val id: UUID,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val subscriptions: List<WebhookSubscription>,
    val targetClass: WebhookTargetClass,
    val health: WebhookHealthView,
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

interface WebhookAdministration {
    fun list(): List<WebhookEndpointView>
    fun get(endpointId: UUID): WebhookEndpointView
    fun create(command: CreateWebhookEndpointCommand, actor: IntegrationAdminActor): WebhookEndpointIssue
    fun update(endpointId: UUID, command: UpdateWebhookEndpointCommand, actor: IntegrationAdminActor): WebhookEndpointView
    fun deactivate(endpointId: UUID, command: WebhookReasonCommand, actor: IntegrationAdminActor): WebhookEndpointView
    fun rotateSecret(endpointId: UUID, command: RotateWebhookSecretCommand, actor: IntegrationAdminActor): WebhookEndpointIssue
    fun createTestDelivery(endpointId: UUID, command: WebhookReasonCommand, actor: IntegrationAdminActor): WebhookDeliveryView
    fun listDeliveries(endpointId: UUID): List<WebhookDeliveryView>
    fun getDelivery(endpointId: UUID, deliveryId: UUID): WebhookDeliveryView
    fun replayDelivery(endpointId: UUID, deliveryId: UUID, command: WebhookReasonCommand, actor: IntegrationAdminActor): WebhookDeliveryView
}

class WebhookEndpointNotFoundException : RuntimeException()
class WebhookDeliveryNotFoundException : RuntimeException()
class WebhookConflictException(val code: String, val currentVersion: Long? = null) : RuntimeException()
