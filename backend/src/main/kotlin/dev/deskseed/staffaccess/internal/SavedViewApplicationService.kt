package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.SavedViewExecutedAccessAudit
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.StaffAuthorityCatalog
import dev.deskseed.organization.StaffRole
import dev.deskseed.ticketing.SavedTicketView
import dev.deskseed.ticketing.SavedViewAccessDeniedException
import dev.deskseed.ticketing.SavedViewConflictException
import dev.deskseed.ticketing.SavedViewDefinition
import dev.deskseed.ticketing.SavedViewDefinitionRules
import dev.deskseed.ticketing.SavedViewNotFoundException
import dev.deskseed.ticketing.SavedViewOrder
import dev.deskseed.ticketing.SavedViewScope
import dev.deskseed.ticketing.SavedViewStore
import dev.deskseed.ticketing.StaffTicketCursor
import dev.deskseed.ticketing.StaffTicketListFilter
import dev.deskseed.ticketing.StaffTicketReadStore
import dev.deskseed.ticketing.StaffTicketSummary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class SavedViewListItem(
    val view: SavedTicketView,
    val ticketCount: Long?,
    val ticketCountState: String,
    val ticketCountAsOf: Instant?,
)

internal data class SavedViewTicketPage(
    val items: List<StaffTicketSummary>,
    val nextCursor: String?,
    val sort: String,
)

internal data class SavedViewPreview(
    val items: List<StaffTicketSummary>,
    val ticketCount: Long,
    val sort: String,
    val ticketCountAsOf: Instant,
)

