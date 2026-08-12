package dev.deskseed.audit.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class SearchQueryProtectionTest {
    private val eventId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val occurredAt = Instant.parse("2026-08-11T00:00:00Z")

    @Test
    fun `protect stores redacted text keyed fingerprint and authenticated ciphertext without losing the exact original`() {
        val protection = protection(activeVersion = "v1", "v1" to key(1))
        val rawQuery = "  customer@example.com\npassword=correct-horse-battery-staple  "

        val protected = protection.protect(eventId, rawQuery, occurredAt)

        assertThat(protected.queryRedacted).isEqualTo("[PROTECTED]")
        assertThat(protected.queryFingerprint).isNotBlank()
        assertThat(protected.keyVersion).isEqualTo("v1")
        assertThat(String(protected.queryCiphertext, Charsets.UTF_8)).doesNotContain(rawQuery)
        assertThat(protected.expiresAt).isEqualTo(occurredAt.plus(Duration.ofDays(30)))
        assertThat(protection.reveal(eventId, protected)).isEqualTo(rawQuery)
    }

    @Test
    fun `routine audit representation is content free for arbitrary sensitive search text`() {
        val protection = protection(activeVersion = "v1", "v1" to key(11))
        val sensitiveQueries = listOf(
            "eyJhbGciOiJIUzI1NiJ9.opaquePayload.signature",
            "+82 10-1234-5678 900101-1234567",
            "환자는 희귀질환 HIV 양성 진단",
            "返品理由：家族に知られたくない 자유 메모",
            "  민감\t검색\r\n\u0000내용  ",
        )

        sensitiveQueries.forEachIndexed { index, rawQuery ->
            val protected = protection.protect(
                UUID.fromString("10000000-0000-0000-0000-${(index + 2).toString().padStart(12, '0')}"),
                rawQuery,
                occurredAt,
            )

            assertThat(protected.queryRedacted).isEqualTo("[PROTECTED]")
        }
    }

    @Test
    fun `fingerprint uses normalized query while ciphertext preserves the original`() {
        val protection = protection(activeVersion = "v1", "v1" to key(2))

        val first = protection.protect(eventId, " 결제   오류 ", occurredAt)
        val second = protection.protect(UUID.randomUUID(), "결제 오류", occurredAt)

        assertThat(first.queryFingerprint).isEqualTo(second.queryFingerprint)
        assertThat(protection.reveal(eventId, first)).isEqualTo(" 결제   오류 ")
    }

    @Test
    fun `associated data rejects event substitution and ciphertext tampering`() {
        val protection = protection(activeVersion = "v1", "v1" to key(3))
        val protected = protection.protect(eventId, "민감한 검색", occurredAt)
        val tamperedBytes = protected.queryCiphertext.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }

        assertThatThrownBy { protection.reveal(UUID.randomUUID(), protected) }
            .isInstanceOf(SearchQueryProtectionException::class.java)
        assertThatThrownBy {
            protection.reveal(eventId, protected.copy(queryCiphertext = tamperedBytes))
        }.isInstanceOf(SearchQueryProtectionException::class.java)
    }

    @Test
    fun `rotated keyring decrypts old ciphertext and writes new version`() {
        val original = protection(activeVersion = "v1", "v1" to key(4))
            .protect(eventId, "rotation query", occurredAt)
        val rotated = protection(
            activeVersion = "v2",
            "v1" to key(4),
            "v2" to key(5),
        )

        assertThat(rotated.reveal(eventId, original)).isEqualTo("rotation query")
        assertThat(rotated.protect(UUID.randomUUID(), "new query", occurredAt).keyVersion).isEqualTo("v2")
    }

    @Test
    fun `missing invalid and unknown active keys fail configuration`() {
        assertThatThrownBy {
            SearchQueryProtection(
                SearchQueryAuditProperties(
                    enabled = true,
                    activeKeyVersion = "v1",
                    keys = emptyMap(),
                ),
            )
        }.isInstanceOf(SearchQueryConfigurationException::class.java)

        assertThatThrownBy {
            protection(activeVersion = "missing", "v1" to key(6))
        }.isInstanceOf(SearchQueryConfigurationException::class.java)

        assertThatThrownBy {
            protection(activeVersion = "v1", "v1" to Base64.getEncoder().encodeToString(ByteArray(16)))
        }.isInstanceOf(SearchQueryConfigurationException::class.java)

        assertThatThrownBy {
            protection(activeVersion = "v".repeat(65), "v".repeat(65) to key(7))
        }.isInstanceOf(SearchQueryConfigurationException::class.java)
    }

    private fun protection(activeVersion: String, vararg keys: Pair<String, String>) =
        SearchQueryProtection(
            SearchQueryAuditProperties(
                enabled = true,
                activeKeyVersion = activeVersion,
                keys = mapOf(*keys),
            ),
        )

    private fun key(seed: Int): String = Base64.getEncoder().encodeToString(
        ByteArray(32) { index -> (seed + index).toByte() },
    )
}
