package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiBackendRequestStatus
import dev.deskseed.aiassistance.AiCitation
import dev.deskseed.aiassistance.AiExecutionStatusReader
import dev.deskseed.aiassistance.AiGenerationProvenance
import dev.deskseed.aiassistance.AiJobReceipt
import dev.deskseed.aiassistance.AiReplyDraftResult
import dev.deskseed.aiassistance.AiReplyRewriteResult
import dev.deskseed.aiassistance.AiSummaryResult
import dev.deskseed.aiassistance.AiTriageResult
import dev.deskseed.aiassistance.AiTypedResult
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
internal class AiExecutionStatusClient(
    private val properties: AiIntegrationProperties,
    private val objectMapper: ObjectMapper,
) : AiExecutionStatusReader {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    override fun read(jobId: UUID, includeResult: Boolean): AiJobReceipt? {
        if (!properties.enabled) return null
        return try {
            properties.validate()
            val request = HttpRequest.newBuilder(
                URI.create(properties.baseUrl.trimEnd('/') + "/internal/v1/jobs/$jobId?includeResult=$includeResult"),
            )
                .timeout(Duration.ofMillis(properties.readTimeoutMillis))
                .header("Accept", "application/json")
                .header("X-Deskseed-AI-Key-Id", properties.keyId)
                .header("Authorization", "Bearer ${properties.secret}")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            response.body().takeIf { it.length <= MAX_RESPONSE_BYTES }?.let(::decode)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun decode(payload: String): AiJobReceipt {
        val node = objectMapper.readTree(payload)
        val result = node.get("result")?.takeUnless(JsonNode::isNull)?.let(::typedResult)
        return AiJobReceipt(
            jobId = node.uuid("jobId"),
            feature = node.text("feature"),
            status = AiBackendRequestStatus.valueOf(node.text("status")),
            requestRevision = node.long("requestRevision"),
            createdAt = node.instant("createdAt"),
            deadlineAt = node.instant("deadlineAt"),
            pollAfterMs = node.long("pollAfterMs"),
            cancelRequested = node.boolean("cancelRequested"),
            sourceJobId = node.nullableText("sourceJobId")?.let(UUID::fromString),
            inputScope = node.text("inputScope"),
            contextRevision = node.text("contextRevision"),
            contextPolicyVersion = node.text("contextPolicyVersion"),
            phase = node.text("phase"),
            generation = node.get("generation")?.takeUnless(JsonNode::isNull)?.intValue(),
            leaseEpoch = node.get("leaseEpoch")?.takeUnless(JsonNode::isNull)?.longValue(),
            completedAt = node.nullableInstant("completedAt"),
            resultExpiresAt = node.nullableInstant("resultExpiresAt"),
            stale = node.boolean("stale"),
            canInsert = node.boolean("canInsert"),
            errorCode = node.nullableText("errorCode"),
            result = result,
            provenance = node.get("provenance")?.takeUnless(JsonNode::isNull)?.let { provenance ->
                AiGenerationProvenance(
                    modelAlias = provenance.text("modelAlias"),
                    actualModel = provenance.text("actualModel"),
                    promptVersion = provenance.text("promptVersion"),
                    configVersion = provenance.text("configVersion"),
                    generatedAt = provenance.instant("generatedAt"),
                    publicCommentIds = provenance.get("publicCommentIds")?.takeIf(JsonNode::isArray)?.values()
                        ?.map { UUID.fromString(it.stringValue()) }?.take(500)
                        ?: error("Missing publicCommentIds"),
                    contextRevision = provenance.text("contextRevision"),
                )
            },
            costMicrousd = node.get("costMicrousd")?.takeUnless(JsonNode::isNull)?.longValue(),
            generationMode = node.nullableText("generationMode"),
            candidateSequence = node.get("candidateSequence")?.takeUnless(JsonNode::isNull)?.intValue(),
            reuseKind = node.nullableText("reuseKind"),
            providerDispatched = node.get("providerDispatched")?.booleanValue() ?: false,
        )
    }

    private fun typedResult(node: JsonNode): AiTypedResult = when (node.text("type")) {
        "ticket.summary" -> AiSummaryResult(
            problem = node.text("problem"),
            attemptedActions = node.stringList("attemptedActions"),
            unresolvedItems = node.stringList("unresolvedItems"),
            nextChecks = node.stringList("nextChecks"),
        )
        "ticket.triage" -> AiTriageResult(
            topicCode = node.text("topicCode"),
            suggestedTagIds = node.get("suggestedTagIds")?.takeIf(JsonNode::isArray)?.values()
                ?.map { UUID.fromString(it.stringValue()) }?.take(20) ?: error("Missing suggestedTagIds"),
            suggestedPriority = node.nullableText("suggestedPriority"),
            reasons = node.stringList("reasons"),
        )
        "ticket.reply_draft" -> AiReplyDraftResult(
            answer = node.text("answer"),
            citations = node.get("citations")?.takeIf(JsonNode::isArray)?.values()?.map { citation ->
                AiCitation(
                    articleId = citation.uuid("articleId"),
                    revisionId = citation.uuid("revisionId"),
                    chunkId = citation.uuid("chunkId"),
                    title = citation.text("title"),
                    url = citation.text("url"),
                )
            } ?: error("Missing citations"),
        )
        "ticket.reply_rewrite" -> AiReplyRewriteResult(
            answer = node.text("answer"),
            citations = node.get("citations")?.takeIf(JsonNode::isArray)?.values()?.map { citation ->
                AiCitation(
                    articleId = citation.uuid("articleId"),
                    revisionId = citation.uuid("revisionId"),
                    chunkId = citation.uuid("chunkId"),
                    title = citation.text("title"),
                    url = citation.text("url"),
                )
            } ?: error("Missing citations"),
            language = node.text("language"),
            tone = node.text("tone"),
            length = node.text("length"),
        )
        else -> error("Unsupported typed AI result")
    }

    private fun JsonNode.text(name: String): String = get(name)?.takeUnless(JsonNode::isNull)?.stringValue()
        ?.takeIf { it.length <= 10_000 }
        ?: error("Missing or oversized $name")

    private fun JsonNode.nullableText(name: String): String? = get(name)?.takeUnless(JsonNode::isNull)?.stringValue()
        ?.takeIf { it.length <= 1_000 }

    private fun JsonNode.long(name: String): Long = get(name)?.longValue() ?: error("Missing $name")
    private fun JsonNode.boolean(name: String): Boolean = get(name)?.booleanValue() ?: error("Missing $name")
    private fun JsonNode.uuid(name: String): UUID = UUID.fromString(text(name))
    private fun JsonNode.instant(name: String): Instant = Instant.parse(text(name))
    private fun JsonNode.nullableInstant(name: String): Instant? = nullableText(name)?.let(Instant::parse)
    private fun JsonNode.stringList(name: String): List<String> = get(name)?.takeIf(JsonNode::isArray)
        ?.values()?.map { it.stringValue().take(2_000) }
        ?.take(20)
        ?: error("Missing $name")

    private companion object {
        const val MAX_RESPONSE_BYTES = 128 * 1024
    }
}
