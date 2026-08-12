package dev.deskseed.integration

import org.springframework.stereotype.Component
import java.net.InetAddress

@Component
class IntegrationNetworkPolicy {
    fun validateCidrs(values: Collection<String>) {
        values.forEach(::parseCidr)
    }

    fun normalizeLiteral(value: String): String? = parseLiteral(value)?.hostAddress

    fun isAllowed(value: String, allowlist: Collection<String>?): Boolean {
        val address = parseLiteral(value) ?: return false
        if (allowlist == null) return true
        return allowlist.any { contains(parseCidr(it), address.address) }
    }

    private fun contains(cidr: Cidr, address: ByteArray): Boolean {
        if (cidr.address.size != address.size) return false
        val fullBytes = cidr.prefixLength / 8
        val remainingBits = cidr.prefixLength % 8
        for (index in 0 until fullBytes) if (cidr.address[index] != address[index]) return false
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (cidr.address[fullBytes].toInt() and mask) == (address[fullBytes].toInt() and mask)
    }

    private fun parseCidr(value: String): Cidr {
        val parts = value.trim().split('/')
        require(parts.size in 1..2) { "Invalid IP/CIDR entry" }
        val address = parseLiteral(parts[0]) ?: throw IllegalArgumentException("Invalid IP/CIDR entry")
        val maxPrefix = address.address.size * 8
        val prefix = if (parts.size == 2) parts[1].toIntOrNull() else maxPrefix
        require(prefix != null && prefix in 0..maxPrefix) { "Invalid IP/CIDR entry" }
        return Cidr(address.address, prefix)
    }

    private fun parseLiteral(value: String): InetAddress? {
        val candidate = value.trim()
        if (candidate.isEmpty() || candidate.any { !(it.isDigit() || it in "abcdefABCDEF:.") }) return null
        return runCatching { InetAddress.getByName(candidate) }.getOrNull()
    }

    private data class Cidr(val address: ByteArray, val prefixLength: Int)
}

