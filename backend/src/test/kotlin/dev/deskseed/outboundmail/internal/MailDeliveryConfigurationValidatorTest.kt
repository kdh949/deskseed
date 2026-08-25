package dev.deskseed.outboundmail.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

@dev.deskseed.testsupport.category.FastTest
class MailDeliveryConfigurationValidatorTest {
    private val protectedKey = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM="
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(OutboundMailConfiguration::class.java)

    @Test
    fun `disabled delivery still requires a protected content key for queued protected intents`() {
        val properties = properties(deliveryEnabled = false, transport = "disabled", protectedKey = null)
        val environment = MockEnvironment().apply { setActiveProfiles("production") }

        assertThatThrownBy {
            MailDeliveryConfigurationValidator(properties, environment, OutboundMailSafety()).afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("production") }
            .withPropertyValues(
                "deskseed.mail.delivery-enabled=false",
                "deskseed.mail.transport=disabled",
                "deskseed.mail.from-address=",
                "deskseed.mail.public-base-url=",
                "deskseed.mail.protected-content.active-key-version=v1",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalStateException::class.java)
            }
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("production") }
            .withPropertyValues(
                "deskseed.mail.delivery-enabled=false",
                "deskseed.mail.transport=disabled",
                "deskseed.mail.protected-content.active-key-version=v1",
                "deskseed.mail.protected-content.keys.v1=$protectedKey",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean("outboundMailDeliveryHealthIndicator")).isNotNull()
            }
    }

    @Test
    fun `enabled production SMTP rejects every missing security prerequisite`() {
        val environment = MockEnvironment().apply { setActiveProfiles("production") }

        assertThatThrownBy {
            MailDeliveryConfigurationValidator(properties(deliveryEnabled = true, transport = "smtp"), environment, OutboundMailSafety())
                .afterPropertiesSet()
        }.isInstanceOf(IllegalArgumentException::class.java)
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("production") }
            .withPropertyValues(
                "deskseed.mail.delivery-enabled=true",
                "deskseed.mail.transport=smtp",
                "deskseed.mail.from-address=no-reply@deskseed.example",
                "deskseed.mail.public-base-url=https://deskseed.example",
                "deskseed.mail.protected-content.active-key-version=v1",
                "deskseed.mail.protected-content.keys.v1=$protectedKey",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            }
    }

    @Test
    fun `enabled production SMTP accepts explicit authenticated TLS configuration`() {
        val environment = MockEnvironment().apply {
            setActiveProfiles("production")
            setProperty("spring.mail.host", "smtp.deskseed.example")
            setProperty("spring.mail.port", "587")
            setProperty("spring.mail.username", "deskseed-smtp")
            setProperty("spring.mail.password", "not-a-real-secret")
            setProperty("spring.mail.properties[mail.smtp.auth]", "true")
            setProperty("spring.mail.properties[mail.smtp.starttls.enable]", "true")
            setProperty("spring.mail.properties[mail.smtp.starttls.required]", "true")
        }

        assertThatCode {
            MailDeliveryConfigurationValidator(properties(deliveryEnabled = true, transport = "smtp"), environment, OutboundMailSafety())
                .afterPropertiesSet()
            ProtectedMailContentCipher(properties(deliveryEnabled = true, transport = "smtp").protectedContent)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `production profile leaves SMTP values unprovisioned until delivery is explicitly enabled`() {
        val production = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-production.yml"))
        }.getObject()

        assertThat(production?.getProperty("deskseed.mail.delivery-enabled"))
            .isEqualTo("${'$'}{DESKSEED_MAIL_DELIVERY_ENABLED:false}")
        assertThat(production?.getProperty("deskseed.mail.transport"))
            .isEqualTo("${'$'}{DESKSEED_MAIL_TRANSPORT:disabled}")
        assertThat(production?.getProperty("spring.mail.host"))
            .isEqualTo("${'$'}{DESKSEED_MAIL_SMTP_HOST:}")
        assertThat(production?.getProperty("deskseed.mail.protected-content.keys.v1"))
            .isEqualTo("${'$'}{DESKSEED_MAIL_PROTECTED_KEY_V1}")
        assertThat(production?.getProperty("deskseed.mail.operations.cursor.signing-keys.v1"))
            .isEqualTo("${'$'}{DESKSEED_MAIL_OPERATIONS_CURSOR_SIGNING_KEY}")
    }

    private fun properties(
        deliveryEnabled: Boolean,
        transport: String,
        protectedKey: String? = this.protectedKey,
    ) = OutboundMailProperties(
        deliveryEnabled = deliveryEnabled,
        transport = transport,
        fromAddress = "no-reply@deskseed.example",
        publicBaseUrl = "https://deskseed.example",
        protectedContent = ProtectedMailContentProperties(
            activeKeyVersion = "v1",
            keys = protectedKey?.let { mapOf("v1" to it) }.orEmpty(),
        ),
    )
}
