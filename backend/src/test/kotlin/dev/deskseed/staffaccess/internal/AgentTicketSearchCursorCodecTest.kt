package dev.deskseed.staffaccess.internal

import dev.deskseed.ticketing.StaffTicketSearchCursor
import dev.deskseed.ticketing.StaffTicketSearchPartition
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@dev.deskseed.testsupport.category.FastTest
class AgentTicketSearchCursorCodecTest {
    private val codec = AgentTicketSearchCursorCodec(
        AgentTicketCursorProperties(
            activeKeyId = "v1",
            signingKeys = mapOf("v1" to "active-cursor-signing-key-with-at-least-thirty-two-characters"),
        ),
    )

    @Test
    fun `signed cursor retains the candidate partition across pages`() {
        val filters = AgentTicketSearchFilter(null, null, null, null, null)
        val cursor = StaffTicketSearchCursor(
            snapshotAt = Instant.parse("2026-09-21T06:55:25Z"),
            lastScore = 3,
            lastUpdatedAt = null,
            lastTicketNumber = 8899,
            returnedBefore = 25,
            partition = StaffTicketSearchPartition.TERMINAL,
        )

        val encoded = codec.encode("partition marker", filters, "score:desc,ticketNumber:desc", cursor)

        assertThat(codec.decode("partition marker", filters, "score:desc,ticketNumber:desc", encoded))
            .isEqualTo(cursor)
    }
}
