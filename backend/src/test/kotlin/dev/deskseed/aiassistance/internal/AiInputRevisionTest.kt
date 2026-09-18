package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiFeature
import dev.deskseed.ticketing.AiCommentAuthorRole
import dev.deskseed.ticketing.AiPublicComment
import dev.deskseed.ticketing.AiPublicTicketContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class AiInputRevisionTest {
    @Test
    fun `ticket metadata version does not change PUBLIC input revision`() {
        val context = context()

        val before = computeAiInputRevision(context, AiFeature.TICKET_SUMMARY)
        val after = computeAiInputRevision(context.copy(ticketVersion = context.ticketVersion + 1), AiFeature.TICKET_SUMMARY)

        assertThat(before).isEqualTo(after).matches("^[0-9a-f]{64}$")
        assertThat(computeAiContextRevision(context)).isNotEqualTo(computeAiContextRevision(context.copy(ticketVersion = 8)))
    }

    @Test
    fun `every current PUBLIC comment fact participates in the input revision`() {
        val context = context()
        val original = computeAiInputRevision(context, AiFeature.TICKET_SUMMARY)
        val comment = context.comments.single()

        val variants = listOf(
            comment.copy(id = UUID.randomUUID()),
            comment.copy(sequence = comment.sequence + 1),
            comment.copy(authorRole = AiCommentAuthorRole.STAFF),
            comment.copy(createdAt = comment.createdAt.plusSeconds(1)),
            comment.copy(body = "변경된 공개 문의"),
        )

        assertThat(variants.map { computeAiInputRevision(context.copy(comments = listOf(it)), AiFeature.TICKET_SUMMARY) })
            .allMatch { it != original }
    }

    @Test
    fun `feature policy domains are distinct and mismatches fail closed`() {
        val context = context()
        val revisions = AiFeature.entries.associateWith { computeAiInputRevision(context, it) }

        assertThat(revisions.values).doesNotHaveDuplicates()
        assertThat(inputPolicyVersion(AiFeature.TICKET_SUMMARY)).isEqualTo("summary-input-v1")
        assertThat(inputPolicyVersion(AiFeature.TICKET_TRIAGE)).isEqualTo("triage-input-v1")
        assertThat(inputPolicyVersion(AiFeature.TICKET_REPLY_DRAFT)).isEqualTo("reply-input-v1")
        assertThatThrownBy {
            computeAiInputRevision(context, AiFeature.TICKET_SUMMARY, "reply-input-v1")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun context() = AiPublicTicketContext(
        ticketId = UUID.randomUUID(),
        ticketNumber = 42,
        ticketVersion = 7,
        comments = listOf(
            AiPublicComment(
                id = UUID.randomUUID(),
                sequence = 1,
                authorRole = AiCommentAuthorRole.CUSTOMER,
                body = "공개 문의",
                createdAt = Instant.parse("2026-09-19T00:00:00Z"),
            ),
        ),
    )
}
