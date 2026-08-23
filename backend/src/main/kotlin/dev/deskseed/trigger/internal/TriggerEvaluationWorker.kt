package dev.deskseed.trigger.internal

import dev.deskseed.eventpublication.DomainEventAppend
import dev.deskseed.eventpublication.DomainEventEnvelope
import dev.deskseed.eventpublication.DomainEventVisibility
import dev.deskseed.eventpublication.EventPublicationPort
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.ApplyTriggerTicketCommand
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TriggerTicketCommandService
import dev.deskseed.trigger.TriggerActionType
import dev.deskseed.trigger.TriggerConditionDefinition
import dev.deskseed.trigger.TriggerConditionField
import dev.deskseed.trigger.TriggerConditionGroup
import dev.deskseed.trigger.TriggerConditionOperator
import dev.deskseed.trigger.TriggerEventType
import dev.deskseed.trigger.TriggerSetGroupAction
import dev.deskseed.trigger.TriggerWebhookAction
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class ClaimedTriggerJob(
    val id: UUID,
    val ticketId: UUID,
    val ticketNumber: Long,
    val rootTicketAuditId: UUID,
    val rootCorrelationId: String,
    val eventType: TriggerEventType,
    val versions: List<TriggerVersionSnapshot>,
    val attemptCount: Int,
    val leaseOwner: String,
)

internal data class TriggerVersionSnapshot(val triggerId: UUID, val triggerVersion: Int, val position: Int)

