package dev.deskseed.customerconsent

import dev.deskseed.knowledge.CanonicalKnowledgeDocument
import dev.deskseed.knowledge.KnowledgeBlock
import dev.deskseed.organization.StaffAuthorityCatalog
import dev.deskseed.organization.StaffRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class CustomerConsentDocumentTest {
    private val objectMapper = JsonMapper.builder().build()
    private val documents = CanonicalCustomerConsentDocumentCodec()

    @Test
    fun `canonicalizes the contracted safe block subset with deterministic text and checksum`() {
        val validated = documents.validateForPublish(
            documents.decode(
                objectMapper.readTree(
                    """{"schemaVersion":1,"blocks":[{"type":"heading","level":2,"text":"이용 조건"},{"type":"paragraph","text":"합성 정책 본문"},{"type":"link","text":"상세 정책","url":"https://policy.example.test/terms"}]}""",
                ),
            ),
        )

        assertThat(validated.plainText).isEqualTo("이용 조건\n합성 정책 본문\n상세 정책")
        assertThat(validated.checksumSha256).matches("[0-9a-f]{64}")
        assertThat(documents.encode(validated.document)).containsKeys("schemaVersion", "blocks")
    }

    @Test
    fun `rejects html code attachments unsafe URLs angle brackets and control characters before persistence`() {
        val rejected = listOf(
            CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Html("<script>alert(1)</script>"))),
            CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Code("text", "secret"))),
            CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Attachment(UUID.randomUUID()))),
            CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Link("unsafe", "http://policy.example.test"))),
            CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Paragraph("<b>markup</b>"))),
            CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Paragraph("line\nbreak"))),
        )

        rejected.forEach { document ->
            assertThatThrownBy { documents.validateDraft(document) }
                .isInstanceOf(InvalidCustomerConsentDocumentException::class.java)
        }
    }

    @Test
    fun `publish boundary accepts fifty thousand canonical characters and rejects fifty thousand one`() {
        documents.validateForPublish(documentWithPlainTextLength(50_000))

        assertThatThrownBy { documents.validateForPublish(documentWithPlainTextLength(50_001)) }
            .isInstanceOf(InvalidCustomerConsentDocumentException::class.java)
    }

    @Test
    fun `consent management authority belongs only to the initial admin role`() {
        assertThat(StaffAuthorityCatalog.forRole(StaffRole.ADMIN))
            .contains(StaffAuthorityCatalog.CUSTOMER_CONSENT_MANAGE)
        assertThat(StaffAuthorityCatalog.forRole(StaffRole.AGENT))
            .doesNotContain(StaffAuthorityCatalog.CUSTOMER_CONSENT_MANAGE)
        assertThat(StaffAuthorityCatalog.forRole(StaffRole.SECURITY_AUDITOR))
            .doesNotContain(StaffAuthorityCatalog.CUSTOMER_CONSENT_MANAGE)
    }

    private fun documentWithPlainTextLength(length: Int): CanonicalKnowledgeDocument {
        val blocks = mutableListOf<KnowledgeBlock>()
        var remainingText = length
        while (remainingText > 0) {
            val separatorCost = if (blocks.isEmpty()) 0 else 1
            val blockLength = minOf(10_000, remainingText - separatorCost)
            check(blockLength > 0)
            blocks += KnowledgeBlock.Paragraph("a".repeat(blockLength))
            remainingText -= blockLength + separatorCost
        }
        return CanonicalKnowledgeDocument(1, blocks)
    }
}
