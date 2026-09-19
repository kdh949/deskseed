package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiAuditUnavailableException
import dev.deskseed.aiassistance.AiRequestMetadata
import dev.deskseed.audit.AiKnowledgeAccessAudit
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.knowledge.AiKnowledgeProjection
import dev.deskseed.knowledge.AiPublicKnowledgeArticle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class AiKnowledgeAuthorizationServiceTest {
    @Test
    fun `authorization keeps current PUBLIC candidates in request order and skips stale revisions`() {
        val projection = mock(AiKnowledgeProjection::class.java)
        val audits = mutableListOf<AiKnowledgeAccessAudit>()
        val audit = auditWriter { audits += it }
        val service = service(projection, audit)
        val first = candidate()
        val stale = candidate()
        val second = candidate()
        `when`(projection.findCurrentPublic(first.articleId, first.revisionId)).thenReturn(article(first, "first"))
        `when`(projection.findCurrentPublic(stale.articleId, stale.revisionId)).thenReturn(null)
        `when`(projection.findCurrentPublic(second.articleId, second.revisionId)).thenReturn(article(second, "second"))

        val approved = service.authorize(principal(), UUID.randomUUID(), listOf(first, stale, second), metadata())

        assertThat(approved.map { it.first.chunkId }).containsExactly(first.chunkId, second.chunkId)
        assertThat(approved.map { it.second.slug }).containsExactly("first", "second")
        assertThat(audits.map { it.articleId }).containsExactly(first.articleId, second.articleId)
    }

    @Test
    fun `required audit failure withholds every authorization response`() {
        val projection = mock(AiKnowledgeProjection::class.java)
        val audit = auditWriter { throw DataAccessResourceFailureException("forced audit failure") }
        val current = candidate()
        `when`(projection.findCurrentPublic(current.articleId, current.revisionId)).thenReturn(article(current, "current"))

        assertThatThrownBy {
            service(projection, audit).authorize(principal(), UUID.randomUUID(), listOf(current), metadata())
        }.isInstanceOf(AiAuditUnavailableException::class.java)
    }

    @Test
    fun `duplicate candidate identity fails closed before audit`() {
        val projection = mock(AiKnowledgeProjection::class.java)
        val audits = mutableListOf<AiKnowledgeAccessAudit>()
        val audit = auditWriter { audits += it }
        val current = candidate()

        assertThatThrownBy {
            service(projection, audit).authorize(principal(), UUID.randomUUID(), listOf(current, current), metadata())
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(audits).isEmpty()
    }

    private fun service(projection: AiKnowledgeProjection, audit: AccessAuditWriter) =
        AiKnowledgeSourceService(projection, audit, Clock.fixed(NOW, ZoneOffset.UTC))

    private fun candidate() = AiKnowledgeCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

    private fun article(candidate: AiKnowledgeCandidate, slug: String) = AiPublicKnowledgeArticle(
        articleId = candidate.articleId,
        revisionId = candidate.revisionId,
        slug = slug,
        title = "공개 도움말",
        categoryTitle = "고객 지원",
        sectionTitle = "결제",
        body = "공개 본문",
        sourceVersion = 1,
        publicRevision = "a".repeat(64),
        publishedAt = NOW,
    )

    private fun principal() = AiSourcePrincipal(
        id = UUID.randomUUID(),
        displayName = "Deskseed AI Service",
        scopes = setOf(AiSourceScope.JOB_SOURCE),
    )

    private fun metadata() = AiRequestMetadata(
        requestId = "req-s04",
        correlationId = "corr-s04",
        ipAddress = null,
        userAgent = null,
    )

    private fun auditWriter(onKnowledge: (AiKnowledgeAccessAudit) -> Unit): AccessAuditWriter =
        Proxy.newProxyInstance(
            AccessAuditWriter::class.java.classLoader,
            arrayOf(AccessAuditWriter::class.java),
        ) { _, method, arguments ->
            if (method.name != "appendAiKnowledgeAccess") error("Unexpected audit method ${method.name}")
            onKnowledge(arguments[0] as AiKnowledgeAccessAudit)
            null
        } as AccessAuditWriter

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-19T00:00:00Z")
    }
}
