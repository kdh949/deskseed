package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.attachments.AttachmentInfectedException
import dev.deskseed.attachments.AttachmentMimeMismatchException
import dev.deskseed.attachments.AttachmentNotFoundException
import dev.deskseed.attachments.AttachmentTooLargeException
import dev.deskseed.attachments.AttachmentUnavailableException
import dev.deskseed.ticketing.SavedViewAccessDeniedException
import dev.deskseed.ticketing.SavedViewConflictException
import dev.deskseed.ticketing.SavedViewNotFoundException
import dev.deskseed.ticketing.SavedViewPreconditionFailedException
import dev.deskseed.knowledge.KnowledgeAccessAuditUnavailableException
import dev.deskseed.knowledge.KnowledgeNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.beans.TypeMismatchException
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(
    assignableTypes = [
        AgentTicketReadController::class,
        AgentAttachmentController::class,
        AgentTicketConfigurationController::class,
        AgentKnowledgeController::class,
        AgentMacroPreviewController::class,
    ],
)
internal class AgentTicketReadExceptionHandler {
    @ExceptionHandler(AttachmentNotFoundException::class)
    fun attachmentNotFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/attachment-not-found",
        "Attachment not found",
        "The requested attachment is not available.",
    )

    @ExceptionHandler(AttachmentTooLargeException::class)
    fun attachmentTooLarge(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.PAYLOAD_TOO_LARGE,
        "/problems/attachment-too-large",
        "Attachment too large",
        "The upload exceeds the configured private attachment limit.",
    )

    @ExceptionHandler(AttachmentMimeMismatchException::class)
    fun attachmentMimeMismatch(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "/problems/attachment-mime-mismatch",
        "Attachment type rejected",
        "The declared and detected attachment types are incompatible.",
    )

    @ExceptionHandler(AttachmentInfectedException::class)
    fun attachmentInfected(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "/problems/attachment-infected",
        "Attachment rejected",
        "The attachment did not pass the malware scan.",
    )

    @ExceptionHandler(AttachmentUnavailableException::class)
    fun attachmentUnavailable(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/attachment-unavailable",
        "Attachment service unavailable",
        "The attachment operation could not be completed safely.",
    )
    @ExceptionHandler(AgentTicketNotFoundException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/agent-ticket-not-found",
        "Ticket not found",
        "The requested ticket or view was not found.",
    )

    @ExceptionHandler(KnowledgeNotFoundException::class)
    fun knowledgeNotFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/knowledge-not-found",
        "Knowledge article not found",
        "The requested knowledge article is not available to this staff actor.",
    )

    @ExceptionHandler(SavedViewNotFoundException::class)
    fun savedViewNotFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/saved-view-not-found",
        "Saved view not found",
        "The requested saved view was not found.",
    )

    @ExceptionHandler(SavedViewAccessDeniedException::class)
    fun savedViewForbidden(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.FORBIDDEN,
        "/problems/saved-view-forbidden",
        "Saved view change forbidden",
        "The current staff actor cannot change this saved view.",
    )

    @ExceptionHandler(SavedViewConflictException::class)
    fun savedViewConflict(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.CONFLICT,
        "/problems/saved-view-conflict",
        "Saved view changed concurrently",
        "Refresh the saved view definition before trying again.",
    )

    @ExceptionHandler(SavedViewPreconditionFailedException::class)
    fun savedViewPrecondition(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.PRECONDITION_FAILED,
        "/problems/saved-view-version-precondition-failed",
        "Saved view version precondition failed",
        "The saved view changed after the supplied ETag was read.",
    )

    @ExceptionHandler(AccessAuditUnavailableException::class, KnowledgeAccessAuditUnavailableException::class)
    fun auditUnavailable(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/audit-write-unavailable",
        "Protected data unavailable",
        "The protected read or search could not be safely audited. Try again later.",
    )

    @ExceptionHandler(DataAccessException::class)
    fun readUnavailable(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/agent-read-unavailable",
        "Protected read unavailable",
        "The requested protected read could not be completed safely. Try again later.",
    )

    @ExceptionHandler(InvalidSearchOriginException::class)
    fun invalidSearchOrigin(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/invalid-search-origin",
        "Invalid search origin",
        "The search-result navigation context is invalid or no longer belongs to this session.",
    )

    @ExceptionHandler(
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        TypeMismatchException::class,
        MissingRequestHeaderException::class,
    )
    fun invalid(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/validation",
        "Agent read request validation failed",
        "One or more request fields are invalid.",
    )

    private fun response(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            this.instance = URI.create(request.requestURI)
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(problem)
    }
}
