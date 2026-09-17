package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiDependencyStatus
import dev.deskseed.aiassistance.AiOperationsStatusReader
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

@Component
internal class AiOperationsStatusClient(
    private val properties: AiIntegrationProperties,
    private val objectMapper: ObjectMapper,
) : AiOperationsStatusReader {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    override fun read(): AiDependencyStatus? {
        if (!properties.enabled) return null
        return try {
            properties.validate()
            val request = HttpRequest.newBuilder(URI.create(properties.baseUrl.trimEnd('/') + "/internal/v1/status"))
                .timeout(Duration.ofMillis(properties.readTimeoutMillis))
                .header("Accept", "application/json")
                .header("X-Deskseed-AI-Key-Id", properties.keyId)
                .header("Authorization", "Bearer ${properties.secret}")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200 || response.body().length > MAX_RESPONSE_BYTES) return null
            val node = objectMapper.readTree(response.body())
            val budget = node.get("budget") ?: return null
            val index = node.get("index") ?: return null
            AiDependencyStatus(
                ready = node.get("ready")?.booleanValue() ?: false,
                dataAsOf = Instant.parse(node.get("dataAsOf")?.stringValue()),
                jobCounts = node.get("jobCounts")?.properties()?.asSequence()?.associate { (name, value) ->
                    name to value.longValue()
                } ?: emptyMap(),
                budgetReservedMicrousd = budget.get("reservedMicrousd")?.longValue() ?: 0,
                budgetSettledMicrousd = budget.get("settledMicrousd")?.longValue() ?: 0,
                budgetUnknownMicrousd = budget.get("unknownMicrousd")?.longValue() ?: 0,
                indexedPublicRevisions = index.get("publicRevisions")?.longValue() ?: 0,
                knowledgeLastReconciledAt = index.get("lastReconciledAt")
                    ?.takeUnless { it.isNull }
                    ?.stringValue()
                    ?.let(Instant::parse),
                deadLetterCount = node.get("deadLetterCount")?.longValue() ?: 0,
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
