package dev.deskseed.platformapi.internal

import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
internal class PlatformProblemWriter(
    private val objectMapper: ObjectMapper,
) {
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: Int,
        type: String,
        title: String,
        detail: String,
        extensions: Map<String, Any?> = emptyMap(),
    ) {
        if (response.isCommitted) return
        response.status = status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader("Cache-Control", "no-store")
        val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString().orEmpty()
        objectMapper.writeValue(
            response.outputStream,
            linkedMapOf<String, Any?>(
                "type" to type,
                "title" to title,
                "status" to status,
                "detail" to detail,
                "requestId" to requestId,
            ) + extensions,
        )
    }
}

