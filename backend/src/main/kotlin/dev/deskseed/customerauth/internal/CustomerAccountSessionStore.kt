package dev.deskseed.customerauth.internal

import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.customerauth.CustomerAuthenticationMethod
import dev.deskseed.customerauth.CustomerCredentialState
import dev.deskseed.customerauth.CustomerPrincipal
import dev.deskseed.customerauth.CustomerRegistrationState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class CustomerAccountIdentity(
    val accountId: UUID,
    val principal: CustomerPrincipal,
    val credentialVersion: Long,
)

internal data class CustomerPasswordLoginCandidate(
    val accountId: UUID,
    val principal: CustomerPrincipal,
    val status: String,
    val passwordHash: CustomerPasswordHash?,
    val credentialVersion: Long,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER PASSWORD LOGIN CANDIDATE]"
}

internal data class NewCustomerSession(
    val rawToken: String,
    val principal: CustomerPrincipal,
) {
    override fun toString(): String = "[PROTECTED NEW CUSTOMER SESSION]"
}

internal data class NewCustomerPasswordAccount(
    val emailNormalized: String,
    val emailDisplay: String,
    val displayName: String,
    val companyName: String,
    val passwordHash: CustomerPasswordHash,
) {
    override fun toString(): String = "[PROTECTED NEW CUSTOMER PASSWORD ACCOUNT]"
}

internal data class CustomerPasswordlessRegistrationCandidate(
    val accountId: UUID,
    val customerId: UUID,
    val emailNormalized: String,
    val verifiedAt: Instant,
    val credentialVersion: Long,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER PASSWORDLESS REGISTRATION CANDIDATE]"
}

private data class CustomerMagicLinkAccountState(
    val status: String,
    val hasPassword: Boolean,
)

