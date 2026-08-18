package dev.deskseed.webhook.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.integration.IntegrationNetworkPolicy
import dev.deskseed.webhook.CreateWebhookEndpointCommand
import dev.deskseed.webhook.PrivateWebhookTargetApproval
import dev.deskseed.webhook.RotateWebhookSecretCommand
import dev.deskseed.webhook.UpdateWebhookEndpointCommand
import dev.deskseed.webhook.WebhookAdministration
import dev.deskseed.webhook.WebhookConflictException
import dev.deskseed.webhook.WebhookDeliveryNotFoundException
import dev.deskseed.webhook.WebhookDeliveryAttemptView
import dev.deskseed.webhook.WebhookDeliveryDetailView
import dev.deskseed.webhook.WebhookDeliverySummaryView
import dev.deskseed.webhook.WebhookDeliveryStatus
import dev.deskseed.webhook.WebhookDeliveryView
import dev.deskseed.webhook.WebhookEndpointIssue
import dev.deskseed.webhook.WebhookEndpointNotFoundException
import dev.deskseed.webhook.WebhookEndpointView
import dev.deskseed.webhook.WebhookEventCatalog
import dev.deskseed.webhook.WebhookHealthState
import dev.deskseed.webhook.WebhookHealthView
import dev.deskseed.webhook.WebhookPayloadPolicy
import dev.deskseed.webhook.WebhookReasonCommand
import dev.deskseed.webhook.WebhookSubscription
import dev.deskseed.webhook.WebhookTargetClass
import dev.deskseed.webhook.WebhookTargetPolicy
import dev.deskseed.webhook.WebhookTargetValidator
import dev.deskseed.webhook.WEBHOOK_MANAGE_AUTHORITY
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
internal class JdbcWebhookAdministration(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val secretCipher: WebhookSecretCipher,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
    private val targetValidator: WebhookTargetValidator = WebhookTargetValidator(),
    private val random: SecureRandom = SecureRandom(),
) : WebhookAdministration {
    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional(readOnly = true)
    override fun list(): List<WebhookEndpointView> = jdbc.query(
        "select * from webhook_endpoints where archived_at is null order by created_at desc, id desc",
    ) { row, _ -> endpointView(row.toEndpoint()) }

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional(readOnly = true)
    override fun get(endpointId: UUID): WebhookEndpointView = endpointView(findEndpoint(endpointId, locked = false))

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional
    override fun create(command: CreateWebhookEndpointCommand, actor: IntegrationAdminActor): WebhookEndpointIssue {
        val now = Instant.now(clock)
        val validated = validateCreate(command)
        val endpointId = UUID.randomUUID()
        jdbc.update(
            """
            insert into webhook_endpoints (
                id, name, url, enabled, target_class, allowed_hostnames_json, allowed_ports_json, allowed_cidrs_json,
                health_state, cooldown_until, consecutive_failures, last_succeeded_at, last_failed_at,
                created_by_staff_id, created_at, updated_at, deactivated_at, version
            ) values (?, ?, ?, true, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
                      'CLOSED', null, 0, null, null, ?, ?, ?, null, 0)
            """.trimIndent(),
            endpointId, command.name.trim(), command.url.trim(), validated.policy.targetClass.name,
            objectMapper.writeValueAsString(validated.policy.allowedHostnames.sorted()),
            objectMapper.writeValueAsString(validated.policy.allowedPorts.sorted()),
            objectMapper.writeValueAsString(validated.policy.allowedCidrs.sorted()),
            actor.staffId, Timestamp.from(now), Timestamp.from(now),
        )
        writeSubscriptions(endpointId, command.subscriptions, now)
        val secret = newSecret()
        val secretId = UUID.randomUUID()
        val encrypted = secretCipher.encrypt(secret, secretId)
        jdbc.update(
            """
            insert into webhook_endpoint_secrets (
                id, endpoint_id, sequence, ciphertext, nonce, key_version, status, overlap_expires_at,
                created_by_staff_id, created_at, revoked_at
            ) values (?, ?, 1, ?, ?, ?, 'ACTIVE', null, ?, ?, null)
            """.trimIndent(),
            secretId, endpointId, encrypted.ciphertext, encrypted.nonce, encrypted.keyVersion, actor.staffId, Timestamp.from(now),
        )
        appendAudit("WEBHOOK_ENDPOINT_CREATED", endpointId, actor, mapOf("targetClass" to validated.policy.targetClass.name), now)
        return WebhookEndpointIssue(endpointView(findEndpoint(endpointId, locked = false)), secret, 1)
    }

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional
    override fun update(endpointId: UUID, command: UpdateWebhookEndpointCommand, actor: IntegrationAdminActor): WebhookEndpointView {
        val endpoint = findEndpoint(endpointId, locked = true)
        requireNotArchived(endpoint)
        if (endpoint.version != command.expectedVersion) throw WebhookConflictException("WEBHOOK_ENDPOINT_VERSION_MISMATCH", endpoint.version)
        require(command.name != null || command.url != null || command.enabled != null || command.subscriptions != null) {
            "Webhook update must change at least one field"
        }
        val now = Instant.now(clock)
        val target = when {
            command.url != null -> validateTarget(command.url, command.privateTargetApproval)
            command.privateTargetApproval != null -> validateTarget(endpoint.url, command.privateTargetApproval)
            else -> endpoint.target
        }
        command.subscriptions?.let(::validateSubscriptions)
        val changedName = command.name?.trim()?.also { require(it.isNotEmpty() && it.length <= 100) } ?: endpoint.name
        val changedUrl = command.url?.trim() ?: endpoint.url
        val changedEnabled = command.enabled ?: endpoint.enabled
        jdbc.update(
            """
            update webhook_endpoints set name = ?, url = ?, enabled = ?, target_class = ?,
                allowed_hostnames_json = cast(? as jsonb), allowed_ports_json = cast(? as jsonb), allowed_cidrs_json = cast(? as jsonb),
                updated_at = ?, version = version + 1
             where id = ? and version = ?
            """.trimIndent(),
            changedName, changedUrl, changedEnabled, target.targetClass.name,
            objectMapper.writeValueAsString(target.allowedHostnames.sorted()),
            objectMapper.writeValueAsString(target.allowedPorts.sorted()),
            objectMapper.writeValueAsString(target.allowedCidrs.sorted()),
            Timestamp.from(now), endpointId, endpoint.version,
        ).also { require(it == 1) { "Webhook endpoint update lost its version guard" } }
        command.subscriptions?.let {
            jdbc.update("delete from webhook_subscriptions where endpoint_id = ?", endpointId)
            writeSubscriptions(endpointId, it, now)
        }
        appendAudit("WEBHOOK_ENDPOINT_UPDATED", endpointId, actor, mapOf("targetClass" to target.targetClass.name), now)
        return endpointView(findEndpoint(endpointId, locked = false))
    }

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional
    override fun deactivate(endpointId: UUID, command: WebhookReasonCommand, actor: IntegrationAdminActor): WebhookEndpointView {
        requireReason(command.reason)
        val endpoint = findEndpoint(endpointId, locked = true)
        requireNotArchived(endpoint)
        val now = Instant.now(clock)
        if (endpoint.enabled) {
            jdbc.update(
                "update webhook_endpoints set enabled = false, deactivated_at = ?, updated_at = ?, version = version + 1 where id = ?",
                Timestamp.from(now), Timestamp.from(now), endpointId,
            )
            jdbc.update(
                "update webhook_deliveries set status = 'CANCELLED', next_attempt_at = null, updated_at = ?, completed_at = ? " +
                    "where endpoint_id = ? and status in ('PENDING', 'RETRY_SCHEDULED')",
                Timestamp.from(now), Timestamp.from(now), endpointId,
            )
            appendAudit("WEBHOOK_ENDPOINT_DEACTIVATED", endpointId, actor, emptyMap(), now)
        }
        return endpointView(findEndpoint(endpointId, locked = false))
    }

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional
    override fun archive(endpointId: UUID, command: WebhookReasonCommand, actor: IntegrationAdminActor): WebhookEndpointView {
        requireReason(command.reason)
        val endpoint = findEndpoint(endpointId, locked = true)
        if (endpoint.archivedAt != null) return endpointView(endpoint)
        if (endpoint.enabled) throw WebhookConflictException("WEBHOOK_ENDPOINT_MUST_BE_DEACTIVATED")
        val now = Instant.now(clock)
        jdbc.update(
            """
            update webhook_endpoints
               set deactivated_at = coalesce(deactivated_at, ?), archived_at = ?, updated_at = ?, version = version + 1
             where id = ? and archived_at is null and enabled = false
            """.trimIndent(),
            Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), endpointId,
        ).also { require(it == 1) { "Webhook endpoint archive lost its state guard" } }
        jdbc.update(
            """update webhook_deliveries set status = 'CANCELLED', next_attempt_at = null, updated_at = ?, completed_at = ?
                 where endpoint_id = ? and status in ('PENDING', 'RETRY_SCHEDULED')""",
            Timestamp.from(now), Timestamp.from(now), endpointId,
        )
        appendAudit("WEBHOOK_ENDPOINT_ARCHIVED", endpointId, actor, emptyMap(), now)
        return endpointView(findEndpoint(endpointId, locked = false))
    }

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional
    override fun rotateSecret(endpointId: UUID, command: RotateWebhookSecretCommand, actor: IntegrationAdminActor): WebhookEndpointIssue {
        require(command.overlapSeconds in 0..86_400) { "Webhook secret overlap is invalid" }
        requireReason(command.reason)
        requireNotArchived(findEndpoint(endpointId, locked = true))
        val now = Instant.now(clock)
        val current = jdbc.queryForMap(
            "select id, sequence from webhook_endpoint_secrets where endpoint_id = ? and status = 'ACTIVE' for update",
            endpointId,
        )
        val previousId = current["id"] as UUID
        val previousSequence = (current["sequence"] as Number).toInt()
        if (command.overlapSeconds == 0L) {
            jdbc.update(
                "update webhook_endpoint_secrets set status = 'REVOKED', revoked_at = ? where id = ?",
                Timestamp.from(now), previousId,
            )
        } else {
            jdbc.update(
                "update webhook_endpoint_secrets set status = 'RETIRING', overlap_expires_at = ? where id = ?",
                Timestamp.from(now.plusSeconds(command.overlapSeconds)), previousId,
            )
        }
        val secret = newSecret()
        val secretId = UUID.randomUUID()
        val encrypted = secretCipher.encrypt(secret, secretId)
        jdbc.update(
            """
            insert into webhook_endpoint_secrets (
                id, endpoint_id, sequence, ciphertext, nonce, key_version, status, overlap_expires_at,
                created_by_staff_id, created_at, revoked_at
            ) values (?, ?, ?, ?, ?, ?, 'ACTIVE', null, ?, ?, null)
            """.trimIndent(),
            secretId, endpointId, previousSequence + 1, encrypted.ciphertext, encrypted.nonce, encrypted.keyVersion,
            actor.staffId, Timestamp.from(now),
        )
        appendAudit("WEBHOOK_SECRET_ROTATED", endpointId, actor, mapOf("overlapSeconds" to command.overlapSeconds.toString()), now)
        return WebhookEndpointIssue(endpointView(findEndpoint(endpointId, locked = false)), secret, previousSequence + 1)
    }

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional
    override fun createTestDelivery(endpointId: UUID, command: WebhookReasonCommand, actor: IntegrationAdminActor): WebhookDeliveryView {
        requireReason(command.reason)
        val endpoint = findEndpoint(endpointId, locked = true)
        requireNotArchived(endpoint)
        if (!endpoint.enabled) throw WebhookConflictException("WEBHOOK_ENDPOINT_DISABLED")
        val now = Instant.now(clock)
        val view = insertDelivery(endpointId, endpoint.version, UUID.randomUUID(), "webhook.test", 1, "{}", now)
        appendAudit("WEBHOOK_TEST_DELIVERY_REQUESTED", endpointId, actor, emptyMap(), now)
        return view
    }

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional(readOnly = true)
    override fun listDeliveries(endpointId: UUID): List<WebhookDeliveryView> {
        findEndpoint(endpointId, locked = false)
        return jdbc.query(
            "select * from webhook_deliveries where endpoint_id = ? order by created_at desc, id desc",
            { row, _ -> row.toDelivery() }, endpointId,
        )
    }

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional(readOnly = true)
    override fun getDelivery(endpointId: UUID, deliveryId: UUID): WebhookDeliveryView = jdbc.query(
        "select * from webhook_deliveries where endpoint_id = ? and id = ?",
        { row, _ -> row.toDelivery() }, endpointId, deliveryId,
    ).singleOrNull() ?: throw WebhookDeliveryNotFoundException()

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional(readOnly = true)
    override fun getDeliveryDetail(endpointId: UUID, deliveryId: UUID): WebhookDeliveryDetailView {
        val delivery = getDelivery(endpointId, deliveryId)
        val attempts = jdbc.query(
            """
            select attempt_number, request_timestamp, response_status, latency_millis, error_category, completed_at
              from webhook_delivery_attempts
             where delivery_id = ?
             order by attempt_number desc
            """.trimIndent(),
            { row, _ ->
                WebhookDeliveryAttemptView(
                    attemptNumber = row.getInt("attempt_number"),
                    requestTimestamp = row.getTimestamp("request_timestamp").toInstant(),
                    responseStatus = row.getObject("response_status") as Int?,
                    latencyMillis = row.getObject("latency_millis") as Long?,
                    errorCategory = row.getString("error_category"),
                    completedAt = row.getTimestamp("completed_at")?.toInstant(),
                )
            },
            deliveryId,
        )
        return WebhookDeliveryDetailView(delivery, attempts)
    }

    @PreAuthorize("hasAuthority('$WEBHOOK_MANAGE_AUTHORITY')")
    @Transactional
    override fun replayDelivery(
        endpointId: UUID,
        deliveryId: UUID,
        command: WebhookReasonCommand,
        actor: IntegrationAdminActor,
    ): WebhookDeliveryView {
        requireReason(command.reason)
        val endpoint = findEndpoint(endpointId, locked = true)
        requireNotArchived(endpoint)
        if (!endpoint.enabled) throw WebhookConflictException("WEBHOOK_ENDPOINT_DISABLED")
        val delivery = jdbc.query(
            "select * from webhook_deliveries where endpoint_id = ? and id = ? for update",
            { row, _ -> row.toDelivery() }, endpointId, deliveryId,
        ).singleOrNull() ?: throw WebhookDeliveryNotFoundException()
        if (delivery.status != WebhookDeliveryStatus.DEAD_LETTERED) throw WebhookConflictException("WEBHOOK_DELIVERY_NOT_REPLAYABLE")
        val now = Instant.now(clock)
        jdbc.update(
            """
            update webhook_deliveries
               set status = 'PENDING', next_attempt_at = ?, error_category = null, completed_at = null,
                   updated_at = ?, version = version + 1
             where id = ? and endpoint_id = ? and status = 'DEAD_LETTERED'
            """.trimIndent(),
            Timestamp.from(now), Timestamp.from(now), deliveryId, endpointId,
        ).also { require(it == 1) { "Webhook replay lost its state guard" } }
        appendAudit("WEBHOOK_DELIVERY_REPLAY_REQUESTED", endpointId, actor, mapOf("deliveryId" to deliveryId.toString()), now)
        return getDelivery(endpointId, deliveryId)
    }

    private fun validateCreate(command: CreateWebhookEndpointCommand): ValidatedTarget {
        require(command.name.trim().isNotEmpty() && command.name.trim().length <= 100) { "Webhook name is invalid" }
        validateSubscriptions(command.subscriptions)
        return ValidatedTarget(validateTarget(command.url, command.privateTargetApproval))
    }

    private fun validateTarget(url: String, approval: PrivateWebhookTargetApproval?): WebhookTargetPolicy {
        val policy = approval?.let {
            requireReason(it.reason)
            IntegrationNetworkPolicy().validateCidrs(it.cidrs)
            WebhookTargetPolicy.privateApproved(it.hostname, it.port, it.cidrs)
        } ?: WebhookTargetPolicy.publicDefault()
        targetValidator.validate(url.trim(), policy)
        return policy
    }

    private fun validateSubscriptions(subscriptions: Set<WebhookSubscription>) {
        require(subscriptions.isNotEmpty() && subscriptions.size <= 20) { "Webhook subscriptions are invalid" }
        subscriptions.forEach { subscription ->
            require(WebhookEventCatalog.supports(subscription)) { "Webhook subscription is unsupported" }
        }
    }

    private fun writeSubscriptions(endpointId: UUID, subscriptions: Set<WebhookSubscription>, now: Instant) {
        subscriptions.sortedWith(compareBy(WebhookSubscription::eventType, WebhookSubscription::version)).forEach { subscription ->
            jdbc.update(
                "insert into webhook_subscriptions (endpoint_id, event_type, event_version, payload_policy, created_at) values (?, ?, ?, ?, ?)",
                endpointId, subscription.eventType, subscription.version, subscription.payloadPolicy.name, Timestamp.from(now),
            )
        }
    }

    private fun endpointView(endpoint: EndpointRow): WebhookEndpointView = WebhookEndpointView(
        id = endpoint.id,
        name = endpoint.name,
        url = endpoint.url,
        enabled = endpoint.enabled,
        subscriptions = jdbc.query(
            "select event_type, event_version, payload_policy from webhook_subscriptions where endpoint_id = ? order by event_type, event_version",
            { row, _ -> WebhookSubscription(row.getString(1), row.getInt(2), WebhookPayloadPolicy.valueOf(row.getString(3))) }, endpoint.id,
        ),
        targetClass = endpoint.target.targetClass,
        health = WebhookHealthView(endpoint.healthState, endpoint.cooldownUntil, endpoint.consecutiveFailures, endpoint.lastSucceededAt, endpoint.lastFailedAt),
        deliverySummary = deliverySummary(endpoint.id),
        archivedAt = endpoint.archivedAt,
        version = endpoint.version,
        createdAt = endpoint.createdAt,
        updatedAt = endpoint.updatedAt,
    )

    private fun findEndpoint(endpointId: UUID, locked: Boolean): EndpointRow = jdbc.query(
        "select * from webhook_endpoints where id = ?${if (locked) " for update" else ""}",
        { row, _ -> row.toEndpoint() }, endpointId,
    ).singleOrNull() ?: throw WebhookEndpointNotFoundException()

    private fun requireNotArchived(endpoint: EndpointRow) {
        if (endpoint.archivedAt != null) throw WebhookConflictException("WEBHOOK_ENDPOINT_ARCHIVED")
    }

    private fun deliverySummary(endpointId: UUID): WebhookDeliverySummaryView = jdbc.query(
        """
        select count(*) as total_deliveries,
               count(*) filter (where status = 'PENDING') as pending_deliveries,
               count(*) filter (where status = 'IN_FLIGHT') as in_flight_deliveries,
               count(*) filter (where status = 'RETRY_SCHEDULED') as retry_scheduled_deliveries,
               count(*) filter (where status = 'SUCCEEDED') as succeeded_deliveries,
               count(*) filter (where status = 'DEAD_LETTERED') as dead_lettered_deliveries,
               count(*) filter (where status = 'CANCELLED') as cancelled_deliveries,
               max(created_at) as last_delivery_at,
               max(updated_at) filter (where error_category is not null) as last_failure_at,
               (
                   select latest.error_category
                     from webhook_deliveries latest
                    where latest.endpoint_id = ? and latest.error_category is not null
                    order by latest.updated_at desc, latest.id desc
                    limit 1
               ) as last_failure_category
          from webhook_deliveries
         where endpoint_id = ?
        """.trimIndent(),
        { row, _ ->
            WebhookDeliverySummaryView(
                totalDeliveries = row.getLong("total_deliveries"),
                pendingDeliveries = row.getLong("pending_deliveries"),
                inFlightDeliveries = row.getLong("in_flight_deliveries"),
                retryScheduledDeliveries = row.getLong("retry_scheduled_deliveries"),
                succeededDeliveries = row.getLong("succeeded_deliveries"),
                deadLetteredDeliveries = row.getLong("dead_lettered_deliveries"),
                cancelledDeliveries = row.getLong("cancelled_deliveries"),
                lastDeliveryAt = row.getTimestamp("last_delivery_at")?.toInstant(),
                lastFailureAt = row.getTimestamp("last_failure_at")?.toInstant(),
                lastFailureCategory = row.getString("last_failure_category"),
            )
        },
        endpointId, endpointId,
    ).single()

    private fun insertDelivery(
        endpointId: UUID,
        endpointVersion: Long,
        eventId: UUID,
        eventType: String,
        eventVersion: Int,
        payload: String,
        now: Instant,
    ): WebhookDeliveryView {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into webhook_deliveries (
                id, endpoint_id, endpoint_version, event_id, event_type, event_version, payload_checksum, payload_json, status,
                attempt_count, next_attempt_at, lease_owner, lease_expires_at, error_category, created_at, updated_at, completed_at, version
            ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), 'PENDING', 0, ?, null, null, null, ?, ?, null, 0)
            """.trimIndent(),
            id, endpointId, endpointVersion, eventId, eventType, eventVersion, sha256(payload), payload, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
        )
        return getDelivery(endpointId, id)
    }

    private fun appendAudit(eventType: String, endpointId: UUID, actor: IntegrationAdminActor, metadata: Map<String, String>, now: Instant) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.STAFF,
                actorId = actor.staffId,
                actorDisplaySnapshot = actor.displayName,
                source = actor.source,
                targetType = "WEBHOOK_ENDPOINT",
                targetId = endpointId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = actor.requestId,
                correlationId = actor.correlationId,
                metadata = metadata,
                occurredAt = now,
            ),
        )
    }

    private fun java.sql.ResultSet.toEndpoint(): EndpointRow = EndpointRow(
        id = getObject("id", UUID::class.java),
        name = getString("name"),
        url = getString("url"),
        enabled = getBoolean("enabled"),
        target = WebhookTargetPolicy.fromStored(
            WebhookTargetClass.valueOf(getString("target_class")),
            strings(getString("allowed_hostnames_json")).toSet(),
            integers(getString("allowed_ports_json")).toSet(),
            strings(getString("allowed_cidrs_json")).toSet(),
        ),
        healthState = WebhookHealthState.valueOf(getString("health_state")),
        cooldownUntil = getTimestamp("cooldown_until")?.toInstant(),
        consecutiveFailures = getInt("consecutive_failures"),
        lastSucceededAt = getTimestamp("last_succeeded_at")?.toInstant(),
        lastFailedAt = getTimestamp("last_failed_at")?.toInstant(),
        archivedAt = getTimestamp("archived_at")?.toInstant(),
        version = getLong("version"),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )

    private fun java.sql.ResultSet.toDelivery(): WebhookDeliveryView = WebhookDeliveryView(
        id = getObject("id", UUID::class.java),
        eventId = getObject("event_id", UUID::class.java),
        endpointId = getObject("endpoint_id", UUID::class.java),
        status = WebhookDeliveryStatus.valueOf(getString("status")),
        attemptCount = getInt("attempt_count"),
        nextAttemptAt = getTimestamp("next_attempt_at")?.toInstant(),
        errorCategory = getString("error_category"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private fun strings(json: String): List<String> = objectMapper.readValue(json, Array<String>::class.java).toList()
    private fun integers(json: String): List<Int> = objectMapper.readValue(json, Array<Int>::class.java).toList()
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun newSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun requireReason(value: String) = require(value.trim().length in 3..500) { "Webhook reason is invalid" }

    private data class ValidatedTarget(val policy: WebhookTargetPolicy)
    private data class EndpointRow(
        val id: UUID,
        val name: String,
        val url: String,
        val enabled: Boolean,
        val target: WebhookTargetPolicy,
        val healthState: WebhookHealthState,
        val cooldownUntil: Instant?,
        val consecutiveFailures: Int,
        val lastSucceededAt: Instant?,
        val lastFailedAt: Instant?,
        val archivedAt: Instant?,
        val version: Long,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

}

private fun WebhookTargetPolicy.Companion.fromStored(
    targetClass: WebhookTargetClass,
    hostnames: Set<String>,
    ports: Set<Int>,
    cidrs: Set<String>,
): WebhookTargetPolicy = when (targetClass) {
    WebhookTargetClass.PUBLIC -> WebhookTargetPolicy.publicDefault()
    WebhookTargetClass.PRIVATE_APPROVED -> WebhookTargetPolicy.privateApproved(
        hostname = hostnames.single(), port = ports.single(), cidrs = cidrs,
    )
}
