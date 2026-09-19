package dev.deskseed.aiassistance.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

internal fun normalizeAiUsageText(value: String): String = Normalizer.normalize(
    value.replace("\r\n", "\n").replace('\r', '\n').trim(),
    Normalizer.Form.NFC,
)

internal fun aiUsageCodePointLength(value: String): Int = value.codePointCount(0, value.length)

internal fun aiUsageSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
