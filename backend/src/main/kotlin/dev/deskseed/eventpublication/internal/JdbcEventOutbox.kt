package dev.deskseed.eventpublication.internal

import dev.deskseed.eventpublication.ClaimedDomainEvent
import dev.deskseed.eventpublication.DomainEventAppend
import dev.deskseed.eventpublication.DomainEventEnvelope
import dev.deskseed.eventpublication.DomainEventVisibility
import dev.deskseed.eventpublication.EventOutboxOperations
import dev.deskseed.eventpublication.EventPublicationPort
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcEventOutbox(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : EventPublicationPort, EventOutboxOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun append(event: DomainEventAppend): UUID {
        val now = Instant.now(clock)
        val envelope = event.envelope
        require(envelope.occurredAt <= now.plusSeconds(MAX_FUTURE_SECONDS)) { "Event occurredAt is implausibly future-dated" }
        jdbc.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            envelope.subject,
        )
        val sequence = jdbc.queryForObject(
            "select coalesce(max(subject_sequence), -1) + 1 from domain_event_outbox where subject = ?",
            Long::class.java,
            envelope.subject,
        ) ?: 0
        val persisted = envelope.copy(sequence = sequence)
        jdbc.update(
            """
            insert into domain_event_outbox (
                id, event_type, event_version, occurred_at, subject, subject_sequence,
                correlation_id, causation_id, visibility, data_json,
                actor_type, actor_id, source, request_id, command_id,
                status, attempt_count, available_at, lease_owner, lease_expires_at,
                delivered_at, dead_lettered_at, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?,
                      'PENDING', 0, ?, null, null, null, null, ?)
            """.trimIndent(),
            persisted.id,
            persisted.type,
            persisted.version,
            Timestamp.from(persisted.occurredAt),
            persisted.subject,
            persisted.sequence,
            persisted.correlationId,
            persisted.causationId,
            event.visibility.name,
            objectMapper.writeValueAsString(persisted.data),
            persisted.actorType.name,
            persisted.actorId,
            persisted.source.name,
            persisted.requestId,
            persisted.commandId,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return persisted.id
    }

    @Transactional
    override fun claimNext(workerId: String, leaseDurationSeconds: Long): ClaimedDomainEvent? {
        require(workerId.matches(WORKER_ID)) { "workerId must be bounded" }
        require(leaseDurationSeconds in 1..300) { "leaseDurationSeconds must be between 1 and 300" }
        val now = Instant.now(clock)
        return try {
            jdbc.queryForObject(
                """
                with candidate as (
                    select candidate.id
                      from domain_event_outbox candidate
                     where candidate.status = 'PENDING'
                       and candidate.available_at <= ?
                       and not exists (
                           select 1
                             from domain_event_outbox predecessor
                            where predecessor.subject = candidate.subject
                              and predecessor.subject_sequence < candidate.subject_sequence
                              and predecessor.status in ('PENDING', 'LEASED')
                       )
                     order by candidate.occurred_at, candidate.id
                     for update skip locked
                     limit 1
                )
                update domain_event_outbox outbox
                   set status = 'LEASED', lease_owner = ?, lease_expires_at = ?,
                       attempt_count = attempt_count + 1
                  from candidate
                 where outbox.id = candidate.id
                returning outbox.*
                """.trimIndent(),
                { result, _ -> result.toClaimed() },
                Timestamp.from(now),
                workerId,
                Timestamp.from(now.plusSeconds(leaseDurationSeconds)),
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }
    }

    @Transactional
    override fun markDelivered(eventId: UUID, workerId: String) {
        val updated = jdbc.update(
            """
            update domain_event_outbox
               set status = 'DELIVERED', delivered_at = ?, lease_owner = null, lease_expires_at = null
             where id = ? and status = 'LEASED' and lease_owner = ?
            """.trimIndent(),
            Timestamp.from(Instant.now(clock)),
            eventId,
            workerId,
        )
        require(updated == 1) { "Event cannot be delivered without its active lease" }
    }

    @Transactional
    override fun returnExpiredLeases(): Int {
        val now = Timestamp.from(Instant.now(clock))
        return jdbc.update(
            """
            update domain_event_outbox
               set status = 'PENDING', lease_owner = null, lease_expires_at = null, available_at = ?
             where status = 'LEASED' and lease_expires_at < ?
            """.trimIndent(),
            now,
            now,
        )
    }

    private fun java.sql.ResultSet.toClaimed(): ClaimedDomainEvent {
        val id = getObject("id", UUID::class.java)
        val envelope = DomainEventEnvelope(
            id = id,
            type = getString("event_type"),
            version = getInt("event_version"),
            occurredAt = getTimestamp("occurred_at").toInstant(),
            subject = getString("subject"),
            sequence = getLong("subject_sequence"),
            correlationId = getString("correlation_id"),
            causationId = getString("causation_id"),
            actorType = ActorType.valueOf(getString("actor_type")),
            actorId = getObject("actor_id", UUID::class.java),
            source = RequestSource.valueOf(getString("source")),
            requestId = getString("request_id"),
            commandId = getString("command_id"),
            data = objectMapper.readValue(getString("data_json"), object : tools.jackson.core.type.TypeReference<Map<String, String>>() {}),
        )
        return ClaimedDomainEvent(
            envelope = envelope,
            visibility = DomainEventVisibility.valueOf(getString("visibility")),
            attemptCount = getInt("attempt_count"),
            leaseOwner = requireNotNull(getString("lease_owner")),
            leaseExpiresAt = getTimestamp("lease_expires_at").toInstant(),
        )
    }

    private companion object {
        const val MAX_FUTURE_SECONDS = 300L
        val WORKER_ID = Regex("[A-Za-z0-9._:-]{1,100}")
    }
}

@Component
internal class EventOutboxLeaseRecoveryWorker(
    private val outbox: EventOutboxOperations,
) {
    @Scheduled(fixedDelayString = "\${deskseed.event-outbox.lease-recovery-delay-ms:30000}")
    fun recoverExpiredLeases() {
        outbox.returnExpiredLeases()
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class EventOutboxSchedulingConfiguration
