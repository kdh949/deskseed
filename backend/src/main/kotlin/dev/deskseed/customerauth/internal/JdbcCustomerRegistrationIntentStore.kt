package dev.deskseed.customerauth.internal

import dev.deskseed.foundation.CommandContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal data class CustomerRegistrationPolicySelection(
    val policyId: UUID,
    val policyVersion: Int,
) {
    init {
        require(policyVersion >= 1) { "registration policy version must be positive" }
    }
}

internal data class NewCustomerRegistrationIntent(
    val emailDisplay: String,
    val passwordHash: CustomerPasswordHash,
    val displayName: String,
    val companyName: String,
    val policySelections: List<CustomerRegistrationPolicySelection>,
    val ttl: Duration,
    val context: CommandContext,
) {
    override fun toString(): String = "[PROTECTED NEW CUSTOMER REGISTRATION INTENT]"
}

internal data class CreatedCustomerRegistrationIntent(
    val id: UUID,
    val rawContinuationSecret: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "[PROTECTED CREATED CUSTOMER REGISTRATION INTENT]"
}

internal sealed interface ProofVerifiedCustomerRegistrationIntent {
    val id: UUID
    val emailNormalized: String
    val emailDisplay: String
    val passwordHash: CustomerPasswordHash
    val displayName: String
    val companyName: String
    val policySelections: List<CustomerRegistrationPolicySelection>
    val requestId: String
    val correlationId: String
    val createdAt: Instant
    val expiresAt: Instant
}

private data class JdbcProofVerifiedCustomerRegistrationIntent(
    override val id: UUID,
    override val emailNormalized: String,
    override val emailDisplay: String,
    override val passwordHash: CustomerPasswordHash,
    override val displayName: String,
    override val companyName: String,
    override val policySelections: List<CustomerRegistrationPolicySelection>,
    override val requestId: String,
    override val correlationId: String,
    override val createdAt: Instant,
    override val expiresAt: Instant,
    val version: Long,
) : ProofVerifiedCustomerRegistrationIntent {
    override fun toString(): String = "[PROTECTED PROOF-VERIFIED CUSTOMER REGISTRATION INTENT]"
}

