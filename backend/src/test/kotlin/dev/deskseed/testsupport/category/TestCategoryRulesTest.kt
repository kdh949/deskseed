package dev.deskseed.testsupport.category

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@FastTest
class TestCategoryRulesTest {
    @Test
    fun `exactly one primary category is accepted`() {
        assertThat(TestCategoryRules.problemFor("example", setOf("fast"))).isNull()

        val discovered = mapOf(
            "fast-only" to setOf("fast"),
            "contract-only" to setOf("contract"),
            "integration-only" to setOf("integration"),
        )
        val separateTaskUnion = TestCategoryRules.selectedTestIds(discovered, setOf("fast")) +
            TestCategoryRules.selectedTestIds(discovered, setOf("contract"))
        assertThat(TestCategoryRules.selectedTestIds(discovered, setOf("fast", "contract")))
            .isEqualTo(separateTaskUnion)
    }

    @Test
    fun `missing and duplicate primary categories fail closed`() {
        assertThat(TestCategoryRules.problemFor("missing", emptySet()))
            .isEqualTo("missing has no primary test category")
        assertThat(TestCategoryRules.problemFor("duplicate", setOf("fast", "integration")))
            .isEqualTo("duplicate has multiple primary test categories: fast, integration")
    }
}
