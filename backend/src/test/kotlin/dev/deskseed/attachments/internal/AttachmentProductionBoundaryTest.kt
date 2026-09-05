package dev.deskseed.attachments.internal

import dev.deskseed.attachments.AttachmentObjectStore
import dev.deskseed.attachments.AttachmentScanStatus
import dev.deskseed.attachments.AttachmentUploadCommand
import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.attachments.MalwareScanResult
import dev.deskseed.attachments.MalwareScanSource
import dev.deskseed.attachments.MalwareScanner
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.mockito.Mockito.mock
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@dev.deskseed.testsupport.category.ContractTest
class AttachmentProductionBoundaryTest {
    @TempDir
    lateinit var privateRoot: Path

    private fun contextRunner() = ApplicationContextRunner()
        .withUserConfiguration(
            AttachmentStorageConfiguration::class.java,
            LocalPrivateAttachmentStore::class.java,
            DeterministicMalwareScanner::class.java,
            TrustedUpstreamWafMalwareScanner::class.java,
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
    fun `production wires trusted upstream WAF scanner only with explicit acknowledgement`() {
        contextRunner()
            .withInitializer { context -> context.environment.setActiveProfiles("production") }
            .withPropertyValues(
                "deskseed.attachments.scan-mode=UPSTREAM_WAF",
                "deskseed.attachments.upstream-waf-acknowledged=true",
            )
            .run { context ->
                assertThat(context).hasSingleBean(MalwareScanner::class.java)
                assertThat(context.getBean(MalwareScanner::class.java))
                    .isInstanceOf(TrustedUpstreamWafMalwareScanner::class.java)
            }
    }

    @Test
    fun `production refuses trusted upstream WAF scanner without acknowledgement`() {
        contextRunner()
            .withInitializer { context -> context.environment.setActiveProfiles("production") }
            .withPropertyValues("deskseed.attachments.scan-mode=UPSTREAM_WAF")
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `plaintext S3 allows only the exact Compose internal Versity endpoint without acknowledgement`() {
        assertThatCode {
            S3AttachmentStorageProperties(
                endpoint = URI.create("http://versitygw:7070"),
                accessKey = "deskseed-test-access",
                secretKey = "deskseed-test-secret-key",
            ).validate()
        }.doesNotThrowAnyException()

        listOf(
            "http://external-storage.example.test:7070",
            "http://versitygw:7071",
        ).forEach { endpoint ->
            assertThatThrownBy {
                S3AttachmentStorageProperties(
                    endpoint = URI.create(endpoint),
                    accessKey = "deskseed-test-access",
                    secretKey = "deskseed-test-secret-key",
                ).validate()
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `trusted upstream WAF scanner does not read or rescan stored bytes`() {
        val properties = AttachmentStorageProperties(
            scanMode = MalwareScanSource.UPSTREAM_WAF,
            upstreamWafAcknowledged = true,
        )
        val scanner = TrustedUpstreamWafMalwareScanner(properties)
        val unreadable = object : InputStream() {
            override fun read(): Int = error("trusted upstream scanner must not read content")
        }

        assertThat(scanner.requiresContent).isFalse()
        assertThat(scanner.source).isEqualTo(MalwareScanSource.UPSTREAM_WAF)
        assertThat(scanner.scan(unreadable, "sample.pdf", "application/pdf"))
            .isEqualTo(MalwareScanResult.CLEAN)
    }

    @Test
    fun `upload skips object reopen and records upstream WAF scan source`() {
        val now = Instant.parse("2026-09-02T00:00:00Z")
        val actorId = UUID.randomUUID()
        val transitions = mock(AttachmentStateTransitions::class.java)
        val objectStore = RecordingUploadObjectStore()
        val scanner = TrustedUpstreamWafMalwareScanner(
            AttachmentStorageProperties(
                scanMode = MalwareScanSource.UPSTREAM_WAF,
                upstreamWafAcknowledged = true,
            ),
        )
        val service = AttachmentApplicationService(
            metadata = mock(AttachmentMetadataStore::class.java),
            transitions = transitions,
            objectStore = objectStore,
            malwareScanner = scanner,
            cleanupTransactions = mock(AttachmentCleanupTransactions::class.java),
            properties = AttachmentStorageProperties(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val command = AttachmentUploadCommand(
            actor = ActorRef(ActorType.CUSTOMER, actorId),
            actorDisplayName = "attachment customer",
            source = RequestSource.CUSTOMER_PORTAL,
            context = CommandContext(
                source = RequestSource.CUSTOMER_PORTAL,
                requestId = "request-upstream-waf",
                correlationId = "correlation-upstream-waf",
                commandId = "command-upstream-waf",
            ),
            boundTicketId = UUID.randomUUID(),
            allowedVisibility = AttachmentVisibility.PUBLIC,
            fileName = "receipt.pdf",
            declaredContentType = "application/pdf",
            content = ByteArrayInputStream("%PDF-1.7\nunchanged bytes".toByteArray()),
        )

        val result = service.upload(command)

        assertThat(result.scanStatus).isEqualTo(AttachmentScanStatus.CLEAN)
        assertThat(objectStore.openCount).isZero()
        val cleanTransition = org.mockito.Mockito.mockingDetails(transitions).invocations
            .single { it.method.name == "markClean" }
        assertThat(cleanTransition.arguments[3]).isEqualTo(MalwareScanSource.UPSTREAM_WAF)
    }

    @Test
    fun `production profile requires explicit upstream scan and private S3 settings`() {
        val production = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-production.yml"))
        }.getObject()

        assertThat(production?.getProperty("deskseed.attachments.scan-mode"))
            .isEqualTo("\${DESKSEED_ATTACHMENT_SCAN_MODE}")
        assertThat(production?.getProperty("deskseed.attachments.upstream-waf-acknowledged"))
            .isEqualTo("\${DESKSEED_ATTACHMENT_UPSTREAM_WAF_ACKNOWLEDGED:false}")
        assertThat(production?.getProperty("deskseed.attachments.s3.endpoint"))
            .isEqualTo("\${DESKSEED_ATTACHMENT_S3_ENDPOINT}")
        assertThat(production?.getProperty("deskseed.attachments.s3.secret-key"))
            .isEqualTo("\${DESKSEED_ATTACHMENT_S3_SECRET_KEY}")
        assertThat(production?.getProperty("deskseed.attachments.s3.plaintext-internal-network-acknowledged"))
            .isNull()
        assertThat(production?.getProperty("spring.servlet.multipart.max-file-size"))
            .isEqualTo("\${DESKSEED_ATTACHMENT_MAX_UPLOAD_BYTES:20MB}")
        assertThat(production?.getProperty("spring.servlet.multipart.max-request-size"))
            .isEqualTo("\${DESKSEED_ATTACHMENT_MAX_REQUEST_BYTES:105MB}")
    }

    @Test
    fun `default profile retains local attachment adapters for development and tests`() {
        contextRunner().run { context ->
            assertThat(context).hasSingleBean(AttachmentObjectStore::class.java)
            assertThat(context).hasSingleBean(MalwareScanner::class.java)
        }
    }

    private class RecordingUploadObjectStore : AttachmentObjectStore {
        var openCount: Int = 0
            private set

        override fun putQuarantine(key: String, content: InputStream): Long = content.readAllBytes().size.toLong()

        override fun openPrivate(key: String): InputStream {
            openCount += 1
            error("upstream WAF mode must not reopen stored bytes")
        }

        override fun delete(key: String) = Unit
    }
}
