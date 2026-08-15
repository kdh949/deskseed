package dev.deskseed.audit

import java.io.InputStream
import java.io.OutputStream

/**
 * Provider-neutral private export artifact boundary. Implementations must never turn keys into public URLs.
 * The writer callback permits bounded, streaming generation without buffering the export in application memory.
 */
interface AuditExportArtifactStore {
    fun writePrivate(key: String, write: (OutputStream) -> Unit): Long

    fun openPrivate(key: String): InputStream

    fun delete(key: String)
}

class AuditExportArtifactStoreUnavailableException(cause: Throwable? = null) : RuntimeException(cause)
