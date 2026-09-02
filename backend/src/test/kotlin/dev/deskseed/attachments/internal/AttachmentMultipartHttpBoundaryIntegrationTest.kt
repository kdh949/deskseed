package dev.deskseed.attachments.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "spring.servlet.multipart.max-file-size=20MB",
        "spring.servlet.multipart.max-request-size=105MB",
    ],
)
@dev.deskseed.testsupport.category.SlowTest
class AttachmentMultipartHttpBoundaryIntegrationTest {
    @LocalServerPort private var port = 0

    @Test
    fun `HTTP multipart accepts an attachment larger than servlet defaults`() {
        val bytes = ByteArray(11 * 1024 * 1024).also { payload ->
            "%PDF-1.7\n".toByteArray().copyInto(payload)
        }
        val form = LinkedMultiValueMap<String, Any>().apply {
            add("name", "대용량 첨부 고객")
            add("email", "large-${UUID.randomUUID()}@example.test")
            add("subject", "multipart HTTP 경계")
            add("message", "기본 Servlet 10 MB request 한도를 넘는 첨부")
            add(
                "attachments",
                object : ByteArrayResource(bytes) {
                    override fun getFilename(): String = "large.pdf"
                },
            )
        }

        val response = RestClient.create("http://127.0.0.1:$port")
            .post()
            .uri("/api/v1/requests")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(form)
            .retrieve()
            .toBodilessEntity()

        assertThat(response.statusCode.value()).isEqualTo(201)
    }
}
