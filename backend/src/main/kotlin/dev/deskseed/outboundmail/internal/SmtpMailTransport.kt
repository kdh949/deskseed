package dev.deskseed.outboundmail.internal

import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.DependsOn
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

@Component
@DependsOn("mailDeliveryConfigurationValidator")
@ConditionalOnExpression("\${deskseed.mail.delivery-enabled:false}")
@ConditionalOnProperty(prefix = "deskseed.mail", name = ["transport"], havingValue = "smtp")
// Spring Boot 4.1 creates JavaMailSender from spring.mail + starter-mail.
// Source: https://docs.spring.io/spring-boot/4.1/reference/io/email.html
internal class SmtpMailTransport(
    private val mailSender: JavaMailSender,
    private val safety: OutboundMailSafety,
) : MailTransport {
    override fun send(message: MailTransportMessage): MailTransportReceipt {
        safety.requireMailbox(message.fromAddress)
        safety.requireMailbox(message.recipientAddress)
        safety.requireHeaderValue(message.subject, "subject", 200)
        safety.requireHeaderValue(message.stableMessageId, "message ID", 200)
        safety.requireIdempotencyKey(message.idempotencyKey)
        try {
            val mimeMessage = mailSender.createMimeMessage()
            mimeMessage.setFrom(InternetAddress(message.fromAddress, true))
            mimeMessage.setRecipient(Message.RecipientType.TO, InternetAddress(message.recipientAddress, true))
            mimeMessage.subject = message.subject
            mimeMessage.setText(message.textBody, StandardCharsets.UTF_8.name())
            mimeMessage.saveChanges()
            mimeMessage.setHeader("Message-ID", message.stableMessageId)
            mimeMessage.setHeader("X-Deskseed-Idempotency-Key", message.idempotencyKey)
            mailSender.send(mimeMessage)
            return MailTransportReceipt("SMTP", message.stableMessageId)
        } catch (failure: MailException) {
            throw MailTransportException(retryable = true, failureCode = "SMTP_DELIVERY_FAILED", cause = failure)
        } catch (failure: jakarta.mail.MessagingException) {
            throw MailTransportException(retryable = true, failureCode = "SMTP_MESSAGE_FAILED", cause = failure)
        }
    }
}
