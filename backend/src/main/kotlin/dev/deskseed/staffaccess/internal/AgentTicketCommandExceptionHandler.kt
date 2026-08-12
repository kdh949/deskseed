package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.ticketing.AgentTicketNotFoundException
import dev.deskseed.ticketing.TicketAssignmentInvalidException
import dev.deskseed.ticketing.TicketAuditUnavailableException
import dev.deskseed.ticketing.TicketCommandInvalidException
import dev.deskseed.ticketing.TicketCommandIdReusedException
import dev.deskseed.ticketing.TicketFieldConflictException
import dev.deskseed.ticketing.TicketRelationInvalidException
import dev.deskseed.ticketing.TicketTransitionInvalidException
import dev.deskseed.ticketing.TicketUpdateContentionException
import dev.deskseed.ticketing.TicketVersionPreconditionFailedException
import dev.deskseed.ticketing.TicketWriteForbiddenException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(assignableTypes = [AgentTicketCommandController::class])
internal class AgentTicketCommandExceptionHandler {
    @ExceptionHandler(AgentTicketNotFoundException::class)
    fun notFound(request: HttpServletRequest) = problem(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/agent-ticket-not-found",
        "Ticket not found",
        "The requested ticket was not found.",
    )

    @ExceptionHandler(TicketWriteForbiddenException::class)
    fun forbidden(request: HttpServletRequest) = problem(
        request,
        HttpStatus.FORBIDDEN,
        "/problems/ticket-write-forbidden",
        "Ticket update forbidden",
        "The current staff actor cannot update this ticket.",
    )

    @ExceptionHandler(TicketAssignmentInvalidException::class)
    fun invalidAssignment(request: HttpServletRequest) = problem(
        request,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "/problems/ticket-assignment-invalid",
        "Ticket assignment is invalid",
        "The requested group and assignee do not satisfy the assignment policy.",
    )

    @ExceptionHandler(TicketTransitionInvalidException::class)
    fun invalidTransition(request: HttpServletRequest) = problem(
        request,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "/problems/ticket-status-transition-invalid",
        "Ticket status transition is invalid",
        "The requested staff status transition is not allowed.",
    )

    @ExceptionHandler(TicketFieldConflictException::class)
    fun conflict(
        exception: TicketFieldConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val response = problem(
            request,
            HttpStatus.CONFLICT,
            "/problems/ticket-field-conflict",
            "Ticket fields changed concurrently",
            "Some fields were changed by another actor.",
        )
        response.body?.apply {
            setProperty("currentVersion", exception.currentVersion)
            setProperty("conflictingFields", exception.conflictingFields)
        }
        return response
    }

    @ExceptionHandler(TicketCommandIdReusedException::class)
    fun commandIdReused(request: HttpServletRequest) = problem(
        request,
        HttpStatus.CONFLICT,
        "/problems/client-command-id-reused",
        "Client command ID was already used",
        "The client command ID identifies a different ticket command.",
    )

    @ExceptionHandler(TicketVersionPreconditionFailedException::class)
    fun preconditionFailed(
        exception: TicketVersionPreconditionFailedException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val response = problem(
            request,
            HttpStatus.PRECONDITION_FAILED,
            "/problems/ticket-version-precondition-failed",
            "Ticket version precondition failed",
            "The ticket changed after the supplied ETag was read.",
        )
        response.body?.setProperty("currentVersion", exception.currentVersion)
        return response
    }

    @ExceptionHandler(TicketRelationInvalidException::class)
    fun invalidRelation(request: HttpServletRequest) = problem(
        request,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "/problems/ticket-relation-invalid",
        "Ticket relation is invalid",
        "The requested parent-child relation violates the relation policy.",
    )

    @ExceptionHandler(
        TicketCommandInvalidException::class,
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalid(request: HttpServletRequest) = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/validation",
        "Ticket command validation failed",
        "One or more command fields are invalid.",
    )

    @ExceptionHandler(
        TicketAuditUnavailableException::class,
        DataAccessException::class,
    )
    fun auditUnavailable(request: HttpServletRequest) = problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/audit-write-unavailable",
        "Ticket command unavailable",
        "The ticket command could not be committed with its required audit.",
    )

    @ExceptionHandler(TicketUpdateContentionException::class)
    fun contention(request: HttpServletRequest) = problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/ticket-update-contention",
        "Ticket update temporarily unavailable",
        "Concurrent updates prevented this command from committing. Retry from the latest ticket.",
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
