package dev.deskseed.outboundmail.internal

import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.MagicLinkMail
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailTemplate
import dev.deskseed.outboundmail.PublicAgentReplyMail
import dev.deskseed.outboundmail.PasswordResetMail
import dev.deskseed.outboundmail.RegistrationVerificationMail
import dev.deskseed.outboundmail.RequestReceivedMail
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class OutboundMailPolicyTest {
    private val properties = OutboundMailProperties(
        fromAddress = "no-reply@deskseed.local",
        publicBaseUrl = "http://localhost:5173",
        retryBackoff = listOf(Duration.ZERO, Duration.ofMinutes(1), Duration.ofMinutes(5)),
    )
    private val renderer = MailTemplateRenderer(properties, OutboundMailSafety())

    @Test
    fun `recipient and all header fields reject control characters or malformed addresses`() {
        val safety = OutboundMailSafety()

        listOf(
            "victim@example.com\r\nBcc: attacker@example.com",
            "not-an-address",
            "two@example.com,other@example.com",
            " victim@example.com ",
            "a".repeat(245) + "@example.com",
        ).forEach { address ->
            assertThatThrownBy { safety.requireMailbox(address) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { safety.requireHeaderValue("safe\nInjected: value", "subject", 200) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `versioned templates render fragment request links and classify token-bearing bodies as protected`() {
        val magicLink = "https://deskseed.example/customer/magic/opaque-token"
        val verificationLink = "https://deskseed.example/customer/register/verify#token=opaque-token"
        val resetLink = "https://deskseed.example/customer/password/reset#token=opaque-reset-token"
        val requestAccessToken = "a".repeat(43)
        val magic = renderer.render(intent(MagicLinkMail(magicLink)))
        val verification = renderer.render(intent(RegistrationVerificationMail(verificationLink)))
        val reset = renderer.render(intent(PasswordResetMail(resetLink)))
        val received = renderer.render(
            intent(RequestReceivedMail(ticketNumber = 1042, requestAccessToken = requestAccessToken)),
        )
        val reply = renderer.render(
            intent(
                PublicAgentReplyMail(
                    ticketNumber = 1042,
                    publicBody = "고객에게 공개되는 답변",
                    requestAccessToken = requestAccessToken,
                ),
            ),
        )

        assertThat(magic.template).isEqualTo(OutboundMailTemplate.CUSTOMER_MAGIC_LINK)
        assertThat(magic.templateVersion).isEqualTo(1)
        assertThat(magic.subject).contains("로그인")
        assertThat(magic.textBody).contains(magicLink)
        assertThat(magic.sensitivity).isEqualTo(dev.deskseed.outboundmail.RenderedMailSensitivity.PROTECTED)
        assertThat(verification.template).isEqualTo(OutboundMailTemplate.CUSTOMER_REGISTRATION_VERIFICATION)
        assertThat(verification.templateVersion).isEqualTo(1)
        assertThat(verification.subject).contains("등록", "이메일 확인")
        assertThat(verification.textBody).contains(verificationLink).doesNotContain("로그인 링크")
        assertThat(verification.sensitivity).isEqualTo(dev.deskseed.outboundmail.RenderedMailSensitivity.PROTECTED)
        assertThat(RegistrationVerificationMail(verificationLink).toString()).doesNotContain(verificationLink)
        assertThat(MagicLinkMail(magicLink).toString()).doesNotContain(magicLink)
        assertThat(reset.template).isEqualTo(OutboundMailTemplate.CUSTOMER_PASSWORD_RESET)
        assertThat(reset.templateVersion).isEqualTo(1)
        assertThat(reset.subject).contains("비밀번호", "재설정")
        assertThat(reset.textBody).contains(resetLink).doesNotContain("로그인 링크", "고객 등록")
        assertThat(reset.sensitivity).isEqualTo(dev.deskseed.outboundmail.RenderedMailSensitivity.PROTECTED)
        assertThat(PasswordResetMail(resetLink).toString()).doesNotContain(resetLink)
        assertThat(received.subject).contains("#1042", "접수")
        assertThat(received.textBody).contains("http://localhost:5173/requests/1042#token=$requestAccessToken")
        assertThat(received.textBody).doesNotContain("?token=")
        assertThat(received.sensitivity).isEqualTo(dev.deskseed.outboundmail.RenderedMailSensitivity.PROTECTED)
        assertThat(reply.subject).contains("#1042", "공개 답변")
        assertThat(reply.textBody).contains("고객에게 공개되는 답변")
        assertThat(reply.textBody).contains("http://localhost:5173/requests/1042#token=$requestAccessToken")
        assertThat(reply.sensitivity).isEqualTo(dev.deskseed.outboundmail.RenderedMailSensitivity.PROTECTED)
        assertThat(reply.textBody).doesNotContain("INTERNAL")
    }

    @Test
    fun `retry backoff is bounded and terminal after configured attempt budget`() {
        val policy = MailRetryPolicy(properties)

        assertThat(policy.nextDelay(completedAttemptCount = 1)).isEqualTo(Duration.ofMinutes(1))
        assertThat(policy.nextDelay(completedAttemptCount = 2)).isEqualTo(Duration.ofMinutes(5))
        assertThat(policy.nextDelay(completedAttemptCount = 3)).isNull()
    }

    private fun intent(content: dev.deskseed.outboundmail.OutboundMailContent) = OutboundMailIntent(
        idempotencyKey = "test:${UUID.randomUUID()}",
        recipient = MailRecipient("customer@example.com"),
        content = content,
        ticketId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        actor = ActorRef(ActorType.SYSTEM, null),
        context = CommandContext(
            source = RequestSource.SYSTEM_JOB,
            requestId = "request-test",
            correlationId = "correlation-test",
            commandId = "command-test",
        ),
    )
}
