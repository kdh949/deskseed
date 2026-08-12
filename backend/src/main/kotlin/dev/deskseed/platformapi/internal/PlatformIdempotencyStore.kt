package dev.deskseed.platformapi.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class PlatformStoredResponse(
    val status: Int,
    val headers: Map<String, String>,
    val bodyJson: String,
    val replayed: Boolean,
)

internal class PlatformIdempotencyKeyReusedException : RuntimeException()
internal class PlatformIdempotencyInProgressException : RuntimeException()
internal class PlatformIdempotencyKeyInvalidException : RuntimeException()

@Component
internal class PlatformIdempotencyStore(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${deskseed.platform.idempotency.retention:24h}") private val retention: Duration,
) {
    init {
        require(!retention.isNegative && !retention.isZero) { "Platform idempotency retention must be positive" }
    }

    fun execute(
        clientId: UUID,
        operationId: String,
        idempotencyKey: String,
        requestDescriptor: Any,
        action: () -> Pair<PlatformStoredResponse, UUID?>,
    ): PlatformStoredResponse {
        validateKey(idempotencyKey)
        val keyHash = sha256(idempotencyKey)
        val requestHash = sha256(objectMapper.writeValueAsString(requestDescriptor))
        val now = Instant.now(clock)
        deleteExpiredIdentity(clientId, operationId, keyHash, now)
        val recordId = UUID.randomUUID()
        val inserted = jdbcTemplate.update(
            """
            insert into platform_idempotency_records (
                id, client_id, operation_id, idempotency_key_hash, request_hash, status, created_at, expires_at
            ) values (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
            on conflict (client_id, operation_id, idempotency_key_hash) do nothing
            """.trimIndent(),
            recordId,
            clientId,
            operationId,
            keyHash,
            requestHash,
            Timestamp.from(now),
            Timestamp.from(now.plus(retention)),
        )
        if (inserted == 0) return replayExisting(clientId, operationId, keyHash, requestHash)

        val (response, resourceId) = action()
        require(response.status in 200..299)
        jdbcTemplate.update(
            """
            update platform_idempotency_records
            set status = 'SUCCEEDED', response_status = ?, response_headers_json = ?, response_body_json = ?, resource_id = ?
            where id = ? and status = 'IN_PROGRESS'
            """.trimIndent(),
            response.status,
            objectMapper.writeValueAsString(response.headers),
            response.bodyJson,
            resourceId,
            recordId,
        )
        return response.copy(replayed = false)
    }

    private fun replayExisting(
        clientId: UUID,
        operationId: String,
        keyHash: String,
        requestHash: String,
    ): PlatformStoredResponse {
        val rows = jdbcTemplate.query(
            """
            select request_hash, status, response_status, response_headers_json, response_body_json
            from platform_idempotency_records
            where client_id = ? and operation_id = ? and idempotency_key_hash = ?
            for update
            """.trimIndent(),
            { result, _ ->
                ExistingRecord(
                    result.getString("request_hash"),
                    result.getString("status"),
                    result.getObject("response_status", Integer::class.java)?.toInt(),
                    result.getString("response_headers_json"),
                    result.getString("response_body_json"),
                )
            },
            clientId,
            operationId,
            keyHash,
        )
        val existing = rows.singleOrNull() ?: throw PlatformIdempotencyInProgressException()
        if (!MessageDigest.isEqual(existing.requestHash.toByteArray(), requestHash.toByteArray())) {
            throw PlatformIdempotencyKeyReusedException()
        }
        if (existing.status != "SUCCEEDED") throw PlatformIdempotencyInProgressException()
        val headers = objectMapper.readValue(existing.headersJson, Map::class.java)
            .entries.associate { it.key.toString() to it.value.toString() }
        return PlatformStoredResponse(
            checkNotNull(existing.responseStatus),
            headers,
            checkNotNull(existing.bodyJson),
            replayed = true,
        )
    }

    private fun deleteExpiredIdentity(clientId: UUID, operationId: String, keyHash: String, now: Instant) {
        jdbcTemplate.update(
            """
            delete from platform_idempotency_records
            where client_id = ? and operation_id = ? and idempotency_key_hash = ? and expires_at <= ?
            """.trimIndent(),
            clientId,
            operationId,
            keyHash,
            Timestamp.from(now),
        )
    }

    private fun validateKey(value: String) {
        if (value.length !in 8..200 || value.any(Char::isISOControl)) throw PlatformIdempotencyKeyInvalidException()
    }

    fun opaqueCommandId(clientId: UUID, operationId: String, key: String): String =
        "platform-${sha256("$clientId\n$operationId\n$key").take(64)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class ExistingRecord(
        val requestHash: String,
        val status: String,
        val responseStatus: Int?,
        val headersJson: String?,
        val bodyJson: String?,
    )
}

