package dev.deskseed.testsupport.category

import org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

object TestCategoryVerifier {
    @JvmStatic
    fun main(args: Array<String>) {
        val launcher = LauncherFactory.create()
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectPackage("dev.deskseed"))
            .build()
        val plan = launcher.discover(request)
        val tests = plan.roots
            .flatMap { plan.getDescendants(it) }
            .filter { it.isTest }
            .distinctBy { it.uniqueId }

        check(tests.isNotEmpty()) { "JUnit Platform discovered no Deskseed tests" }

        val problems = tests.mapNotNull { test ->
            TestCategoryRules.problemFor(
                test.uniqueId,
                test.tags.map { it.name }.toSet(),
            )
        }
        check(problems.isEmpty()) {
            "Invalid primary test categories (${problems.size}):\n${problems.joinToString("\n")}"
        }

        val counts = TestCategories.primary.associateWith { category ->
            tests.count { test -> test.tags.any { it.name == category } }
        }
        val emptyCategories = counts.filterValues { it == 0 }.keys
        check(emptyCategories.isEmpty()) {
            "Primary test categories discovered no tests: ${emptyCategories.sorted().joinToString()}"
        }
        check(counts.values.sum() == tests.size) {
            "Category sum ${counts.values.sum()} does not match ${tests.size} unique discovered tests"
        }

        println("Deskseed test categories: total=${tests.size}, ${counts.entries.joinToString { "${it.key}=${it.value}" }}")
    }
}