@Service
internal class SavedViewApplicationService(
    private val savedViewStore: SavedViewStore,
    private val ticketStore: StaffTicketReadStore,
    private val cursorCodec: AgentTicketCursorCodec,
    private val accessAuditWriter: AccessAuditWriter,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
    private val adminSecurityAuditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(principal: StaffPrincipal): List<SavedViewListItem> {
        requireActive(principal)
        val views = savedViewStore.listVisible(principal.id)
        val countBatch = ticketStore.countSavedViews(
            principal.id,
            views.take(SavedViewDefinitionRules.MAX_VISIBLE_COUNTED_VIEWS),
        )
        return views.mapIndexed { index, view ->
            SavedViewListItem(
                view = view,
                ticketCount = countBatch?.counts?.get(view.id),
                ticketCountState = if (index < SavedViewDefinitionRules.MAX_VISIBLE_COUNTED_VIEWS) {
                    "EXACT"
                } else {
                    "OMITTED_VISIBLE_LIMIT"
                },
                ticketCountAsOf = if (index < SavedViewDefinitionRules.MAX_VISIBLE_COUNTED_VIEWS) {
                    checkNotNull(countBatch).asOf
                } else {
                    null
                },
            )
        }
    }

    @Transactional
    fun create(
        principal: StaffPrincipal,
        scope: SavedViewScope,
        definition: SavedViewDefinition,
        context: AgentReadRequestContext,
    ): SavedTicketView {
        requireActive(principal)
        require(scope != SavedViewScope.SYSTEM) { "SYSTEM saved views cannot be created" }
        SavedViewDefinitionRules.validate(definition)
        if (scope == SavedViewScope.SHARED) requireSharedManager(principal)
        val now = Instant.now(clock)
        val view = savedViewStore.create(
            key = "pv-${UUID.randomUUID().toString().replace("-", "")}",
            scope = scope,
            ownerStaffId = if (scope == SavedViewScope.PERSONAL) principal.id else null,
            definition = definition,
            createdAt = now,
        )
        appendConfigurationAudit("SAVED_VIEW_CREATED", principal, view, context, now)
        return view
    }

    @Transactional
    fun update(
        principal: StaffPrincipal,
        viewKey: String,
        expectedVersion: Long,
        definition: SavedViewDefinition,
        context: AgentReadRequestContext,
    ): SavedTicketView {
        requireActive(principal)
        SavedViewDefinitionRules.validate(definition)
        val current = writableView(principal, viewKey)
        val now = Instant.now(clock)
        val updated = savedViewStore.update(current.id, expectedVersion, definition, now)
        appendConfigurationAudit("SAVED_VIEW_UPDATED", principal, updated, context, now)
        return updated
    }

    @Transactional
    fun delete(
        principal: StaffPrincipal,
        viewKey: String,
        expectedVersion: Long,
        context: AgentReadRequestContext,
    ) {
        requireActive(principal)
        val current = writableView(principal, viewKey)
        val now = Instant.now(clock)
        if (!savedViewStore.delete(current.id, expectedVersion, now)) throw SavedViewNotFoundException()
        appendConfigurationAudit("SAVED_VIEW_DELETED", principal, current, context, now)
    }

    @Transactional
    fun reorder(
        principal: StaffPrincipal,
        scope: SavedViewScope,
        expectedOrderVersion: Long,
        viewKeys: List<String>,
        context: AgentReadRequestContext,
    ): SavedViewOrder {
        requireActive(principal)
        require(scope != SavedViewScope.SYSTEM) { "SYSTEM saved views cannot be reordered" }
        if (scope == SavedViewScope.SHARED) requireSharedManager(principal)
        val now = Instant.now(clock)
        val order = savedViewStore.reorder(
            scope = scope,
            ownerStaffId = if (scope == SavedViewScope.PERSONAL) principal.id else null,
            expectedOrderVersion = expectedOrderVersion,
            viewKeys = viewKeys,
            updatedAt = now,
        )
        appendOrderAudit(principal, order, context, now)
        return order
    }

    @Transactional
    fun preview(
        principal: StaffPrincipal,
        definition: SavedViewDefinition,
        interactionId: UUID,
        context: AgentReadRequestContext,
    ): SavedViewPreview {
        requireActive(principal)
        SavedViewDefinitionRules.validate(definition)
        val now = Instant.now(clock)
        val previewId = UUID.nameUUIDFromBytes(
            "saved-view-preview:${SavedViewDefinitionRules.fingerprint(definition)}".toByteArray(StandardCharsets.UTF_8),
        )
        val virtualView = SavedTicketView(
            id = previewId,
            key = "preview",
            scope = SavedViewScope.PERSONAL,
            ownerStaffId = principal.id,
            definition = definition,
            active = true,
            definitionVersion = 1,
            orderVersion = 1,
            categoryPath = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        val rows = ticketStore.listSavedView(
            actorId = principal.id,
            conditions = definition.conditions,
            filters = StaffTicketListFilter(),
            cursor = null,
            limit = PREVIEW_LIMIT,
        )
        val countBatch = checkNotNull(ticketStore.countSavedViews(principal.id, listOf(virtualView)))
        val count = countBatch.counts[previewId] ?: 0L
        appendExecutionAudit(principal, previewId, interactionId, context, now)
        return SavedViewPreview(rows, count, definition.sort, countBatch.asOf)
    }

    @Transactional
    fun listTickets(
        principal: StaffPrincipal,
        viewKey: String,
        filters: StaffTicketListFilter,
        cursor: String?,
        limit: Int,
        interactionId: UUID,
        context: AgentReadRequestContext,
    ): SavedViewTicketPage {
        requireActive(principal)
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val view = readableView(principal, viewKey)
        val decodedCursor = cursor?.let { cursorCodec.decode(view.key, filters, it) }
        val rows = ticketStore.listSavedView(
            actorId = principal.id,
            conditions = view.definition.conditions,
            filters = filters,
            cursor = decodedCursor,
            limit = limit + 1,
        )
        val items = rows.take(limit)
        val nextCursor = if (rows.size > limit) {
            val last = checkNotNull(items.lastOrNull())
            cursorCodec.encode(view.key, filters, StaffTicketCursor(last.updatedAt, last.ticketNumber))
        } else {
            null
        }
        appendExecutionAudit(principal, view.id, interactionId, context, Instant.now(clock))
        return SavedViewTicketPage(items, nextCursor, view.definition.sort)
    }

    private fun readableView(principal: StaffPrincipal, viewKey: String): SavedTicketView {
        val view = savedViewStore.findByKey(viewKey) ?: throw SavedViewNotFoundException()
        SavedViewDefinitionRules.validate(view.definition)
        if (view.scope == SavedViewScope.PERSONAL && view.ownerStaffId != principal.id) {
            throw SavedViewNotFoundException()
        }
        return view
    }

    private fun writableView(principal: StaffPrincipal, viewKey: String): SavedTicketView {
        val view = readableView(principal, viewKey)
        when (view.scope) {
            SavedViewScope.SYSTEM -> throw SavedViewAccessDeniedException()
            SavedViewScope.PERSONAL -> if (view.ownerStaffId != principal.id) throw SavedViewAccessDeniedException()
            SavedViewScope.SHARED -> requireSharedManager(principal)
        }
        return view
    }

    private fun requireSharedManager(principal: StaffPrincipal) {
        if (principal.role != StaffRole.ADMIN || StaffAuthorityCatalog.SAVED_VIEW_SHARED_MANAGE !in principal.authorities) {
            throw SavedViewAccessDeniedException()
        }
    }

    private fun requireActive(principal: StaffPrincipal) {
        require(principal.id != UUID(0, 0)) { "Active staff principal is required" }
    }

    private fun appendExecutionAudit(
        principal: StaffPrincipal,
        viewId: UUID,
        interactionId: UUID,
        context: AgentReadRequestContext,
        occurredAt: Instant,
    ) {
        accessAuditWriter.appendSavedViewExecuted(
            SavedViewExecutedAccessAudit(
                context = context.toAccessAuditContext(
                    principal,
                    sessionFingerprint.fingerprint(context.sessionId),
                ),
                viewId = viewId,
                interactionId = interactionId,
                outcome = AccessAuditOutcome.SUCCEEDED,
                httpStatus = 200,
                occurredAt = occurredAt,
            ),
        )
    }

    private fun appendConfigurationAudit(
        eventType: String,
        principal: StaffPrincipal,
        view: SavedTicketView,
        context: AgentReadRequestContext,
        occurredAt: Instant,
    ) {
        adminSecurityAuditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.STAFF,
                actorId = principal.id,
                actorDisplaySnapshot = principal.displayName,
                source = RequestSource.AGENT_UI,
                targetType = "SAVED_VIEW",
                targetId = view.id,
                outcome = dev.deskseed.audit.AdminSecurityOutcome.SUCCEEDED,
                requestId = context.requestId,
                correlationId = context.correlationId,
                metadata = mapOf(
                    "scope" to view.scope.name,
                    "definitionVersion" to view.definitionVersion.toString(),
                    "conditionFingerprint" to SavedViewDefinitionRules.fingerprint(view.definition),
                ),
                occurredAt = occurredAt,
            ),
        )
    }

    private fun appendOrderAudit(
        principal: StaffPrincipal,
        order: SavedViewOrder,
        context: AgentReadRequestContext,
        occurredAt: Instant,
    ) {
        adminSecurityAuditWriter.append(
            AdminSecurityAudit(
                eventType = "SAVED_VIEW_REORDERED",
                actorType = ActorType.STAFF,
                actorId = principal.id,
                actorDisplaySnapshot = principal.displayName,
                source = RequestSource.AGENT_UI,
                targetType = "SAVED_VIEW_ORDER",
                targetId = null,
                outcome = dev.deskseed.audit.AdminSecurityOutcome.SUCCEEDED,
                requestId = context.requestId,
                correlationId = context.correlationId,
                metadata = mapOf(
                    "scope" to order.scope.name,
                    "orderVersion" to order.orderVersion.toString(),
                    "viewCount" to order.viewKeys.size.toString(),
                ),
                occurredAt = occurredAt,
            ),
        )
    }

    private companion object {
        const val PREVIEW_LIMIT = 20
    }
}
