package dev.deskseed.staffaccess.internal

import dev.deskseed.ticketing.DefaultStaffView
import dev.deskseed.ticketing.StaffTicketCursor
import dev.deskseed.ticketing.StaffTicketListFilter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class AgentTicketCursorCodecTest {
    private val filters = StaffTicketListFilter()
    private val cursor = StaffTicketCursor(Instant.parse("2026-08-10T10:15:30Z"), 1042)

    @Test
    fun `active key signs cursors while a retained previous key remains decodable`() {
        val previous = AgentTicketCursorCodec(
            AgentTicketCursorProperties(
                activeKeyId = "v1",
                signingKeys = mapOf("v1" to "previous-cursor-signing-key-with-at-least-thirty-two-characters"),
            ),
        )
        val rotated = AgentTicketCursorCodec(
            AgentTicketCursorProperties(
                activeKeyId = "v2",
                signingKeys = mapOf(
                    "v1" to "previous-cursor-signing-key-with-at-least-thirty-two-characters",
                    "v2" to "active-cursor-signing-key-with-at-least-thirty-two-characters",
                ),
            ),
        )

        val priorCursor = previous.encode(DefaultStaffView.MY_OPEN, filters, cursor)
        val activeCursor = rotated.encode(DefaultStaffView.MY_OPEN, filters, cursor)

        assertThat(rotated.decode(DefaultStaffView.MY_OPEN, filters, priorCursor)).isEqualTo(cursor)
        assertThat(rotated.decode(DefaultStaffView.MY_OPEN, filters, activeCursor)).isEqualTo(cursor)
        assertThat(activeCursor.substringBefore('.')).isEqualTo("v2")
    }

    @Test
    fun `cursor signature rejects a one character mutation`() {
        val codec = AgentTicketCursorCodec(
            AgentTicketCursorProperties(
                activeKeyId = "v1",
                signingKeys = mapOf("v1" to "active-cursor-signing-key-with-at-least-thirty-two-characters"),
            ),
        )
        val encoded = codec.encode(DefaultStaffView.MY_OPEN, filters, cursor)
        val parts = encoded.split('.')
        val signature = parts[2]
        val tampered = listOf(
            parts[0],
            parts[1],
            (if (signature.first() == 'A') 'B' else 'A').toString() + signature.drop(1),
        ).joinToString(".")

        assertThatThrownBy { codec.decode(DefaultStaffView.MY_OPEN, filters, tampered) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
