package dev.deskseed.architecture

import dev.deskseed.DeskseedApplication
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

@dev.deskseed.testsupport.category.ContractTest
class ArchitectureTest {
    @Test
    fun `application modules have no cycles or internal package violations`() {
        ApplicationModules.of(DeskseedApplication::class.java).verify()
    }
}
