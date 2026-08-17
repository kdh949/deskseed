package dev.deskseed.attachments.internal

import dev.deskseed.attachments.AttachmentObjectStore
import dev.deskseed.attachments.AttachmentTooLargeException
import dev.deskseed.attachments.AttachmentUnavailableException
import dev.deskseed.attachments.MalwareScanResult
import dev.deskseed.attachments.MalwareScanner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@ConfigurationProperties("deskseed.attachments")
internal data class AttachmentStorageProperties(
    var privateRoot: Path = Path.of("/tmp/deskseed-private-attachments"),
    var maxUploadBytes: Long = 20L * 1024 * 1024,
    var unlinkedTtlHours: Long = 24,
    var linkedTtlDays: Long = 30,
    var cleanupBatchSize: Int = 100,
) {
    fun validate() {
        require(maxUploadBytes in 1..(100L * 1024 * 1024)) { "Attachment upload limit must be between 1 byte and 100 MiB" }
        require(unlinkedTtlHours in 1..(24L * 30)) { "Attachment unlinked TTL must be bounded" }
        require(linkedTtlDays in 1..3650) { "Attachment linked TTL must be bounded" }
        require(cleanupBatchSize in 1..1_000) { "Attachment cleanup batch size must be bounded" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttachmentStorageProperties::class)
internal class AttachmentStorageConfiguration

/** Local, private CI/development adapter. Production may replace this port with a private S3-compatible adapter. */
@Component
@Profile("!production")
internal class LocalPrivateAttachmentStore(
    private val properties: AttachmentStorageProperties,
) : AttachmentObjectStore {
    init {
        properties.validate()
        try {
            Files.createDirectories(properties.privateRoot)
        } catch (exception: Exception) {
            throw AttachmentUnavailableException(exception)
        }
    }

    override fun putQuarantine(key: String, content: InputStream): Long {
        val target = resolve(key)
        val temporary = Files.createTempFile(properties.privateRoot, ".upload-", ".tmp")
        return try {
            val bytes = content.use { source -> Files.newOutputStream(temporary).use(source::transferTo) }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            bytes
        } catch (exception: AttachmentTooLargeException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw exception
        } catch (exception: Exception) {
            runCatching { Files.deleteIfExists(temporary) }
            throw AttachmentUnavailableException(exception)
        }
    }

    override fun openPrivate(key: String): InputStream = try {
        Files.newInputStream(resolve(key))
    } catch (exception: Exception) {
        throw AttachmentUnavailableException(exception)
    }

    override fun delete(key: String) {
        try {
            Files.deleteIfExists(resolve(key))
        } catch (exception: Exception) {
            throw AttachmentUnavailableException(exception)
        }
    }

    private fun resolve(key: String): Path {
        require(key.matches(Regex("attachments/quarantine/[0-9a-f-]{36}"))) { "Invalid attachment object key" }
        val target = properties.privateRoot.resolve(key.replace('/', '_')).normalize()
        require(target.parent == properties.privateRoot.normalize()) { "Attachment object key escapes private root" }
        return target
    }
}

/** Deterministic scanner used only for local/CI wiring; scanner errors are represented by thrown exceptions. */
@Component
@Profile("!production")
internal class DeterministicMalwareScanner : MalwareScanner {
    override fun scan(content: InputStream, fileName: String, contentType: String): MalwareScanResult = try {
        var tail = ""
        val buffer = ByteArray(8 * 1024)
        content.use { source ->
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                val text = tail + String(buffer, 0, read, StandardCharsets.ISO_8859_1)
                if (text.contains("EICAR-STANDARD-ANTIVIRUS-TEST-FILE", ignoreCase = true) ||
                    text.contains("MALWARE", ignoreCase = true)
                ) {
                    return MalwareScanResult.INFECTED
                }
                tail = text.takeLast(64)
            }
        }
        MalwareScanResult.CLEAN
    } catch (exception: Exception) {
        throw AttachmentUnavailableException(exception)
    }
}
