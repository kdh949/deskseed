package dev.deskseed.integration.internal

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

internal interface IntegrationClientRepository : JpaRepository<IntegrationClientEntity, UUID> {
    override fun findAll(pageable: Pageable): Page<IntegrationClientEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select client from IntegrationClientEntity client where client.id = :id")
    fun findLockedById(id: UUID): IntegrationClientEntity?
}

internal interface IntegrationCredentialRepository : JpaRepository<IntegrationCredentialEntity, UUID> {
    fun findAllByClientIdOrderBySequenceDesc(clientId: UUID): List<IntegrationCredentialEntity>

    @Query(
        """
        select credential
        from IntegrationCredentialEntity credential
        where credential.clientId in :clientIds
          and (
            credential.status in ('ACTIVE', 'RETIRING')
            or credential.sequence = (
              select max(latest.sequence)
              from IntegrationCredentialEntity latest
              where latest.clientId = credential.clientId
            )
          )
        """,
    )
    fun findListSummariesByClientIdIn(clientIds: Collection<UUID>): List<IntegrationCredentialEntity>

    @Query("select credential.clientId from IntegrationCredentialEntity credential where credential.publicKeyId = :publicKeyId")
    fun findClientIdByPublicKeyId(publicKeyId: String): UUID?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select credential from IntegrationCredentialEntity credential where credential.publicKeyId = :publicKeyId")
    fun findLockedByPublicKeyId(publicKeyId: String): IntegrationCredentialEntity?
}
