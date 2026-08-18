package dev.deskseed.customerauth.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class CustomerAuthSecretsTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `customer session fingerprint is stable bounded and purpose bound`() {
        val sessionId = UUID.fromString("11111111-1111-4111-8111-111111111111")

        val first = CustomerAuthSecrets.customerSessionFingerprint(key, sessionId)
        val second = CustomerAuthSecrets.customerSessionFingerprint(key, sessionId)

        assertThat(first).isEqualTo(second)
        assertThat(first).matches("v1:[A-Za-z0-9_-]{43}")
        assertThat(first).doesNotContain(sessionId.toString())
        assertThat(first).isNotEqualTo(CustomerAuthSecrets.fingerprint(key, sessionId.toString()))
    }
}
