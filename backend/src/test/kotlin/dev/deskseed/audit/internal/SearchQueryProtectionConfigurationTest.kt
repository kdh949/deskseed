package dev.deskseed.audit.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class SearchQueryProtectionConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(SearchQueryProtectionConfiguration::class.java)

    @Test
    fun `enabled access audit fails application startup when active key is missing`() {
        contextRunner
            .withPropertyValues(
                "deskseed.audit.access.enabled=true",
                "deskseed.audit.access.active-key-version=v1",
                "deskseed.audit.access.session-fingerprint-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(SearchQueryConfigurationException::class.java)
            }
    }

    @Test
    fun `enabled access audit fails application startup when session fingerprint key is missing`() {
        contextRunner
            .withPropertyValues(
                "deskseed.audit.access.enabled=true",
                "deskseed.audit.access.active-key-version=v1",
                "deskseed.audit.access.keys.v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(SearchQueryConfigurationException::class.java)
            }
    }

    @Test
    fun `disabled access audit may start without a key but reports out of service`() {
        contextRunner
            .withPropertyValues("deskseed.audit.access.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean("accessAuditKeyHealthIndicator")).isNotNull()
            }
    }
}
