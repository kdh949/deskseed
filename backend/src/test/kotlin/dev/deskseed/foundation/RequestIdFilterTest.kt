package dev.deskseed.foundation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@dev.deskseed.testsupport.category.FastTest
class RequestIdFilterTest {
    private val filter = RequestIdFilter()

    @Test
    fun `uses valid request and correlation identifiers`() {
        val request = MockHttpServletRequest().apply {
            addHeader(RequestIdFilter.REQUEST_ID_HEADER, "request-123")
            addHeader(RequestIdFilter.CORRELATION_ID_HEADER, "correlation-456")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo("request-123")
        assertThat(request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE)).isEqualTo("correlation-456")
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("request-123")
        assertThat(response.getHeader(RequestIdFilter.CORRELATION_ID_HEADER)).isEqualTo("correlation-456")
    }

    @Test
    fun `replaces malformed identifiers with server generated values`() {
        val request = MockHttpServletRequest().apply {
            addHeader(RequestIdFilter.REQUEST_ID_HEADER, "bad\nrequest")
            addHeader(RequestIdFilter.CORRELATION_ID_HEADER, "bad correlation")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString())
            .matches("[A-Za-z0-9._:-]{1,100}")
        assertThat(request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString())
            .matches("[A-Za-z0-9._:-]{1,100}")
    }
}
