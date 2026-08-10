package dev.deskseed.portal.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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
}
