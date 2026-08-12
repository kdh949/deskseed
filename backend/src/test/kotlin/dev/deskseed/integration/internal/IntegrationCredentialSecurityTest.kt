package dev.deskseed.integration.internal

import dev.deskseed.integration.IntegrationResourceConstraints
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IntegrationCredentialSecurityTest {
    private val hasher = IntegrationSecretHasher()
    private val ipPolicy = IpAllowlistPolicy()

    @Test
    fun `PBKDF2 verifier is salted and verifies without storing plaintext`() {
        val secret = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
        val first = hasher.hash(secret)
        val second = hasher.hash(secret)

        assertThat(first).isNotEqualTo(second).doesNotContain(secret)
        assertThat(hasher.matches(secret, first)).isTrue()
        assertThat(hasher.matches("wrong-secret", first)).isFalse()
        assertThat(hasher.matches("wrong-secret", null)).isFalse()
    }

    @Test
    fun `IPv4 and IPv6 CIDR seam permits only matching literal addresses`() {
        val allowlist = setOf("10.20.0.0/16", "2001:db8::/32", "192.0.2.10")
        ipPolicy.validate(IntegrationResourceConstraints(ipAllowlist = allowlist))

        assertThat(ipPolicy.isAllowed("10.20.3.9", allowlist)).isTrue()
        assertThat(ipPolicy.isAllowed("10.21.3.9", allowlist)).isFalse()
        assertThat(ipPolicy.isAllowed("2001:db8::42", allowlist)).isTrue()
        assertThat(ipPolicy.isAllowed("2001:db9::42", allowlist)).isFalse()
        assertThat(ipPolicy.isAllowed("192.0.2.10", allowlist)).isTrue()
        assertThat(ipPolicy.isAllowed("orders.example.com", allowlist)).isFalse()
        assertThat(ipPolicy.isAllowed("203.0.113.1", null)).isTrue()
    }

    @Test
    fun `invalid CIDR is rejected at the management boundary`() {
        assertThatThrownBy {
            ipPolicy.validate(IntegrationResourceConstraints(ipAllowlist = setOf("10.0.0.0/99")))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
