package dev.deskseed.outboundmail.internal

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboundMailProperties::class)
internal class OutboundMailConfiguration {
    @Bean
    fun outboundMailSafety() = OutboundMailSafety()

    @Bean
    fun mailTemplateRenderer(properties: OutboundMailProperties, safety: OutboundMailSafety) =
        MailTemplateRenderer(properties, safety)

    @Bean
    fun mailRetryPolicy(properties: OutboundMailProperties) = MailRetryPolicy(properties)

    @Bean
    fun protectedMailContentCipher(properties: OutboundMailProperties) =
        ProtectedMailContentCipher(properties.protectedContent)

    @Bean
    fun mailDeliveryConfigurationValidator(
        properties: OutboundMailProperties,
        environment: Environment,
        safety: OutboundMailSafety,
    ) = MailDeliveryConfigurationValidator(properties, environment, safety)

    @Bean("outboundMailDeliveryHealthIndicator")
    fun outboundMailDeliveryHealthIndicator(properties: OutboundMailProperties) = HealthIndicator {
        Health.up()
            .withDetail("delivery", if (properties.deliveryEnabled) "enabled" else "disabled")
            .withDetail("scheduling", if (properties.schedulingEnabled) "enabled" else "disabled")
            .withDetail("transport", properties.transport.uppercase())
            .build()
    }
}