@Component
internal class JdbcCustomerRegistrationIntentStore(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    fun replacePending(command: NewCustomerRegistrationIntent): CreatedCustomerRegistrationIntent {
        val prepared = prepare(command)
        lockEmail(prepared.emailNormalized)
        return replacePendingLocked(command, prepared)
    }

    @Transactional
    fun replacePendingIfAccountAbsent(command: NewCustomerRegistrationIntent): CreatedCustomerRegistrationIntent? {
        val prepared = prepare(command)
        lockEmail(prepared.emailNormalized)
        val accountExists = jdbc.queryForObject(
            "select exists(select 1 from customer_accounts where email_normalized = ?)",
            Boolean::class.java,
            prepared.emailNormalized,
        ) == true
        if (accountExists) return null
        return replacePendingLocked(command, prepared)
    }

    private fun prepare(command: NewCustomerRegistrationIntent): PreparedRegistrationIntent {
        validate(command)
        val emailDisplay = command.emailDisplay.trim()
        val emailNormalized = emailDisplay.lowercase(Locale.ROOT)
        val now = Instant.now(clock)
        return PreparedRegistrationIntent(
            emailDisplay = emailDisplay,
            emailNormalized = emailNormalized,
            displayName = command.displayName.trim(),
            companyName = command.companyName.trim(),
            now = now,
            expiresAt = now.plus(command.ttl),
            id = UUID.randomUUID(),
            rawContinuationSecret = CustomerAuthSecrets.randomBearer(),
        )
    }

    private fun lockEmail(emailNormalized: String) {
        jdbc.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "customer-registration-intent:$emailNormalized",
        )
    }

    private fun replacePendingLocked(
        command: NewCustomerRegistrationIntent,
        prepared: PreparedRegistrationIntent,
    ): CreatedCustomerRegistrationIntent {
        jdbc.update(
            """
            update customer_registration_intents
               set status = 'CANCELLED', cancelled_at = ?, updated_at = ?, version = version + 1
             where email_normalized = ?
               and status = 'PENDING'
            """.trimIndent(),
            Timestamp.from(prepared.now),
            Timestamp.from(prepared.now),
            prepared.emailNormalized,
        )
        jdbc.update(
            """
            insert into customer_registration_intents
                (id, email_normalized, email_display, password_hash, display_name, company_name,
                 continuation_secret_digest, status, request_id, correlation_id,
                 created_at, updated_at, expires_at, consumed_at, cancelled_at, version)
            values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, null, null, 0)
            """.trimIndent(),
            prepared.id,
            prepared.emailNormalized,
            prepared.emailDisplay,
            command.passwordHash.encoded,
            prepared.displayName,
            prepared.companyName,
            CustomerAuthSecrets.digest(prepared.rawContinuationSecret),
            command.context.requestId,
            command.context.correlationId,
            Timestamp.from(prepared.now),
            Timestamp.from(prepared.now),
            Timestamp.from(prepared.expiresAt),
        )
        command.policySelections.forEach { selection ->
            jdbc.update(
                """
                insert into customer_registration_intent_consents
                    (intent_id, policy_id, policy_version, context, selected_at)
                values (?, ?, ?, 'REGISTRATION', ?)
                """.trimIndent(),
                prepared.id,
                selection.policyId,
                selection.policyVersion,
                Timestamp.from(prepared.now),
            )
        }
        return CreatedCustomerRegistrationIntent(
            prepared.id,
            prepared.rawContinuationSecret,
            prepared.expiresAt,
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun lockPendingByProof(intentId: UUID, rawContinuationSecret: String): ProofVerifiedCustomerRegistrationIntent? {
        if (rawContinuationSecret.length !in 1..256) return null
        val now = Instant.now(clock)
        val pending = jdbc.query(
            """
            select id, email_normalized, email_display, password_hash, display_name, company_name,
                   request_id, correlation_id, created_at, expires_at, version
              from customer_registration_intents
             where id = ?
               and continuation_secret_digest = ?
               and status = 'PENDING'
               and expires_at > ?
             for update
            """.trimIndent(),
            { resultSet, _ -> pending(resultSet) },
            intentId,
            CustomerAuthSecrets.digest(rawContinuationSecret),
            Timestamp.from(now),
        ).singleOrNull() ?: return null
        return pending.copy(policySelections = policySelections(intentId))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun markConsumed(intent: ProofVerifiedCustomerRegistrationIntent): Boolean {
        val verified = intent as? JdbcProofVerifiedCustomerRegistrationIntent ?: return false
        val now = Instant.now(clock)
        return jdbc.update(
            """
            update customer_registration_intents
               set status = 'CONSUMED', consumed_at = ?, updated_at = ?, version = version + 1
             where id = ?
               and status = 'PENDING'
               and expires_at > ?
               and version = ?
            """.trimIndent(),
            Timestamp.from(now),
            Timestamp.from(now),
            verified.id,
            Timestamp.from(now),
            verified.version,
        ) == 1
    }

    private fun pending(resultSet: ResultSet) = JdbcProofVerifiedCustomerRegistrationIntent(
        id = resultSet.getObject("id", UUID::class.java),
        emailNormalized = resultSet.getString("email_normalized"),
        emailDisplay = resultSet.getString("email_display"),
        passwordHash = CustomerPasswordHash.fromEncoded(resultSet.getString("password_hash")),
        displayName = resultSet.getString("display_name"),
        companyName = resultSet.getString("company_name"),
        policySelections = emptyList(),
        requestId = resultSet.getString("request_id"),
        correlationId = resultSet.getString("correlation_id"),
        createdAt = resultSet.getTimestamp("created_at").toInstant(),
        expiresAt = resultSet.getTimestamp("expires_at").toInstant(),
        version = resultSet.getLong("version"),
    )

    private fun policySelections(intentId: UUID): List<CustomerRegistrationPolicySelection> = jdbc.query(
        """
        select policy_id, policy_version
          from customer_registration_intent_consents
         where intent_id = ?
         order by policy_id
        """.trimIndent(),
        { resultSet, _ ->
            CustomerRegistrationPolicySelection(
                resultSet.getObject("policy_id", UUID::class.java),
                resultSet.getInt("policy_version"),
            )
        },
        intentId,
    )

    private fun validate(command: NewCustomerRegistrationIntent) {
        val email = command.emailDisplay.trim()
        require(email.length in 3..254 && email.none(::forbiddenTextCharacter)) {
            "registration email is invalid"
        }
        require(command.displayName.trim().hasCodePointLength(1, 100) && command.displayName.none(::forbiddenTextCharacter)) {
            "registration display name is invalid"
        }
        require(command.companyName.trim().hasCodePointLength(1, 160) && command.companyName.none(::forbiddenTextCharacter)) {
            "registration company name is invalid"
        }
        require(command.ttl in Duration.ofMinutes(5)..Duration.ofHours(48)) {
            "registration intent TTL is out of policy"
        }
        require(command.policySelections.size <= 20) { "registration policy selection count is invalid" }
        require(command.policySelections.distinctBy { it.policyId }.size == command.policySelections.size) {
            "registration policy selections must be unique"
        }
    }

    private fun forbiddenTextCharacter(character: Char): Boolean =
        character.isISOControl() || character == '<' || character == '>'

    private fun String.hasCodePointLength(minimum: Int, maximum: Int): Boolean =
        codePointCount(0, length) in minimum..maximum

    private data class PreparedRegistrationIntent(
        val emailDisplay: String,
        val emailNormalized: String,
        val displayName: String,
        val companyName: String,
        val now: Instant,
        val expiresAt: Instant,
        val id: UUID,
        val rawContinuationSecret: String,
    )
}
