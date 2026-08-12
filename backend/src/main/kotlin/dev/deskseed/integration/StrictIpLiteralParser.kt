package dev.deskseed.integration

import java.net.InetAddress

/** Parses textual IP literals without ever invoking name service resolution. */
internal object StrictIpLiteralParser {
    fun parse(value: String): InetAddress? {
        val candidate = value.trim()
        if (candidate.isEmpty() || candidate != value) return null
        val bytes = when {
            ':' in candidate -> parseIpv6(candidate)
            '.' in candidate -> parseIpv4(candidate)
            else -> null
        } ?: return null
        return InetAddress.getByAddress(bytes)
    }

    private fun parseIpv4(candidate: String): ByteArray? {
        val parts = candidate.split('.')
        if (parts.size != IPV4_PARTS) return null
        return ByteArray(IPV4_PARTS) { index ->
            val part = parts[index]
            if (
                part.isEmpty() ||
                part.any { !it.isDigit() } ||
                part.length > 3 ||
                (part.length > 1 && part.startsWith('0'))
            ) {
                return null
            }
            val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            octet.toByte()
        }
    }

    private fun parseIpv6(candidate: String): ByteArray? {
        if (candidate.any { !(it.isDigit() || it in "abcdefABCDEF:") }) return null
        val compression = candidate.indexOf("::")
        if (compression != candidate.lastIndexOf("::")) return null

        val groups = if (compression >= 0) {
            val left = parseIpv6Side(candidate.substring(0, compression)) ?: return null
            val right = parseIpv6Side(candidate.substring(compression + 2)) ?: return null
            if (left.size + right.size >= IPV6_GROUPS) return null
            left + List(IPV6_GROUPS - left.size - right.size) { 0 } + right
        } else {
            parseIpv6Side(candidate)?.takeIf { it.size == IPV6_GROUPS } ?: return null
        }

        return ByteArray(IPV6_BYTES).also { bytes ->
            groups.forEachIndexed { index, group ->
                bytes[index * 2] = (group ushr 8).toByte()
                bytes[index * 2 + 1] = group.toByte()
            }
        }
    }

    private fun parseIpv6Side(value: String): List<Int>? {
        if (value.isEmpty()) return emptyList()
        val groups = value.split(':')
        if (groups.any { it.isEmpty() || it.length > 4 }) return null
        return groups.map { it.toIntOrNull(16) ?: return null }
    }

    private const val IPV4_PARTS = 4
    private const val IPV6_GROUPS = 8
    private const val IPV6_BYTES = 16
}
