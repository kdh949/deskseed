package dev.deskseed.portal.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

internal interface RequestAccessTokenRepository : JpaRepository<RequestAccessTokenEntity, UUID> {
    @Query(
        """
        select token
        from RequestAccessTokenEntity token
        where token.tokenHash = :tokenHash
          and token.revokedAt is null
          and token.expiresAt > :now
        """,
    )
    fun findActiveByHash(
        @Param("tokenHash") tokenHash: String,
        @Param("now") now: Instant,
    ): RequestAccessTokenEntity?

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select token from RequestAccessTokenEntity token
        where token.tokenHash = :tokenHash
          and token.revokedAt is null
          and token.expiresAt > :now
        """,
    )
    fun lockActiveByHash(
        @Param("tokenHash") tokenHash: String,
        @Param("now") now: Instant,
    ): RequestAccessTokenEntity?

    @Modifying
    @Query(
        """
        update RequestAccessTokenEntity token set token.revokedAt = :now
        where token.ticketId = :ticketId and token.revokedAt is null
        """,
    )
    fun revokeAllForTicket(@Param("ticketId") ticketId: UUID, @Param("now") now: Instant): Int
}
