package dev.deskseed.eventpublication.internal

import dev.deskseed.eventpublication.DomainEventAppend
import dev.deskseed.eventpublication.DomainEventEnvelope
import dev.deskseed.eventpublication.DomainEventVisibility
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers
@dev.deskseed.testsupport.category.IntegrationTest
class EventOutboxIntegrationTest {
    private val now = Instant.parse("2026-08-18T00:00:00Z")
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var outbox: JdbcEventOutbox

    @BeforeEach
    fun prepareStore() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("truncate domain_event_outbox")
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        outbox = JdbcEventOutbox(jdbc, ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `rolled back ticket mutation leaves no delivery intent`() {
        transactions.executeWithoutResult { status ->
            outbox.append(eventAppend())
            status.setRollbackOnly()
        }

        assertThat(jdbc.queryForObject("select count(*) from domain_event_outbox", Long::class.java)).isZero()
    }

    @Test
    fun `lease recovery gives the same stable event a replayable second claim`() {
        val eventId = transactions.execute {
            outbox.append(eventAppend())
        }

        val firstClaim = transactions.execute { outbox.claimNext("worker-a", 30) }!!
        assertThat(firstClaim.envelope.id).isEqualTo(eventId)
        assertThat(firstClaim.envelope.sequence).isZero()
        assertThat(firstClaim.attemptCount).isEqualTo(1)
        assertThat(firstClaim.visibility).isEqualTo(DomainEventVisibility.PUBLIC)
        assertThat(firstClaim.envelope.data).doesNotContainKeys("body", "secret", "token")

        jdbc.update(
            "update domain_event_outbox set lease_expires_at = ? where id = ?",
            Timestamp.from(now.minusSeconds(1)),
            eventId,
        )
        assertThat(transactions.execute { outbox.returnExpiredLeases() }).isEqualTo(1)

        val replayedClaim = transactions.execute { outbox.claimNext("worker-b", 30) }!!
        assertThat(replayedClaim.envelope.id).isEqualTo(eventId)
        assertThat(replayedClaim.envelope.sequence).isZero()
        assertThat(replayedClaim.attemptCount).isEqualTo(2)

        transactions.executeWithoutResult { outbox.markDelivered(eventId, "worker-b") }
        assertThat(jdbc.queryForObject("select status from domain_event_outbox where id = ?", String::class.java, eventId))
            .isEqualTo("DELIVERED")
    }

    @Test
    fun `a later event for the same subject waits for the active predecessor lease`() {
        val firstId = transactions.execute { outbox.append(eventAppend()) }!!
        val secondId = transactions.execute { outbox.append(eventAppend()) }!!

        val first = transactions.execute { outbox.claimNext("worker-a", 30) }!!
        assertThat(first.envelope.id).isEqualTo(firstId)
        assertThat(first.envelope.sequence).isZero()
        assertThat(transactions.execute { outbox.claimNext("worker-b", 30) }).isNull()

        transactions.executeWithoutResult { outbox.markDelivered(firstId, "worker-a") }

        val second = transactions.execute { outbox.claimNext("worker-b", 30) }!!
        assertThat(second.envelope.id).isEqualTo(secondId)
        assertThat(second.envelope.sequence).isEqualTo(1)
    }

    private fun eventAppend() = DomainEventAppend(
        envelope = DomainEventEnvelope(
            id = UUID.randomUUID(),
            type = "ticket.updated",
            version = 1,
            occurredAt = now,
            subject = "ticket:018f7c2c-7348-7a32-a971-4c9a845b3350",
            sequence = null,
            correlationId = "correlation-1042",
            causationId = "command-1042",
            actorType = ActorType.STAFF,
            actorId = UUID.fromString("018f7c2c-7348-7a32-a971-4c9a845b3350"),
            source = RequestSource.AGENT_UI,
            requestId = "request-1042",
            commandId = "command-1042",
            data = mapOf("ticketNumber" to "1042", "changedFields" to "status"),
        ),
        visibility = DomainEventVisibility.PUBLIC,
    )

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
