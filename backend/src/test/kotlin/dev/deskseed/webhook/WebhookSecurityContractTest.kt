package dev.deskseed.webhook

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.time.Instant

class WebhookSecurityContractTest {
    private val signer = WebhookSignatureSigner()

    @Test
    fun `signature uses the documented timestamp dot raw body HMAC vector`() {
        val signature = signer.sign(
            secret = "whsec_test_secret".toByteArray(),
            timestamp = Instant.ofEpochSecond(1_700_000_000),
            rawBody = "{\"id\":\"evt_1\",\"type\":\"ticket.created\"}".toByteArray(),
        )

        assertThat(signature).isEqualTo("v1=L8UpXTKFwmnfxtxKEPwmZ/8Hqv3Dp/Y8aoceLsvS/uk=")
        assertThat(
            signer.verify(
                secret = "whsec_test_secret".toByteArray(),
                timestamp = Instant.ofEpochSecond(1_700_000_000),
                rawBody = "{\"id\":\"evt_1\",\"type\":\"ticket.created\"}".toByteArray(),
                signature = signature,
            ),
        ).isTrue()
        assertThat(
            signer.verify(
                secret = "whsec_test_secret".toByteArray(),
                timestamp = Instant.ofEpochSecond(1_700_000_000),
                rawBody = "{\"id\":\"evt_2\",\"type\":\"ticket.created\"}".toByteArray(),
                signature = signature,
            ),
        ).isFalse()
    }

    @Test
    fun `public target rejects every non-public resolved address`() {
        val validator = WebhookTargetValidator(
            resolver = StubResolver(
                mapOf(
                    "mixed.example.test" to listOf("203.0.113.10", "10.0.0.12"),
                ),
            ),
        )

        assertThatThrownBy {
            validator.validate(
                url = "https://mixed.example.test/hooks/ticket",
                policy = WebhookTargetPolicy.publicDefault(),
            )
        }.isInstanceOf(WebhookTargetRejectedException::class.java)
            .hasMessage("WEBHOOK_TARGET_ADDRESS_NOT_ALLOWED")
    }

    @Test
    fun `private target requires endpoint scoped hostname port and CIDR approval while loopback stays forbidden`() {
        val resolver = StubResolver(
            mapOf(
                "operations.internal.test" to listOf("10.20.30.40"),
                "loopback.internal.test" to listOf("127.0.0.1"),
            ),
        )
        val validator = WebhookTargetValidator(resolver)

        val approved = validator.validate(
            url = "https://operations.internal.test:8443/events",
            policy = WebhookTargetPolicy.privateApproved(
                hostname = "operations.internal.test",
                port = 8443,
                cidrs = setOf("10.20.0.0/16"),
            ),
        )

        assertThat(approved.addresses.map { it.hostAddress }).containsExactly("10.20.30.40")
        assertThatThrownBy {
            validator.validate(
                url = "https://operations.internal.test:9443/events",
                policy = WebhookTargetPolicy.privateApproved(
                    hostname = "operations.internal.test",
                    port = 8443,
                    cidrs = setOf("10.20.0.0/16"),
                ),
            )
        }.isInstanceOf(WebhookTargetRejectedException::class.java)
            .hasMessage("WEBHOOK_TARGET_PORT_NOT_ALLOWED")
        assertThatThrownBy {
            validator.validate(
                url = "https://loopback.internal.test:8443/events",
                policy = WebhookTargetPolicy.privateApproved(
                    hostname = "loopback.internal.test",
                    port = 8443,
                    cidrs = setOf("127.0.0.0/8"),
                ),
            )
        }.isInstanceOf(WebhookTargetRejectedException::class.java)
            .hasMessage("WEBHOOK_TARGET_ADDRESS_NOT_ALLOWED")
    }

    @Test
    fun `target requires https without userinfo and defaults public HTTPS to port 443`() {
        val validator = WebhookTargetValidator(StubResolver(mapOf("hooks.example.test" to listOf("203.0.113.10"))))

        val target = validator.validate(
            url = "https://hooks.example.test/events",
            policy = WebhookTargetPolicy.publicDefault(),
        )

        assertThat(target.port).isEqualTo(443)
        listOf(
            "http://hooks.example.test/events",
            "https://user:password@hooks.example.test/events",
            "https://hooks.example.test:8443/events",
        ).forEach { url ->
            assertThatThrownBy { validator.validate(url, WebhookTargetPolicy.publicDefault()) }
                .isInstanceOf(WebhookTargetRejectedException::class.java)
        }
    }

    private class StubResolver(private val values: Map<String, List<String>>) : WebhookAddressResolver {
        override fun resolve(hostname: String): List<InetAddress> = values[hostname].orEmpty().map(InetAddress::getByName)
    }
}
