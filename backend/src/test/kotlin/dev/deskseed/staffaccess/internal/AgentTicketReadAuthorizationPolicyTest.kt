package dev.deskseed.staffaccess.internal

import dev.deskseed.ticketing.StaffTicketReadScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentTicketReadAuthorizationPolicyTest {
    private val policy = AgentTicketReadAuthorizationPolicy()

    @Test
    fun `ALL_TICKETS makes relation grant redundant`() {
        assertThat(
            policy.canRead(
                scope = StaffTicketReadScope.ALL_TICKETS,
                directGrant = false,
                relationGrant = false,
            ),
        ).isTrue()
    }

    @Test
    fun `relation grant remains usable by a future restrictive mode`() {
        assertThat(
            policy.canRead(
                scope = StaffTicketReadScope.OWN_GROUPS,
                directGrant = false,
                relationGrant = true,
            ),
        ).isTrue()
        assertThat(
            policy.canRead(
                scope = StaffTicketReadScope.OWN_GROUPS,
                directGrant = false,
                relationGrant = false,
            ),
        ).isFalse()
    }
}
