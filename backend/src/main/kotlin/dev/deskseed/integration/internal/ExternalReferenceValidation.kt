package dev.deskseed.integration.internal

import dev.deskseed.integration.ExternalReferenceValidationException
import dev.deskseed.integration.StrictIpLiteralParser
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.IDN
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale

@Component
internal class ExternalReferenceValidation(
    private val objectMapper: ObjectMapper,
) {
    fun normalizeSystemKey(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        invalidUnless(SYSTEM_KEY.matches(normalized), "EXTERNAL_SYSTEM_KEY_INVALID")
        return normalized
    }

    fun normalizeDisplayName(value: String, code: String = "EXTERNAL_SYSTEM_DISPLAY_NAME_INVALID"): String =
        boundedText(value, 100, code)

    fun normalizeHostnames(values: Set<String>): List<String> {
        invalidUnless(values.isNotEmpty() && values.size <= MAX_HOSTS, "EXTERNAL_SYSTEM_HOSTS_INVALID")
        return values.map(::normalizeHostname).distinct().sorted().also {
            invalidUnless(it.size == values.size, "EXTERNAL_SYSTEM_HOSTS_DUPLICATE")
            invalidUnless(objectMapper.writeValueAsBytes(it).size <= MAX_HOSTS_BYTES, "EXTERNAL_SYSTEM_HOSTS_TOO_LARGE")
        }
    }

    fun validateLink(value: String, allowedHostnames: Collection<String>): ValidatedExternalLink {
        invalidUnless(value == value.trim() && value.length in 1..MAX_LINK_LENGTH, "EXTERNAL_LINK_INVALID")
        invalidUnless(value.none(Char::isISOControl), "EXTERNAL_LINK_CONTROL_CHARACTER")
        invalidUnless(!ENCODED_CONTROL.containsMatchIn(value), "EXTERNAL_LINK_CONTROL_CHARACTER")
        val uri = runCatching { URI(value) }
            .getOrElse { throw ExternalReferenceValidationException("EXTERNAL_LINK_INVALID") }
        invalidUnless(uri.scheme.equals("https", ignoreCase = true), "EXTERNAL_LINK_HTTPS_REQUIRED")
        invalidUnless(uri.rawUserInfo == null, "EXTERNAL_LINK_USERINFO_FORBIDDEN")
        invalidUnless(uri.port == -1 || uri.port == 443, "EXTERNAL_LINK_PORT_FORBIDDEN")
        invalidUnless(uri.rawFragment == null, "EXTERNAL_LINK_FRAGMENT_FORBIDDEN")
        val host = normalizeHostname(uri.host ?: throw ExternalReferenceValidationException("EXTERNAL_LINK_HOST_INVALID"))
        invalidUnless(host in allowedHostnames, "EXTERNAL_LINK_HOST_NOT_ALLOWED")
        validateQuery(uri.rawQuery)
        return ValidatedExternalLink(value, host)
    }

    fun normalizeExternalId(value: String): String = boundedText(value, 200, "EXTERNAL_ID_INVALID")

    fun normalizeLabel(value: String): String = boundedText(value, 200, "EXTERNAL_LABEL_INVALID")

    fun normalizeActorDisplay(value: String): String = boundedText(value, 100, "EXTERNAL_ACTOR_DISPLAY_INVALID")

    fun normalizeMetadata(values: Map<String, Any>): Map<String, Any> {
        invalidUnless(values.size <= MAX_METADATA_PROPERTIES, "EXTERNAL_METADATA_TOO_MANY_PROPERTIES")
        val normalized = values.toSortedMap().mapValues { (key, value) ->
            invalidUnless(key in ALLOWED_METADATA_KEYS, "EXTERNAL_METADATA_KEY_FORBIDDEN")
            when (value) {
                is String -> boundedText(value, MAX_METADATA_TEXT, "EXTERNAL_METADATA_VALUE_INVALID")
                is Boolean -> value
                is Byte, is Short, is Int, is Long -> value
                is BigDecimal -> value.stripTrailingZeros()
                is Float -> finiteDecimal(value.toDouble())
                is Double -> finiteDecimal(value)
                else -> throw ExternalReferenceValidationException("EXTERNAL_METADATA_VALUE_INVALID")
            }
        }
        invalidUnless(
            objectMapper.writeValueAsBytes(normalized).size <= MAX_METADATA_BYTES,
            "EXTERNAL_METADATA_TOO_LARGE",
        )
        return normalized
    }

    fun validateObservedAt(value: Instant, now: Instant): Instant {
        invalidUnless(
            !value.isAfter(now.plus(MAX_FUTURE_SKEW)) && !value.isBefore(now.minus(MAX_SNAPSHOT_AGE)),
            "EXTERNAL_METADATA_OBSERVED_AT_INVALID",
        )
        return value
    }

    private fun normalizeHostname(value: String): String {
        invalidUnless(value == value.trim() && value.length in 1..253, "EXTERNAL_SYSTEM_HOST_INVALID")
        invalidUnless(!value.contains('*') && !value.endsWith('.'), "EXTERNAL_SYSTEM_HOST_INVALID")
        invalidUnless(value.none(Char::isISOControl), "EXTERNAL_SYSTEM_HOST_INVALID")
        val normalized = runCatching { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES) }
            .getOrElse { throw ExternalReferenceValidationException("EXTERNAL_SYSTEM_HOST_INVALID") }
            .lowercase(Locale.ROOT)
        invalidUnless(HOSTNAME.matches(normalized), "EXTERNAL_SYSTEM_HOST_INVALID")
        invalidUnless(normalized.contains('.'), "EXTERNAL_SYSTEM_HOST_PRIVATE")
        invalidUnless(!looksLikeIpLiteral(normalized), "EXTERNAL_SYSTEM_HOST_PRIVATE")
        invalidUnless(
            PRIVATE_HOST_SUFFIXES.none { normalized == it || normalized.endsWith(".$it") },
            "EXTERNAL_SYSTEM_HOST_PRIVATE",
        )
        return normalized
    }

    private fun validateQuery(rawQuery: String?) {
        rawQuery ?: return
        rawQuery.split('&').forEach { part ->
            val rawKey = part.substringBefore('=')
            val key = percentDecode(rawKey).lowercase(Locale.ROOT).replace('-', '_')
            invalidUnless(
                CREDENTIAL_QUERY_TOKENS.none { token ->
                    key == token || key.startsWith("${token}_") || key.endsWith("_${token}")
                },
                "EXTERNAL_LINK_CREDENTIAL_QUERY_FORBIDDEN",
            )
        }
    }

    private fun percentDecode(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                    ?: throw ExternalReferenceValidationException("EXTERNAL_LINK_INVALID_QUERY")
                result.append(decoded.toChar())
                index += 3
            } else {
                result.append(value[index])
                index++
            }
        }
        return result.toString()
    }

    private fun boundedText(value: String, max: Int, code: String): String {
        val normalized = value.trim()
        invalidUnless(normalized.isNotEmpty() && normalized.length <= max, code)
        invalidUnless(normalized.none(Char::isISOControl), code)
        return normalized
    }

    private fun finiteDecimal(value: Double): BigDecimal {
        invalidUnless(value.isFinite(), "EXTERNAL_METADATA_VALUE_INVALID")
        return BigDecimal.valueOf(value).stripTrailingZeros()
    }

    private fun looksLikeIpLiteral(value: String): Boolean =
        value.contains(':') ||
            StrictIpLiteralParser.parse(value) != null ||
            value.split('.').all { label -> label.isNotEmpty() && label.all(Char::isDigit) }

    private fun invalidUnless(condition: Boolean, code: String) {
        if (!condition) throw ExternalReferenceValidationException(code)
    }

    internal data class ValidatedExternalLink(val value: String, val hostname: String)

    companion object {
        internal val ALLOWED_METADATA_KEYS = setOf(
            "status",
            "storeName",
            "amountDisplay",
            "currency",
            "occurredAt",
            "ownerLabel",
            "channel",
        )
        private val SYSTEM_KEY = Regex("^[a-z]([a-z0-9-]{0,62}[a-z0-9])?$")
        private val HOSTNAME = Regex(
            "^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$",
        )
        private val ENCODED_CONTROL = Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE)
        private val PRIVATE_HOST_SUFFIXES = setOf("localhost", "local", "internal", "home", "lan")
        private val CREDENTIAL_QUERY_TOKENS = setOf(
            "token",
            "api_key",
            "apikey",
            "key",
            "secret",
            "password",
            "authorization",
            "auth",
            "signature",
            "session",
            "access_token",
        )
        private const val MAX_HOSTS = 20
        private const val MAX_HOSTS_BYTES = 2048
        private const val MAX_LINK_LENGTH = 2048
        private const val MAX_METADATA_PROPERTIES = 8
        private const val MAX_METADATA_TEXT = 200
        private const val MAX_METADATA_BYTES = 2048
        private val MAX_FUTURE_SKEW = Duration.ofMinutes(5)
        private val MAX_SNAPSHOT_AGE = Duration.ofDays(3650)
    }
}
