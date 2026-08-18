package dev.deskseed.collaboration.internal

import dev.deskseed.collaboration.NewTicketDraft
import dev.deskseed.collaboration.TicketDraftChannel
import dev.deskseed.collaboration.UpdatedTicketDraft
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
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers
@dev.deskseed.testsupport.category.IntegrationTest
class JdbcTicketDraftStoreIntegrationTest {
    private val now = Instant.parse("2026-08-18T00:00:00Z")
    private val customerId = UUID.fromString("018f7c2c-7348-7a32-a971-4c9a845b3301")
    private val ownerId = UUID.fromString("018f7c2c-7348-7a32-a971-4c9a845b3302")
    private val otherOwnerId = UUID.fromString("018f7c2c-7348-7a32-a971-4c9a845b3303")
    private val ticketId = UUID.fromString("018f7c2c-7348-7a32-a971-4c9a845b3304")
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var store: JdbcTicketDraftStore

    @BeforeEach
    fun prepareStore() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
        jdbc.update("truncate ticket_drafts")
        jdbc.update("update ticket_draft_cleanup_lease set lease_owner = null, lease_expires_at = null")
        jdbc.update("delete from tickets where id = ?", ticketId)
        jdbc.update("delete from staff_accounts where id in (?, ?)", ownerId, otherOwnerId)
        jdbc.update("delete from customers where id = ?", customerId)
        jdbc.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, 'Draft Customer', 'draft-customer@example.test', 'draft-customer@example.test', ?, ?)
            """.trimIndent(),
            customerId,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        listOf(ownerId, otherOwnerId).forEachIndexed { index, staffId ->
            jdbc.update(
                """
                insert into staff_accounts (
                    id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at
                ) values (?, ?, ?, ?, 'AGENT', 'ACTIVE', 'not-a-real-hash', ?, ?)
                """.trimIndent(),
                staffId,
                "draft-agent-$index@example.test",
                "draft-agent-$index@example.test",
                "Draft Agent $index",
                Timestamp.from(now),
                Timestamp.from(now),
            )
        }
        jdbc.update(
            """
            insert into tickets (
                id, ticket_number, requester_id, kind, subject, status, priority, channel, version, created_at, updated_at
            ) values (?, 1042, ?, 'CUSTOMER_REQUEST', 'Draft recovery', 'OPEN', 'NORMAL', 'WEB', 7, ?, ?)
            """.trimIndent(),
            ticketId,
            customerId,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        store = JdbcTicketDraftStore(jdbc, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `PUBLIC and INTERNAL drafts are independent owner-only optimistic rows without touching ticket state`() {
        val publicDraft = transactions.execute {
            store.create(newDraft(TicketDraftChannel.PUBLIC_REPLY, "Public recovery"))
        }!!
        val internalDraft = transactions.execute {
            store.create(newDraft(TicketDraftChannel.INTERNAL_NOTE, "Internal recovery"))
        }!!

        assertThat(publicDraft.draftVersion).isEqualTo(1)
        assertThat(internalDraft.draftVersion).isEqualTo(1)
        assertThat(transactions.execute {
            store.update(
                ownerId,
                ticketId,
                TicketDraftChannel.PUBLIC_REPLY,
                UpdatedTicketDraft("silent overwrite", emptyList(), UUID.randomUUID(), 7, 2),
            )
        }).isNull()

        val updated = transactions.execute {
            store.update(
                ownerId,
                ticketId,
                TicketDraftChannel.PUBLIC_REPLY,
                UpdatedTicketDraft("Updated public recovery", emptyList(), UUID.randomUUID(), 7, 1),
            )
        }!!

        assertThat(updated.draftVersion).isEqualTo(2)
        assertThat(updated.body).isEqualTo("Updated public recovery")
        assertThat(transactions.execute {
            store.find(ownerId, ticketId, TicketDraftChannel.INTERNAL_NOTE)
        }?.body).isEqualTo("Internal recovery")
        assertThat(transactions.execute {
            store.find(otherOwnerId, ticketId, TicketDraftChannel.PUBLIC_REPLY)
        }).isNull()
        assertThat(jdbc.queryForObject("select version from tickets where id = ?", Long::class.java, ticketId))
            .isEqualTo(7)
        assertThat(jdbc.queryForObject("select updated_at from tickets where id = ?", Timestamp::class.java, ticketId)?.toInstant())
            .isEqualTo(now)
    }

    @Test
    fun `expired drafts are deleted in a bounded lease-protected maintenance pass`() {
        transactions.execute { store.create(newDraft(TicketDraftChannel.PUBLIC_REPLY, "Expired recovery")) }
        jdbc.update(
            "update ticket_drafts set updated_at = ?, expires_at = ? where owner_staff_id = ? and ticket_id = ?",
            Timestamp.from(now.minusSeconds(2)),
            Timestamp.from(now.minusSeconds(1)),
            ownerId,
            ticketId,
        )

        assertThat(transactions.execute {
            store.find(ownerId, ticketId, TicketDraftChannel.PUBLIC_REPLY)
        }).isNull()
        assertThat(transactions.execute { store.purgeExpired("test-draft-cleanup", 10) }).isEqualTo(1)
        assertThat(transactions.execute {
            store.find(ownerId, ticketId, TicketDraftChannel.PUBLIC_REPLY)
        }).isNull()
        assertThat(jdbc.queryForObject("select version from tickets where id = ?", Long::class.java, ticketId))
            .isEqualTo(7)
    }

    private fun newDraft(channel: TicketDraftChannel, body: String) = NewTicketDraft(
        ownerStaffId = ownerId,
        ticketId = ticketId,
        ticketNumber = 1042,
        channel = channel,
        body = body,
        attachmentIds = emptyList(),
        clientDeviceId = UUID.fromString("018f7c2c-7348-7a32-a971-4c9a845b3305"),
        baseTicketVersion = 7,
    )

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
