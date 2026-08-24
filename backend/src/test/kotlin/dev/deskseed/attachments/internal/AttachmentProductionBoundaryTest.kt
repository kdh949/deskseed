package dev.deskseed.attachments.internal

import dev.deskseed.attachments.AttachmentObjectStore
import dev.deskseed.attachments.MalwareScanner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Path

@dev.deskseed.testsupport.category.ContractTest
class AttachmentProductionBoundaryTest {
    @TempDir
    lateinit var privateRoot: Path

    private fun contextRunner() = ApplicationContextRunner()
        .withUserConfiguration(
            AttachmentStorageConfiguration::class.java,
            LocalPrivateAttachmentStore::class.java,
            DeterministicMalwareScanner::class.java,
        )
        .withPropertyValues("deskseed.attachments.private-root=$privateRoot")

    @Test
    fun `production never wires local attachment storage or deterministic malware scanning`() {
        contextRunner()
            .withInitializer { context -> context.environment.setActiveProfiles("production") }
            .run { context ->
                assertThat(context).doesNotHaveBean(AttachmentObjectStore::class.java)
                assertThat(context).doesNotHaveBean(MalwareScanner::class.java)
            }
    }

    @Test
    fun `default profile retains local attachment adapters for development and tests`() {
        contextRunner().run { context ->
            assertThat(context).hasSingleBean(AttachmentObjectStore::class.java)
            assertThat(context).hasSingleBean(MalwareScanner::class.java)
        }
    }
}
