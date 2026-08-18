package dev.deskseed.staffaccess.internal

import dev.deskseed.collaboration.ClosedTicketDraftException
import dev.deskseed.collaboration.TicketDraftConflictException
import dev.deskseed.attachments.AttachmentLinkInvalidException
import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(assignableTypes = [AgentTicketDraftController::class])
internal class AgentTicketDraftExceptionHandler {
    @ExceptionHandler(AgentTicketNotFoundException::class, AgentTicketDraftNotFoundException::class)
    fun notFound(request: HttpServletRequest) = problem(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/ticket-draft-not-found",
        "Ticket draft not found",
        "The requested ticket is unreadable or no owner draft exists for this channel.",
    )

    @ExceptionHandler(ClosedTicketDraftException::class)
    fun closedTicket(request: HttpServletRequest) = problem(
        request,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "/problems/ticket-draft-closed",
        "Closed ticket drafts cannot be saved",
        "The existing draft may be read and explicitly cleared, but a CLOSED ticket cannot accept another draft write.",
    )

    @ExceptionHandler(TicketDraftConflictException::class)
    fun conflict(
        exception: TicketDraftConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val response = problem(
            request,
            HttpStatus.CONFLICT,
            "/problems/ticket-draft-conflict",
            "Ticket draft changed concurrently",
            "A newer owner draft exists. Compare it before choosing a recovery action.",
        )
        response.body?.apply {
            exception.current?.let { current ->
                setProperty("currentVersion", current.draftVersion)
                setProperty("updatedAt", current.updatedAt)
            }
        }
        return response
    }

    @ExceptionHandler(
        IllegalArgumentException::class,
        AttachmentLinkInvalidException::class,
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
    )
    fun invalid(request: HttpServletRequest) = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/validation",
        "Ticket draft validation failed",
        "One or more draft fields are invalid.",
    )

    private fun problem(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            this.instance = URI.create(request.requestURI)
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
    }
}
