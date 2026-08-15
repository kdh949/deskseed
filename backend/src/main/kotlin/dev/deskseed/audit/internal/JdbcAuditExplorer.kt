package dev.deskseed.audit.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.audit.AuditActivity
import dev.deskseed.audit.AuditActivityDetail
import dev.deskseed.audit.AuditActivityFilter
import dev.deskseed.audit.AuditActivityNotFoundException
import dev.deskseed.audit.AuditActivityPage
import dev.deskseed.audit.AuditExplorer
import dev.deskseed.audit.AuditExplorerActor
import dev.deskseed.audit.AuditExplorerOutcome
import dev.deskseed.audit.AuditExportArtifact
import dev.deskseed.audit.AuditExportArtifactStore
import dev.deskseed.audit.AuditExportDownload
import dev.deskseed.audit.AuditExportExpiredException
import dev.deskseed.audit.AuditExportFormat
import dev.deskseed.audit.AuditExportJob
import dev.deskseed.audit.AuditExportNotFoundException
import dev.deskseed.audit.AuditFieldChange
import dev.deskseed.audit.AuditLedgerType
import dev.deskseed.audit.AuditOpenedActivity
import dev.deskseed.audit.AuditProjectionRebuildConflictException
import dev.deskseed.audit.AuditProjectionRebuildResult
import dev.deskseed.audit.AuditProjectionState
import dev.deskseed.audit.AuditProjectionStatus
import dev.deskseed.audit.AuditProtectedContentInvalidException
import dev.deskseed.audit.AuditRevealDeniedException
import dev.deskseed.audit.AuditRevealForbiddenException
import dev.deskseed.audit.AuditRevealReasonInvalidException
import dev.deskseed.audit.AuditRevealTargetInvalidException
import dev.deskseed.audit.AuditRequestContext
import dev.deskseed.audit.AuditSearchContext
import dev.deskseed.audit.CreateAuditExportCommand
import dev.deskseed.audit.ProtectedSearchQueryAudit
import dev.deskseed.audit.SearchQueryAuthenticationException
import dev.deskseed.audit.SearchQueryKeyUnavailableException
import dev.deskseed.audit.SearchQueryRevealResult
import dev.deskseed.audit.SearchQueryRevealState
import dev.deskseed.audit.SearchQueryRevealer
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcAuditExplorer(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val cursorCodec: AuditActivityCursorCodec,
    private val revealProperties: AuditExplorerRevealProperties,
    private val searchQueryRevealer: SearchQueryRevealer,
    private val auditWriter: AdminSecurityAuditWriter,
    private val selfAuditWriter: AuditExplorerSelfAuditWriter,
    private val exportJobStore: JdbcAuditExportJobStore,
    private val exportArtifactStore: AuditExportArtifactStore,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : AuditExplorer {
    @Transactional
    override fun list(
        filters: AuditActivityFilter,
        cursor: String?,
        limit: Int,
        context: AuditRequestContext,
    ): AuditActivityPage {
        require(limit in 1..100) { "Audit page limit must be between 1 and 100" }
        val now = Instant.now(clock)
        val resolved = resolveAndValidate(filters, now)
        appendViewAudit(
            context = context,
            view = "LIST",
            targetId = null,
            filterFingerprint = cursorCodec.filterFingerprint(resolved),
        )
        val position = cursor?.let { cursorCodec.decode(resolved, it) }
        val snapshotAt = position?.snapshotAt ?: now
        val snapshotId = position?.snapshotId ?: MAX_UUID
        val parameters = MapSqlParameterSource()
            .addValue("from", Timestamp.from(resolved.from!!))
            .addValue("to", Timestamp.from(resolved.to!!))
            .addValue("snapshotAt", Timestamp.from(snapshotAt))
            .addValue("snapshotId", snapshotId)
            .addValue("limit", limit + 1)
        val conditions = mutableListOf(
            "occurred_at >= :from",
            "occurred_at < :to",
            "(occurred_at, id) <= (:snapshotAt, :snapshotId)",
        )
        position?.let {
            conditions += "(occurred_at, id) < (:lastOccurredAt, :lastId)"
            parameters.addValue("lastOccurredAt", Timestamp.from(it.lastOccurredAt))
            parameters.addValue("lastId", it.lastId)
        }
        addFilters(resolved, conditions, parameters)
        val rows = jdbcTemplate.query(
            """
            select *
            from audit_activity_projection
            where ${conditions.joinToString("\n  and ")}
            order by occurred_at desc, id desc
            limit :limit
            """.trimIndent(),
            parameters,
        ) { result, _ -> projectionRow(result) }
        val pageRows = rows.take(limit)
        val nextCursor = if (rows.size > limit) {
            pageRows.last().let { last ->
                cursorCodec.encode(
                    resolved,
                    AuditActivityCursor(snapshotAt, snapshotId, last.occurredAt, last.id),
                )
            }
        } else {
            null
        }
        return AuditActivityPage(
            items = pageRows.map(::activity),
            nextCursor = nextCursor,
            snapshotAt = snapshotAt,
            projection = projectionStatus(),
        )
    }

    @Transactional(noRollbackFor = [AuditActivityNotFoundException::class])
    override fun detail(activityId: UUID, context: AuditRequestContext): AuditActivityDetail {
        val row = findProjection(activityId) ?: run {
            appendViewAudit(context, "DETAIL", activityId, null, AdminSecurityOutcome.DENIED)
            throw AuditActivityNotFoundException()
        }
        appendViewAudit(context, "DETAIL", activityId, null)
        val fieldChange = row.fieldName
            ?.takeUnless(::isProtectedField)
            ?.let { AuditFieldChange(it, jsonValue(row.oldValueJson), jsonValue(row.newValueJson)) }
        return AuditActivityDetail(
            activity = activity(row),
            canonicalEventId = row.sourceEventId,
            canonicalParentId = row.sourceParentId,
            fieldChange = fieldChange,
            interactionId = row.interactionId,
            sessionFingerprint = row.sessionFingerprint,
            authType = row.authType,
            ipAddress = row.ipAddress,
            userAgent = row.userAgent,
            search = searchContext(row),
            metadata = allowlistedMetadata(row.metadataJson),
        )
    }

    @Transactional(
        noRollbackFor = [
            AuditRevealDeniedException::class,
            AuditRevealForbiddenException::class,
            AuditRevealReasonInvalidException::class,
            AuditRevealTargetInvalidException::class,
            AuditProtectedContentInvalidException::class,
            AuditActivityNotFoundException::class,
        ],
    )
    override fun revealSearchQuery(
        activityId: UUID,
        reason: String,
        context: AuditRequestContext,
    ): SearchQueryRevealResult {
        if (!context.authorities.contains("audit:search-query:reveal")) {
            appendRevealAudit(
                context,
                activityId,
                AdminSecurityOutcome.DENIED,
                "PERMISSION_DENIED",
                "permission-denied",
                null,
            )
            throw AuditRevealForbiddenException()
        }
        val normalizedReason = reason.trim()
        if (normalizedReason.isEmpty() || normalizedReason.length > 1000 || normalizedReason.any(Char::isISOControl)) {
            appendRevealAudit(
                context,
                activityId,
                AdminSecurityOutcome.DENIED,
                "INVALID_REASON",
                "reason-required",
                null,
            )
            throw AuditRevealReasonInvalidException()
        }
        val now = Instant.now(clock)
        val recentAuthentication = context.authenticatedAt?.plus(revealProperties.recentAuthentication)?.isAfter(now) == true
        val recentMfa = !revealProperties.mfaRequired ||
            context.mfaVerifiedAt?.plus(revealProperties.recentAuthentication)?.isAfter(now) == true
        if (!recentAuthentication || !recentMfa) {
            appendRevealAudit(
                context,
                activityId,
                AdminSecurityOutcome.DENIED,
                "REAUTHENTICATION_REQUIRED",
                normalizedReason,
                null,
            )
            throw AuditRevealDeniedException()
        }
        val row = findProjection(activityId) ?: run {
            appendRevealAudit(
                context,
                activityId,
                AdminSecurityOutcome.DENIED,
                "NOT_FOUND",
                normalizedReason,
                null,
            )
            throw AuditActivityNotFoundException()
        }
        if (row.ledger != AuditLedgerType.ACCESS_SEARCH || row.action != "SEARCH_EXECUTED") {
            appendRevealAudit(
                context,
                activityId,
                AdminSecurityOutcome.DENIED,
                "NOT_SEARCH_EXECUTED",
                normalizedReason,
                null,
            )
            throw AuditRevealTargetInvalidException()
        }
        val material = jdbcTemplate.query(
            """
            select detail.query_redacted, detail.query_fingerprint, detail.query_key_version,
                   ciphertext.key_version as ciphertext_key_version,
                   ciphertext.query_ciphertext, ciphertext.created_at, ciphertext.expires_at
            from search_audit_details detail
            left join search_audit_query_ciphertexts ciphertext
              on ciphertext.access_event_id = detail.access_event_id
            where detail.access_event_id = :eventId
            """.trimIndent(),
            mapOf("eventId" to row.sourceEventId),
        ) { result, _ ->
            SearchQueryMaterial(
                queryRedacted = result.getString("query_redacted"),
                queryFingerprint = result.getString("query_fingerprint"),
                detailKeyVersion = result.getString("query_key_version"),
                ciphertextKeyVersion = result.getString("ciphertext_key_version"),
                ciphertext = result.getBytes("query_ciphertext"),
                createdAt = result.getTimestamp("created_at")?.toInstant(),
                expiresAt = result.getTimestamp("expires_at")?.toInstant(),
            )
        }.singleOrNull() ?: run {
            appendRevealAudit(
                context,
                activityId,
                AdminSecurityOutcome.FAILED,
                "SEARCH_METADATA_UNAVAILABLE",
                normalizedReason,
                null,
            )
            throw AuditActivityNotFoundException()
        }
        if (material.ciphertext == null || material.expiresAt == null || !material.expiresAt.isAfter(now)) {
            appendRevealAudit(
                context,
                activityId,
                AdminSecurityOutcome.FAILED,
                SearchQueryRevealState.RETENTION_EXPIRED.name,
                normalizedReason,
                material.detailKeyVersion,
            )
            return SearchQueryRevealResult(
                activityId,
                SearchQueryRevealState.RETENTION_EXPIRED,
                rawQuery = null,
                keyVersion = material.detailKeyVersion,
                revealedAt = null,
            )
        }
        val keyVersion = material.ciphertextKeyVersion ?: material.detailKeyVersion
        val protected = ProtectedSearchQueryAudit(
            queryRedacted = material.queryRedacted,
            queryFingerprint = material.queryFingerprint,
            keyVersion = keyVersion,
            queryCiphertext = material.ciphertext,
            expiresAt = material.expiresAt,
        )
        val rawQuery = try {
            searchQueryRevealer.reveal(row.sourceEventId, protected)
        } catch (_: SearchQueryKeyUnavailableException) {
            appendRevealAudit(
                context,
                activityId,
                AdminSecurityOutcome.FAILED,
                SearchQueryRevealState.KEY_UNAVAILABLE.name,
                normalizedReason,
                keyVersion,
            )
            return SearchQueryRevealResult(
                activityId,
                SearchQueryRevealState.KEY_UNAVAILABLE,
                rawQuery = null,
                keyVersion = keyVersion,
                revealedAt = null,
            )
        } catch (exception: SearchQueryAuthenticationException) {
            appendRevealAudit(
                context,
                activityId,
                AdminSecurityOutcome.FAILED,
                "AUTHENTICATION_FAILED",
                normalizedReason,
                keyVersion,
            )
            throw AuditProtectedContentInvalidException(exception)
        }
        appendRevealAudit(
            context,
            activityId,
            AdminSecurityOutcome.SUCCEEDED,
            SearchQueryRevealState.AVAILABLE.name,
            normalizedReason,
            keyVersion,
        )
        return SearchQueryRevealResult(
            activityId = activityId,
            state = SearchQueryRevealState.AVAILABLE,
            rawQuery = rawQuery,
            keyVersion = keyVersion,
            revealedAt = now,
        )
    }

    @Transactional
    override fun createExport(
        command: CreateAuditExportCommand,
        context: AuditRequestContext,
    ): AuditExportJob {
        require(context.authorities.contains("audit:export")) { "Audit export authority is required" }
        val now = Instant.now(clock)
        val reason = command.reason.trim()
        require(reason.isNotEmpty() && reason.length <= 1000 && reason.none(Char::isISOControl)) {
            "A bounded export reason is required"
        }
        require(command.fields.isNotEmpty() && command.fields.size <= 20 && command.fields.distinct().size == command.fields.size) {
            "Audit export fields are invalid"
        }
        require(command.fields.all(ALLOWED_EXPORT_FIELDS::contains)) { "Audit export field is not allowlisted" }
        val resolvedFilters = resolveAndValidate(command.filters, now)
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into audit_export_jobs (
                id, requester_id, status, format, filters_json, fields_json, reason,
                permission_snapshot_json, request_id, correlation_id, interaction_id, created_at, snapshot_at
            ) values (
                :id, :requesterId, 'REQUESTED', :format, cast(:filtersJson as jsonb),
                cast(:fieldsJson as jsonb), :reason, cast(:permissionSnapshotJson as jsonb),
                :requestId, :correlationId, :interactionId, :createdAt, :snapshotAt
            )
            """.trimIndent(),
            mapOf(
                "id" to id,
                "requesterId" to context.actorId,
                "format" to command.format.name,
                "filtersJson" to objectMapper.writeValueAsString(exportFilterMap(resolvedFilters)),
                "fieldsJson" to objectMapper.writeValueAsString(command.fields),
                "reason" to reason,
                "permissionSnapshotJson" to objectMapper.writeValueAsString(context.authorities.sorted()),
                "requestId" to context.requestId,
                "correlationId" to context.correlationId,
                "interactionId" to context.interactionId,
                "createdAt" to Timestamp.from(now),
                "snapshotAt" to Timestamp.from(now),
            ),
        )
        jdbcTemplate.update(
            """
            insert into audit_export_artifacts (job_id, state, generation_available, created_at)
            values (:jobId, 'PENDING', false, :createdAt)
            """.trimIndent(),
            mapOf("jobId" to id, "createdAt" to Timestamp.from(now)),
        )
        appendAdminAudit(
            context = context,
            eventType = "AUDIT_EXPORT_REQUESTED",
            targetType = "AUDIT_EXPORT_JOB",
            targetId = id,
            outcome = AdminSecurityOutcome.SUCCEEDED,
            metadata = mapOf(
                "interactionId" to context.interactionId.toString(),
                "reason" to reason,
                "format" to command.format.name,
                "fields" to command.fields.joinToString(","),
                "generationAvailable" to "false",
            ),
        )
        return AuditExportJob(
            id = id,
            status = "REQUESTED",
            createdAt = now,
            format = command.format,
            fields = command.fields,
            artifact = AuditExportArtifact("PENDING", false),
        )
    }

    @Transactional(noRollbackFor = [AuditExportNotFoundException::class])
    override fun getExport(jobId: UUID, context: AuditRequestContext): AuditExportJob {
        require(context.authorities.contains("audit:export")) { "Audit export authority is required" }
        val job = exportJobStore.getForRequester(jobId, context.actorId) ?: run {
            appendAdminAudit(
                context = context,
                eventType = "AUDIT_LOG_VIEWED",
                targetType = "AUDIT_EXPORT_JOB",
                targetId = jobId,
                outcome = AdminSecurityOutcome.DENIED,
                metadata = mapOf(
                    "view" to "EXPORT_STATUS",
                    "interactionId" to context.interactionId.toString(),
                    "state" to "NOT_FOUND_OR_NOT_OWNED",
                ),
            )
            throw AuditExportNotFoundException()
        }
        selfAuditWriter.appendSemanticView(
            adminAudit(
                context = context,
                eventType = "AUDIT_LOG_VIEWED",
                targetType = "AUDIT_EXPORT_JOB",
                targetId = jobId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                metadata = mapOf(
                    "view" to "EXPORT_STATUS",
                    "interactionId" to context.interactionId.toString(),
                    "sessionFingerprint" to context.sessionFingerprint,
                ),
            ),
        )
        return job
    }

    @Transactional(noRollbackFor = [AuditExportNotFoundException::class, AuditExportExpiredException::class])
    override fun openExport(jobId: UUID, context: AuditRequestContext): AuditExportDownload {
        require(context.authorities.contains("audit:export")) { "Audit export authority is required" }
        val now = Instant.now(clock)
        val handle = exportJobStore.readyForRequester(jobId, context.actorId, now) ?: run {
            if (exportJobStore.expiredOrMissingForRequester(jobId, context.actorId, now)) {
                appendAdminAudit(
                    context, "AUDIT_EXPORT_DOWNLOADED", "AUDIT_EXPORT_JOB", jobId, AdminSecurityOutcome.DENIED,
                    mapOf("interactionId" to context.interactionId.toString(), "state" to "EXPIRED"),
                )
                throw AuditExportExpiredException()
            }
            appendAdminAudit(
                context, "AUDIT_EXPORT_DOWNLOADED", "AUDIT_EXPORT_JOB", jobId, AdminSecurityOutcome.DENIED,
                mapOf("interactionId" to context.interactionId.toString(), "state" to "NOT_READY_OR_NOT_OWNED"),
            )
            throw AuditExportNotFoundException()
        }
        val stream = exportArtifactStore.openPrivate(handle.objectKey)
        try {
            appendAdminAudit(
                context, "AUDIT_EXPORT_DOWNLOADED", "AUDIT_EXPORT_JOB", jobId, AdminSecurityOutcome.SUCCEEDED,
                mapOf(
                    "interactionId" to context.interactionId.toString(),
                    "format" to handle.format.name,
                    "checksumPrefix" to handle.checksumSha256.take(12),
                ),
            )
        } catch (exception: RuntimeException) {
            runCatching { stream.close() }
            throw exception
        }
        return AuditExportDownload(
            fileName = "audit-export-$jobId.${if (handle.format == AuditExportFormat.CSV) "csv" else "jsonl"}",
            contentType = handle.contentType,
            checksumSha256 = handle.checksumSha256,
            stream = stream,
        )
    }

    @Transactional
    override fun rebuild(context: AuditRequestContext): AuditProjectionRebuildResult {
        require(context.authorities.contains("audit:projection:rebuild")) {
            "Audit projection rebuild authority is required"
        }
        appendAdminAudit(
            context = context,
            eventType = "AUDIT_PROJECTION_REBUILT",
            targetType = "AUDIT_ACTIVITY_PROJECTION",
            targetId = null,
            outcome = AdminSecurityOutcome.SUCCEEDED,
            metadata = mapOf("interactionId" to context.interactionId.toString()),
        )
        val counts = try {
            jdbcTemplate.queryForMap("select * from rebuild_audit_activity_projection()", emptyMap<String, Any>())
        } catch (exception: DataAccessException) {
            if (exception.causeChain().filterIsInstance<SQLException>().any { it.sqlState == "55P03" }) {
                throw AuditProjectionRebuildConflictException(exception)
            }
            throw exception
        }
        val completedAt = Instant.now(clock)
        return AuditProjectionRebuildResult(
            ticketChangeCount = (counts.getValue("ticket_change_count") as Number).toLong(),
            accessSearchCount = (counts.getValue("access_search_count") as Number).toLong(),
            adminSecurityCount = (counts.getValue("admin_security_count") as Number).toLong(),
            totalCount = (counts.getValue("total_count") as Number).toLong(),
            completedAt = completedAt,
            projection = projectionStatus(),
        )
    }

    private fun resolveAndValidate(filters: AuditActivityFilter, now: Instant): AuditActivityFilter {
        val resolved = filters.copy(
            from = filters.from ?: now.minus(DEFAULT_RANGE),
            to = filters.to ?: now,
            action = filters.action?.trim(),
            field = filters.field?.trim(),
            source = filters.source?.trim(),
            requestId = filters.requestId?.trim(),
            correlationId = filters.correlationId?.trim(),
            searchFingerprint = filters.searchFingerprint?.trim(),
        )
        require(!resolved.from!!.isAfter(resolved.to)) { "Audit date range is invalid" }
        require(Duration.between(resolved.from, resolved.to) <= MAXIMUM_RANGE) {
            "Audit date range cannot exceed 366 days"
        }
        require(resolved.ticketNumber == null || resolved.ticketNumber > 0) { "Ticket number must be positive" }
        validateBounded(resolved.action, 80, "action")
        validateBounded(resolved.field, 60, "field")
        validateBounded(resolved.source, 40, "source")
        validateBounded(resolved.requestId, 100, "requestId")
        validateBounded(resolved.correlationId, 100, "correlationId")
        validateBounded(resolved.searchFingerprint, 100, "searchFingerprint")
        return resolved
    }

    private fun validateBounded(value: String?, maximum: Int, field: String) {
        require(value == null || value.isNotBlank() && value.length <= maximum && value.none(Char::isISOControl)) {
            "$field is invalid"
        }
    }

    private fun addFilters(
        filters: AuditActivityFilter,
        conditions: MutableList<String>,
        parameters: MapSqlParameterSource,
    ) {
        fun add(value: Any?, parameter: String, column: String = parameter) {
            value?.let {
                conditions += "$column = :$parameter"
                parameters.addValue(parameter, it)
            }
        }
        add(filters.ledger?.name, "ledger", "ledger_type")
        add(filters.action, "action")
        add(filters.actorType?.name, "actorType", "actor_type")
        add(filters.actorId, "actorId", "actor_id")
        add(filters.ticketNumber, "ticketNumber", "ticket_number")
        add(filters.groupId, "groupId", "group_id")
        add(filters.field, "field", "field_name")
        add(filters.source, "source")
        add(filters.outcome?.name, "outcome")
        add(filters.requestId, "requestId", "request_id")
        add(filters.correlationId, "correlationId", "correlation_id")
        add(filters.searchFingerprint, "searchFingerprint", "search_fingerprint")
    }

    private fun findProjection(id: UUID): ProjectionRow? = jdbcTemplate.query(
        "select * from audit_activity_projection where id = :id",
        mapOf("id" to id),
    ) { result, _ -> projectionRow(result) }.singleOrNull()

    private fun projectionStatus(): AuditProjectionStatus = jdbcTemplate.queryForObject(
        """
        select
               case
                   when pg_try_advisory_xact_lock_shared(hashtext('deskseed:audit-activity-projection:rebuild'))
                       then state
                   else 'REBUILDING'
               end as state,
               last_rebuilt_at,
               projected_count
        from audit_activity_projection_state where id = 1
        """.trimIndent(),
        emptyMap<String, Any>(),
    ) { result, _ ->
        AuditProjectionStatus(
            state = AuditProjectionState.valueOf(result.getString("state")),
            projectedCount = result.getLong("projected_count"),
            lastRebuiltAt = result.getTimestamp("last_rebuilt_at")?.toInstant(),
        )
    }

    private fun searchContext(row: ProjectionRow): AuditSearchContext? {
        val search = when {
            row.action == "SEARCH_EXECUTED" -> row
            row.originSearchEventId != null -> jdbcTemplate.query(
                """
                select * from audit_activity_projection
                where ledger_type = 'ACCESS_SEARCH' and source_event_id = :sourceEventId
                """.trimIndent(),
                mapOf("sourceEventId" to row.originSearchEventId),
            ) { result, _ -> projectionRow(result) }.singleOrNull()
            else -> null
        } ?: return null
        val redacted = search.queryRedacted ?: return null
        val fingerprint = search.searchFingerprint ?: return null
        val opened = jdbcTemplate.query(
            """
            select id, ticket_number, occurred_at
            from audit_activity_projection
            where ledger_type = 'ACCESS_SEARCH'
              and origin_search_event_id = :sourceEventId
              and action = 'SEARCH_RESULT_OPENED'
              and ticket_number is not null
            order by occurred_at, id
            limit :openedLimit
            """.trimIndent(),
            mapOf("sourceEventId" to search.sourceEventId, "openedLimit" to MAX_OPENED_ACTIVITIES + 1),
        ) { result, _ ->
            AuditOpenedActivity(
                activityId = result.getObject("id", UUID::class.java),
                ticketNumber = result.getLong("ticket_number"),
                occurredAt = result.getTimestamp("occurred_at").toInstant(),
            )
        }
        val openedActivityCount = jdbcTemplate.queryForObject(
            """
            select count(*)
            from audit_activity_projection
            where ledger_type = 'ACCESS_SEARCH'
              and origin_search_event_id = :sourceEventId
              and action = 'SEARCH_RESULT_OPENED'
              and ticket_number is not null
            """.trimIndent(),
            mapOf("sourceEventId" to search.sourceEventId),
            Long::class.java,
        ) ?: 0
        return AuditSearchContext(
            queryRedacted = redacted,
            queryFingerprint = fingerprint,
            filters = stringMap(search.searchFiltersJson),
            sort = search.searchSort,
            resultCount = search.searchResultCount ?: 0,
            originSearchActivityId = search.id.takeIf { row.originSearchEventId != null },
            openedActivities = opened.take(MAX_OPENED_ACTIVITIES),
            openedActivityCount = openedActivityCount,
            openedActivitiesTruncated = openedActivityCount > MAX_OPENED_ACTIVITIES,
        )
    }

    private fun projectionRow(result: ResultSet) = ProjectionRow(
        id = result.getObject("id", UUID::class.java),
        ledger = AuditLedgerType.valueOf(result.getString("ledger_type")),
        sourceEventId = result.getObject("source_event_id", UUID::class.java),
        sourceParentId = result.getObject("source_parent_id", UUID::class.java),
        occurredAt = result.getTimestamp("occurred_at").toInstant(),
        actorType = ActorType.valueOf(result.getString("actor_type")),
        actorId = result.getObject("actor_id", UUID::class.java),
        actorDisplaySnapshot = result.getString("actor_display_snapshot"),
        source = result.getString("source"),
        action = result.getString("action"),
        outcome = AuditExplorerOutcome.valueOf(result.getString("outcome")),
        resourceType = result.getString("resource_type"),
        resourceId = result.getObject("resource_id", UUID::class.java),
        ticketNumber = result.getObject("ticket_number")?.let { (it as Number).toLong() },
        groupId = result.getObject("group_id", UUID::class.java),
        fieldName = result.getString("field_name"),
        oldValueJson = result.getString("old_value_json"),
        newValueJson = result.getString("new_value_json"),
        metadataJson = result.getString("metadata_json"),
        requestId = result.getString("request_id"),
        correlationId = result.getString("correlation_id"),
        interactionId = result.getObject("interaction_id", UUID::class.java),
        sessionFingerprint = result.getString("session_fingerprint"),
        authType = result.getString("auth_type"),
        ipAddress = result.getString("ip_address"),
        userAgent = result.getString("user_agent"),
        originSearchEventId = result.getObject("origin_search_event_id", UUID::class.java),
        queryRedacted = result.getString("query_redacted"),
        searchFingerprint = result.getString("search_fingerprint"),
        searchFiltersJson = result.getString("search_filters_json"),
        searchSort = result.getString("search_sort"),
        searchResultCount = result.getObject("search_result_count")?.let { (it as Number).toLong() },
        protectedContentAvailable = result.getBoolean("protected_content_available"),
    )

    private fun activity(row: ProjectionRow) = AuditActivity(
        id = row.id,
        ledger = row.ledger,
        action = row.action,
        actor = AuditExplorerActor(row.actorType, row.actorId, row.actorDisplaySnapshot),
        occurredAt = row.occurredAt,
        ticketNumber = row.ticketNumber,
        groupId = row.groupId,
        field = row.fieldName,
        resourceType = row.resourceType,
        resourceId = row.resourceId,
        summary = buildString {
            append(row.action.replace('_', ' ').lowercase())
            row.ticketNumber?.let { append(" · ticket #$it") }
        },
        source = row.source,
        outcome = row.outcome,
        requestId = row.requestId,
        correlationId = row.correlationId,
        protectedContentAvailable = row.protectedContentAvailable,
        searchFingerprint = row.searchFingerprint,
    )

    private fun appendViewAudit(
        context: AuditRequestContext,
        view: String,
        targetId: UUID?,
        filterFingerprint: String?,
        outcome: AdminSecurityOutcome = AdminSecurityOutcome.SUCCEEDED,
    ) {
        selfAuditWriter.appendSemanticView(
            adminAudit(
                context = context,
                eventType = "AUDIT_LOG_VIEWED",
                targetType = if (view == "LIST") "AUDIT_ACTIVITY_EXPLORER" else "AUDIT_ACTIVITY",
                targetId = targetId,
                outcome = outcome,
                metadata = buildMap {
                    put("view", view)
                    put("interactionId", context.interactionId.toString())
                    put("sessionFingerprint", context.sessionFingerprint)
                    context.ipAddress?.let { put("ipAddress", it) }
                    context.userAgent?.let { put("userAgent", it) }
                    filterFingerprint?.let { put("filterFingerprint", it) }
                },
            ),
        )
    }

    private fun appendAdminAudit(
        context: AuditRequestContext,
        eventType: String,
        targetType: String,
        targetId: UUID?,
        outcome: AdminSecurityOutcome,
        metadata: Map<String, String>,
    ) {
        auditWriter.append(adminAudit(context, eventType, targetType, targetId, outcome, metadata))
    }

    private fun adminAudit(
        context: AuditRequestContext,
        eventType: String,
        targetType: String,
        targetId: UUID?,
        outcome: AdminSecurityOutcome,
        metadata: Map<String, String>,
    ) = AdminSecurityAudit(
        eventType = eventType,
        actorType = ActorType.STAFF,
        actorId = context.actorId,
        actorDisplaySnapshot = context.actorDisplayName,
        source = RequestSource.ADMIN_UI,
        targetType = targetType,
        targetId = targetId,
        outcome = outcome,
        requestId = context.requestId,
        correlationId = context.correlationId,
        metadata = metadata,
        occurredAt = Instant.now(clock),
    )

    private fun appendRevealAudit(
        context: AuditRequestContext,
        activityId: UUID,
        outcome: AdminSecurityOutcome,
        state: String,
        reason: String,
        keyVersion: String?,
    ) = appendAdminAudit(
        context = context,
        eventType = "AUDIT_SENSITIVE_CONTENT_REVEALED",
        targetType = "SEARCH_QUERY",
        targetId = activityId,
        outcome = outcome,
        metadata = buildMap {
            put("interactionId", context.interactionId.toString())
            put("state", state)
            put("reason", reason)
            keyVersion?.let { put("keyVersion", it) }
        },
    )

    private fun jsonValue(json: String?): Any? = json?.let {
        objectMapper.readValue(it, Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun allowlistedMetadata(json: String): Map<String, Any?> {
        val raw = objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
        return raw.filterKeys(ALLOWED_METADATA_KEYS::contains)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringMap(json: String?): Map<String, String> {
        if (json == null) return emptyMap()
        val raw = objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
        return raw.mapValues { (_, value) -> value?.toString().orEmpty() }
    }

    private fun isProtectedField(field: String): Boolean = PROTECTED_FIELD.containsMatchIn(field)

    private fun exportFilterMap(filters: AuditActivityFilter): Map<String, String> = buildMap {
        filters.from?.let { put("from", it.toString()) }
        filters.to?.let { put("to", it.toString()) }
        filters.ledger?.let { put("ledger", it.name) }
        filters.action?.let { put("action", it) }
        filters.actorType?.let { put("actorType", it.name) }
        filters.actorId?.let { put("actorId", it.toString()) }
        filters.ticketNumber?.let { put("ticketNumber", it.toString()) }
        filters.groupId?.let { put("groupId", it.toString()) }
        filters.field?.let { put("field", it) }
        filters.source?.let { put("source", it) }
        filters.outcome?.let { put("outcome", it.name) }
        filters.requestId?.let { put("requestId", it) }
        filters.correlationId?.let { put("correlationId", it) }
        filters.searchFingerprint?.let { put("searchFingerprint", it) }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private data class ProjectionRow(
        val id: UUID,
        val ledger: AuditLedgerType,
        val sourceEventId: UUID,
        val sourceParentId: UUID?,
        val occurredAt: Instant,
        val actorType: ActorType,
        val actorId: UUID?,
        val actorDisplaySnapshot: String,
        val source: String,
        val action: String,
        val outcome: AuditExplorerOutcome,
        val resourceType: String?,
        val resourceId: UUID?,
        val ticketNumber: Long?,
        val groupId: UUID?,
        val fieldName: String?,
        val oldValueJson: String?,
        val newValueJson: String?,
        val metadataJson: String,
        val requestId: String?,
        val correlationId: String?,
        val interactionId: UUID?,
        val sessionFingerprint: String?,
        val authType: String?,
        val ipAddress: String?,
        val userAgent: String?,
        val originSearchEventId: UUID?,
        val queryRedacted: String?,
        val searchFingerprint: String?,
        val searchFiltersJson: String?,
        val searchSort: String?,
        val searchResultCount: Long?,
        val protectedContentAvailable: Boolean,
    )

    private data class SearchQueryMaterial(
        val queryRedacted: String,
        val queryFingerprint: String,
        val detailKeyVersion: String,
        val ciphertextKeyVersion: String?,
        val ciphertext: ByteArray?,
        val createdAt: Instant?,
        val expiresAt: Instant?,
    )

    private companion object {
        val DEFAULT_RANGE: Duration = Duration.ofDays(7)
        val MAXIMUM_RANGE: Duration = Duration.ofDays(366)
        val MAX_UUID: UUID = UUID(-1, -1)
        const val MAX_OPENED_ACTIVITIES = 100
        val PROTECTED_FIELD = Regex("comment|body|description", RegexOption.IGNORE_CASE)
        val ALLOWED_METADATA_KEYS = setOf(
            "action",
            "assigneeId",
            "authType",
            "channel",
            "childTicketNumber",
            "contentLength",
            "contentSha256",
            "field",
            "filterFingerprint",
            "format",
            "generationAvailable",
            "groupId",
            "httpStatus",
            "interactionId",
            "kind",
            "keyVersion",
            "parentTicketNumber",
            "priority",
            "reason",
            "relationId",
            "relationType",
            "role",
            "staffId",
            "state",
            "status",
            "view",
            "visibility",
        )
        val ALLOWED_EXPORT_FIELDS = setOf(
            "occurredAt",
            "ledger",
            "action",
            "actor",
            "ticketNumber",
            "groupId",
            "field",
            "source",
            "outcome",
            "requestId",
            "correlationId",
            "searchFingerprint",
        )
    }
}
