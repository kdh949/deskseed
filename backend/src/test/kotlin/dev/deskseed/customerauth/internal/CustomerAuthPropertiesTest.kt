package dev.deskseed.customerauth.internal

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class CustomerAuthPropertiesTest {
    @Test
    fun `magic link ttl accepts the inclusive five to sixty minute policy`() {
        listOf(Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60)).forEach { ttl ->
            assertThatCode { CustomerAuthProperties(magicLinkTtl = ttl).validate() }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `magic link ttl rejects values outside policy`() {
        listOf(Duration.ofMinutes(4), Duration.ofMinutes(61)).forEach { ttl ->
            assertThatThrownBy { CustomerAuthProperties(magicLinkTtl = ttl).validate() }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
