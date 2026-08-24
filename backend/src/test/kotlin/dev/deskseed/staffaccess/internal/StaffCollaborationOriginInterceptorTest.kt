package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.socket.handler.TextWebSocketHandler

@dev.deskseed.testsupport.category.FastTest
class StaffCollaborationOriginInterceptorTest {
    private val handler = object : TextWebSocketHandler() {}
    private val interceptor = StaffCollaborationOriginInterceptor("https://staff.deskseed.test")

    @Test
    fun `only configured staff origin can start the cookie authenticated handshake`() {
        val allowedResponse = MockHttpServletResponse()
        val deniedResponse = MockHttpServletResponse()

        assertThat(interceptor.beforeHandshake(
            request("https://staff.deskseed.test"),
            ServletServerHttpResponse(allowedResponse),
            handler,
            mutableMapOf(),
        )).isTrue()
        assertThat(interceptor.beforeHandshake(
            request("https://attacker.example"),
            ServletServerHttpResponse(deniedResponse),
            handler,
            mutableMapOf(),
        )).isFalse()
        assertThat(deniedResponse.status).isEqualTo(403)
    }

    private fun request(origin: String) = ServletServerHttpRequest(
        MockHttpServletRequest("GET", "/ws/agent/collaboration").apply {
            addHeader(HttpHeaders.ORIGIN, origin)
        },
    )
}
