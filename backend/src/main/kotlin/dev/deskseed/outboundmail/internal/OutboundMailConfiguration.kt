package dev.deskseed.outboundmail.internal

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
}
