package dev.deskseed.portal.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequestAccessTokenCodecTest {
    private val codec = RequestAccessTokenCodec()

    @Test
    fun `issued token is opaque and only its stable hash needs persistence`() {
        val issued = codec.issue()

        assertThat(issued.raw).hasSizeGreaterThanOrEqualTo(40)
        assertThat(issued.hash).hasSize(64)
        assertThat(issued.hash).doesNotContain(issued.raw)
        assertThat(codec.hash(issued.raw)).isEqualTo(issued.hash)
    }

    @Test
    fun `separate issues produce separate tokens`() {
        assertThat(codec.issue().raw).isNotEqualTo(codec.issue().raw)
    }
}
