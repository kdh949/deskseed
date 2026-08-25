package dev.deskseed.portal.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

@dev.deskseed.testsupport.category.FastTest
class CustomerPortalSecurityPropertiesTest {
    private val signingKey = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ="
    private val fingerprintKey = "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU="
    private val cursorKey = "BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY="

    @Test
    fun `startup validation rejects missing invalid and short customer portal keys`() {
        listOf(
            properties(claimSigningKey = ""),
            properties(claimFingerprintKey = "not-base64"),
            properties(requestCursorSigningKey = "AQID"),
        ).forEach { properties ->
            assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `startup validation rejects key reuse across customer portal purposes`() {
        assertThatThrownBy {
            properties(requestCursorSigningKey = signingKey).afterPropertiesSet()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("purpose-specific")
    }

    @Test
    fun `production profile requires all customer portal secrets without defaults`() {
        val production = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-production.yml"))
        }.getObject()

        assertThat(production?.getProperty("deskseed.customer-portal.claim-signing-key"))
            .isEqualTo("${'$'}{DESKSEED_CUSTOMER_CLAIM_SIGNING_KEY}")
        assertThat(production?.getProperty("deskseed.customer-portal.claim-fingerprint-key"))
            .isEqualTo("${'$'}{DESKSEED_CUSTOMER_CLAIM_FINGERPRINT_KEY}")
        assertThat(production?.getProperty("deskseed.customer-portal.request-cursor-signing-key"))
            .isEqualTo("${'$'}{DESKSEED_CUSTOMER_REQUEST_CURSOR_SIGNING_KEY}")
    }

    private fun properties(
        claimSigningKey: String = signingKey,
        claimFingerprintKey: String = fingerprintKey,
        requestCursorSigningKey: String = cursorKey,
    ) = CustomerPortalSecurityProperties(
        claimSigningKey = claimSigningKey,
        claimFingerprintKey = claimFingerprintKey,
        requestCursorSigningKey = requestCursorSigningKey,
    )
}
