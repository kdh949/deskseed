package dev.deskseed.platformapi.internal

import dev.deskseed.audit.AccessAuditAuthType
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.TicketResourceReadAccessAudit
import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.AuthenticatedIntegrationClient
import dev.deskseed.integration.IntegrationAuthorizationPolicy
import dev.deskseed.integration.IntegrationResourceRequest
import dev.deskseed.integration.IntegrationScope
import dev.deskseed.integration.IntegrationTicketField
import dev.deskseed.integration.IntegrationTicketKind
import dev.deskseed.ticketing.AddPlatformInternalCommentCommand
import dev.deskseed.ticketing.CreatePlatformTicketCommand
import dev.deskseed.ticketing.PlatformTicketActor
import dev.deskseed.ticketing.PlatformTicketKind
import dev.deskseed.ticketing.PlatformTicketNotFoundException
import dev.deskseed.ticketing.PlatformTicketService
import dev.deskseed.ticketing.PlatformTicketView
import dev.deskseed.ticketing.TicketField
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.UpdatePlatformTicketCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal class PlatformScopeDeniedException : RuntimeException()
internal class PlatformResourceDeniedException : RuntimeException()

internal data class PlatformRequestContext(
    val requestId: String,
    val correlationId: String,
    val remoteIp: String,
    val userAgent: String?,
)

internal data class PlatformCreateInput(
    val kind: PlatformTicketKind,
    val subject: String,
    val message: String,
    val requesterName: String?,
    val requesterEmail: String?,
    val priority: TicketPriority,
    val groupId: UUID?,
    val assigneeId: UUID?,
)

internal data class PlatformUpdateInput(
    val changedFields: Set<TicketField>,
    val status: TicketStatus?,
    val priority: TicketPriority?,
    val groupId: UUID?,
    val assigneeId: UUID?,
)

