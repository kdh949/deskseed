package dev.deskseed.ticketing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import dev.deskseed.testsupport.category.FastTest

@FastTest
class CanonicalCommentContentCodecTest {
    private val mapper = ObjectMapper()
    private val codec = CanonicalCommentContentCodec(mapper)

    @Test
    fun `canonical rich document derives plain text and preserves allowlisted marks`() {
        val result = codec.decode(
            body = null,
            content = mapper.readTree(
                """
                {
                  "format":"RICH_TEXT_V1",
                  "document":{"type":"doc","content":[
                    {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"안내"}]},
                    {"type":"paragraph","content":[{"type":"text","text":"문서를 확인해 주세요","marks":[{"type":"bold"}]}]}
                  ]}
                }
                """.trimIndent(),
            ),
            attachmentIds = emptySet(),
        )

        assertEquals(CommentContentFormat.RICH_TEXT_V1, result.format)
        assertEquals("안내\n문서를 확인해 주세요", result.body)
        assertEquals("doc", result.document?.get("type")?.asString())
    }

    @Test
    fun `arbitrary html remote image and javascript link are rejected`() {
        listOf(
            """{"type":"doc","content":[{"type":"html","text":"<img src=x onerror=alert(1)>"}]}""",
            """{"type":"doc","content":[{"type":"attachmentImage","attrs":{"src":"https://evil.test/x.png","alt":"x"}}]}""",
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"x","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]}]}""",
        ).forEach { document ->
            assertThrows(InvalidCommentContentException::class.java) {
                codec.decode(
                    body = null,
                    content = mapper.readTree("""{"format":"RICH_TEXT_V1","document":$document}"""),
                    attachmentIds = emptySet(),
                )
            }
        }
    }

    @Test
    fun `attachment image must reference submitted attachment and requires alt text`() {
        val attachmentId = UUID.randomUUID()
        val content = mapper.readTree(
            """
            {"format":"RICH_TEXT_V1","document":{"type":"doc","content":[
              {"type":"attachmentImage","attrs":{"attachmentId":"$attachmentId","alt":"오류 화면"}}
            ]}}
            """.trimIndent(),
        )

        assertThrows(InvalidCommentContentException::class.java) {
            codec.decode(null, content, emptySet())
        }
        assertEquals("오류 화면", codec.decode(null, content, setOf(attachmentId)).body)
    }

    @Test
    fun `body and content are mutually exclusive`() {
        assertThrows(InvalidCommentContentException::class.java) {
            codec.decode(
                body = "plain",
                content = mapper.readTree("""{"format":"PLAIN_TEXT","text":"other"}"""),
                attachmentIds = emptySet(),
            )
        }
    }
}
