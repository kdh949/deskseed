package dev.deskseed.architecture

import dev.deskseed.DeskseedApplication
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ArchitectureTest {
    @Test
    fun `application modules have no cycles or internal package violations`() {
        ApplicationModules.of(DeskseedApplication::class.java).verify()
    }
}
