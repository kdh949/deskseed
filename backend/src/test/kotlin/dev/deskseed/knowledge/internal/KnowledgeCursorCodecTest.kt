package dev.deskseed.knowledge.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class KnowledgeCursorCodecTest {
    private val codec = KnowledgeCursorCodec(KnowledgeCursorProperties())
    private val cursor = KnowledgeRankedCursor(0.6079271f, Instant.parse("2026-09-01T00:00:00.123456Z"), UUID.randomUUID())

    @Test
    fun `rank retains float4 precision while legacy listing cursors remain independently valid`() {
        val ranked = codec.encodeRanked("help-search:anonymous:query", cursor)
        assertThat(codec.decodeRanked("help-search:anonymous:query", ranked)).isEqualTo(cursor)
        val legacy = KnowledgeCursor(cursor.createdAt, cursor.articleId)
        val listing = codec.encode("help-section:login:anonymous", legacy)
        assertThat(codec.decode("help-section:login:anonymous", listing)).isEqualTo(legacy)
        assertThatThrownBy { codec.decodeRanked("help-section:login:anonymous", listing) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { codec.decode("help-search:anonymous:query", ranked) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { codec.decodeRanked("help-search:customer:query", ranked) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ranked cursor supports key rotation and rejects invalid ranks and tampering`() {
        val rotated = KnowledgeCursorCodec(KnowledgeCursorProperties("next", KnowledgeCursorProperties().signingKeys + ("next" to "new-cursor-signing-key-with-at-least-thirty-two-characters")))
        val encoded = codec.encodeRanked("query", cursor)
        assertThat(rotated.decodeRanked("query", encoded)).isEqualTo(cursor)
        val parts = encoded.split('.')
        val signature = (if (parts[2].first() == 'A') "B" else "A") + parts[2].drop(1)
        assertThatThrownBy { codec.decodeRanked("query", "${parts[0]}.${parts[1]}.$signature") }.isInstanceOf(IllegalArgumentException::class.java)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach { rank ->
            assertThatThrownBy { codec.encodeRanked("query", cursor.copy(rank = rank)) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
