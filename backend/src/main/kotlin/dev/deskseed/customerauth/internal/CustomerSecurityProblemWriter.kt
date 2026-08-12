package dev.deskseed.customerauth.internal

import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
internal class CustomerSecurityProblemWriter(private val objectMapper: ObjectMapper) {
    fun write(
        response: HttpServletResponse,
        request: HttpServletRequest,
        status: Int,
        type: String,
        title: String,
        detail: String,
    ) {
        response.status = status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Referrer-Policy", "no-referrer")
        objectMapper.writeValue(
            response.outputStream,
            mapOf(
                "type" to type,
                "title" to title,
                "status" to status,
                "detail" to detail,
                "instance" to request.requestURI,
                "requestId" to request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString(),
            ),
        )
    }
}
