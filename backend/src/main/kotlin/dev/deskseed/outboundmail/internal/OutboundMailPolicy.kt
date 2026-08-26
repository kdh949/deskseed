package dev.deskseed.outboundmail.internal

import dev.deskseed.outboundmail.MagicLinkMail
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailTemplate
import dev.deskseed.outboundmail.PasswordResetMail
import dev.deskseed.outboundmail.PublicAgentReplyMail
import dev.deskseed.outboundmail.RegistrationVerificationMail
import dev.deskseed.outboundmail.RenderedMailSensitivity
import dev.deskseed.outboundmail.RequestReceivedMail
import jakarta.mail.internet.InternetAddress
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration
import java.util.Base64

@ConfigurationProperties("deskseed.mail")
internal data class OutboundMailProperties(
    var deliveryEnabled: Boolean = false,
    var schedulingEnabled: Boolean = true,
    var transport: String = "disabled",
    var fromAddress: String = "no-reply@deskseed.local",
    var publicBaseUrl: String = "http://localhost:5173",
    var retryBackoff: List<Duration> = listOf(
        Duration.ZERO,
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        Duration.ofMinutes(30),
        Duration.ofHours(2),
    ),
    var workerFixedDelay: Duration = Duration.ofSeconds(5),
    var workerInitialDelay: Duration = Duration.ofSeconds(5),
    var leaseDuration: Duration = Duration.ofMinutes(2),
    var batchSize: Int = 20,
    var protectedContent: ProtectedMailContentProperties = ProtectedMailContentProperties(),
)

internal data class ProtectedMailContentProperties(
    var activeKeyVersion: String = "local-v1",
    var keys: Map<String, String> = emptyMap(),
) {
    fun decodedKey(version: String): ByteArray {
        require(version.matches(KEY_VERSION_PATTERN)) { "protected mail key version is invalid" }
        val encoded = keys[version] ?: error("protected mail key version is not configured")
        val decoded = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("protected mail key is invalid") }
        require(decoded.size == PROTECTED_KEY_BYTES) { "protected mail key must contain $PROTECTED_KEY_BYTES bytes" }
        return decoded
    }

    fun requireActiveKey(): ByteArray = decodedKey(activeKeyVersion)

    private companion object {
        val KEY_VERSION_PATTERN = Regex("[A-Za-z0-9._-]{1,40}")
        const val PROTECTED_KEY_BYTES = 32
    }
}

internal class OutboundMailSafety {
    fun requireMailbox(value: String): String {
        require(value.length in 3..254) { "mailbox length is invalid" }
        require(value == value.trim()) { "mailbox must not contain surrounding whitespace" }
        require(value.none(Char::isISOControl)) { "mailbox must not contain control characters" }
        require(!value.contains(',')) { "exactly one mailbox is required" }
        require(value.count { it == '@' } == 1 && value.none(Char::isWhitespace)) {
            "mailbox must contain one local and domain part"
        }
        val (localPart, domainPart) = value.split('@', limit = 2)
        require(localPart.isNotEmpty() && domainPart.contains('.') && !domainPart.startsWith('.') && !domainPart.endsWith('.')) {
            "mailbox must contain one local and domain part"
        }
        val parsed = InternetAddress(value, true)
        parsed.validate()
        require(parsed.address == value && parsed.personal == null) { "exactly one bare mailbox is required" }
        return value
    }

    fun requireHeaderValue(value: String, field: String, maxLength: Int): String {
        require(value.isNotBlank()) { "$field must not be blank" }
        require(value.length <= maxLength) { "$field is too long" }
        require(value.none(Char::isISOControl)) { "$field must not contain control characters" }
        return value
    }

    fun requireIdempotencyKey(value: String): String {
        require(value.length in 1..200) { "idempotency key length is invalid" }
        require(value.all { it.isLetterOrDigit() || it in "._:-" }) { "idempotency key is invalid" }
        return value
    }

    fun requireAbsoluteHttpUrl(value: String, field: String): String {
        require(value.length <= 2_000 && value.none(Char::isISOControl)) { "$field is invalid" }
        val uri = URI(value)
        require(uri.isAbsolute && uri.host != null && uri.scheme in setOf("http", "https")) { "$field is invalid" }
        return value
    }

    fun requireRequestAccessToken(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{32,256}"))) { "request access token is invalid" }
        return value
    }
}

