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
import dev.deskseed.outboundmail.RequestReceivedMail
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

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
    fun `three versioned plain text templates render only their public inputs`() {
        val magicLink = "https://deskseed.example/customer/magic/opaque-token"
        val magic = renderer.render(intent(MagicLinkMail(magicLink)))
        val received = renderer.render(intent(RequestReceivedMail(ticketNumber = 1042)))
        val reply = renderer.render(
            intent(
                PublicAgentReplyMail(
                    ticketNumber = 1042,
                    publicBody = "고객에게 공개되는 답변",
                ),
            ),
        )

        assertThat(magic.template).isEqualTo(OutboundMailTemplate.CUSTOMER_MAGIC_LINK)
        assertThat(magic.templateVersion).isEqualTo(1)
        assertThat(magic.subject).contains("로그인")
        assertThat(magic.textBody).contains(magicLink)
        assertThat(received.subject).contains("#1042", "접수")
        assertThat(received.textBody).contains("http://localhost:5173/requests/1042")
        assertThat(reply.subject).contains("#1042", "공개 답변")
        assertThat(reply.textBody).contains("고객에게 공개되는 답변")
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
