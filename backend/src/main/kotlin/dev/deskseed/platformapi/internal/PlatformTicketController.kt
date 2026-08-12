package dev.deskseed.platformapi.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.integration.AuthenticatedIntegrationClient
import dev.deskseed.ticketing.PlatformTicketInvalidException
import dev.deskseed.ticketing.PlatformTicketKind
import dev.deskseed.ticketing.TicketField
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

@RestController
@RequestMapping("/api/v1/platform/tickets")
internal class PlatformTicketController(
    private val application: PlatformTicketApplicationService,
) {
    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: AuthenticatedIntegrationClient,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody body: CreatePlatformTicketRequest,
        request: HttpServletRequest,
    ): ResponseEntity<String> = application.create(principal, idempotencyKey, body.toInput(), request.context())
        .toResponseEntity()

    @GetMapping("/{ticketNumber}")
    fun read(
        @AuthenticationPrincipal principal: AuthenticatedIntegrationClient,
        @PathVariable ticketNumber: Long,
        request: HttpServletRequest,
    ): ResponseEntity<String> = application.read(principal, ticketNumber, request.context()).toResponseEntity()

    @PatchMapping("/{ticketNumber}")
    fun update(
        @AuthenticationPrincipal principal: AuthenticatedIntegrationClient,
        @PathVariable ticketNumber: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestBody body: JsonNode,
        request: HttpServletRequest,
    ): ResponseEntity<String> = application.update(
        principal,
        ticketNumber,
        parseEtag(ifMatch),
        idempotencyKey,
        body.toUpdateInput(),
        request.context(),
    ).toResponseEntity()

    @PostMapping("/{ticketNumber}/internal-comments")
    fun addInternalComment(
        @AuthenticationPrincipal principal: AuthenticatedIntegrationClient,
        @PathVariable ticketNumber: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody body: AddInternalCommentRequest,
        request: HttpServletRequest,
    ): ResponseEntity<String> = application.addInternalComment(
        principal,
        ticketNumber,
        idempotencyKey,
        body.body,
        request.context(),
    ).toResponseEntity()

    private fun PlatformStoredResponse.toResponseEntity(): ResponseEntity<String> {
        val contentType = headers["Content-Type"]?.let(MediaType::parseMediaType) ?: MediaType.APPLICATION_JSON
        val response = ResponseEntity.status(status).contentType(contentType)
        headers.filterKeys { it != "Content-Type" }.forEach(response::header)
        return response.body(bodyJson)
    }

    private fun HttpServletRequest.context() = PlatformRequestContext(
        requestId = identifier(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
        correlationId = identifier(RequestIdFilter.CORRELATION_ID_ATTRIBUTE),
        remoteIp = getAttribute(PlatformSecurityFilter.EFFECTIVE_REMOTE_IP_ATTRIBUTE)?.toString()
            ?: error("Platform security filter must resolve the effective remote IP"),
        userAgent = getHeader("User-Agent"),
    )

    private fun HttpServletRequest.identifier(attribute: String): String =
        getAttribute(attribute)?.toString() ?: error("RequestIdFilter must run before Platform controller")

    private fun parseEtag(value: String): Long = ETAG.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
        ?: throw PlatformIfMatchInvalidException()

    private fun JsonNode.toUpdateInput(): PlatformUpdateInput {
        if (!isObject || isEmpty) throw PlatformTicketInvalidException("UPDATE_FIELDS_REQUIRED")
        val names = properties().asSequence().map(Map.Entry<String, JsonNode>::key).toSet()
        if (!ALLOWED_UPDATE_FIELDS.containsAll(names)) throw PlatformTicketInvalidException("UPDATE_FIELD_NOT_ALLOWED")
        val changed = linkedSetOf<TicketField>()
        fun present(name: String, field: TicketField): Boolean = has(name).also { if (it) changed += field }

        val status = if (present("status", TicketField.STATUS)) {
            textValue("status")?.let { runCatching { TicketStatus.valueOf(it) }.getOrNull() }
                ?: throw PlatformTicketInvalidException("STATUS_INVALID")
        } else null
        val priority = if (present("priority", TicketField.PRIORITY)) {
            textValue("priority")?.let { runCatching { TicketPriority.valueOf(it) }.getOrNull() }
                ?: throw PlatformTicketInvalidException("PRIORITY_INVALID")
        } else null
        val groupId = if (present("groupId", TicketField.GROUP_ID)) nullableUuid("groupId") else null
        val assigneeId = if (present("assigneeId", TicketField.ASSIGNEE_ID)) nullableUuid("assigneeId") else null
        return PlatformUpdateInput(changed, status, priority, groupId, assigneeId)
    }

    private fun JsonNode.textValue(name: String): String? = get(name)?.takeUnless(JsonNode::isNull)?.asText()

    private fun JsonNode.nullableUuid(name: String): UUID? {
        val node = get(name) ?: return null
        if (node.isNull) return null
        return runCatching { UUID.fromString(node.asText()) }.getOrNull()
            ?: throw PlatformTicketInvalidException("${name.uppercase()}_INVALID")
    }

    private companion object {
        val ETAG = Regex("^\"ticket-v(0|[1-9][0-9]*)\"$")
        val ALLOWED_UPDATE_FIELDS = setOf("status", "priority", "groupId", "assigneeId")
    }
}

internal data class CreatePlatformTicketRequest(
    val kind: PlatformTicketKind,
    @field:NotBlank @field:Size(max = 200) val subject: String,
    @field:NotBlank @field:Size(max = 50_000) val message: String,
    @field:Valid val requester: PlatformRequesterRequest? = null,
    val priority: TicketPriority = TicketPriority.NORMAL,
    val groupId: UUID? = null,
    val assigneeId: UUID? = null,
) {
    fun toInput(): PlatformCreateInput {
        if (kind == PlatformTicketKind.CUSTOMER_REQUEST && requester == null) {
            throw PlatformTicketInvalidException("REQUESTER_REQUIRED")
        }
        return PlatformCreateInput(
            kind,
            subject,
            message,
            requester?.name,
            requester?.email,
            priority,
            groupId,
            assigneeId,
        )
    }
}

internal data class PlatformRequesterRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String,
)

internal data class AddInternalCommentRequest(
    @field:NotBlank @field:Size(max = 50_000) val body: String,
)

internal class PlatformIfMatchInvalidException : RuntimeException()
