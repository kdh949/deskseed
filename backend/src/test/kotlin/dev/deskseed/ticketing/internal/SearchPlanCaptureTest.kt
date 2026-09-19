package dev.deskseed.ticketing.internal

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

@dev.deskseed.testsupport.category.FastTest
class SearchPlanCaptureTest {
    @Test
    fun `personal staging explain analyze is rejected before files or database are touched`() {
        assertThatThrownBy {
            SearchPlanCapture.main(
                arrayOf(
                    "--environment", "personal-staging",
                    "--corpus", "/does/not/exist.json",
                    "--case-id", "phrase:0",
                    "--family", "page",
                    "--deployment-sha", "0123456789abcdef0123456789abcdef01234567",
                    "--output", "/does/not/exist",
                    "--actor-id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("load environment")
    }

    @Test
    fun `short deployment revision is rejected before database access`() {
        assertThatThrownBy {
            SearchPlanCapture.main(
                arrayOf(
                    "--environment", "load",
                    "--corpus", "/does/not/exist.json",
                    "--case-id", "phrase:0",
                    "--family", "count",
                    "--deployment-sha", "deadbeef",
                    "--output", "/does/not/exist",
                    "--actor-id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("40-character")
    }
}
