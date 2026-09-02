package dev.deskseed.attachments

import java.io.InputStream

/** Provider-neutral private object-store boundary. Keys never cross an HTTP boundary. */
interface AttachmentObjectStore {
    fun putQuarantine(key: String, content: InputStream): Long

    fun openPrivate(key: String): InputStream

    fun delete(key: String)
}

enum class MalwareScanResult {
    CLEAN,
    INFECTED,
}

enum class MalwareScanSource {
    APPLICATION,
    UPSTREAM_WAF,
}

/** Scanner adapters must fail by throwing; callers never convert a scanner failure to CLEAN. */
interface MalwareScanner {
    val source: MalwareScanSource
        get() = MalwareScanSource.APPLICATION

    val requiresContent: Boolean
        get() = true

    fun scan(content: InputStream, fileName: String, contentType: String): MalwareScanResult
}
