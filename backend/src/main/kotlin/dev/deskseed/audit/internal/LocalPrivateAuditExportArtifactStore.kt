package dev.deskseed.audit.internal

import dev.deskseed.audit.AuditExportArtifactStore
import dev.deskseed.audit.AuditExportArtifactStoreUnavailableException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@ConfigurationProperties("deskseed.audit-export")
internal data class AuditExportStorageProperties(
    var privateRoot: Path = Path.of("/tmp/deskseed-private-audit-exports"),
    var ttlHours: Long = 24,
    var workerLeaseSeconds: Long = 300,
    var workerBatchSize: Int = 4,
) {
    fun validate() {
        require(ttlHours in 1..(24L * 31)) { "Audit export TTL must be between one hour and 31 days" }
        require(workerLeaseSeconds in 30..3600) { "Audit export worker lease must be bounded" }
        require(workerBatchSize in 1..100) { "Audit export worker batch size must be bounded" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditExportStorageProperties::class)
internal class AuditExportStorageConfiguration

/** Private filesystem adapter for development and CI. Production replaces this port with private object storage. */
@Component
internal class LocalPrivateAuditExportArtifactStore(
    private val properties: AuditExportStorageProperties,
) : AuditExportArtifactStore {
    init {
        properties.validate()
        try {
            Files.createDirectories(properties.privateRoot)
        } catch (exception: Exception) {
            throw AuditExportArtifactStoreUnavailableException(exception)
        }
    }

    override fun writePrivate(key: String, write: (OutputStream) -> Unit): Long {
        val target = resolve(key)
        val temporary = Files.createTempFile(properties.privateRoot, ".audit-export-", ".tmp")
        return try {
            Files.newOutputStream(temporary).use(write)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.size(target)
        } catch (exception: Exception) {
            runCatching { Files.deleteIfExists(temporary) }
            throw AuditExportArtifactStoreUnavailableException(exception)
        }
    }

    override fun openPrivate(key: String): InputStream = try {
        Files.newInputStream(resolve(key))
    } catch (exception: Exception) {
        throw AuditExportArtifactStoreUnavailableException(exception)
    }

    override fun delete(key: String) {
        try {
            Files.deleteIfExists(resolve(key))
        } catch (exception: Exception) {
            throw AuditExportArtifactStoreUnavailableException(exception)
        }
    }

    private fun resolve(key: String): Path {
        require(key.matches(Regex("audit-exports/[0-9a-f-]{36}/attempt-[1-9][0-9]*\\.(csv|jsonl)"))) {
            "Invalid audit export artifact key"
        }
        val target = properties.privateRoot.resolve(key.replace('/', '_')).normalize()
        require(target.parent == properties.privateRoot.normalize()) { "Audit export key escapes private root" }
        return target
    }
}