internal data class RenderedMail(
    val template: OutboundMailTemplate,
    val templateVersion: Int,
    val fromAddress: String,
    val recipient: String,
    val subject: String,
    val textBody: String,
    val sensitivity: RenderedMailSensitivity,
)

internal class MailTemplateRenderer(
    private val properties: OutboundMailProperties,
    private val safety: OutboundMailSafety,
) {
    fun render(intent: OutboundMailIntent): RenderedMail {
        safety.requireIdempotencyKey(intent.idempotencyKey)
        val recipient = safety.requireMailbox(intent.recipient.address)
        val fromAddress = safety.requireMailbox(properties.fromAddress)
        val baseUrl = safety.requireAbsoluteHttpUrl(properties.publicBaseUrl.removeSuffix("/"), "public base URL")
        val rendered = when (val content = intent.content) {
            is MagicLinkMail -> {
                val link = safety.requireAbsoluteHttpUrl(content.magicLink, "magic link")
                "[Deskseed] 로그인 링크" to """
                    Deskseed 로그인 링크입니다.

                    $link

                    요청하지 않았다면 이 메일을 무시하세요.
                """.trimIndent()
            }
            is RegistrationVerificationMail -> {
                val link = safety.requireAbsoluteHttpUrl(content.verificationLink, "registration verification link")
                "[Deskseed] 고객 등록 이메일 확인" to """
                    Deskseed 고객 등록을 완료하려면 이메일 주소를 확인해 주세요.

                    $link

                    이 브라우저에서 등록을 시작하지 않았다면 이 메일을 무시하세요.
                """.trimIndent()
            }
            is PasswordResetMail -> {
                val link = safety.requireAbsoluteHttpUrl(content.resetLink, "password reset link")
                "[Deskseed] 고객 비밀번호 재설정" to """
                    Deskseed 고객 비밀번호를 재설정하려면 아래 링크를 사용해 주세요.

                    $link

                    요청하지 않았다면 이 메일을 무시하세요.
                """.trimIndent()
            }
            is RequestReceivedMail -> {
                require(content.ticketNumber > 0) { "ticket number must be positive" }
                val requestUrl = requestUrl(baseUrl, content.ticketNumber, content.requestAccessToken)
                "[Deskseed] 요청 #${content.ticketNumber} 접수 완료" to """
                    요청 #${content.ticketNumber}이 접수되었습니다.

                    요청 보기: $requestUrl
                """.trimIndent()
            }
            is PublicAgentReplyMail -> {
                require(content.ticketNumber > 0) { "ticket number must be positive" }
                val publicBody = content.publicBody.trim()
                require(publicBody.isNotEmpty() && publicBody.length <= 20_000) { "public reply body is invalid" }
                val requestUrl = requestUrl(baseUrl, content.ticketNumber, content.requestAccessToken)
                "[Deskseed] 요청 #${content.ticketNumber} 새 공개 답변" to """
                    요청 #${content.ticketNumber}에 새 공개 답변이 등록되었습니다.

                    $publicBody

                    요청 보기: $requestUrl
                """.trimIndent()
            }
        }
        return RenderedMail(
            template = intent.content.template,
            templateVersion = intent.content.template.version,
            fromAddress = fromAddress,
            recipient = recipient,
            subject = safety.requireHeaderValue(rendered.first, "subject", 200),
            textBody = rendered.second,
            sensitivity = intent.content.renderedSensitivity,
        )
    }

    private fun requestUrl(baseUrl: String, ticketNumber: Long, rawAccessToken: String): String =
        "$baseUrl/requests/$ticketNumber#token=${safety.requireRequestAccessToken(rawAccessToken)}"
}

internal class MailRetryPolicy(private val properties: OutboundMailProperties) {
    init {
        require(properties.retryBackoff.isNotEmpty()) { "at least one delivery attempt is required" }
        require(properties.retryBackoff.first() == Duration.ZERO) { "the first delivery attempt must be immediate" }
        require(properties.retryBackoff.all { !it.isNegative }) { "retry backoff must not be negative" }
    }

    val maxAttempts: Int get() = properties.retryBackoff.size

    fun nextDelay(completedAttemptCount: Int): Duration? =
        properties.retryBackoff.getOrNull(completedAttemptCount)
}
