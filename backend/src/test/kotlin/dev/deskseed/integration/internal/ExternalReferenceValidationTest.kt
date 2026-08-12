package dev.deskseed.integration.internal

import dev.deskseed.integration.ExternalReferenceValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant

class ExternalReferenceValidationTest {
    private val subject = ExternalReferenceValidation(ObjectMapper())

    @Test
    fun `normalizes registry identity and accepts exact allowed https host`() {
        assertThat(subject.normalizeSystemKey(" Shop-Order ")).isEqualTo("shop-order")
        assertThat(subject.normalizeHostnames(setOf("ADMIN.Shop.Example", "pay.shop.example")))
            .containsExactly("admin.shop.example", "pay.shop.example")

        val result = subject.validateLink(
            "https://admin.shop.example/orders/100?tab=refunds",
            listOf("admin.shop.example"),
        )

        assertThat(result.hostname).isEqualTo("admin.shop.example")
        assertThat(result.value).isEqualTo("https://admin.shop.example/orders/100?tab=refunds")
    }

    @Test
    fun `rejects unsafe schemes authority host ports controls and credential queries`() {
        val candidates = mapOf(
            "http://admin.shop.example/orders/100" to "EXTERNAL_LINK_HTTPS_REQUIRED",
            "file:///etc/passwd" to "EXTERNAL_LINK_HTTPS_REQUIRED",
            "gopher://admin.shop.example/1" to "EXTERNAL_LINK_HTTPS_REQUIRED",
            "https://staff:password@admin.shop.example/orders/100" to "EXTERNAL_LINK_USERINFO_FORBIDDEN",
            "https://admin.shop.example:8443/orders/100" to "EXTERNAL_LINK_PORT_FORBIDDEN",
            "https://evil.example/orders/100" to "EXTERNAL_LINK_HOST_NOT_ALLOWED",
            "https://admin.shop.example/orders/100#secret" to "EXTERNAL_LINK_FRAGMENT_FORBIDDEN",
            "https://admin.shop.example/orders/100?access_token=secret" to
                "EXTERNAL_LINK_CREDENTIAL_QUERY_FORBIDDEN",
            "https://admin.shop.example/orders/100?%74oken=secret" to
                "EXTERNAL_LINK_CREDENTIAL_QUERY_FORBIDDEN",
            "https://admin.shop.example/orders/%0A100" to "EXTERNAL_LINK_CONTROL_CHARACTER",
        )

        candidates.forEach { (candidate, code) ->
            assertThatThrownBy { subject.validateLink(candidate, listOf("admin.shop.example")) }
                .isInstanceOfSatisfying(ExternalReferenceValidationException::class.java) {
                    assertThat(it.code).describedAs(candidate).isEqualTo(code)
                }
        }
    }

    @Test
    fun `rejects ip literals localhost private name seams and wildcard registry hosts`() {
        listOf(
            "127.0.0.1",
            "10.0.0.7",
            "169.254.169.254",
            "localhost",
            "orders.localhost",
            "orders.internal",
            "orders.local",
            "*.shop.example",
        ).forEach { host ->
            assertThatThrownBy { subject.normalizeHostnames(setOf(host)) }
                .describedAs(host)
                .isInstanceOf(ExternalReferenceValidationException::class.java)
        }
    }

    @Test
    fun `keeps only bounded allowlisted scalar metadata`() {
        val normalized = subject.normalizeMetadata(
            mapOf(
                "status" to " paid ",
                "amountDisplay" to 12900,
                "channel" to true,
            ),
        )

        assertThat(normalized).containsEntry("status", "paid")
        assertThat(normalized).containsEntry("amountDisplay", 12900)
        assertThat(normalized).containsEntry("channel", true)
        assertThatThrownBy { subject.normalizeMetadata(mapOf("rawPayload" to "forbidden")) }
            .isInstanceOfSatisfying(ExternalReferenceValidationException::class.java) {
                assertThat(it.code).isEqualTo("EXTERNAL_METADATA_KEY_FORBIDDEN")
            }
        assertThatThrownBy { subject.normalizeMetadata(mapOf("status" to mapOf("nested" to true))) }
            .isInstanceOfSatisfying(ExternalReferenceValidationException::class.java) {
                assertThat(it.code).isEqualTo("EXTERNAL_METADATA_VALUE_INVALID")
            }
        assertThatThrownBy { subject.normalizeMetadata(mapOf("status" to "x".repeat(201))) }
            .isInstanceOfSatisfying(ExternalReferenceValidationException::class.java) {
                assertThat(it.code).isEqualTo("EXTERNAL_METADATA_VALUE_INVALID")
            }
    }

    @Test
    fun `rejects stale and unreasonably future snapshot timestamps`() {
        val now = Instant.parse("2026-08-12T00:00:00Z")

        assertThat(subject.validateObservedAt(now.minusSeconds(60), now)).isEqualTo(now.minusSeconds(60))
        assertThatThrownBy { subject.validateObservedAt(now.plusSeconds(301), now) }
            .isInstanceOf(ExternalReferenceValidationException::class.java)
        assertThatThrownBy { subject.validateObservedAt(now.minusSeconds(3_651L * 86_400L), now) }
            .isInstanceOf(ExternalReferenceValidationException::class.java)
    }
}
