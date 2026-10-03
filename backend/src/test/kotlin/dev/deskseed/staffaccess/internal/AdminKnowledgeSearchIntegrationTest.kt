package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.dao.DataAccessException
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false", "deskseed.test.context-group=admin-knowledge-search"])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@dev.deskseed.testsupport.category.IntegrationTest
class AdminKnowledgeSearchIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: ObjectMapper
    private val password = "Knowledge fixture password 42"
    private val passwordHash = BCryptPasswordEncoder(4).encode(password)
    private lateinit var adminId: UUID
    private lateinit var sectionId: UUID
    private lateinit var browser: MockHttpSession
    @BeforeEach fun reset() {
        jdbc.execute("truncate table knowledge_categories, staff_accounts, support_groups, access_audit_events, admin_security_audit_events, audit_activity_projection cascade")
        adminId = staff("admin@example.test", "Knowledge admin", role="ADMIN")
        browser = login("admin@example.test")
        val category=UUID.randomUUID()
        sectionId=UUID.randomUUID()
        jdbc.update("insert into knowledge_categories (id,slug,title,status,display_order,created_at,updated_at) values (?,'help','Help','ACTIVE',0,now(),now())",category)
        jdbc.update("insert into knowledge_sections (id,category_id,slug,title,status,display_order,created_at,updated_at) values (?,?,'billing','Billing','ACTIVE',0,now(),now())",sectionId,category)
    }
    @Test fun `whole latest title search has exact count bounded keyset and safe summary projection`() {
        repeat(55) { article("Refund latest $it") }
        val first=search(mapOf("query" to " refund ")).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.items.length()").value(50)).andExpect(jsonPath("$.resultCount").value(55))
            .andExpect(jsonPath("$.items[0].latestRevision.title").isString)
            .andExpect(jsonPath("$.items[0].latestRevision.document").doesNotExist()).andExpect(jsonPath("$.items[0].document").doesNotExist())
            .andExpect(jsonPath("$.hasMore").value(true)).andReturn()
        val cursor=mapper.readTree(first.response.contentAsString).get("nextCursor").stringValue()
        val second=search(mapOf("query" to "refund","cursor" to cursor)).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(5)).andExpect(jsonPath("$.resultCount").value(55))
            .andExpect(jsonPath("$.hasMore").value(false)).andReturn()
        val firstIds=mapper.readTree(first.response.contentAsString).get("items").toList().map { it.get("id").stringValue() }
        val secondIds=mapper.readTree(second.response.contentAsString).get("items").toList().map { it.get("id").stringValue() }
        assertThat(firstIds.intersect(secondIds.toSet())).isEmpty()
        search(mapOf("query" to "changed","cursor" to cursor)).andExpect(status().isBadRequest)
        search(mapOf("query" to "refund","lifecycle" to "DRAFT","cursor" to cursor)).andExpect(status().isBadRequest)
        search(mapOf("query" to "refund","cursor" to cursor.dropLast(8)+"tampered")).andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject("select count(*) from admin_security_audit_events where event_type='KNOWLEDGE_ARTICLE_LISTED'",Long::class.java)).isZero()
    }
    @Test fun `latest title excludes history and body with literal and domain filters`() {
        val id=article("Old published title","STAFF")
        val old=jdbc.queryForObject("select id from knowledge_article_revisions where article_id=?",UUID::class.java,id)!!
        jdbc.update("update knowledge_articles set current_published_revision_id=? where id=?",old,id)
        revision(id,"Private %_\\ latest",2)
        search(mapOf("query" to "%_\\","sectionId" to sectionId,"audience" to "STAFF","lifecycle" to "DRAFT"))
            .andExpect(status().isOk).andExpect(jsonPath("$.resultCount").value(1))
            .andExpect(jsonPath("$.items[0].latestRevision.title").value("Private %_\\ latest"))
            .andExpect(jsonPath("$.items[0].currentPublishedRevision.title").value("Old published title"))
            .andExpect(jsonPath("$.items[0].currentPublishedRevision.document").doesNotExist())
        listOf("Old published","secret body","no match").forEach {
            search(mapOf("query" to it)).andExpect(status().isOk).andExpect(jsonPath("$.resultCount").value(0))
        }
        search(mapOf("query" to "Private","audience" to "PUBLIC")).andExpect(status().isOk).andExpect(jsonPath("$.resultCount").value(0))
        mockMvc.perform(get("/api/v1/admin/knowledge/articles").session(browser).header("X-Deskseed-Expected-Staff-Id",adminId))
            .andExpect(status().isOk).andExpect(jsonPath("$.items[0].latestRevision.title").value("Private %_\\ latest"))
    }
    @Test fun `authorization csrf expected actor and bounded query are enforced`() {
        listOf(""," ","x".repeat(255),"control\nquery").forEach { search(mapOf("query" to it)).andExpect(status().isBadRequest) }
        search(mapOf("query" to "ok","lifecycle" to "INVALID")).andExpect(status().isBadRequest)
        search(mapOf("query" to "ok"),expectedActor=UUID.randomUUID()).andExpect(status().isConflict)
        mockMvc.perform(post("/api/v1/admin/knowledge/articles/search").session(browser).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden)
        listOf("AGENT","SECURITY_AUDITOR").forEach { role ->
            val id=staff("$role@example.test",role,role=role)
            search(mapOf("query" to "ok"),login("$role@example.test"),id).andExpect(status().isForbidden)
        }
        val anonymous=mockMvc.perform(get("/api/v1/agent/csrf")).andReturn().request.session as MockHttpSession
        search(mapOf("query" to "ok"),anonymous,adminId).andExpect(status().isUnauthorized)
    }
    @Test fun `successful search stores protected append only query without ticket result membership`(output: CapturedOutput) {
        val query="private-title-unique-1091"
        search(mapOf("query" to query)).andExpect(status().isOk)
        val row=jdbc.queryForMap("select e.action,e.source,e.ticket_number,e.origin_search_event_id,s.query_redacted,s.result_count_relation,c.query_ciphertext from access_audit_events e join search_audit_details s on s.access_event_id=e.id join search_audit_query_ciphertexts c on c.access_event_id=e.id where action='ADMIN_KNOWLEDGE_SEARCH_EXECUTED'")
        assertThat(row).containsEntry("source","ADMIN_UI").containsEntry("query_redacted","[PROTECTED]").containsEntry("result_count_relation","EXACT").containsEntry("ticket_number",null).containsEntry("origin_search_event_id",null)
        assertThat(row["query_ciphertext"] as ByteArray).isNotEmpty()
        assertThat(jdbc.queryForObject("select count(*) from search_audit_query_ciphertexts where expires_at=created_at+interval '30 days'",Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from search_audit_result_items",Long::class.java)).isZero()
        assertThatThrownBy { jdbc.update("delete from search_audit_details") }.isInstanceOf(DataAccessException::class.java)
        assertThat(output.all).doesNotContain(query)
        val auditorId=staff("auditor@example.test","Auditor",role="SECURITY_AUDITOR")
        jdbc.update("insert into staff_authority_grants (id,staff_id,authority,granted_by_staff_id,granted_at) values (?,?,'AUDIT_SEARCH_QUERY_REVEAL',?,now())",UUID.randomUUID(),auditorId,adminId)
        val auditor=login("auditor@example.test")
        val activityId=jdbc.queryForObject("select id from audit_activity_projection where action='ADMIN_KNOWLEDGE_SEARCH_EXECUTED'",UUID::class.java)!!
        mockMvc.perform(get("/api/v1/audit/activities/$activityId").session(auditor).header("X-Interaction-Id",UUID.randomUUID()))
            .andExpect(status().isOk).andExpect(jsonPath("$.protectedContentAvailable").value(false)).andExpect(jsonPath("$.search.queryRedacted").value("[PROTECTED]"))
        val csrf=mockMvc.perform(get("/api/v1/agent/csrf").session(auditor)).andReturn()
        mockMvc.perform(post("/api/v1/audit/activities/$activityId/search-query-reveal").session(auditor).header("X-CSRF-TOKEN",mapper.readTree(csrf.response.contentAsString).get("token").stringValue()).header("X-Interaction-Id",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("""{"reason":"지식 검색 보호 경계 확인"}"""))
            .andExpect(status().isUnprocessableContent)
    }
    @Test fun `audit failure returns unavailable and rolls back audit without query leak`(output: CapturedOutput) {
        val query="sensitive-title-fixture-473"
        jdbc.execute("create function fail_kb_ciphertext() returns trigger language plpgsql as 'begin raise exception ''injected failure''; end'")
        jdbc.execute("create trigger fail_kb_ciphertext before insert on search_audit_query_ciphertexts for each row execute function fail_kb_ciphertext()")
        try {
            val response=search(mapOf("query" to query)).andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.type").value("/problems/audit-write-unavailable")).andReturn().response.contentAsString
            assertThat(response).doesNotContain(query)
            assertThat(output.all).doesNotContain(query)
            assertThat(jdbc.queryForObject("select count(*) from access_audit_events where action='ADMIN_KNOWLEDGE_SEARCH_EXECUTED'",Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("select count(*) from search_audit_details",Long::class.java)).isZero()
        } finally {
            jdbc.execute("drop trigger fail_kb_ciphertext on search_audit_query_ciphertexts")
            jdbc.execute("drop function fail_kb_ciphertext()")
        }
    }
    private fun article(title: String,audience: String="PUBLIC"): UUID {
        val id=UUID.randomUUID()
        jdbc.update("insert into knowledge_articles (id,section_id,slug,lifecycle,audience_type,author_id,created_at,updated_at) values (?,?,?,'DRAFT',?,?,now(),now())",id,sectionId,"article-$id",audience,adminId)
        revision(id,title,1)
        return id
    }
    private fun revision(id: UUID,title: String,number: Int) {
        jdbc.update("insert into knowledge_article_revisions (id,article_id,revision_number,title,document_json,plain_text,summary,content_checksum,created_by_staff_id,created_at) values (?,?,?,?,?::jsonb,'secret body','summary',?,?,now())",UUID.randomUUID(),id,number,title,"""{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"secret body"}]}""","a".repeat(64),adminId)
    }
    private fun staff(email: String, name: String, role: String = "AGENT", status: String = "ACTIVE"): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into staff_accounts (id,email_normalized,email_display,display_name,role,status,password_hash,created_at,updated_at,version) values (?,?,?,?,?,?,?,now(),now(),0)", id,email.lowercase(),email,name,role,status,passwordHash)
        return id
    }

    private fun group(name: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into support_groups (id,name,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())", id,name)
        return id
    }

    private fun login(email: String): MockHttpSession {
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        return mockMvc.perform(post("/api/v1/agent/session")
            .session(csrf.request.session as MockHttpSession).header("X-CSRF-TOKEN", mapper.readTree(csrf.response.contentAsString).get("token").stringValue())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("email" to email, "password" to password))))
            .andExpect(status().isNoContent).andReturn().request.session as MockHttpSession
    }

    private fun search(fields: Map<String,Any>,session: MockHttpSession=browser,expectedActor: UUID=adminId): org.springframework.test.web.servlet.ResultActions {
        val csrf=mockMvc.perform(get("/api/v1/agent/csrf").session(session)).andExpect(status().isOk).andReturn()
        return mockMvc.perform(post("/api/v1/admin/knowledge/articles/search").session(session)
            .header("X-CSRF-TOKEN",mapper.readTree(csrf.response.contentAsString).get("token").stringValue())
            .header("X-Deskseed-Expected-Staff-Id",expectedActor).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(fields+("interactionId" to UUID.randomUUID()))))
    }
}
