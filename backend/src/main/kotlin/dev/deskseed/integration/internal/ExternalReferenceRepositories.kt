package dev.deskseed.integration.internal

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

internal interface ExternalSystemRepository : JpaRepository<ExternalSystemEntity, UUID> {
    fun findAllByOrderByDisplayNameAscIdAsc(): List<ExternalSystemEntity>
    fun findAllByStatusOrderByDisplayNameAscIdAsc(status: String): List<ExternalSystemEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select system from ExternalSystemEntity system where system.id = :id")
    fun findLockedById(id: UUID): ExternalSystemEntity?
}

internal interface ExternalReferenceRepository : JpaRepository<ExternalReferenceEntity, UUID> {
    fun findAllByTicketIdOrderByCreatedAtDescIdDesc(ticketId: UUID): List<ExternalReferenceEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select reference from ExternalReferenceEntity reference " +
            "where reference.id = :referenceId and reference.ticketId = :ticketId",
    )
    fun findLockedByTicketIdAndId(ticketId: UUID, referenceId: UUID): ExternalReferenceEntity?
}
