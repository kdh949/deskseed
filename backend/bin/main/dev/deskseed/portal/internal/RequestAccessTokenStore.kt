package dev.deskseed.portal.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class RequestAccessTokenStore(
    private val repository: RequestAccessTokenRepository,
    private val codec: RequestAccessTokenCodec,
    private val clock: Clock,
) {
    @Transactional
    fun issue(ticketId: UUID): String {
        val issued = codec.issue()
        repository.save(
            RequestAccessTokenEntity(
                ticketId = ticketId,
                tokenHash = issued.hash,
                createdAt = Instant.now(clock),
            ),
        )
        return issued.raw
    }

    @Transactional(readOnly = true)
    fun resolveTicketId(rawToken: String): UUID? = repository
        .findActiveByHash(
            tokenHash = codec.hash(rawToken),
            now = Instant.now(clock),
        )
        ?.ticketId
}