@Component
internal class CustomerAccountSessionStore(
    private val jdbcTemplate: JdbcTemplate,
    private val customerDirectory: CustomerDirectory,
    private val properties: CustomerAuthProperties,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockAccountEmail(emailNormalized: String) {
        jdbcTemplate.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "customer-account:$emailNormalized",
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun createPasswordAccount(command: NewCustomerPasswordAccount): CustomerAccountIdentity? {
        val exists = jdbcTemplate.queryForObject(
            "select exists(select 1 from customer_accounts where email_normalized = ?)",
            Boolean::class.java,
            command.emailNormalized,
        ) == true
        if (exists) return null
        val now = Instant.now(clock)
        val customer = customerDirectory.createVerified(
            name = command.displayName,
            email = command.emailDisplay,
            verifiedAt = now,
            companyName = command.companyName,
        )
        val accountId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into customer_accounts
                (id, customer_id, email_normalized, status, verified_at, last_login_at,
                 created_at, updated_at, version, password_hash, password_changed_at, credential_version)
            values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0, ?, ?, 0)
            """.trimIndent(),
            accountId,
            customer.id,
            command.emailNormalized,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now),
            command.passwordHash.encoded,
            Timestamp.from(now),
        )
        return CustomerAccountIdentity(
            accountId,
            CustomerPrincipal(
                accountId = accountId,
                customerId = customer.id,
                email = command.emailNormalized,
                displayName = customer.name,
                verifiedAt = now,
                companyName = command.companyName,
                credentialState = CustomerCredentialState.PASSWORD,
                registrationState = CustomerRegistrationState.COMPLETE,
                availableAuthenticationMethods = listOf(CustomerAuthenticationMethod.PASSWORD),
            ),
            credentialVersion = 0,
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun isPasswordlessMagicLinkEligible(emailNormalized: String): Boolean {
        lockAccountEmail(emailNormalized)
        return magicLinkAccountState(emailNormalized)?.let { state ->
            state.status == "ACTIVE" && !state.hasPassword
        } ?: customerDirectory.existsByNormalizedEmail(emailNormalized)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun resolveOrCreatePasswordlessAccount(
        emailNormalized: String,
        emailDisplay: String,
    ): CustomerAccountIdentity? {
        lockAccountEmail(emailNormalized)
        magicLinkAccountState(emailNormalized)?.let { state ->
            if (state.status != "ACTIVE" || state.hasPassword) return null
            return findAccount(emailNormalized)
        }
        if (!customerDirectory.existsByNormalizedEmail(emailNormalized)) return null

        val now = Instant.now(clock)
        // Reuse only an already-verified identity. An anonymous requester is never upgraded or claimed by email equality.
        val customer = customerDirectory.findVerifiedByNormalizedEmail(emailNormalized)
            ?: customerDirectory.createVerified("고객", emailDisplay, now)
        val accountId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into customer_accounts
                (id, customer_id, email_normalized, status, verified_at, last_login_at,
                 created_at, updated_at, version)
            values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
            """.trimIndent(),
            accountId,
            customer.id,
            emailNormalized,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return CustomerAccountIdentity(
            accountId = accountId,
            principal = CustomerPrincipal(
                accountId = accountId,
                customerId = customer.id,
                email = emailNormalized,
                displayName = customer.name,
                verifiedAt = now,
            ),
            credentialVersion = 0,
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun createSession(
        account: CustomerAccountIdentity,
        previousRawSession: String?,
        authenticationMethod: CustomerAuthenticationMethod = CustomerAuthenticationMethod.MAGIC_LINK,
    ): NewCustomerSession {
        val now = Instant.now(clock)
        if (!previousRawSession.isNullOrBlank()) {
            jdbcTemplate.update(
                "update customer_sessions set revoked_at = ? where session_token_digest = ? and revoked_at is null",
                Timestamp.from(now),
                CustomerAuthSecrets.digest(previousRawSession),
            )
        }
        val raw = CustomerAuthSecrets.randomBearer()
        val sessionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into customer_sessions
                (id, account_id, session_token_digest, created_at, last_activity_at,
                 expires_at, absolute_expires_at, revoked_at, authentication_method,
                 credential_version_snapshot)
            values (?, ?, ?, ?, ?, ?, ?, null, ?, ?)
            """.trimIndent(),
            sessionId,
            account.accountId,
            CustomerAuthSecrets.digest(raw),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plus(properties.sessionIdle)),
            Timestamp.from(now.plus(properties.sessionAbsolute)),
            authenticationMethod.name,
            account.credentialVersion,
        )
        jdbcTemplate.update(
            "update customer_accounts set last_login_at = ?, updated_at = ? where id = ?",
            Timestamp.from(now),
            Timestamp.from(now),
            account.accountId,
        )
        return NewCustomerSession(
            raw,
            account.principal.copy(
                sessionFingerprint = CustomerAuthSecrets.customerSessionFingerprint(properties.fingerprintKey, sessionId),
            ),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun lockPasswordlessRegistrationCandidate(
        accountId: UUID,
        emailNormalized: String,
        rawSession: String,
    ): CustomerPasswordlessRegistrationCandidate? {
        lockAccountEmail(emailNormalized)
        val now = Instant.now(clock)
        return jdbcTemplate.query(
            """
            select account.id as account_id, account.customer_id, account.email_normalized,
                   account.verified_at, account.credential_version
              from customer_accounts account
              join customers customer on customer.id = account.customer_id
              join customer_sessions session on session.account_id = account.id
             where account.id = ?
               and account.email_normalized = ?
               and account.status = 'ACTIVE'
               and account.password_hash is null
               and session.session_token_digest = ?
               and session.revoked_at is null
               and session.expires_at > ?
               and session.absolute_expires_at > ?
               and session.authentication_method = 'MAGIC_LINK'
               and session.credential_version_snapshot = account.credential_version
             for update of account, customer, session
            """.trimIndent(),
            { resultSet, _ ->
                CustomerPasswordlessRegistrationCandidate(
                    accountId = resultSet.getObject("account_id", UUID::class.java),
                    customerId = resultSet.getObject("customer_id", UUID::class.java),
                    emailNormalized = resultSet.getString("email_normalized"),
                    verifiedAt = resultSet.getTimestamp("verified_at").toInstant(),
                    credentialVersion = resultSet.getLong("credential_version"),
                )
            },
            accountId,
            emailNormalized,
            CustomerAuthSecrets.digest(rawSession),
            Timestamp.from(now),
            Timestamp.from(now),
        ).singleOrNull()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun completePasswordlessRegistration(
        candidate: CustomerPasswordlessRegistrationCandidate,
        passwordHash: CustomerPasswordHash,
        displayName: String,
        companyName: String,
    ): NewCustomerSession? {
        val now = Instant.now(clock)
        val updated = jdbcTemplate.update(
            """
            update customer_accounts
               set password_hash = ?, password_changed_at = ?, credential_version = credential_version + 1,
                   version = version + 1, updated_at = ?
             where id = ?
               and customer_id = ?
               and email_normalized = ?
               and status = 'ACTIVE'
               and password_hash is null
               and credential_version = ?
            """.trimIndent(),
            passwordHash.encoded,
            Timestamp.from(now),
            Timestamp.from(now),
            candidate.accountId,
            candidate.customerId,
            candidate.emailNormalized,
            candidate.credentialVersion,
        )
        if (updated != 1) return null
        check(
            jdbcTemplate.update(
                "update customers set name = ?, company_name = ?, updated_at = ? where id = ?",
                displayName,
                companyName,
                Timestamp.from(now),
                candidate.customerId,
            ) == 1,
        ) { "customer profile is missing" }
        jdbcTemplate.update(
            "update customer_sessions set revoked_at = ? where account_id = ? and revoked_at is null",
            Timestamp.from(now),
            candidate.accountId,
        )
        val credentialVersion = candidate.credentialVersion + 1
        return createSession(
            account = CustomerAccountIdentity(
                accountId = candidate.accountId,
                principal = CustomerPrincipal(
                    accountId = candidate.accountId,
                    customerId = candidate.customerId,
                    email = candidate.emailNormalized,
                    displayName = displayName,
                    verifiedAt = candidate.verifiedAt,
                    companyName = companyName,
                    credentialState = CustomerCredentialState.PASSWORD,
                    registrationState = CustomerRegistrationState.COMPLETE,
                    availableAuthenticationMethods = listOf(CustomerAuthenticationMethod.PASSWORD),
                ),
                credentialVersion = credentialVersion,
            ),
            previousRawSession = null,
            authenticationMethod = CustomerAuthenticationMethod.PASSWORD,
        )
    }

    fun resolveSession(rawToken: String): CustomerPrincipal? {
        val now = Instant.now(clock)
        return jdbcTemplate.query(
            """
            update customer_sessions session
               set last_activity_at = ?,
                   expires_at = least(?, session.absolute_expires_at)
              from customer_accounts account, customers customer
             where session.session_token_digest = ?
               and session.revoked_at is null
               and session.expires_at > ?
               and session.absolute_expires_at > ?
               and account.id = session.account_id
               and account.status = 'ACTIVE'
               and session.credential_version_snapshot = account.credential_version
               and customer.id = account.customer_id
            returning session.id as session_id, account.id as account_id, customer.id as customer_id,
                      account.email_normalized, customer.name, customer.company_name, account.verified_at,
                      account.password_hash
            """.trimIndent(),
            { resultSet, _ ->
                CustomerPrincipal(
                    accountId = resultSet.getObject("account_id", UUID::class.java),
                    customerId = resultSet.getObject("customer_id", UUID::class.java),
                    email = resultSet.getString("email_normalized"),
                    displayName = resultSet.getString("name"),
                    verifiedAt = resultSet.getTimestamp("verified_at").toInstant(),
                    companyName = resultSet.getString("company_name"),
                    credentialState = credentialState(resultSet.getString("password_hash")),
                    registrationState = registrationState(resultSet.getString("password_hash")),
                    availableAuthenticationMethods = authenticationMethods(resultSet.getString("password_hash")),
                    sessionFingerprint = CustomerAuthSecrets.customerSessionFingerprint(
                        properties.fingerprintKey,
                        resultSet.getObject("session_id", UUID::class.java),
                    ),
                )
            },
            Timestamp.from(now),
            Timestamp.from(now.plus(properties.sessionIdle)),
            CustomerAuthSecrets.digest(rawToken),
            Timestamp.from(now),
            Timestamp.from(now),
        ).singleOrNull()
    }

    fun revoke(rawToken: String): CustomerPrincipal? {
        val principal = resolveSession(rawToken) ?: return null
        jdbcTemplate.update(
            "update customer_sessions set revoked_at = ? where session_token_digest = ? and revoked_at is null",
            Timestamp.from(Instant.now(clock)),
            CustomerAuthSecrets.digest(rawToken),
        )
        return principal
    }

    fun findPasswordLoginCandidate(emailNormalized: String): CustomerPasswordLoginCandidate? = jdbcTemplate.query(
        """
        select account.id as account_id, account.customer_id, account.email_normalized,
               account.status, account.verified_at, account.password_hash, account.credential_version,
               customer.name, customer.company_name
          from customer_accounts account
          join customers customer on customer.id = account.customer_id
         where account.email_normalized = ?
        """.trimIndent(),
        { resultSet, _ ->
            val passwordHash = resultSet.getString("password_hash")?.let(CustomerPasswordHash::fromEncoded)
            val accountId = resultSet.getObject("account_id", UUID::class.java)
            val credentialVersion = resultSet.getLong("credential_version")
            CustomerPasswordLoginCandidate(
                accountId = accountId,
                principal = CustomerPrincipal(
                    accountId = accountId,
                    customerId = resultSet.getObject("customer_id", UUID::class.java),
                    email = resultSet.getString("email_normalized"),
                    displayName = resultSet.getString("name"),
                    verifiedAt = resultSet.getTimestamp("verified_at").toInstant(),
                    companyName = resultSet.getString("company_name"),
                    credentialState = credentialState(passwordHash?.encoded),
                    registrationState = registrationState(passwordHash?.encoded),
                    availableAuthenticationMethods = authenticationMethods(passwordHash?.encoded),
                ),
                status = resultSet.getString("status"),
                passwordHash = passwordHash,
                credentialVersion = credentialVersion,
            )
        },
        emailNormalized,
    ).singleOrNull()

    @Transactional(propagation = Propagation.MANDATORY)
    fun lockAndFindPasswordLoginCandidate(emailNormalized: String): CustomerPasswordLoginCandidate? {
        lockAccountEmail(emailNormalized)
        return findPasswordLoginCandidate(emailNormalized)
    }

    private fun findAccount(emailNormalized: String): CustomerAccountIdentity? = jdbcTemplate.query(
        """
        select account.id as account_id, account.customer_id, account.email_normalized,
               account.status, customer.name, customer.company_name, account.verified_at,
               account.password_hash, account.credential_version
          from customer_accounts account
          join customers customer on customer.id = account.customer_id
         where account.email_normalized = ?
        """.trimIndent(),
        { resultSet, _ ->
            check(resultSet.getString("status") == "ACTIVE") { "customer account is disabled" }
            val accountId = resultSet.getObject("account_id", UUID::class.java)
            CustomerAccountIdentity(
                accountId,
                CustomerPrincipal(
                    accountId = accountId,
                    customerId = resultSet.getObject("customer_id", UUID::class.java),
                    email = resultSet.getString("email_normalized"),
                    displayName = resultSet.getString("name"),
                    verifiedAt = resultSet.getTimestamp("verified_at").toInstant(),
                    companyName = resultSet.getString("company_name"),
                    credentialState = credentialState(resultSet.getString("password_hash")),
                    registrationState = registrationState(resultSet.getString("password_hash")),
                    availableAuthenticationMethods = authenticationMethods(resultSet.getString("password_hash")),
                ),
                credentialVersion = resultSet.getLong("credential_version"),
            )
        },
        emailNormalized,
    ).singleOrNull()

    private fun magicLinkAccountState(emailNormalized: String): CustomerMagicLinkAccountState? = jdbcTemplate.query(
        """
        select status, password_hash is not null as has_password
          from customer_accounts
         where email_normalized = ?
        """.trimIndent(),
        { resultSet, _ ->
            CustomerMagicLinkAccountState(
                status = resultSet.getString("status"),
                hasPassword = resultSet.getBoolean("has_password"),
            )
        },
        emailNormalized,
    ).singleOrNull()

    private fun credentialState(passwordHash: String?): CustomerCredentialState =
        if (passwordHash == null) CustomerCredentialState.PASSWORDLESS else CustomerCredentialState.PASSWORD

    private fun registrationState(passwordHash: String?): CustomerRegistrationState =
        if (passwordHash == null) CustomerRegistrationState.REGISTRATION_REQUIRED else CustomerRegistrationState.COMPLETE

    private fun authenticationMethods(passwordHash: String?): List<CustomerAuthenticationMethod> =
        listOf(if (passwordHash == null) CustomerAuthenticationMethod.MAGIC_LINK else CustomerAuthenticationMethod.PASSWORD)
}
