package dev.deskseed.staffaccess.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

@Component
internal class CustomerConsentRequestSizeFilter(
    private val problemWriter: StaffSecurityProblemWriter,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method !in setOf("POST", "PUT") ||
            !request.requestURI.startsWith("/api/v1/admin/customer-consent-policies")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > MAX_REQUEST_BYTES) {
            invalid(response, request)
            return
        }
        try {
            filterChain.doFilter(BoundedRequest(request), response)
        } catch (_: RequestTooLargeException) {
            if (!response.isCommitted) invalid(response, request)
        }
    }

    private fun invalid(response: HttpServletResponse, request: HttpServletRequest) {
        problemWriter.write(
            response, request, 400, "/problems/customer-consent-request-invalid",
            "고객 동의 정책 요청을 처리할 수 없습니다", "요청 본문이 허용된 크기를 초과했습니다.",
        )
    }

    private class BoundedRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream = BoundedServletInputStream(super.getInputStream())

        override fun getReader(): BufferedReader = BufferedReader(
            InputStreamReader(inputStream, characterEncoding?.let(Charset::forName) ?: StandardCharsets.UTF_8),
        )
    }

    private class BoundedServletInputStream(
        private val delegate: ServletInputStream,
    ) : ServletInputStream() {
        private var consumed = 0L

        override fun read(): Int = delegate.read().also { value ->
            if (value >= 0) consume(1)
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            delegate.read(bytes, offset, length).also { count ->
                if (count > 0) consume(count)
            }

        override fun isFinished(): Boolean = delegate.isFinished
        override fun isReady(): Boolean = delegate.isReady
        override fun setReadListener(readListener: ReadListener?) = delegate.setReadListener(readListener)

        private fun consume(count: Int) {
            consumed += count
            if (consumed > MAX_REQUEST_BYTES) throw RequestTooLargeException()
        }
    }

    private class RequestTooLargeException : IOException()

    private companion object {
        const val MAX_REQUEST_BYTES = 262_144L
    }
}
