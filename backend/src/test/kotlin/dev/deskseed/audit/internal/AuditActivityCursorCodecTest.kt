package dev.deskseed.audit.internal

import dev.deskseed.audit.AuditActivityFilter
import dev.deskseed.audit.AuditLedgerType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class AuditActivityCursorCodecTest {
    private val filters = AuditActivityFilter(
        from = Instant.parse("2026-08-01T00:00:00Z"),
        to = Instant.parse("2026-08-12T00:00:00Z"),
        ledger = AuditLedgerType.ACCESS_SEARCH,
        action = "SEARCH_EXECUTED",
    )
    private val cursor = AuditActivityCursor(
        snapshotAt = Instant.parse("2026-08-12T00:00:00Z"),
        snapshotId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
        lastOccurredAt = Instant.parse("2026-08-11T00:00:00Z"),
        lastId = UUID.fromString("10000000-0000-0000-0000-000000000001"),
    )

    @Test
    fun `rotation decodes a previous key and active key signs new cursor`() {
        val previous = codec("v1", mapOf("v1" to KEY_ONE))
        val rotated = codec("v2", mapOf("v1" to KEY_ONE, "v2" to KEY_TWO))

        assertThat(rotated.decode(filters, previous.encode(filters, cursor))).isEqualTo(cursor)
        assertThat(rotated.encode(filters, cursor)).startsWith("v2.")
    }

    @Test
    fun `signature and normalized filters are bound to the cursor`() {
        val codec = codec("v1", mapOf("v1" to KEY_ONE))
        val encoded = codec.encode(filters, cursor)
        val parts = encoded.split('.')
        val signature = parts[2]
        val tampered = listOf(
            parts[0],
            parts[1],
            (if (signature.first() == 'A') 'B' else 'A').toString() + signature.drop(1),
        ).joinToString(".")

        assertThatThrownBy { codec.decode(filters, tampered) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { codec.decode(filters.copy(action = "TICKET_VIEWED"), encoded) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `filter fingerprint distinguishes delimiters unicode empty values and null`() {
        val codec = codec("v1", mapOf("v1" to KEY_ONE))
        val delimiterLeft = filters.copy(requestId = "a|b", correlationId = "c")
        val delimiterRight = filters.copy(requestId = "a", correlationId = "b|c")
        val empty = filters.copy(requestId = "", correlationId = "한글|🙂")
        val absent = filters.copy(requestId = null, correlationId = "한글|🙂")

        assertThat(codec.filterFingerprint(delimiterLeft))
            .isNotEqualTo(codec.filterFingerprint(delimiterRight))
        assertThat(codec.filterFingerprint(empty))
            .isNotEqualTo(codec.filterFingerprint(absent))
        assertThatThrownBy {
            codec.decode(delimiterRight, codec.encode(delimiterLeft, cursor))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun codec(active: String, keys: Map<String, String>) =
        AuditActivityCursorCodec(AuditActivityCursorProperties(active, keys))

    private companion object {
        const val KEY_ONE = "previous-audit-cursor-signing-key-with-at-least-thirty-two-characters"
        const val KEY_TWO = "active-audit-cursor-signing-key-with-at-least-thirty-two-characters"
    }
}