@Service
internal class PlatformTicketApplicationService(
    private val customerDirectory: CustomerDirectory,
    private val ticketService: PlatformTicketService,
    private val authorizationPolicy: IntegrationAuthorizationPolicy,
    private val idempotencyStore: PlatformIdempotencyStore,
    private val accessAuditWriter: AccessAuditWriter,
    private val securityAuditRecorder: PlatformSecurityAuditRecorder,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional
    fun create(
        principal: AuthenticatedIntegrationClient,
        idempotencyKey: String,
        input: PlatformCreateInput,
        context: PlatformRequestContext,
    ): PlatformStoredResponse {
        val operationId = "platformCreateTicket"
        authorize(
            principal,
            IntegrationScope.TICKETS_CREATE,
            IntegrationResourceRequest(input.groupId, input.kind.toIntegrationKind()),
            operationId,
            context,
        )
        return idempotencyStore.execute(
            principal.id,
            operationId,
            idempotencyKey,
            mapOf("operationId" to operationId, "path" to "/tickets", "body" to input),
        ) {
            val requesterId = when (input.kind) {
                PlatformTicketKind.CUSTOMER_REQUEST -> customerDirectory.createUnverified(
                    input.requesterName ?: throw dev.deskseed.ticketing.PlatformTicketInvalidException("REQUESTER_REQUIRED"),
                    input.requesterEmail ?: throw dev.deskseed.ticketing.PlatformTicketInvalidException("REQUESTER_REQUIRED"),
                ).id
                PlatformTicketKind.INTERNAL_WORK_ITEM -> null
            }
            val view = ticketService.create(
                CreatePlatformTicketCommand(
                    input.kind,
                    requesterId,
                    input.subject,
                    input.message,
                    input.priority,
                    input.groupId,
                    input.assigneeId,
                    PlatformTicketActor(principal.id, principal.name),
                    commandContext(principal, operationId, idempotencyKey, context),
                ),
            )
            platformResponse(201, view, view.version) to view.id
        }
    }

    @Transactional
    fun read(
        principal: AuthenticatedIntegrationClient,
        ticketNumber: Long,
        context: PlatformRequestContext,
    ): PlatformStoredResponse {
        val operationId = "platformGetTicket"
        requireScope(principal, IntegrationScope.TICKETS_READ, operationId, context)
        val view = ticketService.find(ticketNumber) ?: throw PlatformTicketNotFoundException()
        authorizeResource(principal, IntegrationScope.TICKETS_READ, view, emptySet(), operationId, context)
        accessAuditWriter.appendTicketResourceRead(
            TicketResourceReadAccessAudit(
                context = AccessAuditContext(
                    actorType = principal.actorType,
                    actorId = principal.id,
                    actorDisplaySnapshot = principal.name,
                    source = RequestSource.PLATFORM_API,
                    sessionFingerprint = null,
                    authType = AccessAuditAuthType.API_KEY,
                    requestId = context.requestId,
                    correlationId = context.correlationId,
                    ipAddress = context.remoteIp,
                    userAgent = context.userAgent,
                ),
                ticketId = view.id,
                ticketNumber = view.ticketNumber,
                interactionId = UUID.randomUUID(),
                outcome = AccessAuditOutcome.SUCCEEDED,
                httpStatus = 200,
                occurredAt = Instant.now(clock),
            ),
        )
        return platformResponse(200, view, view.version)
    }

    @Transactional
    fun update(
        principal: AuthenticatedIntegrationClient,
        ticketNumber: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        input: PlatformUpdateInput,
        context: PlatformRequestContext,
    ): PlatformStoredResponse {
        val operationId = "platformUpdateTicket"
        requireScope(principal, IntegrationScope.TICKETS_UPDATE, operationId, context)
        val current = ticketService.find(ticketNumber) ?: throw PlatformTicketNotFoundException()
        authorizeResource(principal, IntegrationScope.TICKETS_UPDATE, current, input.changedFields, operationId, context)
        if (TicketField.GROUP_ID in input.changedFields) {
            authorize(
                principal,
                IntegrationScope.TICKETS_UPDATE,
                IntegrationResourceRequest(
                    input.groupId,
                    current.kind.toIntegrationKind(),
                    input.changedFields.map { IntegrationTicketField.valueOf(it.name) }.toSet(),
                ),
                operationId,
                context,
            )
        }
        return idempotencyStore.execute(
            principal.id,
            operationId,
            idempotencyKey,
            mapOf(
                "operationId" to operationId,
                "path" to "/tickets/$ticketNumber",
                "ifMatch" to expectedVersion,
                "body" to mapOf(
                    "fields" to input.changedFields.map(TicketField::externalName).sorted(),
                    "status" to input.status?.name,
                    "priority" to input.priority?.name,
                    "groupId" to input.groupId?.toString(),
                    "assigneeId" to input.assigneeId?.toString(),
                ),
            ),
        ) {
            val view = ticketService.update(
                UpdatePlatformTicketCommand(
                    ticketNumber,
                    expectedVersion,
                    input.changedFields,
                    input.status,
                    input.priority,
                    input.groupId,
                    input.assigneeId,
                    PlatformTicketActor(principal.id, principal.name),
                    commandContext(principal, operationId, idempotencyKey, context),
                ),
            )
            platformResponse(200, view, view.version) to view.id
        }
    }

    @Transactional
    fun addInternalComment(
        principal: AuthenticatedIntegrationClient,
        ticketNumber: Long,
        idempotencyKey: String,
        body: String,
        context: PlatformRequestContext,
    ): PlatformStoredResponse {
        val operationId = "platformAddInternalComment"
        requireScope(principal, IntegrationScope.TICKETS_COMMENT_INTERNAL, operationId, context)
        val current = ticketService.find(ticketNumber) ?: throw PlatformTicketNotFoundException()
        authorizeResource(principal, IntegrationScope.TICKETS_COMMENT_INTERNAL, current, emptySet(), operationId, context)
        return idempotencyStore.execute(
            principal.id,
            operationId,
            idempotencyKey,
            mapOf("operationId" to operationId, "path" to "/tickets/$ticketNumber/internal-comments", "body" to body),
        ) {
            val comment = ticketService.addInternalComment(
                AddPlatformInternalCommentCommand(
                    ticketNumber,
                    body,
                    PlatformTicketActor(principal.id, principal.name),
                    commandContext(principal, operationId, idempotencyKey, context),
                ),
            )
            val responseBody = mapOf(
                "id" to comment.id,
                "ticketNumber" to comment.ticketNumber,
                "visibility" to comment.visibility.name,
                "body" to comment.body,
                "createdAt" to comment.createdAt,
            )
            PlatformStoredResponse(
                201,
                mapOf("ETag" to etag(comment.ticketVersion)),
                objectMapper.writeValueAsString(responseBody),
                false,
            ) to comment.ticketId
        }
    }

    private fun authorize(
        principal: AuthenticatedIntegrationClient,
        scope: IntegrationScope,
        resource: IntegrationResourceRequest,
        operationId: String,
        context: PlatformRequestContext,
    ) {
        requireScope(principal, scope, operationId, context)
        if (!authorizationPolicy.isAllowed(principal, scope, resource)) {
            securityAuditRecorder.denied(principal, context.requestId, context.correlationId, "RESOURCE_CONSTRAINT_DENIED", operationId)
            throw PlatformResourceDeniedException()
        }
    }

    private fun authorizeResource(
        principal: AuthenticatedIntegrationClient,
        scope: IntegrationScope,
        view: PlatformTicketView,
        fields: Set<TicketField>,
        operationId: String,
        context: PlatformRequestContext,
    ) = authorize(
        principal,
        scope,
        IntegrationResourceRequest(
            view.groupId,
            view.kind.toIntegrationKind(),
            fields.map { IntegrationTicketField.valueOf(it.name) }.toSet(),
        ),
        operationId,
        context,
    )

    private fun requireScope(
        principal: AuthenticatedIntegrationClient,
        scope: IntegrationScope,
        operationId: String,
        context: PlatformRequestContext,
    ) {
        if (scope !in principal.scopes) {
            securityAuditRecorder.denied(principal, context.requestId, context.correlationId, "SCOPE_DENIED", operationId)
            throw PlatformScopeDeniedException()
        }
    }

    private fun commandContext(
        principal: AuthenticatedIntegrationClient,
        operationId: String,
        idempotencyKey: String,
        context: PlatformRequestContext,
    ) = CommandContext(
        RequestSource.PLATFORM_API,
        context.requestId,
        context.correlationId,
        idempotencyStore.opaqueCommandId(principal.id, operationId, idempotencyKey),
    )

    private fun platformResponse(status: Int, view: PlatformTicketView, version: Long) = PlatformStoredResponse(
        status,
        mapOf("ETag" to etag(version)),
        objectMapper.writeValueAsString(
            mapOf(
                "ticketNumber" to view.ticketNumber,
                "kind" to view.kind.name,
                "subject" to view.subject,
                "status" to view.status.name,
                "priority" to view.priority.name,
                "groupId" to view.groupId,
                "assigneeId" to view.assigneeId,
                "version" to view.version,
                "createdAt" to view.createdAt,
                "updatedAt" to view.updatedAt,
            ),
        ),
        false,
    )

    private fun etag(version: Long) = "\"ticket-v$version\""

    private fun PlatformTicketKind.toIntegrationKind() = when (this) {
        PlatformTicketKind.CUSTOMER_REQUEST -> IntegrationTicketKind.CUSTOMER_REQUEST
        PlatformTicketKind.INTERNAL_WORK_ITEM -> IntegrationTicketKind.INTERNAL_TASK
    }
}
