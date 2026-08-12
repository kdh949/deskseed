package dev.deskseed.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class IntegrationAuthorizationPolicyTest {
    private val policy = IntegrationAuthorizationPolicy()
    private val groupA = UUID.randomUUID()
    private val groupB = UUID.randomUUID()

    @Test
    fun `scope and every configured resource constraint must intersect`() {
        val principal = principal(
            scopes = setOf(IntegrationScope.TICKETS_READ, IntegrationScope.TICKETS_UPDATE),
            constraints = IntegrationResourceConstraints(
                allowedGroupIds = setOf(groupA),
                allowedTicketKinds = setOf(IntegrationTicketKind.CUSTOMER_REQUEST),
                allowedFields = setOf(IntegrationTicketField.STATUS, IntegrationTicketField.PRIORITY),
            ),
        )

        assertThat(
            policy.isAllowed(
                principal,
                IntegrationScope.TICKETS_UPDATE,
                IntegrationResourceRequest(
                    groupId = groupA,
                    ticketKind = IntegrationTicketKind.CUSTOMER_REQUEST,
                    fields = setOf(IntegrationTicketField.STATUS),
                ),
            ),
        ).isTrue()
        assertThat(policy.isAllowed(principal, IntegrationScope.TICKETS_CREATE, IntegrationResourceRequest(groupId = groupA)))
            .isFalse()
        assertThat(policy.isAllowed(principal, IntegrationScope.TICKETS_READ, IntegrationResourceRequest(groupId = groupB)))
            .isFalse()
        assertThat(
            policy.isAllowed(
                principal,
                IntegrationScope.TICKETS_READ,
                IntegrationResourceRequest(ticketKind = IntegrationTicketKind.INTERNAL_TASK),
            ),
        ).isFalse()
        assertThat(
            policy.isAllowed(
                principal,
                IntegrationScope.TICKETS_READ,
                IntegrationResourceRequest(ticketKind = IntegrationTicketKind.CUSTOMER_REQUEST),
            ),
        ).isFalse()
        assertThat(
            policy.isAllowed(
                principal,
                IntegrationScope.TICKETS_READ,
                IntegrationResourceRequest(groupId = groupA),
            ),
        ).isFalse()
        assertThat(
            policy.isAllowed(
                principal,
                IntegrationScope.TICKETS_UPDATE,
                IntegrationResourceRequest(fields = setOf(IntegrationTicketField.ASSIGNEE_ID)),
            ),
        ).isFalse()
    }

    @Test
    fun `absent configured constraints are unrestricted but never add a missing scope`() {
        val principal = principal(
            scopes = setOf(IntegrationScope.TICKETS_COMMENT_INTERNAL),
            constraints = IntegrationResourceConstraints(),
        )
        assertThat(
            policy.isAllowed(
                principal,
                IntegrationScope.TICKETS_COMMENT_INTERNAL,
                IntegrationResourceRequest(groupId = groupB, ticketKind = IntegrationTicketKind.INTERNAL_TASK),
            ),
        ).isTrue()
        assertThat(policy.isAllowed(principal, IntegrationScope.TICKETS_READ, IntegrationResourceRequest())).isFalse()
    }

    private fun principal(
        scopes: Set<IntegrationScope>,
        constraints: IntegrationResourceConstraints,
    ) = AuthenticatedIntegrationClient(
        id = UUID.randomUUID(),
        name = "orders",
        scopes = scopes,
        resourceConstraints = constraints,
        credentialId = UUID.randomUUID(),
    )
}
