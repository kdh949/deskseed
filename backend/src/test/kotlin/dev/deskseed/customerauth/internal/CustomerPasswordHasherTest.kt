package dev.deskseed.customerauth.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

@dev.deskseed.testsupport.category.FastTest
class CustomerPasswordHasherTest {
    private val hasher = CustomerPasswordHasher()

    @Test
    fun `customer passwords use the dedicated Argon2id policy without exposing the encoded value`() {
        val rawPassword = "correct horse battery 🔐"

        val encoded = hasher.encode(rawPassword)

        assertThat(encoded.encoded)
            .startsWith("${'$'}argon2id${'$'}v=19${'$'}m=19456,t=2,p=1${'$'}")
            .doesNotContain(rawPassword)
        assertThat(hasher.matches(rawPassword, encoded)).isTrue()
        assertThat(hasher.matches("different password", encoded)).isFalse()
        assertThat(encoded.toString()).isEqualTo("[PROTECTED CUSTOMER PASSWORD HASH]")
    }

    @Test
    fun `password policy counts Unicode code points and rejects controls outside 12 to 128 characters`() {
        assertThat(hasher.encode("가".repeat(12)))
            .isNotNull
        assertThatThrownBy { hasher.encode("a".repeat(11)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("customer password must contain 12 to 128 characters")
        assertThat(hasher.encode("🔐".repeat(12))).isNotNull
        assertThatThrownBy { hasher.encode("🔐".repeat(129)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("customer password must contain 12 to 128 characters")
        assertThatThrownBy { hasher.encode("valid password\u0007") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("customer password must not contain control characters")
    }

    @Test
    fun `encoded hash parser accepts Argon2id only`() {
        assertThatThrownBy { CustomerPasswordHash.fromEncoded("{bcrypt}not-customer-argon") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("customer password hash must use Argon2id")
    }
}
