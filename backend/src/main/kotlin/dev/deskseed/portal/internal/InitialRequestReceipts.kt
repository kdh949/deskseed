package dev.deskseed.portal.internal

import dev.deskseed.attachments.InitialRequestAttachmentContent
import dev.deskseed.ticketing.CustomerRequestFormValues
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class InitialRequestReceipt(val payloadDigest: String, val ticketId: UUID, val ticketNumber: Long)
internal class InitialRequestCommandConflictException : RuntimeException()

/** Seven-day operational replay protection; raw request content and capabilities never enter this table. */
@Repository
internal class InitialRequestReceipts(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    properties: PublicRequestRateLimitProperties,
) {
    private val key = SecretKeySpec(properties.fingerprintKeyBytes(), "HmacSHA256")

    fun commandDigest(command: SubmitAnonymousRequest): String = digest("request-command-v1", mapper.writeValueAsString(listOf(
        command.authenticatedCustomerId?.let { "customer:$it" } ?: "anonymous:${command.email.trim().lowercase(Locale.ROOT)}",
        command.clientCommandId,
    )))

    fun payloadDigest(command: SubmitAnonymousRequest, form: CustomerRequestFormValues,
                      attachments: List<InitialRequestAttachmentContent>): String = digest("request-payload-v1", mapper.writeValueAsString(listOf(
        command.authenticatedCustomerId?.toString(),
        if (command.authenticatedCustomerId == null) listOf(command.name.trim(), command.email.trim().lowercase(Locale.ROOT)) else null,
        command.subject.trim(), command.message.trim(), form.formId, form.formVersion, form.fieldValues.toSortedMap(),
        command.acceptedPolicies.sortedBy { it.policyKey }, attachments,
    )))

    fun lockAndRead(digest: String, now: Instant): InitialRequestReceipt? {
        // Small bounded expiry sweep, independent of audit retention. SKIP LOCKED avoids competing cleanup waits.
        jdbc.update("""delete from customer_request_command_receipts where command_digest in
            (select command_digest from customer_request_command_receipts where expires_at <= ?
             order by expires_at, command_digest limit 50 for update skip locked)""", Timestamp.from(now))
        val lock = ByteBuffer.wrap(digest.chunked(2).take(8).map { it.toInt(16).toByte() }.toByteArray()).long
        jdbc.queryForList("select pg_advisory_xact_lock(?)", lock)
        jdbc.update("delete from customer_request_command_receipts where command_digest = ? and expires_at <= ?", digest, Timestamp.from(now))
        return read(digest, now)
    }

    fun read(digest: String, now: Instant): InitialRequestReceipt? = jdbc.query(
        "select payload_digest, ticket_id, ticket_number from customer_request_command_receipts where command_digest = ? and expires_at > ?",
        { row, _ -> InitialRequestReceipt(row.getString("payload_digest"), row.getObject("ticket_id", UUID::class.java), row.getLong("ticket_number")) },
        digest, Timestamp.from(now),
    ).singleOrNull()

    fun save(digest: String, payloadDigest: String, ticketId: UUID, ticketNumber: Long, now: Instant) {
        jdbc.update("""insert into customer_request_command_receipts
            (command_digest, payload_digest, ticket_id, ticket_number, created_at, expires_at) values (?, ?, ?, ?, ?, ?)""",
            digest, payloadDigest, ticketId, ticketNumber, Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(7))))
    }

    private fun digest(purpose: String, value: String): String = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal("$purpose\u0000$value".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
