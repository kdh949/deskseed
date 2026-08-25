package dev.deskseed.testsupport.category

import org.junit.jupiter.api.Tag
import java.lang.annotation.Inherited

object TestCategories {
    const val FAST = "fast"
    const val CONTRACT = "contract"
    const val INTEGRATION = "integration"
    const val MIGRATION = "migration"
    const val SLOW = "slow"

    val primary: Set<String> = linkedSetOf(FAST, CONTRACT, INTEGRATION, MIGRATION, SLOW)
}

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
@Tag(TestCategories.FAST)
annotation class FastTest

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
@Tag(TestCategories.CONTRACT)
annotation class ContractTest

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
@Tag(TestCategories.INTEGRATION)
annotation class IntegrationTest

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
@Tag(TestCategories.MIGRATION)
annotation class MigrationTest

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
@Tag(TestCategories.SLOW)
annotation class SlowTest

internal object TestCategoryRules {
    fun problemFor(displayName: String, tags: Set<String>): String? {
        val primary = tags.intersect(TestCategories.primary)
        return when (primary.size) {
            1 -> null
            0 -> "$displayName has no primary test category"
            else -> "$displayName has multiple primary test categories: ${primary.sorted().joinToString()}"
        }
    }

    fun selectedTestIds(
        testTags: Map<String, Set<String>>,
        selectedCategories: Set<String>,
    ): Set<String> = testTags
        .filterValues { tags -> tags.any(selectedCategories::contains) }
        .keys
}
