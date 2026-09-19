package dev.deskseed.ticketing.internal

import dev.deskseed.foundation.ServiceVersionMetrics
import dev.deskseed.ticketing.StaffTicketSearchFilter
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties
import java.util.UUID

/** Load-only CLI. It never prints the selected query or SQL and refuses non-load environments. */
internal object SearchPlanCapture {
    private val mapper = JsonMapper.builder().build()

    @JvmStatic
    fun main(arguments: Array<String>) {
        val args = Arguments.parse(arguments)
        require(args.environment == "load") {
            "SearchPlanCapture only runs against an explicitly identified load environment"
        }
        require(ServiceVersionMetrics.DEPLOYMENT_SHA.matches(args.deploymentSha)) {
            "--deployment-sha must be a 40-character lowercase hexadecimal revision"
        }
        val corpusBytes = Files.readAllBytes(args.corpus)
        val corpus = mapper.readTree(corpusBytes)
        require(corpus.path("synthetic").asBoolean(false) || corpus.has("sourceManifestSha256")) {
            "Only a synthetic search corpus with source provenance is supported"
        }
        val selected = selectCase(corpus, args.caseId)
        val now = Instant.now()
        val plan = StaffTicketSearchSqlPlanFactory().build(
            query = selected.query,
            actorId = args.actorId,
            filters = StaffTicketSearchFilter(null, null, null, null, null),
            sort = STAFF_SEARCH_SCORE_SORT,
            snapshotAt = now,
            cursor = null,
            limit = 26,
            now = now,
        )
        val sql = when (args.family) {
            "count" -> plan.countSql
            "page" -> plan.pageSql
            else -> error("Validated by argument parser")
        }
        val jdbcUrl = requiredEnvironment("DESKSEED_PLAN_CAPTURE_DATABASE_URL")
        require(jdbcUrl.startsWith("jdbc:postgresql:")) { "Only PostgreSQL JDBC URLs are supported" }
        val properties = Properties().apply {
            setProperty("user", requiredEnvironment("DESKSEED_PLAN_CAPTURE_DATABASE_USER"))
            setProperty("password", requiredEnvironment("DESKSEED_PLAN_CAPTURE_DATABASE_PASSWORD"))
            setProperty("ApplicationName", "deskseed-search-plan-capture")
        }
        val captured = DriverManager.getConnection(jdbcUrl, properties).use { connection ->
            connection.isReadOnly = true
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("set local statement_timeout = '60s'")
                statement.execute("set local lock_timeout = '2s'")
            }
            val jdbc = NamedParameterJdbcTemplate(SingleConnectionDataSource(connection, true))
            val json = jdbc.query(
                "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) $sql",
                plan.parameters,
            ) { result, _ -> result.getString(1) }.single()
            connection.rollback()
            CapturedPlan(json, connection.metaData.databaseProductVersion)
        }
        val document = mapper.readTree(captured.json)
        val top = document.path(0)
        val queryId = top.path("Query Identifier").takeUnless(JsonNode::isMissingNode)?.asText()
            ?.takeUnless { it.isBlank() }
            ?: "unavailable"
        Files.createDirectories(args.output)
        val artifact = args.output.resolve("queryid-${sanitize(queryId)}-${args.deploymentSha}-${sanitize(args.caseId)}-${args.family}")
        Files.createDirectory(artifact)
        Files.writeString(artifact.resolve("plan.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n")
        val metadata = mapper.createObjectNode().apply {
            put("version", 1)
            put("capturedAt", now.toString())
            put("environment", args.environment)
            put("deploymentSha", args.deploymentSha)
            put("corpusCaseId", args.caseId)
            put("queryClass", selected.queryClass)
            put("queryFamily", args.family)
            put("querySummary", "deskseed.staff_ticket_search.${args.family}")
            put("queryId", queryId)
            put("corpusSha256", sha256(corpusBytes))
            put("sourceManifestSha256", corpus.path("sourceManifestSha256").asText("unavailable"))
            put("databaseVersion", captured.databaseVersion)
            put("containsProtectedSyntheticPlanLiterals", true)
        }
        Files.writeString(artifact.resolve("metadata.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata) + "\n")
        Files.writeString(artifact.resolve("summary.md"), summary(top, metadata), StandardCharsets.UTF_8)
        updateIndex(args.output, artifact.fileName.toString(), metadata)
        println("Captured protected ${args.family} plan artifact at $artifact (queryid=$queryId)")
    }

    private fun selectCase(corpus: JsonNode, caseId: String): SelectedCase {
        val separator = caseId.lastIndexOf(':')
        require(separator in 1 until caseId.lastIndex) { "--case-id must use <query-class>:<zero-based-index>" }
        val queryClass = caseId.substring(0, separator)
        val index = caseId.substring(separator + 1).toIntOrNull()
            ?: throw IllegalArgumentException("--case-id index must be an integer")
        val group = corpus.path("groups").firstOrNull { it.path("queryClass").asText() == queryClass }
            ?: throw IllegalArgumentException("Unknown corpus query class")
        val queries = group.path("queries")
        require(index in 0 until queries.size()) { "Corpus case index is out of range" }
        return SelectedCase(queryClass, queries.path(index).asText())
    }

    private fun summary(top: JsonNode, metadata: JsonNode): String {
        val plan = top.path("Plan")
        return """
            # Deskseed search plan capture

            - query family: `${metadata.path("queryFamily").asText()}`
            - query summary: `${metadata.path("querySummary").asText()}`
            - queryid: `${metadata.path("queryId").asText()}`
            - deployment SHA: `${metadata.path("deploymentSha").asText()}`
            - corpus case: `${metadata.path("corpusCaseId").asText()}` (raw query intentionally omitted)
            - corpus SHA-256: `${metadata.path("corpusSha256").asText()}`
            - root node: `${plan.path("Node Type").asText("unavailable")}`
            - planning time: `${top.path("Planning Time").asText("unavailable")}` ms
            - execution time: `${top.path("Execution Time").asText("unavailable")}` ms
            - actual rows: `${plan.path("Actual Rows").asText("unavailable")}`
            - shared hit/read blocks: `${plan.path("Shared Hit Blocks").asText("unavailable")}` / `${plan.path("Shared Read Blocks").asText("unavailable")}`
            - temp read/written blocks: `${plan.path("Temp Read Blocks").asText("unavailable")}` / `${plan.path("Temp Written Blocks").asText("unavailable")}`

            `plan.json` is a protected diagnostic artifact and may contain synthetic literal values emitted by PostgreSQL.
        """.trimIndent() + "\n"
    }

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$name is required")

    private fun sanitize(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "-")
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun updateIndex(output: Path, artifactName: String, metadata: JsonNode) {
        val indexPath = output.resolve("index.json")
        val entries = if (Files.exists(indexPath)) {
            mapper.readTree(Files.readAllBytes(indexPath)) as? ArrayNode
                ?: throw IllegalArgumentException("Existing plan artifact index must be a JSON array")
        } else {
            mapper.createArrayNode()
        }
        entries.add(mapper.createObjectNode().apply {
            put("queryId", metadata.path("queryId").asText())
            put("queryFamily", metadata.path("queryFamily").asText())
            put("querySummary", metadata.path("querySummary").asText())
            put("deploymentSha", metadata.path("deploymentSha").asText())
            put("corpusCaseId", metadata.path("corpusCaseId").asText())
            put("capturedAt", metadata.path("capturedAt").asText())
            put("summary", "$artifactName/summary.md")
            put("metadata", "$artifactName/metadata.json")
        })
        Files.writeString(indexPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries) + "\n")
        Files.writeString(output.resolve("index.html"), INDEX_HTML)
    }

    private data class SelectedCase(val queryClass: String, val query: String)
    private data class CapturedPlan(val json: String, val databaseVersion: String)

    private data class Arguments(
        val corpus: Path,
        val caseId: String,
        val family: String,
        val deploymentSha: String,
        val output: Path,
        val actorId: UUID,
        val environment: String,
    ) {
        companion object {
            fun parse(values: Array<String>): Arguments {
                val pairs = values.asList().chunked(2).associate { pair ->
                    require(pair.size == 2 && pair[0].startsWith("--")) { "Arguments must be --name value pairs" }
                    pair[0] to pair[1]
                }
                fun required(name: String) = pairs[name]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("$name is required")
                val family = required("--family")
                require(family in setOf("count", "page")) { "--family must be count or page" }
                return Arguments(
                    corpus = Path.of(required("--corpus")),
                    caseId = required("--case-id"),
                    family = family,
                    deploymentSha = required("--deployment-sha"),
                    output = Path.of(required("--output")),
                    actorId = UUID.fromString(required("--actor-id")),
                    environment = required("--environment"),
                )
            }
        }
    }

    private const val INDEX_HTML = """<!doctype html>
<html lang="en"><meta charset="utf-8"><title>Deskseed protected plan artifacts</title>
<body><h1>Deskseed protected plan artifacts</h1><p>Protected synthetic load evidence. Raw queries are intentionally omitted.</p><table><thead><tr><th>queryid</th><th>family</th><th>revision</th><th>case</th><th>captured</th><th>artifact</th></tr></thead><tbody id="rows"></tbody></table>
<script>
const wanted = new URLSearchParams(location.search).get('queryid');
fetch('index.json').then(r => r.json()).then(entries => {
  const rows = document.getElementById('rows');
  entries.filter(e => !wanted || e.queryId === wanted).forEach(e => {
    const row = document.createElement('tr');
    [e.queryId,e.queryFamily,e.deploymentSha,e.corpusCaseId,e.capturedAt].forEach(value => { const cell=document.createElement('td'); cell.textContent=value; row.appendChild(cell); });
    const cell=document.createElement('td'); const link=document.createElement('a'); link.href=e.summary; link.textContent='summary'; cell.appendChild(link); row.appendChild(cell); rows.appendChild(row);
  });
});
</script></body></html>
"""
}
