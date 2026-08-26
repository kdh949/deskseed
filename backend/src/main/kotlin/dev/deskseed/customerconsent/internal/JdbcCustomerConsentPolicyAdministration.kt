package dev.deskseed.customerconsent.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.customerconsent.CanonicalCustomerConsentDocumentCodec
import dev.deskseed.customerconsent.CreateCustomerConsentPolicy
import dev.deskseed.customerconsent.CustomerConsentConflictException
import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentLifecycle
import dev.deskseed.customerconsent.CustomerConsentNotFoundException
import dev.deskseed.customerconsent.CustomerConsentPolicyActor
import dev.deskseed.customerconsent.CustomerConsentPolicyAdministration
import dev.deskseed.customerconsent.CustomerConsentPolicyDetail
import dev.deskseed.customerconsent.CustomerConsentPolicyDraft
import dev.deskseed.customerconsent.CustomerConsentPolicyDraftInput
import dev.deskseed.customerconsent.CustomerConsentPolicyPage
import dev.deskseed.customerconsent.CustomerConsentPolicySummary
import dev.deskseed.customerconsent.CustomerConsentPolicyVersion
import dev.deskseed.customerconsent.CustomerConsentPreconditionFailedException
import dev.deskseed.customerconsent.CustomerConsentUnavailableException
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.StaffAuthorityCatalog
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcCustomerConsentPolicyAdministration(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) : CustomerConsentPolicyAdministration {
    private val documents = CanonicalCustomerConsentDocumentCodec()

    @Transactional(readOnly = true)
    override fun list(
        context: CustomerConsentContext?,
        lifecycle: CustomerConsentLifecycle?,
        page: Int,
        size: Int,
        actor: CustomerConsentPolicyActor,
    ): CustomerConsentPolicyPage {
        requireAccess(actor)
        if (page < 0 || size !in 1..100) throw IllegalArgumentException("Customer consent page is invalid")
        val where = mutableListOf<String>()
        val arguments = mutableListOf<Any>()
        context?.let { where += "context = ?"; arguments += it.name }
        lifecycle?.let { where += "lifecycle = ?"; arguments += it.name }
        val predicate = if (where.isEmpty()) "" else " where ${where.joinToString(" and ")}"
        val total = jdbc.queryForObject(
            "select count(*) from customer_consent_policies$predicate",
            Long::class.java,
            *arguments.toTypedArray(),
        ) ?: 0
        val offset = Math.multiplyExact(page.toLong(), size.toLong())
        val items = jdbc.query(
            "$SUMMARY_SELECT$predicate order by updated_at desc, id limit ? offset ?",
            ::summary,
            *(arguments + size + offset).toTypedArray(),
        )
        val totalPages = if (total == 0L) 0 else Math.toIntExact((total + size - 1) / size)
        return CustomerConsentPolicyPage(items, page, size, total, totalPages)
    }

    @Transactional
    override fun create(
        command: CreateCustomerConsentPolicy,
        actor: CustomerConsentPolicyActor,
    ): CustomerConsentPolicyDetail = translateStorageFailure {
        requireAccess(actor)
        val validated = documents.validateDraft(command.draft.document)
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                insert into customer_consent_policies (
                    id, policy_key, context, lifecycle, draft_title, draft_document_json, draft_plain_text,
                    draft_checksum_sha256, draft_required, draft_display_order, draft_version,
                    published_version, aggregate_version, created_at, updated_at
                ) values (?, ?, ?, 'DRAFT', ?, cast(? as jsonb), ?, ?, ?, ?, 1, null, 0, ?, ?)
                """.trimIndent(),
                id, command.policyKey, command.context.name, command.draft.title.trim(), documentJson(validated.document),
                validated.plainText, validated.checksumSha256, command.draft.required, command.draft.displayOrder,
                Timestamp.from(now), Timestamp.from(now),
            )
        } catch (_: DataIntegrityViolationException) {
            throw CustomerConsentConflictException("POLICY_KEY_CONTEXT_EXISTS")
        }
        audit(
            "CUSTOMER_CONSENT_POLICY_CREATED", id, command.policyKey, command.context, 1,
            validated.checksumSha256, actor, now,
        )
        detail(stateById(id))
    }

    @Transactional(readOnly = true)
    override fun get(policyId: UUID, actor: CustomerConsentPolicyActor): CustomerConsentPolicyDetail {
        requireAccess(actor)
        return detail(stateById(policyId))
    }

    @Transactional
    override fun updateDraft(
        policyId: UUID,
        expectedAggregateVersion: Long,
        draft: CustomerConsentPolicyDraftInput,
        actor: CustomerConsentPolicyActor,
    ): CustomerConsentPolicyDetail = translateStorageFailure {
        requireAccess(actor)
        val validated = documents.validateDraft(draft.document)
        val current = lockedState(policyId)
        requireExpected(current, expectedAggregateVersion)
        if (current.lifecycle == CustomerConsentLifecycle.ARCHIVED) {
            throw CustomerConsentConflictException("POLICY_ARCHIVED")
        }
        val nextDraftVersion = current.draft.version + 1
        val now = Instant.now(clock)
        jdbc.update(
            """
            update customer_consent_policies
               set draft_title = ?, draft_document_json = cast(? as jsonb), draft_plain_text = ?,
                   draft_checksum_sha256 = ?, draft_required = ?, draft_display_order = ?, draft_version = ?,
                   aggregate_version = aggregate_version + 1, updated_at = ?
             where id = ?
            """.trimIndent(),
            draft.title.trim(), documentJson(validated.document), validated.plainText, validated.checksumSha256,
            draft.required, draft.displayOrder, nextDraftVersion, Timestamp.from(now), policyId,
        )
        audit(
            "CUSTOMER_CONSENT_POLICY_DRAFT_UPDATED", policyId, current.policyKey, current.context,
            nextDraftVersion, validated.checksumSha256, actor, now,
        )
        detail(stateById(policyId))
    }

    @Transactional
    override fun publish(
        policyId: UUID,
        expectedAggregateVersion: Long,
        actor: CustomerConsentPolicyActor,
    ): CustomerConsentPolicyDetail = translateStorageFailure {
        requireAccess(actor)
        val current = lockedState(policyId)
        requireExpected(current, expectedAggregateVersion)
        if (current.lifecycle == CustomerConsentLifecycle.ARCHIVED) {
            throw CustomerConsentConflictException("POLICY_ARCHIVED")
        }
        val validated = documents.validateForPublish(current.draft.document)
        lockContext(current.context)
        if (current.lifecycle != CustomerConsentLifecycle.PUBLISHED && currentPublishedCount(current.context) >= MAX_CURRENT_POLICIES) {
            throw CustomerConsentConflictException("CURRENT_POLICY_LIMIT_REACHED")
        }
        val version = nextPublishedVersion(policyId)
        if (version > MAX_PUBLISHED_VERSIONS) {
            throw CustomerConsentConflictException("POLICY_VERSION_LIMIT_REACHED")
        }
        val now = Instant.now(clock)
        jdbc.update(
            """
            insert into customer_consent_policy_versions (
                policy_id, version, title, document_json, plain_text, checksum_sha256, required,
                display_order, effective_at, published_by_staff_id, published_by_display, published_at
            ) values (?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            policyId, version, current.draft.title, documentJson(validated.document), validated.plainText,
            validated.checksumSha256, current.draft.required, current.draft.displayOrder, Timestamp.from(now),
            actor.staffId, actor.displayName.trim(), Timestamp.from(now),
        )
        jdbc.update(
            """
            update customer_consent_policies
               set lifecycle = 'PUBLISHED', published_version = ?, aggregate_version = aggregate_version + 1
             where id = ?
            """.trimIndent(),
            version, policyId,
        )
        audit(
            "CUSTOMER_CONSENT_POLICY_PUBLISHED", policyId, current.policyKey, current.context,
            version, validated.checksumSha256, actor, now,
        )
        detail(stateById(policyId))
    }

    @Transactional
    override fun archive(
        policyId: UUID,
        expectedAggregateVersion: Long,
        actor: CustomerConsentPolicyActor,
    ): CustomerConsentPolicyDetail = translateStorageFailure {
        requireAccess(actor)
        val current = lockedState(policyId)
        requireExpected(current, expectedAggregateVersion)
        if (current.lifecycle != CustomerConsentLifecycle.PUBLISHED) {
            throw CustomerConsentConflictException("POLICY_NOT_PUBLISHED")
        }
        lockContext(current.context)
        val publishedVersion = requireNotNull(current.publishedVersion)
        val publishedChecksum = publishedChecksum(policyId, publishedVersion)
        val now = Instant.now(clock)
        jdbc.update(
            """
            update customer_consent_policies
               set lifecycle = 'ARCHIVED', aggregate_version = aggregate_version + 1
             where id = ?
            """.trimIndent(),
            policyId,
        )
        audit(
            "CUSTOMER_CONSENT_POLICY_ARCHIVED", policyId, current.policyKey, current.context,
            publishedVersion, publishedChecksum, actor, now,
        )
        detail(stateById(policyId))
    }

    private fun detail(state: PolicyState): CustomerConsentPolicyDetail {
        val versions = versions(state)
        return CustomerConsentPolicyDetail(
            state.id, state.policyKey, state.context, state.lifecycle, state.aggregateVersion, state.draft,
            versions.singleOrNull { it.version == state.publishedVersion }, versions, state.createdAt, state.updatedAt,
        )
    }

    private fun versions(state: PolicyState): List<CustomerConsentPolicyVersion> = jdbc.query(
        """
        select version, title, document_json::text, plain_text, checksum_sha256, required, display_order,
               effective_at, published_by_staff_id, published_by_display, published_at
          from customer_consent_policy_versions
         where policy_id = ?
         order by version
         limit 200
        """.trimIndent(),
        { result, _ -> version(state, result) },
        state.id,
    )

    private fun version(state: PolicyState, result: ResultSet) = CustomerConsentPolicyVersion(
        state.id,
        result.getInt("version"),
        result.getString("title"),
        documents.decode(objectMapper.readTree(result.getString("document_json"))),
        result.getString("plain_text"),
        result.getString("checksum_sha256"),
        result.getBoolean("required"),
        result.getInt("display_order"),
        result.getTimestamp("effective_at").toInstant(),
        result.getObject("published_by_staff_id", UUID::class.java),
        result.getString("published_by_display"),
        result.getTimestamp("published_at").toInstant(),
    )

    private fun summary(result: ResultSet, row: Int) = CustomerConsentPolicySummary(
        result.getObject("id", UUID::class.java),
        result.getString("policy_key"),
        CustomerConsentContext.valueOf(result.getString("context")),
        CustomerConsentLifecycle.valueOf(result.getString("lifecycle")),
        result.getLong("aggregate_version"),
        (result.getObject("published_version") as? Number)?.toInt(),
        result.getBoolean("draft_required"),
        result.getInt("draft_display_order"),
        result.getTimestamp("created_at").toInstant(),
        result.getTimestamp("updated_at").toInstant(),
    )

    private fun stateById(id: UUID): PolicyState = jdbc.query("$STATE_SELECT where id = ?", ::state, id)
        .singleOrNull() ?: throw CustomerConsentNotFoundException()

    private fun lockedState(id: UUID): PolicyState = jdbc.query("$STATE_SELECT where id = ? for update", ::state, id)
        .singleOrNull() ?: throw CustomerConsentNotFoundException()

    private fun state(result: ResultSet, row: Int) = PolicyState(
        result.getObject("id", UUID::class.java),
        result.getString("policy_key"),
        CustomerConsentContext.valueOf(result.getString("context")),
        CustomerConsentLifecycle.valueOf(result.getString("lifecycle")),
        CustomerConsentPolicyDraft(
            result.getString("draft_title"),
            documents.decode(objectMapper.readTree(result.getString("draft_document_json"))),
            result.getString("draft_plain_text"),
            result.getString("draft_checksum_sha256"),
            result.getBoolean("draft_required"),
            result.getInt("draft_display_order"),
            result.getInt("draft_version"),
        ),
        (result.getObject("published_version") as? Number)?.toInt(),
        result.getLong("aggregate_version"),
        result.getTimestamp("created_at").toInstant(),
        result.getTimestamp("updated_at").toInstant(),
    )

    private fun nextPublishedVersion(policyId: UUID): Int = jdbc.queryForObject(
        "select coalesce(max(version), 0) + 1 from customer_consent_policy_versions where policy_id = ?",
        Int::class.java,
        policyId,
    ) ?: 1

    private fun currentPublishedCount(context: CustomerConsentContext): Long = jdbc.queryForObject(
        "select count(*) from customer_consent_policies where context = ? and lifecycle = 'PUBLISHED'",
        Long::class.java,
        context.name,
    ) ?: 0

    private fun publishedChecksum(policyId: UUID, version: Int): String = jdbc.queryForObject(
        "select checksum_sha256 from customer_consent_policy_versions where policy_id = ? and version = ?",
        String::class.java,
        policyId,
        version,
    ) ?: throw CustomerConsentNotFoundException()

    private fun lockContext(context: CustomerConsentContext) {
        jdbc.query(
            "select pg_advisory_xact_lock(?)",
            { _, _ -> Unit },
            if (context == CustomerConsentContext.REGISTRATION) REGISTRATION_LOCK else REQUEST_SUBMISSION_LOCK,
        )
    }

    private fun requireExpected(current: PolicyState, expected: Long) {
        if (current.aggregateVersion != expected) {
            throw CustomerConsentPreconditionFailedException(current.aggregateVersion)
        }
    }

    private fun requireAccess(actor: CustomerConsentPolicyActor) {
        if (
            !actor.isAdmin ||
            StaffAuthorityCatalog.CUSTOMER_CONSENT_MANAGE !in actor.authorities ||
            actor.source != RequestSource.ADMIN_UI
        ) {
            throw AccessDeniedException("Customer consent management requires its explicit active admin capability")
        }
    }

    private fun audit(
        eventType: String,
        policyId: UUID,
        policyKey: String,
        context: CustomerConsentContext,
        version: Int,
        checksum: String,
        actor: CustomerConsentPolicyActor,
        now: Instant,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType, ActorType.STAFF, actor.staffId, actor.displayName, actor.source,
                "CUSTOMER_CONSENT_POLICY", policyId, AdminSecurityOutcome.SUCCEEDED,
                actor.requestId, actor.correlationId,
                mapOf(
                    "policyKey" to policyKey,
                    "context" to context.name,
                    "version" to version.toString(),
                    "checksumSha256" to checksum,
                ),
                now,
            ),
        )
    }

    private fun documentJson(document: dev.deskseed.knowledge.CanonicalKnowledgeDocument): String =
        objectMapper.writeValueAsString(documents.encode(document))

    private fun <T> translateStorageFailure(block: () -> T): T = try {
        block()
    } catch (failure: DataAccessException) {
        throw CustomerConsentUnavailableException(failure)
    }

    private data class PolicyState(
        val id: UUID,
        val policyKey: String,
        val context: CustomerConsentContext,
        val lifecycle: CustomerConsentLifecycle,
        val draft: CustomerConsentPolicyDraft,
        val publishedVersion: Int?,
        val aggregateVersion: Long,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private companion object {
        const val MAX_CURRENT_POLICIES = 20
        const val MAX_PUBLISHED_VERSIONS = 200
        const val REGISTRATION_LOCK = 1_067_539_001L
        const val REQUEST_SUBMISSION_LOCK = 1_067_539_002L
        const val SUMMARY_SELECT = """
            select id, policy_key, context, lifecycle, aggregate_version, published_version,
                   draft_required, draft_display_order, created_at, updated_at
              from customer_consent_policies
        """
        const val STATE_SELECT = """
            select id, policy_key, context, lifecycle, draft_title, draft_document_json::text,
                   draft_plain_text, draft_checksum_sha256, draft_required, draft_display_order,
                   draft_version, published_version, aggregate_version, created_at, updated_at
              from customer_consent_policies
        """
    }
}
