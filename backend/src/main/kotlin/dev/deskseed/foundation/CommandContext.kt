package dev.deskseed.foundation

import jakarta.servlet.http.HttpServletRequest
import java.util.UUID

enum class ActorType {
    CUSTOMER,
    STAFF,
    INTEGRATION_CLIENT,
    TRIGGER,
    AUTOMATION,
    SYSTEM,
}

data class ActorRef(
    val actorType: ActorType,
    val actorId: UUID?,
)

enum class RequestSource {
    CUSTOMER_PORTAL,
    AGENT_UI,
    ADMIN_UI,
    PLATFORM_API,
    TRIGGER,
    AUTOMATION,
    SYSTEM_JOB,
}

data class CommandContext(
    val source: RequestSource,
    val requestId: String,
    val correlationId: String,
    val commandId: String,
) {
    init {
        require(RequestIdFilter.isValidIdentifier(requestId)) { "requestId must be a bounded identifier" }
        require(RequestIdFilter.isValidIdentifier(correlationId)) { "correlationId must be a bounded identifier" }
        require(RequestIdFilter.isValidIdentifier(commandId)) { "commandId must be a bounded identifier" }
    }
}

object CommandContexts {
    fun from(request: HttpServletRequest, source: RequestSource): CommandContext = CommandContext(
        source = source,
        requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString()
            ?: UUID.randomUUID().toString(),
        correlationId = request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE)?.toString()
            ?: UUID.randomUUID().toString(),
        commandId = UUID.randomUUID().toString(),
    )
}
