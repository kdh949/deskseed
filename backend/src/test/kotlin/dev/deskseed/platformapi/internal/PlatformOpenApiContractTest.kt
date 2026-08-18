package dev.deskseed.platformapi.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@dev.deskseed.testsupport.category.ContractTest
class PlatformOpenApiContractTest {
    @Test
    fun `frozen v1 exposes only four ticket operations with machine controls`() {
        val contract = Files.readString(Path.of("..", "api", "platform-api-outline-v1.yaml"))

        assertThat(operationIds(contract)).containsExactlyInAnyOrder(
            "platformCreateTicket",
            "platformGetTicket",
            "platformUpdateTicket",
            "platformAddInternalComment",
        )
        assertThat(contract).contains(
            "x-deskseed-network-boundary: private-only",
            "x-deskseed-active-authentication-strategy: OPAQUE_API_KEY",
            "ExternalOAuthTokenAuthenticator is inactive",
            "tickets:create",
            "tickets:read",
            "tickets:update",
            "tickets:comment:internal",
            "name: Idempotency-Key",
            "name: If-Match",
            "X-RateLimit-Remaining",
            "const: INTERNAL",
        )
        assertThat(contract).doesNotContain(
            "operationId: platformAddPublicComment",
            "/admin",
            "/webhooks",
            "oauth2:",
            "createdByStaffId",
        )
    }

    private fun operationIds(contract: String): List<String> = contract.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("operationId:") }
        .map { it.substringAfter(':').trim() }
        .toList()
}
