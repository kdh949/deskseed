package dev.deskseed.knowledge

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class KnowledgeContentTest {
    private val validator = CanonicalKnowledgeDocumentValidator()

    @Test
    fun `accepts supported canonical blocks and extracts safe plain text`() {
        val document = CanonicalKnowledgeDocument(
            schemaVersion = 1,
            blocks = listOf(
                KnowledgeBlock.Heading(level = 2, text = "결제가 실패할 때"),
                KnowledgeBlock.Paragraph("카드 승인 상태를 확인하세요."),
                KnowledgeBlock.Link("결제 도움말", "https://help.example.test/payment"),
            ),
        )

        val validated = validator.validate(document)

        assertThat(validated.plainText).isEqualTo("결제가 실패할 때\n카드 승인 상태를 확인하세요.\n결제 도움말")
    }

    @Test
    fun `rejects unknown schema versions html and unsafe URL protocols`() {
        assertThatThrownBy {
            validator.validate(CanonicalKnowledgeDocument(2, listOf(KnowledgeBlock.Divider)))
        }.isInstanceOf(InvalidKnowledgeDocumentException::class.java)

        assertThatThrownBy {
            validator.validate(CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Html("<script>alert(1)</script>"))))
        }.isInstanceOf(InvalidKnowledgeDocumentException::class.java)

        assertThatThrownBy {
            validator.validate(CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Link("클릭", "javascript:alert(1)"))))
        }.isInstanceOf(InvalidKnowledgeDocumentException::class.java)
    }

    @Test
    fun `selected staff group audience requires unique groups and only allows matching active group`() {
        val groupId = UUID.randomUUID()
        val audience = KnowledgeAudience.selectedStaffGroups(setOf(groupId))

        assertThat(DefaultKnowledgeAudienceEvaluator().allows(audience, KnowledgeReader.Staff(setOf(groupId)))).isTrue()
        assertThat(DefaultKnowledgeAudienceEvaluator().allows(audience, KnowledgeReader.Staff(emptySet()))).isFalse()

        assertThatThrownBy { KnowledgeAudience.selectedStaffGroups(emptySet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `public articles cannot reference attachments`() {
        assertThatThrownBy {
            validator.validate(
                CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Attachment(UUID.randomUUID()))),
                KnowledgeAudience.public(),
            )
        }.isInstanceOf(InvalidKnowledgeDocumentException::class.java)
    }
}
