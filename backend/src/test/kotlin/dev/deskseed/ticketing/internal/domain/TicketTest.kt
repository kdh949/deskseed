package dev.deskseed.ticketing.internal.domain

import dev.deskseed.ticketing.CommentAuthorType
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.internal.TicketEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.reflect.full.memberProperties

@dev.deskseed.testsupport.category.FastTest
class TicketTest {
    @Test
    fun `web submission stores the request body as the first public customer comment`() {
        val ticket = Ticket.submitFromWeb(
            ticketNumber = 1000,
            requesterId = UUID.randomUUID(),
            subject = "결제가 되지 않아요",
            message = "카드를 눌러도 오류가 발생합니다.",
            now = Instant.parse("2026-08-10T00:00:00Z"),
        )

        assertThat(ticket.firstComment.body).isEqualTo("카드를 눌러도 오류가 발생합니다.")
        assertThat(ticket.firstComment.visibility).isEqualTo(CommentVisibility.PUBLIC)
        assertThat(ticket.firstComment.authorType).isEqualTo(CommentAuthorType.CUSTOMER)
        assertThat(Ticket::class.memberProperties.map { it.name }).doesNotContain("description")
        assertThat(TicketEntity::class.memberProperties.map { it.name }).doesNotContain("description")
    }
}
