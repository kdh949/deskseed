package dev.deskseed.outboundmail.internal

import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import java.net.URI

/**
 * Keeps outbound delivery opt-in. A production process may start with delivery disabled and no
 * SMTP settings; selecting an enabled SMTP path requires every security-critical setting first.
 */
internal class MailDeliveryConfigurationValidator(
    private val properties: OutboundMailProperties,
    private val environment: Environment,
    private val safety: OutboundMailSafety,
) : InitializingBean {
    override fun afterPropertiesSet() {
        require(properties.transport in SUPPORTED_TRANSPORTS) { "unsupported outbound mail transport" }
        require(properties.batchSize in 1..1_000) { "outbound mail batch size must be between 1 and 1000" }
        requirePositive(properties.workerFixedDelay, "outbound mail worker fixed delay")
        require(!properties.workerInitialDelay.isNegative) { "outbound mail worker initial delay must not be negative" }
        requirePositive(properties.leaseDuration, "outbound mail lease duration")
        // Public request and public-reply flows enqueue PROTECTED intents even while delivery is disabled.
        properties.protectedContent.requireActiveKey()
        if (!properties.deliveryEnabled) {
            require(properties.transport == "disabled") { "disabled outbound delivery requires the disabled transport" }
            return
        }

        require(properties.transport != "disabled") { "enabled outbound delivery requires a transport" }
        safety.requireMailbox(properties.fromAddress)
        safety.requireAbsoluteHttpUrl(properties.publicBaseUrl.removeSuffix("/"), "public base URL")

        if (environment.acceptsProfiles(Profiles.of("production"))) validateProductionSmtp()
    }

    private fun validateProductionSmtp() {
        require(properties.transport == "smtp") { "production outbound delivery requires the smtp transport" }
        requiredProperty("spring.mail.host", "production SMTP host")
        val port = requiredProperty("spring.mail.port", "production SMTP port").toIntOrNull()
        require(port != null && port in 1..65_535) { "production SMTP port is invalid" }
        requiredProperty("spring.mail.username", "production SMTP username")
        requiredProperty("spring.mail.password", "production SMTP password")
        require(booleanProperty("mail.smtp.auth")) { "production SMTP authentication must be enabled" }
        val tlsEnabled = booleanProperty("mail.smtp.ssl.enable") ||
            (booleanProperty("mail.smtp.starttls.enable") && booleanProperty("mail.smtp.starttls.required"))
        require(tlsEnabled) { "production SMTP TLS must be enabled and required" }
        val baseUrl = URI(properties.publicBaseUrl.removeSuffix("/"))
        require(
            baseUrl.scheme == "https" &&
                baseUrl.host != null &&
                baseUrl.userInfo == null &&
                baseUrl.rawQuery == null &&
                baseUrl.rawFragment == null,
        ) { "production public base URL must be an HTTPS origin" }
    }

    private fun requiredProperty(name: String, label: String): String = environment.getProperty(name)
        ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        ?: throw IllegalArgumentException("$label is required")

    private fun booleanProperty(mailProperty: String): Boolean = listOf(
        "spring.mail.properties[$mailProperty]",
        "spring.mail.properties.$mailProperty",
    ).any { name -> environment.getProperty(name)?.trim()?.equals("true", ignoreCase = true) == true }

    private fun requirePositive(value: java.time.Duration, label: String) {
        require(!value.isNegative && !value.isZero) { "$label must be positive" }
    }

    private companion object {
        val SUPPORTED_TRANSPORTS = setOf("disabled", "fake", "smtp")
    }
}
