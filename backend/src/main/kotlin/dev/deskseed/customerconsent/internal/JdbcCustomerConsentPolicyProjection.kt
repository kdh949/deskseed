package dev.deskseed.customerconsent.internal

import dev.deskseed.customerconsent.CanonicalCustomerConsentDocumentCodec
import dev.deskseed.customerconsent.CurrentCustomerConsentPolicies
import dev.deskseed.customerconsent.CurrentCustomerConsentPolicy
import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentPolicyProjection
import dev.deskseed.customerconsent.CustomerConsentUnavailableException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet

@Service
internal class JdbcCustomerConsentPolicyProjection(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : CustomerConsentPolicyProjection {
    private val documents = CanonicalCustomerConsentDocumentCodec()

    @Transactional(readOnly = true)
    override fun current(context: CustomerConsentContext): CurrentCustomerConsentPolicies = failClosed {
        val policies = jdbc.query(
            """
            select policy.id, policy.policy_key, version.version, version.title,
                   version.document_json::text, version.plain_text, version.checksum_sha256,
                   version.required, version.display_order, version.effective_at, version.published_at
              from customer_consent_policies policy
              join customer_consent_policy_versions version
                on version.policy_id = policy.id and version.version = policy.published_version
             where policy.context = ?
               and policy.lifecycle = 'PUBLISHED'
               and policy.published_version is not null
             order by version.display_order, policy.policy_key, policy.id
             limit 21
            """.trimIndent(),
            ::policy,
            context.name,
        )
        check(policies.size <= MAX_CURRENT_POLICIES) { "Current customer consent policy limit is inconsistent" }
        CurrentCustomerConsentPolicies(context, policies)
    }

    private fun policy(result: ResultSet, row: Int): CurrentCustomerConsentPolicy {
        val policyKey = result.getString("policy_key")
        val title = result.getString("title")
        val document = documents.decode(objectMapper.readTree(result.getString("document_json")))
        val validated = documents.validateForPublish(document)
        val checksum = result.getString("checksum_sha256")
        check(policyKey.matches(POLICY_KEY)) { "Published customer consent policy key is invalid" }
        check(title.isNotBlank() && title.length <= 200 && title.none(Char::isISOControl) && '<' !in title && '>' !in title) {
            "Published customer consent policy title is invalid"
        }
        check(result.getString("plain_text") == validated.plainText) {
            "Published customer consent policy plain text is inconsistent"
        }
        check(checksum == validated.checksumSha256) {
            "Published customer consent policy checksum is inconsistent"
        }
        val effectiveAt = result.getTimestamp("effective_at").toInstant()
        check(effectiveAt == result.getTimestamp("published_at").toInstant()) {
            "Published customer consent policy effective time is inconsistent"
        }
        return CurrentCustomerConsentPolicy(
            result.getObject("id", java.util.UUID::class.java),
            policyKey,
            result.getInt("version"),
            title,
            validated.document,
            checksum,
            result.getBoolean("required"),
            result.getInt("display_order"),
            effectiveAt,
        )
    }

    private fun <T> failClosed(action: () -> T): T = try {
        action()
    } catch (failure: CustomerConsentUnavailableException) {
        throw failure
    } catch (failure: RuntimeException) {
        throw CustomerConsentUnavailableException(failure)
    }

    private companion object {
        const val MAX_CURRENT_POLICIES = 20
        val POLICY_KEY = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
    }
}
