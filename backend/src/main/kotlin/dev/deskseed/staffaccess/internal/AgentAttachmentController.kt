package dev.deskseed.staffaccess.internal

import dev.deskseed.attachments.AttachmentContent
import dev.deskseed.attachments.AttachmentDownloadCommand
import dev.deskseed.attachments.AttachmentDownloadService
import dev.deskseed.attachments.AttachmentNotFoundException
import dev.deskseed.attachments.AttachmentUploadCommand
import dev.deskseed.attachments.AttachmentUploadResult
import dev.deskseed.attachments.AttachmentUploadService
import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.NotBlank
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Handles only private object handles and bytes. Ticket authorization remains owned by the ticket read service,
 * and the attachment service records its required download audit before the stream is returned.
 */
@RestController
@RequestMapping("/api/v1/agent/attachments")
@Validated
internal class AgentAttachmentController(
    private val uploadService: AttachmentUploadService,
    private val downloadService: AttachmentDownloadService,
    private val ticketReadApplicationService: AgentTicketReadApplicationService,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
    private val clock: Clock,
) {
    @PostMapping("/uploads", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestPart("file") file: MultipartFile,
        request: HttpServletRequest,
    ): ResponseEntity<AttachmentUploadResponse> {
        val result = file.inputStream.use { content ->
            uploadService.upload(
                AttachmentUploadCommand(
                    actor = ActorRef(ActorType.STAFF, principal.id),
                    actorDisplayName = principal.displayName,
                    source = RequestSource.AGENT_UI,
                    context = CommandContexts.from(request, RequestSource.AGENT_UI),
                    boundTicketId = null,
                    allowedVisibility = null,
                    fileName = file.originalFilename.orEmpty(),
                    declaredContentType = file.contentType,
                    content = content,
                ),
            )
        }
        return ResponseEntity.status(201)
            .cacheControl(CacheControl.noStore())
            .body(AttachmentUploadResponse.from(result))
    }

    @GetMapping("/{attachmentId}/download")
    fun download(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable attachmentId: UUID,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<StreamingResponseBody> {
        val locator = downloadService.locateLinkedAttachment(attachmentId) ?: throw AttachmentNotFoundException()
        // This authorizes the ticket at the service boundary and records a non-semantic protected read.
        ticketReadApplicationService.readTicket(
            principal = principal,
            ticketNumber = locator.ticketNumber,
            interactionId = interactionId,
            intent = AgentReadIntent.BACKGROUND,
            originSearchEventId = null,
            context = request.readContext(),
        )
        val content = downloadService.openForDownload(
            AttachmentDownloadCommand(
                attachmentId = attachmentId,
                ticketId = locator.ticketId,
                ticketNumber = locator.ticketNumber,
                allowedVisibilities = setOf(AttachmentVisibility.PUBLIC, AttachmentVisibility.INTERNAL),
                accessContext = request.readContext().toAccessAuditContext(
                    principal,
                    sessionFingerprint.fingerprint(request.authenticatedSessionId()),
                ),
                interactionId = interactionId,
                occurredAt = Instant.now(clock),
            ),
        )
        return stream(content)
    }

    private fun stream(content: AttachmentContent): ResponseEntity<StreamingResponseBody> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(safeMediaType(content.attachment.contentType))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(content.attachment.fileName, StandardCharsets.UTF_8).build().toString(),
        )
        .body(StreamingResponseBody { output -> content.stream.use { it.copyTo(output) } })

    private fun safeMediaType(contentType: String): MediaType = runCatching { MediaType.parseMediaType(contentType) }
        .getOrDefault(MediaType.APPLICATION_OCTET_STREAM)

    private fun HttpServletRequest.readContext() = AgentReadRequestContext(
        requestId = getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
        correlationId = getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
        sessionId = authenticatedSessionId(),
        ipAddress = remoteAddr,
        userAgent = getHeader("User-Agent"),
    )

    private fun HttpServletRequest.authenticatedSessionId(): String = getSession(false)?.id
        ?: throw AccessAuditUnavailableException(IllegalStateException("Authenticated staff session is unavailable"))
}

internal data class AttachmentUploadResponse(
    val id: UUID,
    val fileName: String,
    val sizeBytes: Long,
    val contentType: String,
    val scanStatus: String,
    val expiresAt: Instant,
) {
    companion object {
        fun from(result: AttachmentUploadResult) = AttachmentUploadResponse(
            id = result.attachment.id,
            fileName = result.attachment.fileName,
            sizeBytes = result.attachment.sizeBytes,
            contentType = result.attachment.contentType,
            scanStatus = result.scanStatus.name,
            expiresAt = result.expiresAt,
        )
    }
}
