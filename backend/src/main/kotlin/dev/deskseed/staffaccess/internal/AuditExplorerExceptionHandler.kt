package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AccessAuditProtectionException
import dev.deskseed.audit.AuditActivityNotFoundException
import dev.deskseed.audit.AuditExportNotFoundException
import dev.deskseed.audit.AuditExportExpiredException
import dev.deskseed.audit.AuditExportArtifactStoreUnavailableException
import dev.deskseed.audit.AuditProtectedContentInvalidException
import dev.deskseed.audit.AuditRevealDeniedException
import dev.deskseed.audit.AuditRevealForbiddenException
import dev.deskseed.audit.AuditRevealReasonInvalidException
import dev.deskseed.audit.AuditRevealTargetInvalidException
import dev.deskseed.audit.AuditProjectionRebuildConflictException
import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [AuditExplorerController::class])
internal class AuditExplorerExceptionHandler {
    @ExceptionHandler(AuditRevealForbiddenException::class)
    fun revealForbidden(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.FORBIDDEN,
        "/problems/audit-reveal-forbidden",
        "Audit reveal forbidden",
        "The current actor cannot reveal protected audit content.",
    )

    @ExceptionHandler(AuditRevealDeniedException::class)
    fun revealDenied(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.FORBIDDEN,
        "/problems/audit-reveal-reauthentication-required",
        "Recent authentication required",
        "Reauthenticate before revealing protected audit content.",
    )

    @ExceptionHandler(AuditProtectedContentInvalidException::class)
    fun protectedContentInvalid(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "/problems/audit-protected-content-invalid",
        "Protected audit content is invalid",
        "The protected content could not be authenticated.",
    )

    @ExceptionHandler(AuditRevealTargetInvalidException::class)
    fun revealTargetInvalid(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "/problems/audit-reveal-target-invalid",
        "Audit reveal target invalid",
        "Only one SEARCH_EXECUTED event can be revealed.",
    )

    @ExceptionHandler(AuditRevealReasonInvalidException::class)
    fun revealReasonInvalid(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/audit-reveal-reason-required",
        "Reveal reason required",
        "Provide a bounded reason for revealing protected audit content.",
    )

    @ExceptionHandler(AuditActivityNotFoundException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/audit-activity-not-found",
        "Audit activity not found",
        "The requested audit activity does not exist.",
    )

    @ExceptionHandler(AuditExportNotFoundException::class)
    fun exportNotFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/audit-export-not-found",
        "Audit export not found",
        "The requested audit export does not exist.",
    )

    @ExceptionHandler(AuditExportExpiredException::class)
    fun exportExpired(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.GONE,
        "/problems/audit-export-expired",
        "Audit export expired",
        "The private export artifact is no longer available.",
    )

    @ExceptionHandler(AuditProjectionRebuildConflictException::class)
    fun rebuildConflict(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.CONFLICT,
        "/problems/audit-projection-rebuild-conflict",
        "Audit projection rebuild conflict",
        "Another projection rebuild is already running.",
    )

    @ExceptionHandler(
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
    )
    fun invalid(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/invalid-audit-explorer-request",
        "Invalid audit explorer request",
        "The audit explorer request is invalid.",
    )

    @ExceptionHandler(
        DataAccessException::class,
        TransactionException::class,
        AccessAuditProtectionException::class,
        AuditExportArtifactStoreUnavailableException::class,
    )
    fun persistenceUnavailable(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/audit-persistence-unavailable",
        "Audit persistence unavailable",
        "The protected audit operation could not be completed.",
    )

    private fun response(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = java.net.URI.create(type)
            this.title = title
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .cacheControl(CacheControl.noStore())
            .body(problem)
    }
}
