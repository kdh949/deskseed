package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiAuditUnavailableException
import dev.deskseed.aiassistance.AiActivityAudit
import dev.deskseed.aiassistance.AiActivityAuditWriter
import dev.deskseed.aiassistance.AiBackendRequestStatus
import dev.deskseed.aiassistance.AiFeedbackReceipt
import dev.deskseed.aiassistance.AiFeature
import dev.deskseed.aiassistance.AiFeatureDisabledException
import dev.deskseed.aiassistance.AiExecutionStatusReader
import dev.deskseed.aiassistance.AiJobReceipt
import dev.deskseed.aiassistance.AiPublicContextUnavailableException
import dev.deskseed.aiassistance.AiRequestConflictException
import dev.deskseed.aiassistance.AiRequestInvalidException
import dev.deskseed.aiassistance.AiRequestMetadata
import dev.deskseed.aiassistance.AiRequestNotFoundException
import dev.deskseed.aiassistance.AiRequestRateLimitedException
import dev.deskseed.aiassistance.AiRequestService
import dev.deskseed.aiassistance.AiServiceIdentity
import dev.deskseed.aiassistance.AiSourceComment
import dev.deskseed.aiassistance.AiSourceContext
import dev.deskseed.aiassistance.AiSourceContextService
import dev.deskseed.aiassistance.AiSourceRevision
import dev.deskseed.aiassistance.AiSourceRequestSupersededException
import dev.deskseed.aiassistance.AiSourceRequestUnavailableException
import dev.deskseed.aiassistance.CreateAiRequestCommand
import dev.deskseed.aiassistance.RecordAiFeedbackCommand
import dev.deskseed.aiassistance.AiStatusUnavailableException
import dev.deskseed.audit.AccessAuditAuthType
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.AiContextAccessAudit
import dev.deskseed.audit.TicketResourceReadAccessAudit
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.AiPublicTicketContext
import dev.deskseed.ticketing.StaffTicketReadStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcAiRequestService(
    private val jdbcTemplate: JdbcTemplate,
    private val ticketStore: StaffTicketReadStore,
    private val auditWriter: AccessAuditWriter,
    private val objectMapper: ObjectMapper,
    private val executionStatusReader: AiExecutionStatusReader,
    private val resultAuthorizer: AiResultAuthorizer,
    private val activityAuditWriter: AiActivityAuditWriter,
    private val clock: Clock,
    @Value("\${deskseed.ai.workspace-key:default}") private val workspaceKey: String,
    @Value("\${deskseed.ai.request-deadline:2m}") private val requestDeadline: Duration,
) : AiRequestService, AiSourceContextService {
    @Transactional
    override fun create(command: CreateAiRequestCommand): AiJobReceipt {
        validateCommand(command)
        requireFeatureEnabled(command.feature, command.actor.id)
        val context = ticketStore.findAiPublicContext(command.ticketNumber, command.actor.id)
            ?: throw AiRequestNotFoundException()
        if (context.comments.isEmpty()) throw AiPublicContextUnavailableException()

        val normalizedOptions = normalizeOptions(command.feature, command.options)
        val optionsJson = objectMapper.writeValueAsString(normalizedOptions)
        val idempotencyFingerprint = sha256(command.idempotencyKey)
        val contextRevision = computeAiContextRevision(context)
        val requestFingerprint = sha256(
            listOf(
                context.ticketId,
                context.ticketNumber,
                command.feature.value,
                command.expectedTicketVersion,
                optionsJson,
            ).joinToString("\u001f"),
        )
        acquireIdempotencyLock(command.actor.id, idempotencyFingerprint)
        findByIdempotency(command.actor.id, idempotencyFingerprint)?.let { existing ->
            if (existing.requestFingerprint != requestFingerprint) throw AiRequestConflictException()
            return existing.receipt
        }
        if (context.ticketVersion != command.expectedTicketVersion) throw AiRequestConflictException()
        enforceAdmissionRate(command.actor.id)

        val now = Instant.now(clock)
        val deadline = now.plus(requestDeadline)
        val jobId = UUID.randomUUID()
        appendStaffReadAudit(command, context, jobId, now)
        jdbcTemplate.update(
            """
            insert into ai_requests (
                job_id, workspace_key, requester_staff_id, ticket_id, ticket_number,
                feature, status, expected_ticket_version, options_json, request_fingerprint,
                idempotency_key_fingerprint, context_policy_version, context_revision,
                request_revision, cancellation_requested, created_at, updated_at, deadline_at
            ) values (?, ?, ?, ?, ?, ?, 'ACCEPTED', ?, ?::jsonb, ?, ?, ?, ?, 1, false, ?, ?, ?)
            """.trimIndent(),
            jobId,
            workspaceKey,
            command.actor.id,
            context.ticketId,
            context.ticketNumber,
            command.feature.value,
            command.expectedTicketVersion,
            optionsJson,
            requestFingerprint,
            idempotencyFingerprint,
            CONTEXT_POLICY_VERSION,
            contextRevision,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(deadline),
        )
        val envelope = linkedMapOf<String, Any?>(
            "schemaVersion" to OUTBOX_SCHEMA_VERSION,
            "eventId" to UUID.randomUUID().toString(),
            "jobId" to jobId.toString(),
            "workspaceKey" to workspaceKey,
            "requesterId" to command.actor.id.toString(),
            "ticketId" to context.ticketId.toString(),
            "ticketNumber" to context.ticketNumber,
            "feature" to command.feature.value,
            "contextRevision" to contextRevision,
            "contextPolicyVersion" to CONTEXT_POLICY_VERSION,
            "dataClass" to INPUT_SCOPE,
            "requestRevision" to 1,
            "options" to normalizedOptions,
            "createdAt" to now.toString(),
            "deadlineAt" to deadline.toString(),
            "traceparent" to command.metadata.traceparent,
            "tracestate" to command.metadata.tracestate,
        )
        appendOutbox(jobId, "JOB_REQUESTED", 1, envelope, now)
        activityAuditWriter.append(
            AiActivityAudit(
                eventId = UUID.randomUUID(),
                action = "AI_REQUEST_CREATED",
                actor = command.actor,
                metadata = command.metadata,
                jobId = jobId,
                ticketId = context.ticketId,
                ticketNumber = context.ticketNumber,
                requestRevision = 1,
                details = mapOf("feature" to command.feature.value),
                occurredAt = now,
            ),
        )
        return receipt(jobId, command.feature.value, AiBackendRequestStatus.ACCEPTED, 1, now, deadline, false, contextRevision)
    }

    @Transactional
    override fun cancel(
        ticketNumber: Long,
        jobId: UUID,
        actor: dev.deskseed.aiassistance.AiStaffActor,
        metadata: AiRequestMetadata,
    ): AiJobReceipt {
        if (!ticketStore.canReadForAi(ticketNumber, actor.id)) throw AiRequestNotFoundException()
        val binding = findForUpdate(jobId, actor.id, ticketNumber) ?: throw AiRequestNotFoundException()
        if (binding.receipt.status == AiBackendRequestStatus.CANCELLED) return binding.receipt
        if (binding.receipt.status != AiBackendRequestStatus.ACCEPTED) throw AiRequestConflictException()
        val now = Instant.now(clock)
        if (!binding.receipt.deadlineAt.isAfter(now)) {
            jdbcTemplate.update(
                "update ai_requests set status = 'EXPIRED', updated_at = ? where job_id = ?",
                Timestamp.from(now),
                jobId,
            )
            throw AiRequestConflictException()
        }
        val revision = binding.receipt.requestRevision + 1
        jdbcTemplate.update(
            """
            update ai_requests
            set status = 'CANCELLED', cancellation_requested = true,
                request_revision = ?, updated_at = ?
            where job_id = ?
            """.trimIndent(),
            revision,
            Timestamp.from(now),
            jobId,
        )
        val envelope = linkedMapOf<String, Any?>(
            "schemaVersion" to OUTBOX_SCHEMA_VERSION,
            "eventId" to UUID.randomUUID().toString(),
            "jobId" to jobId.toString(),
            "workspaceKey" to workspaceKey,
            "requestRevision" to revision,
            "createdAt" to now.toString(),
            "traceparent" to metadata.traceparent,
            "tracestate" to metadata.tracestate,
        )
        appendOutbox(jobId, "JOB_CANCELLED", revision, envelope, now)
        val ticketId = jdbcTemplate.queryForObject(
            "select ticket_id from ai_requests where job_id = ?",
            UUID::class.java,
            jobId,
        ) ?: throw AiRequestNotFoundException()
        activityAuditWriter.append(
            AiActivityAudit(
                eventId = UUID.randomUUID(),
                action = "AI_REQUEST_CANCELLED",
                actor = actor,
                metadata = metadata,
                jobId = jobId,
                ticketId = ticketId,
                ticketNumber = ticketNumber,
                requestRevision = revision,
                details = emptyMap(),
                occurredAt = now,
            ),
        )
        return binding.receipt.copy(
            status = AiBackendRequestStatus.CANCELLED,
            requestRevision = revision,
            cancelRequested = true,
        )
    }

    override fun get(
        ticketNumber: Long,
        jobId: UUID,
        includeResult: Boolean,
        actor: dev.deskseed.aiassistance.AiStaffActor,
        metadata: AiRequestMetadata,
    ): AiJobReceipt {
        if (!ticketStore.canReadForAi(ticketNumber, actor.id)) throw AiRequestNotFoundException()
        val backendReceipt = jdbcTemplate.query(
            """
            select job_id, feature, status, request_revision, created_at, deadline_at,
                   cancellation_requested, context_revision, request_fingerprint
            from ai_requests
            where job_id = ? and workspace_key = ? and requester_staff_id = ? and ticket_number = ?
            """.trimIndent(),
            { result, _ -> mapBinding(result).receipt },
            jobId,
            workspaceKey,
            actor.id,
            ticketNumber,
        ).singleOrNull() ?: throw AiRequestNotFoundException()
        if (backendReceipt.status != AiBackendRequestStatus.ACCEPTED) return backendReceipt
        val remote = executionStatusReader.read(jobId, includeResult)
        if (remote == null) {
            val delivered = jdbcTemplate.queryForObject(
                "select exists (select 1 from ai_integration_outbox where job_id = ? and event_type = 'JOB_REQUESTED' and status = 'DELIVERED')",
                Boolean::class.java,
                jobId,
            ) == true
            if (delivered) throw AiStatusUnavailableException()
            return backendReceipt
        }
        if (remote.jobId != jobId || remote.feature != backendReceipt.feature) throw AiStatusUnavailableException()
        return resultAuthorizer.authorize(ticketNumber, jobId, actor, metadata, remote)
    }

    @Transactional
    override fun feedback(command: RecordAiFeedbackCommand): AiFeedbackReceipt {
        validateIdempotencyKey(command.idempotencyKey)
        if (!ticketStore.canReadForAi(command.ticketNumber, command.actor.id)) throw AiRequestNotFoundException()
        val binding = jdbcTemplate.query(
            """
            select job_id, ticket_id, feature, request_revision
            from ai_requests
            where job_id = ? and workspace_key = ? and requester_staff_id = ? and ticket_number = ?
            for update
            """.trimIndent(),
            { result, _ -> FeedbackBinding(
                ticketId = result.getObject("ticket_id", UUID::class.java),
                feature = AiFeature.fromValue(result.getString("feature")),
                requestRevision = result.getLong("request_revision"),
            ) },
            command.jobId,
            workspaceKey,
            command.actor.id,
            command.ticketNumber,
        ).singleOrNull() ?: throw AiRequestNotFoundException()
        requireFeatureEnabled(binding.feature, command.actor.id)
        val reasonCode = command.reasonCode?.trim()?.also {
            if (it.isEmpty() || it.length > 40 || it.any(Char::isISOControl)) {
                throw AiRequestInvalidException("Feedback reason code is invalid")
            }
        }
        val keyFingerprint = sha256(command.idempotencyKey)
        val requestFingerprint = sha256("${command.jobId}\u001f${command.type.value}\u001f${reasonCode.orEmpty()}")
        acquireIdempotencyLock(command.actor.id, keyFingerprint)
        jdbcTemplate.query(
            """
            select job_id, feedback_type, source_revision, request_fingerprint, recorded_at
            from ai_feedback_idempotency
            where requester_staff_id = ? and idempotency_key_fingerprint = ?
            """.trimIndent(),
            { result, _ -> ExistingFeedback(
                jobId = result.getObject("job_id", UUID::class.java),
                type = result.getString("feedback_type"),
                sourceRevision = result.getLong("source_revision"),
                requestFingerprint = result.getString("request_fingerprint"),
                recordedAt = result.getTimestamp("recorded_at").toInstant(),
            ) },
            command.actor.id,
            keyFingerprint,
        ).singleOrNull()?.let { existing ->
            if (existing.jobId != command.jobId || existing.requestFingerprint != requestFingerprint) {
                throw AiRequestConflictException()
            }
            return AiFeedbackReceipt(existing.jobId, existing.type, existing.sourceRevision, true, existing.recordedAt)
        }
        val now = Instant.now(clock)
        val sourceRevision = jdbcTemplate.query(
            "select source_revision from ai_request_feedback where job_id = ? and feedback_type = ? for update",
            { result, _ -> result.getLong("source_revision") },
            command.jobId,
            command.type.value,
        ).singleOrNull()?.plus(1L) ?: 1L
        val eventId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into ai_request_feedback (
                job_id, feedback_type, requester_staff_id, source_revision, reason_code,
                event_id, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (job_id, feedback_type) do update set
                source_revision = excluded.source_revision, reason_code = excluded.reason_code,
                event_id = excluded.event_id, updated_at = excluded.updated_at
            """.trimIndent(),
            command.jobId,
            command.type.value,
            command.actor.id,
            sourceRevision,
            reasonCode,
            eventId,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        jdbcTemplate.update(
            """
            insert into ai_feedback_idempotency (
                requester_staff_id, idempotency_key_fingerprint, request_fingerprint,
                job_id, feedback_type, source_revision, recorded_at
            ) values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            command.actor.id,
            keyFingerprint,
            requestFingerprint,
            command.jobId,
            command.type.value,
            sourceRevision,
            Timestamp.from(now),
        )
        val requestRevision = binding.requestRevision + 1
        jdbcTemplate.update(
            "update ai_requests set request_revision = ?, updated_at = ? where job_id = ?",
            requestRevision,
            Timestamp.from(now),
            command.jobId,
        )
        appendOutbox(
            command.jobId,
            "JOB_FEEDBACK",
            requestRevision,
            linkedMapOf(
                "schemaVersion" to OUTBOX_SCHEMA_VERSION,
                "eventId" to eventId.toString(),
                "jobId" to command.jobId.toString(),
                "workspaceKey" to workspaceKey,
                "requesterId" to command.actor.id.toString(),
                "feedbackType" to command.type.value,
                "reasonCode" to reasonCode,
                "sourceRevision" to sourceRevision,
                "requestRevision" to requestRevision,
                "createdAt" to now.toString(),
            ),
            now,
        )
        activityAuditWriter.append(
            AiActivityAudit(
                eventId = UUID.randomUUID(),
                action = "AI_FEEDBACK_RECORDED",
                actor = command.actor,
                metadata = command.metadata,
                jobId = command.jobId,
                ticketId = binding.ticketId,
                ticketNumber = command.ticketNumber,
                requestRevision = requestRevision,
                details = mapOf("feedbackType" to command.type.value),
                occurredAt = now,
            ),
        )
        return AiFeedbackReceipt(command.jobId, command.type.value, sourceRevision, false, now)
    }

    @Transactional(readOnly = true)
    override fun list(ticketNumber: Long, actorId: UUID, limit: Int): List<AiJobReceipt> {
        require(limit in 1..50) { "limit must be between 1 and 50" }
        if (!ticketStore.canReadForAi(ticketNumber, actorId)) throw AiRequestNotFoundException()
        return jdbcTemplate.query(
            """
            select job_id, feature, status, request_revision, created_at, deadline_at,
                   cancellation_requested, context_revision, request_fingerprint
            from ai_requests
            where workspace_key = ? and requester_staff_id = ? and ticket_number = ?
            order by created_at desc, job_id desc
            limit ?
            """.trimIndent(),
            { result, _ -> mapBinding(result) },
            workspaceKey,
            actorId,
            ticketNumber,
            limit,
        ).map(Binding::receipt)
    }

    @Transactional(noRollbackFor = [AiSourceRequestSupersededException::class])
    override fun read(
        jobId: UUID,
        serviceIdentity: AiServiceIdentity,
        metadata: AiRequestMetadata,
    ): AiSourceContext {
        val binding = jdbcTemplate.query(
            """
            select job_id, requester_staff_id, ticket_number, feature, status,
                   request_revision, deadline_at, context_revision, context_policy_version
            from ai_requests
            where job_id = ? and workspace_key = ?
            for update
            """.trimIndent(),
            { result, _ ->
                SourceBinding(
                    jobId = result.getObject("job_id", UUID::class.java),
                    requesterStaffId = result.getObject("requester_staff_id", UUID::class.java),
                    ticketNumber = result.getLong("ticket_number"),
                    feature = result.getString("feature"),
                    status = AiBackendRequestStatus.valueOf(result.getString("status")),
                    requestRevision = result.getLong("request_revision"),
                    deadlineAt = result.getTimestamp("deadline_at").toInstant(),
                    contextRevision = result.getString("context_revision"),
                    contextPolicyVersion = result.getString("context_policy_version"),
                )
            },
            jobId,
            workspaceKey,
        ).singleOrNull() ?: throw AiSourceRequestUnavailableException()
        val now = Instant.now(clock)
        if (binding.status != AiBackendRequestStatus.ACCEPTED || !binding.deadlineAt.isAfter(now)) {
            throw AiSourceRequestUnavailableException()
        }
        val context = ticketStore.findAiPublicContext(binding.ticketNumber, binding.requesterStaffId)
            ?: throw AiSourceRequestUnavailableException()
        if (context.comments.isEmpty()) throw AiSourceRequestUnavailableException()
        val currentRevision = computeAiContextRevision(context)
        if (currentRevision != binding.contextRevision) {
            jdbcTemplate.update(
                "update ai_requests set status = 'SUPERSEDED', updated_at = ? where job_id = ?",
                Timestamp.from(now),
                jobId,
            )
            throw AiSourceRequestSupersededException()
        }
        try {
            auditWriter.appendAiContextAccess(
                AiContextAccessAudit(
                    eventId = UUID.randomUUID(),
                    context = AccessAuditContext(
                        actorType = ActorType.INTEGRATION_CLIENT,
                        actorId = serviceIdentity.id,
                        actorDisplaySnapshot = serviceIdentity.displayName,
                        source = RequestSource.AI_SERVICE,
                        sessionFingerprint = null,
                        authType = AccessAuditAuthType.API_KEY,
                        requestId = metadata.requestId,
                        correlationId = metadata.correlationId,
                        ipAddress = null,
                        userAgent = null,
                    ),
                    jobId = jobId,
                    requesterStaffId = binding.requesterStaffId,
                    ticketId = context.ticketId,
                    ticketNumber = context.ticketNumber,
                    feature = binding.feature,
                    requestRevision = binding.requestRevision,
                    outcome = AccessAuditOutcome.SUCCEEDED,
                    httpStatus = 200,
                    occurredAt = now,
                ),
            )
        } catch (exception: DataAccessException) {
            throw AiAuditUnavailableException(exception)
        }
        return AiSourceContext(
            jobId = jobId,
            ticketId = context.ticketId,
            ticketNumber = context.ticketNumber,
            ticketVersion = context.ticketVersion,
            feature = binding.feature,
            requestRevision = binding.requestRevision,
            contextRevision = currentRevision,
            contextPolicyVersion = binding.contextPolicyVersion,
            inputScope = INPUT_SCOPE,
            comments = context.comments.map { AiSourceComment(it.id, it.body, it.createdAt) },
        )
    }

    @Transactional(readOnly = true)
    override fun revision(jobId: UUID): AiSourceRevision {
        val binding = jdbcTemplate.query(
            """
            select requester_staff_id, ticket_number, feature, status, request_revision,
                   context_revision, cancellation_requested, deadline_at
            from ai_requests where job_id = ? and workspace_key = ?
            """.trimIndent(),
            { result, _ -> RevisionBinding(
                requesterId = result.getObject("requester_staff_id", UUID::class.java),
                ticketNumber = result.getLong("ticket_number"),
                feature = AiFeature.fromValue(result.getString("feature")),
                status = AiBackendRequestStatus.valueOf(result.getString("status")),
                requestRevision = result.getLong("request_revision"),
                contextRevision = result.getString("context_revision"),
                cancelRequested = result.getBoolean("cancellation_requested"),
                deadlineAt = result.getTimestamp("deadline_at").toInstant(),
            ) },
            jobId,
            workspaceKey,
        ).singleOrNull() ?: throw AiSourceRequestUnavailableException()
        val context = ticketStore.findAiPublicContext(binding.ticketNumber, binding.requesterId)
        val currentRevision = context?.let(::computeAiContextRevision) ?: binding.contextRevision
        val featureEnabled = isFeatureEnabled(binding.feature, binding.requesterId)
        val authorized = context != null && context.comments.isNotEmpty() &&
            binding.status == AiBackendRequestStatus.ACCEPTED && !binding.cancelRequested &&
            binding.deadlineAt.isAfter(Instant.now(clock)) && currentRevision == binding.contextRevision && featureEnabled
        return AiSourceRevision(
            jobId = jobId,
            requestRevision = binding.requestRevision,
            contextRevision = currentRevision,
            authorized = authorized,
            cancelRequested = binding.cancelRequested,
            featureEnabled = featureEnabled,
        )
    }

    private fun validateCommand(command: CreateAiRequestCommand) {
        if (command.ticketNumber <= 0) throw AiRequestInvalidException("ticketNumber must be positive")
        if (command.expectedTicketVersion < 0) throw AiRequestInvalidException("expectedTicketVersion must be nonnegative")
        validateIdempotencyKey(command.idempotencyKey)
        if (workspaceKey.isBlank() || workspaceKey.length > 80 || workspaceKey.any(Char::isISOControl)) {
            throw IllegalStateException("AI workspace key configuration is invalid")
        }
        if (requestDeadline.isZero || requestDeadline.isNegative || requestDeadline > Duration.ofMinutes(10)) {
            throw IllegalStateException("AI request deadline configuration is invalid")
        }
    }

    private fun validateIdempotencyKey(value: String) {
        if (value.length !in 16..200 || value.any(Char::isISOControl)) {
            throw AiRequestInvalidException("Idempotency-Key must be a bounded printable value")
        }
    }

    private fun requireFeatureEnabled(feature: AiFeature, actorId: UUID) {
        if (!isFeatureEnabled(feature, actorId)) throw AiFeatureDisabledException()
    }

    private fun isFeatureEnabled(feature: AiFeature, actorId: UUID): Boolean {
        val featureColumn = when (feature) {
            AiFeature.TICKET_SUMMARY -> "summary_enabled"
            AiFeature.TICKET_TRIAGE -> "triage_enabled"
            AiFeature.TICKET_REPLY_DRAFT -> "reply_draft_enabled"
        }
        val enabled = jdbcTemplate.queryForObject(
            """
            select exists (
                select 1 from ai_settings settings
                where settings.singleton = true and settings.enabled = true and settings.$featureColumn = true
                  and exists (
                      select 1 from ai_feature_staff_allowlist allowed
                      join staff_accounts staff on staff.id = allowed.staff_id and staff.status = 'ACTIVE'
                      where allowed.staff_id = ?
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            actorId,
        ) == true
        return enabled
    }

    private fun normalizeOptions(feature: AiFeature, options: Map<String, String>): Map<String, String> {
        val allowedKeys = when (feature) {
            AiFeature.TICKET_REPLY_DRAFT -> setOf("language", "tone")
            AiFeature.TICKET_SUMMARY, AiFeature.TICKET_TRIAGE -> setOf("language")
        }
        if (options.keys.any { it !in allowedKeys }) throw AiRequestInvalidException("Unsupported AI request option")
        return options.toSortedMap().mapValues { (key, rawValue) ->
            val value = rawValue.trim()
            if (value.isEmpty() || value.length > 40 || value.any(Char::isISOControl)) {
                throw AiRequestInvalidException("AI option $key is invalid")
            }
            value
        }
    }

    private fun acquireIdempotencyLock(actorId: UUID, idempotencyFingerprint: String) {
        jdbcTemplate.queryForObject(
            "select pg_advisory_xact_lock(hashtext(?), hashtext(?))",
            Any::class.java,
            actorId.toString(),
            idempotencyFingerprint,
        )
    }

    private fun enforceAdmissionRate(actorId: UUID) {
        val now = Instant.now(clock)
        val since = Timestamp.from(now.minusSeconds(60))
        val actorCount = jdbcTemplate.queryForObject(
            "select count(*) from ai_requests where workspace_key = ? and requester_staff_id = ? and created_at >= ?",
            Long::class.java,
            workspaceKey,
            actorId,
            since,
        ) ?: 0L
        val workspaceCount = jdbcTemplate.queryForObject(
            "select count(*) from ai_requests where workspace_key = ? and created_at >= ?",
            Long::class.java,
            workspaceKey,
            since,
        ) ?: 0L
        if (actorCount >= ACTOR_REQUESTS_PER_MINUTE || workspaceCount >= WORKSPACE_REQUESTS_PER_MINUTE) {
            throw AiRequestRateLimitedException(60)
        }
        val outstanding = jdbcTemplate.queryForObject(
            """
            select count(*) from ai_requests
            where workspace_key = ? and status = 'ACCEPTED' and deadline_at > ?
            """.trimIndent(),
            Long::class.java,
            workspaceKey,
            Timestamp.from(now),
        ) ?: 0L
        if (outstanding >= MAX_OUTSTANDING_REQUESTS) throw AiRequestRateLimitedException(5)
    }

    private fun findByIdempotency(actorId: UUID, fingerprint: String): Binding? = jdbcTemplate.query(
        """
        select job_id, feature, status, request_revision, created_at, deadline_at,
               cancellation_requested, context_revision, request_fingerprint
        from ai_requests
        where workspace_key = ? and requester_staff_id = ? and idempotency_key_fingerprint = ?
        """.trimIndent(),
        { result, _ -> mapBinding(result) },
        workspaceKey,
        actorId,
        fingerprint,
    ).singleOrNull()

    private fun findForUpdate(jobId: UUID, actorId: UUID, ticketNumber: Long): Binding? = jdbcTemplate.query(
        """
        select job_id, feature, status, request_revision, created_at, deadline_at,
               cancellation_requested, context_revision, request_fingerprint
        from ai_requests
        where job_id = ? and workspace_key = ? and requester_staff_id = ? and ticket_number = ?
        for update
        """.trimIndent(),
        { result, _ -> mapBinding(result) },
        jobId,
        workspaceKey,
        actorId,
        ticketNumber,
    ).singleOrNull()

    private fun mapBinding(result: java.sql.ResultSet): Binding = Binding(
        receipt = receipt(
            jobId = result.getObject("job_id", UUID::class.java),
            feature = result.getString("feature"),
            status = AiBackendRequestStatus.valueOf(result.getString("status")),
            requestRevision = result.getLong("request_revision"),
            createdAt = result.getTimestamp("created_at").toInstant(),
            deadlineAt = result.getTimestamp("deadline_at").toInstant(),
            cancelRequested = result.getBoolean("cancellation_requested"),
            contextRevision = result.getString("context_revision"),
        ),
        requestFingerprint = result.getString("request_fingerprint"),
    )

    private fun appendStaffReadAudit(
        command: CreateAiRequestCommand,
        context: AiPublicTicketContext,
        jobId: UUID,
        now: Instant,
    ) {
        try {
            auditWriter.appendTicketResourceRead(
                TicketResourceReadAccessAudit(
                    context = AccessAuditContext(
                        actorType = ActorType.STAFF,
                        actorId = command.actor.id,
                        actorDisplaySnapshot = command.actor.displayName,
                        source = RequestSource.AGENT_UI,
                        sessionFingerprint = command.actor.sessionFingerprint,
                        authType = AccessAuditAuthType.STAFF_SESSION,
                        requestId = command.metadata.requestId,
                        correlationId = command.metadata.correlationId,
                        ipAddress = command.metadata.ipAddress,
                        userAgent = command.metadata.userAgent,
                    ),
                    ticketId = context.ticketId,
                    ticketNumber = context.ticketNumber,
                    interactionId = jobId,
                    outcome = AccessAuditOutcome.SUCCEEDED,
                    httpStatus = 202,
                    occurredAt = now,
                ),
            )
        } catch (exception: DataAccessException) {
            throw AiAuditUnavailableException(exception)
        }
    }

    private fun appendOutbox(
        jobId: UUID,
        eventType: String,
        requestRevision: Long,
        envelope: Map<String, Any?>,
        now: Instant,
    ) {
        val payload = objectMapper.writeValueAsString(envelope)
        val eventId = UUID.fromString(envelope.getValue("eventId").toString())
        jdbcTemplate.update(
            """
            insert into ai_integration_outbox (
                event_id, job_id, event_type, schema_version, request_revision,
                payload_json, payload_checksum, status, attempts, available_at, created_at
            ) values (?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?)
            """.trimIndent(),
            eventId,
            jobId,
            eventType,
            OUTBOX_SCHEMA_VERSION,
            requestRevision,
            payload,
            sha256(payload),
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }

    private fun receipt(
        jobId: UUID,
        feature: String,
        status: AiBackendRequestStatus,
        requestRevision: Long,
        createdAt: Instant,
        deadlineAt: Instant,
        cancelRequested: Boolean,
        contextRevision: String,
    ) = AiJobReceipt(
        jobId = jobId,
        feature = feature,
        status = status,
        requestRevision = requestRevision,
        createdAt = createdAt,
        deadlineAt = deadlineAt,
        pollAfterMs = POLL_AFTER_MS,
        cancelRequested = cancelRequested,
        contextRevision = contextRevision,
        contextPolicyVersion = AI_CONTEXT_POLICY_VERSION,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class Binding(
        val receipt: AiJobReceipt,
        val requestFingerprint: String,
    )

    private data class SourceBinding(
        val jobId: UUID,
        val requesterStaffId: UUID,
        val ticketNumber: Long,
        val feature: String,
        val status: AiBackendRequestStatus,
        val requestRevision: Long,
        val deadlineAt: Instant,
        val contextRevision: String,
        val contextPolicyVersion: String,
    )

    private data class RevisionBinding(
        val requesterId: UUID,
        val ticketNumber: Long,
        val feature: AiFeature,
        val status: AiBackendRequestStatus,
        val requestRevision: Long,
        val contextRevision: String,
        val cancelRequested: Boolean,
        val deadlineAt: Instant,
    )

    private data class FeedbackBinding(val ticketId: UUID, val feature: AiFeature, val requestRevision: Long)
    private data class ExistingFeedback(
        val jobId: UUID,
        val type: String,
        val sourceRevision: Long,
        val requestFingerprint: String,
        val recordedAt: Instant,
    )

    private companion object {
        const val CONTEXT_POLICY_VERSION = AI_CONTEXT_POLICY_VERSION
        const val INPUT_SCOPE = "PUBLIC_ONLY"
        const val OUTBOX_SCHEMA_VERSION = 1
        const val POLL_AFTER_MS = 750L
        const val ACTOR_REQUESTS_PER_MINUTE = 5L
        const val WORKSPACE_REQUESTS_PER_MINUTE = 30L
        const val MAX_OUTSTANDING_REQUESTS = 300L
    }
}