@Service
internal class TriggerEvaluationJobStore(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(workerId: String): ClaimedTriggerJob? {
        require(workerId.matches(Regex("[A-Za-z0-9._:-]{1,100}"))) { "Trigger worker ID is invalid" }
        val now = Instant.now(clock)
        return jdbc.query(
            """
            with candidate as (
                select id from trigger_evaluation_jobs
                 where status in ('PENDING', 'RETRY_SCHEDULED') and available_at <= ?
                 order by available_at, created_at, id
                 for update skip locked limit 1
            )
            update trigger_evaluation_jobs job
               set status = 'LEASED', attempt_count = attempt_count + 1, lease_owner = ?,
                   lease_expires_at = ?, updated_at = ?
              from candidate where job.id = candidate.id
            returning job.*
            """.trimIndent(),
            { result, _ -> ClaimedTriggerJob(
                result.getObject("id", UUID::class.java),
                result.getObject("ticket_id", UUID::class.java),
                result.getLong("ticket_number"),
                result.getObject("root_ticket_audit_id", UUID::class.java),
                result.getString("root_correlation_id"),
                TriggerEventType.valueOf(result.getString("event_type")),
                objectMapper.readValue(result.getString("trigger_versions_json"), object : TypeReference<List<TriggerVersionSnapshot>>() {}),
                result.getInt("attempt_count"),
                result.getString("lease_owner"),
            ) },
            Timestamp.from(now), workerId, Timestamp.from(now.plusSeconds(60)), Timestamp.from(now),
        ).singleOrNull()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(job: ClaimedTriggerJob, failure: Throwable) {
        val now = Instant.now(clock)
        val terminal = job.attemptCount >= MAX_ATTEMPTS
        val status = if (terminal) "DEAD_LETTERED" else "RETRY_SCHEDULED"
        val availableAt = if (terminal) now else now.plusSeconds(1L shl job.attemptCount.coerceAtMost(6))
        val completion = if (terminal) ", completed_at = ?" else ""
        val parameters = mutableListOf<Any>(
            status, Timestamp.from(availableAt), failureCode(failure), Timestamp.from(now),
        )
        if (terminal) parameters += Timestamp.from(now)
        parameters.addAll(listOf(job.id, job.leaseOwner, job.attemptCount))
        jdbc.update(
            """
            update trigger_evaluation_jobs
               set status = ?, available_at = ?, lease_owner = null, lease_expires_at = null,
                   last_error_code = ?, updated_at = ?$completion
             where id = ? and status = 'LEASED' and lease_owner = ? and attempt_count = ?
            """.trimIndent(),
            *parameters.toTypedArray(),
        )
    }

    @Transactional
    fun recoverExpired(): Int {
        val now = Instant.now(clock)
        return jdbc.update(
            """
            update trigger_evaluation_jobs
               set status = 'RETRY_SCHEDULED', available_at = ?, lease_owner = null, lease_expires_at = null,
                   last_error_code = 'LEASE_EXPIRED', updated_at = ?
             where status = 'LEASED' and lease_expires_at < ?
            """.trimIndent(),
            Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
        )
    }

    private fun failureCode(failure: Throwable): String = generateSequence(failure) { it.cause }
        .last().javaClass.simpleName.uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(80)

    private companion object { const val MAX_ATTEMPTS = 5 }
}

@Service
internal class TriggerEvaluationExecutor(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val ticketCommands: TriggerTicketCommandService,
    private val eventPublication: EventPublicationPort,
    private val clock: Clock,
) {
    @Transactional
    fun execute(job: ClaimedTriggerJob) {
        require(job.versions.size <= MAX_TRIGGER_DEPTH) { "Trigger execution depth exceeded" }
        val seen = mutableSetOf<String>()
        val duplicatedVersions = job.versions.groupingBy { it.triggerId to it.triggerVersion }
            .eachCount().filterValues { it > 1 }.keys
        var actionCount = 0
        job.versions.sortedWith(compareBy<TriggerVersionSnapshot> { it.position }.thenBy { it.triggerId }).forEach { snapshot ->
            if (executionExists(job.id, snapshot)) return@forEach
            val ticket = loadTicket(job.ticketNumber)
            val fingerprint = fingerprint(job.eventType, ticket)
            val executionId = UUID.randomUUID()
            val startedAt = Instant.now(clock)
            if (snapshot.triggerId to snapshot.triggerVersion in duplicatedVersions) {
                insertExecution(job, snapshot, executionId, "LOOP_BLOCKED", fingerprint, null, "DUPLICATE_RULE_VERSION", startedAt)
                return@forEach
            }
            val definition = loadDefinition(snapshot)
            actionCount += definition.actions.size
            require(actionCount <= MAX_ACTION_COUNT) { "Trigger action limit exceeded" }
            val repetitionKey = "${snapshot.triggerId}:${snapshot.triggerVersion}:$fingerprint"
            if (!seen.add(repetitionKey)) {
                insertExecution(job, snapshot, executionId, "LOOP_BLOCKED", fingerprint, null, "STATE_REPETITION", startedAt)
                return@forEach
            }
            if (!matches(definition.conditions, job.eventType, ticket)) {
                insertExecution(job, snapshot, executionId, "NOT_MATCHED", fingerprint, null, null, startedAt)
                return@forEach
            }

            val targetGroup = definition.actions.filterIsInstance<TriggerSetGroupAction>().singleOrNull()?.groupId
            val context = CommandContext(
                RequestSource.TRIGGER,
                "trigger-job-${job.id}",
                job.rootCorrelationId,
                executionId.toString(),
            )
            val result = ticketCommands.applyTrigger(ApplyTriggerTicketCommand(
                job.ticketNumber,
                ticket.version,
                snapshot.triggerId,
                snapshot.triggerVersion,
                executionId,
                job.rootTicketAuditId,
                targetGroup,
                context,
            ))
            if (definition.actions.any { it.type == TriggerActionType.ENQUEUE_WEBHOOK }) {
                appendWebhookIntent(job, snapshot, executionId, result.auditId, ticket.kind, context)
            }
            insertExecution(job, snapshot, executionId, "MATCHED", fingerprint, result.auditId, null, startedAt)
        }
        val now = Instant.now(clock)
        val updated = jdbc.update(
            """
            update trigger_evaluation_jobs
               set status = 'SUCCEEDED', lease_owner = null, lease_expires_at = null,
                   last_error_code = null, updated_at = ?, completed_at = ?
             where id = ? and status = 'LEASED' and lease_owner = ? and attempt_count = ?
            """.trimIndent(),
            Timestamp.from(now), Timestamp.from(now), job.id, job.leaseOwner, job.attemptCount,
        )
        check(updated == 1) { "Trigger job lease was lost" }
    }

    private fun loadDefinition(snapshot: TriggerVersionSnapshot): TriggerDefinitionSnapshot {
        val conditions = jdbc.query(
            "select condition_group, field_name, operator, value_text from trigger_conditions where trigger_id = ? and trigger_version = ? order by ordinal",
            { result, _ -> TriggerConditionDefinition(
                TriggerConditionGroup.valueOf(result.getString(1)), TriggerConditionField.valueOf(result.getString(2)),
                TriggerConditionOperator.valueOf(result.getString(3)), result.getString(4),
            ) },
            snapshot.triggerId, snapshot.triggerVersion,
        )
        val actions = jdbc.query(
            "select action_type, configuration_json::text from trigger_actions where trigger_id = ? and trigger_version = ? order by ordinal",
            { result, _ -> when (TriggerActionType.valueOf(result.getString(1))) {
                TriggerActionType.SET_GROUP -> TriggerSetGroupAction(
                    UUID.fromString(objectMapper.readTree(result.getString(2))["groupId"].asText()),
                )
                TriggerActionType.ENQUEUE_WEBHOOK -> TriggerWebhookAction(
                    objectMapper.readTree(result.getString(2))["eventType"].asText(),
                )
            } },
            snapshot.triggerId, snapshot.triggerVersion,
        )
        check(conditions.isNotEmpty() && actions.isNotEmpty()) { "Trigger version snapshot is incomplete" }
        return TriggerDefinitionSnapshot(conditions, actions)
    }

    private fun loadTicket(ticketNumber: Long): TicketSnapshot = jdbc.query(
        "select id, version, priority, group_id, kind from tickets where ticket_number = ? for update",
        { result, _ -> TicketSnapshot(
            result.getObject("id", UUID::class.java), result.getLong("version"), result.getString("priority"),
            result.getObject("group_id", UUID::class.java), TicketKind.valueOf(result.getString("kind")),
        ) },
        ticketNumber,
    ).singleOrNull() ?: error("Trigger ticket is unavailable")

    private fun matches(conditions: List<TriggerConditionDefinition>, event: TriggerEventType, ticket: TicketSnapshot): Boolean {
        val results = conditions.map { condition ->
            val current = when (condition.field) {
                TriggerConditionField.EVENT -> event.name
                TriggerConditionField.PRIORITY -> ticket.priority
                TriggerConditionField.GROUP -> ticket.groupId?.toString()
            }
            when (condition.operator) {
                TriggerConditionOperator.IS -> current == condition.value
                TriggerConditionOperator.IS_NOT -> current != condition.value
                TriggerConditionOperator.PRESENT -> current != null
                TriggerConditionOperator.NOT_PRESENT -> current == null
            }
        }
        val all = conditions.indices.filter { conditions[it].group == TriggerConditionGroup.ALL }
        val any = conditions.indices.filter { conditions[it].group == TriggerConditionGroup.ANY }
        return all.all(results::get) && (any.isEmpty() || any.any(results::get))
    }

    private fun appendWebhookIntent(
        job: ClaimedTriggerJob,
        snapshot: TriggerVersionSnapshot,
        executionId: UUID,
        ticketAuditId: UUID,
        kind: TicketKind,
        context: CommandContext,
    ) {
        val now = Instant.now(clock)
        eventPublication.append(DomainEventAppend(
            DomainEventEnvelope(
                UUID.randomUUID(), TriggerWebhookAction.WEBHOOK_EVENT_TYPE, 1, now, "ticket:${job.ticketId}", null,
                context.correlationId, job.rootTicketAuditId.toString(), ActorType.TRIGGER, snapshot.triggerId,
                RequestSource.TRIGGER, context.requestId, context.commandId,
                mapOf(
                    "ticketNumber" to job.ticketNumber.toString(),
                    "triggerId" to snapshot.triggerId.toString(),
                    "triggerVersion" to snapshot.triggerVersion.toString(),
                    "executionId" to executionId.toString(),
                    "ticketAuditId" to ticketAuditId.toString(),
                ),
            ),
            if (kind == TicketKind.INTERNAL_CHILD) DomainEventVisibility.INTERNAL else DomainEventVisibility.PUBLIC,
        ))
    }

    private fun insertExecution(
        job: ClaimedTriggerJob,
        snapshot: TriggerVersionSnapshot,
        executionId: UUID,
        outcome: String,
        fingerprint: String,
        ticketAuditId: UUID?,
        errorCode: String?,
        startedAt: Instant,
    ) {
        jdbc.update(
            """
            insert into trigger_executions (
                id, job_id, trigger_id, trigger_version, position, outcome, state_fingerprint,
                ticket_audit_id, error_code, started_at, completed_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            executionId, job.id, snapshot.triggerId, snapshot.triggerVersion, snapshot.position, outcome, fingerprint,
            ticketAuditId, errorCode, Timestamp.from(startedAt), Timestamp.from(Instant.now(clock)),
        )
    }

    private fun executionExists(jobId: UUID, snapshot: TriggerVersionSnapshot): Boolean = jdbc.queryForObject(
        "select exists(select 1 from trigger_executions where job_id = ? and trigger_id = ? and trigger_version = ?)",
        Boolean::class.java, jobId, snapshot.triggerId, snapshot.triggerVersion,
    ) == true

    private fun fingerprint(event: TriggerEventType, ticket: TicketSnapshot): String = sha256(
        listOf(event.name, ticket.id, ticket.version, ticket.priority, ticket.groupId, ticket.kind).joinToString("|"),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class TriggerDefinitionSnapshot(
        val conditions: List<TriggerConditionDefinition>,
        val actions: List<dev.deskseed.trigger.TriggerActionDefinition>,
    )
    private data class TicketSnapshot(
        val id: UUID,
        val version: Long,
        val priority: String,
        val groupId: UUID?,
        val kind: TicketKind,
    )

    private companion object {
        const val MAX_TRIGGER_DEPTH = 100
        const val MAX_ACTION_COUNT = 200
    }
}

@Component
internal class TriggerEvaluationWorker(
    private val jobs: TriggerEvaluationJobStore,
    private val executor: TriggerEvaluationExecutor,
) {
    fun runOnce(workerId: String = "trigger-worker"): Boolean {
        val job = jobs.claim(workerId) ?: return false
        try {
            executor.execute(job)
        } catch (failure: RuntimeException) {
            jobs.fail(job, failure)
        }
        return true
    }

    @Scheduled(fixedDelayString = "\${deskseed.trigger.worker-delay-ms:1000}")
    fun executeDueJobs() {
        repeat(100) { if (!runOnce()) return }
    }

    @Scheduled(fixedDelayString = "\${deskseed.trigger.lease-recovery-delay-ms:30000}")
    fun recoverExpiredLeases() {
        jobs.recoverExpired()
    }
}
