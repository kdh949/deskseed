package dev.deskseed.knowledge.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class KnowledgeCursorCodecTest {
    @Test
    fun `admin title scope is keyed and exact with rotation compatible continuation`() {
        val keys = mapOf("old" to "old-synthetic-signing-key-at-least-32-bytes", "new" to "new-synthetic-signing-key-at-least-32-bytes")
        val old = KnowledgeCursorCodec(KnowledgeCursorProperties("old", keys))
        val current = KnowledgeCursorCodec(KnowledgeCursorProperties("new", keys))
        val point = KnowledgeCursor(Instant.parse("2026-10-03T00:00:00Z"), UUID.randomUUID())
        val query = "private-title-fixture"
        val cursor = old.encode(old.adminTitleSearchScope(query, "all", null), point)
        assertThat(current.decode(current.adminTitleSearchScope(query, "all", cursor), cursor)).isEqualTo(point)
        val next = current.encode(current.adminTitleSearchScope(query, "all", null), point)
        assertThat(next).startsWith("new.")
        assertThat(current.decode(current.adminTitleSearchScope(query, "all", next), next)).isEqualTo(point)
        assertThat(String(Base64.getUrlDecoder().decode(cursor.split('.')[1]))).doesNotContain(query)
        assertThat(old.adminTitleSearchScope(query, "all", null)).isNotEqualTo(current.adminTitleSearchScope(query, "all", null))
        listOf("private-title-fixturE", "other", "$query ").forEach { changed ->
            assertThatThrownBy { current.decode(current.adminTitleSearchScope(changed, "all", cursor), cursor) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { current.decode(current.adminTitleSearchScope(query, "STAFF", cursor), cursor) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { KnowledgeCursorCodec(KnowledgeCursorProperties("new", mapOf("new" to keys.getValue("new")))).adminTitleSearchScope(query, "all", cursor) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
