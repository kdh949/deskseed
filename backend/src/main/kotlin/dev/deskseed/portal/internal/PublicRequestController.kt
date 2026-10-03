package dev.deskseed.portal.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import dev.deskseed.audit.AccessAuditAuthType
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.attachments.AttachmentContent
import dev.deskseed.attachments.AttachmentUploadResult
import dev.deskseed.customerauth.CustomerPrincipal
import dev.deskseed.ticketing.CustomerRequestStatus
import dev.deskseed.ticketing.CanonicalCommentContentCodec
import dev.deskseed.ticketing.CommentContentView
import dev.deskseed.ticketing.commentContentView
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.nio.charset.StandardCharsets
import java.net.URI
import java.time.Instant
import java.util.UUID
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/api/v1/requests")
@Validated
internal class PublicRequestController(
    private val applicationService: PublicRequestApplicationService,
    private val clientAddressResolver: PublicRequestClientAddressResolver,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(
        @Valid @RequestBody body: SubmitRequestBody,
        @AuthenticationPrincipal principal: CustomerPrincipal?,
        request: HttpServletRequest,
    ): ResponseEntity<SubmittedRequestResponse> {
        body.validateIdentity(principal)
        val result = applicationService.submit(
            SubmitAnonymousRequest(
                name = body.requester?.name.orEmpty(),
                email = body.requester?.email.orEmpty(),
                subject = body.subject,
                message = body.message,
                clientCommandId = body.clientCommandId.toString(),
                formValues = body.formValues(),
                acceptedPolicies = body.acceptedPolicies.map { dev.deskseed.customerconsent.CustomerRequestPolicySelection(it.policyKey, it.version) },
                authenticatedCustomerId = principal?.customerId,
                authenticatedEmail = principal?.email,
                effectiveClientAddress = clientAddressResolver.resolve(request),
                context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
            ),
        )

        return ResponseEntity
            .created(URI.create("/api/v1/requests/${result.ticketNumber}"))
            .cacheControl(CacheControl.noStore())
            .body(
                SubmittedRequestResponse(
                    ticketNumber = result.ticketNumber,
                    status = result.status,
                    accessToken = result.accessToken,
                    createdAt = result.createdAt,
                    replayed = result.replayed,
                ),
            )
    }

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submitMultipart(
        @Valid @RequestPart("request") body: SubmitRequestBody,
        @RequestPart(value = "attachments", required = false) attachments: List<MultipartFile>?,
        @AuthenticationPrincipal principal: CustomerPrincipal?,
        request: HttpServletRequest,
    ): ResponseEntity<SubmittedRequestResponse> {
        body.validateIdentity(principal)
        val context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL)
        val input = SubmitAnonymousRequest(
            name = body.requester?.name.orEmpty(), email = body.requester?.email.orEmpty(),
            subject = body.subject, message = body.message,
            authenticatedCustomerId = principal?.customerId, authenticatedEmail = principal?.email,
            effectiveClientAddress = clientAddressResolver.resolve(request), context = context,
            clientCommandId = body.clientCommandId.toString(), formValues = body.formValues(),
            acceptedPolicies = body.acceptedPolicies.map { dev.deskseed.customerconsent.CustomerRequestPolicySelection(it.policyKey, it.version) },
        )
        val files = attachments.orEmpty()
        require(files.size <= 5) { "A request can contain at most five attachments" }
        val prepared = applicationService.prepareInitialSubmission(input)
        val attachmentIds = files.map { file ->
            file.inputStream.use { stream ->
                applicationService.uploadInitialAttachment(prepared, file.originalFilename.orEmpty(), file.contentType,
                    stream, context.copy(commandId = UUID.randomUUID().toString())).attachment.id
            }
        }
        val result = applicationService.finishInitialSubmission(prepared, attachmentIds)
        return ResponseEntity.created(URI.create("/api/v1/requests/${result.ticketNumber}"))
            .cacheControl(CacheControl.noStore()).body(SubmittedRequestResponse(
                result.ticketNumber, result.status, result.accessToken, result.createdAt, result.replayed,
            ))
    }

    @GetMapping("/{ticketNumber}")
    fun view(
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("X-Request-Access-Token") accessToken: String,
    ): ResponseEntity<PublicRequestResponse> {
        val ticket = applicationService.view(ticketNumber, accessToken)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                PublicRequestResponse(
                    ticketNumber = ticket.ticketNumber,
                    subject = ticket.subject,
                    status = ticket.status,
                    createdAt = ticket.createdAt,
                    updatedAt = ticket.updatedAt,
                    comments = ticket.comments.map {
                        PublicCommentResponse(
                            id = it.id,
                            authorDisplayName = it.authorDisplayName,
                            body = it.body,
                            content = commentContentView(it.contentFormat, it.body, it.contentDocument),
                            createdAt = it.createdAt,
                            attachments = it.attachments,
                        )
                    },
                ),
            )
    }

    @PostMapping("/{ticketNumber}/comments")
    fun addComment(
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("X-Request-Access-Token") @Size(min = 32, max = 256) accessToken: String,
        @Valid @RequestBody body: AnonymousCustomerFollowUpRequest,
        request: HttpServletRequest,
    ): ResponseEntity<PublicCommentResponse> {
        val result = applicationService.addComment(
            ticketNumber = ticketNumber,
            rawAccessToken = accessToken,
            content = CanonicalCommentContentCodec(objectMapper).decode(
                body = body.body,
                content = body.content,
                attachmentIds = body.attachmentIds.toSet(),
            ),
            attachmentIds = body.attachmentIds,
            clientCommandId = body.clientCommandId,
            context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        return ResponseEntity.created(
            URI.create("/api/v1/requests/$ticketNumber/comments/${result.comment.id}"),
        ).cacheControl(CacheControl.noStore()).body(
            PublicCommentResponse(
                id = result.comment.id,
                authorDisplayName = result.comment.authorDisplayName,
                body = result.comment.body,
                content = commentContentView(
                    result.comment.contentFormat,
                    result.comment.body,
                    result.comment.contentDocument,
                ),
                createdAt = result.comment.createdAt,
                attachments = result.comment.attachments,
            ),
        )
    }

    @PostMapping("/{ticketNumber}/attachments/uploads", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAttachment(
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("X-Request-Access-Token") @Size(min = 32, max = 256) accessToken: String,
        @RequestPart("file") file: MultipartFile,
        request: HttpServletRequest,
    ): ResponseEntity<PublicAttachmentUploadResponse> {
        val ticket = applicationService.authorizeAttachmentTicket(ticketNumber, accessToken)
        val result = file.inputStream.use { content ->
            applicationService.uploadAttachment(
                ticket = ticket,
                fileName = file.originalFilename.orEmpty(),
                declaredContentType = file.contentType,
                content = content,
                context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
            )
        }
        return ResponseEntity.status(201)
            .cacheControl(CacheControl.noStore())
            .body(PublicAttachmentUploadResponse.from(result))
    }

    @GetMapping("/{ticketNumber}/attachments/{attachmentId}/download")
    fun downloadAttachment(
        @PathVariable @Positive ticketNumber: Long,
        @PathVariable attachmentId: UUID,
        @RequestHeader("X-Request-Access-Token") @Size(min = 32, max = 256) accessToken: String,
        request: HttpServletRequest,
    ): ResponseEntity<StreamingResponseBody> {
        val ticket = applicationService.authorizeAttachmentTicket(ticketNumber, accessToken)
        val content = applicationService.downloadAttachment(
            ticket = ticket,
            attachmentId = attachmentId,
            accessContext = AccessAuditContext(
                actorType = ActorType.CUSTOMER,
                actorId = ticket.requesterId,
                actorDisplaySnapshot = "고객",
                source = RequestSource.CUSTOMER_PORTAL,
                sessionFingerprint = null,
                authType = AccessAuditAuthType.CUSTOMER_CAPABILITY,
                requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
                correlationId = request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
                ipAddress = request.remoteAddr,
                userAgent = request.getHeader("User-Agent"),
            ),
            occurredAt = Instant.now(),
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
}

@Schema(name = "CreateCustomerRequest", description = "현재 고객 폼과 동의 버전으로 접수하는 문의")
internal data class SubmitRequestBody(
    val clientCommandId: UUID,
    @field:Valid val requester: InitialRequestRequester? = null,
    @field:NotBlank @field:Size(max = 200) val subject: String,
    @field:NotBlank @field:Size(max = 20_000) val message: String,
    val formId: UUID? = null,
    @field:Positive val formVersion: Int? = null,
    @field:Valid @field:Size(max = 100) val fieldValues: Map<String, InitialRequestFieldValue>,
    @field:Valid @field:Size(max = 20) val acceptedPolicies: List<InitialRequestPolicyVersion>,
) {
    fun validateIdentity(principal: CustomerPrincipal?) {
        require((principal == null) == (requester != null)) { "Requester must match the authentication mode" }
        require(clientCommandId.version() == 4 && clientCommandId.variant() == 2) { "A random command UUID is required" }
    }
    fun formValues() = dev.deskseed.ticketing.CustomerRequestFormValues(formId, formVersion, fieldValues.mapValues { it.value.command() })
    override fun toString(): String = "[PROTECTED INITIAL CUSTOMER REQUEST]"
}

@Schema(name = "CustomerRequestRequester")
internal data class InitialRequestRequester(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
) { override fun toString(): String = "[PROTECTED CUSTOMER IDENTITY]" }

@Schema(name = "AcceptedCustomerPolicyVersion")
internal data class InitialRequestPolicyVersion(
    @field:NotBlank @field:Size(max = 120) val policyKey: String,
    @field:Positive val version: Int,
)

@Schema(name = "CustomerTicketFieldValue")
internal data class InitialRequestFieldValue(
    val booleanValue: Boolean? = null,
    val numberValue: java.math.BigDecimal? = null,
    val optionId: UUID? = null,
    @field:Size(max = 1000) val shortTextValue: String? = null,
    @field:Size(max = 10000) val longTextValue: String? = null,
) {
    fun command() = try {
        dev.deskseed.ticketing.TicketConfigurationFieldValue(booleanValue, numberValue?.toString(), optionId, shortTextValue, longTextValue)
    } catch (_: IllegalArgumentException) { throw dev.deskseed.ticketing.CustomerFormValidationException() }
    override fun toString(): String = "[PROTECTED CUSTOMER FIELD VALUE]"
}

@Schema(name = "CreateCustomerRequestResult", description = "고객 문의 접수 결과")
internal data class SubmittedRequestResponse(
    @field:Schema(description = "사람이 식별하는 티켓 번호", example = "1042")
    val ticketNumber: Long,
    val status: CustomerRequestStatus,
    @field:Schema(description = "다시 조회할 때 사용하는 일회 발급 토큰", example = "example-token-not-valid-0000000000000000")
    val accessToken: String,
    val createdAt: Instant,
    val replayed: Boolean,
)

@Schema(description = "고객에게 공개 가능한 문의 상세")
internal data class PublicRequestResponse(
    val ticketNumber: Long,
    val subject: String,
    val status: CustomerRequestStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val comments: List<PublicCommentResponse>,
)

@Schema(description = "고객에게 노출할 수 있는 PUBLIC 코멘트")
internal data class PublicCommentResponse(
    val id: UUID,
    val authorDisplayName: String,
    val body: String,
    val content: CommentContentView,
    val createdAt: Instant,
    val attachments: List<dev.deskseed.attachments.TicketAttachment> = emptyList(),
)

internal data class PublicAttachmentUploadResponse(
    val id: UUID,
    val fileName: String,
    val sizeBytes: Long,
    val contentType: String,
    val scanStatus: String,
    val expiresAt: Instant,
) {
    companion object {
        fun from(result: AttachmentUploadResult) = PublicAttachmentUploadResponse(
            result.attachment.id,
            result.attachment.fileName,
            result.attachment.sizeBytes,
            result.attachment.contentType,
            result.scanStatus.name,
            result.expiresAt,
        )
    }
}

@Schema(description = "접근 토큰으로 고객 문의에 추가할 PUBLIC 댓글")
internal data class AnonymousCustomerFollowUpRequest(
    @field:Schema(description = "고객이 추가로 남기는 PUBLIC 댓글 본문", example = "결제 카드 승인 내역을 추가로 확인해 주세요.")
    @field:Size(max = 20_000)
    val body: String? = null,

    val content: JsonNode? = null,

    @field:Size(max = 5)
    val attachmentIds: List<UUID> = emptyList(),

    @field:Schema(description = "네트워크 재전송에도 유지하는 고객 명령 식별자", example = "77777777-7777-4777-8777-777777777777")
    @field:NotBlank
    @field:Size(max = 100)
    @field:Pattern(regexp = "[A-Za-z0-9._:-]+")
    val clientCommandId: String,
)
